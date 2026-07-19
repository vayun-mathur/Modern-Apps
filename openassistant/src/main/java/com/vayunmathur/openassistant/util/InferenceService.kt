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
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.IntentCompat
import com.google.ai.edge.litertlm.*
import com.vayunmathur.library.util.SecureResultReceiver
import com.vayunmathur.library.room.buildDatabase
import com.vayunmathur.library.util.DataStoreUtils
import com.vayunmathur.library.downloadservice.downloadModelFiles
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.catch
import java.io.File
import kotlin.time.Clock
import com.vayunmathur.openassistant.data.AppDatabase
import com.vayunmathur.openassistant.data.Message
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import com.vayunmathur.openassistant.R
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.selects.select

class InferenceService : Service() {

    companion object {
        var newTitle: String? = null
        var halt: Boolean = false
    }

    private sealed class InferenceJob {
        data class Intent(
            val userText: String,
            val imagePaths: Array<String>,
            val schema: String,
            val receiver: ResultReceiver,
            val enqueuedTime: Long = System.currentTimeMillis()
        ) : InferenceJob()

        data class Standard(
            val conversationId: Long,
            val userText: String,
            val imagePaths: Array<String>,
            val audioPath: String?
        ) : InferenceJob()

        /**
         * A SigLIP2 embedding request from another app (photos). [mode] is
         * "text", "image", or "info". Runs on [SiglipEmbedder], independent of
         * the litertlm chat [Engine], so it can be served while the LLM is busy.
         */
        data class Embedding(
            val mode: String,
            val userText: String,
            val imagePath: String?,
            val receiver: ResultReceiver,
        ) : InferenceJob()
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val standardQueue = Channel<InferenceJob.Standard>(Channel.UNLIMITED)
    private val intentQueue = Channel<InferenceJob.Intent>(Channel.UNLIMITED)
    private val embeddingQueue = Channel<InferenceJob.Embedding>(Channel.UNLIMITED)

    /** Guards the on-demand SigLIP2 model download so we start it at most once. */
    @Volatile private var downloadJob: Job? = null

    private var engine: Engine? = null
    private var currentConversation: com.google.ai.edge.litertlm.Conversation? = null
    private var currentConversationId: Long = -1L

