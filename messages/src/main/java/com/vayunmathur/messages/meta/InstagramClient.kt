package com.vayunmathur.messages.meta

import android.content.Context
import android.util.Log
import com.vayunmathur.messages.data.MessageSource
import com.vayunmathur.messages.gmessages.GMEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.concurrent.atomic.AtomicBoolean

object InstagramClient {

    private const val TAG = "InstagramClient"

    sealed interface State {
        data object Idle : State
        data object NeedsSetup : State
        data object Connecting : State
        data object Connected : State
        data class Disconnected(val reason: String) : State
    }

    val source: MessageSource = MessageSource.INSTAGRAM

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()

    private val _events = MutableSharedFlow<GMEvent>(extraBufferCapacity = 256)
    val events: SharedFlow<GMEvent> = _events.asSharedFlow()

    private val initialized = AtomicBoolean(false)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private lateinit var appContext: Context
    private var authData: MetaAuthData? = null
    private var mqttClient: MetaMqttClient? = null
    private var db: MetaDatabase? = null
    private var backfillJob: Job? = null

    fun init(context: Context) {
        if (!initialized.compareAndSet(false, true)) return
        appContext = context.applicationContext
        db = MetaDatabase.getDatabase(appContext)
        Log.i(TAG, "init")
        runBlocking {
            val auth = MetaAuthData.load(appContext, MetaAuthData.Platform.INSTAGRAM)
            if (auth != null && auth.isValid()) {
                authData = auth
                _state.value = State.Connecting
            } else {
                _state.value = State.NeedsSetup
            }
        }
    }

    fun start() {
        if (!initialized.get()) return
        if (_state.value is State.Connected) return
        scope.launch {
            val auth = MetaAuthData.load(appContext, MetaAuthData.Platform.INSTAGRAM)
                ?: run {
                    _state.value = State.NeedsSetup
                    return@launch
                }
            if (!auth.isValid()) {
                _state.value = State.NeedsSetup
                return@launch
            }
            authData = auth
            connect(auth)
        }
    }

    fun stop() {
        Log.i(TAG, "stop — clearing Instagram session")
        backfillJob?.cancel()
        mqttClient?.disconnect()
        mqttClient = null
        scope.launch { MetaAuthData.clear(appContext, MetaAuthData.Platform.INSTAGRAM) }
        _state.value = State.NeedsSetup
    }

    fun saveAuthData(cookies: Map<String, String>) {
        scope.launch {
            val userId = cookies["ds_user_id"] ?: return@launch
            val auth = MetaAuthData(
                platform = MetaAuthData.Platform.INSTAGRAM,
                userId = userId,
                cookies = cookies
            )
            MetaAuthData.save(appContext, auth)
            authData = auth
            connect(auth)
        }
    }

    private suspend fun connect(auth: MetaAuthData) {
        _state.value = State.Connecting

        mqttClient = MetaMqttClient(auth).apply {
            scope.launch {
                connectionState.collect { state ->
                    when (state) {
                        is MetaMqttClient.ConnectionState.Connected -> {
                            _state.value = State.Connected
                            kickoffBackfill()
                        }
                        is MetaMqttClient.ConnectionState.Disconnected -> {
                            _state.value = State.Disconnected(state.reason)
                        }
                        is MetaMqttClient.ConnectionState.Connecting -> {
                            _state.value = State.Connecting
                        }
                        else -> {}
                    }
                }
            }

            scope.launch {
                messages.collect { mqttMessage ->
                    handleIncomingMessage(mqttMessage)
                }
            }

            connect()
        }
    }

