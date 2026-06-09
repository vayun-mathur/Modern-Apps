package com.vayunmathur.messages.gvoice

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
import kotlinx.coroutines.delay
import requests.Requests
import responses.Responses
import threads.Threads
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Process-global owner of the Google Voice protocol session.
 *
 * Mirrors the [com.vayunmathur.messages.gmessages.GMessagesClient]
 * pattern: a singleton with a state machine, an event flow, and
 * persistence backed by DataStore. Both this and the gmessages client
 * fan their events into the same [com.vayunmathur.messages.util.MessagesSessionManager]
 * which writes the unified Room DB.
 */
object GVoiceClient {

    private const val TAG = "GVoiceClient"

    sealed interface State {
        data object Idle : State
        /** Awaiting user-pasted cookies — UI shows the paste form. */
        data object NeedsSetup : State
        data object Connecting : State
        data object Connected : State
        data class Disconnected(val reason: String) : State
    }

    val source: MessageSource = MessageSource.VOICE

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()

    private val _events = MutableSharedFlow<GMEvent>(extraBufferCapacity = 256)
    val events: SharedFlow<GMEvent> = _events.asSharedFlow()

    private val initialized = AtomicBoolean(false)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private lateinit var appContext: Context
    @Volatile private var rpc: GVoiceRpcClient? = null
    private var realtime: RealtimeChannel? = null
    private var backfillJob: Job? = null

