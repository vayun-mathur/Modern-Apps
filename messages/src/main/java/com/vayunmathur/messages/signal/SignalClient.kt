package com.vayunmathur.messages.signal

import android.content.Context
import android.util.Base64
import android.util.Log
import com.vayunmathur.messages.data.MessageSource
import com.vayunmathur.messages.gmessages.GMEvent
import com.vayunmathur.messages.signal.auth.PreKeyManager
import com.vayunmathur.messages.signal.auth.Provisioning
import com.vayunmathur.messages.signal.contacts.ContactManager
import com.vayunmathur.messages.signal.contacts.ProfileManager
import com.vayunmathur.messages.signal.groups.GroupManager
import com.vayunmathur.messages.signal.groups.SenderKeyManager
import com.vayunmathur.messages.signal.media.AttachmentManager
import com.vayunmathur.messages.signal.receiving.ContentDispatcher
import com.vayunmathur.messages.signal.receiving.DecryptedMessage
import com.vayunmathur.messages.signal.receiving.EnvelopeDecryptor
import com.vayunmathur.messages.signal.receiving.MessageContent
import com.vayunmathur.messages.signal.sending.ContentBuilders
import com.vayunmathur.messages.signal.sending.DeviceManager
import com.vayunmathur.messages.signal.sending.MessageSender
import com.vayunmathur.messages.signal.store.SignalDatabase
import com.vayunmathur.messages.signal.store.SignalGroupStore
import com.vayunmathur.messages.signal.store.SignalIdentityKeyStore
import com.vayunmathur.messages.signal.store.SignalPreKeyStore
import com.vayunmathur.messages.signal.store.SignalRecipientStore
import com.vayunmathur.messages.signal.store.SignalSenderKeyStore
import com.vayunmathur.messages.signal.store.SignalSessionStore
import com.vayunmathur.messages.signal.web.SignalHttpClient
import com.vayunmathur.messages.signal.web.SignalWebSocket
import com.vayunmathur.messages.signal.proto.SignalServiceProtos
import com.vayunmathur.messages.signal.proto.WebSocketProtos
import com.vayunmathur.messages.util.ContactSuggestion
import kotlinx.coroutines.CancellationException
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
import org.signal.libsignal.protocol.IdentityKeyPair
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

object SignalClient {

    private const val TAG = "SignalClient"

    sealed interface State {
        data object Idle : State
        data object NeedsSetup : State
        data class AwaitingQrScan(val qrUrl: String) : State
        data object Connecting : State
        data object Connected : State
        data class Disconnected(val reason: String) : State
    }

    val source: MessageSource = MessageSource.SIGNAL

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()

    private val _events = MutableSharedFlow<GMEvent>(extraBufferCapacity = 256)
    val events: SharedFlow<GMEvent> = _events.asSharedFlow()

    private val initialized = AtomicBoolean(false)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private lateinit var appContext: Context
    private var authData: SignalAuthData? = null
    private var webSocket: SignalWebSocket? = null
    private var backfillJob: Job? = null
    private var provisioningJob: Job? = null

    private var db: SignalDatabase? = null
    private var sessionStore: SignalSessionStore? = null
    private var identityKeyStore: SignalIdentityKeyStore? = null
    private var preKeyStore: SignalPreKeyStore? = null
    private var senderKeyStore: SignalSenderKeyStore? = null
    private var messageSender: MessageSender? = null
    private var contactManager: ContactManager? = null
    private var profileManager: ProfileManager? = null
    private var groupManager: GroupManager? = null

    private val nameCache = ConcurrentHashMap<String, String>()

    fun init(context: Context) {
        if (!initialized.compareAndSet(false, true)) return
        appContext = context.applicationContext
        Log.i(TAG, "init")
        runBlocking {
            val auth = SignalAuthData.load(appContext)
            if (auth != null) {
                authData = auth
                bootSession(auth)
            } else {
                _state.value = State.NeedsSetup
            }
        }
    }

    fun start() {
        if (!initialized.get()) return
        if (_state.value is State.Connected) return
        scope.launch {
            val auth = SignalAuthData.load(appContext) ?: run {
                _state.value = State.NeedsSetup
                return@launch
            }
            authData = auth
            bootSession(auth)
        }
    }

