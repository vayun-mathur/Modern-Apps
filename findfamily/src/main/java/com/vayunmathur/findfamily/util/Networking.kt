package com.vayunmathur.findfamily.util
import com.vayunmathur.library.log.Log
import com.vayunmathur.findfamily.data.LocationValue
import com.vayunmathur.findfamily.data.TemporaryLink
import com.vayunmathur.findfamily.data.User
import com.vayunmathur.findfamily.tracker.PoweredOffProtocol
import com.vayunmathur.findfamily.data.RequestStatus
import com.vayunmathur.findfamily.data.FindFamilyRepository
import com.vayunmathur.findfamily.uwb.UwbEnvelope
import com.vayunmathur.e2ee.E2eeKeyStore
import com.vayunmathur.e2ee.Pqc
import com.vayunmathur.e2ee.PqcIdentity
import com.vayunmathur.library.network.WebSocketClient
import com.vayunmathur.library.network.WsSession
import com.vayunmathur.library.network.webSocket
import com.vayunmathur.library.util.DataStoreUtils
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.io.encoding.Base64
import kotlin.random.Random
import kotlin.time.Clock

/**
 * FindFamily networking — **WebSocket only**. Every server interaction (register,
 * key lookup, publish, receive) rides one persistent binary socket at
 * `wss://api.maproj.org/api/ws`; there are deliberately no HTTP requests and no
 * HTTP fallback. If the socket drops, [startLive] reconnects (1s→15s backoff) and
 * re-registers on connect.
 *
 * Post-quantum only: there is no classic RSA identity. The PQC identity mirrors
 * Office's `PqcIdentity.loadOrCreate` with a distinct `ff_pqc` DataStore prefix.
 * Bundle format [4B kemLen][kemPubDer][dsaPubDer]; sealed layout
 * [4B encapLen][encap][aesGCM] with KDF SHA256(BE32(1)||Z) — iOS must match to interop.
 */
object Networking {
    internal const val TAG = "FF-Networking"
    private const val PLATFORM = "android"

    /**
     * Base for recipient share links (`$SHARE_BASE/<base26-id>#s=<seed>`).
     * Served by the same `/view/:id` route that `findfamily.cc` serves, so
     * links minted on either host resolve identically. Host migrated from
     * findfamily.cc to api.maproj.org; findfamily.cc remains valid for
     * backward compatibility during the transition.
     */
    internal const val SHARE_BASE = "https://api.maproj.org/view"

    internal val json = Json { ignoreUnknownKeys = true }

    /** Shared end-to-end-encryption identity (key generation/storage/crypto lives in :library:e2ee-p2p). */
    internal lateinit var pqcIdentity: PqcIdentity
    @Volatile internal var pqcReady = false
    @Volatile private var pqcInitAttempted = false

    var userid = 0L
        private set

    internal lateinit var repository: FindFamilyRepository
    private lateinit var dataStoreUtils: DataStoreUtils

    // init() is called from both the app UI (on launch) and the location
    // foreground service (which may start first). Guard it so only one coroutine
    // runs the identity/userid bootstrap at a time, and make it a no-op once done.
    private val initMutex = Mutex()
    @Volatile
    private var initialized = false

    /**
     * True while running on the device-protected mirror, before the user has unlocked.
     * In this mode [repository] is not bound and must not be touched — the Room database
     * is credential-encrypted and unreadable.
     */
    @Volatile
    private var directBoot = false

    /** Whether the pre-unlock path is currently driving the socket. */
    val inDirectBootMode: Boolean get() = directBoot

    /** Adapts the app's encrypted DataStore to the e2ee module's storage abstraction. */
    private class DataStoreKeyStore(private val ds: DataStoreUtils) : E2eeKeyStore {
        override suspend fun getBytes(name: String): ByteArray? = ds.getByteArrayAwait(name)
        override suspend fun setBytes(name: String, value: ByteArray, onlyIfAbsent: Boolean) =
            ds.setByteArray(name, value, onlyIfAbsent)
    }

