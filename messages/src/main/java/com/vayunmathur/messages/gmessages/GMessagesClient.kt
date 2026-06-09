package com.vayunmathur.messages.gmessages

import android.content.Context
import android.util.Base64
import android.util.Log
import authentication.Authentication
import client.Client.ListConversationsRequest
import client.Client.ListConversationsResponse
import client.Client.ListContactsRequest
import client.Client.ListContactsResponse
import client.Client.ListTopContactsRequest
import client.Client.ListTopContactsResponse
import client.Client.GetOrCreateConversationRequest
import client.Client.GetOrCreateConversationResponse
import client.Client.ListMessagesRequest
import client.Client.ListMessagesResponse
import client.Client.MessagePayload
import client.Client.MessageReadRequest
import client.Client.SendMessageRequest
import client.Client.SendMessageResponse
import client.Client.SendReactionRequest
import client.Client.SendReactionResponse
import client.Client.TypingUpdateRequest
import client.Client.UpdateConversationRequest
import client.Client.UpdateConversationResponse
import client.Client.DeleteConversationData
import client.Client.ConversationActionStatus
import client.Client.DeleteMessageRequest
import client.Client.DeleteMessageResponse
import com.google.protobuf.ByteString
import com.vayunmathur.messages.data.MessageSource
import com.vayunmathur.messages.util.ContactResolver
import conversations.Conversations.Conversation
import conversations.Conversations.Message
import conversations.Conversations.MessageInfo
import conversations.Conversations.MessageContent
import conversations.Conversations.ReactionData
import events.Events.UpdateEvents
import java.util.UUID
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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import rpc.Rpc.ActionType
import rpc.Rpc.MessageType
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Process-global owner of the Google-Messages-for-Web protocol session.
 *
 * Lifecycle:
 *  1. [init] is called once by the foreground service. We load any
 *     persisted [AuthData] off disk.
 *  2. [start] is idempotent: if persisted auth exists, opens the
 *     long-poll and emits Connected. Otherwise sits Idle.
 *  3. [startPair] hits Pairing/RegisterPhoneRelay, stores the initial
 *     tachyon token, opens the long-poll so we can receive the Paired
 *     event, then returns the QR URL for the UI to render.
 *  4. The long-poll dispatches a Paired event → we persist the new
 *     device info + tachyon token, transition to Connected, and trigger
 *     the conversation backfill.
 *  5. [stop] cancels everything and clears the persisted auth.
 *
 * For v1 we LIST_CONVERSATIONS on connect (for backfill / inbox
 * population). [sendMessage] is wired through but stubbed — building
 * the full `SendMessageRequest` payload structure requires more proto
 * coverage than what's needed for receiving and is deferred.
 */
object GMessagesClient {

    private const val TAG = "GMessagesClient"
    
    /** Refresh token if it expires within this buffer (1 hour). Matches Go implementation. */
    private const val REFRESH_TACHYON_BUFFER_MS = 60 * 60 * 1000L

    /** Default TTL when the server returns 0 (24 hours in microseconds). Matches Go `updateTachyonAuthToken`. */
    private const val DEFAULT_TTL_US = 24L * 60 * 60 * 1_000_000

    sealed interface State {
        data object Idle : State
        data class Pairing(val qrUrl: String) : State
        data object Connected : State
        data class Disconnected(val reason: String) : State
    }

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()

    private val _events = MutableSharedFlow<GMEvent>(extraBufferCapacity = 256)
    val events: SharedFlow<GMEvent> = _events.asSharedFlow()

    val source: MessageSource = MessageSource.MESSAGES_WEB

    private val initialized = AtomicBoolean(false)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private lateinit var appContext: Context
    private val rpc = RpcClient()
    @Volatile private var auth: AuthData = AuthData.generateInitial()
    private val authMutex = Mutex()
    private val sessionHandler = SessionHandler(rpc) { auth }
    private val media = Media { auth }
    private val longPoll = LongPoll(
        rpc = rpc,
        authProvider = { auth },
        sessionHandler = sessionHandler,
        onEvent = ::handleLongPollEvent,
        refreshToken = ::refreshAuthToken,
    )
    private var backfillJob: Job? = null

    /** Per-conversation "my SIM" participant ID surfaced by
     *  LIST_CONVERSATIONS.defaultOutgoingID. Used to populate the
     *  participantID on outgoing SendMessageRequest payloads. */
    private val outgoingIds = java.util.concurrent.ConcurrentHashMap<String, String>()

    @Volatile private var conversationsFetchedOnce = false

    fun init(context: Context) {
        if (!initialized.compareAndSet(false, true)) return
        appContext = context.applicationContext
        Log.i(TAG, "init")
        scope.launch {
            AuthData.load(appContext)?.let { auth = it }
            if (auth.isPaired()) {
                Log.i(TAG, "found persisted pair, resuming long-poll")
                longPoll.start(scope)
                _state.value = State.Connected
                postConnect()
            }
        }
    }

