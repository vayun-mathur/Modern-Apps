package com.vayunmathur.findfamily.util

import com.vayunmathur.library.log.Log
import com.vayunmathur.findfamily.data.LocationValue
import com.vayunmathur.findfamily.data.LocationValueCompatible
import com.vayunmathur.findfamily.uwb.UwbEnvelope
import com.vayunmathur.library.network.WebSocketClient
import com.vayunmathur.library.network.WsSession
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Live-socket frame layer, extracted from [Networking] so the object stays
 * under the function cap.
 *
 * Owns everything that happens *inside* one connected WebSocket session:
 * the SUBSCRIBE handshake, the reader/supervisor pair that detects half-open
 * death, and the per-opcode frame parsers. All shared relay state is read
 * through [Networking]'s internal members — this file adds no new state.
 */

/**
 * SUB doubles as registration: append our PQC bundle so the server stores
 * it (no separate register call). Sent every (re)connect, so it self-heals.
 */
internal suspend fun WsSession.sendSubscribe() {
    val bundle = if (Networking.pqcReady) Networking.pqcIdentity.publicBundle else ByteArray(0)
    val sub = ByteArray(Networking.SUB_FRAME_HEADER_LEN + bundle.size)
    sub[0] = Networking.WS_OP_SUB
    Networking.putU64Be(sub, 1, Networking.userid.toULong())
    bundle.copyInto(sub, Networking.BUNDLE_FIELD_OFFSET)
    send(sub)
    Log.dev(Networking.TAG, "live WS connected as ${Networking.userid.toULong()} bundleLen=${bundle.size}")
}

/**
 * Run one connected session: a detached reader decrypts and delivers
 * inbound frames while this supervisor pings and watches for half-open
 * death. Returns when the socket needs a reconnect.
 */
internal suspend fun WsSession.superviseConnection(
    connScope: CoroutineScope,
    outerScope: CoroutineScope,
    onLocations: suspend (List<InboundFix>) -> Unit,
    onUwb: suspend (List<UwbEnvelope>) -> Unit,
    onRawMsg: (suspend (ByteArray) -> Unit)? = null,
) {
    val lastInboundMs = AtomicLong(System.currentTimeMillis())
    // Reader: decrypts and delivers inbound frames, refreshing liveness on
    // each one. Runs in the detached connScope and is never joined, so if it
    // wedges on a half-open socket the supervisor below can still reconnect.
    // It ends on its own once abort() closes the socket and the read errors.
    connScope.launch {
        runCatching {
            incoming.collect { frame ->
                lastInboundMs.set(System.currentTimeMillis())
                when (frame) {
                    is WebSocketClient.WsFrame.Binary ->
                        dispatchLiveFrame(frame.bytes, onLocations, onUwb, onRawMsg)
                    else -> Unit
                }
            }
        }
    }
    // Supervisor: only ever suspends on delay(), so it can always make
    // progress to reconnect. Pings are fire-and-forget (a write blocked on a
    // half-open socket must not stall this loop). If no inbound frame — pong,
    // push, key response, anything — arrives within the timeout, the socket is
    // half-open, so return to abort and reconnect.
    try {
        while (outerScope.isActive) {
            connScope.launch { runCatching { ping() } }
            delay(Networking.PING_INTERVAL_MS)
            val idle = System.currentTimeMillis() - lastInboundMs.get()
            if (idle > Networking.LIVENESS_TIMEOUT_MS) {
                Log.status(Networking.TAG, "no inbound for ${idle}ms; socket half-open, reconnecting")
                break
            }
        }
    } finally {
        // Hard, non-blocking close, then abandon the reader (never joined).
        // abort() sets closed first, so webSocket()'s graceful close() is a
        // no-op and cannot hang the block's return.
        runCatching { abort() }
        connScope.cancel()
    }
}

/**
 * Parse one server frame and dispatch: MSG → decrypt+deliver; KEYRESP → complete the lookup.
 *
 * With [onRawMsg] set, MSG frames are handed over still encrypted instead of being decrypted
 * and delivered. The pre-unlock socket uses this to spool them (see
 * [com.vayunmathur.findfamily.data.InboundSpool]) because it has nowhere to store a location;
 * the unlocked path later replays each spooled frame through this function without it.
 */
internal suspend fun dispatchLiveFrame(
    buf: ByteArray,
    onLocations: suspend (List<InboundFix>) -> Unit,
    onUwb: suspend (List<UwbEnvelope>) -> Unit,
    onRawMsg: (suspend (ByteArray) -> Unit)? = null,
) {
    val op = buf.firstOrNull() ?: return
    when (op) {
        Networking.WS_OP_MSG -> if (onRawMsg != null) onRawMsg(buf) else handleLiveMsg(buf, onLocations, onUwb)
        Networking.WS_OP_GETKEY_RESP -> handleKeyResp(buf)
        WS_OP_RESOLVE_RESP -> handleResolveResp(buf)
        WS_OP_REPORT_GET_RESP -> handleReportGetResp(buf)
        else -> Unit
    }
}