    // Broad Throwable catch below is deliberate: PQC identity load crosses the
    // native boundary (libe2ee_pqc.so may fail to load with UnsatisfiedLinkError,
    // an Error, not an Exception); the app must degrade to not-sharing, not crash.
    @Suppress("TooGenericExceptionCaught")
    suspend fun init(repository: FindFamilyRepository, dataStoreUtils: DataStoreUtils, meName: String) {
        if (initialized) return
        initMutex.withLock {
            if (initialized) return
            Networking.dataStoreUtils = dataStoreUtils
            Networking.repository = repository
            // FindFamily is post-quantum only — there is no classic RSA identity. The PQC identity
            // uses the same library as Office with a distinct `ff_pqc` prefix. The try/catch keeps the
            // app from crashing if libe2ee_pqc.so fails to load (pqcReady stays false and the app
            // cannot share until the native lib is present); there is deliberately no RSA fallback.
            if (!pqcInitAttempted) {
                pqcInitAttempted = true
                try {
                    pqcIdentity = PqcIdentity.loadOrCreate(DataStoreKeyStore(dataStoreUtils), "ff_pqc")
                    pqcReady = true
                    Log.dev(TAG, "PQC identity ready bundleLen=${pqcIdentity.publicBundle.size}")
                } catch (e: Throwable) {
                    Log.status(TAG, "PQC identity unavailable (native lib load failed)", e)
                    pqcReady = false
                }
            }
            // Avoid negative IDs: server keys on ULong; generating only positive IDs keeps both
            // sides consistent and makes Base26 encoding stable.
            if (dataStoreUtils.getLongAwait("userid") == null) {
                dataStoreUtils.setLong("userid", Random.nextLong(from = 1, until = Long.MAX_VALUE), true)
            }
            userid = dataStoreUtils.getLongAwait("userid")!!

            if (repository.getUser(userid) == null) {
                repository.upsertUser(
                    User(
                        meName,
                        null,
                        "Unnamed Location",
                        true,
                        RequestStatus.MUTUAL_CONNECTION,
                        Clock.System.now(),
                        null,
                        userid,
                    )
                )
            }
            initialized = true
        }
    }

    /**
     * Bootstrap from the device-protected mirror, for the window between a reboot and the
     * first unlock. Loads the identity [DirectBootStore] copied out of credential-encrypted
     * storage and binds no repository, since Room is unreadable until the passcode is entered.
     *
     * Returns false, rather than creating anything, when the mirror has not been seeded.
     * `PqcIdentity.loadOrCreate` would happily mint a fresh identity, and a userid invented
     * here would not be the one in credential-encrypted storage — every publish would arrive
     * at peers as an unknown sender. Doing nothing is the correct failure.
     */
    // Broad Throwable catch below is deliberate, same as [init]: native load boundary.
    @Suppress("TooGenericExceptionCaught")
    suspend fun initDirectBoot(ds: DataStoreUtils): Boolean {
        if (initialized) return directBoot
        initMutex.withLock {
            if (initialized) return directBoot
            val mirroredId = ds.getLongAwait("userid") ?: return false
            dataStoreUtils = ds
            if (!pqcInitAttempted) {
                pqcInitAttempted = true
                try {
                    pqcIdentity = PqcIdentity.loadOrCreate(DataStoreKeyStore(ds), "ff_pqc")
                    pqcReady = true
                } catch (e: Throwable) {
                    Log.status(TAG, "direct boot: PQC identity unavailable", e)
                    pqcReady = false
                }
            }
            if (!pqcReady) return false
            userid = mirroredId
            directBoot = true
            initialized = true
            Log.dev(TAG, "direct boot init as ${userid.toULong()}")
            return true
        }
    }

    /**
     * Hand over from the mirror to the real credential-encrypted state once the user unlocks.
     * Drops the socket first so the reconnect re-subscribes with the identity loaded from
     * credential-encrypted storage, which is the authoritative copy.
     */
    suspend fun promoteToUnlocked(
        repository: FindFamilyRepository,
        ceStore: DataStoreUtils,
        meName: String,
    ) {
        initMutex.withLock {
            if (!directBoot) return
            directBoot = false
            initialized = false
            pqcInitAttempted = false
            pqcReady = false
        }
        stopLive()
        init(repository, ceStore, meName)
    }

