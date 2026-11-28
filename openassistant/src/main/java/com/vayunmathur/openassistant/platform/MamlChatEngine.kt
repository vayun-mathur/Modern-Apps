package com.vayunmathur.openassistant.platform

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.vayunmathur.library.log.Log
import com.vayunmathur.library.ml.Gemma4AudioHandle
import com.vayunmathur.library.ml.Gemma4Handle
import com.vayunmathur.library.ml.Gemma4VisionHandle
import com.vayunmathur.library.ml.Sampling
import com.vayunmathur.library.ml.shortFiles
import com.vayunmathur.openassistant.domain.ChatGenerator
import com.vayunmathur.openassistant.domain.ParsedToolCall
import com.vayunmathur.openassistant.domain.ToolLoopOutcome
import com.vayunmathur.openassistant.domain.decodeWavMono16k
import com.vayunmathur.openassistant.domain.fitConversation
import com.vayunmathur.openassistant.domain.runToolLoop
import com.vayunmathur.openassistant.domain.withA2uiBlock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.Executors

/**
 * One stored message. A user turn keeps the paths of the photos attached to
 * it, so the picture stays part of the conversation: the engine re-encodes
 * them with the vision tower on each turn and [fitConversation] drops the
 * oldest turns (images included) when the whole history no longer fits.
 * Dropping them here instead made the model answer as if no photo had ever
 * been sent (#929).
 */
data class HistoryTurn(val fromUser: Boolean, val text: String, val imagePaths: List<String> = emptyList())

/** Immutable value describing chat request. */
data class ChatRequest(
    val system: String,
    val history: List<HistoryTurn>,
    val userText: String,
    val imagePaths: List<String> = emptyList(),
    val audioPath: String? = null,
)

/** Contract for chat result. */
sealed interface ChatResult {
    /** Immutable value describing reply; carries text, halted. */
    data class Reply(val text: String, val halted: Boolean) : ChatResult
    /** A WRITE_CONFIRM call awaits host confirmation; nothing was executed. */
    data class Pending(val pendingId: String, val summary: String, val text: String = "") : ChatResult
    /** Singleton holding too long state and operations. */
    data object TooLong : ChatResult
    /** Singleton holding unavailable state and operations. */
    data object Unavailable : ChatResult
}

/**
 * Gemma 4 on the maml runtime, replacing LiteRT-LM's Engine/Conversation.
 *
 * The handles are not thread-safe, so every JNI call runs on one dedicated thread and a
 * [Mutex] covers a whole turn, tool rounds included. The vision and audio towers load on
 * first use and close after [TOWER_IDLE_MILLIS] without one.
 */
open class MamlChatEngine(private val modelDir: File) : AutoCloseable {
    private val executor = Executors.newSingleThreadExecutor { Thread(it, "maml-engine") }
    private val engineThread = executor.asCoroutineDispatcher()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val mutex = Mutex()

    private var handle: TextModel? = null

    /**
     * Behavior-preserving test seam: production opens the real Gemma4 handle
     * via [openRealTextModel]; tests inject a Kotlin fake because the native
     * model cannot load on the JVM. Null factory result means unavailable,
     * with the second element carrying the load error to surface.
     */
    internal var textModelOpener: ((File) -> Pair<TextModel?, String?>)? = null

    /**
     * Behavior-preserving test seam for the vision tower: production leaves
     * this null and encodes through [Gemma4VisionHandle]; tests substitute a
     * fake encoder because the tower cannot load on the JVM. A null result
     * drops that image, like a failed encode does in production.
     */
    internal var imageEncoder: ((String) -> FloatArray?)? = null

    /** The text-generation surface [MamlChatEngine] needs from the model. */
    internal interface TextModel {
        val available: Boolean
        fun loadPrefix(prefix: String): Boolean
        fun positionsFor(
            conversation: List<Gemma4Handle.Turn>,
            system: String?,
            tools: List<Gemma4Handle.ToolDeclaration>,
            limit: Int,
        ): Int
        fun generate(
            conversation: List<Gemma4Handle.Turn>,
            system: String?,
            tools: List<Gemma4Handle.ToolDeclaration>,
            limit: Int,
            continuation: String,
            sampling: Sampling,
            onToken: (String) -> Boolean,
        ): String?
        fun close()
    }

    private class RealTextModel(private val real: Gemma4Handle) : TextModel {
        override val available: Boolean get() = real.isAvailable
        override fun loadPrefix(prefix: String): Boolean = real.loadPrefix(prefix)
        override fun positionsFor(
            conversation: List<Gemma4Handle.Turn>,
            system: String?,
            tools: List<Gemma4Handle.ToolDeclaration>,
            limit: Int,
        ): Int = real.positionsFor(conversation, system, tools, limit)
        override fun generate(
            conversation: List<Gemma4Handle.Turn>,
            system: String?,
            tools: List<Gemma4Handle.ToolDeclaration>,
            limit: Int,
            continuation: String,
            sampling: Sampling,
            onToken: (String) -> Boolean,
        ): String? = real.generate(conversation, system, tools, limit, continuation, sampling, onToken)
        override fun close() = real.close()
    }

