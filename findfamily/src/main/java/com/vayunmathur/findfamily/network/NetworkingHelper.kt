package com.vayunmathur.findfamily.network

import com.vayunmathur.library.log.Log
import com.vayunmathur.findfamily.domain.PoweredOffProtocol
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull

/** Number of length-prefix bytes before the secret in a tracker-register frame. */
private const val TRACKER_REGISTER_HEADER_LEN = 11

/** Tracker epoch-id length carried in report frames. */
private const val TRACKER_EPOCH_ID_LEN = 16

/** Max report epoch-ids per fetch frame — the count field is a u16. */
private const val MAX_REPORT_EPOCH_IDS = 0xFFFF

/** Offset of the u16 secret-length field in a tracker-register frame (after op + u64 id). */
private const val SECRET_LEN_OFFSET = 9

/** Length of the secret-length field itself. */
private const val SECRET_LEN_FIELD_LEN = 2

/** Offset of the payload in single-epoch frames (after the 1-byte opcode). */
private const val SINGLE_EPOCH_PAYLOAD_OFFSET = 1

/** Offset of the u16 count field in a report-get frame (after the 1-byte opcode). */
private const val REPORT_COUNT_OFFSET = 1

/** Offset of the last byte of a u32 field. */
internal const val U32_LAST_BYTE_OFFSET = 3

/** High byte of a u16 field (shift to reach it). */
private const val U16_HIGH_BYTE_SHIFT = 8

/** Offset where epoch-ids start in a report-get frame (after op + u16 count). */
private const val REPORT_EPOCHS_OFFSET = 3

// Broad catches below are deliberate: every function here crosses the relay
// WebSocket boundary, whose failures surface as undocumented runtime
// exceptions; each is logged and mapped to a failure return, never thrown,
// so one failing relay call cannot crash the tracking service loop.