    // ----------------------------------------------------------------
    // Binary wire protocol (no JSON, no base64 on the wire):
    //   client→server SUB    : [0x01][u64 userid][optional raw PQC bundle…]  (subscribe + register)
    //   client→server PUB    : [0x02][flags][u64 recipient][raw ciphertext…]
    //   client→server GETKEY : [0x04][u64 target userid]
    //   server→client MSG    : [0x03][flags][raw ciphertext…]
    //   server→client KEYRESP: [0x05][status][u64 target userid][optional raw PQC bundle…]
    // flags: bit0 = kind (0 location, 1 uwb). KEYRESP status: 0 none, 1 classic, 2 pqc.
    // ----------------------------------------------------------------

    private const val WS_URL = "wss://api.maproj.org/api/ws"

    internal const val WS_OP_SUB: Byte = 0x01
    private const val WS_OP_PUB: Byte = 0x02
    internal const val WS_OP_MSG: Byte = 0x03
    private const val WS_OP_GETKEY_REQ: Byte = 0x04
    internal const val WS_OP_GETKEY_RESP: Byte = 0x05
    internal const val WS_FLAG_UWB = 0x01

    // Custom UWB tracker crowd-finding opcodes live in NetworkingTracker.kt.

    private const val WS_KEY_NONE = 0
    private const val WS_KEY_CLASSIC = 1
    private const val WS_KEY_PQC = 2

    internal const val GETKEY_TIMEOUT_MS = 5_000L

    internal const val BYTE_MASK = 0xFFL
    internal const val INT_BYTE_MASK = 0xFF
    internal const val BITS_PER_BYTE = 8
    internal const val U64_LEN = 8
    internal const val U64_MSB_SHIFT = 56
    internal const val U32_LEN = 4
    internal const val U32_BYTE0_SHIFT = 24
    internal const val U32_BYTE1_SHIFT = 16
    internal const val SUB_FRAME_HEADER_LEN = 9
    internal const val BUNDLE_FIELD_OFFSET = 9
    internal const val KEYRESP_HEADER_LEN = 10
    internal const val RESOLVE_RESP_MIN_LEN = 18
    internal const val REPORT_GET_RESP_HEADER_LEN = 3

    /** MSG frame: [op][flags][ciphertext…]. */
    internal const val MSG_FRAME_HEADER_LEN = 2
    internal const val MSG_FLAGS_OFFSET = 1
    internal const val MSG_PAYLOAD_OFFSET = 2

    /** KEYRESP frame: [op][status][u64 target][bundle…]. */
    internal const val KEYRESP_STATUS_OFFSET = 1
    internal const val KEYRESP_TARGET_OFFSET = 2

    /** RESOLVE_RESP frame: [op][status][16B epochId][bundle…]. */
    internal const val RESOLVE_STATUS_OFFSET = 1
    internal const val RESOLVE_EPOCH_OFFSET = 2
    internal const val RESOLVE_EPOCH_END = 18
    internal const val RESOLVE_FOUND_STATUS = 1

    /** REPORT_GET_RESP count field: u16 at [REPORT_COUNT_OFFSET]. */
    internal const val REPORT_COUNT_OFFSET = 1

    private const val BACKOFF_INITIAL_MS = 1_000L
    private const val BACKOFF_MAX_MS = 15_000L
    private const val PUB_FRAME_HEADER_LEN = 10
    private const val PUB_PAYLOAD_OFFSET = 10

    /**
     * Keepalive ping cadence. Mobile NATs/proxies drop idle sockets within a few
     * minutes, and a half-open socket is otherwise only noticed on the next write;
     * a periodic ping keeps the connection alive and surfaces breakage fast so the
     * server's fan-outs land and queued backlog drains promptly on reconnect.
     */
    internal const val PING_INTERVAL_MS = 30_000L

    /**
     * If no frame at all (pong, location push, key response — anything) arrives
     * within this window, the socket is treated as half-open and force-closed to
     * trigger a reconnect. This is the crucial half-open detector: a half-open
     * socket still accepts writes into the local TCP send buffer, so `send`/`ping`
     * keep returning success and would otherwise never be noticed as dead. The
     * server replies to our pings with pongs, so a healthy socket always refreshes
     * this well within the window.
     */
    internal const val LIVENESS_TIMEOUT_MS = 75_000L

    @Volatile internal var wsSession: WsSession? = null
    private var liveJob: Job? = null

