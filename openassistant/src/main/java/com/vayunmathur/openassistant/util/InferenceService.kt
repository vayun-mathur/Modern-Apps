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
import com.vayunmathur.openassistant.platform.AssistantCatalog
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
import com.vayunmathur.openassistant.data.AuditRecord
import com.vayunmathur.openassistant.data.AuditRecordDao
import com.vayunmathur.openassistant.data.ConversationDao
import com.vayunmathur.openassistant.data.MemoryDao
import com.vayunmathur.openassistant.data.MessageDao
import com.vayunmathur.openassistant.data.PendingToolCallDao
import com.vayunmathur.openassistant.data.PendingToolCallEntity
import com.vayunmathur.openassistant.domain.PendingToolCall
import com.vayunmathur.openassistant.domain.redactedArgsSummary
import com.vayunmathur.openassistant.platform.A2uiTemplates
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import com.vayunmathur.openassistant.data.Message as DbMessage
import com.vayunmathur.openassistant.data.OpenAssistantRepository
import com.vayunmathur.openassistant.R
import com.vayunmathur.library.ml.GEMMA4_EXPECTED_BYTES
import com.vayunmathur.library.ml.Gemma4Handle
import com.vayunmathur.openassistant.domain.ModelLoadFailure
import com.vayunmathur.openassistant.domain.classifyModelLoadFailure
import com.vayunmathur.openassistant.domain.describe
import com.vayunmathur.openassistant.domain.splitA2ui
import com.vayunmathur.openassistant.domain.withA2uiBlock
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.selects.select

/** Inference Service: a service implementation for inference service. */
class InferenceService : Service() {

    /** Shared constants and helpers for InferenceService. */
    companion object {
        var newTitle: String? = null
        var halt: Boolean = false

        /** DataStore key holding the user-editable chat system prompt. */
        const val KEY_SYSTEM_PROMPT = "system_prompt"
        private const val RESULT_ERROR_CODE = -1
        private const val RESULT_OK_CODE = 0
        private const val INTENT_TIMEOUT_MILLIS = 45000L
        private const val INTENT_QUEUE_TTL_MILLIS = 45000L
        private const val HISTORY_RECENCY_MILLIS = 1000L
        private const val DB_WRITE_INTERVAL_MILLIS = 100L
        private const val AUDIT_OUTCOME_CHARS = 200
        private val HOST_CONFIRM = Regex("""\[HostConfirm ([^\]]+)]""")
        private val HOST_CANCEL = Regex("""\[HostCancel ([^\]]+)]""")

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
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val standardQueue = Channel<InferenceJob.Standard>(Channel.UNLIMITED)
    private val intentQueue = Channel<InferenceJob.Intent>(Channel.UNLIMITED)

    private var engine: MamlChatEngine? = null
    private val engineLock = Mutex()

    // Behavior-preserving test seams: production always uses the defaults
    // (real MamlChatEngine, repository DAOs, wall clock); tests substitute
    // fakes because the native engine and SQLCipher cannot load on the JVM.
    internal var engineFactory: (File) -> MamlChatEngine = { dir -> MamlChatEngine(dir) }
    internal var conversationDaoOverride: ConversationDao? = null
    internal var messageDaoOverride: MessageDao? = null
    internal var memoryDaoOverride: MemoryDao? = null
    internal var pendingDaoOverride: PendingToolCallDao? = null
    internal var auditDaoOverride: AuditRecordDao? = null
    internal var nowMillis: () -> Long = { System.currentTimeMillis() }
    private val tools by lazy { AssistantTools(applicationContext, memoryDao) }

    private val repository by lazy { OpenAssistantRepository.get(applicationContext) }
    private val conversationDao get() = conversationDaoOverride ?: repository.conversationDaoRef
    private val messageDao get() = messageDaoOverride ?: repository.messageDaoRef

    private val store: MessageStore get() = MessageStore(messageDao, conversationDao)
    private val memoryDao get() = memoryDaoOverride ?: repository.memoryDaoRef
    private val pendingDao: PendingToolCallDao?
        get() = pendingDaoOverride ?: runCatching { repository.pendingToolCallDaoRef }.getOrNull()
    private val auditDao: AuditRecordDao?
        get() = auditDaoOverride ?: runCatching { repository.auditRecordDaoRef }.getOrNull()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        startForegroundTask()
        launchPrewarm()
        launchJobLoop()
    }

