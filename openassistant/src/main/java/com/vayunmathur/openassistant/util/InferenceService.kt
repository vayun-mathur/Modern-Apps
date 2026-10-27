package com.vayunmathur.openassistant.util

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.os.ResultReceiver
import com.vayunmathur.library.log.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.IntentCompat
import com.vayunmathur.library.util.SecureResultReceiver
import com.vayunmathur.library.util.DataStoreUtils
import com.vayunmathur.library.downloadservice.ModelUrls
import com.vayunmathur.openassistant.platform.AssistantTools
import com.vayunmathur.openassistant.platform.ChatRequest
import com.vayunmathur.openassistant.platform.ChatResult
import com.vayunmathur.openassistant.platform.HistoryTurn
import com.vayunmathur.openassistant.platform.MamlChatEngine
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.File
import java.util.concurrent.atomic.AtomicReference
import kotlin.time.Clock
import com.vayunmathur.openassistant.data.Message as DbMessage
import com.vayunmathur.openassistant.data.OpenAssistantRepository
import com.vayunmathur.openassistant.R
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.selects.select

class InferenceService : Service() {

    companion object {
        var newTitle: String? = null
        var halt: Boolean = false

        /** DataStore key holding the user-editable chat system prompt. */
        const val KEY_SYSTEM_PROMPT = "system_prompt"
        internal const val EMBEDDING_ERROR_CODE = -1
        internal const val EMBEDDING_DOWNLOADING_CODE = 2
        internal const val EMBEDDING_OK_CODE = 0
        private const val INTENT_TIMEOUT_MILLIS = 45000L
        private const val INTENT_QUEUE_TTL_MILLIS = 45000L
        private const val HISTORY_RECENCY_MILLIS = 1000L
        private const val DB_WRITE_INTERVAL_MILLIS = 100L

        /** The system prompt used when the user has not set a custom one. */
        val DEFAULT_SYSTEM_PROMPT = """
            You are a helpful Android assistant.
            On the first request the user sends to you, you MUST define a title for the conversation. You may optionally change the title if the topic of conversation changes sufficiently.

            TOOL USE GUIDELINES:
            - Only use a tool when the user's request clearly and directly relates to that tool's purpose. Do NOT guess or speculatively call tools on short or ambiguous prompts.
            - If the user asks a general knowledge question (e.g. "what is...", "how does...", "tell me about..."), answer from your own knowledge. Do not invoke app tools unless the user explicitly asks to interact with an app.
            - If a tool call fails because the required app is not installed, do NOT stop. Continue helping the user by answering from your own knowledge or suggesting alternatives.

            MEMORY:
            You have a memory feature. Use it aggressively to provide a personalized and consistent experience:
            - Whenever the user shares ANY information that might conceivably be useful in future conversations (e.g., their name, preferences, family details, interests, opinions, routines, or important facts), use 'add_to_memory' to store it immediately.
            - At the start of every conversation and whenever the user asks a question or makes a request where past context could even remotely be relevant, use 'get_memories' to retrieve stored information.
            - If a stored memory is no longer accurate or requested to be forgotten, use 'remove_memory'.
            - When in doubt about whether to use memory, USE IT.
            """.trimIndent()
    }

    private sealed class InferenceJob {
        data class Intent(
            val userText: String,
            val imagePaths: Array<String>,
            val schema: String,
            val receiver: ResultReceiver,
            val enqueuedTime: Long = System.currentTimeMillis(),
        ) : InferenceJob()

        data class Standard(
            val conversationId: Long,
            val userText: String,
            val imagePaths: Array<String>,
            val audioPath: String?,
        ) : InferenceJob()

        /**
         * A SigLIP2 embedding request from another app (photos). [mode] is
         * "text", "image", or "info". Runs on [SiglipEmbedder], independent of
         * the chat [MamlChatEngine], so it can be served while the LLM is busy.
         */
        data class Embedding(
            val mode: String,
            val userText: String,
            val imagePath: String?,
            val receiver: ResultReceiver,
        ) : InferenceJob()
    }

    /** Public aliases so [InferenceEmbeddingHandler] can consume the queue types. */
    object InferenceJobPublic {
        data class Embedding(
            val mode: String,
            val userText: String,
            val imagePath: String?,
            val receiver: ResultReceiver,
        )
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val standardQueue = Channel<InferenceJob.Standard>(Channel.UNLIMITED)
    private val intentQueue = Channel<InferenceJob.Intent>(Channel.UNLIMITED)
    private val embeddingQueue = Channel<InferenceJobPublic.Embedding>(Channel.UNLIMITED)

    private val embeddingHandler by lazy {
        InferenceEmbeddingHandler(applicationContext, serviceScope)
    }