    fun start() {
        if (!initialized.get()) return
        if (auth.isPaired() && _state.value !is State.Connected) {
            longPoll.start(scope)
            _state.value = State.Connected
            scope.launch { postConnect() }
        }
    }

    fun stop() {
        Log.i(TAG, "stop — clearing pair")
        backfillJob?.cancel()
        longPoll.stop()
        sessionHandler.cancelAll()
        val freshAuth = AuthData.generateInitial()
        auth = freshAuth
        scope.launch { freshAuth.save(appContext) }
        outgoingIds.clear()
        conversationsFetchedOnce = false
        rpc.close()
        media.close()
        _state.value = State.Idle
    }

    suspend fun startPair(): String {
        try {
            val qrUrl = authMutex.withLock {
                // Fresh keys for a fresh pair.
                auth = AuthData.generateInitial()
                val result = PairFlow.registerAndBuildQrUrl(rpc, auth)
                val ttlUs = result.tachyonTtlUs.let { if (it == 0L) DEFAULT_TTL_US else it }
                auth = auth.copy(
                    tachyonAuthTokenB64 = android.util.Base64.encodeToString(result.tachyonToken, android.util.Base64.NO_WRAP),
                    tachyonTtlUs = ttlUs,
                    tachyonExpiryMs = System.currentTimeMillis() + (ttlUs / 1000),
                )
                result.qrUrl
            }
            // Open the long-poll now so we receive the Paired event when the
            // user finishes scanning.
            longPoll.start(scope)
            _state.value = State.Pairing(qrUrl)
            return qrUrl
        } catch (t: Throwable) {
            Log.e(TAG, "startPair failed", t)
            _state.value = State.Disconnected("Pair failed: ${t.message}")
            throw t
        }
    }

    /**
     * Send a text message via SEND_MESSAGE.
     *
     * Mirrors `ConvertMatrixMessage` + `Client.SendMessage` in
     * `pkg/connector/handlematrix.go` / `pkg/libgm/methods.go`:
     * builds a [SendMessageRequest] with a [MessageContent] info part,
     * a fresh `tmp_…` transaction ID, and the per-conversation
     * `participantID` we captured from LIST_CONVERSATIONS.
     *
     * Returns `true` iff the relay reports `SUCCESS`.
     */
    suspend fun sendMessage(conversationId: String, body: String): Boolean {
        if (_state.value !is State.Connected) return false
        val webId = conversationId.substringAfter(':', conversationId)
        val info = MessageInfo.newBuilder()
            .setMessageContent(MessageContent.newBuilder().setContent(body))
            .build()
        return sendWithInfos(webId, listOf(info))
    }

    /**
     * Send an image (or any media) via SEND_MESSAGE. Uploads the bytes
     * first via [Media.upload], then attaches the resulting
     * [MediaContent] to a SendMessageRequest. If [caption] is non-blank,
     * a separate MessageContent info part is appended so the recipient
     * sees image + caption like Google Messages does.
     */
    suspend fun sendMedia(
        conversationId: String,
        data: ByteArray,
        mime: String,
        fileName: String,
        caption: String?,
    ): Boolean {
        if (_state.value !is State.Connected) return false
        val webId = conversationId.substringAfter(':', conversationId)
        val mediaContent = try {
            media.upload(data, fileName, mime)
        } catch (t: Throwable) {
            Log.w(TAG, "media upload failed: ${t.message}")
            return false
        }
        val infos = mutableListOf(
            MessageInfo.newBuilder().setMediaContent(mediaContent).build()
        )
        if (!caption.isNullOrBlank()) {
            infos += MessageInfo.newBuilder()
                .setMessageContent(MessageContent.newBuilder().setContent(caption))
                .build()
        }
        return sendWithInfos(webId, infos)
    }

    /** Common SEND_MESSAGE plumbing — builds the envelope and awaits a response. */
    private suspend fun sendWithInfos(webId: String, infos: List<MessageInfo>): Boolean {
        val tmpId = "tmp_${kotlin.random.Random.nextLong(1_000_000_000_000L).toString().padStart(12, '0')}"
        val participantId = outgoingIds[webId].orEmpty()
        val payload = MessagePayload.newBuilder()
            .setTmpID(tmpId)
            .setConversationID(webId)
            .setParticipantID(participantId)
            .setTmpID2(tmpId)
            .addAllMessageInfo(infos)
            .build()
        val req = SendMessageRequest.newBuilder()
            .setConversationID(webId)
            .setMessagePayload(payload)
            .setTmpID(tmpId)
            .build()
        val resp = sessionHandler.sendAndWait(ActionType.SEND_MESSAGE, req)
            ?: return false.also { Log.w(TAG, "SEND_MESSAGE timed out") }
        val data = resp.decryptedData ?: return false
        val parsed = runCatching { SendMessageResponse.parseFrom(data) }.getOrNull()
            ?: return false
        Log.i(TAG, "SEND_MESSAGE status=${parsed.status}")
        return parsed.status == SendMessageResponse.Status.SUCCESS
    }

