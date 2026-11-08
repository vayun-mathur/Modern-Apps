package com.vayunmathur.communicate.data.whatsapp

import android.app.Application
import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import com.vayunmathur.library.log.Log
import com.vayunmathur.communicate.data.whatsapp.buildMediaConnQuery
import com.vayunmathur.communicate.data.whatsapp.buildMexQuery
import com.vayunmathur.communicate.data.whatsapp.call.WhatsAppCallManager
import com.vayunmathur.communicate.data.whatsapp.e2e.WhatsAppE2E
import com.vayunmathur.communicate.data.whatsapp.e2e.WhatsAppE2E.ParsedPreKeyBundle
import com.vayunmathur.communicate.data.whatsapp.encodeNode
import com.vayunmathur.communicate.data.whatsapp.mex.MexResult
import com.vayunmathur.communicate.data.whatsapp.transport.WhatsAppSocket
import java.security.SecureRandom
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CompletableDeferred
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
import kotlinx.coroutines.withTimeoutOrNull

@OptIn(ExperimentalAtomicApi::class)
object WhatsAppClient {

    internal const val TAG = "WhatsAppClient"

    // HistorySync.syncType values (ref whatsmeow HistorySync_HistorySyncType).
    internal const val SYNC_INITIAL_BOOTSTRAP = 0
    internal const val SYNC_ON_DEMAND = 6

    // Number of older messages to request per on-demand page.
    internal const val ON_DEMAND_PAGE_SIZE = 50
    // Safety cap on on-demand pages per chat so a misbehaving peer can't loop forever
    // (ON_DEMAND_PAGE_SIZE * this ≈ upper bound on backfilled messages per chat).
    internal const val MAX_ON_DEMAND_PAGES_PER_CHAT = 400

    // When true, on-demand history keeps paginating older messages until the phone reports
    // end-of-history (an empty/anchor-only response). When false, only the recent pushed
    // history is kept (no backfill of "not downloaded" messages).
    @Volatile
    var fullHistorySync: Boolean = true

    sealed interface State {
        data object Idle : State
        data object NeedsSetup : State
        data object Connecting : State
        data object Connected : State
        data class Disconnected(val reason: String) : State
    }

    val source: MessageSource = MessageSource.WHATSAPP

    /** True only when the Noise socket is logged in (`<success>`). Group/message ops require it. */
    fun isConnected(): Boolean = stateMutable.value is State.Connected

    internal val stateMutable = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = stateMutable.asStateFlow()

    internal val eventsMutable = MutableSharedFlow<WhatsAppEvent>(extraBufferCapacity = 256)
    val events: SharedFlow<WhatsAppEvent> = eventsMutable.asSharedFlow()

    internal val initialized = AtomicBoolean(false)
    internal val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    internal val random = SecureRandom()

    internal lateinit var appContext: Application
    internal var authData: WhatsAppAuthData? = null
    // Use WebView-based WebSocket to bypass TLS fingerprinting
    // WhatsApp blocks non-browser TLS fingerprints (JA3). WebView uses Chromium's
    // network stack which is indistinguishable from Chrome browser.
    internal var webSocket: WhatsAppSocket? = null
    // Collector jobs for the current socket; cancelled on teardown so old (zombie) sockets
    // don't keep running keepalives or trigger reconnects, which causes self-conflicts.
    private var socketCollectorJobs: MutableList<Job> = mutableListOf()
    // Set when the server tells us this session is terminal (conflict/replaced/device removed),
    // so the disconnect handler does not start a pointless reconnect loop.
    internal var suppressReconnect = false
    internal var db: WhatsAppDatabase? = null
    private var e2e: WhatsAppE2E? = null
    private var backfillJob: Job? = null
    private var qrJob: Job? = null
    private var qrRotateJob: Job? = null

