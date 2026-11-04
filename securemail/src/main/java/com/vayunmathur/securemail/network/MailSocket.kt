package com.vayunmathur.securemail.network

import com.vayunmathur.library.log.Log
import com.vayunmathur.library.network.NetworkClient
import com.vayunmathur.library.network.WebSocketClient
import com.vayunmathur.library.network.WsSession
import com.vayunmathur.library.network.webSocket
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Live-socket mail relay (protocol v2), mirroring FindFamily's binary wire design:
 *
 * ```
 * server→client CHALLENGE : [0x08][32B nonce]
 * client→server SUB       : [0x01][u16 addrLen][address][u16 sigLen][detached sig][armored public key…]
 * server→client SUBRESP   : [0x09][status][u16 addrLen][address]
 * client→server PUB       : [0x02][flags][u16 fromLen][from][u16 rcptLen][recipient][raw PGP/MIME…]
 * client→server GETKEY    : [0x04][u16 addrLen][address UTF-8]
 * client→server FETCH     : [0x06][u16 idLen][message id UTF-8]
 * server→client MSG       : [0x03][flags][u32 idLen][id][u16 rcptLen][delivered-to][raw PGP/MIME…]
 * server→client KEYRESP   : [0x05][status][u16 addrLen][address UTF-8][armored public key…]
 * server→client FETCHED   : [0x07][u16 idLen][message id UTF-8][raw PGP/MIME…]
 * ```
 *
 * flags bit0: 0 = encrypted PGP/MIME, 1 = unencrypted fallback (explicit opt-in only).
 * flags bit1: 0 = ham, 1 = spam (SPF/DKIM/DMARC failed at receipt; independent
 *   of PGP signing — unsigned mail is accepted, not spam).
 * KEYRESP status: 0 = none, 1 = found (key follows).
 * SUBRESP status: see [SUB_OK]…[SUB_INVALID].
 *
 * SUB authenticates one own address (primary or alias) by signing
 * `"maemail-sub-v1\0" || nonce || address` with that address's key; the first
 * SUB pins the key server-side. One socket SUBs every active identity, and the
 * server only accepts PUB from addresses this socket authenticated.
 */
object MailSocket {

    const val WS_URL = "wss://mail.maemail.org/mail/ws"

    const val OP_SUB: Byte = 0x01
    const val OP_PUB: Byte = 0x02
    const val OP_MSG: Byte = 0x03
    const val OP_GETKEY_REQ: Byte = 0x04
    const val OP_GETKEY_RESP: Byte = 0x05
    const val OP_FETCH_REQ: Byte = 0x06
    const val OP_FETCH_RESP: Byte = 0x07
    const val OP_CHALLENGE: Byte = 0x08
    const val OP_SUBRESP: Byte = 0x09

    const val FLAG_PLAIN: Byte = 0x01
    /** Spam flag: SPF/DKIM/DMARC verification failed at receipt (routes to Spam). */
    const val FLAG_SPAM: Byte = 0x02

    const val SUB_OK = 0
    const val SUB_TAKEN = 1
    const val SUB_BAD_SIG = 2
    const val SUB_RATE_LIMITED = 3
    const val SUB_INVALID = 4

    private val SUB_CONTEXT = "maemail-sub-v1\u0000".toByteArray(Charsets.UTF_8)
    const val NONCE_LEN = 32

    /** Decoded MSG frame flags (bit0 = plaintext shape, bit1 = server spam verdict). */
    data class MsgFlags(val unencrypted: Boolean, val spam: Boolean)

    /** One decoded MSG frame. [deliveredTo] is the own address it was routed to. */
    class InboundMsg(val id: String, val flags: MsgFlags, val deliveredTo: String, val raw: ByteArray)

    const val KEY_NONE = 0
    const val KEY_FOUND = 1

    const val GETKEY_TIMEOUT_MS = 8_000L
    const val FETCH_TIMEOUT_MS = 15_000L
    const val SUB_TIMEOUT_MS = 10_000L
    const val CHALLENGE_TIMEOUT_MS = 10_000L
    const val PING_INTERVAL_MS = 30_000L
    const val LIVENESS_TIMEOUT_MS = 75_000L

    private const val BACKOFF_INITIAL_MS = 1_000L
    private const val BACKOFF_MAX_MS = 30_000L