    /** In-flight GETKEY requests, keyed by target userid, completed when the KEYRESP arrives. */
    internal val pendingKeyRequests = ConcurrentHashMap<Long, CompletableDeferred<KeyResult?>>()

    /** In-flight tracker RESOLVE requests, keyed by the epoch-id hex, completed on RESOLVE_RESP. */
    internal val pendingResolves = ConcurrentHashMap<String, CompletableDeferred<ByteArray?>>()

    /** In-flight tracker REPORT_GET requests (FIFO — the owner polls them one at a time). */
    internal val pendingReportGets = ConcurrentLinkedQueue<CompletableDeferred<List<ByteArray>>>()

    /** True while the live socket is connected. */
    val liveConnected: Boolean get() = wsSession != null

    internal data class KeyResult(val status: Int, val bundle: ByteArray?)

    /** Reads 8 big-endian bytes at [off] back into the original Long bit pattern. */
    internal fun readU64Be(src: ByteArray, off: Int): Long {
        var v = 0L
        for (i in 0 until U64_LEN) v = (v shl BITS_PER_BYTE) or (src[off + i].toLong() and BYTE_MASK)
        return v
    }

    /** Encodes [v] as 8 big-endian bytes into [dst] starting at [off]. */
    internal fun putU64Be(dst: ByteArray, off: Int, v: ULong) {
        for (i in 0 until U64_LEN) dst[off + i] = (v shr (U64_MSB_SHIFT - i * BITS_PER_BYTE)).toByte()
    }

    /**
     * Start (or no-op if already running) the live relay loop. On connect it subscribes
     * and registers this device's PQC bundle in one frame; [onLocations]/[onUwb] then
     * receive already-decrypted pushes. Reconnects with 1s→15s backoff — there is no
     * HTTP fallback, so a dropped socket is simply re-established.
     */
    // @Synchronized (with stopLive) makes the check-and-set atomic. onStartCommand can
    // fire concurrently (START_STICKY redelivery, ServiceRestartWorker, BootReceiver),
    // each launching startTracking()->startLive() on Dispatchers.IO. Without this, two
    // invocations both pass the isActive check before either assigns liveJob, leaving
    // two live WebSocket connections — one orphaned and unstoppable. Idempotent: a call
    // while a connection is already live is a no-op.
    @Synchronized
    fun startLive(
        scope: CoroutineScope,
        onLocations: suspend (List<InboundFix>) -> Unit,
        onUwb: suspend (List<UwbEnvelope>) -> Unit,
    ) {
        if (liveJob?.isActive == true) return
        stopLive()
        liveJob = scope.launch(Dispatchers.IO) {
            val outerScope = this
            var backoff = BACKOFF_INITIAL_MS
            while (isActive) {
                // Per-connection scope for the reader. Deliberately independent of the
                // supervisor loop below: a blocking readFrame() — or a stuck delivery
                // callback — that refuses to unwind must never be able to wedge reconnect,
                // so the reader is launched here and abandoned on teardown rather than
                // structurally awaited.
                val connScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
                runCatching {
                    webSocket(WS_URL) {
                        wsSession = this
                        sendSubscribe()
                        backoff = BACKOFF_INITIAL_MS
                        superviseConnection(connScope, outerScope, onLocations, onUwb)
                    }
                }.onFailure { Log.status(TAG, "live WS loop error", it) }
                connScope.cancel()
                wsSession = null
                // Fail any awaiting key lookups so their callers don't hang until timeout.
                pendingKeyRequests.values.forEach { it.complete(null) }
                pendingKeyRequests.clear()
                pendingResolves.values.forEach { it.complete(null) }
                pendingResolves.clear()
                while (true) { (pendingReportGets.poll() ?: break).complete(emptyList()) }
                if (!isActive) break
                delay(backoff)
                backoff = (backoff * 2).coerceAtMost(BACKOFF_MAX_MS)
            }
        }
    }

    @Synchronized
    fun stopLive() {
        liveJob?.cancel(); liveJob = null; wsSession = null
        pendingKeyRequests.values.forEach { it.complete(null) }
        pendingKeyRequests.clear()
        pendingResolves.values.forEach { it.complete(null) }
        pendingResolves.clear()
        while (true) { (pendingReportGets.poll() ?: break).complete(emptyList()) }
    }

