// PACKAGE STRUCTURE EXCEPTION (JNI): FQN frozen for native RegisterNatives/symbol mangling
package com.vayunmathur.share.protocol

import com.vayunmathur.library.log.NativeLoader

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** The recovered fields of a Nearby Sharing endpoint-info blob. */
@Serializable
data class EndpointInfoFields(
    /** The peer's display name, absent when it advertises in contact-only mode. */
    val deviceName: String? = null,
    /** `p000\eanu.java` ordinal, driving which icon to render. */
    val deviceType: Int = 0,
    val version: Int = 0,
    val vendorId: Int = 0,
)

/** The recovered fields of a Nearby Connections `WifiLanServiceInfo` instance name. */
@Serializable
data class WifiLanServiceInfoFields(
    val endpointId: String,
    val pcp: Int,
)

/**
 * JNI surface for the native Quick Share protocol crate (libshare_nearby.so) —
 * session half: create/feed/drain/query/destroy plus file staging.
 *
 * Discovery and endpoint-info codecs live in [ShareNativeDiscovery]. The split is
 * purely organizational: every `nativeX` here still binds to
 * `Java_com_vayunmathur_share_protocol_ShareNative_nativeX` in
 * `share/src/main/rust/src/lib.rs`, so the Rust side is untouched.
 *
 * Kotlin owns transport (NSD/BLE discovery + TCP sockets and file I/O); Rust owns the
 * pure state machine: the Nearby Connections connection handshake, UKEY2 with the
 * `AES_256_CBC-HMAC_SHA256` record protocol, the paired-key exchange, `OfflineFrame`
 * and Sharing `Frame` encode/decode, and payload chunking. No networking touches Rust.
 *
 * Threading: all calls are synchronous and must be serialized per session handle.
 * Call from a single coroutine / background thread per session.
 *
 * Lifecycle (see share/PROTOCOL_CONTRACT.md for the full spec):
 * ```
 * handle = nativeInit(localName, localEndpointInfo, localEndpointId, isInitiator)
 * repeat { if ((bytes = socket.read()) != null) nativeFeedInbound(handle, bytes) }
 * while ((rec = nativeDrainReceived(handle)) != null) appendToFile(rec)
 * while ((out = nativeDrainOutbound(handle)) != null) socket.write(out)
 * state = nativeQueryState(handle); files = nativeQueryPendingFiles(handle)
 * nativeAccept(handle, accept, destDir)     // after the user taps Accept/Reject
 * nativeDestroy(handle)                     // always, e.g. in finally / onCleared
 * ```
 */
internal object ShareNative {
    /**
     * Create a session. [isInitiator] must be `true` for the side that dialled the TCP
     * socket and `false` for the side that accepted it — only the initiator sends
     * `CONNECTION_REQUEST` and only the initiator is the UKEY2 client.
     *
     * [localEndpointId] must be the id this device advertises, so the peer sees the same
     * identity in `CONNECTION_REQUEST` that it discovered over mDNS. Empty falls back to
     * a fresh random id.
     */
    external fun nativeInit(
        localName: String,
        localEndpointInfo: ByteArray,
        localEndpointId: String,
        isInitiator: Boolean,
    ): Long

    external fun nativeFeedInbound(handle: Long, bytes: ByteArray): Int

    external fun nativeDrainOutbound(handle: Long): ByteArray?

    external fun nativeQueryState(handle: Long): Int

    /** JSON utf8 `[{"name","sizeBytes","mimeType"}]`, or null for a bad handle. */
    external fun nativeQueryPendingFiles(handle: Long): ByteArray?

    external fun nativeAccept(handle: Long, accept: Boolean, destDir: String): Int

    /** Stage the files to announce. [json] uses the `nativeQueryPendingFiles` shape. */
    external fun nativeSetFilesToSend(handle: Long, json: ByteArray): Int

    /**
     * Announce the staged files. Safe to call before the paired-key exchange finishes:
     * the frame is held and emitted once the session is ready.
     */
    external fun nativeQueueIntroduction(handle: Long): Int

    /** Emit a `KEEP_ALIVE` so a long transfer does not look idle. */
    external fun nativeSendKeepAlive(handle: Long): Int

    external fun nativeOpenFile(handle: Long, fileName: String, fileSize: Long): Int

    external fun nativeWriteChunk(handle: Long, chunk: ByteArray): Int

    external fun nativeCloseFile(handle: Long): Int

    /**
     * One received FILE chunk in the `PROTOCOL_CONTRACT.md` §6 record layout, or null
     * when nothing is pending. Call in a loop after every [nativeFeedInbound]: each
     * call hands over one chunk and drops it, so a large file costs constant memory.
     */
    external fun nativeDrainReceived(handle: Long): ByteArray?

    /** Why the session failed, or null while it is healthy. */
    external fun nativeQueryFailureReason(handle: Long): String?

    /**
     * The peer's advertised device name, or null before its `CONNECTION_REQUEST` has been
     * read. Prefers the human-readable name inside `endpoint_info` over `endpoint_name`.
     */
    external fun nativeQueryPeerName(handle: Long): String?

    /** Recent protocol events, one per line — which frames each side actually exchanged. */
    external fun nativeQueryTrace(handle: Long): String?

    external fun nativeDestroy(handle: Long)

    init {
        // Do not let a missing/ABI-mismatched .so fail class initialization: that
        // turns every later access into NoClassDefFoundError, which the call sites'
        // catch (UnsatisfiedLinkError) cannot match. A failed load leaves the class
        // usable; native calls then throw UnsatisfiedLinkError, which they do catch.
        NativeLoader.load("share_nearby", "ShareNative")
    }

    /** `p000\eanu.java:8` — the device type `:share` advertises. */
    const val DEVICE_TYPE_PHONE: Int = 1
}