    private const val TAG = "MailSocket"

    val json: Json = Json { ignoreUnknownKeys = true }

    /** In-flight key lookups keyed by lowercase address, completed on KEYRESP. */
    internal val pendingKeys = ConcurrentHashMap<String, CompletableDeferred<KeyResult?>>()

    /** In-flight fetches keyed by message id, completed on FETCHED. */
    internal val pendingFetches = ConcurrentHashMap<String, CompletableDeferred<ByteArray?>>()

    /** In-flight [claim]s keyed by lowercase address, completed on SUBRESP. */
    internal val pendingSubs = ConcurrentHashMap<String, CompletableDeferred<Int>>()

    /** This session's CHALLENGE nonce (null until received). */
    @Volatile private var nonce: ByteArray? = null

    @Volatile internal var session: WsSession? = null
        private set(value) {
            field = value
            _connected.value = value != null
        }
    private var loop: Job? = null

    private val _connected = kotlinx.coroutines.flow.MutableStateFlow(false)
    /** Live socket state for the UI to collect (replaces polling `connected`). */
    val connectedFlow: kotlinx.coroutines.flow.StateFlow<Boolean> = _connected

    val connected: Boolean get() = session != null

    data class KeyResult(val address: String, val armoredPublic: String?)

    data class IdentitySnapshot(val address: String, val armoredPublic: String)

    /**
     * Start (no-op if running) the persistent relay loop. On every (re)connect
     * each of [identities] is SUBbed with a challenge signature from [sign], so
     * registration self-heals; the socket stays up with zero identities so
     * onboarding can [claim]. [onMessage] receives each inbound MSG frame;
     * [onBump] fires on any inbound frame to refresh UI/sync state.
     */
    @Synchronized
    fun start(
        scope: CoroutineScope,
        identities: suspend () -> List<IdentitySnapshot>,
        sign: suspend (address: String, payload: ByteArray) -> ByteArray?,
        onMessage: suspend (InboundMsg) -> Unit,
        onBump: () -> Unit = {},
    ) {
        if (loop?.isActive == true) return
        stop()
        loop = scope.launch(Dispatchers.IO) {
            val outer = this
            var backoff = BACKOFF_INITIAL_MS
            while (isActive) {
                val conn = CoroutineScope(Dispatchers.IO + Job())
                runCatching {
                    // mail.maemail.org is a pinned first-party host → platform trust is
                    // wrong here; the app inits NetworkClient with FIRST_PARTY.
                    webSocket(WS_URL) {
                        val ws = this
                        supervise(conn, outer, onMessage, onBump) { challenge ->
                            nonce = challenge
                            for (id in identities()) {
                                val sig = sign(id.address, subPayload(challenge, id.address))
                                if (sig == null) {
                                    Log.status(TAG, "cannot sign SUB for ${id.address}")
                                    continue
                                }
                                send(subFrame(id.address, sig, id.armoredPublic))
                            }
                            // Publish only after the SUBs are on the wire: the server
                            // processes frames in order, so any PUB lands authenticated.
                            session = ws
                            backoff = BACKOFF_INITIAL_MS
                        }
                    }
                }.onFailure { Log.status(TAG, "relay loop error", it) }
                conn.cancel()
                clearSession()
                if (!isActive) break
                delay(backoff)
                backoff = (backoff * 2).coerceAtMost(BACKOFF_MAX_MS)
            }
        }
    }

    @Synchronized
    fun stop() {
        loop?.cancel(); loop = null
        clearSession()
    }

    private fun clearSession() {
        session = null
        nonce = null
        pendingKeys.values.forEach { it.complete(null) }
        pendingKeys.clear()
        pendingFetches.values.forEach { it.complete(null) }
        pendingFetches.clear()
        pendingSubs.values.forEach { it.cancel() }
        pendingSubs.clear()
    }