    /** Send an already-encrypted payload to [recipient] over the socket. False if the socket is down. */
    private suspend fun sendLivePublish(recipient: Long, kind: String, raw: ByteArray): Boolean {
        val session = wsSession ?: return false
        return runCatching {
            val frame = ByteArray(PUB_FRAME_HEADER_LEN + raw.size)
            frame[0] = WS_OP_PUB
            frame[1] = (if (kind == "uwb") WS_FLAG_UWB else 0).toByte()
            putU64Be(frame, 2, recipient.toULong())
            raw.copyInto(frame, PUB_PAYLOAD_OFFSET)
            session.send(frame)
            true
        }.onFailure { Log.status(TAG, "live publish failed", it) }.getOrDefault(false)
    }

    /**
     * Look up a peer's key over the socket (request/response, [GETKEY_TIMEOUT_MS] timeout).
     * Returns null when the socket is down or the reply times out — callers treat that as
     * "unknown" and rely on reconnection rather than any HTTP fallback.
     */
    // Broad catch is deliberate: the relay socket's failures surface as undocumented
    // runtime exceptions; each is logged and mapped to a failure return below.
    @Suppress("TooGenericExceptionCaught")
    private suspend fun wsGetKey(userId: Long): KeyResult? {
        val session = wsSession ?: return null
        val deferred = CompletableDeferred<KeyResult?>()
        pendingKeyRequests[userId] = deferred
        return try {
            val req = ByteArray(9)
            req[0] = WS_OP_GETKEY_REQ
            putU64Be(req, 1, userId.toULong())
            session.send(req)
            withTimeoutOrNull(GETKEY_TIMEOUT_MS) { deferred.await() }
        } catch (e: Exception) {
            Log.status(TAG, "wsGetKey failed for ${userId.toULong()}", e)
            null
        } finally {
            pendingKeyRequests.remove(userId)
        }
    }

    // ----------------------------------------------------------------
    // Publish (WebSocket only)
    // ----------------------------------------------------------------

    /**
     * Publish location to a connected user over the socket. Returns false if the peer has no
     * PQC bundle (outdated app — surface [PeerCrypto.NEEDS_UPDATE] via [peerCryptoStatus]) or
     * the socket is down (the next heartbeat retries once reconnected).
     */
    // Broad catch is deliberate: relay-socket boundary, same as [wsGetKey].
    @Suppress("TooGenericExceptionCaught")
    suspend fun publishLocation(location: LocationValue, user: User): Boolean {
        val bundle = peerPqcBundle(user)
        if (bundle == null) {
            Log.status(TAG, "publishLocation: no PQC bundle for ${user.id.toULong()} (${user.name}); peer must update")
            return false
        }
        return try {
            val pairId = outboundPairIdFor(user)
            val ok = sendLivePublish(user.id, "location", sealLocation(location, bundle, pairId))
            Log.dev(TAG, "publishLocation PQC to ${user.id.toULong()} (${user.name}) ok=$ok")
            ok
        } catch (e: Exception) {
            Log.status(TAG, "publishLocation to ${user.id.toULong()} exception", e)
            false
        }
    }

    /** Publish to an anonymous share link (post-quantum only). */
    // Broad catch is deliberate: relay-socket boundary, same as [wsGetKey].
    @Suppress("TooGenericExceptionCaught")
    suspend fun publishLocation(location: LocationValue, link: TemporaryLink): Boolean {
        return try {
            val bundle = Base64.decode(link.pqcPublicKey)
            // Anonymous link viewers prove nothing about identity and expect no auth:
            // pair ID stays null so nothing link-specific is pinned or leaked.
            val ok = sendLivePublish(link.id, "location", sealLocation(location, bundle, pairId = null))
            Log.dev(TAG, "publishLocation PQC to temp link ${link.id} ok=$ok")
            ok
        } catch (e: Exception) {
            Log.status(TAG, "publishLocation temp link ${link.id} failed", e)
            false
        }
    }