    /**
     * Mark messages in [conversationId] as read up to [messageId].
     * Mirrors `Client.MarkRead`.
     */
    suspend fun markRead(conversationId: String, messageId: String?): Boolean {
        if (_state.value !is State.Connected) return false
        val webId = conversationId.substringAfter(':', conversationId)
        val msgWebId = messageId?.substringAfter(':', messageId).orEmpty()
        val req = MessageReadRequest.newBuilder()
            .setConversationID(webId)
            .setMessageID(msgWebId)
            .build()
        val resp = sessionHandler.sendAndWait(ActionType.MESSAGE_READ, req)
        return resp != null
    }

    /**
     * Delete [conversationId] on the phone via UPDATE_CONVERSATION
     * with [ConversationActionStatus.DELETE]. The phone is part of the
     * proto because the relay routes the delete to the correct SIM /
     * thread on the device.
     */
    suspend fun deleteConversation(conversationId: String, phone: String?): Boolean {
        if (_state.value !is State.Connected) return false
        val webId = conversationId.substringAfter(':', conversationId)
        val data = DeleteConversationData.newBuilder()
            .setConversationID(webId)
            .apply { if (!phone.isNullOrBlank()) setPhone(phone) }
            .build()
        val req = UpdateConversationRequest.newBuilder()
            .setAction(ConversationActionStatus.DELETE)
            .setConversationID(webId)
            .setDeleteData(data)
            .build()
        val resp = sessionHandler.sendAndWait(ActionType.UPDATE_CONVERSATION, req)
            ?: return false
        val body = resp.decryptedData ?: return false
        val parsed = runCatching { UpdateConversationResponse.parseFrom(body) }.getOrNull()
        return parsed?.success == true
    }

    /**
     * Add / remove / switch a reaction on [messageId].
     * Mirrors `Client.SendReaction`. We send only the unicode emoji and
     * `EmojiType.CUSTOM` is reserved for actual stickers — we always
     * pass `REACTION_TYPE_UNSPECIFIED` so the relay infers the type
     * from the unicode codepoint (Google's web client does the same).
     */
    suspend fun sendReaction(
        messageId: String,
        emoji: String,
        action: SendReactionRequest.Action,
    ): Boolean {
        if (_state.value !is State.Connected) return false
        val msgWebId = messageId.substringAfter(':', messageId)
        val req = SendReactionRequest.newBuilder()
            .setMessageID(msgWebId)
            .setAction(action)
            .setReactionData(ReactionData.newBuilder().setUnicode(emoji))
            .build()
        val resp = sessionHandler.sendAndWait(ActionType.SEND_REACTION, req) ?: return false
        val body = resp.decryptedData ?: return false
        val parsed = runCatching { SendReactionResponse.parseFrom(body) }.getOrNull()
        return parsed?.success == true
    }

    /**
     * Notify the peer that the user is currently typing.
     * Fire-and-forget (no waiter).
     */
    suspend fun sendTyping(conversationId: String): Boolean {
        if (_state.value !is State.Connected) return false
        val webId = conversationId.substringAfter(':', conversationId)
        val req = TypingUpdateRequest.newBuilder()
            .setData(
                TypingUpdateRequest.Data.newBuilder()
                    .setConversationID(webId)
                    .setTyping(true)
            )
            .build()
        return sessionHandler.sendNoWait(ActionType.TYPING_UPDATES, req)
    }

    /**
     * Delete a single message via DELETE_MESSAGE. The relay echoes the
     * delete through the long-poll's MESSAGE_DELETED event when
     * complete — local Room rows are cleared via that event path so
     * the UI updates once for both local-initiated and remote-initiated
     * deletes.
     */
    suspend fun deleteMessage(messageId: String): Boolean {
        if (_state.value !is State.Connected) return false
        val webId = messageId.substringAfter(':', messageId)
        val req = DeleteMessageRequest.newBuilder().setMessageID(webId).build()
        val resp = sessionHandler.sendAndWait(ActionType.DELETE_MESSAGE, req) ?: return false
        val body = resp.decryptedData ?: return false
        val parsed = runCatching { DeleteMessageResponse.parseFrom(body) }.getOrNull()
        return parsed?.success == true
    }

