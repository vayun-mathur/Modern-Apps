package com.vayunmathur.openassistant.platform

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.vayunmathur.library.log.Log
import com.vayunmathur.library.ml.Gemma4AudioHandle
import com.vayunmathur.library.ml.Gemma4Handle
import com.vayunmathur.library.ml.Gemma4VisionHandle
import com.vayunmathur.library.ml.Sampling
import com.vayunmathur.openassistant.domain.ChatGenerator
import com.vayunmathur.openassistant.domain.ToolLoopOutcome
import com.vayunmathur.openassistant.domain.decodeWavMono16k
import com.vayunmathur.openassistant.domain.fitConversation
import com.vayunmathur.openassistant.domain.runToolLoop
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

/** One stored message, text only: media is only ever attached to the current turn. */
data class HistoryTurn(val fromUser: Boolean, val text: String)

data class ChatRequest(
    val system: String,
    val history: List<HistoryTurn>,
    val userText: String,
    val imagePaths: List<String> = emptyList(),
    val audioPath: String? = null,
)

sealed interface ChatResult {
    data class Reply(val text: String, val halted: Boolean) : ChatResult
    data object TooLong : ChatResult
    data object Unavailable : ChatResult
}

/**
 * Gemma 4 on the maml runtime, replacing LiteRT-LM's Engine/Conversation.
 *
 * The handles are not thread-safe, so every JNI call runs on one dedicated thread and a
 * [Mutex] covers a whole turn, tool rounds included. The vision and audio towers load on
 * first use and close after [TOWER_IDLE_MILLIS] without one.
 */
class MamlChatEngine(private val modelDir: File) : AutoCloseable {
    private val executor = Executors.newSingleThreadExecutor { Thread(it, "maml-engine") }
    private val engineThread = executor.asCoroutineDispatcher()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val mutex = Mutex()

    private var handle: Gemma4Handle? = null
    private var vision: Gemma4VisionHandle? = null
    private var audio: Gemma4AudioHandle? = null
    private var towerIdleJob: Job? = null
    private var prefixKey: Int? = null

    /** Loads the model if needed; false when it cannot come up. */
    suspend fun load(): Boolean = mutex.withLock { withContext(engineThread) { model() != null } }

    suspend fun chat(request: ChatRequest, tools: AssistantTools, onText: (String) -> Boolean): ChatResult =
        mutex.withLock {
            val model = withContext(engineThread) { model() } ?: return@withLock ChatResult.Unavailable
            val declarations = tools.declarations
            val current = withContext(engineThread) { currentTurn(request) }
            val history = request.history.map {
                Gemma4Handle.Turn(if (it.fromUser) Gemma4Handle.Role.USER else Gemma4Handle.Role.MODEL, it.text)
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
                    model.generate(conversation, request.system, declarations, CHAT_REPLY_LIMIT, continuation, sampling, onRaw)
                }
            }
            val outcome = runToolLoop(
                generator = generator,
                executeTool = { call ->
                    withContext(Dispatchers.IO) { tools.invoke(call) }.also {
                        Log.status(TAG, "tool ${call.name} -> ${it.take(LOG_CHARS)}")
                    }
                },
                renderToolResponse = Gemma4Handle::renderToolResponse,
                onVisible = onText,
            )
            when (outcome) {
                is ToolLoopOutcome.Finished -> ChatResult.Reply(outcome.text, halted = false)
                is ToolLoopOutcome.Halted -> ChatResult.Reply(outcome.text, halted = true)
                is ToolLoopOutcome.Failed -> ChatResult.Unavailable
            }
        }

    /** Single-turn structured extraction: greedy, no tools, no history. Null when unavailable. */
    suspend fun extract(system: String, userText: String, imagePaths: List<String>, onText: (String) -> Boolean): String? =
        mutex.withLock {
            withContext(engineThread) {
                val model = model() ?: return@withContext null
                val turn = currentTurn(ChatRequest(system, emptyList(), userText, imagePaths))
                model.generate(listOf(turn), system, emptyList(), EXTRACT_REPLY_LIMIT, "", Sampling.Greedy, onText)
            }
        }

    private fun model(): Gemma4Handle? {
        handle?.takeIf { it.isAvailable }?.let { return it }
        handle?.close()
        val opened = Gemma4Handle.inDirectory(modelDir)
        if (!opened.isAvailable) {
            Log.status(TAG, "gemma4 did not load from $modelDir")
            opened.close()
            handle = null
            return null
        }
        handle = opened
        prefixKey = null
        return opened
    }

    private fun seedPrefix(model: Gemma4Handle, system: String, tools: List<Gemma4Handle.ToolDeclaration>) {
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

    private fun encodeImages(paths: List<String>): List<FloatArray> {
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

    override fun close() {
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

    private companion object {
        const val TAG = "MamlChatEngine"
        const val CHAT_REPLY_LIMIT = 1024
        const val EXTRACT_REPLY_LIMIT = 1024
        const val TOWER_IDLE_MILLIS = 3 * 60_000L
        const val MAX_IMAGE_SIDE = 1024
        const val LOG_CHARS = 120

        fun decodeDownscaled(path: String): Bitmap? {
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