    private fun launchPrewarm() {
        serviceScope.launch {
            try {
                ensureEngineInitialized()
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

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.debug("InferenceService", "onStartCommand received intent")
        if (intent == null) return START_STICKY
        intent.setExtrasClassLoader(SecureResultReceiver::class.java.classLoader)

        val conversationId = intent.getLongExtra("conversation_id", -1L)
        val userText = intent.getStringExtra("user_text") ?: ""
        val audioPath = intent.getStringExtra("audio_path")
        val schema = intent.getStringExtra("schema")
        val receiver = IntentCompat.getParcelableExtra(intent, "RECEIVER", ResultReceiver::class.java)

        val imageUris = IntentCompat.getParcelableArrayListExtra(intent, "image_uris", Uri::class.java)
        val staticImagePaths = intent.getStringArrayExtra("image_paths") ?: emptyArray()

        serviceScope.launch {
            val imagePathsFromUris = imageUris?.mapNotNull { uri ->
                copyUriToFile(this@InferenceService, uri)?.absolutePath
            }?.toTypedArray() ?: emptyArray()

            val imagePaths = staticImagePaths + imagePathsFromUris

            if (receiver != null && schema != null) {
                Log.status("InferenceService", "Queueing Intent Inference request")
                intentQueue.trySend(InferenceJob.Intent(userText, imagePaths, schema, receiver, nowMillis()))
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
            Log.error("InferenceService", "Intent inference timed out after 45 seconds", e)
            job.receiver.send(
                RESULT_ERROR_CODE,
                Bundle().apply { putString("error", getString(R.string.error_inference_timeout)) },
            )
        } catch (e: CancellationException) {
            throw e
        } catch (expected: Exception) {
            Log.error("InferenceService", "Error during intent inference", expected)
            job.receiver.send(
                RESULT_ERROR_CODE,
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
        if (nowMillis() - job.enqueuedTime > INTENT_QUEUE_TTL_MILLIS) {
            Log.status("InferenceService", "Intent job expired in queue, discarding.")
            job.receiver.send(
                RESULT_ERROR_CODE,
                Bundle().apply { putString("error", getString(R.string.error_request_expired)) },
            )
        } else {
            executeIntentInference(job)
        }
    }

    private suspend fun loadHistory(conversationId: Long, userText: String): List<HistoryTurn> {
        val cutoff = Clock.System.now().toEpochMilliseconds() - HISTORY_RECENCY_MILLIS
        return fetchHistoryFromDb(conversationId)
            .filter { it.text != userText || it.timestamp < cutoff }
            .map { HistoryTurn(fromUser = it.role != "assistant", text = withA2uiBlock(it.text, it.a2ui)) }
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

    // Last-resort retry: any engine failure is logged and surfaced, so the catch stays broad.
    @Suppress("TooGenericExceptionCaught")
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
            store.upsert(
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
        val chat = engine ?: failEngine()
        val job = currentCoroutineContext()[Job]
        var sent = false
        Log.debug("InferenceService", "Sending intent inference request (streaming for early halt)")
        val reply = chat.extract(intentSystemPrompt(schema), userText, imagePaths.toList()) { text ->
            if (job?.isActive == false) return@extract false
            val candidate = tryExtractLargestJson(text)
            if (candidate != null && JsonSchemaValidator.validateJsonAgainstSchema(candidate, schema) == null) {
                Log.status("InferenceService", "Valid JSON extracted and verified against schema. Halting.")
                receiver.send(RESULT_OK_CODE, Bundle().apply { putString("json_result", candidate) })
                sent = true
                false
            } else {
                true
            }
        }
        if (sent) return
        currentCoroutineContext()[Job]?.let { if (!it.isActive) failCancelled() }
        if (reply == null) failEngine()

        val finalJson = tryExtractLargestJson(reply)
        if (finalJson != null && JsonSchemaValidator.validateJsonAgainstSchema(finalJson, schema) == null) {
            receiver.send(RESULT_OK_CODE, Bundle().apply { putString("json_result", finalJson) })
            Log.dev("InferenceService", "AI produced output: $finalJson")
            return
        }

        Log.error("InferenceService", "AI finished generation without providing a schema-matching JSON.")
        receiver.send(
            RESULT_ERROR_CODE,
            Bundle().apply { putString("error", getString(R.string.error_ai_json_schema_mismatch)) },
        )
    }

    /** Fails with the engine-failed error; keeps throw sites out of the inference paths. */
    private fun failEngine(): Nothing =
        throw IllegalStateException(getString(R.string.error_ai_engine_failed))

    /** Fails with a cancellation; keeps throw sites out of the inference paths. */
    private fun failCancelled(): Nothing = throw CancellationException("intent cancelled")

    private fun tryExtractLargestJson(text: String): String? =
        JsonExtractor.largestObject(text)

    private suspend fun ensureEngineInitialized() = engineLock.withLock {
        if (engine != null) return@withLock
        val dir = ModelUrls.GemmaMaml.modelDir(applicationContext)
        val created = engineFactory(dir)
        if (!created.load()) {
            val detail = created.loadError()
            created.close()
            // Say which it was (#917): "missing or failed to load" read as "missing" to a
            // user whose verified files were all there and whose GPU had refused them.
            throw IllegalStateException(modelLoadFailure(dir, detail).describe(dir.absolutePath))
        }
        engine = created
    }

    /** What is on disk in [dir], classified against the engine's own [engineError]. */
    private fun modelLoadFailure(dir: File, engineError: String?): ModelLoadFailure {
        val names = (Gemma4Handle.FILES + GEMMA4_EXPECTED_BYTES.keys).distinct()
        val sizes = names.associateWith { name -> File(dir, name).takeIf { it.isFile }?.length() }
        return classifyModelLoadFailure(sizes, Gemma4Handle.FILES, GEMMA4_EXPECTED_BYTES, engineError)
    }

    /** The user's (or default) prompt plus the A2UI section, which custom prompts must not lose. */
    private suspend fun systemPrompt(): String {
        val stored = DataStoreUtils.getInstance(applicationContext).getStringAwait(KEY_SYSTEM_PROMPT)
        val base = if (stored.isNullOrBlank()) DEFAULT_SYSTEM_PROMPT else stored
        return base + "\n\n" + AssistantCatalog.PROMPT_SECTION
    }

    private fun PendingToolCallEntity.toDomain(): PendingToolCall = PendingToolCall(
        id = id,
        name = toolName,
        args = runCatching {
            Json.parseToJsonElement(argsJson).jsonObject
                .mapValues { it.value.jsonPrimitive.content }
        }.getOrDefault(emptyMap()),
        summary = summary,
        createdAtMillis = createdAt,
        expiresAtMillis = expiresAt,
        consumed = consumed,
    )

    private fun PendingToolCall.toEntity(conversationId: Long): PendingToolCallEntity =
        PendingToolCallEntity(
            id = id,
            conversationId = conversationId,
            toolName = name,
            argsJson = JsonObject(args.mapValues { JsonPrimitive(it.value) }).toString(),
            summary = summary,
            createdAt = createdAtMillis,
            expiresAt = expiresAtMillis,
            consumed = consumed,
        )

    private suspend fun loadPendingDomain(id: String): PendingToolCall? {
        tools.pendingById(id)?.let { return it }
        val entity = runCatching { pendingDao?.getById(id) }.getOrNull() ?: return null
        return entity.toDomain().also { tools.restorePending(it) }
    }

    private suspend fun persistPending(conversationId: Long, pending: PendingToolCall) {
        runCatching { pendingDao?.upsert(pending.toEntity(conversationId)) }
    }

    /** Returns the model-turn text for a host marker, or null when [userText] is not one. */
    private suspend fun handleHostMarker(conversationId: Long, userText: String): String? {
        val trimmed = userText.trim()
        val confirmId = HOST_CONFIRM.matchEntire(trimmed)?.groupValues?.get(1)
        val cancelId = HOST_CANCEL.matchEntire(trimmed)?.groupValues?.get(1)
        if (cancelId != null) {
            val pending = loadPendingDomain(cancelId)
            tools.removePending(cancelId)
            runCatching { pendingDao?.deleteById(cancelId) }
            return "[Cancelled \"${pending?.name ?: "tool"}\"]"
        }
        if (confirmId == null) return null
        val pending = loadPendingDomain(confirmId)
            ?: return "[Confirmed tool call could not be completed: Error: pending tool call not found]"
        val wasValid = !pending.consumed && !pending.isExpired(nowMillis())
        val result = tools.invokeConfirmed(confirmId)
        if (!wasValid) {
            return "[Confirmed \"${pending.name}\" could not be completed: $result]"
        }
        runCatching {
            auditDao?.upsert(
                AuditRecord(
                    toolName = pending.name,
                    argsSummary = redactedArgsSummary(pending.args),
                    outcome = result.take(AUDIT_OUTCOME_CHARS),
                    timestamp = nowMillis(),
                ),
            )
        }
        runCatching { pendingDao?.upsert(pending.copy(consumed = true).toEntity(conversationId)) }
        store.upsert(
            DbMessage(
                conversationId = conversationId,
                text = "Confirmed \"${pending.name}\": $result",
                role = "assistant",
                timestamp = Clock.System.now().toEpochMilliseconds(),
            ),
        )
        return "[Confirmed \"${pending.name}\": $result]"
    }

    // The chat call needs cleanup-and-rethrow on any engine failure, so the catch stays broad.
    @Suppress("TooGenericExceptionCaught")
    private suspend fun runInferenceLoop(
        conversationId: Long,
        userText: String,
        imagePaths: Array<String>,
        audioPath: String?,
    ) {
        val chat = engine ?: failEngine()
        val effectiveUserText = handleHostMarker(conversationId, userText) ?: userText
        val request = ChatRequest(
            system = systemPrompt(),
            history = loadHistory(conversationId, userText),
            userText = effectiveUserText,
            imagePaths = imagePaths.toList(),
            audioPath = audioPath?.takeIf { File(it).exists() },
        )

        val aiMsgId = store.upsert(
            DbMessage(
                conversationId = conversationId,
                text = "...",
                role = "assistant",
                timestamp = Clock.System.now().toEpochMilliseconds(),
            ),
        )

        val job = currentCoroutineContext()[Job]
        val latest = AtomicReference("")
        val writer = launchReplyWriter(conversationId, aiMsgId, latest)
        val result = try {
            chat.chat(request, tools) { text ->
                if (halt || job?.isActive == false) {
                    false
                } else {
                    latest.set(splitA2ui(text).visible)
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
                store.storeReply(aiMsgId, result.text)
            }
            is ChatResult.Pending -> {
                val pending = tools.pendingById(result.pendingId) ?: PendingToolCall(
                    id = result.pendingId,
                    name = "tool",
                    args = emptyMap(),
                    summary = result.summary,
                    createdAtMillis = nowMillis(),
                )
                persistPending(conversationId, pending)
                val shown = result.text.ifBlank { "Please confirm to continue." }
                store.updateMessage(aiMsgId, shown, A2uiTemplates.confirmCard(pending))
            }
            ChatResult.TooLong -> store.updateMessage(
                aiMsgId,
                getString(R.string.error_prefix, "the message is too long for the model's context"),
            )
            ChatResult.Unavailable -> {
                messageDao.deleteById(aiMsgId)
                failEngine()
            }
        }
    }


    /** Periodically flushes the streaming reply and any new title to the DB. */
    private fun launchReplyWriter(
        conversationId: Long,
        aiMsgId: Long,
        latest: AtomicReference<String>,
    ) = serviceScope.launch {
        var written = ""
        while (isActive) {
            delay(DB_WRITE_INTERVAL_MILLIS)
            flushTitle(conversationId)
            val text = latest.get()
            if (text.isNotBlank() && text != written) {
                store.updateMessage(aiMsgId, text)
                written = text
            }
        }
    }
    private suspend fun flushTitle(conversationId: Long) {
        val title = newTitle ?: return
        newTitle = null
        store.updateTitle(conversationId, title)
    }

    private suspend fun fetchHistoryFromDb(id: Long): List<DbMessage> = store.history(id)

    /** Stores a finished reply: the prose as its text, any A2UI block beside it. */

    override fun onDestroy() {
        serviceScope.cancel()
        engine?.close()
        engine = null
        super.onDestroy()
    }
}

/** Message persistence for [InferenceService], split out of the service class. */
internal class MessageStore(
    private val messageDao: MessageDao,
    private val conversationDao: ConversationDao,
) {
    /** Conversation history, oldest first. */
    suspend fun history(id: Long): List<DbMessage> = messageDao.getByConversation(id)

    /** Stores a finished reply: the prose as its text, any A2UI block beside it. */
    suspend fun storeReply(id: Long, reply: String) {
        val split = splitA2ui(reply, streaming = false)
        val shown = if (split.visible.isBlank() && split.json == null) reply else split.visible
        updateMessage(id, shown, split.json)
    }

    /** Inserts a message and returns its row id. */
    suspend fun upsert(msg: DbMessage): Long = messageDao.upsert(msg)

    /** Replaces a message's text and A2UI payload. */
    suspend fun updateMessage(id: Long, text: String, a2ui: String? = null) {
        val existing = messageDao.getById(id) ?: return
        upsert(existing.copy(text = text, a2ui = a2ui))
    }

    /** Sets the conversation title. */
    suspend fun updateTitle(id: Long, title: String) {
        val oldConversation = conversationDao.getById(id)
        if (oldConversation != null) {
            conversationDao.upsert(oldConversation.copy(title = title))
        }
    }
}