    /**
     * Create (or return an existing) conversation for [numbers].
     *
     * Mirrors `Client.GetOrCreateConversation` (`pkg/libgm/methods.go:52`).
     * Single phone = 1:1 thread. Multiple phones = SMS group, or RCS
     * group when [createRcsGroup] + [rcsGroupName] is supplied.
     *
     * Returns the source-prefixed conversation id of the (possibly
     * newly-created) thread, or null on failure. The relay emits a
     * GET_UPDATES with the new conversation immediately after; we also
     * emit a synthetic [GMEvent.ConversationUpdate] so the inbox sees
     * the row before the long-poll catches up.
     */
    suspend fun getOrCreateConversation(
        numbers: List<String>,
        rcsGroupName: String? = null,
        createRcsGroup: Boolean = false,
    ): String? {
        if (numbers.isEmpty() || _state.value !is State.Connected) return null
        val req = GetOrCreateConversationRequest.newBuilder()
            .addAllNumbers(numbers.map {
                conversations.Conversations.ContactNumber.newBuilder()
                    .setMysteriousInt(2)  // 2 = "from contact list", per libgm
                    .setNumber(it)
                    .build()
            })
            .apply {
                if (createRcsGroup) {
                    setCreateRCSGroup(true)
                    rcsGroupName?.let { setRCSGroupName(it) }
                }
            }
            .build()
        val resp = sessionHandler.sendAndWait(ActionType.GET_OR_CREATE_CONVERSATION, req)
            ?: return null
        val parsed = runCatching {
            GetOrCreateConversationResponse.parseFrom(resp.decryptedData ?: return null)
        }.getOrNull() ?: return null

        if (parsed.status == GetOrCreateConversationResponse.Status.CREATE_RCS) {
            // The server is telling us "you asked for RCS but this set
            // of participants isn't RCS-capable — try again without".
            // The bridge does the same retry (handlematrix.go).
            Log.i(TAG, "getOrCreate: CREATE_RCS — retrying with SMS")
            return getOrCreateConversation(numbers, rcsGroupName = null, createRcsGroup = false)
        }
        if (!parsed.hasConversation()) {
            Log.w(TAG, "getOrCreate: status=${parsed.status} but no conversation")
            return null
        }
        val conv = parsed.conversation
        // Eagerly emit a synthetic ConversationUpdate so the inbox row
        // appears before the long-poll's GET_UPDATES delivers the same
        // info. emitConversation is idempotent via Room upsert so the
        // duplicate from the long-poll is harmless.
        emitConversation(conv)
        return "${source.idPrefix}:${conv.conversationID}"
    }

    /**
     * Server-side contact list, used to populate the recipient picker.
     * Mirrors `Client.ListContacts` (`pkg/libgm/methods.go:34`).
     * The bridge passes hardcoded magic ints (1, 350, 50) that we
     * preserve verbatim — the relay rejects requests without them.
     */
    suspend fun listContacts(): List<com.vayunmathur.messages.util.ContactSuggestion> {
        if (_state.value !is State.Connected) return emptyList()
        val req = ListContactsRequest.newBuilder()
            .setI1(1)
            .setI2(350)
            .setI3(50)
            .build()
        val resp = sessionHandler.sendAndWait(ActionType.LIST_CONTACTS, req) ?: return emptyList()
        val parsed = runCatching {
            ListContactsResponse.parseFrom(resp.decryptedData ?: return emptyList())
        }.getOrNull() ?: return emptyList()
        return (0 until parsed.contactsCount).mapNotNull { i ->
            parsed.getContacts(i).toSuggestion()
        }
    }

    /** Server-side "frequent contacts" — used as the picker's initial
     *  suggestion list before the user types anything. */
    suspend fun listTopContacts(count: Int = 8): List<com.vayunmathur.messages.util.ContactSuggestion> {
        if (_state.value !is State.Connected) return emptyList()
        val req = ListTopContactsRequest.newBuilder().setCount(count).build()
        val resp = sessionHandler.sendAndWait(ActionType.LIST_TOP_CONTACTS, req) ?: return emptyList()
        val parsed = runCatching {
            ListTopContactsResponse.parseFrom(resp.decryptedData ?: return emptyList())
        }.getOrNull() ?: return emptyList()
        return (0 until parsed.contactsCount).mapNotNull { i ->
            parsed.getContacts(i).toSuggestion()
        }
    }

    private fun conversations.Conversations.Contact.toSuggestion(): com.vayunmathur.messages.util.ContactSuggestion? {
        val phone = number?.number?.takeIf { it.isNotBlank() }
            ?: number?.number2?.takeIf { it.isNotBlank() }
            ?: return null
        val displayName = name.takeIf { it.isNotBlank() } ?: phone
        return com.vayunmathur.messages.util.ContactSuggestion(
            displayName = displayName,
            phoneE164 = phone,
            avatarUrl = null,
            source = source,
        )
    }

    @Suppress("UNUSED_PARAMETER")
    @Deprecated("kept for binary compat — call sendMessage(conversationId, body) directly", level = DeprecationLevel.HIDDEN)
    suspend fun sendMessageLegacy(conversationId: String, body: String): Boolean =
        sendMessage(conversationId, body)