    val db by lazy { buildDatabase<AppDatabase>() }
    private val conversationDao by lazy { db.conversationDao() }
    private val messageDao by lazy { db.messageDao() }
    private val memoryDao by lazy { db.memoryDao() }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        startForegroundTask()
        serviceScope.launch {
            try {
                ensureEngineInitialized()
            } catch (e: Exception) {
                Log.e("InferenceService", "Error pre-warming engine", e)
            }
        }
        serviceScope.launch {
            while (isActive) {
                try {
                    val standardJob = standardQueue.tryReceive().getOrNull()
                    if (standardJob != null) { executeStandardInference(standardJob); continue }

                    val intentJob = intentQueue.tryReceive().getOrNull()
                    if (intentJob != null) { processIntentJob(intentJob); continue }

                    select<Unit> {
                        standardQueue.onReceive { executeStandardInference(it) }
                        intentQueue.onReceive { processIntentJob(it) }
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.e("InferenceService", "Critical error in job processor loop", e)
                }
            }
        }
        // Embeddings run on their own consumer so they don't wait behind the
        // (possibly long) chat/intent LLM jobs — they use SiglipEmbedder, not the
        // litertlm Engine.
        serviceScope.launch {
            for (job in embeddingQueue) {
                try {
                    processEmbeddingJob(job)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.e("InferenceService", "Error processing embedding job", e)
                    try {
                        job.receiver.send(-1, Bundle().apply { putString("error", e.localizedMessage ?: "Embedding failed") })
                    } catch (_: Exception) {}
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d("InferenceService", "onStartCommand received intent")
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
                Log.i("InferenceService", "Queueing Embedding request mode=$embedMode")
                embeddingQueue.trySend(
                    InferenceJob.Embedding(embedMode, userText, imagePaths.firstOrNull(), receiver)
                )
            } else if (receiver != null && schema != null) {
                Log.i("InferenceService", "Queueing Intent Inference request")
                intentQueue.trySend(InferenceJob.Intent(userText, imagePaths, schema, receiver))
            } else if (conversationId != -1L) {
                Log.d("InferenceService", "Queueing standard inference for conversation: $conversationId")
                standardQueue.trySend(InferenceJob.Standard(conversationId, userText, imagePaths, audioPath))
            }
        }

        return START_STICKY
    }

    private fun startForegroundTask() {
        val channelId = "inference_service"
        val manager = getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(channelId, getString(R.string.inference_service), NotificationManager.IMPORTANCE_LOW)
        manager?.createNotificationChannel(channel)

        val notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(getString(R.string.processing_ai_inference))
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setOngoing(true)
            .build()

        androidx.core.app.ServiceCompat.startForeground(
            this,
            1,
            notification,
            android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
        )
    }

    private suspend fun executeIntentInference(job: InferenceJob.Intent) {
        try {
            Log.d("InferenceService", "Executing Intent Inference")
            ensureEngineInitialized()

            currentConversation?.close()
            currentConversation = null
            delay(100)

            setupIntentConversation(job.schema)

            withTimeout(45000) {
                runIntentInferenceLoop(job.userText, job.imagePaths, job.schema, job.receiver)
            }
        } catch (e: TimeoutCancellationException) {
            Log.e("InferenceService", "Intent inference timed out after 45 seconds")
            job.receiver.send(-1, Bundle().apply { putString("error", getString(R.string.error_inference_timeout)) })
        } catch (e: CancellationException) {
            if (e.message != "HALT") throw e
            Log.i("InferenceService", "Intent inference halted successfully via schema match.")
        } catch (e: Exception) {
            Log.e("InferenceService", "Error during intent inference", e)
            job.receiver.send(-1, Bundle().apply { putString("error", e.localizedMessage ?: getString(R.string.error_ai_engine_failed)) })
        } finally {
            currentConversation?.close()
            currentConversation = null
            currentConversationId = -1L
        }
    }

    private suspend fun processIntentJob(job: InferenceJob.Intent) {
        if (System.currentTimeMillis() - job.enqueuedTime > 45000) {
            Log.w("InferenceService", "Intent job expired in queue, discarding.")
            job.receiver.send(-1, Bundle().apply { putString("error", getString(R.string.error_request_expired)) })
        } else {
            executeIntentInference(job)
        }
    }

    // ---------- SigLIP2 embedding provider (served to the photos app) ----------

    private suspend fun processEmbeddingJob(job: InferenceJob.Embedding) {
        val context = applicationContext

        // Models are downloaded on demand; until all three files are present,
        // report "downloading" (code 2) with aggregate progress and retry later.
        if (!SiglipEmbedder.filesPresent(context)) {
            startModelDownloadIfNeeded()
            job.receiver.send(2, Bundle().apply {
                putString("status", "downloading")
                putDouble("progress", currentDownloadProgress())
            })
            return
        }

        if (!SiglipEmbedder.isAvailable(context)) {
            job.receiver.send(-1, Bundle().apply { putString("error", "Embedder failed to load") })
            return
        }

        val dim = SiglipEmbedder.dim(context)
        when (job.mode) {
            "info" -> job.receiver.send(0, Bundle().apply {
                putString("model_id", SiglipEmbedder.MODEL_ID)
                putInt("dim", dim)
                putString("status", "ready")
            })
            "text" -> {
                val t0 = System.currentTimeMillis()
                val emb = SiglipEmbedder.textEmbedding(context, job.userText)
                Log.i("InferenceService", "Text embed (${emb?.size ?: 0}d) in ${System.currentTimeMillis() - t0}ms ok=${emb != null}")
                sendEmbedding(job.receiver, emb)
            }
            "image" -> {
                val path = job.imagePath
                val t0 = System.currentTimeMillis()
                val emb = if (path != null) SiglipEmbedder.imageEmbedding(context, File(path)) else null
                Log.i("InferenceService", "Image embed (${emb?.size ?: 0}d) in ${System.currentTimeMillis() - t0}ms ok=${emb != null} path=$path")
                sendEmbedding(job.receiver, emb)
                // The temp copy from copyUriToFile is no longer needed.
                if (path != null) runCatching { File(path).delete() }
            }
            else -> job.receiver.send(-1, Bundle().apply { putString("error", "Unknown embed_mode ${job.mode}") })
        }
    }

    private fun sendEmbedding(receiver: ResultReceiver, emb: FloatArray?) {
        if (emb == null) {
            // Provider is up but this specific item couldn't be embedded; mark it
            // per_item so the client skips just this one rather than pausing.
            receiver.send(-1, Bundle().apply {
                putString("error", "Embedding failed")
                putBoolean("per_item", true)
            })
        } else {
            receiver.send(0, Bundle().apply {
                putByteArray("embedding", SiglipEmbedder.floatsToBytes(emb))
                putString("model_id", SiglipEmbedder.MODEL_ID)
                putInt("dim", emb.size)
            })
        }
    }

    private fun startModelDownloadIfNeeded() {
        if (downloadJob?.isActive == true) return
        downloadJob = serviceScope.launch {
            try {
                val ds = DataStoreUtils.getInstance(applicationContext)
                downloadModelFiles(applicationContext, ds, siglipDownloadFiles())
            } catch (e: Exception) {
                Log.e("InferenceService", "SigLIP2 model download failed", e)
            }
        }
    }

    private fun currentDownloadProgress(): Double {
        val ds = DataStoreUtils.getInstance(applicationContext)
        val files = listOf(SiglipEmbedder.VISION_FILE, SiglipEmbedder.TEXT_FILE, SiglipEmbedder.TOKENIZER_FILE)
        return files.sumOf { ds.getDouble("progress_$it") ?: 0.0 } / files.size
    }

    private fun siglipDownloadFiles(): List<Triple<String, String, String>> = listOf(
        Triple(SiglipEmbedder.VISION_URL, SiglipEmbedder.VISION_FILE, "Vision Model"),
        Triple(SiglipEmbedder.TEXT_URL, SiglipEmbedder.TEXT_FILE, "Text Model"),
        Triple(SiglipEmbedder.TOKENIZER_URL, SiglipEmbedder.TOKENIZER_FILE, "Tokenizer"),
    )

    private suspend fun resetConversation(conversationId: Long, userText: String) {
        currentConversation?.close()
        currentConversation = null
        delay(100)
        val history = fetchHistoryFromDb(conversationId)
            .filter { it.text != userText || it.timestamp < Clock.System.now().toEpochMilliseconds() - 1000 }
        setupConversation(conversationId, history)
    }

    private suspend fun executeStandardInference(job: InferenceJob.Standard) {
        try {
            ensureEngineInitialized()
            if (currentConversationId != job.conversationId || currentConversation == null || !currentConversation!!.isAlive) {
                resetConversation(job.conversationId, job.userText)
            }
            runInferenceLoop(job.conversationId, job.userText, job.imagePaths, job.audioPath)
        } catch (e: CancellationException) {
            if (e.message != "HALT") throw e
            Log.i("InferenceService", "Standard inference halted successfully.")
        } catch (e: Exception) {
            Log.e("InferenceService", "Inference failed, resetting engine for retry", e)
            currentConversation?.close()
            currentConversation = null
            currentConversationId = -1L
            engine?.close()
            engine = null
            try {
                ensureEngineInitialized()
                resetConversation(job.conversationId, job.userText)
                runInferenceLoop(job.conversationId, job.userText, job.imagePaths, job.audioPath)
            } catch (retryError: Exception) {
                Log.e("InferenceService", "Retry also failed", retryError)
                upsertMessageToDb(Message(
                    conversationId = job.conversationId,
                    text = getString(R.string.error_prefix, retryError.localizedMessage ?: ""),
                    role = "assistant",
                    timestamp = Clock.System.now().toEpochMilliseconds()
                ))
            }
        }
    }

    private fun setupIntentConversation(schema: String) {
        val systemPrompt = """
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

        currentConversation = engine?.createConversation(ConversationConfig(
            systemInstruction = Contents.of(systemPrompt),
            initialMessages = emptyList(),
            automaticToolCalling = false,
        ))
        currentConversationId = -2L // Special ID for intent inference
    }

    private suspend fun runIntentInferenceLoop(
        userText: String,
        imagePaths: Array<String>,
        schema: String,
        receiver: ResultReceiver
    ) {
        val conv = currentConversation ?: return

        val initialContents = mutableListOf<Content>()
        imagePaths.forEach { path -> initialContents.add(Content.ImageFile(path)) }
        if (userText.isNotBlank()) { initialContents.add(Content.Text(userText)) }

        val nextMessage = com.google.ai.edge.litertlm.Message.user(Contents.of(initialContents))

        var fullResponseText = ""
        Log.d("InferenceService", "Sending intent inference request (Streaming mode for safe interruption)")

        val stream = conv.sendMessageAsync(nextMessage)

        stream.collect { chunk ->
            val chunkText = chunk.contents.contents.filterIsInstance<Content.Text>().joinToString("") { it.text }
            fullResponseText += chunkText

            // Try to extract JSON and check if it matches schema
            val jsonCandidate = tryExtractLargestJson(fullResponseText)
            if (jsonCandidate != null) {
                val validationError = JsonSchemaValidator.validateJsonAgainstSchema(jsonCandidate, schema)
                if (validationError == null) {
                    Log.i("InferenceService", "Valid JSON extracted and verified against schema. Halting.")
                    receiver.send(0, Bundle().apply { putString("json_result", jsonCandidate) })
                    throw CancellationException("HALT")
                }
            }
        }

        // If it finished without tryExtractLargestJson triggering HALT (e.g. at the very end)
        val finalJson = tryExtractLargestJson(fullResponseText)
        if (finalJson != null) {
            val validationError = JsonSchemaValidator.validateJsonAgainstSchema(finalJson, schema)
            if (validationError == null) {
                receiver.send(0, Bundle().apply { putString("json_result", finalJson) })
                Log.d("InferenceService", "AI produced output: $finalJson")
                return
            }
        }

        Log.e("InferenceService", "AI finished generation without providing a schema-matching JSON.")
        receiver.send(-1, Bundle().apply { putString("error", getString(R.string.error_ai_json_schema_mismatch)) })
    }

    private fun tryExtractLargestJson(text: String): String? {
        val start = text.indexOf('{')
        if (start == -1) return null

        var depth = 0
        var inString = false
        var escaped = false
        for (i in start until text.length) {
            val c = text[i]
            if (inString) {
                when {
                    escaped -> escaped = false
                    c == '\\' -> escaped = true
                    c == '"' -> inString = false
                }
                continue
            }
            when (c) {
                '"' -> inString = true
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) {
                        val candidate = text.substring(start, i + 1)
                        return try {
                            Json.parseToJsonElement(candidate)
                            candidate
                        } catch (e: Exception) {
                            null
                        }
                    }
                }
            }
        }
        return null
    }

    private suspend fun ensureEngineInitialized() {
        if (engine != null) return

        @OptIn(ExperimentalApi::class)
        ExperimentalFlags.enableSpeculativeDecoding = true

        val modelFile = File(applicationContext.getExternalFilesDir(null)!!, "gemma4-2b.litertlm")
        if (!modelFile.exists()) throw Exception("Model file missing at ${modelFile.absolutePath}")

        val config = EngineConfig(
            modelPath = modelFile.absolutePath,
            backend = Backend.GPU(),
            visionBackend = Backend.GPU(),
            audioBackend = Backend.CPU(),
            cacheDir = applicationContext.cacheDir.absolutePath,
        )

        val newEngine = Engine(config)
        withContext(Dispatchers.IO) {
            newEngine.initialize()
        }
        engine = newEngine
    }

    private fun setupConversation(id: Long, history: List<Message>) {
        val systemPrompt = """
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

        val initialMessages = history.map { msg ->
            when (msg.role) {
                "user" -> com.google.ai.edge.litertlm.Message.user(Contents.of(msg.text))
                "assistant" -> com.google.ai.edge.litertlm.Message.model(Contents.of(msg.text))
                else -> com.google.ai.edge.litertlm.Message.user(Contents.of(msg.text))
            }
        }

        currentConversation = engine?.createConversation(ConversationConfig(
            systemInstruction = Contents.of(systemPrompt),
            initialMessages = initialMessages,
            tools = listOf(tool(AssistantToolSet(applicationContext, memoryDao, messageDao, id))),
            automaticToolCalling = true,
        ))
        currentConversationId = id
    }

    private suspend fun runInferenceLoop(
        conversationId: Long,
        userText: String,
        imagePaths: Array<String>,
        audioPath: String?
    ) {
        val conv = currentConversation ?: return

        val aiMsgId = upsertMessageToDb(Message(
            conversationId = conversationId,
            text = "...",
            role = "assistant",
            timestamp = Clock.System.now().toEpochMilliseconds()
        ))

        var fullResponseText = ""
        val contents = mutableListOf<Content>()
        imagePaths.forEach { path -> contents.add(Content.ImageFile(path)) }
        audioPath?.let { if (File(it).exists()) contents.add(Content.AudioFile(it)) }
        if (userText.isNotBlank()) contents.add(Content.Text(userText))

        val stream = conv.sendMessageAsync(com.google.ai.edge.litertlm.Message.user(Contents.of(contents)))

        stream.catch { e ->
            Log.d("InferenceService", "Caught inference error: ${e::class.simpleName}", e)
            if (e is MissingAppException || e is StopInferenceException || e.cause is StopInferenceException || halt) {
                halt = false
                messageDao.deleteById(aiMsgId)
            } else {
                messageDao.deleteById(aiMsgId)
                throw e
            }
        }.collect { chunk ->
            if (halt) {
                halt = false
                messageDao.deleteById(aiMsgId)
                throw CancellationException("HALT")
            }
            val chunkText = chunk.contents.contents.filterIsInstance<Content.Text>().joinToString("") { it.text }
            fullResponseText += chunkText

            if(newTitle != null) {
                updateTitleInDb(conversationId, newTitle!!)
                newTitle = null
            }

            if (fullResponseText.isNotBlank()) {
                updateMessageInDb(aiMsgId, fullResponseText)
            }
        }
    }

    private suspend fun fetchHistoryFromDb(id: Long): List<Message> = messageDao.getByConversation(id)
    private suspend fun upsertMessageToDb(msg: Message): Long = messageDao.upsert(msg)
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
        currentConversation?.close()
        engine?.close()
        super.onDestroy()
    }
}