    private var engine: MamlChatEngine? = null
    private val engineLock = Mutex()
    private val tools by lazy { AssistantTools(applicationContext, memoryDao) }

    private val repository by lazy { OpenAssistantRepository.get(applicationContext) }
    private val conversationDao get() = repository.conversationDaoRef
    private val messageDao get() = repository.messageDaoRef
    private val memoryDao get() = repository.memoryDaoRef

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        startForegroundTask()
        launchPrewarm()
        launchJobLoop()
        launchEmbeddingLoop()
    }

    private fun launchPrewarm() {
        serviceScope.launch {
            try {
                ensureEngineInitialized()
                engine?.warmUp(systemPrompt(), tools)
            } catch (expected: Exception) {
                Log.error("InferenceService", "Error pre-warming engine", expected)
            }
        }
    }

    private fun launchJobLoop() {
        serviceScope.launch {
            while (isActive) {
                try {
                    if (drainReady()) continue
                    select<Unit> {
                        standardQueue.onReceive { executeStandardInference(it) }
                        intentQueue.onReceive { processIntentJob(it) }
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (expected: Exception) {
                    Log.error("InferenceService", "Critical error in job processor loop", expected)
                }
            }
        }
    }

    private suspend fun drainReady(): Boolean {
        val standardJob = standardQueue.tryReceive().getOrNull()
        if (standardJob != null) {
            executeStandardInference(standardJob)
            return true
        }
        val intentJob = intentQueue.tryReceive().getOrNull()
        if (intentJob != null) {
            processIntentJob(intentJob)
            return true
        }
        return false
    }

    // Embeddings run on their own consumer so they don't wait behind the
    // (possibly long) chat/intent LLM jobs — they use SiglipEmbedder, not the
    // chat engine.
    private fun launchEmbeddingLoop() {
        serviceScope.launch {
            for (job in embeddingQueue) {
                try {
                    embeddingHandler.process(job)
                } catch (e: CancellationException) {
                    throw e
                } catch (expected: Exception) {
                    Log.error("InferenceService", "Error processing embedding job", expected)
                    embeddingHandler.reportError(job, expected)
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.debug("InferenceService", "onStartCommand received intent")
        if (intent == null) return START_STICKY
        intent.setExtrasClassLoader(SecureResultReceiver::class.java.classLoader)

        val conversationId = intent.getLongExtra("conversation_id", -1L)
        val userText = intent.getStringExtra("user_text") ?: ""
        val audioPath = intent.getStringExtra("audio_path")
        val schema = intent.getStringExtra("schema")
        val embedMode = intent.getStringExtra("embed_mode")
        val receiver = IntentCompat.getParcelableExtra(intent, "RECEIVER", ResultReceiver::class.java)

        val imageUris = IntentCompat.getParcelableArrayListExtra(intent, "image_uris", Uri::class.java)
        val staticImagePaths = intent.getStringArrayExtra("image_paths") ?: emptyArray()

        serviceScope.launch {
            val imagePathsFromUris = imageUris?.mapNotNull { uri ->
                copyUriToFile(this@InferenceService, uri)?.absolutePath
            }?.toTypedArray() ?: emptyArray()

            val imagePaths = staticImagePaths + imagePathsFromUris

            if (embedMode != null && receiver != null) {
                // Embedding request from another app (photos): text/image/info.
                Log.status("InferenceService", "Queueing Embedding request mode=$embedMode")
                embeddingQueue.trySend(
                    InferenceJobPublic.Embedding(
                        embedMode,
                        userText,
                        imagePaths.firstOrNull(),
                        receiver,
                    ),
                )
            } else if (receiver != null && schema != null) {
                Log.status("InferenceService", "Queueing Intent Inference request")
                intentQueue.trySend(InferenceJob.Intent(userText, imagePaths, schema, receiver))
            } else if (conversationId != -1L) {
                Log.dev("InferenceService", "Queueing standard inference for conversation: $conversationId")
                standardQueue.trySend(InferenceJob.Standard(conversationId, userText, imagePaths, audioPath))
            }
        }

        return START_STICKY
    }

    private fun startForegroundTask() {
        val channelId = "inference_service"
        val manager = getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(
            channelId,
            getString(R.string.inference_service),
            NotificationManager.IMPORTANCE_LOW,
        )
        manager?.createNotificationChannel(channel)

        val notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(getString(R.string.processing_ai_inference))
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setOngoing(true)
            .build()

        // FOREGROUND_SERVICE_TYPE_SPECIAL_USE only exists from API 34. Below that the platform has
        // no notion of the type, and an untyped foreground start is the equivalent.
        val serviceType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        } else {
            0
        }
        androidx.core.app.ServiceCompat.startForeground(
            this,
            1,
            notification,
            serviceType,
        )
    }

    private suspend fun executeIntentInference(job: InferenceJob.Intent) {
        try {
            Log.debug("InferenceService", "Executing Intent Inference")
            ensureEngineInitialized()
            withTimeout(INTENT_TIMEOUT_MILLIS) {
                runIntentInference(job.userText, job.imagePaths, job.schema, job.receiver)
            }
        } catch (e: TimeoutCancellationException) {
            Log.error("InferenceService", "Intent inference timed out after 45 seconds")
            job.receiver.send(
                EMBEDDING_ERROR_CODE,
                Bundle().apply { putString("error", getString(R.string.error_inference_timeout)) },
            )
        } catch (e: CancellationException) {
            throw e
        } catch (expected: Exception) {
            Log.error("InferenceService", "Error during intent inference", expected)
            job.receiver.send(
                EMBEDDING_ERROR_CODE,
                Bundle().apply {
                    putString(
                        "error",
                        expected.localizedMessage ?: getString(R.string.error_ai_engine_failed),
                    )
                },
            )
        }
    }

    private suspend fun processIntentJob(job: InferenceJob.Intent) {
        if (System.currentTimeMillis() - job.enqueuedTime > INTENT_QUEUE_TTL_MILLIS) {
            Log.status("InferenceService", "Intent job expired in queue, discarding.")
            job.receiver.send(
                EMBEDDING_ERROR_CODE,
                Bundle().apply { putString("error", getString(R.string.error_request_expired)) },
            )
        } else {
            executeIntentInference(job)
        }
    }

    // ---------- SigLIP2 embedding provider (served to the photos app) ----------
    // Dispatch lives in [InferenceEmbeddingHandler]; queue types are public aliases above.

    private suspend fun loadHistory(conversationId: Long, userText: String): List<HistoryTurn> {
        val cutoff = Clock.System.now().toEpochMilliseconds() - HISTORY_RECENCY_MILLIS
        return fetchHistoryFromDb(conversationId)
            .filter { it.text != userText || it.timestamp < cutoff }
            .map { HistoryTurn(fromUser = it.role != "assistant", text = it.text) }
    }

    private suspend fun executeStandardInference(job: InferenceJob.Standard) {
        try {
            ensureEngineInitialized()
            runInferenceLoop(job.conversationId, job.userText, job.imagePaths, job.audioPath)
        } catch (e: CancellationException) {
            throw e
        } catch (expected: Exception) {
            Log.error("InferenceService", "Inference failed, resetting engine for retry", expected)
            retryStandardInference(job)
        }
    }

    private suspend fun retryStandardInference(job: InferenceJob.Standard) {
        engineLock.withLock {
            engine?.close()
            engine = null
        }
        try {
            ensureEngineInitialized()
            runInferenceLoop(job.conversationId, job.userText, job.imagePaths, job.audioPath)
        } catch (e: CancellationException) {
            throw e
        } catch (retryExpected: Exception) {
            Log.error("InferenceService", "Retry also failed", retryExpected)
            val detail = retryExpected.localizedMessage ?: ""
            upsertMessageToDb(
                DbMessage(
                    conversationId = job.conversationId,
                    text = getString(R.string.error_prefix, detail),
                    role = "assistant",
                    timestamp = Clock.System.now().toEpochMilliseconds(),
                ),
            )
        }
    }

    private fun intentSystemPrompt(schema: String): String = """
        You are a highly specialized data extraction engine.
        Your sole purpose is to analyze the provided images/text and output a SINGLE valid JSON object that adheres STRICTLY to the provided JSON schema.

        EXTREMELY IMPORTANT:
        1. DO NOT respond with any conversational text.
        2. DO NOT include any preamble, explanation, or postscript.
        3. Output ONLY the raw JSON object.
        4. Ensure all keys and values are properly quoted.

        SCHEMA:
        $schema

        Start extraction immediately.
        """.trimIndent()

    private suspend fun runIntentInference(
        userText: String,
        imagePaths: Array<String>,
        schema: String,
        receiver: ResultReceiver,
    ) {
        val chat = engine ?: throw IllegalStateException(getString(R.string.error_ai_engine_failed))
        val job = currentCoroutineContext()[Job]
        var sent = false
        Log.debug("InferenceService", "Sending intent inference request (streaming for early halt)")
        val reply = chat.extract(intentSystemPrompt(schema), userText, imagePaths.toList()) { text ->
            if (job?.isActive == false) return@extract false
            val candidate = tryExtractLargestJson(text)
            if (candidate != null && JsonSchemaValidator.validateJsonAgainstSchema(candidate, schema) == null) {
                Log.status("InferenceService", "Valid JSON extracted and verified against schema. Halting.")
                receiver.send(EMBEDDING_OK_CODE, Bundle().apply { putString("json_result", candidate) })
                sent = true
                false
            } else {
                true
            }
        }
        if (sent) return
        currentCoroutineContext()[Job]?.let { if (!it.isActive) throw CancellationException("intent cancelled") }
        if (reply == null) throw IllegalStateException(getString(R.string.error_ai_engine_failed))

        val finalJson = tryExtractLargestJson(reply)
        if (finalJson != null && JsonSchemaValidator.validateJsonAgainstSchema(finalJson, schema) == null) {
            receiver.send(EMBEDDING_OK_CODE, Bundle().apply { putString("json_result", finalJson) })
            Log.dev("InferenceService", "AI produced output: $finalJson")
            return
        }

        Log.error("InferenceService", "AI finished generation without providing a schema-matching JSON.")
        receiver.send(
            EMBEDDING_ERROR_CODE,
            Bundle().apply { putString("error", getString(R.string.error_ai_json_schema_mismatch)) },
        )
    }

    private fun tryExtractLargestJson(text: String): String? =
        JsonExtractor.largestObject(text)

    private suspend fun ensureEngineInitialized() = engineLock.withLock {
        if (engine != null) return@withLock
        val dir = ModelUrls.GemmaMaml.modelDir(applicationContext)
        val created = MamlChatEngine(dir)
        if (!created.load()) {
            created.close()
            throw IllegalStateException("Model missing or failed to load at ${dir.absolutePath}")
        }
        engine = created
    }

    private suspend fun systemPrompt(): String {
        val stored = DataStoreUtils.getInstance(applicationContext).getStringAwait(KEY_SYSTEM_PROMPT)
        return if (stored.isNullOrBlank()) DEFAULT_SYSTEM_PROMPT else stored
    }

    private suspend fun runInferenceLoop(
        conversationId: Long,
        userText: String,
        imagePaths: Array<String>,
        audioPath: String?,
    ) {
        val chat = engine ?: throw IllegalStateException(getString(R.string.error_ai_engine_failed))
        val request = ChatRequest(
            system = systemPrompt(),
            history = loadHistory(conversationId, userText),
            userText = userText,
            imagePaths = imagePaths.toList(),
            audioPath = audioPath?.takeIf { File(it).exists() },
        )

        val aiMsgId = upsertMessageToDb(
            DbMessage(
                conversationId = conversationId,
                text = "...",
                role = "assistant",
                timestamp = Clock.System.now().toEpochMilliseconds(),
            ),
        )

        val job = currentCoroutineContext()[Job]
        val latest = AtomicReference("")
        val writer = serviceScope.launch {
            var written = ""
            while (isActive) {
                delay(DB_WRITE_INTERVAL_MILLIS)
                flushTitle(conversationId)
                val text = latest.get()
                if (text.isNotBlank() && text != written) {
                    updateMessageInDb(aiMsgId, text)
                    written = text
                }
            }
        }
        val result = try {
            chat.chat(request, tools) { text ->
                if (halt || job?.isActive == false) {
                    false
                } else {
                    latest.set(text)
                    true
                }
            }
        } catch (e: Exception) {
            withContext(NonCancellable) { messageDao.deleteById(aiMsgId) }
            throw e
        } finally {
            withContext(NonCancellable) { writer.cancelAndJoin() }
        }
        flushTitle(conversationId)

        when (result) {
            is ChatResult.Reply -> if (result.halted || halt) {
                halt = false
                messageDao.deleteById(aiMsgId)
                Log.status("InferenceService", "Standard inference halted successfully.")
            } else if (result.text.isNotBlank()) {
                updateMessageInDb(aiMsgId, result.text)
            }
            ChatResult.TooLong -> updateMessageInDb(
                aiMsgId,
                getString(R.string.error_prefix, "the message is too long for the model's context"),
            )
            ChatResult.Unavailable -> {
                messageDao.deleteById(aiMsgId)
                throw IllegalStateException(getString(R.string.error_ai_engine_failed))
            }
        }
    }

    private suspend fun flushTitle(conversationId: Long) {
        val title = newTitle ?: return
        newTitle = null
        updateTitleInDb(conversationId, title)
    }

    private suspend fun fetchHistoryFromDb(id: Long): List<DbMessage> = messageDao.getByConversation(id)
    private suspend fun upsertMessageToDb(msg: DbMessage): Long = messageDao.upsert(msg)
    private suspend fun updateMessageInDb(id: Long, text: String) {
        val existing = messageDao.getById(id) ?: return
        upsertMessageToDb(existing.copy(text = text))
    }
    private suspend fun updateTitleInDb(id: Long, title: String) {
        val oldConversation = conversationDao.getById(id)
        if (oldConversation != null) {
            conversationDao.upsert(oldConversation.copy(title = title))
        }
    }

    override fun onDestroy() {
        serviceScope.cancel()
        engine?.close()
        engine = null
        super.onDestroy()
    }
}