    /**
     * Pump for inbound real-time updates pushed by the relay.
     *
     * Each GET_UPDATES message wraps an [UpdateEvents] oneof. We handle
     * the three high-signal kinds:
     *  - [UpdateEvents.MessageEvent]: new/edited messages → emit
     *    MessageUpdate (+ IncomingMessage for non-outgoing).
     *  - [UpdateEvents.ConversationEvent]: conversation metadata bumps
     *    → re-emit the row via [emitConversation].
     *  - [UpdateEvents.TypingEvent]: kept here for completeness; no UI
     *    yet, so we just log.
     *
     * Anything else is logged at debug and ignored. The bridge itself
     * has handlers for stickers / settings / participant events; those
     * are out of scope for v1.
     */
    private suspend fun handleGetUpdates(data: ByteArray) {
        val updates = runCatching { UpdateEvents.parseFrom(data) }.getOrNull() ?: run {
            Log.w(TAG, "GET_UPDATES: failed to parse UpdateEvents")
            return
        }
        when {
            updates.hasMessageEvent() -> {
                val msgs = updates.messageEvent.dataList
                Log.i(TAG, "GET_UPDATES: ${msgs.size} message event(s)")
                msgs.forEach { emitMessage(it) }
            }
            updates.hasConversationEvent() -> {
                val convs = updates.conversationEvent.dataList
                Log.i(TAG, "GET_UPDATES: ${convs.size} conversation event(s)")
                convs.forEach { emitConversation(it) }
            }
            updates.hasTypingEvent() -> {
                val data2 = updates.typingEvent.data
                Log.d(TAG, "GET_UPDATES typing conv=${data2.conversationID} type=${data2.type}")
            }
            updates.hasUserAlertEvent() -> {
                Log.d(TAG, "GET_UPDATES user-alert: ${updates.userAlertEvent.alertType}")
            }
            else -> Log.d(TAG, "GET_UPDATES: unhandled event kind")
        }
    }

    /**
     * Load the recent messages for [conversationId] via LIST_MESSAGES.
     * The response flows back through the long-poll into
     * [handleDataMessage] which calls [emitMessage] for each row.
     *
     * Idempotent on the wire — Room upsert deduplicates by message id.
     * Strip the source prefix because the relay only knows Google's
     * thread id.
     */
    fun fetchMessages(conversationId: String, count: Int = 100) {
        if (_state.value !is State.Connected) return
        scope.launch {
            val webId = conversationId.substringAfter(':', conversationId)
            Log.i(TAG, "fetchMessages convId=$webId count=$count")
            val req = ListMessagesRequest.newBuilder()
                .setConversationID(webId)
                .setCount(count.toLong())
                .build()
            val resp = sessionHandler.sendAndWait(ActionType.LIST_MESSAGES, req)
            if (resp == null) Log.w(TAG, "fetchMessages: no response")
        }
    }

    /**
     * Post-connect sequence matching Go's `postConnect`:
     * wait for long-poll to settle, set active session, then backfill.
     */
    private suspend fun postConnect() {
        kotlinx.coroutines.delay(2_000)
        sessionHandler.setActiveSession()
        kotlinx.coroutines.delay(1_000)
        kickoffBackfill()
    }

    fun forceResync() {
        if (_state.value !is State.Connected) return
        scope.launch {
            // Try to refresh token first, then do backfill
            refreshAuthToken()
            kickoffBackfill()
        }
    }