    /**
     * Publish to a peer identified only by id and cached public bundle. This is the
     * pre-unlock path: it takes the same wire format as the [User] overload but reads
     * nothing from Room, which is unreadable before the passcode is entered.
     *
     * [pairId] is the stored outbound secret for this peer, mirrored into
     * device-protected storage at seed time — the pre-unlock path cannot read Room.
     */
    // Broad catch is deliberate: relay-socket boundary, same as [wsGetKey].
    @Suppress("TooGenericExceptionCaught")
    suspend fun publishLocation(location: LocationValue, targetId: Long, bundleB64: String, pairId: String? = null): Boolean {
        return try {
            val ok = sendLivePublish(targetId, "location", sealLocation(location, Base64.decode(bundleB64), pairId))
            Log.dev(TAG, "publishLocation PQC to ${targetId.toULong()} (direct boot) ok=$ok")
            ok
        } catch (e: Exception) {
            Log.status(TAG, "publishLocation direct boot to ${targetId.toULong()} failed", e)
            false
        }
    }

    // ----------------------------------------------------------------
    // UWB session-setup channel — small handshake envelopes (request / ack /
    // config / cancel), end-to-end encrypted over the same socket. Ranging
    // samples never touch the server.
    // ----------------------------------------------------------------

    // Broad catch is deliberate: relay-socket boundary, same as [wsGetKey].
    @Suppress("TooGenericExceptionCaught")
    suspend fun publishUwbMessage(envelope: UwbEnvelope, recipientUserId: Long, recipient: User? = null): Boolean {
        val resolvedUser = recipient ?: repository.getUser(recipientUserId)
        val bundle = if (resolvedUser != null) {
            peerPqcBundle(resolvedUser)
        } else {
            wsGetKey(recipientUserId)?.takeIf { it.status == WS_KEY_PQC }?.bundle
        }
        if (bundle == null) {
            Log.status(TAG, "publishUwbMessage: no PQC bundle for ${recipientUserId.toULong()}; peer must update")
            return false
        }
        return try {
            val sealed = Pqc.encryptTo(bundle, json.encodeToString(envelope).encodeToByteArray())
            val ok = sendLivePublish(recipientUserId, "uwb", sealed)
            Log.dev(TAG, "publishUwbMessage PQC to ${recipientUserId.toULong()} ok=$ok")
            ok
        } catch (e: Exception) {
            Log.status(TAG, "publishUwbMessage to ${recipientUserId.toULong()} failed", e)
            false
        }
    }

    // Custom UWB tracker crowd-finding lives in NetworkingTracker.kt as
    // Networking extensions (registerTracker, resolveTrackerBundle,
    // uploadTrackerReport, fetchTrackerReports, registerPoweredOffEids).

    // ----------------------------------------------------------------
    // Encryption helpers (post-quantum only)
    // ----------------------------------------------------------------

    private fun sealLocation(location: LocationValue, bundle: ByteArray, pairId: String?): ByteArray {
        val str = json.encodeToString(location.toCompatible(senderPlatform = PLATFORM, pairId = pairId))
        // PQC hybrid: ML-KEM encapsulate → AES-256-GCM, layout [4B encapLen][encap][aes].
        return Pqc.encryptTo(bundle, str.encodeToByteArray())
    }

    /**
     * Our outbound pair-ID secret for [user] (GHSA-x94x-v6f7-wwpp): the hidden value every
     * packet to this peer carries inside the ciphertext.
     *
     * Created once and never rotated: the receiver pins whatever it first sees, so rotating
     * would look exactly like a forgery and get dropped. Uses the conditional
     * `setOutboundPairIdIfAbsent` write so two concurrent heartbeats cannot mint different
     * values — the loser re-reads the winner.
     */
    private suspend fun outboundPairIdFor(user: User): String {
        user.outboundPairId?.let { return it }
        val fresh = com.vayunmathur.findfamily.domain.PairAuth.generatePairId()
        runCatching { repository.setOutboundPairIdIfAbsent(user.id, fresh) }
        return runCatching { repository.getUser(user.id)?.outboundPairId }
            .getOrNull() ?: fresh
    }

    /**
     * Generates a fresh share-link key. The link is ML-KEM only and derived from a
     * 32-byte seed, which is the whole secret handed to the recipient in the URL
     * fragment — short enough to fit in an SMS. The private key is never stored:
     * the sender only ever encrypts, and the recipient re-derives it from the seed.
     */
    fun generatePqcLinkKey(): PqcLinkKey {
        val key = Pqc.generateLinkKey()
        return PqcLinkKey(
            seedB64Url = Base64.UrlSafe.encode(key.seed).trimEnd('='),
            publicBundleB64 = Base64.encode(key.publicBundle)
        )
    }

