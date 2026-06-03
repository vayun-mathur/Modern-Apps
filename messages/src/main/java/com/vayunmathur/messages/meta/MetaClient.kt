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
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Meta (Messenger) client using MQTT over WebSocket.
 * Handles authentication via cookies and message routing.
 */
object MetaClient {

    private const val TAG = "MetaClient"

    sealed interface State {
        data object Idle : State
        data object NeedsSetup : State
        data object Connecting : State
        data object Connected : State
        data class Disconnected(val reason: String) : State
    }

    val source: MessageSource = MessageSource.MESSENGER

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
            val auth = MetaAuthData.load(appContext, MetaAuthData.Platform.MESSENGER)
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
            val auth = MetaAuthData.load(appContext, MetaAuthData.Platform.MESSENGER)
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
        Log.i(TAG, "stop — clearing Messenger session")
        backfillJob?.cancel()
        mqttClient?.disconnect()
        mqttClient = null
        scope.launch { MetaAuthData.clear(appContext, MetaAuthData.Platform.MESSENGER) }
        _state.value = State.NeedsSetup
    }

    fun saveAuthData(cookies: Map<String, String>) {
        scope.launch {
            val userId = cookies["c_user"] ?: return@launch
            val auth = MetaAuthData(
                platform = MetaAuthData.Platform.MESSENGER,
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
                val message = MetaProtocol.parseMessage(
                    mqttMessage.payload,
                    MetaAuthData.Platform.MESSENGER
                ) ?: return@launch

                // Convert to GMEvent and emit
                val event = GMEvent.IncomingMessage(
                    source = MessageSource.MESSENGER,
                    conversationId = message.threadId,
                    messageId = message.messageId,
                    body = message.text,
                    peerName = message.senderName ?: message.senderId,
                    peerPhone = null,
                    timestamp = message.timestamp,
                )
                _events.emit(event)

            } catch (e: Exception) {
                Log.e(TAG, "Failed to handle incoming message", e)
            }
        }
    }

    private fun kickoffBackfill() {
        backfillJob?.cancel()
        backfillJob = scope.launch {
            // Messenger doesn't support history backfill via MQTT
            // Only new messages will be received
            Log.i(TAG, "Backfill complete (Messenger MQTT has no history)")
        }
    }

    suspend fun sendMessage(conversationId: String, body: String): Boolean {
        if (_state.value !is State.Connected) return false
        val client = mqttClient ?: return false

        val threadId = extractThreadId(conversationId) ?: return false
        val messageId = generateMessageId()
        val timestamp = System.currentTimeMillis()

        val task = MetaProtocol.SendMessageTask(
            threadId = threadId,
            messageId = messageId,
            text = body,
            timestamp = timestamp
        )

        val payload = MetaProtocol.buildSendMessagePayload(task)
        return client.publish(MetaProtocol.TOPIC_LS_REQ, payload)
    }

    suspend fun sendMedia(
        conversationId: String,
        bytes: ByteArray,
        mimeType: String,
        fileName: String?
    ): Boolean {
        // Media sending requires implementing Meta's media upload API
        Log.w(TAG, "Media sending not yet implemented for Messenger")
        return false
    }

    suspend fun markRead(conversationId: String) {
        val threadId = extractThreadId(conversationId) ?: return
        val client = mqttClient ?: return

        val task = MetaProtocol.ThreadMarkReadTask(
            threadId = threadId,
            lastReadWatermark = System.currentTimeMillis()
        )

        // Build and send mark read task
        val payload = buildMarkReadPayload(task)
        client.publish(MetaProtocol.TOPIC_LS_REQ, payload)
    }

    suspend fun sendReaction(conversationId: String, messageId: String, emoji: String) {
        val threadId = extractThreadId(conversationId) ?: return
        val client = mqttClient ?: return

        val task = MetaProtocol.SendReactionTask(
            threadId = threadId,
            messageId = messageId,
            reaction = emoji
        )

        val payload = buildReactionPayload(task)
        client.publish(MetaProtocol.TOPIC_LS_REQ, payload)
    }

    private fun extractThreadId(conversationId: String): String? {
        // Conversation ID format: "fb:{threadId}"
        return conversationId.removePrefix("fb:")
    }

    private fun generateMessageId(): String {
        return System.currentTimeMillis().toString()
    }

    private fun buildMarkReadPayload(task: MetaProtocol.ThreadMarkReadTask): ByteArray {
        // Simplified JSON payload
        return """{"type":"mark_read","thread_id":"${task.threadId}","watermark":${task.lastReadWatermark}}"""
            .toByteArray(Charsets.UTF_8)
    }

    private fun buildReactionPayload(task: MetaProtocol.SendReactionTask): ByteArray {
        // Simplified JSON payload
        return """{"type":"reaction","thread_id":"${task.threadId}","message_id":"${task.messageId}","reaction":"${task.reaction}"}"""
            .toByteArray(Charsets.UTF_8)
    }
}