    // Pending IQ request/response correlation (by stanza id) for prekey fetch/upload/count.
    internal val pendingIqs = ConcurrentHashMap<String, CompletableDeferred<WhatsAppProtocol.Node>>()

    internal val nameCache = ConcurrentHashMap<String, String>()
    internal val lidToPhoneMap = ConcurrentHashMap<String, String>()
    internal val undecryptableTracker = ConcurrentHashMap<String, Int>()
    internal val pendingMessageIDs: MutableSet<String> = Collections.newSetFromMap(ConcurrentHashMap())
    internal val processedEditIDs: MutableSet<String> = Collections.newSetFromMap(ConcurrentHashMap())
    // Groups we've already fetched metadata for this session, so we publish the group's
    // ConversationUpdate (name / isGroup / participants) at most once instead of on every event.
    internal val knownGroups: MutableSet<String> = Collections.newSetFromMap(ConcurrentHashMap())
    private var reconnectJob: Job? = null
    private var reconnectAttempts = 0
    // True while a connect() attempt is actively setting up / handshaking, so the network-available
    // callback doesn't tear down an in-progress attempt (it fires right after registration).
    private val connectInProgress = AtomicBoolean(false)
    // Watches for the network coming back so we can reconnect immediately instead of waiting out
    // the backoff (or staying dead after a long offline stretch).
    private var connectivityCallback: ConnectivityManager.NetworkCallback? = null
    internal val pollSecrets = ConcurrentHashMap<String, ByteArray>()
    // Cached media upload connection (host, auth, expiryEpochMs) from the w:m media_conn IQ.
    private var mediaConnCache: Triple<String, String, Long>? = null