    data class PqcLinkKey(val seedB64Url: String, val publicBundleB64: String)

    /**
     * This device's invite fingerprint, as the unpadded base64url `k=` value of a share link.
     * Null when PQC is unavailable — the link then goes out without it and the recipient simply
     * has nothing to pre-verify against, exactly as before this parameter existed.
     */
    fun myInviteFingerprint(): String? {
        if (!pqcReady) return null
        return runCatching {
            Base64.UrlSafe.encode(Pqc.inviteFingerprint(userid, pqcIdentity.publicBundle)).trimEnd('=')
        }.onFailure { Log.status(TAG, "invite fingerprint failed", it) }.getOrNull()
    }

    /**
     * Generates a full PQC identity (ML-KEM + ML-DSA) for a device we own but that cannot
     * run keygen itself — currently only UWB trackers, whose private bundle we hold on
     * their behalf. Returns (publicBundleBase64, privateBundleBase64) where the private
     * bundle is [4B kemPrivLen][kemPrivDer][dsaPrivDer] — DERs, BC-compatible, same KDF
     * as Office. Share links use [generatePqcLinkKey] instead.
     */
    fun generatePqcIdentityKeyPair(): PqcIdentityKeyPair {
        val (kemPub, kemPriv) = Pqc.generateKem()
        val (dsaPub, dsaPriv) = Pqc.generateDsa()
        val pubBundle = Pqc.bundle(kemPub, dsaPub)
        val privBundle = buildPrivBundle(kemPriv, dsaPriv)
        return PqcIdentityKeyPair(
            publicBundleB64 = Base64.encode(pubBundle),
            privateBundleB64 = Base64.encode(privBundle)
        )
    }

    data class PqcIdentityKeyPair(val publicBundleB64: String, val privateBundleB64: String)

    /** Private bundle layout: [u32 kemPrivLen BE][kemPriv][dsaPriv] — mirrors public bundle. */
    private fun buildPrivBundle(kemPriv: ByteArray, dsaPriv: ByteArray): ByteArray {
        val out = ByteArray(U32_LEN + kemPriv.size + dsaPriv.size)
        out[0] = (kemPriv.size ushr U32_BYTE0_SHIFT).toByte()
        out[1] = (kemPriv.size ushr U32_BYTE1_SHIFT).toByte()
        out[2] = (kemPriv.size ushr BITS_PER_BYTE).toByte()
        out[U32_LEN - 1] = kemPriv.size.toByte()
        kemPriv.copyInto(out, U32_LEN)
        dsaPriv.copyInto(out, U32_LEN + kemPriv.size)
        return out
    }

    /**
     * Computes the quantum-safe verification "security code" for a connection: a fingerprint of
     * *both* this device's and [user]'s PQC bundles. Identical on both peers; comparing them
     * confirms the channel isn't intercepted. Null if PQC is unavailable or the peer has no bundle.
     */
    suspend fun securityCode(user: User): String? {
        if (!pqcReady) return null
        val theirBundle = peerPqcBundle(user) ?: return null
        return runCatching { Pqc.securityCode(pqcIdentity.publicBundle, theirBundle) }.getOrNull()
    }

    /** PQC safety number from a cached peer bundle (no lookup). */
    fun securityCodePqc(user: User): String? {
        val theirBundle = user.pqcEncryptionKey?.let { Base64.decode(it) } ?: return null
        if (!pqcReady) return null
        return runCatching { Pqc.securityCode(pqcIdentity.publicBundle, theirBundle) }.getOrNull()
    }

    /**
     * The peer's PQC public bundle — from cached `User.pqcEncryptionKey` or looked up over the
     * socket. If looked up, caches it. Non-null means the peer supports post-quantum sharing.
     */
    private suspend fun peerPqcBundle(user: User): ByteArray? {
        if (!pqcReady) return null
        user.pqcEncryptionKey?.let { return Base64.decode(it) }
        val res = wsGetKey(user.id) ?: return null
        if (res.status == WS_KEY_PQC && res.bundle != null) {
            runCatching { repository.setPqcEncryptionKey(user.id, Base64.encode(res.bundle)) }
            return res.bundle
        }
        return null
    }