/** MSG frame: [op][flags][ciphertext] — location or UWB envelope after PQC decrypt. */
private suspend fun handleLiveMsg(
    buf: ByteArray,
    onLocations: suspend (List<InboundFix>) -> Unit,
    onUwb: suspend (List<UwbEnvelope>) -> Unit,
) {
    if (buf.size < Networking.MSG_FRAME_HEADER_LEN) return
    val isUwb = (buf[Networking.MSG_FLAGS_OFFSET].toInt() and Networking.WS_FLAG_UWB) != 0
    val raw = buf.copyOfRange(Networking.MSG_PAYLOAD_OFFSET, buf.size)
    if (!isUwb) {
        val decoded = runCatching { decryptLocationPqcBytes(raw) }
            .onFailure { Log.status(Networking.TAG, "live location decrypt fail", it) }.getOrNull() ?: return
        val (loc, platform, pairId) = decoded
        if (platform != null) runCatching { Networking.repository.setPlatform(loc.userid, platform) }
        runCatching { onLocations(listOf(loc.withPairId(pairId))) }
    } else {
        val env = runCatching {
            val plain = Networking.pqcIdentity.decrypt(raw)
            Networking.json.decodeFromString<UwbEnvelope>(plain.decodeToString())
        }.onFailure { Log.status(Networking.TAG, "live uwb decrypt fail", it) }.getOrNull() ?: return
        runCatching { onUwb(listOf(env)) }
    }
}

/** KEYRESP frame: [op][status][u64 target][optional PQC bundle]. */
private fun handleKeyResp(buf: ByteArray) {
    if (buf.size < Networking.KEYRESP_HEADER_LEN) return
    val status = buf[Networking.KEYRESP_STATUS_OFFSET].toInt()
    val target = Networking.readU64Be(buf, Networking.KEYRESP_TARGET_OFFSET)
    val bundle = if (buf.size > Networking.KEYRESP_HEADER_LEN) {
        buf.copyOfRange(Networking.KEYRESP_HEADER_LEN, buf.size)
    } else {
        null
    }
    Networking.pendingKeyRequests.remove(target)?.complete(Networking.KeyResult(status, bundle))
}

/** RESOLVE_RESP frame: [op][status][16B epochId][bundle…]. */
private fun handleResolveResp(buf: ByteArray) {
    if (buf.size < Networking.RESOLVE_RESP_MIN_LEN) return
    val found = buf[Networking.RESOLVE_STATUS_OFFSET].toInt() == Networking.RESOLVE_FOUND_STATUS
    val epochHex = buf.copyOfRange(Networking.RESOLVE_EPOCH_OFFSET, Networking.RESOLVE_EPOCH_END).toHex()
    val bundle = if (found && buf.size > Networking.RESOLVE_RESP_MIN_LEN) {
        buf.copyOfRange(Networking.RESOLVE_RESP_MIN_LEN, buf.size)
    } else {
        null
    }
    Networking.pendingResolves.remove(epochHex)?.complete(bundle)
}

/** REPORT_GET_RESP frame: [op][u16 count]([u32 len][ct]×count). */
private fun handleReportGetResp(buf: ByteArray) {
    if (buf.size < Networking.REPORT_GET_RESP_HEADER_LEN) return
    val count = readU16Be(buf, Networking.REPORT_COUNT_OFFSET)
    val out = ArrayList<ByteArray>(count)
    var off = Networking.REPORT_GET_RESP_HEADER_LEN
    var i = 0
    while (i < count && off + Networking.U32_LEN <= buf.size) {
        val len = readU32Be(buf, off)
        off += Networking.U32_LEN
        if (len < 0 || off + len > buf.size) break
        out.add(buf.copyOfRange(off, off + len))
        off += len
        i++
    }
    Networking.pendingReportGets.poll()?.complete(out)
}

/**
 * A decrypted inbound fix plus its envelope metadata (never persisted as-is).
 *
 * [pairId] is the sender's claimed outbound secret (GHSA-x94x-v6f7-wwpp). It rides
 * beside the fix rather than inside [LocationValue] because Room must never store an
 * unauthenticated claim — the inbound pipeline checks it against the pinned
 * `User.inboundPairId` first and drops the fix on mismatch (see
 * `processIncomingLocations`).
 *
 * Public (not internal): [Networking.startLive] is public API, so its parameter type
 * must be visible at the call site in [LocationTrackingService].
 */
data class InboundFix(val location: LocationValue, val pairId: String?)

/** Attach the claimed pair ID to a freshly-decrypted fix for the inbound pipeline. */
internal fun LocationValue.withPairId(pairId: String?): InboundFix = InboundFix(this, pairId)

private fun decryptLocationPqcBytes(raw: ByteArray): Triple<LocationValue, String?, String?> {
    val plainBytes = Networking.pqcIdentity.decrypt(raw)
    val compat = Networking.json.decodeFromString<LocationValueCompatible>(plainBytes.decodeToString())
    return Triple(compat.toLocationValue(), compat.senderPlatform, compat.pairId)
}

/** Reads a big-endian u16 at [off]. */
private fun readU16Be(src: ByteArray, off: Int): Int =
    ((src[off].toInt() and Networking.INT_BYTE_MASK) shl Networking.BITS_PER_BYTE) or
        (src[off + 1].toInt() and Networking.INT_BYTE_MASK)

/** Reads a big-endian u32 at [off]. */
private fun readU32Be(src: ByteArray, off: Int): Int =
    ((src[off].toInt() and Networking.INT_BYTE_MASK) shl Networking.U32_BYTE0_SHIFT) or
        ((src[off + 1].toInt() and Networking.INT_BYTE_MASK) shl Networking.U32_BYTE1_SHIFT) or
        ((src[off + 2].toInt() and Networking.INT_BYTE_MASK) shl Networking.BITS_PER_BYTE) or
        (src[off + U32_LAST_BYTE_OFFSET].toInt() and Networking.INT_BYTE_MASK)