    /**
     * Refresh the tachyon auth token if it's about to expire.
     * Based on Go implementation in pkg/libgm/client.go.
     * Uses the RegisterRefresh endpoint with ECDSA-signed request.
     */
    /**
     * Refresh the tachyon auth token if it's about to expire.
     * Port of `refreshAuthToken` in `pkg/libgm/client.go`.
     */
    suspend fun refreshAuthToken() {
        val currentAuth = auth
        val browser = currentAuth.browser() ?: return

        val now = System.currentTimeMillis()
        val timeUntilExpiry = currentAuth.tachyonExpiryMs - now
        if (timeUntilExpiry > REFRESH_TACHYON_BUFFER_MS) {
            Log.d(TAG, "Token refresh not needed, expires in ${timeUntilExpiry / 1000}s")
            return
        }

        Log.i(TAG, "Refreshing auth token (expires in ${timeUntilExpiry / 1000}s)")

        try {
            val requestId = UUID.randomUUID().toString()
            val timestamp = System.currentTimeMillis() * 1000

            // sign() uses SHA256withECDSA which hashes internally —
            // pass the raw bytes, NOT a pre-computed SHA-256 digest.
            val signData = "$requestId:$timestamp".toByteArray(Charsets.UTF_8)
            val signature = currentAuth.refreshKey.sign(signData)

            val tachyonToken = currentAuth.tachyonToken() ?: return
            val authMessage = Authentication.AuthMessage.newBuilder()
                .setRequestID(requestId)
                .setTachyonAuthToken(ByteString.copyFrom(tachyonToken))
                .setNetwork(PairFlow.QrNetwork)
                .setConfigVersion(PairFlow.ConfigVersion)
                .build()

            val refreshRequest = Authentication.RegisterRefreshRequest.newBuilder()
                .setMessageAuth(authMessage)
                .setCurrBrowserDevice(browser)
                .setUnixTimestamp(timestamp)
                .setSignature(ByteString.copyFrom(signature))
                .setParameters(
                    Authentication.RegisterRefreshRequest.Parameters.newBuilder()
                        .setEmptyArr(util.Util.EmptyArr.getDefaultInstance())
                        .build()
                )
                .setMessageType(2)
                .build()

            val response = rpc.postProtobuf(
                url = Endpoints.RegisterRefreshUrl,
                body = refreshRequest,
                responseTemplate = Authentication.RegisterRefreshResponse.getDefaultInstance()
            )
            if (response == null) {
                Log.w(TAG, "Token refresh failed: no response")
                return
            }

            val tokenData = response.tokenData
            val newToken = tokenData.tachyonAuthToken
            if (newToken == null || newToken.isEmpty()) {
                Log.w(TAG, "Token refresh failed: no token in response")
                return
            }

            var newTtlUs = tokenData.ttl
            if (newTtlUs == 0L) {
                newTtlUs = DEFAULT_TTL_US
            }
            authMutex.withLock {
                auth = currentAuth.copy(
                    tachyonAuthTokenB64 = Base64.encodeToString(newToken.toByteArray(), Base64.NO_WRAP),
                    tachyonTtlUs = newTtlUs,
                    tachyonExpiryMs = System.currentTimeMillis() + (newTtlUs / 1000)
                )
                auth.save(appContext)
            }
            Log.i(TAG, "Auth token refreshed successfully, new expiry in ${newTtlUs / 1000 / 1000}s")

        } catch (e: Exception) {
            Log.e(TAG, "Failed to refresh auth token", e)
        }
    }

    // ----------------------------------------------------------------
    // Long-poll event handling
    // ----------------------------------------------------------------

    private suspend fun handleLongPollEvent(evt: LongPollEvent) {
        when (evt) {
            is LongPollEvent.Paired -> {
                // Hand off to our own scope: handlePaired calls longPoll.stop()
                // which would otherwise cancel the very coroutine we're in
                // (the long-poll's reader) mid-execution.
                scope.launch { handlePaired(evt) }
            }
            LongPollEvent.Revoked -> {
                Log.w(TAG, "pair revoked by phone")
                _state.value = State.Disconnected("Pair revoked")
            }
            is LongPollEvent.Data -> handleDataMessage(evt.msg)
        }
    }

    private suspend fun handlePaired(p: LongPollEvent.Paired) {
        Log.i(TAG, "received Paired event — switching to Connected (ttlUs=${p.tachyonTtlUs})")
        val ttlUs = p.tachyonTtlUs.let { if (it == 0L) DEFAULT_TTL_US else it }
        authMutex.withLock {
            auth = auth.copy(
                mobileDeviceB64 = p.mobileDeviceB64,
                browserDeviceB64 = p.browserDeviceB64,
                tachyonAuthTokenB64 = p.tachyonTokenB64,
                tachyonTtlUs = ttlUs,
                tachyonExpiryMs = System.currentTimeMillis() + (ttlUs / 1000),
            )
            auth.save(appContext)
        }
        _state.value = State.Connected

        // CRITICAL: the long-poll that received this Paired event is
        // still authenticated with the INITIAL (pre-pair) tachyon token.
        // The relay routes responses keyed by the long-poll's auth
        // token; subsequent SendMessage calls use the new PERMANENT
        // token after pair, so their responses get routed to a
        // long-poll that doesn't exist. We must close + reopen the
        // long-poll to start listening with the permanent token.
        //
        // Sleep 2 s first to let the phone persist the pair data — if
        // we reconnect too quickly the phone may not recognize the
        // session and silently unpair us (same trick libgm uses in
        // `pair.go` completePairing).
        Log.i(TAG, "sleeping 2s before reconnecting long-poll with permanent token")
        kotlinx.coroutines.delay(2_000)
        longPoll.stop()
        longPoll.start(scope)

        postConnect()
    }