    /**
     * Claims (or re-authenticates) [address] on the live session: signs this
     * session's challenge via [sign] and waits for SUBRESP. Returns the
     * [SUB_OK]…[SUB_INVALID] status, or null when offline / timed out.
     */
    suspend fun claim(address: String, armoredPublic: String, sign: suspend (ByteArray) -> ByteArray): Int? {
        val s = session ?: return null
        val n = nonce ?: return null
        val key = address.lowercase()
        val deferred = CompletableDeferred<Int>()
        pendingSubs[key] = deferred
        return try {
            s.send(subFrame(key, sign(subPayload(n, key)), armoredPublic))
            withTimeoutOrNull(SUB_TIMEOUT_MS) { deferred.await() }
        } catch (e: Exception) {
            Log.status(TAG, "claim failed for $address", e)
            null
        } finally {
            pendingSubs.remove(key)
        }
    }

    /** Publish an already-built PGP/MIME blob [from] an own address to [recipient]. False when offline. */
    suspend fun publish(from: String, recipient: String, raw: ByteArray, unencrypted: Boolean = false): Boolean {
        val s = session
        if (s == null) {
            lastPublishError = "no session (relay offline)"
            return false
        }
        return runCatching {
            s.send(pubFrame(from, recipient, raw, unencrypted))
            lastPublishError = null
            true
        }.onFailure {
            lastPublishError = it.message?.takeIf { m -> m.isNotBlank() }
                ?: it.javaClass.simpleName ?: "socket write failed"
            Log.status(TAG, "publish failed", it)
        }.getOrDefault(false)
    }

    /**
     * Cause of the most recent [publish] failure (null after a success).
     * Read immediately after a `false` return — the next publish overwrites it.
     * Surfaced to the outbox `lastError` so retry shows the real cause instead
     * of one generic string.
     */
    @Volatile
    var lastPublishError: String? = null
        private set

    /** Directory lookup over the socket (request/response with timeout). */
    suspend fun getKey(address: String): KeyResult? {
        val s = session ?: return null
        val key = address.lowercase()
        val deferred = CompletableDeferred<KeyResult?>()
        pendingKeys[key] = deferred
        return try {
            s.send(getKeyFrame(address))
            withTimeoutOrNull(GETKEY_TIMEOUT_MS) { deferred.await() }
        } catch (e: Exception) {
            Log.status(TAG, "getKey failed for $address", e)
            null
        } finally {
            pendingKeys.remove(key)
        }
    }

    /** Fetch one backlog message's blob by id (for missed fan-outs / fresh devices). */
    suspend fun fetch(id: String): ByteArray? {
        val s = session ?: return null
        val deferred = CompletableDeferred<ByteArray?>()
        pendingFetches[id] = deferred
        return try {
            s.send(fetchFrame(id))
            withTimeoutOrNull(FETCH_TIMEOUT_MS) { deferred.await() }
        } catch (e: Exception) {
            Log.status(TAG, "fetch failed for $id", e)
            null
        } finally {
            pendingFetches.remove(id)
        }
    }

    // ------------------------------------------------------------------
    // Session internals
    // ------------------------------------------------------------------

    private suspend fun WsSession.supervise(
        conn: CoroutineScope,
        outer: CoroutineScope,
        onMessage: suspend (InboundMsg) -> Unit,
        onBump: () -> Unit,
        onChallenge: suspend WsSession.(ByteArray) -> Unit,
    ) {
        var lastInbound = System.currentTimeMillis()
        val challenge = CompletableDeferred<ByteArray>()
        conn.launch {
            runCatching {
                incoming.collect { frame ->
                    lastInbound = System.currentTimeMillis()
                    if (frame is WebSocketClient.WsFrame.Binary) {
                        val bytes = frame.bytes
                        if (bytes.firstOrNull() == OP_CHALLENGE && bytes.size == 1 + NONCE_LEN) {
                            challenge.complete(bytes.copyOfRange(1, bytes.size))
                        } else {
                            dispatch(bytes, onMessage)
                        }
                    }
                    runCatching { onBump() }
                }
            }
        }
        try {
            val n = withTimeoutOrNull(CHALLENGE_TIMEOUT_MS) { challenge.await() }
            if (n == null) {
                Log.status(TAG, "no CHALLENGE from relay, reconnecting")
                return
            }
            onChallenge(n)
            while (outer.isActive) {
                conn.launch { runCatching { ping() } }
                delay(PING_INTERVAL_MS)
                if (System.currentTimeMillis() - lastInbound > LIVENESS_TIMEOUT_MS) {
                    Log.status(TAG, "socket half-open, reconnecting")
                    break
                }
            }
        } finally {
            runCatching { abort() }
            conn.cancel()
        }
    }