    private fun handleIncomingMessage(mqttMessage: MetaProtocol.MqttMessage) {
        scope.launch {
            try {
                when (mqttMessage.topic) {
                    MetaProtocol.TOPIC_LS_RESP -> {
                        val responseData = MetaProtocol.parsePublishResponse(mqttMessage.payload)
                            ?: return@launch
                        val events = LightspeedDecoder.decodePublishResponse(
                            responseData.payload,
                            responseData.sp,
                        )
                        val message = MetaProtocol.parseMessage(events) ?: return@launch

                        val event = GMEvent.IncomingMessage(
                            source = MessageSource.INSTAGRAM,
                            conversationId = "ig:${message.threadId}",
                            messageId = message.messageId,
                            body = message.text,
                            peerName = message.senderName ?: message.senderId,
                            peerPhone = null,
                            timestamp = message.timestamp,
                        )
                        _events.emit(event)
                    }

                    MetaProtocol.TOPIC_THREAD_TYPING,
                    MetaProtocol.TOPIC_ORCA_TYPING_NOTIFICATIONS -> {
                        val json = Json { ignoreUnknownKeys = true }
                        val payload = String(mqttMessage.payload, Charsets.UTF_8)
                        val obj = json.parseToJsonElement(payload).jsonObject
                        val senderId = obj["sender_fbid"]?.jsonPrimitive?.content ?: return@launch
                        val state = obj["state"]?.jsonPrimitive?.content?.toIntOrNull() ?: return@launch
                        val threadId = obj["thread"]?.jsonPrimitive?.content ?: senderId
                        _events.emit(
                            GMEvent.TypingIndicator(
                                source = MessageSource.INSTAGRAM,
                                conversationId = "ig:$threadId",
                                senderId = senderId,
                                isTyping = state == 1,
                            )
                        )
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to handle incoming message", e)
            }
        }
    }

    private fun kickoffBackfill() {
        backfillJob?.cancel()
        backfillJob = scope.launch {
            Log.i(TAG, "Backfill: syncing thread list via Lightspeed")
            val client = mqttClient ?: return@launch
            try {
                val payload = MetaProtocol.buildFetchThreadsPayload(client.versionId)
                client.makeLSRequest(payload, MetaProtocol.LS_REQUEST_TYPE_TASK)

                val payloadSG95 = MetaProtocol.buildFetchThreadsPayload(client.versionId, syncGroup = 95)
                client.makeLSRequest(payloadSG95, MetaProtocol.LS_REQUEST_TYPE_TASK)
            } catch (e: Exception) {
                Log.e(TAG, "Backfill failed", e)
            }
        }
    }

    suspend fun sendMessage(conversationId: String, body: String): Boolean {
        if (_state.value !is State.Connected) return false
        val client = mqttClient ?: return false

        val threadId = extractThreadId(conversationId)?.toLongOrNull() ?: return false

        val payload = MetaProtocol.buildSendMessagePayload(threadId, body, client.versionId)
        val response = client.makeLSRequest(payload, 3)
        return response != null
    }

    suspend fun sendMedia(
        conversationId: String,
        bytes: ByteArray,
        mimeType: String,
        fileName: String?
    ): Boolean {
        Log.w(TAG, "Media sending not yet implemented for Instagram")
        return false
    }

    suspend fun markRead(conversationId: String) {
        val threadId = extractThreadId(conversationId)?.toLongOrNull() ?: return
        val client = mqttClient ?: return

        val payload = MetaProtocol.buildMarkReadPayload(threadId, client.versionId)
        client.makeLSRequest(payload, 3)
    }

    suspend fun sendReaction(conversationId: String, messageId: String, emoji: String) {
        val threadId = extractThreadId(conversationId)?.toLongOrNull() ?: return
        val client = mqttClient ?: return
        val actorId = authData?.userId?.toLongOrNull() ?: return

        val payload = MetaProtocol.buildReactionPayload(
            threadKey = threadId,
            messageId = messageId,
            reaction = emoji,
            actorId = actorId,
            versionId = client.versionId,
        )
        client.makeLSRequest(payload, 3)
    }

    suspend fun deleteMessage(conversationId: String, messageId: String): Boolean {
        val client = mqttClient ?: return false
        val payload = MetaProtocol.buildDeleteMessagePayload(messageId, client.versionId)
        return client.makeLSRequest(payload, MetaProtocol.LS_REQUEST_TYPE_TASK) != null
    }

    suspend fun deleteMessageMeOnly(conversationId: String, messageId: String): Boolean {
        val threadId = extractThreadId(conversationId)?.toLongOrNull() ?: return false
        val client = mqttClient ?: return false
        val payload = MetaProtocol.buildDeleteMessageMeOnlyPayload(threadId, messageId, client.versionId)
        return client.makeLSRequest(payload, MetaProtocol.LS_REQUEST_TYPE_TASK) != null
    }

    suspend fun editMessage(conversationId: String, messageId: String, text: String): Boolean {
        val client = mqttClient ?: return false
        val payload = MetaProtocol.buildEditMessagePayload(messageId, text, client.versionId)
        return client.makeLSRequest(payload, MetaProtocol.LS_REQUEST_TYPE_TASK) != null
    }

    suspend fun deleteThread(conversationId: String): Boolean {
        val threadId = extractThreadId(conversationId)?.toLongOrNull() ?: return false
        val client = mqttClient ?: return false
        val payload = MetaProtocol.buildDeleteThreadPayload(threadId, client.versionId)
        return client.makeLSRequest(payload, MetaProtocol.LS_REQUEST_TYPE_TASK) != null
    }

    suspend fun muteThread(conversationId: String, muteExpireTimeMs: Long): Boolean {
        val threadId = extractThreadId(conversationId)?.toLongOrNull() ?: return false
        val client = mqttClient ?: return false
        val payload = MetaProtocol.buildMuteThreadPayload(threadId, muteExpireTimeMs, client.versionId)
        return client.makeLSRequest(payload, MetaProtocol.LS_REQUEST_TYPE_TASK) != null
    }

    suspend fun renameThread(conversationId: String, threadName: String): Boolean {
        val threadId = extractThreadId(conversationId)?.toLongOrNull() ?: return false
        val client = mqttClient ?: return false
        val payload = MetaProtocol.buildRenameThreadPayload(threadId, threadName, client.versionId)
        return client.makeLSRequest(payload, MetaProtocol.LS_REQUEST_TYPE_TASK) != null
    }

    suspend fun fetchMessages(conversationId: String, referenceTimestampMs: Long, referenceMessageId: String) {
        val threadId = extractThreadId(conversationId)?.toLongOrNull() ?: return
        val client = mqttClient ?: return
        val payload = MetaProtocol.buildFetchMessagesPayload(threadId, referenceTimestampMs, referenceMessageId, client.versionId)
        client.makeLSRequest(payload, MetaProtocol.LS_REQUEST_TYPE_TASK)
    }

    private fun extractThreadId(conversationId: String): String? {
        return conversationId.removePrefix("ig:")
    }
}
