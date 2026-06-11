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
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
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
    // Use WebView-based WebSocket to bypass TLS fingerprinting
    // WhatsApp blocks non-browser TLS fingerprints (JA3). WebView uses Chromium's
    // network stack which is indistinguishable from Chrome browser.
    private var webSocket: WebViewWebSocket? = null
    private var db: WhatsAppDatabase? = null
    private var backfillJob: Job? = null
    private var qrJob: Job? = null

    private val nameCache = ConcurrentHashMap<String, String>()

    private val httpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
    }

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
                // 1. Generate real X25519 key pairs for Noise and identity
                val (noisePriv, noisePub) = WhatsAppProtocol.generateX25519KeyPair()
                val (identityPriv, identityPub) = WhatsAppProtocol.generateX25519KeyPair()
                val advSecretKey = ByteArray(32).apply { random.nextBytes(this) }
                
                // 2. Connect WebSocket and wait for Noise handshake to complete
                webSocket = WebViewWebSocket(appContext, null).apply {
                    val ws = this
                    scope.launch {
                        connectionState.collect { connState ->
                            when (connState) {
                                is WebViewWebSocket.ConnectionState.Connected -> {
                                    Log.i(TAG, "WebSocket connected, waiting for ref from server")
                                }
                                is WebViewWebSocket.ConnectionState.Disconnected -> {
                                    if (_state.value is State.AwaitingQrScan || _state.value is State.Connecting) {
                                        _state.value = State.Disconnected(connState.reason)
                                    }
                                }
                                else -> {}
                            }
                        }
                    }
                    
                    scope.launch {
                        messages.collect { data ->
                            handleProvisioningMessage(data, noisePriv, noisePub, identityPriv, identityPub, advSecretKey)
                        }
                    }
                    
                    connect()
                }
                
                // 3. Generate QR data with real keys
                // Go format: https://wa.me/settings/linked_devices#ref,noise,identity,adv,clientType
                // Use a placeholder ref until the server sends one via pair-device IQ
                val refBytes = ByteArray(16).apply { random.nextBytes(this) }
                val ref = Base64.encodeToString(refBytes, Base64.NO_WRAP)
                val qrData = "https://wa.me/settings/linked_devices#" + listOf(
                    ref,
                    Base64.encodeToString(noisePub, Base64.NO_WRAP),
                    Base64.encodeToString(identityPub, Base64.NO_WRAP),
                    Base64.encodeToString(advSecretKey, Base64.NO_WRAP),
                    "1" // PairClientChrome (from whatsmeow/pair-code.go)
                ).joinToString(",")
                _state.value = State.AwaitingQrScan(qrData)
                
                Log.i(TAG, "QR code generated, waiting for phone to scan")

            } catch (e: Exception) {
                Log.e(TAG, "Provisioning failed", e)
                _state.value = State.Disconnected("Provisioning failed: ${e.message}")
            }
        }
    }
    
    private fun handleProvisioningMessage(
        data: ByteArray,
        noisePriv: ByteArray,
        noisePub: ByteArray,
        identityPriv: ByteArray,
        identityPub: ByteArray,
        advSecretKey: ByteArray,
    ) {
        scope.launch {
            try {
                val node = WhatsAppProtocol.decodeNode(data)
                Log.d(TAG, "Provisioning message: tag=${node.tag}")
                
                if (node.tag == "success") {
                    val wid = node.attrs["wid"] ?: ""
                    val signedPreKP = WhatsAppProtocol.generateX25519KeyPair()
                    val auth = WhatsAppAuthData(
                        phoneNumber = wid.substringBefore("@"),
                        pushName = node.attrs["pushname"] ?: "User",
                        wid = wid,
                        noisePrivateKey = Base64.encodeToString(noisePriv, Base64.NO_WRAP),
                        noisePublicKey = Base64.encodeToString(noisePub, Base64.NO_WRAP),
                        identityPrivateKey = Base64.encodeToString(identityPriv, Base64.NO_WRAP),
                        identityPublicKey = Base64.encodeToString(identityPub, Base64.NO_WRAP),
                        registrationId = random.nextInt(),
                        signedPreKeyId = 1,
                        signedPreKeyPublic = Base64.encodeToString(signedPreKP.second, Base64.NO_WRAP),
                        signedPreKeyPrivate = Base64.encodeToString(signedPreKP.first, Base64.NO_WRAP),
                        signedPreKeySignature = Base64.encodeToString(ByteArray(64), Base64.NO_WRAP),
                        advSecretKey = Base64.encodeToString(advSecretKey, Base64.NO_WRAP),
                    )
                    WhatsAppAuthData.save(appContext, auth)
                    authData = auth
                    _state.value = State.Connected
                    kickoffBackfill()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to handle provisioning message", e)
            }
        }
    }

    private suspend fun connect(auth: WhatsAppAuthData) {
        _state.value = State.Connecting

        // Use WebView-based WebSocket to bypass TLS fingerprinting
        webSocket = WebViewWebSocket(appContext, auth).apply {
            scope.launch {
                connectionState.collect { state ->
                    when (state) {
                        is WebViewWebSocket.ConnectionState.Connected -> {
                            _state.value = State.Connected
                            kickoffBackfill()
                        }
                        is WebViewWebSocket.ConnectionState.Disconnected -> {
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

                // Ack messages, notifications, and receipts (whatsmeow/receipt.go)
                if (node.tag == "message" || node.tag == "notification" || node.tag == "receipt") {
                    val ack = WhatsAppProtocol.buildAck(
                        nodeClass = node.tag,
                        nodeId = node.attrs["id"] ?: "",
                        from = node.attrs["from"] ?: "",
                        participant = node.attrs["participant"],
                        recipient = node.attrs["recipient"],
                        type = if (node.tag != "message") node.attrs["type"] else null,
                    )
                    webSocket?.send(WhatsAppProtocol.encodeNode(ack))
                }

                // Handle receipts (read, delivered) from Go handleWAReceipt
                if (node.tag == "receipt") {
                    val receiptType = node.attrs["type"]
                    if (receiptType == "read" || receiptType == "read-self") {
                        val from = node.attrs["from"] ?: return@launch
                        _events.emit(GMEvent.ReadReceipt(
                            source = MessageSource.WHATSAPP,
                            conversationId = "wa:$from",
                            messageId = node.attrs["id"] ?: "",
                            timestamp = (node.attrs["t"]?.toLongOrNull() ?: System.currentTimeMillis() / 1000) * 1000,
                        ))
                    }
                    return@launch
                }

                // Handle chat presence (typing indicators) from Go handleWAChatPresence
                if (node.tag == "chatstate") {
                    val from = node.attrs["from"] ?: return@launch
                    val composing = node.getChildByTag("composing") != null
                    _events.emit(GMEvent.TypingIndicator(
                        source = MessageSource.WHATSAPP,
                        conversationId = "wa:$from",
                        senderId = from,
                        isTyping = composing,
                    ))
                    return@launch
                }

                if (node.tag != "message") return@launch
                val message = WhatsAppProtocol.parseMessage(node) ?: return@launch

                // Handle revoke (message deletion) from Go handleWAMessage/revoke case
                if (message.isRevoke && message.revokeTargetId != null) {
                    _events.emit(GMEvent.MessageDeleted(
                        source = MessageSource.WHATSAPP,
                        conversationId = "wa:${message.from}",
                        messageId = message.revokeTargetId,
                        timestamp = message.timestamp * 1000,
                    ))
                    return@launch
                }

                // Handle edit from Go handleWAMessage/edit case
                if (message.isEdit && message.editTargetId != null) {
                    _events.emit(GMEvent.MessageEdited(
                        source = MessageSource.WHATSAPP,
                        conversationId = "wa:${message.from}",
                        messageId = message.editTargetId,
                        newBody = message.body,
                        timestamp = message.timestamp * 1000,
                    ))
                    return@launch
                }

                _events.emit(GMEvent.IncomingMessage(
                    source = MessageSource.WHATSAPP,
                    conversationId = "wa:${message.from}",
                    messageId = message.id,
                    body = message.body,
                    peerName = resolveName(message.participant ?: message.from),
                    peerPhone = null,
                    timestamp = message.timestamp * 1000,
                ))

                // Send delivery receipt
                val receiptAttrs = mutableMapOf(
                    "id" to message.id,
                    "to" to message.from
                )
                node.attrs["participant"]?.let { receiptAttrs["participant"] = it }
                node.attrs["recipient"]?.let { receiptAttrs["recipient"] = it }
                val receipt = WhatsAppProtocol.Node(tag = "receipt", attrs = receiptAttrs)
                webSocket?.send(WhatsAppProtocol.encodeNode(receipt))

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
        val id = WhatsAppProtocol.generateMessageId(authData?.wid)

        val node = WhatsAppProtocol.buildTextMessage(to, id, body)
        val data = WhatsAppProtocol.encodeNode(node)

        return ws.send(data)
    }

    private data class MediaUploadResult(
        val url: String,
        val directPath: String,
    )

    private suspend fun uploadMedia(
        encryptedData: ByteArray,
        mediaType: String,
        token: String,
    ): MediaUploadResult = withContext(Dispatchers.IO) {
        val uploadUrl = "https://mmg.whatsapp.net/mms/$mediaType/$token"
        val requestBody = encryptedData.toRequestBody(null)
        val request = Request.Builder()
            .url(uploadUrl)
            .put(requestBody)
            .header("Origin", "https://web.whatsapp.com")
            .header("Referer", "https://web.whatsapp.com/")
            .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/125.0.0.0 Safari/537.36")
            .build()

        val response = httpClient.newCall(request).execute()
        if (!response.isSuccessful) {
            throw Exception("Media upload failed: HTTP ${response.code}")
        }
        val json = JSONObject(response.body?.string() ?: throw Exception("Empty upload response"))
        MediaUploadResult(
            url = json.getString("url"),
            directPath = json.getString("direct_path"),
        )
    }

    suspend fun sendMedia(
        conversationId: String,
        bytes: ByteArray,
        mimeType: String,
        fileName: String?
    ): Boolean {
        if (_state.value !is State.Connected) return false
        val ws = webSocket ?: return false
        val to = extractJid(conversationId) ?: return false
        val id = WhatsAppProtocol.generateMessageId(authData?.wid)

        val mediaType = when {
            mimeType.startsWith("image/") -> "image"
            mimeType.startsWith("video/") -> "video"
            mimeType.startsWith("audio/") -> "audio"
            else -> "document"
        }
        val mediaKeyStr = when (mediaType) {
            "image" -> WhatsAppProtocol.MEDIA_KEY_IMAGE
            "video" -> WhatsAppProtocol.MEDIA_KEY_VIDEO
            "audio" -> WhatsAppProtocol.MEDIA_KEY_AUDIO
            else -> WhatsAppProtocol.MEDIA_KEY_DOCUMENT
        }

        return try {
            val enc = WhatsAppProtocol.encryptMedia(bytes, mediaKeyStr)
            val token = Base64.encodeToString(enc.fileEncSha256, Base64.URL_SAFE or Base64.NO_WRAP)
            val upload = uploadMedia(enc.encryptedData, mediaType, token)
            val node = WhatsAppProtocol.buildMediaMessage(
                to, id, upload.url, upload.directPath,
                enc.mediaKey, enc.fileSha256, enc.fileEncSha256, enc.fileLength,
                mimeType, fileName, mediaType
            )
            ws.send(WhatsAppProtocol.encodeNode(node))
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send media", e)
            false
        }
    }

    suspend fun markRead(conversationId: String, messageIds: List<String> = emptyList()) {
        val to = extractJid(conversationId) ?: return
        val ws = webSocket ?: return
        if (messageIds.isEmpty()) return

        val node = WhatsAppProtocol.buildReadReceipt(chatJid = to, messageIds = messageIds)
        ws.send(WhatsAppProtocol.encodeNode(node))
    }

    suspend fun sendReaction(conversationId: String, messageId: String, emoji: String) {
        if (_state.value !is State.Connected) return
        val ws = webSocket ?: return
        val chatJid = extractJid(conversationId) ?: return
        val id = WhatsAppProtocol.generateMessageId(authData?.wid)
        val ownJid = authData?.wid ?: ""

        val node = WhatsAppProtocol.buildReactionMessage(
            chatJid = chatJid,
            senderJid = "",
            targetMessageId = messageId,
            emoji = emoji,
            ownJid = ownJid,
            id = id,
        )
        ws.send(WhatsAppProtocol.encodeNode(node))
    }

    /**
     * Send a typing indicator (chat presence).
     * From whatsmeow HandleMatrixTyping / SendChatPresence
     */
    suspend fun sendTyping(conversationId: String, isTyping: Boolean, isAudio: Boolean = false) {
        if (_state.value !is State.Connected) return
        val ws = webSocket ?: return
        val chatJid = extractJid(conversationId) ?: return
        val node = WhatsAppProtocol.buildChatPresence(chatJid, isTyping, isAudio, authData?.wid ?: "")
        ws.send(WhatsAppProtocol.encodeNode(node))
    }

    /**
     * Edit a previously sent message.
     * From whatsmeow HandleMatrixEdit / BuildEdit
     */
    suspend fun sendEdit(conversationId: String, targetMessageId: String, newBody: String): Boolean {
        if (_state.value !is State.Connected) return false
        val ws = webSocket ?: return false
        val chatJid = extractJid(conversationId) ?: return false
        val id = WhatsAppProtocol.generateMessageId(authData?.wid)
        val ownJid = authData?.wid ?: ""

        val node = WhatsAppProtocol.buildEditMessage(chatJid, targetMessageId, newBody, ownJid, id)
        return ws.send(WhatsAppProtocol.encodeNode(node))
    }

    /**
     * Revoke (delete) a previously sent message.
     * From whatsmeow HandleMatrixMessageRemove / BuildRevoke
     */
    suspend fun sendRevoke(conversationId: String, targetMessageId: String, senderJid: String = ""): Boolean {
        if (_state.value !is State.Connected) return false
        val ws = webSocket ?: return false
        val chatJid = extractJid(conversationId) ?: return false
        val id = WhatsAppProtocol.generateMessageId(authData?.wid)
        val ownJid = authData?.wid ?: ""

        val node = WhatsAppProtocol.buildRevokeMessage(chatJid, senderJid, targetMessageId, ownJid, id)
        return ws.send(WhatsAppProtocol.encodeNode(node))
    }

    suspend fun sendNewThread(recipientJid: String, body: String): String? {
        if (_state.value !is State.Connected) return null
        val ws = webSocket ?: return null
        val jid = if (recipientJid.contains("@")) recipientJid else "$recipientJid@s.whatsapp.net"
        val id = WhatsAppProtocol.generateMessageId(authData?.wid)
        val node = WhatsAppProtocol.buildTextMessage(jid, id, body)
        val sent = ws.send(WhatsAppProtocol.encodeNode(node))
        return if (sent) "wa:$jid" else null
    }

    suspend fun deleteThread(conversationId: String): Boolean {
        val jid = extractJid(conversationId) ?: return false
        db?.conversationDao()?.delete(jid)
        _events.emit(GMEvent.ConversationDeleted(source, conversationId))
        return true
    }

    fun isLoggedIn(): Boolean {
        return authData != null && _state.value is State.Connected
    }

    /**
     * Logout from WhatsApp server and clear local data.
     * From whatsmeow LogoutRemote
     */
    fun logoutRemote() {
        scope.launch {
            val ws = webSocket
            val ownJid = authData?.wid ?: ""
            if (ws != null && ownJid.isNotEmpty()) {
                val logoutNode = WhatsAppProtocol.Node(
                    tag = "iq",
                    attrs = mapOf(
                        "to" to "s.whatsapp.net",
                        "type" to "set",
                        "xmlns" to "md",
                        "id" to WhatsAppProtocol.generateMessageId(authData?.wid),
                    ),
                    content = listOf(
                        WhatsAppProtocol.Node(
                            tag = "remove-companion-device",
                            attrs = mapOf("jid" to ownJid, "reason" to "user_initiated")
                        )
                    )
                )
                try {
                    ws.send(WhatsAppProtocol.encodeNode(logoutNode))
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to send logout", e)
                }
            }
            stop()
        }
    }

    private fun extractJid(conversationId: String): String? {
        // Conversation ID format: "wa:{jid}"
        return conversationId.removePrefix("wa:")
    }

    private fun generateMessageId(): String {
        return WhatsAppProtocol.generateMessageId(authData?.wid)
    }

    private fun generateRef(): String {
        val bytes = ByteArray(16)
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
            .map { ContactSuggestion(it.value, null, null, MessageSource.WHATSAPP) }
            .take(10)
    }
}
