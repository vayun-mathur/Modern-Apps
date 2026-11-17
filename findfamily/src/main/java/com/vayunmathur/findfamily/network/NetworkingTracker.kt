package com.vayunmathur.findfamily.network

import com.vayunmathur.findfamily.domain.PoweredOffProtocol
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull

// Custom UWB tracker crowd-finding opcodes (DEV_BUILD). Mirrored on the server
// (src/handlers/findfamily.rs). Older servers ignore unknown opcodes, so these
// are backward-compatible: resolve/report-get simply time out to null/empty.
internal const val WS_OP_TRACKER_REGISTER: Byte = 0x06 // [0x06][u64 tracker_id][u16 secretLen][secret][bundle…]
internal const val WS_OP_RESOLVE_REQ: Byte = 0x07 //      [0x07][16B epochId]
internal const val WS_OP_RESOLVE_RESP: Byte = 0x08 //     [0x08][status][16B epochId][bundle…]
internal const val WS_OP_REPORT_PUT: Byte = 0x09 //       [0x09][16B epochId][ciphertext…]
internal const val WS_OP_REPORT_GET_REQ: Byte = 0x0A //   [0x0A][u16 n]([16B epochId]×n)
internal const val WS_OP_REPORT_GET_RESP: Byte = 0x0B //  [0x0B][u16 count]([u32 len][ct]×count)

// Powered-off finding. The server cannot derive these ids itself the way it derives tracker
// epoch-ids: the rotation period is fixed at 1024s by the Bluetooth HAL and the controller
// anchors its key schedule to shutdown time, which nothing else knows. So the device uploads
// the EIDs it armed with and the server just remembers them, after which the existing
// 0x07/0x09/0x0A path resolves and carries reports unchanged. Additive: a server that
// predates this ignores 0x0C, and the only symptom is that no sightings are ever resolved.
//
// The trailing recovery bundle is what finders seal to, in place of the owner's identity
// bundle — the change that makes a sighting readable by a family member rather than only by
// the dead phone. Additive in the other direction too: the server sizes the EID array from
// `count`, so a relay that predates the bundle never reads those trailing bytes.
internal const val WS_OP_POF_REGISTER: Byte = 0x0C // [0x0C][u64 userid][u16 count]([20B eid]×count)[u16 bundleLen][bundle]

internal fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