    fun init(context: Context) {
        if (!initialized.compareAndSet(false, true)) return
        appContext = context.applicationContext
        Log.i(TAG, "init")
        scope.launch {
            val auth = VoiceAuthData.load(appContext)
            if (auth?.hasRequired() == true) {
                Log.i(TAG, "resuming from persisted cookies")
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
            val auth = VoiceAuthData.load(appContext)
            if (auth?.hasRequired() == true) bootSession(auth)
            else _state.value = State.NeedsSetup
        }
    }

    fun stop() {
        Log.i(TAG, "stop — clearing Voice session")
        backfillJob?.cancel()
        realtime?.stop()
        realtime = null
        rpc?.close()
        rpc = null
        scope.launch { VoiceAuthData.clear(appContext) }
        _state.value = State.NeedsSetup
    }

    /**
     * Validate the user-supplied cookies via a GetAccount round-trip;
     * on success, persist + start the session. Returns null on success
     * (and transitions state to Connecting → Connected), or a
     * human-readable error message on failure.
     */
    suspend fun submitCookies(cookies: Map<String, String>): String? {
        val missing = CookieParser.missingRequired(cookies)
        if (missing.isNotEmpty()) return "Missing required cookies: ${missing.joinToString(", ")}"
        _state.value = State.Connecting
        val client = GVoiceRpcClient(cookies)
        val accountOk = try {
            val acc = client.postPbLite(
                url = VoiceEndpoints.EndpointGetAccount,
                body = Requests.ReqGetAccount.getDefaultInstance(),
                responseTemplate = Responses.RespGetAccount.getDefaultInstance(),
            )
            Log.i(TAG, "GetAccount OK; primary=${acc.account.primaryDestinationID}")
            true
        } catch (t: Throwable) {
            Log.e(TAG, "GetAccount failed: ${t.message}")
            client.close()
            _state.value = State.NeedsSetup
            return "Sign-in failed: ${t.message?.take(160) ?: "unknown error"}"
        }
        if (!accountOk) return "Sign-in validation failed"
        val authData = VoiceAuthData(cookies)
        authData.save(appContext)
        // Tear down any previous session before booting the new one.
        realtime?.stop()
        rpc?.close()
        rpc = client
        bootSession(authData)
        return null
    }

    /** Trigger a re-list of conversations. */
    fun forceResync() {
        if (_state.value !is State.Connected) return
        kickoffBackfill()
    }

    /**
     * Send an SMS via Google Voice on the given thread.
     *
     * Mirrors `Client.SendMessage` in
     * `/Users/vayun/Documents/gvoice/pkg/libgv/client.go`: builds a
     * [Requests.ReqSendSMS] with a random transaction ID and the
     * bridge's `"!"` tracking-data fallback (the real signature is
     * generated by an Electron-hosted JS blob in the bridge; the
     * server happily accepts the placeholder for text-only sends).
     *
     * Returns true if the server responded 2xx with a thread item ID.
     */
    suspend fun sendMessage(conversationId: String, body: String): Boolean {
        if (_state.value !is State.Connected) return false
        val client = rpc ?: return false
        val webId = conversationId.substringAfter(':', conversationId)
        val req = baseSendBuilder(webId)
            .setText(body)
            .build()
        return doSend(client, webId, req)
    }

    /**
     * Send an MMS via Google Voice. The bridge inlines the media bytes
     * directly into `ReqSendSMS.Media` (see `handlematrix.go:91`); no
     * separate upload endpoint is required. Caption (if any) goes in
     * the text field — that matches what google-voice's own web client
     * does when sending photo + caption together.
     *
     * Only the explicit image MIME types listed in `ReqSendSMS.Media.Type`
     * are accepted: jpeg/png/gif/bmp/tiff/webp. Everything else returns
     * false (the server would reject it).
     */
    suspend fun sendMedia(
        conversationId: String,
        data: ByteArray,
        mime: String,
        caption: String?,
    ): Boolean {
        if (_state.value !is State.Connected) return false
        val client = rpc ?: return false
        val webId = conversationId.substringAfter(':', conversationId)
        val mediaType = mimeToVoiceMediaType(mime) ?: run {
            Log.w(TAG, "sendMedia: unsupported MIME $mime")
            return false
        }
        val media = Requests.ReqSendSMS.Media.newBuilder()
            .setType(mediaType)
            .setData(com.google.protobuf.ByteString.copyFrom(data))
            .build()
        val req = baseSendBuilder(webId)
            .apply { caption?.takeIf { it.isNotBlank() }?.let { setText(it) } }
            .setMedia(media)
            .build()
        return doSend(client, webId, req)
    }

    /**
     * Mark every message in [conversationId] as read via
     * `thread/updateattributes`. Mirrors `Client.UpdateThreadAttributes`
     * in `pkg/libgv/client.go` with `Read=true`.
     */
    suspend fun markRead(conversationId: String): Boolean {
        if (_state.value !is State.Connected) return false
        val client = rpc ?: return false
        val webId = conversationId.substringAfter(':', conversationId)
        val req = Requests.ReqUpdateAttributes.newBuilder()
            .setAttributes(
                Threads.ThreadAttributes.newBuilder()
                    .setThreadID(webId)
                    .setRead(true)
            )
            .setOtherAttributes(
                Threads.ThreadAttributes.newBuilder().setRead(true)
            )
            .setUnknownInt(1)
            .build()
        return try {
            client.postPbLite(
                url = VoiceEndpoints.EndpointUpdateAttributes,
                body = req,
                responseTemplate = Responses.RespUpdateAttributes.getDefaultInstance(),
            )
            true
        } catch (t: Throwable) {
            Log.w(TAG, "markRead failed thread=$webId: ${t.message}")
            false
        }
    }

    /**
     * Delete a Voice thread server-side. Mirrors `Client.DeleteThread`.
     * The realtime channel will (eventually) push a thread-list update
     * removing the thread; we also eagerly delete the local Room rows
     * from the session manager so the UI updates immediately.
     */
    suspend fun deleteThread(conversationId: String): Boolean {
        if (_state.value !is State.Connected) return false
        val client = rpc ?: return false
        val webId = conversationId.substringAfter(':', conversationId)
        val req = Requests.ReqDeleteThread.newBuilder()
            .setThreadID(webId)
            .build()
        return try {
            client.postPbLite(
                url = VoiceEndpoints.EndpointDeleteThread,
                body = req,
                responseTemplate = Responses.RespDeleteThread.getDefaultInstance(),
            )
            true
        } catch (t: Throwable) {
            Log.w(TAG, "deleteThread failed thread=$webId: ${t.message}")
            false
        }
    }

    /** Common builder: txn ID + bridge's tracking-data placeholder. */
    private fun baseSendBuilder(webId: String): Requests.ReqSendSMS.Builder =
        Requests.ReqSendSMS.newBuilder()
            .setThreadID(webId)
            .setTransactionID(
                Requests.ReqSendSMS.WrappedTxnID.newBuilder()
                    .setID(kotlin.random.Random.nextLong(100_000_000_000_000L))
                    .build()
            )
            .setTrackingData(
                Requests.ReqSendSMS.TrackingData.newBuilder()
                    .setData("!")
                    .build()
            )

    private suspend fun doSend(
        client: GVoiceRpcClient,
        webId: String,
        req: Requests.ReqSendSMS,
    ): Boolean = try {
        val resp = client.postPbLite(
            url = VoiceEndpoints.EndpointSendSms,
            body = req,
            responseTemplate = Responses.RespSendSMS.getDefaultInstance(),
        )
        Log.i(TAG, "sendsms ok thread=$webId itemId=${resp.threadItemID}")
        resp.threadItemID.isNotBlank()
    } catch (t: Throwable) {
        Log.w(TAG, "sendsms failed thread=$webId: ${t.message}")
        false
    }

    private fun mimeToVoiceMediaType(mime: String): Requests.ReqSendSMS.Media.Type? =
        when (mime.lowercase()) {
            "image/jpeg", "image/jpg" -> Requests.ReqSendSMS.Media.Type.JPEG
            "image/png" -> Requests.ReqSendSMS.Media.Type.PNG
            "image/gif" -> Requests.ReqSendSMS.Media.Type.GIF
            "image/bmp", "image/x-ms-bmp" -> Requests.ReqSendSMS.Media.Type.BMP
            "image/tiff" -> Requests.ReqSendSMS.Media.Type.TIFF
            "image/webp" -> Requests.ReqSendSMS.Media.Type.WEBP
            else -> null
        }

    /**
     * Send a text message to a brand-new thread keyed by [recipients].
     * Mirrors the bridge's "no portal id yet" path
     * (`pkg/connector/handlematrix.go:49`): build a [Requests.ReqSendSMS]
     * with `setRecipients(...)` and **no** `setThreadID`; the server
     * creates the thread and returns its assigned id in the response.
     *
     * Returns the new conversation id (prefixed with [source.idPrefix])
     * on success, or null on failure.
     */
    suspend fun sendNewThread(recipients: List<String>, body: String): String? {
        if (recipients.isEmpty()) return null
        if (_state.value !is State.Connected) return null
        val client = rpc ?: return null
        val req = Requests.ReqSendSMS.newBuilder()
            .addAllRecipients(recipients)
            .setText(body)
            .setTransactionID(
                Requests.ReqSendSMS.WrappedTxnID.newBuilder()
                    .setID(kotlin.random.Random.nextLong(100_000_000_000_000L))
                    .build()
            )
            .setTrackingData(
                Requests.ReqSendSMS.TrackingData.newBuilder().setData("!").build()
            )
            .build()
        return sendNewThreadInner(client, req)
    }

    /**
     * MMS variant of [sendNewThread]. Caption is optional; pass null to
     * send the media alone.
     */
    suspend fun sendNewThreadMedia(
        recipients: List<String>,
        mime: String,
        data: ByteArray,
        caption: String?,
    ): String? {
        if (recipients.isEmpty()) return null
        if (_state.value !is State.Connected) return null
        val client = rpc ?: return null
        val mediaType = mimeToVoiceMediaType(mime) ?: run {
            Log.w(TAG, "sendNewThreadMedia: unsupported MIME $mime")
            return null
        }
        val media = Requests.ReqSendSMS.Media.newBuilder()
            .setType(mediaType)
            .setData(com.google.protobuf.ByteString.copyFrom(data))
            .build()
        val req = Requests.ReqSendSMS.newBuilder()
            .addAllRecipients(recipients)
            .apply { caption?.takeIf { it.isNotBlank() }?.let { setText(it) } }
            .setMedia(media)
            .setTransactionID(
                Requests.ReqSendSMS.WrappedTxnID.newBuilder()
                    .setID(kotlin.random.Random.nextLong(100_000_000_000_000L))
                    .build()
            )
            .setTrackingData(
                Requests.ReqSendSMS.TrackingData.newBuilder().setData("!").build()
            )
            .build()
        return sendNewThreadInner(client, req)
    }

    private suspend fun sendNewThreadInner(
        client: GVoiceRpcClient,
        req: Requests.ReqSendSMS,
    ): String? = try {
        val resp = client.postPbLite(
            url = VoiceEndpoints.EndpointSendSms,
            body = req,
            responseTemplate = Responses.RespSendSMS.getDefaultInstance(),
        )
        // The server-assigned thread id appears in resp.threadID for
        // newly-created threads. If absent we have no way to surface
        // the result; return null so the caller can show an error.
        val threadId = resp.threadID.takeIf { it.isNotBlank() }
        if (threadId == null) {
            Log.w(TAG, "sendNewThread: missing threadID in response (itemId=${resp.threadItemID})")
            null
        } else {
            // Kick a backfill so the realtime channel surfaces the
            // brand-new thread metadata immediately.
            kickoffBackfill()
            "${source.idPrefix}:$threadId"
        }
    } catch (t: Throwable) {
        Log.w(TAG, "sendNewThread failed: ${t.message}")
        null
    }

    /**
     * Server-side contact autocomplete. Mirrors `Client.AutocompleteContacts`
     * in `pkg/libgv/client.go:172`. Empty [query] returns the top ~500
     * contacts (used to populate the picker initially); non-empty
     * narrows to ~15 matches.
     */
    suspend fun autocompleteContacts(query: String): List<com.vayunmathur.messages.util.ContactSuggestion> {
        if (_state.value !is State.Connected) return emptyList()
        val client = rpc ?: return emptyList()
        val maxResults: Int = if (query.isEmpty()) 500 else 15
        val req = Requests.ReqAutocompleteContacts.newBuilder()
            .setUnknownInt1(243)
            .setQuery(query)
            .addAllUnknownInts3(listOf(1, 2))
            .setMaxResults(maxResults)
            .build()
        val resp = try {
            client.postPbLite(
                url = VoiceEndpoints.EndpointAutocompleteContacts,
                body = req,
                responseTemplate = Responses.RespAutocompleteContacts.getDefaultInstance(),
            )
        } catch (t: Throwable) {
            Log.w(TAG, "autocompleteContacts failed: ${t.message}")
            return emptyList()
        }
        return (0 until resp.resultsCount).flatMap { i ->
            personToSuggestions(resp.getResults(i))
        }
    }

    /**
     * Resolve one-or-more phone numbers to known contacts via
     * peoplestack Lookup. Mirrors `Client.LookupContact`. Returned map
     * is keyed by E.164 phone with a [ContactSuggestion] value when a
     * match exists; missing keys = no match.
     */
    suspend fun lookupContact(phones: List<String>): Map<String, com.vayunmathur.messages.util.ContactSuggestion> {
        if (phones.isEmpty() || _state.value !is State.Connected) return emptyMap()
        val client = rpc ?: return emptyMap()
        val req = Requests.ReqLookupContacts.newBuilder()
            .setUnknownInt1(243)
            .addAllUnknownInts2(listOf(1, 2))
            .addAllTargets(phones.map { p ->
                contacts.Contacts.ContactID.newBuilder().setPhone(p).build()
            })
            .build()
        val resp = try {
            client.postPbLite(
                url = VoiceEndpoints.EndpointLookupContacts,
                body = req,
                responseTemplate = Responses.RespLookupContacts.getDefaultInstance(),
            )
        } catch (t: Throwable) {
            Log.w(TAG, "lookupContact failed: ${t.message}")
            return emptyMap()
        }
        val out = mutableMapOf<String, com.vayunmathur.messages.util.ContactSuggestion>()
        for (i in 0 until resp.matchesCount) {
            val match = resp.getMatches(i)
            val phone = match.id.phone.takeIf { it.isNotBlank() } ?: continue
            personToSuggestions(match.autocompletion).firstOrNull()?.let { out[phone] = it }
        }
        return out
    }

    /**
     * Flatten a [PersonWrapper] into one [ContactSuggestion] per phone
     * contact-method. Email-only entries are dropped — we can't send
     * SMS to them.
     */
    private fun personToSuggestions(
        wrapper: contacts.Contacts.PersonWrapper,
    ): List<com.vayunmathur.messages.util.ContactSuggestion> {
        val person = wrapper.person ?: return emptyList()
        // The display name lives on the contact method's displayInfo;
        // typically all methods on a person share the same name, so
        // just use the first non-blank one we see.
        val displayName = (0 until person.contactMethodsCount)
            .asSequence()
            .map { person.getContactMethods(it) }
            .mapNotNull { it.displayInfo?.name?.value?.takeIf { v -> v.isNotBlank() } }
            .firstOrNull()
            ?: "Unknown"
        val avatar = (0 until person.contactMethodsCount)
            .asSequence()
            .map { person.getContactMethods(it) }
            .mapNotNull { it.displayInfo?.photo?.url?.takeIf { v -> v.isNotBlank() } }
            .firstOrNull()
        return (0 until person.contactMethodsCount).mapNotNull { idx ->
            val cm = person.getContactMethods(idx)
            val rawPhone = cm.phone?.canonicalValue?.takeIf { it.isNotBlank() }
                ?: cm.phone?.displayValue?.takeIf { it.isNotBlank() }
                ?: return@mapNotNull null
            com.vayunmathur.messages.util.ContactSuggestion(
                displayName = displayName,
                phoneE164 = rawPhone,
                avatarUrl = avatar,
                source = source,
            )
        }
    }

    /** Load (or refresh) messages for a thread. */
    fun fetchMessages(conversationId: String, count: Int = 100) {
        if (_state.value !is State.Connected) return
        scope.launch {
            val client = rpc ?: return@launch
            val webId = conversationId.substringAfter(':', conversationId)
            Log.i(TAG, "fetchMessages threadId=$webId count=$count")
            val req = Requests.ReqGetThread.newBuilder()
                .setThreadID(webId)
                .setMaybeMessageCount(count)
                .build()
            val resp = try {
                client.postPbLite(
                    url = VoiceEndpoints.EndpointGetThread,
                    body = req,
                    responseTemplate = Responses.RespGetThread.getDefaultInstance(),
                )
            } catch (t: Throwable) {
                Log.w(TAG, "GetThread failed: ${t.message}")
                return@launch
            }
            if (!resp.hasThread()) return@launch
            val thread = resp.thread
            for (i in 0 until thread.messagesCount) {
                emitMessage(thread.getID(), thread.getMessages(i), fromBackfill = true)
            }
        }
    }

    // ----------------------------------------------------------------
    // Internals
    // ----------------------------------------------------------------

    private fun bootSession(auth: VoiceAuthData) {
        val client = rpc ?: GVoiceRpcClient(auth.cookies).also { rpc = it }
        client.updateCookies(auth.cookies)
        rpc = client

        _state.value = State.Connecting
        _state.value = State.Connected
        realtime?.stop()
        realtime = RealtimeChannel(client) { evt ->
            when (evt) {
                RealtimeEvent.Connected -> Log.i(TAG, "realtime connected")
                is RealtimeEvent.Data -> handleRealtimeData(evt.event)
            }
        }.also { ch ->
            val rtJob = ch.start(scope)
            rtJob.invokeOnCompletion { cause ->
                if (!rtJob.isCancelled) {
                    _state.value = State.Disconnected("realtime connection lost")
                }
            }
        }
        kickoffBackfill()
    }

    private fun kickoffBackfill() {
        backfillJob?.cancel()
        backfillJob = scope.launch { doBackfill() }
    }

    private suspend fun doBackfill() {
        val client = rpc ?: return
        Log.i(TAG, "ListThreads (TEXT_THREADS)")
        val req = Requests.ReqListThreads.newBuilder()
            .setFolder(Threads.ThreadFolder.TEXT_THREADS)
            // libgv hard-codes these "unknown" fields — 20 on the
            // first call (10 on subsequent), 15 for the second.
            // Without them the server returns 0 threads even though
            // the account has real conversations.
            .setUnknownInt2(20)
            .setUnknownInt3(15)
            .build()
        val resp = try {
            client.postPbLite(
                url = VoiceEndpoints.EndpointListThreads,
                body = req,
                responseTemplate = Responses.RespListThreads.getDefaultInstance(),
            )
        } catch (t: Throwable) {
            Log.w(TAG, "ListThreads failed: ${t.message}")
            _state.value = State.Disconnected(t.message ?: "ListThreads failed")
            return
        }
        Log.i(TAG, "ListThreads: ${resp.threadsCount} threads")
        for (i in 0 until resp.threadsCount) {
            emitConversation(resp.getThreads(i))
        }
    }

    private fun handleRealtimeData(evt: webchannel.Webchannel.WebChannelEvent) {
        Log.d(TAG, "realtime event arrayID=${evt.arrayID} wrappers=${evt.dataWrapperCount}")
        backfillJob?.cancel()
        backfillJob = scope.launch {
            delay(2000)
            doBackfill()
        }
    }

    private suspend fun emitConversation(t: Threads.Thread) {
        val contact = if (t.contactsCount > 0) t.getContacts(0) else null
        val peerPhone = contact?.phoneNumber?.takeIf { it.isNotBlank() }
        // Lookup in device contacts (same as the gmessages path).
        val device = peerPhone?.let {
            com.vayunmathur.messages.util.ContactResolver.lookup(appContext, it)
        }

        val isGroup = t.contactsCount > 1
        val displayName: String? = when {
            isGroup -> {
                val names = (0 until t.contactsCount).mapNotNull { idx ->
                    val c = t.getContacts(idx)
                    val phone = c.phoneNumber.takeIf { it.isNotBlank() }
                    val deviceName = phone?.let {
                        com.vayunmathur.messages.util.ContactResolver.lookup(appContext, it)?.displayName
                    }
                    deviceName ?: c.name.takeIf { n -> n.isNotBlank() } ?: phone
                }
                when {
                    names.isEmpty() -> "Group"
                    names.size <= 2 -> names.joinToString(", ")
                    else -> names.take(2).joinToString(", ") + " & ${names.size - 2} others"
                }
            }
            else -> device?.displayName ?: peerPhone ?: contact?.name?.takeIf { it.isNotBlank() }
        }

        val latest: Threads.Message? = if (t.messagesCount > 0) {
            t.getMessages(t.messagesCount - 1)
        } else null
        val preview = latest?.text?.takeIf { it.isNotBlank() }
        // Voice's Message.timestamp is epoch-milliseconds (confirmed
        // against mautrix-gvoice's `time.UnixMilli(msg.Timestamp)`).
        // Don't scale.
        val tsMillis = latest?.timestamp ?: 0L

        _events.emit(
            GMEvent.ConversationUpdate(
                source = source,
                conversationId = t.getID(),
                peerName = displayName,
                peerPhone = if (isGroup) null else peerPhone,
                avatarUrl = device?.photoUri,
                lastPreview = preview,
                lastTimestamp = tsMillis,
                // `Thread.read = false` means there are unread messages.
                unreadCount = if (!t.read) 1 else 0,
                isGroup = isGroup,
                participantCount = t.contactsCount,
                conversationType = "Voice",
            )
        )
    }

    private suspend fun emitMessage(threadId: String, m: Threads.Message, fromBackfill: Boolean = false) {
        // Skip non-text messages (calls / voicemail / etc.) for v1.
        if (m.text.isBlank()) return
        val outgoing = m.type == Threads.Message.Type.SMS_OUT
        val tsMs = m.timestamp
        val peerPhone = if (m.hasContact()) m.contact.phoneNumber.takeIf { it.isNotBlank() } else null
        _events.emit(
            GMEvent.MessageUpdate(
                source = source,
                conversationId = threadId,
                messageId = m.getID(),
                body = m.text,
                outgoing = outgoing,
                timestamp = tsMs,
                senderName = if (m.hasContact()) m.contact.name.takeIf { it.isNotBlank() } else null,
            )
        )
        if (!outgoing && !fromBackfill) {
            _events.emit(
                GMEvent.IncomingMessage(
                    source = source,
                    conversationId = threadId,
                    messageId = m.getID(),
                    body = m.text,
                    peerName = if (m.hasContact()) m.contact.name.takeIf { it.isNotBlank() } else null,
                    peerPhone = peerPhone,
                    timestamp = tsMs,
                )
            )
        }
    }
}