    private suspend fun handleDataMessage(msg: IncomingRpc) {
        val data = msg.decryptedData ?: return
        when (msg.action) {
            ActionType.LIST_CONVERSATIONS -> {
                val resp = runCatching { ListConversationsResponse.parseFrom(data) }.getOrNull() ?: return
                Log.i(TAG, "backfill: ${resp.conversationsCount} conversations")
                for (i in 0 until resp.conversationsCount) emitConversation(resp.getConversations(i))
            }
            ActionType.LIST_MESSAGES -> {
                val resp = runCatching { ListMessagesResponse.parseFrom(data) }.getOrNull() ?: return
                Log.i(TAG, "thread fill: ${resp.messagesCount} messages")
                // Build all rows up-front then bulk-write in one Room
                // transaction. Per-message events would trigger one Flow
                // emission each — noticeably slow when opening a thread
                // with hundreds of historical messages.
                val rows = (0 until resp.messagesCount).map { idx ->
                    buildMessageRow(resp.getMessages(idx))
                }
                com.vayunmathur.messages.util.MessagesSessionManager.bulkUpsertMessages(rows)
            }
            ActionType.GET_UPDATES -> handleGetUpdates(data)
            else -> Log.d(TAG, "unhandled data action ${msg.action}")
        }
    }

    private suspend fun emitConversation(c: conversations.Conversations.Conversation) {
        // Capture defaultOutgoingID so the SEND_MESSAGE path can route
        // the outgoing through the right SIM. Keyed by web ID (no prefix).
        c.defaultOutgoingID.takeIf { it.isNotBlank() }?.let { outgoingIds[c.conversationID] = it }

        // Collect the non-self participants — used for both phone-lookup
        // (1:1 chats) and group-display labeling.
        val otherParticipants = (0 until c.participantsCount)
            .map { c.getParticipants(it) }
            .filter { !it.isMe }

        val isGroup = c.isGroupChat || otherParticipants.size > 1
        val peerPhone = otherParticipants.firstOrNull { it.id.number.isNotBlank() }?.id?.number

        // Always prefer the device's contact database for naming + photos
        // (1:1 only). For groups we synthesize a "Alice, Bob & 2 others"
        // style label below.
        val contact = if (!isGroup) {
            peerPhone?.let { ContactResolver.lookup(appContext, it) }
        } else null

        val displayName = when {
            isGroup -> groupLabel(c, otherParticipants)
            else -> contact?.displayName ?: peerPhone ?: c.name.takeIf { it.isNotBlank() }
        }

        val type = when (c.type) {
            conversations.Conversations.ConversationType.SMS -> "SMS"
            conversations.Conversations.ConversationType.RCS -> "RCS"
            else -> null
        }

        _events.emit(
            GMEvent.ConversationUpdate(
                source = source,
                conversationId = c.conversationID,
                peerName = displayName,
                peerPhone = if (isGroup) null else peerPhone,
                avatarUrl = contact?.photoUri,
                lastPreview = if (c.hasLatestMessage()) c.latestMessage.displayContent.takeIf { it.isNotBlank() } else null,
                lastTimestamp = toMillis(c.lastMessageTimestamp),
                unreadCount = if (c.unread) 1 else 0,
                isGroup = isGroup,
                participantCount = otherParticipants.size,
                conversationType = type,
                outgoingId = c.defaultOutgoingID.takeIf { it.isNotBlank() },
            )
        )
    }

    /** Build a "Alice, Bob & 2 others" label for a group. Uses the
     *  device's contact name for each participant when available, else
     *  the participant's fullName from the relay, else their number. */
    private fun groupLabel(
        c: conversations.Conversations.Conversation,
        others: List<conversations.Conversations.Participant>,
    ): String {
        // Prefer the explicit thread name (RCS groups often have one).
        val explicit = c.name.takeIf { it.isNotBlank() }
        if (explicit != null) return explicit
        val names = others.map { p ->
            val phone = p.id.number.takeIf { it.isNotBlank() }
            val deviceName = phone?.let { ContactResolver.lookup(appContext, it)?.displayName }
            deviceName
                ?: p.firstName.takeIf { it.isNotBlank() }
                ?: p.fullName.takeIf { it.isNotBlank() }
                ?: phone
                ?: "Unknown"
        }
        return when {
            names.isEmpty() -> "Group"
            names.size <= 2 -> names.joinToString(", ")
            else -> names.take(2).joinToString(", ") + " & ${names.size - 2} others"
        }
    }

    /** Pure transformation of one proto Message into a Room row.
     *  Used by the bulk LIST_MESSAGES path. */
    private fun buildMessageRow(m: conversations.Conversations.Message): com.vayunmathur.messages.data.Message {
        val body = (0 until m.messageInfoCount)
            .mapNotNull { idx ->
                val info = m.getMessageInfo(idx)
                if (info.hasMessageContent()) info.messageContent.content else null
            }
            .joinToString("\n")
        val outgoing = m.hasSenderParticipant() && m.senderParticipant.isMe
        val sourcePrefix = source.idPrefix
        return com.vayunmathur.messages.data.Message(
            id = "$sourcePrefix:${m.messageID}",
            conversationId = "$sourcePrefix:${m.conversationID}",
            body = body,
            direction = if (outgoing) com.vayunmathur.messages.data.MessageDirection.OUTGOING
                else com.vayunmathur.messages.data.MessageDirection.INCOMING,
            state = if (outgoing) com.vayunmathur.messages.data.MessageState.SENT
                else com.vayunmathur.messages.data.MessageState.DELIVERED,
            timestamp = toMillis(m.timestamp),
            senderName = if (m.hasSenderParticipant()) {
                m.senderParticipant.fullName.takeIf { it.isNotBlank() }
                    ?: m.senderParticipant.firstName.takeIf { it.isNotBlank() }
            } else null,
            reactionsJson = extractReactionsJson(m),
        )
    }