/** Owner: register/refresh a tracker's secret + ML-KEM public bundle on the server. */
@Suppress("TooGenericExceptionCaught")
suspend fun Networking.registerTracker(trackerUserId: Long, secret: ByteArray, publicBundle: ByteArray): Boolean {
    val session = wsSession ?: return false
    return try {
        val frame = ByteArray(TRACKER_REGISTER_HEADER_LEN + secret.size + publicBundle.size)
        frame[0] = WS_OP_TRACKER_REGISTER
        putU64Be(frame, 1, trackerUserId.toULong())
        frame[SECRET_LEN_OFFSET] = (secret.size ushr U16_HIGH_BYTE_SHIFT).toByte()
        frame[SECRET_LEN_OFFSET + 1] = secret.size.toByte()
        secret.copyInto(frame, TRACKER_REGISTER_HEADER_LEN)
        publicBundle.copyInto(frame, TRACKER_REGISTER_HEADER_LEN + secret.size)
        session.send(frame)
        true
    } catch (e: Exception) {
        Log.status("FF-Networking", "registerTracker failed", e); false
    }
}
/** Finder: resolve a beacon epoch-id to the owning tracker's ML-KEM public bundle. */
@Suppress("TooGenericExceptionCaught")
suspend fun Networking.resolveTrackerBundle(epochId: ByteArray): ByteArray? {
    val session = wsSession ?: return null
    val hex = epochId.toHex()
    val deferred = CompletableDeferred<ByteArray?>()
    pendingResolves[hex] = deferred
    return try {
        val req = ByteArray(SINGLE_EPOCH_PAYLOAD_OFFSET + epochId.size)
        req[0] = WS_OP_RESOLVE_REQ
        epochId.copyInto(req, SINGLE_EPOCH_PAYLOAD_OFFSET)
        session.send(req)
        withTimeoutOrNull(GETKEY_TIMEOUT_MS) { deferred.await() }
    } catch (e: Exception) {
        Log.status("FF-Networking", "resolveTrackerBundle failed", e); null
    } finally {
        pendingResolves.remove(hex)
    }
}
/** Finder: upload a sealed crowd report keyed by the beacon epoch-id. */
@Suppress("TooGenericExceptionCaught")
suspend fun Networking.uploadTrackerReport(epochId: ByteArray, ciphertext: ByteArray): Boolean {
    val session = wsSession ?: return false
    return try {
        val frame = ByteArray(SINGLE_EPOCH_PAYLOAD_OFFSET + epochId.size + ciphertext.size)
        epochId.copyInto(frame, SINGLE_EPOCH_PAYLOAD_OFFSET)
        frame[0] = WS_OP_REPORT_PUT
        ciphertext.copyInto(frame, SINGLE_EPOCH_PAYLOAD_OFFSET + epochId.size)
        session.send(frame)
        true
    } catch (e: Exception) {
        Log.status("FF-Networking", "uploadTrackerReport failed", e); false
    }
}
/** Owner: fetch (and drain) sealed reports for a batch of recent epoch-ids. */
@Suppress("TooGenericExceptionCaught")
suspend fun Networking.fetchTrackerReports(epochIds: List<ByteArray>): List<ByteArray> {
    val session = wsSession ?: return emptyList()
    if (epochIds.isEmpty()) return emptyList()
    val deferred = CompletableDeferred<List<ByteArray>>()
    pendingReportGets.add(deferred)
    return try {
        val n = epochIds.size.coerceAtMost(MAX_REPORT_EPOCH_IDS)
        val frame = ByteArray(REPORT_EPOCHS_OFFSET + n * TRACKER_EPOCH_ID_LEN)
        frame[0] = WS_OP_REPORT_GET_REQ
        frame[REPORT_COUNT_OFFSET] = (n ushr U16_HIGH_BYTE_SHIFT).toByte()
        frame[REPORT_COUNT_OFFSET + 1] = n.toByte()
        var off = REPORT_EPOCHS_OFFSET
        for (i in 0 until n) {
            epochIds[i].copyInto(frame, off, 0, TRACKER_EPOCH_ID_LEN.coerceAtMost(epochIds[i].size))
            off += TRACKER_EPOCH_ID_LEN
        }
        session.send(frame)
        withTimeoutOrNull(GETKEY_TIMEOUT_MS) { deferred.await() } ?: emptyList()
    } catch (e: Exception) {
        Log.status("FF-Networking", "fetchTrackerReports failed", e); emptyList()
    } finally {
        pendingReportGets.remove(deferred)
    }
}
/**
 * Beacon: register the EIDs this device armed its Bluetooth controller with before shutting
 * down, together with the public half of the **recovery** keypair finders should seal to.
 *
 * Must be sent *before* the device powers off - once it is off it cannot re-register, and
 * unlike a live tracker it will not self-heal on the next heartbeat. Registrations are held
 * in memory server-side, so a relay restart during the powered-off window silently ends
 * findability until the device boots again.
 */
@Suppress("TooGenericExceptionCaught")
suspend fun Networking.registerPoweredOffEids(
    userId: Long,
    eids: List<ByteArray>,
    recoveryBundle: ByteArray,
): Boolean {
    val session = wsSession ?: return false
    if (eids.isEmpty()) return false
    val n = eids.size.coerceAtMost(PoweredOffProtocol.ARMED_SLOTS)
    return try {
        // u16 count, not u8: the controller addresses 256 keys (index 0..255) and 256 does
        // not fit in a byte. Getting this wrong would silently drop the last EID, costing the
        // final 17 minutes of the window.
        val frame = ByteArray(
            TRACKER_REGISTER_HEADER_LEN + n * PoweredOffProtocol.EID_LEN + 2 + recoveryBundle.size
        )
        frame[0] = WS_OP_POF_REGISTER
        putU64Be(frame, 1, userId.toULong())
        frame[SECRET_LEN_OFFSET] = (n ushr U16_HIGH_BYTE_SHIFT).toByte()
        frame[SECRET_LEN_OFFSET + 1] = n.toByte()
        var off = TRACKER_REGISTER_HEADER_LEN
        for (i in 0 until n) {
            eids[i].copyInto(frame, off, 0, PoweredOffProtocol.EID_LEN)
            off += PoweredOffProtocol.EID_LEN
        }
        frame[off] = (recoveryBundle.size ushr U16_HIGH_BYTE_SHIFT).toByte()
        frame[off + 1] = recoveryBundle.size.toByte()
        recoveryBundle.copyInto(frame, off + 2)
        session.send(frame)
        true
    } catch (e: Exception) {
        Log.status("FF-Networking", "registerPoweredOffEids failed", e); false
    }
}