    fun stop() {
        Log.i(TAG, "stop — clearing Signal session")
        backfillJob?.cancel()
        provisioningJob?.cancel()
        webSocket?.disconnect()
        webSocket = null
        messageSender = null
        contactManager = null
        profileManager = null
        groupManager = null
        nameCache.clear()
        scope.launch { SignalAuthData.clear(appContext) }
        _state.value = State.NeedsSetup
    }

    fun startProvisioning() {
        _state.value = State.Connecting
        provisioningJob?.cancel()
        provisioningJob = scope.launch {
            try {
                Provisioning.startProvisioning(appContext).collect { event ->
                    when (event) {
                        is Provisioning.ProvisioningEvent.QrUrl -> {
                            _state.value = State.AwaitingQrScan(event.url)
                        }
                        is Provisioning.ProvisioningEvent.Success -> {
                            val data = event.deviceData
                            val auth = SignalAuthData(
                                aci = data.aci,
                                pni = data.pni,
                                deviceId = data.deviceId,
                                number = data.number,
                                password = data.password,
                                aciIdentityKeyPair = data.aciIdentityKeyPair,
                                pniIdentityKeyPair = data.pniIdentityKeyPair,
                                aciRegistrationId = data.aciRegistrationId,
                                pniRegistrationId = data.pniRegistrationId,
                                profileKey = data.profileKey,
                            )
                            auth.save(appContext)
                            authData = auth
                            bootSession(auth)
                        }
                        is Provisioning.ProvisioningEvent.Error -> {
                            Log.e(TAG, "Provisioning error: ${event.message}")
                            _state.value = State.Disconnected("Provisioning failed: ${event.message}")
                        }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Provisioning failed", e)
                _state.value = State.Disconnected("Provisioning failed: ${e.message}")
            }
        }
    }

    fun forceResync() {
        if (_state.value !is State.Connected) return
        kickoffBackfill()
    }

    suspend fun sendMessage(conversationId: String, body: String): Boolean {
        if (_state.value !is State.Connected) return false
        val sender = messageSender ?: return false
        val recipientAci = extractAci(conversationId) ?: return false
        val timestamp = System.currentTimeMillis()
        val content = ContentBuilders.textMessage(body, timestamp)
        val result = sender.sendMessage(recipientAci, content, timestamp)
        return result.success
    }

    suspend fun sendMedia(
        conversationId: String,
        bytes: ByteArray,
        mime: String,
        fileName: String,
        caption: String?,
    ): Boolean {
        if (_state.value !is State.Connected) return false
        return try {
            val pointer = AttachmentManager.upload(bytes, mime, fileName) ?: return false
            val sender = messageSender ?: return false
            val recipientAci = extractAci(conversationId) ?: return false
            val timestamp = System.currentTimeMillis()
            val dataMessage = SignalServiceProtos.DataMessage.newBuilder()
                .setTimestamp(timestamp)
                .addAttachments(pointer)
            if (!caption.isNullOrBlank()) dataMessage.setBody(caption)
            val content = SignalServiceProtos.Content.newBuilder()
                .setDataMessage(dataMessage.build())
                .build()
            val result = sender.sendMessage(recipientAci, content, timestamp)
            result.success
        } catch (t: Throwable) {
            Log.w(TAG, "sendMedia failed: ${t.message}")
            false
        }
    }

    suspend fun markRead(conversationId: String): Boolean {
        if (_state.value !is State.Connected) return false
        val sender = messageSender ?: return false
        val recipientAci = extractAci(conversationId) ?: return false
        return try {
            val content = ContentBuilders.readReceipt(listOf(System.currentTimeMillis()))
            sender.sendMessage(recipientAci, content, System.currentTimeMillis())
            true
        } catch (t: Throwable) {
            Log.w(TAG, "markRead failed: ${t.message}")
            false
        }
    }

    suspend fun deleteThread(conversationId: String): Boolean {
        return true
    }

    suspend fun sendReaction(
        messageId: String,
        conversationId: String,
        emoji: String,
        add: Boolean,
    ): Boolean {
        if (_state.value !is State.Connected) return false
        val sender = messageSender ?: return false
        val recipientAci = extractAci(conversationId) ?: return false
        val targetTimestamp = messageId.substringAfterLast('_').toLongOrNull() ?: return false
        val content = ContentBuilders.reactionMessage(emoji, recipientAci, targetTimestamp, !add)
        val timestamp = System.currentTimeMillis()
        val result = sender.sendMessage(recipientAci, content, timestamp)
        return result.success
    }

    suspend fun sendTyping(conversationId: String): Boolean {
        if (_state.value !is State.Connected) return false
        val sender = messageSender ?: return false
        val recipientAci = extractAci(conversationId) ?: return false
        val content = ContentBuilders.typingMessage(true, System.currentTimeMillis())
        val result = sender.sendMessage(recipientAci, content, System.currentTimeMillis())
        return result.success
    }

    fun fetchMessages(conversationId: String, count: Int = 50) {
        // Signal doesn't support fetching history for linked devices
    }

    suspend fun sendNewThread(recipients: List<String>, body: String): String? {
        if (recipients.isEmpty()) return null
        if (_state.value !is State.Connected) return null
        val sender = messageSender ?: return null
        val recipientAci = recipients.first()
        val timestamp = System.currentTimeMillis()
        val content = ContentBuilders.textMessage(body, timestamp)
        val result = sender.sendMessage(recipientAci, content, timestamp)
        return if (result.success) "${source.idPrefix}:$recipientAci" else null
    }

    suspend fun searchContacts(query: String): List<ContactSuggestion> {
        return contactManager?.searchContacts(query) ?: emptyList()
    }

    // ----------------------------------------------------------------
    // Internals
    // ----------------------------------------------------------------

    private suspend fun bootSession(auth: SignalAuthData) {
        _state.value = State.Connecting
        try {
            val database = SignalDatabase.getInstance(appContext)
            db = database

            val sessStore = SignalSessionStore(database)
            val idStore = SignalIdentityKeyStore(
                database,
                auth.aciIdentityKeyPair,
                auth.aciRegistrationId,
            )
            val pkStore = SignalPreKeyStore(database)
            val skStore = SignalSenderKeyStore(database)
            sessionStore = sessStore
            identityKeyStore = idStore
            preKeyStore = pkStore
            senderKeyStore = skStore

            val basicAuth = "${auth.aci}.${auth.deviceId}:${auth.password}"
            val ws = SignalWebSocket(
                appContext,
                android.util.Base64.encodeToString(
                    basicAuth.toByteArray(),
                    android.util.Base64.NO_WRAP,
                ),
            )
            ws.connect("wss://chat.signal.org/v1/websocket/?login=${auth.aci}.${auth.deviceId}")
            webSocket = ws

            val devManager = DeviceManager(ws, sessStore, idStore, pkStore, pkStore, pkStore, skStore)
            val sender = MessageSender(ws, sessStore, idStore, pkStore, pkStore, pkStore, skStore, auth.aci, auth.deviceId, devManager)
            messageSender = sender

            val recipientStore = SignalRecipientStore(database)
            contactManager = ContactManager(recipientStore)
            profileManager = ProfileManager(ws, recipientStore)
            groupManager = GroupManager(ws, SignalGroupStore(database))

            ws.incomingRequestHandler = { request ->
                handleIncomingRequest(request, auth, sessStore, idStore, pkStore, skStore)
            }

            _state.value = State.Connected
            Log.i(TAG, "Connected to Signal")
            kickoffBackfill()

            scope.launch {
                ws.connectionEvents.collect { event ->
                    when (event) {
                        is SignalWebSocket.ConnectionEvent.Connected -> {
                            _state.value = State.Connected
                        }
                        is SignalWebSocket.ConnectionEvent.Disconnected -> {
                            _state.value = State.Disconnected(event.reason)
                        }
                        is SignalWebSocket.ConnectionEvent.LoggedOut -> {
                            _state.value = State.Disconnected("Logged out")
                            stop()
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "bootSession failed", e)
            _state.value = State.Disconnected("Boot failed: ${e.message}")
        }
    }

    private fun handleIncomingRequest(
        request: WebSocketProtos.WebSocketRequestMessage,
        auth: SignalAuthData,
        sessStore: SignalSessionStore,
        idStore: SignalIdentityKeyStore,
        pkStore: SignalPreKeyStore,
        skStore: SignalSenderKeyStore,
    ) {
        if (request.verb != "PUT" || request.path != "/api/v1/message") {
            webSocket?.sendResponse(request.id, 200)
            return
        }
        scope.launch {
            try {
                val envelope = SignalServiceProtos.Envelope.parseFrom(request.body)
                val result = EnvelopeDecryptor.decrypt(
                    envelope, sessStore, idStore, pkStore, pkStore, pkStore, skStore,
                    null, auth.aci, auth.deviceId,
                )
                webSocket?.sendResponse(request.id, 200)

                if (result.content != null) {
                    val decrypted = ContentDispatcher.dispatch(
                        result.senderAci, result.senderDeviceId,
                        result.content, result.timestamp, result.serverTimestamp,
                    )
                    handleDecryptedMessage(decrypted)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to handle incoming message", e)
                webSocket?.sendResponse(request.id, 200)
            }
        }
    }

    private suspend fun handleDecryptedMessage(msg: DecryptedMessage) {
        val chatId = msg.senderAci
        val senderName = nameCache[msg.senderAci] ?: resolveDisplayName(msg.senderAci)

        when (val content = msg.content) {
            is MessageContent.TextMessage -> {
                val msgId = "${chatId}_${msg.timestamp}"
                _events.emit(GMEvent.MessageUpdate(source, chatId, msgId, content.body, false, msg.timestamp, senderName))
                _events.emit(GMEvent.IncomingMessage(source, chatId, msgId, content.body, senderName, null, msg.timestamp))
                _events.emit(GMEvent.ConversationUpdate(
                    source = source, conversationId = chatId,
                    peerName = senderName, peerPhone = null, avatarUrl = null,
                    lastPreview = content.body, lastTimestamp = msg.timestamp,
                    unreadCount = 1, conversationType = "Signal",
                ))
            }
            is MessageContent.Attachment -> {
                val msgId = "${chatId}_${msg.timestamp}"
                val body = content.body ?: "[Attachment]"
                _events.emit(GMEvent.MessageUpdate(source, chatId, msgId, body, false, msg.timestamp, senderName))
                _events.emit(GMEvent.IncomingMessage(source, chatId, msgId, body, senderName, null, msg.timestamp))
            }
            is MessageContent.Reaction -> {
                // Reactions are handled by updating the message
            }
            is MessageContent.Delete -> {
                val msgId = "${chatId}_${content.targetTimestamp}"
                _events.emit(GMEvent.MessageDeleted(source, msgId))
            }
            is MessageContent.SyncSent -> {
                val destAci = content.destinationAci ?: return
                val syncContent = content.message ?: return
                if (syncContent is MessageContent.TextMessage) {
                    val msgId = "${destAci}_${content.timestamp}"
                    _events.emit(GMEvent.MessageUpdate(source, destAci, msgId, syncContent.body, true, content.timestamp, null))
                }
            }
            is MessageContent.Typing -> {}
            is MessageContent.ReadReceipt -> {}
            is MessageContent.DeliveryReceipt -> {}
            is MessageContent.Call -> {}
            is MessageContent.Edit -> {
                val msgId = "${chatId}_${content.targetTimestamp}"
                _events.emit(GMEvent.MessageUpdate(source, chatId, msgId, content.newBody, false, msg.timestamp, senderName))
            }
            is MessageContent.SyncRead -> {}
            is MessageContent.Unknown -> {
                Log.d(TAG, "Unknown content: ${content.description}")
            }
        }
    }

    private suspend fun resolveDisplayName(aci: String): String? {
        nameCache[aci]?.let { return it }
        val name = contactManager?.getDisplayName(aci)
        if (name != null) nameCache[aci] = name
        return name
    }

    private fun kickoffBackfill() {
        backfillJob?.cancel()
        backfillJob = scope.launch {
            Log.i(TAG, "kickoffBackfill — Signal backfill is contact-sync driven")
            _events.emit(GMEvent.ConversationUpdate(
                source = source, conversationId = "_backfill_sentinel",
                peerName = null, peerPhone = null, avatarUrl = null,
                lastPreview = null, lastTimestamp = 0, unreadCount = 0,
            ))
        }
    }

    private fun extractAci(conversationId: String): String? {
        return conversationId.substringAfter(':', conversationId).takeIf { it.isNotBlank() }
    }
}
