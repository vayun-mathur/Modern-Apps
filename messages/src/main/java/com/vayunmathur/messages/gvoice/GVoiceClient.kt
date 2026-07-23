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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import requests.Requests
import responses.Responses
import threads.Threads
import waa.Waa
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

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
        data class Disconnected(val reason: String, val errorCode: String? = null) : State
        data class BadCredentials(val reason: String) : State
        data class ConnectError(val reason: String) : State
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
    @Volatile private var waaSigner: WaaSigner? = null
    private var realtime: RealtimeChannel? = null
    private var backfillJob: Job? = null
    private const val AUTH_USER = "0"

    // Incremental thread-list cursor. ListThreads echoes a versionToken
    // (RespListThreads.versionToken); feeding it back on the next call
    // returns only changed threads. Mirrors libgv ListThreads' versionToken
    // parameter (client.go:132). Reset per session.
    @Volatile private var versionToken: String = ""

    // Contact cache with 5-min TTL (matches Go's contactCache + fetchContactInfo)
    private data class CachedContact(
        val name: String?,
        val avatarUrl: String?,
        val timestamp: Long,
    )
    private val contactCache = ConcurrentHashMap<String, CachedContact>()
    private const val CONTACT_CACHE_TTL_MS = 5L * 60L * 1000L

    // Rate-limited message fetching (matches Go: 10s min, burst 5, 15min ticker)
    private const val MIN_REFRESH_INTERVAL_MS = 10_000L
    private const val MIN_REFRESH_BURST = 5
    private const val BACKGROUND_REFRESH_INTERVAL_MS = 15L * 60L * 1000L
    // Safety cap on backfill pagination (≈ MAX_BACKFILL_PAGES * count messages).
    private const val MAX_BACKFILL_PAGES = 20
    private val refreshTokens = AtomicInteger(MIN_REFRESH_BURST)
    private val lastRefreshTime = AtomicLong(0L)
    private val fetchLock = Mutex()
    @Volatile private var fetchLoopJob: Job? = null

    // Per-thread latest message timestamp for deduplication (matches Go's gc.lastEvents)
    private val lastEvents = ConcurrentHashMap<String, Long>()

    private const val MAX_AVATAR_SIZE = 5 * 1024 * 1024

    fun init(context: Context) {
        if (!initialized.compareAndSet(false, true)) return
        appContext = context.applicationContext
        Log.i(TAG, "init")
        scope.launch {
            val auth = VoiceAuthData.load(appContext)
            if (auth?.hasRequired() == true) {
                Log.i(TAG, "resuming from persisted cookies")
                connectWithValidation(auth)
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
            if (auth?.hasRequired() == true) connectWithValidation(auth)
            else _state.value = State.NeedsSetup
        }
    }

    fun stop() {
        Log.i(TAG, "stop — clearing Voice session")
        backfillJob?.cancel()
        fetchLoopJob?.cancel()
        realtime?.stop()
        realtime = null
        rpc?.close()
        rpc = null
        waaSigner?.destroy()
        waaSigner = null
        versionToken = ""
        lastEvents.clear()
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
                body = Requests.ReqGetAccount.newBuilder().setUnknownInt2(1).build(),
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
        // User-initiated full refresh: drop the incremental cursor so the
        // next ListThreads returns every thread, not just the delta.
        versionToken = ""
        kickoffBackfill()
    }

    /**
     * Send an SMS via Google Voice on the given thread.
     *
     * Mirrors `Client.SendMessage` in
     * `/Users/vayun/Documents/gvoice/pkg/libgv/client.go`: builds a
     * [Requests.ReqSendSMS] with a random transaction ID.
     * TrackingData is only set when a real Electron signature is
     * available; without one, the field is omitted entirely.
     *
     * Returns true if the server responded 2xx with a thread item ID.
     */
    suspend fun sendMessage(conversationId: String, body: String): Boolean {
        if (_state.value !is State.Connected) return false
        val client = rpc ?: return false
        val webId = conversationId.substringAfter(':', conversationId)
        val req = buildSendReq(webId, emptyList()) { setText(body) }
        return doSend(client, webId, req)
    }

    /**
     * Send an emote (/me) message. Mirrors the Go bridge's handling
     * of MsgEmote (handlematrix.go): prepends "/me " to the body text.
     */
    suspend fun sendEmote(conversationId: String, body: String): Boolean {
        return sendMessage(conversationId, "/me $body")
    }

    /**
     * Send an MMS via Google Voice. The bridge inlines the media bytes
     * directly into `ReqSendSMS.Media` (see `handlematrix.go:91`); no
     * separate upload endpoint is required. Caption (if any) goes in
     * the text field — that matches what google-voice's own web client
     * does when sending photo + caption together.
     *
     * Only the explicit image MIME types listed in `ReqSendSMS.Media.Type`
     * are accepted: jpeg/png/bmp/tiff. Everything else returns false
     * (the server would reject it). GIF and WEBP are excluded to match
     * the Go bridge's capabilities.go.
     */
    suspend fun sendMedia(
        conversationId: String,
        data: ByteArray,
        mime: String,
        caption: String?,
        fileName: String? = null,
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
        val effectiveCaption = when {
            fileName.isNullOrBlank() -> null
            fileName == caption -> null
            else -> caption
        }
        val req = buildSendReq(webId, emptyList()) {
            effectiveCaption?.takeIf { it.isNotBlank() }?.let { setText(it) }
            setMedia(media)
        }
        return doSend(client, webId, req)
    }

    /**
     * Polls are not supported by Google Voice (no poll concept in libgv's SendSMS
     * proto) — contract §2b lists this platform as a no-op. Always returns false;
     * the capability layer hides the poll entry for this platform.
     */
    @Suppress("UNUSED_PARAMETER")
    suspend fun sendPoll(
        conversationId: String,
        question: String,
        options: List<String>,
        allowMultiple: Boolean,
    ): Boolean {
        Log.i(TAG, "sendPoll unsupported on Google Voice")
        return false
    }

    /**
     * Mark every message in [conversationId] as read via
     * `thread/updateattributes`. Mirrors `Client.UpdateThreadAttributes`
     * in `pkg/libgv/client.go` with `Read=true`.
     */
    /**
     * Integrator read-receipt contract: Google Voice threads are SMS/voicemail
     * and have no read-receipt concept, so this is always a no-op returning
     * false. (markRead still updates the server-side "read" thread attribute; it
     * does not notify the sender.)
     */
    @Suppress("UNUSED_PARAMETER")
    suspend fun sendReadReceipt(
        conversationId: String,
        lastMessageId: String?,
        lastTimestamp: Long,
    ): Boolean {
        return false
    }

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

    /**
     * Build a [Requests.ReqSendSMS] with a random txn id and a real WAA
     * `TrackingData` signature when available. Mirrors the Go bridge's
     * `HandleMatrixMessage` (handlematrix.go:40-63): it asks the WAA
     * signer for `TrackingData`, and only when that's unavailable does
     * `Client.SendMessage` (client.go:101) fall back to "!".
     *
     * [threadId] is empty for new-thread sends; [recipients] is empty for
     * existing-thread sends (we don't track per-thread participants here —
     * see // UNVERIFIED note in [signTracking]).
     */
    private suspend fun buildSendReq(
        threadId: String,
        recipients: List<String>,
        configure: Requests.ReqSendSMS.Builder.() -> Unit,
    ): Requests.ReqSendSMS {
        val txnId = kotlin.random.Random.nextLong(100_000_000_000_000L)
        val tracking = signTracking(threadId, recipients, txnId)
        val builder = Requests.ReqSendSMS.newBuilder()
            .setTransactionID(
                Requests.ReqSendSMS.WrappedTxnID.newBuilder().setID(txnId).build()
            )
            .setTrackingData(
                Requests.ReqSendSMS.TrackingData.newBuilder().setData(tracking).build()
            )
        if (threadId.isNotEmpty()) builder.setThreadID(threadId)
        if (recipients.isNotEmpty()) builder.addAllRecipients(recipients)
        builder.configure()
        return builder.build()
    }

    /**
     * Returns the WAA tracking-data signature, or "!" as the fallback
     * (matches Go's `SendMessage` default when no signature is produced).
     *
     * // UNVERIFIED: the WAA signer runs Google's interpreter JS in a
     * WebView (see [WaaSigner]); that JS path is not runtime-verified in
     * this environment, and existing-thread sends pass empty recipients
     * because per-thread participant lists aren't tracked here (the Go
     * bridge sources them from PortalMetadata.Participants).
     */
    private suspend fun signTracking(
        threadId: String,
        recipients: List<String>,
        txnId: Long,
    ): String {
        val signer = waaSigner ?: return "!"
        return try {
            signer.sign(threadId, recipients, txnId) ?: "!"
        } catch (t: Throwable) {
            Log.w(TAG, "WAA sign failed, using fallback tracking: ${t.message}")
            "!"
        }
    }

    // Issue 7: rate limit retry delay for 429 responses
    @Volatile private var rateLimitedUntil: Long = 0L
    private const val RATE_LIMIT_DELAY_MS = 30_000L

    private suspend fun doSend(
        client: GVoiceRpcClient,
        webId: String,
        req: Requests.ReqSendSMS,
    ): Boolean {
        // Check if we're still in a rate-limit cooldown
        val now = System.currentTimeMillis()
        val waitUntil = rateLimitedUntil
        if (now < waitUntil) {
            val remaining = waitUntil - now
            Log.w(TAG, "sendsms rate-limited, waiting ${remaining}ms before retry")
            delay(remaining)
        }
        return try {
            val resp = client.postPbLite(
                url = VoiceEndpoints.EndpointSendSms,
                body = req,
                responseTemplate = Responses.RespSendSMS.getDefaultInstance(),
            )
            Log.i(TAG, "sendsms ok thread=$webId itemId=${resp.threadItemID} ts=${resp.timestampMS}")
            if (resp.timestampMS > 0) {
                lastEvents.merge(webId, resp.timestampMS) { old, new -> maxOf(old, new) }
            }
            resp.threadItemID.isNotBlank()
        } catch (t: Throwable) {
            val errMsg = t.message ?: ""
            val httpStatus = Regex("HTTP (\\d+)").find(errMsg)?.groupValues?.get(1)?.toIntOrNull()
            when {
                httpStatus == 429 -> {
                    Log.e(TAG, "sendsms rate-limited (429) thread=$webId, backing off ${RATE_LIMIT_DELAY_MS}ms")
                    rateLimitedUntil = System.currentTimeMillis() + RATE_LIMIT_DELAY_MS
                }
                httpStatus != null && httpStatus in 400..499 ->
                    Log.e(TAG, "sendsms client error ($httpStatus) thread=$webId: $errMsg")
                else -> Log.w(TAG, "sendsms failed thread=$webId: $errMsg")
            }
            false
        }
    }

    // Issue 6: only png, jpeg, bmp, tiff — no gif/webp (matches Go handlematrix.go:70-81)
    private fun mimeToVoiceMediaType(mime: String): Requests.ReqSendSMS.Media.Type? =
        when (mime.lowercase()) {
            "image/png" -> Requests.ReqSendSMS.Media.Type.PNG
            "image/jpeg" -> Requests.ReqSendSMS.Media.Type.JPEG
            "image/bmp" -> Requests.ReqSendSMS.Media.Type.BMP
            "image/tiff" -> Requests.ReqSendSMS.Media.Type.TIFF
            else -> null
        }

    /**
     * Download an attachment by media ID. Mirrors `Client.DownloadAttachment`
     * in `pkg/libgv/client.go`. Returns (data, mimeType) or null on failure.
     */
    suspend fun downloadAttachment(mediaId: String): Pair<ByteArray, String>? {
        val client = rpc ?: return null
        val url = VoiceEndpoints.EndpointDownloadTemplate.format(AUTH_USER, mediaId)
        return try {
            client.getRaw(
                url = url,
                extraQuery = mapOf("s" to Threads.Attachment.Metadata.SizeSpec.ORIGINAL.number.toString()),
            )
        } catch (t: Throwable) {
            Log.w(TAG, "downloadAttachment failed id=$mediaId: ${t.message}")
            null
        }
    }

    /**
     * Boot the WAA signer for this session and send the readiness ping.
     * Mirrors `electron.go`: CreateWaa → load interpreter → request a
     * blank signature → PingWaa. On any failure the session continues
     * with the "!" tracking-data fallback (matches the Go bridge running
     * without Electron).
     */
    private suspend fun setupWaa() {
        val signer = WaaSigner(appContext) { createWaa() }
        val blank = try {
            signer.signBlank()
        } catch (t: Throwable) {
            Log.w(TAG, "WAA setup failed: ${t.message}")
            signer.destroy()
            return
        }
        if (blank == null) {
            Log.i(TAG, "WAA signer unavailable; sends will use fallback tracking data")
            signer.destroy()
            return
        }
        waaSigner = signer
        // Revives pingWaa (electron.go "ready" → blank signature → PingWaa).
        val pinged = pingWaa(blank, kotlin.random.Random.nextLong(2_000_000_000L))
        Log.i(TAG, "WAA ping ${if (pinged) "ok" else "failed"}")
    }

    /**
     * Create a WAA challenge token. Mirrors `Client.CreateWaa` in
     * `pkg/libgv/client.go`.
     */
    suspend fun createWaa(): Waa.CreatedWaa? {
        val client = rpc ?: return null
        return try {
            val req = Waa.ReqCreateWaa.newBuilder()
                .setRequestKey(VoiceEndpoints.WaaRequestKey)
                .build()
            val resp = client.postPbLite(
                url = VoiceEndpoints.EndpointCreateWaa,
                body = req,
                responseTemplate = Waa.RespCreateWaa.getDefaultInstance(),
            )
            resp.waa
        } catch (t: Throwable) {
            Log.w(TAG, "createWaa failed: ${t.message}")
            null
        }
    }

    /**
     * Ping the WAA endpoint with a generated signature. Mirrors
     * `Client.PingWaa` in `pkg/libgv/client.go`.
     */
    suspend fun pingWaa(signature: String, value: Long): Boolean {
        val client = rpc ?: return false
        return try {
            val req = Waa.ReqPingWaa.newBuilder()
                .setRequestKey(VoiceEndpoints.WaaRequestKey)
                .setPayload(signature)
                .setI1(72)
                .setI2(value)
                .build()
            client.postPbLite(
                url = VoiceEndpoints.EndpointPingWaa,
                body = req,
                responseTemplate = Waa.RespPingWaa.getDefaultInstance(),
            )
            true
        } catch (t: Throwable) {
            Log.w(TAG, "pingWaa failed: ${t.message}")
            false
        }
    }

    /**
     * Send a text message to a brand-new thread keyed by [recipients].
     * Mirrors the bridge's `HandleMatrixMessage`
     * (`pkg/connector/handlematrix.go:41`): builds a [Requests.ReqSendSMS]
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
        val req = buildSendReq(threadId = "", recipients = recipients) { setText(body) }
        return sendNewThreadInner(client, req)
    }

    /**
     * MMS variant of [sendNewThread].
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
        val req = buildSendReq(threadId = "", recipients = recipients) {
            caption?.takeIf { it.isNotBlank() }?.let { setText(it) }
            setMedia(media)
        }
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
            // Match Go: check failureType; cache nil for PERMANENT failures
            if (match.failureType != responses.Responses.RespLookupContacts.Match.FailureType.NO_FAILURE) {
                if (match.failureType == responses.Responses.RespLookupContacts.Match.FailureType.PERMANENT) {
                    contactCache[phone] = CachedContact(name = null, avatarUrl = null, timestamp = System.currentTimeMillis())
                }
                Log.d(TAG, "lookupContact: $phone failure=${match.failureType}")
                continue
            }
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
        // Match Go's processContact: prefer primary displayInfo, skip MONOGRAM avatars
        var displayName = "Unknown"
        var avatar: String? = null
        for (idx in 0 until person.contactMethodsCount) {
            val di = person.getContactMethods(idx).displayInfo ?: continue
            val isPrimary = di.primary
            val nameVal = di.name?.value?.takeIf { it.isNotBlank() }
            if (nameVal != null && (displayName == "Unknown" || isPrimary)) {
                displayName = nameVal
            }
            val photoUrl = di.photo?.url?.takeIf { it.isNotBlank() }
            if (photoUrl != null
                && di.photo.type != contacts.Contacts.ContactDisplayInfo.Photo.Type.MONOGRAM
                && (avatar == null || isPrimary)
            ) {
                avatar = photoUrl
            }
        }
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

    /** Load (or refresh) messages for a thread, following pagination to backfill. */
    fun fetchMessages(conversationId: String, count: Int = 100, paginationToken: String = "") {
        if (_state.value !is State.Connected) return
        scope.launch {
            val client = rpc ?: return@launch
            val webId = conversationId.substringAfter(':', conversationId)
            Log.i(TAG, "fetchMessages threadId=$webId count=$count")
            var token = paginationToken
            var pages = 0
            do {
                val req = Requests.ReqGetThread.newBuilder()
                    .setThreadID(webId)
                    .setMaybeMessageCount(count)
                    .setPaginationToken(token)
                    .setUnknownWrapper(
                        Requests.UnknownWrapper.newBuilder()
                            .setUnknownInt2(1)
                            .setUnknownInt3(1)
                    )
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
                // Follow the next-page cursor (libgv connector/handlegvoice.go loops
                // on thread.PaginationToken until empty); bound pages as a safety cap.
                token = thread.paginationToken
                pages++
            } while (token.isNotEmpty() && pages < MAX_BACKFILL_PAGES)
        }
    }

    // ----------------------------------------------------------------
    // Internals
    // ----------------------------------------------------------------

    /**
     * Validate credentials with GetAccount before starting the session.
     * Mirrors Go's `Connect` which always calls GetAccount first.
     */
    private suspend fun connectWithValidation(auth: VoiceAuthData) {
        _state.value = State.Connecting
        val client = rpc ?: GVoiceRpcClient(auth.cookies).also { rpc = it }
        try {
            client.postPbLite(
                url = VoiceEndpoints.EndpointGetAccount,
                body = Requests.ReqGetAccount.newBuilder().setUnknownInt2(1).build(),
                responseTemplate = Responses.RespGetAccount.getDefaultInstance(),
            )
        } catch (t: Throwable) {
            Log.e(TAG, "GetAccount failed on connect: ${t.message}")
            client.close()
            rpc = null
            val errMsg = t.message ?: ""
            val httpStatus = Regex("HTTP (\\d+)").find(errMsg)?.groupValues?.get(1)?.toIntOrNull()
            if (httpStatus == 401 || httpStatus == 403) {
                _state.value = State.BadCredentials(errMsg)
            } else {
                _state.value = State.ConnectError(errMsg)
            }
            return
        }
        bootSession(auth)
    }

    private fun bootSession(auth: VoiceAuthData) {
        val client = rpc ?: GVoiceRpcClient(auth.cookies).also { rpc = it }
        client.updateCookies(auth.cookies)
        client.onCookiesChanged = { updatedCookies ->
            scope.launch {
                val newAuth = VoiceAuthData(updatedCookies)
                newAuth.save(appContext)
                Log.i(TAG, "cookies refreshed and persisted")
            }
        }
        rpc = client

        // Fresh session ⇒ full thread list on first ListThreads.
        versionToken = ""

        // Boot the WAA request signer (revives createWaa/pingWaa). Mirrors
        // electron.go: create the Waa payload, load the interpreter, then
        // send the readiness ping with a blank signature.
        waaSigner?.destroy()
        waaSigner = null
        scope.launch { setupWaa() }

        scope.launch { loadInitialContacts() }

        realtime?.stop()
        realtime = RealtimeChannel(client) { evt ->
            when (evt) {
                RealtimeEvent.Connected -> {
                    Log.i(TAG, "realtime connected")
                    _state.value = State.Connected
                    kickoffBackfill()
                }
                is RealtimeEvent.Data -> handleRealtimeData(evt.event)
            }
        }.also { ch ->
            val rtJob = ch.start(scope)
            rtJob.invokeOnCompletion { cause ->
                if (!rtJob.isCancelled) {
                    when (ch.terminationReason) {
                        RealtimeChannel.TerminationReason.AuthError ->
                            _state.value = State.BadCredentials("realtime auth error")
                        RealtimeChannel.TerminationReason.TooManyRetries ->
                            _state.value = State.Disconnected("too many retries",
                                errorCode = "gv-too-many-retries")
                        else ->
                            _state.value = State.Disconnected("realtime connection lost",
                                errorCode = "gv-realtime-error")
                    }
                }
            }
        }
        startFetchLoop()
    }

    private fun kickoffBackfill() {
        backfillJob?.cancel()
        backfillJob = scope.launch { doBackfill() }
    }

    /**
     * Start the rate-limited fetch loop. Matches Go's fetchNewMessagesLoop:
     * 15-min background ticker, wakeup on realtime events, rate limiter.
     */
    private fun startFetchLoop() {
        fetchLoopJob?.cancel()
        fetchLoopJob = scope.launch {
            while (true) {
                delay(BACKGROUND_REFRESH_INTERVAL_MS)
                rateLimitedBackfill()
            }
        }
    }

    private suspend fun rateLimitedBackfill() {
        val now = System.currentTimeMillis()
        val elapsed = now - lastRefreshTime.get()
        val tokensToAdd = (elapsed / MIN_REFRESH_INTERVAL_MS).toInt()
        if (tokensToAdd > 0) {
            refreshTokens.updateAndGet { minOf(it + tokensToAdd, MIN_REFRESH_BURST) }
            lastRefreshTime.set(now)
        }
        if (refreshTokens.getAndDecrement() <= 0) {
            refreshTokens.incrementAndGet()
            Log.d(TAG, "rate-limited: skipping backfill")
            return
        }
        fetchLock.withLock { doBackfill() }
    }

    /** Load initial contacts into cache (matches Go's loadInitialContacts).
     *  Uses the RPC client directly — runs before state is Connected. */
    private suspend fun loadInitialContacts() {
        Log.i(TAG, "loading initial contacts")
        val client = rpc ?: return
        try {
            val req = Requests.ReqAutocompleteContacts.newBuilder()
                .setUnknownInt1(243)
                .setQuery("")
                .addAllUnknownInts3(listOf(1, 2))
                .setMaxResults(500)
                .build()
            val resp = client.postPbLite(
                url = VoiceEndpoints.EndpointAutocompleteContacts,
                body = req,
                responseTemplate = Responses.RespAutocompleteContacts.getDefaultInstance(),
            )
            for (i in 0 until resp.resultsCount) {
                val person = resp.getResults(i).person ?: continue
                var name: String? = null
                var avatar: String? = null
                for (idx in 0 until person.contactMethodsCount) {
                    val di = person.getContactMethods(idx).displayInfo ?: continue
                    val isPrimary = di.primary
                    val nameVal = di.name?.value?.takeIf { it.isNotBlank() }
                    if (nameVal != null && (name == null || isPrimary)) name = nameVal
                    val photoUrl = di.photo?.url?.takeIf { it.isNotBlank() }
                    if (photoUrl != null
                        && di.photo.type != contacts.Contacts.ContactDisplayInfo.Photo.Type.MONOGRAM
                        && (avatar == null || isPrimary)
                    ) avatar = photoUrl
                }
                for (idx in 0 until person.contactMethodsCount) {
                    val e164 = person.getContactMethods(idx).phone?.canonicalValue
                        ?.takeIf { it.isNotBlank() } ?: continue
                    val existing = contactCache[e164]
                    if (existing == null || person.getContactMethods(idx).displayInfo?.primary == true) {
                        contactCache[e164] = CachedContact(
                            name = name,
                            avatarUrl = avatar,
                            timestamp = System.currentTimeMillis(),
                        )
                    }
                }
            }
            Log.i(TAG, "loaded ${contactCache.size} initial contacts")
        } catch (t: Throwable) {
            Log.w(TAG, "failed to load initial contacts: ${t.message}")
        }
    }

    /** Fast cache lookup — no API call, returns null if missing or expired. */
    private fun getCachedContactFast(phone: String): CachedContact? {
        val cached = contactCache[phone] ?: return null
        if (System.currentTimeMillis() - cached.timestamp > CONTACT_CACHE_TTL_MS) return null
        return cached
    }

    /** Download an avatar with proper headers and a 5 MB size limit. */
    suspend fun downloadAvatar(url: String): ByteArray? {
        if (url.isBlank()) return null
        val client = rpc ?: return null
        return try {
            val (data, _) = client.getRaw(url = url)
            if (data.size > MAX_AVATAR_SIZE) {
                Log.w(TAG, "avatar too large: ${data.size} bytes")
                null
            } else data
        } catch (t: Throwable) {
            Log.w(TAG, "downloadAvatar failed: ${t.message}")
            null
        }
    }

    private suspend fun doBackfill() {
        val client = rpc ?: return
        val token = versionToken
        Log.i(TAG, "ListThreads (ALL_THREADS)${if (token.isNotEmpty()) " incremental" else ""}")
        // Mirror libgv ListThreads (client.go:132): pass the previous
        // versionToken for an incremental delta, and switch unknownInt2
        // 20→10 when a token is present.
        val req = Requests.ReqListThreads.newBuilder()
            .setFolder(Threads.ThreadFolder.ALL_THREADS)
            .setUnknownInt2(if (token.isNotEmpty()) 10 else 20)
            .setUnknownInt3(15)
            .setVersionToken(token)
            .setUnknownWrapper(
                Requests.UnknownWrapper.newBuilder()
                    .setUnknownInt2(1)
                    .setUnknownInt3(1)
            )
            .build()
        val resp = try {
            client.postPbLite(
                url = VoiceEndpoints.EndpointListThreads,
                body = req,
                responseTemplate = Responses.RespListThreads.getDefaultInstance(),
            )
        } catch (t: Throwable) {
            Log.w(TAG, "ListThreads failed: ${t.message}")
            return
        }
        // Persist the new cursor so the next backfill only fetches changes.
        resp.versionToken.takeIf { it.isNotEmpty() }?.let { versionToken = it }
        Log.i(TAG, "ListThreads: ${resp.threadsCount} threads")
        for (i in 0 until resp.threadsCount) {
            val thread = resp.getThreads(i)
            val threadId = thread.getID()
            emitConversation(thread)
            if (thread.messagesCount == 0) continue
            val latestTs = thread.getMessages(0).timestamp
            val prevTs = lastEvents.put(threadId, latestTs)
            for (j in 0 until thread.messagesCount) {
                val msg = thread.getMessages(j)
                if (prevTs != null && msg.timestamp <= prevTs) break
                emitMessage(threadId, msg, fromBackfill = true)
            }
        }
    }

    private fun handleRealtimeData(evt: webchannel.Webchannel.WebChannelEvent) {
        Log.d(TAG, "realtime event arrayID=${evt.arrayID} wrappers=${evt.dataWrapperCount}")
        if (!isNewMessages(evt)) return
        backfillJob?.cancel()
        backfillJob = scope.launch {
            delay(500)
            rateLimitedBackfill()
        }
    }

    /** Filter realtime events — match Go's isNewMessages: deep-check for
     *  sub2.data[0].unknownBytes starting with '['. */
    private fun isNewMessages(evt: webchannel.Webchannel.WebChannelEvent): Boolean {
        if (evt.dataWrapperCount == 0) return false
        val w = evt.getDataWrapper(0)
        if (w.dataCount == 0) return false
        val d = w.getData(0)
        if (!d.hasEvent()) return false
        val ev = d.event
        if (!ev.hasSub2()) return false
        val sub2 = ev.sub2
        if (sub2.dataCount == 0) return false
        val unknownBytes = sub2.getData(0).unknownBytes
        return unknownBytes.size() > 0 && unknownBytes.byteAt(0) == '['.code.toByte()
    }

    private suspend fun emitConversation(t: Threads.Thread) {
        // Filter out "Group Message." pseudo-contacts (matches Go's wrapChatInfo)
        val contacts = (0 until t.contactsCount)
            .map { t.getContacts(it) }
            .filter { !it.phoneNumber.startsWith("Group Message.") }
        val contact = contacts.firstOrNull()
        val peerPhone = contact?.phoneNumber?.takeIf { it.isNotBlank() }
        val device = peerPhone?.let {
            com.vayunmathur.messages.util.ContactResolver.lookup(appContext, it)
        }
        val cached = peerPhone?.let { getCachedContactFast(it) }

        val isGroup = contacts.size > 1
        val displayName: String? = when {
            isGroup -> {
                val names = contacts.mapNotNull { c ->
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
            else -> device?.displayName
                ?: cached?.name
                ?: peerPhone
                ?: contact?.name?.takeIf { it.isNotBlank() }
        }

        val latest: Threads.Message? = if (t.messagesCount > 0) {
            t.getMessages(0)
        } else null
        val preview = latest?.text?.takeIf { it.isNotBlank() }
        val tsMillis = latest?.timestamp ?: 0L

        _events.emit(
            GMEvent.ConversationUpdate(
                source = source,
                conversationId = t.getID(),
                peerName = displayName,
                peerPhone = if (isGroup) null else peerPhone,
                avatarUrl = device?.photoUri ?: cached?.avatarUrl,
                lastPreview = preview,
                lastTimestamp = tsMillis,
                unreadCount = if (!t.read) 1 else 0,
                isGroup = isGroup,
                participantCount = contacts.size,
                conversationType = "Voice",
            )
        )
    }

    private suspend fun emitMessage(threadId: String, m: Threads.Message, fromBackfill: Boolean = false) {
        val outgoing = when (m.type) {
            Threads.Message.Type.SMS_OUT,
            Threads.Message.Type.OUTGOING_CALL,
            Threads.Message.Type.OUTGOING_CALL_CANCELLED -> true
            else -> false
        }
        val tsMs = m.timestamp
        val peerPhone = when {
            m.hasMMS() && m.getMMS().senderPhoneNumber.isNotBlank() -> m.getMMS().senderPhoneNumber
            m.hasContact() -> m.contact.phoneNumber.takeIf { it.isNotBlank() }
            else -> null
        }
        val senderName = if (m.hasContact()) m.contact.name.takeIf { it.isNotBlank() } else null

        val body = buildMessageBody(m)
        if (body.isEmpty()) return

        _events.emit(
            GMEvent.MessageUpdate(
                source = source,
                conversationId = threadId,
                messageId = m.getID(),
                body = body,
                outgoing = outgoing,
                timestamp = tsMs,
                senderName = senderName,
            )
        )
        if (!outgoing && !fromBackfill) {
            _events.emit(
                GMEvent.IncomingMessage(
                    source = source,
                    conversationId = threadId,
                    messageId = m.getID(),
                    body = body,
                    peerName = senderName,
                    peerPhone = peerPhone,
                    timestamp = tsMs,
                )
            )
        }
    }

    private suspend fun buildMessageBody(m: Threads.Message): String {
        // Voicemail handling (checked first, matches Go's convertGVVoicemailMessage)
        if (m.type == Threads.Message.Type.VOICEMAIL &&
            m.coarseType == Threads.Message.CoarseType.CALL_TYPE_VOICEMAIL
        ) {
            val transcript = buildVoicemailTranscript(m)
            if (transcript.isNotBlank()) return "Voicemail: $transcript"
            // Match Go: empty transcript falls through to unknown message
        }

        // Missed calls — no text/MMS guard (matches Go's convertGVMissedCallMessage
        // which has no `msg.GetText() != ""` check)
        if (m.type == Threads.Message.Type.MISSED_CALL &&
            m.coarseType == Threads.Message.CoarseType.CALL_TYPE_MISSED
        ) {
            return "Missed voice call"
        }

        // Other call messages — only when no text/MMS content (matches Go's
        // convertGVCallMessage guard: `if msg.GetText() != "" || msg.GetMMS() != nil { return nil }`)
        if (m.text.isEmpty() && !m.hasMMS()) {
            when (m.coarseType) {
                Threads.Message.CoarseType.CALL_TYPE_INCOMING ->
                    if (m.type == Threads.Message.Type.INCOMING_CALL)
                        return formatCallBody(m.durationSeconds)
                Threads.Message.CoarseType.CALL_TYPE_OUTGOING -> when (m.type) {
                    Threads.Message.Type.OUTGOING_CALL -> return formatCallBody(m.durationSeconds)
                    Threads.Message.Type.OUTGOING_CALL_CANCELLED -> return "Unanswered voice call"
                    else -> {}
                }
                else -> {}
            }
        }

        // Unknown message: log + preserve base64 protobuf (matches Go's convertUnknownGVMessage)
        if (m.text.isEmpty() && !m.hasMMS()) {
            Log.w(TAG, "Unknown GV message id=${m.getID()} type=${m.type} coarseType=${m.coarseType}")
            try {
                val protoBytes = m.toByteArray()
                val encoded = android.util.Base64.encodeToString(protoBytes, android.util.Base64.NO_WRAP)
                if (encoded.length < 16 * 1024) {
                    Log.d(TAG, "unsupported_message_data=$encoded")
                }
            } catch (_: Throwable) {}
            return "Unknown message type, please view it on the Google Voice app"
        }

        // MMS messages
        if (m.hasMMS()) {
            val mms = m.getMMS()
            // Bold the subject (matches Go's fmt.Sprintf("**%s**\n%s", subject, text))
            val textPart = when {
                mms.subject.isNotEmpty() && mms.text.isNotEmpty() -> "**${mms.subject}**\n${mms.text}"
                mms.subject.isNotEmpty() -> "**${mms.subject}**"
                mms.text.isNotEmpty() -> mms.text
                else -> ""
            }
            val attachmentParts = (0 until mms.attachmentsCount).mapNotNull { i ->
                val att = mms.getAttachments(i)
                if (att.status == Threads.Attachment.Status.NOT_SUPPORTED) {
                    "File type not supported by Google Voice"
                } else {
                    // Download the attachment (matches Go's convertMedia pipeline)
                    val result = downloadAttachment(att.getID())
                    if (result == null) {
                        "Failed to download attachment"
                    } else {
                        val (data, detectedMime) = result
                        val finalMime = att.mimeType.ifBlank { detectedMime }
                        val prefix = finalMime.substringBefore('/')
                        saveAttachmentToCache(att.getID(), data, finalMime)
                        "[$prefix attachment: ${att.getID()}]"
                    }
                }
            }
            val parts = listOfNotNull(
                textPart.takeIf { it.isNotBlank() },
            ) + attachmentParts
            val result = parts.joinToString("\n")
            if (result.isBlank()) {
                Log.w(TAG, "Empty MMS message id=${m.getID()} type=${m.type} coarseType=${m.coarseType}")
                return "Unknown message type, please view it on the Google Voice app"
            }
            return result
        }

        return m.text
    }

    /** Save downloaded attachment to app cache for later retrieval. */
    private fun saveAttachmentToCache(id: String, data: ByteArray, mime: String) {
        try {
            val cacheDir = java.io.File(appContext.cacheDir, "gvoice_attachments")
            cacheDir.mkdirs()
            val ext = mime.substringAfter('/').takeIf { it.isNotBlank() && '/' !in it } ?: "bin"
            val file = java.io.File(cacheDir, "$id.$ext")
            file.writeBytes(data)
        } catch (t: Throwable) {
            Log.w(TAG, "failed to cache attachment $id: ${t.message}")
        }
    }

    private fun formatCallBody(durationSeconds: Float): String {
        val totalSec = (durationSeconds + 0.5f).toInt()
        if (totalSec <= 0) return "Voice call"
        val mins = totalSec / 60
        val secs = totalSec % 60
        val parts = mutableListOf<String>()
        if (mins > 0) parts += "$mins min${if (mins != 1) "s" else ""}"
        if (secs > 0) parts += "$secs sec${if (secs != 1) "s" else ""}"
        return "Voice call \u2022 ${parts.joinToString(" ")}"
    }

    private fun buildVoicemailTranscript(m: Threads.Message): String {
        if (!m.hasTranscript()) return ""
        return (0 until m.transcript.tokensCount).mapNotNull { i ->
            val text = m.transcript.getTokens(i).text
            if (text != null && text.isValidUtf8) {
                text.toStringUtf8().trim().takeIf { it.isNotBlank() }
            } else null
        }.joinToString(" ")
    }
}