    /**
     * The relay returns timestamps in **microseconds** since epoch (see
     * `time.UnixMicro(conv.GetLastMessageTimestamp())` in the Go bridge
     * `pkg/connector/chatsync.go`). Everywhere else in this app (Room,
     * notifications, [java.util.Date], Voice's emission path) expects
     * **milliseconds**. Convert at the boundary so we never mix units.
     */
    private fun toMillis(usec: Long): Long = usec / 1000

    /** Roll up the per-emoji reaction entries on a Message into the
     *  [count: Int] aggregate we store. */
    private fun extractReactionsJson(m: conversations.Conversations.Message): String? {
        if (m.reactionsCount == 0) return null
        val reactions = (0 until m.reactionsCount).mapNotNull { idx ->
            val entry = m.getReactions(idx)
            val emoji = entry.data.unicode.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            com.vayunmathur.messages.data.Reaction(
                emoji = emoji,
                count = entry.participantIDsCount.coerceAtLeast(1),
            )
        }
        if (reactions.isEmpty()) return null
        return kotlinx.serialization.json.Json.encodeToString(
            kotlinx.serialization.builtins.ListSerializer(
                com.vayunmathur.messages.data.Reaction.serializer()
            ),
            reactions,
        )
    }

    private suspend fun emitMessage(m: conversations.Conversations.Message) {
        // Concatenate all text-bearing MessageInfo parts. Most messages
        // have exactly one; RCS / MMS can have multiple parts (e.g. a
        // caption + an attachment we don't render yet).
        val body = (0 until m.messageInfoCount)
            .mapNotNull { idx ->
                val info = m.getMessageInfo(idx)
                if (info.hasMessageContent()) info.messageContent.content else null
            }
            .joinToString("\n")
            .ifBlank { "" }
        // The Message proto has no top-level fromMe boolean, but its
        // senderParticipant carries an `isMe` flag the relay populates
        // for outgoing messages. Falls back to incoming when missing.
        val outgoing = m.hasSenderParticipant() && m.senderParticipant.isMe
        _events.emit(
            GMEvent.MessageUpdate(
                source = source,
                conversationId = m.conversationID,
                messageId = m.messageID,
                body = body,
                outgoing = outgoing,
                timestamp = toMillis(m.timestamp),
                senderName = if (m.hasSenderParticipant()) {
                    m.senderParticipant.fullName.takeIf { it.isNotBlank() }
                        ?: m.senderParticipant.firstName.takeIf { it.isNotBlank() }
                } else null,
                reactionsJson = extractReactionsJson(m),
            )
        )
        if (!outgoing && body.isNotEmpty()) {
            _events.emit(
                GMEvent.IncomingMessage(
                    source = source,
                    conversationId = m.conversationID,
                    messageId = m.messageID,
                    body = body,
                    peerName = if (m.hasSenderParticipant()) {
                        m.senderParticipant.fullName.takeIf { it.isNotBlank() }
                    } else null,
                    peerPhone = if (m.hasSenderParticipant()) {
                        m.senderParticipant.id.number.takeIf { it.isNotBlank() }
                    } else null,
                    timestamp = toMillis(m.timestamp),
                )
            )
        }
    }

    private fun kickoffBackfill() {
        backfillJob?.cancel()
        backfillJob = scope.launch {
            // First call uses BUGLE_ANNOTATION as a "give me everything"
            // hint to the relay; subsequent calls use BUGLE_MESSAGE. See
            // libgm `methods.go.ListConversations` for the same trick.
            val msgType = if (!conversationsFetchedOnce) {
                conversationsFetchedOnce = true
                MessageType.BUGLE_ANNOTATION
            } else {
                MessageType.BUGLE_MESSAGE
            }
            Log.i(TAG, "kicking off LIST_CONVERSATIONS (messageType=$msgType)")
            val req = ListConversationsRequest.newBuilder()
                .setCount(50)
                .build()
            val resp = sessionHandler.sendAndWait(
                ActionType.LIST_CONVERSATIONS,
                req,
                messageType = msgType,
            )
            if (resp == null) {
                Log.w(TAG, "backfill: no response (timeout?)")
            } else {
                Log.i(TAG, "backfill response received (decryptedBytes=${resp.decryptedData?.size ?: 0})")
            }
        }
    }
}