    /**
     * Sign [data] with this device's ML-DSA identity key, or null if PQC is unavailable.
     *
     * Used to authenticate powered-off recovery grants. The peer channel encrypts to the
     * recipient but leaves the sender field self-declared, which is tolerable for a UWB handshake
     * and is not tolerable for handing over decryption keys.
     */
    fun signAsMe(data: ByteArray): ByteArray? {
        if (!pqcReady) return null
        return runCatching { pqcIdentity.sign(data) }
            .onFailure { Log.status(TAG, "signAsMe failed", it) }.getOrNull()
    }

    /** Whether [signature] over [data] was really produced by [userId]'s identity key. */
    suspend fun verifyFrom(userId: Long, data: ByteArray, signature: ByteArray): Boolean {
        val user = runCatching { repository.getUser(userId) }.getOrNull() ?: return false
        val bundle = peerPqcBundle(user) ?: return false
        return Pqc.verify(bundle, data, signature)
    }

    /** The post-quantum capability of a peer, used to gate connecting/sharing. */
    enum class PeerCrypto {
        /** Peer has a PQC bundle registered — sharing works. */
        PQC,

        /** Peer has only a classic (RSA) key — they are on an outdated app and must update. */
        NEEDS_UPDATE,

        /** Peer is not registered, or the lookup couldn't complete (socket down/timeout). */
        UNKNOWN,

        /** The relay's bundle doesn't match the fingerprint the invite link vouched for. */
        KEY_MISMATCH,
    }

    /**
     * Outcome of the connect-time key check. [verifiedBundleB64] is set only when an invite
     * fingerprint was supplied and matched, and is meant to go straight onto the [User] row being
     * created — pinning the key that was checked, so [peerPqcBundle] prefers it and a later lookup
     * can't swap in one the fingerprint never covered.
     */
    data class PeerKeyCheck(val status: PeerCrypto, val verifiedBundleB64: String? = null)

    /**
     * Checks a peer's post-quantum capability "when connecting", over the socket. PQC bundle →
     * [PeerCrypto.PQC]; only a classic key → [PeerCrypto.NEEDS_UPDATE] (show the update dialog);
     * neither, or the socket is down/times out → [PeerCrypto.UNKNOWN].
     *
     * [expectedFingerprint] is the `k=` value of the invite link this connection came from, when
     * there was one. The relay's bundle has to hash to it, or the result is
     * [PeerCrypto.KEY_MISMATCH] — this is what stops the relay handing out a key it controls
     * instead of the sender's.
     */
    suspend fun peerCryptoStatus(userId: Long, expectedFingerprint: String? = null): PeerKeyCheck {
        val res = wsGetKey(userId) ?: return PeerKeyCheck(PeerCrypto.UNKNOWN)
        return when (res.status) {
            WS_KEY_PQC -> {
                if (expectedFingerprint == null) return PeerKeyCheck(PeerCrypto.PQC)
                val bundle = res.bundle ?: return PeerKeyCheck(PeerCrypto.UNKNOWN)
                if (!fingerprintMatches(userId, bundle, expectedFingerprint)) {
                    Log.status(TAG, "invite fingerprint mismatch for ${userId.toULong()}")
                    return PeerKeyCheck(PeerCrypto.KEY_MISMATCH)
                }
                PeerKeyCheck(PeerCrypto.PQC, Base64.encode(bundle))
            }
            WS_KEY_CLASSIC -> PeerKeyCheck(PeerCrypto.NEEDS_UPDATE)
            else -> PeerKeyCheck(PeerCrypto.UNKNOWN)
        }
    }

    /**
     * Whether [bundle] is the one [expected] (unpadded base64url) names. A malformed or truncated
     * value fails rather than being skipped, so a mangled link is treated as unverified instead of
     * silently trusting whatever the relay returned.
     */
    private fun fingerprintMatches(userId: Long, bundle: ByteArray, expected: String): Boolean {
        val padded = if (expected.length % 4 == 0) expected else expected + "=".repeat(4 - expected.length % 4)
        val want = runCatching { Base64.UrlSafe.decode(padded) }.getOrNull() ?: return false
        return want.contentEquals(Pqc.inviteFingerprint(userId, bundle))
    }

}