    internal suspend fun dispatch(buf: ByteArray, onMessage: suspend (InboundMsg) -> Unit) {
        when (buf.firstOrNull()) {
            OP_MSG -> parseMsg(buf)?.let { onMessage(it) }
            OP_SUBRESP -> parseSubResp(buf)?.let { (addr, status) ->
                val waiter = pendingSubs.remove(addr.lowercase())
                if (waiter != null) {
                    waiter.complete(status)
                } else if (status != SUB_OK) {
                    Log.status(TAG, "relay rejected SUB for $addr (status $status)")
                }
            }
            OP_GETKEY_RESP -> parseKeyResp(buf)?.let { (addr, key) ->
                pendingKeys.remove(addr.lowercase())?.complete(KeyResult(addr, key))
            }
            OP_FETCH_RESP -> parseFetched(buf)?.let { (id, raw) ->
                pendingFetches.remove(id)?.complete(raw)
            }
            else -> Unit
        }
    }

    // ------------------------------------------------------------------
    // Framing
    // ------------------------------------------------------------------

    /** The exact bytes a SUB signature covers (must match the server's `sub_payload`). */
    internal fun subPayload(nonce: ByteArray, address: String): ByteArray =
        SUB_CONTEXT + nonce + address.lowercase().toByteArray(Charsets.UTF_8)

    internal fun subFrame(address: String, signature: ByteArray, armoredPublic: String): ByteArray {
        val addr = address.lowercase().toByteArray(Charsets.UTF_8)
        val key = armoredPublic.toByteArray(Charsets.UTF_8)
        val out = ByteArray(1 + 2 + addr.size + 2 + signature.size + key.size)
        out[0] = OP_SUB
        putU16(out, 1, addr.size)
        addr.copyInto(out, 3)
        putU16(out, 3 + addr.size, signature.size)
        signature.copyInto(out, 5 + addr.size)
        key.copyInto(out, 5 + addr.size + signature.size)
        return out
    }

    internal fun pubFrame(from: String, recipient: String, raw: ByteArray, unencrypted: Boolean): ByteArray {
        val sender = from.lowercase().toByteArray(Charsets.UTF_8)
        val rcpt = recipient.lowercase().toByteArray(Charsets.UTF_8)
        val out = ByteArray(1 + 1 + 2 + sender.size + 2 + rcpt.size + raw.size)
        out[0] = OP_PUB
        out[1] = if (unencrypted) FLAG_PLAIN else 0
        putU16(out, 2, sender.size)
        sender.copyInto(out, 4)
        putU16(out, 4 + sender.size, rcpt.size)
        rcpt.copyInto(out, 6 + sender.size)
        raw.copyInto(out, 6 + sender.size + rcpt.size)
        return out
    }

    internal fun getKeyFrame(address: String): ByteArray {
        val addr = address.lowercase().toByteArray(Charsets.UTF_8)
        val out = ByteArray(1 + 2 + addr.size)
        out[0] = OP_GETKEY_REQ
        putU16(out, 1, addr.size)
        addr.copyInto(out, 3)
        return out
    }

    internal fun fetchFrame(id: String): ByteArray {
        val raw = id.toByteArray(Charsets.UTF_8)
        val out = ByteArray(1 + 2 + raw.size)
        out[0] = OP_FETCH_REQ
        putU16(out, 1, raw.size)
        raw.copyInto(out, 3)
        return out
    }

    /** MSG: [0x03][flags][u32 idLen][id][u16 rcptLen][rcpt][raw…]. */
    internal fun parseMsg(buf: ByteArray): InboundMsg? {
        if (buf.size < 1 + 1 + 4 + 2) return null
        val flags = buf[1].toInt() and 0xFF
        val parsed = MsgFlags(
            unencrypted = (flags and FLAG_PLAIN.toInt()) != 0,
            spam = (flags and FLAG_SPAM.toInt()) != 0,
        )
        val idLen = readU32(buf, 2)
        if (idLen < 0 || buf.size < 6 + idLen + 2) return null
        val id = buf.copyOfRange(6, 6 + idLen).toString(Charsets.UTF_8)
        val rcptOff = 6 + idLen
        val rcptLen = readU16(buf, rcptOff)
        if (buf.size < rcptOff + 2 + rcptLen) return null
        val rcpt = buf.copyOfRange(rcptOff + 2, rcptOff + 2 + rcptLen).toString(Charsets.UTF_8)
        val raw = buf.copyOfRange(rcptOff + 2 + rcptLen, buf.size)
        return InboundMsg(id, parsed, rcpt, raw)
    }