    internal val recentSentDMs: MutableMap<String, SentDM> = Collections.synchronizedMap(
        object : LinkedHashMap<String, SentDM>(64, 0.75f, false) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, SentDM>?): Boolean = size > 200
        }
    )
    // Per-(messageId|deviceJid) resend cap so a stuck peer can't loop us forever.
    internal val retryResendCounts = ConcurrentHashMap<String, Int>()

    // Whether this account has read receipts enabled (privacy setting). Default true (WhatsApp
    // default); refreshed from the server after <success>. When false we must not send read
    // receipts (the user turned them off).
    @Volatile internal var readReceiptsEnabled = true

    private const val INITIAL_RECONNECT_DELAY_MS = 1000L
    private const val MAX_RECONNECT_DELAY_MS = 60_000L
    internal const val MAX_FILE_SIZE = 50L * 1024 * 1024

    // Media upload/download run on library:network (HttpURLConnection).
    internal val MEDIA_CONNECT_TIMEOUT_MS = 30.seconds.inWholeMilliseconds
    internal val MEDIA_READ_TIMEOUT_MS = 60.seconds.inWholeMilliseconds

    // App-state collections to sync (ref whatsmeow appstate AllPatchNames).
    internal val APP_STATE_COLLECTIONS = listOf(
        "critical_block", "critical_unblock_low", "regular_high", "regular", "regular_low",
    )
    internal val appStateCollectionsFetching = java.util.Collections.synchronizedSet(HashSet<String>())
    internal val appStatePrefs by lazy {
        appContext.getSharedPreferences("wa_appstate", android.content.Context.MODE_PRIVATE)
    }
    internal val appStateKeyRequested = java.util.Collections.synchronizedSet(HashSet<String>())

    // Cursors ("chatJid|oldestMsgId") already requested, so a repeated response for the same anchor
    // stops the walk instead of looping. Distinct (older) cursors are allowed through so pagination
    // can continue.
    internal val onDemandRequested = Collections.synchronizedSet(HashSet<String>())
    // Pages requested per chat, to bound pagination (see MAX_ON_DEMAND_PAGES_PER_CHAT).
    internal val onDemandPageCount = ConcurrentHashMap<String, Int>()

    fun init(context: Context) {
        if (!initialized.compareAndSet(false, true)) return
        appContext = context.applicationContext as Application
        db = WhatsAppDatabase.getDatabase(appContext)
        WhatsAppCallManager.init(appContext, callBridge)
        Log.status(TAG, "init")
        runBlocking {
            val auth = WhatsAppAuthData.load(appContext)
            if (auth != null) {
                authData = auth
                stateMutable.value = State.Connecting
                registerNetworkMonitor()
            } else {
                stateMutable.value = State.NeedsSetup
            }
        }
    }

    fun start() {
        if (!initialized.load()) return
        // Only skip if fully connected. (Connecting is intentionally NOT skipped: a stale/stuck
        // Connecting state must be able to retry; connect() calls teardownSocket() first so an
        // overlapping attempt can't leave two live sockets.)
        if (stateMutable.value is State.Connected) return
        scope.launch {
            val auth = WhatsAppAuthData.load(appContext) ?: run {
                stateMutable.value = State.NeedsSetup
                return@launch
            }
            authData = auth
            connect(auth)
        }
    }

    fun stop() {
        // Never touch appContext / DAOs before init(); the sync service + MainActivity can call
        // stop() at startup (waSignedIn=false) before the client was ever initialized.
        if (!initialized.load()) return
        Log.status(TAG, "stop — disconnecting WhatsApp session")
        backfillJob?.cancel()
        qrJob?.cancel()
        qrRotateJob?.cancel()
        reconnectJob?.cancel()
        reconnectAttempts = 0
        suppressReconnect = true
        unregisterNetworkMonitor()
        webSocket?.disconnect()
        webSocket = null
        nameCache.clear()
        knownGroups.clear()
        // NOTE: credentials are intentionally NOT cleared here — that would wipe the registration
        // every time the foreground service stops. Sign-out (which clears auth) is owned by
        // WhatsAppLineSession.signOut().
        stateMutable.value = State.NeedsSetup
    }

    /**
     * User-triggered "force resync" (settings button): drop any existing socket and reconnect from
     * scratch, regardless of current state. This is the recovery path when the socket has silently
     * died — resetting attempts and calling connect() (which tears down the old socket first).
     * No-op if there is no saved session.
     */
    fun forceResync() {
        if (!initialized.load()) return
        val auth = authData ?: run {
            WhatsAppDiag.log(TAG, "forceResync: no session, ignoring")
            return
        }
        WhatsAppDiag.log(TAG, "forceResync: forcing full reconnect")
        reconnectAttempts = 0
        reconnectJob?.cancel()
        suppressReconnect = false
        scope.launch {
            try {
                connect(auth)
            } catch (expected: Exception) {
                Log.error(TAG, "forceResync connect failed", expected)
                scheduleReconnect()
            }
        }
    }

    private fun scheduleReconnect() {
        // Never permanently give up: a companion device must keep retrying so it recovers after a
        // long offline stretch (device asleep, no Wi-Fi/data) without a manual app restart. The
        // backoff is capped, and registerNetworkMonitor() short-circuits the wait when the network
        // actually returns.
        if (suppressReconnect || authData == null) return
        reconnectJob?.cancel()
        reconnectJob = scope.launch {
            // Exponential backoff capped at MAX_RECONNECT_DELAY_MS. Cap the shift too so the left
            // shift can't overflow/wrap once attempts grow large.
            val shift = reconnectAttempts.coerceAtMost(16)
            val delayMs = minOf(INITIAL_RECONNECT_DELAY_MS shl shift, MAX_RECONNECT_DELAY_MS)
            Log.status(TAG, "Reconnecting in ${delayMs}ms (attempt ${reconnectAttempts + 1})")
            stateMutable.value = State.Connecting
            delay(delayMs)
            reconnectAttempts++
            val auth = authData ?: return@launch
            try {
                connect(auth)
            } catch (expected: Exception) {
                Log.error(TAG, "Reconnection failed", expected)
                scheduleReconnect()
            }
        }
    }

    /**
     * Register a default-network callback so a returning network triggers an immediate reconnect,
     * instead of waiting out the (up to 60s) backoff or staying disconnected indefinitely. Idempotent.
     */
    private fun registerNetworkMonitor() {
        if (connectivityCallback != null) return
        val cm = appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                if (authData == null || suppressReconnect) return
                if (stateMutable.value is State.Connected) return
                if (connectInProgress.load()) return
                WhatsAppDiag.log(TAG, "network available — reconnecting now")
                reconnectAttempts = 0
                reconnectJob?.cancel()
                reconnectJob = scope.launch {
                    val auth = authData ?: return@launch
                    try {
                        connect(auth)
                    } catch (expected: Exception) {
                        Log.error(TAG, "reconnect on network-available failed", expected)
                        scheduleReconnect()
                    }
                }
            }
        }
        connectivityCallback = cb
        try {
            cm.registerDefaultNetworkCallback(cb)
        } catch (expected: Exception) {
            Log.error(TAG, "registerDefaultNetworkCallback failed", expected)
            connectivityCallback = null
        }
    }

    private fun unregisterNetworkMonitor() {
        val cb = connectivityCallback ?: return
        connectivityCallback = null
        try {
            (appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager)
                ?.unregisterNetworkCallback(cb)
        } catch (expected: Exception) {
            Log.error(TAG, "unregisterNetworkCallback failed", expected)
        }
    }

    /** Compact human-readable summary of a decoded binary-XML node for on-screen diagnostics. */
    internal fun nodeSummary(node: WhatsAppProtocol.Node): String {
        val attrs =
            if (node.attrs.isEmpty()) "" else " " + node.attrs.entries.joinToString(" ") { "${it.key}=${it.value}" }
        val kids = node.getChildren()
        val kidPart = if (kids.isEmpty()) "" else " {" + kids.joinToString(",") { it.tag } + "}"
        return "<${node.tag}$attrs>$kidPart"
    }

    private fun concat(vararg parts: ByteArray): ByteArray {
        val out = ByteArray(parts.sumOf { it.size })
        var off = 0
        for (p in parts) {
            System.arraycopy(p, 0, out, off, p.size)
            off += p.size
        }
        return out
    }

    internal fun ensureE2E(auth: WhatsAppAuthData): WhatsAppE2E? {
        val database = db ?: return null
        var inst = e2e
        if (inst == null) {
            inst = WhatsAppE2E(database, auth)
            inst.ensureSignedPreKeyStored()
            e2e = inst
        }
        return inst
    }

    /**
     * Send an <iq> stanza and await its result/error response by id.
     * Returns the response node, or null on timeout.
     */
    internal suspend fun sendIqAndWait(node: WhatsAppProtocol.Node, timeoutMs: Long = 30_000): WhatsAppProtocol.Node? {
        val ws = webSocket ?: return null
        val id = node.attrs["id"] ?: return null
        val deferred = CompletableDeferred<WhatsAppProtocol.Node>()
        pendingIqs[id] = deferred
        return try {
            if (!ws.send(WhatsAppProtocol.encodeNode(node))) {
                pendingIqs.remove(id)
                return null
            }
            withTimeoutOrNull(timeoutMs) { deferred.await() }
        } finally {
            pendingIqs.remove(id)
        }
    }

    /**
     * Perform a MEX (`w:mex`) GraphQL call over the existing Noise/XMPP socket (w2.md §5.2). Resolves
     * the persisted-query [operationName] → doc_id via [MexPersistedQueryProvider]; when the id isn't
     * in the bundled JSON returns a typed `no_persisted_id:<op>` transport failure (never crashes,
     * unlike the official debug client). Builds the `{"queryId":…,"variables":…}` envelope, sends the
     * `<iq xmlns="w:mex">`, waits (32s per §1.5), then extracts the `<result>` node's
     * `{"data":…,"errors":…}` envelope into a [MexResult].
     *
     * Dev-only: guarded by [WhatsAppFeature.enabled] at the repository layer; requires an active
     * connection (use [com.vayunmathur.communicate.data.whatsapp.mex.WhatsAppMex] to fall back to WWW
     * HTTP when disconnected).
     */
    suspend fun mexCall(
        operationName: String,
        variablesJson: String,
        type: String = "get",
    ): com.vayunmathur.communicate.data.whatsapp.mex.MexResult {
        val docId = com.vayunmathur.communicate.data.whatsapp.mex.MexPersistedQueryProvider
            .docIdFor(appContext, operationName)
            ?: run {
                WhatsAppDiag.log(TAG, "mex[$operationName]: no persisted doc_id (add it to mex_persist_ids.json)")
                return MexResult.transport("no_persisted_id:$operationName")
            }
        val id = generateMessageId()
        val queryJson = com.vayunmathur.communicate.data.whatsapp.mex.MexEnvelope.buildQueryJson(docId, variablesJson)
        val node = WhatsAppProtocol.buildMexQuery(docId, queryJson, id, type)
        val resp = sendIqAndWait(node, timeoutMs = 32_000)
            ?: return MexResult.transport("timeout")
        if (resp.attrs["type"] == "error") {
            val code = resp.getChildByTag("error")?.attrs?.get("code")
            WhatsAppDiag.log(TAG, "mex[$operationName]: iq error code=${code ?: "unknown"}")
            return MexResult.transport("iq_error:${code ?: "unknown"}")
        }
        val envelope = resp.getChildByTag("result")?.data?.toString(Charsets.UTF_8)
            ?: return MexResult.transport("no_result")
        val out = MexResult.fromEnvelope(envelope)
        WhatsAppDiag.log(TAG, "mex[$operationName]: success=${out.isSuccess} errors=${out.errors.size}")
        return out
    }

    /**
     * Upload one-time + signed prekeys to the server.
     * Ref whatsmeow prekeys.go uploadPreKeys. [initialUpload] uploads a large batch after pair.
     */
    /**
     * Upload prekeys on connect if the server is likely low. Does an initial large batch
     * when the local prekey store is empty (freshly paired). Ref whatsmeow prekeys.go.
     */
    internal suspend fun maybeUploadPreKeys() {
        val database = db ?: return
        val count = try { database.e2ePreKeyDao().getCount() } catch (ignored: Exception) { return }
        val unuploaded = try { database.e2ePreKeyDao().getUnuploaded().size } catch (ignored: Exception) { 0 }
        WhatsAppDiag.log(TAG, "prekeys: local count=$count unuploaded=$unuploaded")
        when {
            count == 0 -> uploadPreKeys(initialUpload = true)
            unuploaded > 0 -> uploadPreKeys(initialUpload = false)
            else -> WhatsAppDiag.log(TAG, "prekeys: nothing to upload")
        }
    }

    /**
     * Upload one-time + signed prekeys to the server.
     * Ref whatsmeow prekeys.go uploadPreKeys. [initialUpload] uploads a large batch after pair.
     */
    internal suspend fun uploadPreKeys(initialUpload: Boolean) {
        val auth = authData ?: return
        val crypto = ensureE2E(auth) ?: return
        val content = crypto.buildPreKeyUploadContent(initialUpload)
        val iq = WhatsAppProtocol.Node(
            tag = "iq",
            attrs = mapOf(
                "id" to generateMessageId(),
                "type" to "set",
                "xmlns" to "encrypt",
                "to" to "s.whatsapp.net",
            ),
            content = content,
        )
        WhatsAppDiag.log(TAG, "prekeys: uploading (initial=$initialUpload, ${content.size} nodes)…")
        val resp = sendIqAndWait(iq)
        if (resp != null && resp.attrs["type"] != "error") {
            crypto.markPreKeysUploaded()
            WhatsAppDiag.log(TAG, "prekeys: upload OK (initial=$initialUpload)")
        } else {
            WhatsAppDiag.log(TAG, "prekeys: upload FAILED/timeout (resp=${resp?.attrs?.get("type") ?: "null"})")
        }
    }

    /**
     * Fetch a peer's prekey bundle and establish an outbound session if none exists.
     * Ref whatsmeow prekeys.go fetchPreKeys + send.go encryptMessageForDevice.
     */
    internal suspend fun ensureSession(jid: String): Boolean {
        val auth = authData ?: return false
        val crypto = ensureE2E(auth) ?: return false
        if (crypto.hasSession(jid)) return true
        val bundle = fetchPreKeyBundle(jid) ?: return false
        return try {
            crypto.processPreKeyBundle(jid, bundle)
            true
        } catch (expected: Exception) {
            Log.error(TAG, "Failed to process prekey bundle for $jid", expected)
            false
        }
    }

    /** Fetch and parse the peer's pre-key bundle, or null when unavailable. */
    private suspend fun fetchPreKeyBundle(jid: String): ParsedPreKeyBundle? {
        val device = jid.substringBefore("@").substringAfter(":", "0").toIntOrNull() ?: 0
        val iq = WhatsAppProtocol.Node(
            tag = "iq",
            attrs = mapOf(
                "id" to generateMessageId(),
                "type" to "get",
                "xmlns" to "encrypt",
                "to" to "s.whatsapp.net",
            ),
            content = listOf(
                WhatsAppProtocol.Node(
                    tag = "key",
                    content = listOf(
                        WhatsAppProtocol.Node(
                            tag = "user",
                            attrs = mapOf("jid" to jid, "reason" to "identity"),
                        )
                    ),
                )
            ),
        )
        val resp = sendIqAndWait(iq) ?: return null
        val list = resp.getChildByTag("list") ?: return null
        val userNode = list.getChildren().firstOrNull { it.tag == "user" } ?: return null
        val auth = authData ?: return null
        return ensureE2E(auth)?.parsePreKeyBundleNode(device, userNode)
    }

    /**
     * Refresh a peer's presence via `xwa2_presence_data_platform_get_online_or_last_status` and emit
     * [WhatsAppEvent.PresenceUpdate] (Phase F 1e). Dev-gated + best-effort. [conversationId] may be a
     * `wa:<jid>` id or a bare JID.
     */
    suspend fun refreshPresence(conversationId: String) {
        if (!WhatsAppFeature.enabled) return
        val jid = extractJid(conversationId) ?: return
        val payload = fetchPresencePayload(jid) ?: return
        val presences = payload.optJSONArray("presences") ?: return
        if (presences.length() == 0) return
        val p = presences.optJSONObject(0) ?: return
        val lastSeen = p.optString("last_seen").toLongOrNull() ?: 0L
        eventsMutable.emit(WhatsAppEvent.PresenceUpdate(
            conversationId = "wa:$jid",
            isOnline = lastSeen == 0L, // online payloads omit last_seen; last-status carries it
            lastSeen = lastSeen,
        ))
    }

    /** Presence payload for [jid], or null when unavailable. */
    private suspend fun fetchPresencePayload(jid: String): org.json.JSONObject? {
        val res = runCatching {
            com.vayunmathur.communicate.data.whatsapp.mex.WhatsAppMexOps.getOnlineOrLastStatus(
                appContext,
                listOf(jid),
                null)
        }.getOrNull() ?: return null
        if (!res.isSuccess) return null
        val data = res.data ?: return null
        return data.optJSONObject("xwa2_presence_data_platform_get_online_or_last_status")
    }

    /**
     * Fetch (and cache) the media upload host + auth token via the w:m media_conn IQ.
     * Ref whatsmeow mediaconn.go queryMediaConn. Returns (host, auth) or null.
     */
    private const val MS_PER_SECOND = 1000L

    internal suspend fun mediaConn(): Pair<String, String>? {
        mediaConnCache?.let { if (System.currentTimeMillis() < it.third) return it.first to it.second }
        val iq = WhatsAppProtocol.buildMediaConnQuery(generateMessageId())
        val resp = sendIqAndWait(iq) ?: return null
        val mc = resp.getChildByTag("media_conn") ?: return null
        val auth = mc.attrs["auth"] ?: return null
        val ttl = mc.attrs["ttl"]?.toLongOrNull() ?: 300L
        val host = mc.getChildren().firstOrNull { it.tag == "host" }?.attrs?.get("hostname") ?: return null

        mediaConnCache = Triple(host, auth, System.currentTimeMillis() + ttl * MS_PER_SECOND)
        return host to auth
    }



    /** Cancel the current socket's collectors and disconnect it, so only one socket is ever live. */
    private fun teardownSocket() {
        socketCollectorJobs.forEach { it.cancel() }
        socketCollectorJobs.clear()
        webSocket?.disconnect()
        webSocket = null
    }

    private suspend fun connect(auth: WhatsAppAuthData) {
        stateMutable.value = State.Connecting
        suppressReconnect = false
        connectInProgress.store(true)
        registerNetworkMonitor()
        // Ensure no previous socket (provisioning or a prior login attempt) is still alive,
        // otherwise overlapping sessions make the server reject us with <stream:error><conflict>.
        teardownSocket()

        // Use WebView-based WebSocket to bypass TLS fingerprinting
        val ws = WhatsAppSocket(auth)
        webSocket = ws
        socketCollectorJobs.add(
            scope.launch {
                ws.connectionState.collect { state ->
                    when (state) {
                        is WhatsAppSocket.ConnectionState.Connected -> {
                            WhatsAppDiag.log(TAG, "login socket: Noise connected, awaiting <success>")
                            connectInProgress.store(false)
                            ensureE2E(auth)
                        }
                        is WhatsAppSocket.ConnectionState.Disconnected -> {
                            WhatsAppDiag.log(TAG, "login socket: Disconnected (${state.reason})")
                            connectInProgress.store(false)
                            if (authData != null && !suppressReconnect) {
                                scheduleReconnect()
                            } else {
                                stateMutable.value = State.Disconnected(state.reason)
                            }
                        }
                        else -> {}
                    }
                }
            }
        )
        socketCollectorJobs.add(
            scope.launch {
                ws.messages.collect { data ->
                    handleIncomingMessage(data)
                }
            }
        )
        ws.connect()
    }

    /**
     * Transport bridge for [WhatsAppCallManager]: lets the call state machine send `<call>` stanzas
     * and encrypt/decrypt the per-device call key without depending on the socket/Signal internals.
     */
    private val callBridge = object : WhatsAppCallManager.Bridge {
        override val ownJid: String get() = authData?.wid ?: ""

        override fun newId(): String = generateMessageId()

        override suspend fun sendCallStanza(node: WhatsAppProtocol.Node): Boolean {
            val ws = webSocket ?: return false
            return ws.send(WhatsAppProtocol.encodeNode(node))
        }

        override suspend fun encryptCallKey(
            toJid: String,
            paddedCallKey: ByteArray,
        ): List<WhatsAppProtocol.ParticipantEnc> {
            val auth = authData ?: return emptyList()
            val crypto = ensureE2E(auth) ?: return emptyList()
            val ownUser = auth.wid.substringBefore("@").substringBefore(":").substringBefore(".")
            val devices = (getUserDevices(listOf(toJid)).ifEmpty { listOf(toJid) } +
                getUserDevices(listOf("$ownUser@s.whatsapp.net"))).distinct()
            return encryptForDevices(crypto, devices, ownUser, auth.wid, paddedCallKey, null).first
        }

        override suspend fun decryptCallKey(
            senderJid: String,
            encType: String,
            ciphertext: ByteArray,
        ): ByteArray? {
            val auth = authData ?: return null
            val crypto = ensureE2E(auth) ?: return null
            return try {
                crypto.decryptDM(senderJid, isPreKey = encType == "pkmsg", ciphertext = ciphertext)
            } catch (expected: Exception) {
                Log.status(TAG, "call key decrypt failed from $senderJid", expected)
                null
            }
        }

        override fun emit(event: WhatsAppEvent) {
            scope.launch { eventsMutable.emit(event) }
        }

        override fun resolveName(jid: String): String? {
            // Non-suspend, best-effort: the phone form of the JID. Full name resolution happens
            // later off the emitted event (resolveName is suspend / DB-backed).
            val local = jid.substringBefore("@").substringBefore(":").substringBefore(".")
            return if (local.isNotEmpty() && local.all { it.isDigit() }) "+$local" else null
        }
    }

    /**
     * Send a typing indicator (chat presence) with media type differentiation.
     * From whatsmeow HandleMatrixTyping / SendChatPresence.
     * Supports: text typing, audio recording, media uploading.
     */
    enum class TypingType { TEXT, RECORDING_AUDIO, UPLOADING_MEDIA }

    /**
     * Handle the server <success> stanza: the point at which login is actually authenticated.
     * Mirrors whatsmeow connectionevents.go handleConnectSuccess — persists the server-assigned
     * LID, sends SetPassive(false) so the companion leaves passive mode and receives the full
     * event stream, then uploads prekeys if low, sends unavailable presence and starts backfill.
     */
    internal suspend fun handleConnectSuccess(node: WhatsAppProtocol.Node) {
        WhatsAppDiag.log(TAG, "AUTHENTICATED (<success>) — login complete")
        reconnectAttempts = 0
        stateMutable.value = State.Connected
        val lid = node.attrs["lid"]
        val current = authData
        if (!lid.isNullOrEmpty() && current != null && current.lid.isEmpty()) {
            val updated = current.copy(lid = lid)
            authData = updated
            WhatsAppAuthData.save(appContext, updated)
        }
        scope.launch { maybeUploadPreKeys() }
        scope.launch { refreshPrivacySettings() }
        // Phase C 2f: best-effort device-contact sync after login (dev-gated, needs READ_CONTACTS).
        scope.launch { runCatching { WhatsAppContactSync.sync(appContext) } }
        val pas = setPassiveActive()
        WhatsAppDiag.log(TAG, "post-success: SetPassive(active) ${if (pas) "OK" else "FAILED"}")
        sendUnavailablePresence()
        kickoffBackfill()
    }

    /**
     * Tell the server this device is active (not passive) so it pushes the full event stream.
     * Ref whatsmeow connectionevents.go SetPassive(false):
     * <iq xmlns="passive" type="set"><active/></iq>. Login sends passive=true, so this is
     * required after <success> or the companion never receives messages.
     */
    internal suspend fun setPassiveActive(): Boolean {
        val node = WhatsAppProtocol.Node(
            tag = "iq",
            attrs = mapOf(
                "id" to generateMessageId(),
                "type" to "set",
                "xmlns" to "passive",
                "to" to "s.whatsapp.net",
            ),
            content = listOf(WhatsAppProtocol.Node(tag = "active")),
        )
        val resp = sendIqAndWait(node, timeoutMs = 10_000)
        return resp != null && resp.attrs["type"] != "error"
    }

    /**
     * Send unavailable presence on connect.
     * Go client.go Connected handler sends PresenceUnavailable.
     */
    internal fun sendUnavailablePresence() {
        val ws = webSocket ?: return
        val node = WhatsAppProtocol.Node(
            tag = "presence",
            attrs = mapOf("type" to "unavailable"),
        )
        ws.send(WhatsAppProtocol.encodeNode(node))
    }
}