    private fun openRealTextModel(dir: File): Pair<TextModel?, String?> {
        val opened = Gemma4Handle.inDirectory(dir)
        if (!opened.isAvailable) {
            val short = shortFiles(dir).takeIf { it.isNotEmpty() }
                ?.let { "short files: ${it.joinToString()}" }
            val error = listOfNotNull(short, opened.loadError).joinToString("; ")
            opened.close()
            return null to error
        }
        return RealTextModel(opened) to null
    }
    private var vision: Gemma4VisionHandle? = null
    private var audio: Gemma4AudioHandle? = null
    private var towerIdleJob: Job? = null
    private var prefixKey: Int? = null

    /** Loads the model if needed; false when it cannot come up. */
    open suspend fun load(): Boolean = mutex.withLock { withContext(engineThread) { model() != null } }

    /** Why the last load failed, for the error the user sees. Null when loaded. */
    open suspend fun loadError(): String? = mutex.withLock { lastLoadError }

    open suspend fun chat(request: ChatRequest, tools: AssistantTools, onText: (String) -> Boolean): ChatResult =
        mutex.withLock {
            val model = withContext(engineThread) { model() } ?: return@withLock ChatResult.Unavailable
            val declarations = tools.declarations
            // History turns are built on the engine thread too: encoding a
            // kept photo runs the vision tower, which is not thread-safe.
            val (current, history) = withContext(engineThread) {
                val builtHistory = request.history.map { historyTurn(it) }
                currentTurn(request) to builtHistory
            }
            val conversation = withContext(engineThread) {
                seedPrefix(model, request.system, declarations)
                val ceiling = Gemma4Handle.promptCeiling(CHAT_REPLY_LIMIT)
                fitConversation(history, current, { it.role == Gemma4Handle.Role.USER }) {
                    model.positionsFor(it, request.system, declarations, CHAT_REPLY_LIMIT) <= ceiling
                }
            } ?: return@withLock ChatResult.TooLong
            if (conversation.size < history.size + 1) {
                Log.status(TAG, "dropped ${history.size + 1 - conversation.size} oldest turns to fit")
            }
            val sampling = Sampling.Chat.copy(seed = System.nanoTime())
            val generator = ChatGenerator { continuation, onRaw ->
                withContext(engineThread) {
                    model.generate(
                        conversation, request.system, declarations, CHAT_REPLY_LIMIT, continuation, sampling, onRaw,
                    )
                }
            }
            val outcome = runToolLoop(
                generator = generator,
                parseCall = { reply ->
                    Gemma4Handle.parseToolCall(reply)?.let { ParsedToolCall(it.name, it.arguments) }
                },
                executeTool = { call ->
                    // Emit progress the moment a tool starts, not when its
                    // whole round-trip returns: a web search can sit on
                    // socket timeouts for most of a minute, and without this
                    // the first visible feedback waited for the tool result
                    // plus the next generation round. The next generated
                    // token overwrites this with the cumulative reply text.
                    onText(toolProgressLabel(call.name))
                    withContext(Dispatchers.IO) { tools.invoke(call) }.also {
                        Log.status(TAG, "tool ${call.name} -> ${it.take(LOG_CHARS)}")
                    }
                },
                renderToolResponse = Gemma4Handle::renderToolResponse,
                pendingFor = { call -> tools.createPending(call) },
                onVisible = onText,
            )
            when (outcome) {
                is ToolLoopOutcome.Finished -> {
                    // A render_ui call stashed its envelope on the tools (it cannot write
                    // the DB); append it as an a2ui fence so storeReply/splitA2ui persists
                    // it on this assistant message.
                    val pendingUi = tools.consumePendingRenderUi()
                    val text = if (pendingUi != null) withA2uiBlock(outcome.text, pendingUi) else outcome.text
                    ChatResult.Reply(text, halted = false)
                }
                is ToolLoopOutcome.Halted -> ChatResult.Reply(outcome.text, halted = true)
                is ToolLoopOutcome.Failed -> ChatResult.Unavailable
                is ToolLoopOutcome.PendingConfirmation -> ChatResult.Pending(
                    pendingId = outcome.pendingId,
                    summary = tools.pendingById(outcome.pendingId)?.summary ?: outcome.callName,
                    text = outcome.text,
                )
            }
        }

    /** Single-turn structured extraction: greedy, no tools, no history. Null when unavailable. */
    open suspend fun extract(
        system: String,
        userText: String,
        imagePaths: List<String>,
        onText: (String) -> Boolean,
    ): String? =
        mutex.withLock {
            withContext(engineThread) {
                val model = model() ?: return@withContext null
                val turn = currentTurn(ChatRequest(system, emptyList(), userText, imagePaths))
                model.generate(listOf(turn), system, emptyList(), EXTRACT_REPLY_LIMIT, "", Sampling.Greedy, onText)
            }
        }

