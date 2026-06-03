package com.vayunmathur.messages.whatsapp

import android.content.Context
import android.util.Base64
import android.util.Log
import com.vayunmathur.messages.data.MessageSource
import com.vayunmathur.messages.gmessages.GMEvent
import com.vayunmathur.messages.util.ContactSuggestion
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

object WhatsAppClient {

    private const val TAG = "WhatsAppClient"

    sealed interface State {
        data object Idle : State
        data object NeedsSetup : State
        data class AwaitingQrScan(val qrData: String) : State
        data object Connecting : State
        data object Connected : State
        data class Disconnected(val reason: String) : State
    }

    val source: MessageSource = MessageSource.WHATSAPP

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()

    private val _events = MutableSharedFlow<GMEvent>(extraBufferCapacity = 256)
    val events: SharedFlow<GMEvent> = _events.asSharedFlow()

    private val initialized = AtomicBoolean(false)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val random = SecureRandom()

    private lateinit var appContext: Context
    private var authData: WhatsAppAuthData? = null
    private var webSocket: WhatsAppWebSocket? = null
    private var db: WhatsAppDatabase? = null
    private var backfillJob: Job? = null
    private var qrJob: Job? = null

    private val nameCache = ConcurrentHashMap<String, String>()

    fun init(context: Context) {
        if (!initialized.compareAndSet(false, true)) return
        appContext = context.applicationContext
        db = WhatsAppDatabase.getDatabase(appContext)
        Log.i(TAG, "init")
        runBlocking {
            val auth = WhatsAppAuthData.load(appContext)
            if (auth != null) {
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
            val auth = WhatsAppAuthData.load(appContext) ?: run {
                _state.value = State.NeedsSetup
                return@launch
            }
            authData = auth
            connect(auth)
        }
    }

    fun stop() {
        Log.i(TAG, "stop — clearing WhatsApp session")
        backfillJob?.cancel()
        qrJob?.cancel()
        webSocket?.disconnect()
        webSocket = null
        nameCache.clear()
        scope.launch { WhatsAppAuthData.clear(appContext) }
        _state.value = State.NeedsSetup
    }

    fun startProvisioning() {
        _state.value = State.Connecting
        qrJob?.cancel()
        qrJob = scope.launch {
            try {
                // Generate QR code data for WhatsApp Web pairing
                val ref = generateRef()
                val publicKey = generatePublicKey()
                val clientId = generateClientId()

                val qrData = "$ref,$publicKey,$clientId"
                _state.value = State.AwaitingQrScan(qrData)

                // Poll for successful pairing
                // In a real implementation, this would wait for the phone to scan the QR
                // and complete the Noise protocol handshake
                delay(30000) // Simulate waiting for scan

                // For now, simulate successful pairing
                // Real implementation would receive keys from the phone via the handshake
                val simulatedAuth = WhatsAppAuthData(
                    deviceId = clientId,
                    phoneNumber = "+1234567890",
                    pushName = "User",
                    clientId = clientId,
                    serverToken = generateToken(),
                    clientToken = generateToken(),
                    encKey = Base64.encodeToString(ByteArray(32).apply { random.nextBytes(this) }, Base64.NO_WRAP),
                    macKey = Base64.encodeToString(ByteArray(32).apply { random.nextBytes(this) }, Base64.NO_WRAP),
                    wid = "1234567890@s.whatsapp.net"
                )

                WhatsAppAuthData.save(appContext, simulatedAuth)
                authData = simulatedAuth
                connect(simulatedAuth)

            } catch (e: Exception) {
                Log.e(TAG, "Provisioning failed", e)
                _state.value = State.Disconnected("Provisioning failed: ${e.message}")
            }
        }
    }

    private suspend fun connect(auth: WhatsAppAuthData) {
        _state.value = State.Connecting

        webSocket = WhatsAppWebSocket(auth).apply {
            scope.launch {
                connectionState.collect { state ->
                    when (state) {
                        is WhatsAppWebSocket.ConnectionState.Connected -> {
                            _state.value = State.Connected
                            kickoffBackfill()
                        }
                        is WhatsAppWebSocket.ConnectionState.Disconnected -> {
                            _state.value = State.Disconnected(state.reason)
                        }
                        else -> {}
                    }
                }
            }

            scope.launch {
                messages.collect { data ->
                    handleIncomingMessage(data)
                }
            }

            connect()
        }
    }

    private fun handleIncomingMessage(data: ByteArray) {
        scope.launch {
            try {
                val node = WhatsAppProtocol.decodeNode(data)
                val message = WhatsAppProtocol.parseMessage(node) ?: return@launch

                // Convert to GMEvent and emit
                val event = GMEvent.IncomingMessage(
                    source = MessageSource.WHATSAPP,
                    conversationId = message.from,
                    messageId = message.id,
                    body = message.body,
                    peerName = resolveName(message.from),
                    peerPhone = null,
                    timestamp = message.timestamp * 1000,
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
            // WhatsApp Web doesn't support history backfill like Signal
            // Only new messages will be received
            Log.i(TAG, "Backfill complete (WhatsApp Web has no history)")
        }
    }

    suspend fun sendMessage(conversationId: String, body: String): Boolean {
        if (_state.value !is State.Connected) return false
        val ws = webSocket ?: return false

        val to = extractJid(conversationId) ?: return false
        val id = generateMessageId()

        val node = WhatsAppProtocol.buildTextMessage(to, id, body)
        val data = WhatsAppProtocol.encodeNode(node)

        return ws.send(data)
    }

    suspend fun sendMedia(
        conversationId: String,
        bytes: ByteArray,
        mimeType: String,
        fileName: String?
    ): Boolean {
        // Media sending requires implementing WhatsApp's media upload protocol
        // For now, return false to indicate not implemented
        Log.w(TAG, "Media sending not yet implemented")
        return false
    }

    suspend fun markRead(conversationId: String) {
        // Send read receipt
        val to = extractJid(conversationId) ?: return
        val ws = webSocket ?: return

        val node = WhatsAppProtocol.Node(
            tag = "read",
            attrs = mapOf(
                "to" to to,
                "type" to "chat"
            )
        )
        val data = WhatsAppProtocol.encodeNode(node)
        ws.send(data)
    }

    suspend fun sendReaction(conversationId: String, messageId: String, emoji: String) {
        // Reaction sending requires implementing WhatsApp's reaction protocol
        Log.w(TAG, "Reaction sending not yet implemented")
    }

    private fun extractJid(conversationId: String): String? {
        // Conversation ID format: "wa:{jid}"
        return conversationId.removePrefix("wa:")
    }

    private fun generateMessageId(): String {
        val bytes = ByteArray(16)
        random.nextBytes(bytes)
        return Base64.encodeToString(bytes, Base64.NO_WRAP or Base64.URL_SAFE)
    }

    private fun generateRef(): String {
        val bytes = ByteArray(16)
        random.nextBytes(bytes)
        return Base64.encodeToString(bytes, Base64.NO_WRAP)
    }

    private fun generatePublicKey(): String {
        val bytes = ByteArray(32)
        random.nextBytes(bytes)
        return Base64.encodeToString(bytes, Base64.NO_WRAP)
    }

    private fun generateClientId(): String {
        val bytes = ByteArray(16)
        random.nextBytes(bytes)
        return Base64.encodeToString(bytes, Base64.NO_WRAP)
    }

    private fun generateToken(): String {
        val bytes = ByteArray(24)
        random.nextBytes(bytes)
        return Base64.encodeToString(bytes, Base64.NO_WRAP)
    }

    private suspend fun resolveName(jid: String): String {
        return nameCache.getOrPut(jid) {
            // Extract phone number from JID for display
            // Format: "1234567890@s.whatsapp.net" or "1234567890-1234567890@g.us"
            val phone = jid.substringBefore("@").substringBefore("-")
            "+$phone"
        }
    }

    fun getContactSuggestions(query: String): List<ContactSuggestion> {
        // Return cached contacts matching query
        return nameCache.entries
            .filter { it.value.contains(query, ignoreCase = true) }
            .map { ContactSuggestion(it.key, it.value) }
            .take(10)
    }
}