    /** SUBRESP: [0x09][status][u16 addrLen][address] → (address, status). */
    internal fun parseSubResp(buf: ByteArray): Pair<String, Int>? {
        if (buf.size < 1 + 1 + 2) return null
        val status = buf[1].toInt() and 0xFF
        val addrLen = readU16(buf, 2)
        if (buf.size < 4 + addrLen) return null
        return buf.copyOfRange(4, 4 + addrLen).toString(Charsets.UTF_8) to status
    }

    /** KEYRESP: [0x05][status][u16 addrLen][address][key…] → (address, key?). */
    internal fun parseKeyResp(buf: ByteArray): Pair<String, String?>? {
        if (buf.size < 1 + 1 + 2) return null
        val status = buf[1].toInt()
        val addrLen = readU16(buf, 2)
        if (buf.size < 4 + addrLen) return null
        val addr = buf.copyOfRange(4, 4 + addrLen).toString(Charsets.UTF_8)
        val key = if (status == KEY_FOUND && buf.size > 4 + addrLen) {
            buf.copyOfRange(4 + addrLen, buf.size).toString(Charsets.UTF_8)
        } else {
            null
        }
        return addr to key
    }

    /** FETCHED: [0x07][u16 idLen][id][raw…] → (id, raw). */
    internal fun parseFetched(buf: ByteArray): Pair<String, ByteArray>? {
        if (buf.size < 1 + 2) return null
        val idLen = readU16(buf, 1)
        if (buf.size < 3 + idLen) return null
        val id = buf.copyOfRange(3, 3 + idLen).toString(Charsets.UTF_8)
        return id to buf.copyOfRange(3 + idLen, buf.size)
    }

    internal fun putU16(dst: ByteArray, off: Int, v: Int) {
        dst[off] = (v shr 8).toByte()
        dst[off + 1] = v.toByte()
    }

    internal fun readU16(src: ByteArray, off: Int): Int =
        ((src[off].toInt() and 0xFF) shl 8) or (src[off + 1].toInt() and 0xFF)

    internal fun readU32(src: ByteArray, off: Int): Int =
        ((src[off].toInt() and 0xFF) shl 24) or
            ((src[off + 1].toInt() and 0xFF) shl 16) or
            ((src[off + 2].toInt() and 0xFF) shl 8) or
            (src[off + 3].toInt() and 0xFF)
}

/** Minimal REST surface: WKD runs over plain HTTPS, not the relay socket. */
object KeyLookupHttp {
    suspend fun fetchText(url: String, useSystemTrust: Boolean): String? {
        return try {
            val r = NetworkClient.performRequest(
                url = url,
                useSystemTrust = useSystemTrust,
            )
            if (r.isSuccess) r.body else null
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Fetches a WKD key record (binary OpenPGP key, possibly armored) and
     * returns it as canonical armor text via the native normalizer. WKD
     * responses are the raw key bytes, not JSON.
     *
     * Bounded at 10s connect / 15s read: most domains have no WKD at all and
     * a hanging lookup must not stall the send path (which falls through to
     * the explicit unencrypted opt-in).
     */
    suspend fun fetchWkdKey(url: String): String? {
        return try {
            val r = NetworkClient.execute(
                url = url,
                useSystemTrust = true,
                connectTimeoutMs = 10_000L,
                readTimeoutMs = 15_000L,
            )
            if (!r.isSuccess || r.bytes.isEmpty()) return null
            runCatching { com.vayunmathur.e2ee.Pgp.normalizeKey(r.bytes) }.getOrNull()
        } catch (_: Exception) {
            null
        }
    }
}

/** Federation DTOs shared with the server's /mail/v1 HTTP surface (Phase 2 SMTP). */
@Serializable
data class FederatedSendRequest(
    val from: String,
    val toExternal: List<String>,
    /** Armored PGP/MIME for key-known externals; null entries send clear. */
    val armoredBodies: List<String?>,
    val subject: String,
)