    private fun model(): TextModel? {
        handle?.takeIf { it.available }?.let { return it }
        handle?.close()
        // Carry the cause here so the failure the user sees says *which*
        // file broke, not just that the directory did not load (#901's
        // undifferentiated error): short-file sizes first, then whatever
        // native logged (already in logcat) via the handle's report.
        val (opened, error) = (textModelOpener ?: { dir -> openRealTextModel(dir) })(modelDir)
        if (opened == null) {
            handle = null
            lastLoadError = error
            return null
        }
        handle = opened
        lastLoadError = null
        prefixKey = null
        return opened
    }

    /**
     * Why the last [model] attempt failed, for the error the user sees.
     * Null when the model is up or no load has been attempted yet.
     */
    var lastLoadError: String? = null
        private set

    private fun seedPrefix(model: TextModel, system: String, tools: List<Gemma4Handle.ToolDeclaration>) {
        val declared = Gemma4Handle.declareTools(tools)
        val key = (system + "\u0000" + declared).hashCode()
        if (key == prefixKey) return
        prefixKey = key
        if (!model.loadPrefix("<bos><|turn>system\n$system$declared<turn|>\n")) {
            Log.status(TAG, "no matching baked prefix; the system block will be prefilled")
        }
    }

    private fun currentTurn(request: ChatRequest): Gemma4Handle.Turn {
        val images = if (request.imagePaths.isEmpty()) emptyList() else encodeImages(request.imagePaths)
        val clips = request.audioPath?.let { encodeAudio(it) }?.let { listOf(it) } ?: emptyList()
        if (request.imagePaths.isNotEmpty() || request.audioPath != null) scheduleTowerUnload()
        return Gemma4Handle.Turn(Gemma4Handle.Role.USER, request.userText, images, clips)
    }

    /** A stored turn as the model sees it, attached photos encoded as vision inputs. */
    private fun historyTurn(turn: HistoryTurn): Gemma4Handle.Turn {
        val images = if (turn.imagePaths.isEmpty()) emptyList() else encodeImages(turn.imagePaths)
        if (turn.imagePaths.isNotEmpty()) scheduleTowerUnload()
        return Gemma4Handle.Turn(
            if (turn.fromUser) Gemma4Handle.Role.USER else Gemma4Handle.Role.MODEL,
            turn.text,
            images,
        )
    }

    private fun encodeImages(paths: List<String>): List<FloatArray> {
        imageEncoder?.let { encode -> return paths.mapNotNull(encode) }
        val tower = vision?.takeIf { it.isAvailable }
            ?: Gemma4VisionHandle.inDirectory(modelDir).also { vision = it }
        if (!tower.isAvailable) {
            Log.status(TAG, "vision tower unavailable; dropping ${paths.size} images")
            return emptyList()
        }
        return paths.mapNotNull { path ->
            val bitmap = decodeDownscaled(path) ?: return@mapNotNull null
            tower.encode(bitmap, Gemma4VisionHandle.SOFT_TOKENS_DEFAULT).also { bitmap.recycle() }
        }
    }

    private fun encodeAudio(path: String): FloatArray? {
        val file = File(path)
        if (!file.isFile) return null
        val samples = runCatching { decodeWavMono16k(file.readBytes()) }.getOrNull()
        if (samples == null) {
            Log.status(TAG, "$path is not 16 kHz mono 16-bit PCM")
            return null
        }
        val tower = audio?.takeIf { it.isAvailable }
            ?: Gemma4AudioHandle.inDirectory(modelDir).also { audio = it }
        if (!tower.isAvailable) {
            Log.status(TAG, "audio tower unavailable; dropping the clip")
            return null
        }
        return tower.encode(samples)
    }

    private fun scheduleTowerUnload() {
        towerIdleJob?.cancel()
        towerIdleJob = scope.launch {
            delay(TOWER_IDLE_MILLIS)
            mutex.withLock {
                withContext(engineThread) {
                    vision?.close()
                    vision = null
                    audio?.close()
                    audio = null
                }
            }
        }
    }

    open override fun close() {
        scope.cancel()
        executor.execute {
            vision?.close()
            audio?.close()
            handle?.close()
            vision = null
            audio = null
            handle = null
        }
        executor.shutdown()
    }

    internal companion object {
        /** Short progress line shown while [toolName] executes, before its result streams back. */
        internal fun toolProgressLabel(toolName: String): String = when (toolName) {
            "web_search" -> "Searching the web…"
            "web_read", "web_read_result" -> "Reading a page…"
            "get_memories" -> "Checking memory…"
            else -> "Using a tool…"
        }

        const val TAG = "MamlChatEngine"
        const val CHAT_REPLY_LIMIT = 1024
        const val EXTRACT_REPLY_LIMIT = 1024
        const val TOWER_IDLE_MILLIS = 3 * 60_000L
        const val MAX_IMAGE_SIDE = 1024
        const val LOG_CHARS = 120

        internal fun decodeDownscaled(path: String): Bitmap? {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(path, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
                Log.status(TAG, "cannot decode $path")
                return null
            }
            var sample = 1
            while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= MAX_IMAGE_SIDE) sample *= 2
            val options = BitmapFactory.Options().apply {
                inSampleSize = sample
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
            return runCatching { BitmapFactory.decodeFile(path, options) }.getOrNull()
        }
    }
}
