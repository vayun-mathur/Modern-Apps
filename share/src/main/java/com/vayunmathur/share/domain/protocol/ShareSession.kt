package com.vayunmathur.share.domain.protocol

import com.vayunmathur.share.protocol.ShareNative
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Immutable value describing pending file; carries name, sizeBytes, mimeType. */
@Serializable
data class PendingFile(val name: String, val sizeBytes: Long, val mimeType: String)

/**
 * One decrypted chunk of an incoming FILE payload, as handed over by
 * [ShareSession.drainReceived].
 *
 * [offset] is the chunk's position within the payload and [totalSize] the payload's
 * announced length, so a caller can report progress without tracking either itself.
 * [isLast] marks the final chunk, which is when the destination file can be closed.
 */
class ReceivedChunk(
    val payloadId: Long,
    val offset: Long,
    val totalSize: Long,
    val isLast: Boolean,
    val name: String,
    val body: ByteArray,
)

/** Transfer state — ordinal matches the Rust State enum (see PROTOCOL_CONTRACT.md §4). */
enum class ShareState(val code: Int) {
    Handshaking(0),
    AwaitingAccept(1),
    Transferring(2),
    Completed(3),
    Failed(4),
    Unknown(-1);

    /** Shared constants and helpers for ShareState. */
    companion object {
        fun fromCode(code: Int): ShareState = entries.firstOrNull { it.code == code } ?: Unknown
    }
}

/**
 * Thin lifecycle wrapper around the native session handle. Dispatch on
 * [state] in your UI (Handshaking → AwaitingAccept → Transferring → Completed/Failed).
 *
 * [isInitiator] must reflect which side opened the TCP socket: `true` for the dialler,
 * `false` for the acceptor. Only the initiator sends `CONNECTION_REQUEST`, and only the
 * initiator is the UKEY2 client, so getting this wrong deadlocks the handshake.
 *
 * [localEndpointInfo] and [localEndpointId] must be the ones this device advertises: the
 * peer matches `CONNECTION_REQUEST` against what it discovered, and drops the connection
 * when either is missing or malformed.
 *
 * Usage (one instance per TCP connection / peer):
 * ```
 * val session = ShareSession(localName = "My Phone", isInitiator = true)
 * try {
 *     // after TCP connect, pump bytes:
 *     session.feedInbound(socket.read())
 *     session.drainOutbound()?.let { socket.write(it) }
 *     when (session.state) {
 *         AwaitingAccept -> showIncoming(session.pendingFiles)
 *         // on user tap:
 *         // session.accept(true, destDir); session.openFile(...); ...
 *         else -> {}
 *     }
 * } finally { session.destroy() }
 * ```
 */
class ShareSession @JvmOverloads constructor(
    localName: String,
    localEndpointInfo: ByteArray = ByteArray(0),
    localEndpointId: String = "",
    isInitiator: Boolean = true,
) : AutoCloseable {
    val handle: Long =
        ShareNative.nativeInit(localName, localEndpointInfo, localEndpointId, isInitiator)
    private var closed = false

    /** Feed raw bytes read from the TCP socket into the protocol state machine. Returns 0 on ok. */
    fun feedInbound(bytes: ByteArray): Int = ShareNative.nativeFeedInbound(handle, bytes)

    /** Drain bytes Rust wants written to the socket; null if nothing to send. Call in a loop. */
    fun drainOutbound(): ByteArray? = ShareNative.nativeDrainOutbound(handle)

    /** Current state ordinal -> [ShareState]. Poll after feed/drain. */
    val state: ShareState get() = ShareState.fromCode(ShareNative.nativeQueryState(handle))

    /** Files announced by the sender's Introduction (valid in AwaitingAccept/Transferring). */
    val pendingFiles: List<PendingFile>
        get() {
            val raw = ShareNative.nativeQueryPendingFiles(handle) ?: return emptyList()
            if (raw.isEmpty()) return emptyList()
            return runCatching {
                Json.decodeFromString<List<PendingFile>>(raw.decodeToString())
            }.getOrElse { emptyList() }
        }

    /** Accept or reject the incoming transfer. Only valid in AwaitingAccept. */
    fun accept(accept: Boolean, destDir: String = ""): Int =
        ShareNative.nativeAccept(handle, accept, destDir)

    /**
     * Stage the files this side will send, then announce them with [queueIntroduction].
     *
     * Safe to call at any point after construction; the announcement itself is held
     * until the paired-key exchange completes.
     */
    fun setFilesToSend(files: List<PendingFile>): Int =
        ShareNative.nativeSetFilesToSend(
            handle,
            Json.encodeToString(files).encodeToByteArray(),
        )

    /** Announce the files staged by [setFilesToSend]. */
    fun queueIntroduction(): Int = ShareNative.nativeQueueIntroduction(handle)

    /** Emit a `KEEP_ALIVE` frame so a long or idle transfer is not torn down. */
    fun sendKeepAlive(): Int = ShareNative.nativeSendKeepAlive(handle)

    fun openFile(fileName: String, fileSize: Long): Int =
        ShareNative.nativeOpenFile(handle, fileName, fileSize)

    fun writeChunk(chunk: ByteArray): Int = ShareNative.nativeWriteChunk(handle, chunk)

    fun closeFile(): Int = ShareNative.nativeCloseFile(handle)

    /**
     * Take the next received FILE chunk, or null when nothing is pending.
     *
     * Call in a loop after every [feedInbound]. Rust drops each chunk as it hands it
     * over, so a caller that drains as it pumps holds one chunk at a time no matter
     * how large the file is; a caller that never drains loses the bytes.
     */
    fun drainReceived(): ReceivedChunk? =
        ShareNative.nativeDrainReceived(handle)?.let(::decodeReceivedRecord)

    /**
     * Why the session failed, or null while it is healthy.
     *
     * Specific enough to diagnose a handshake against real hardware, unlike the
     * return code alone.
     */
    val failureReason: String? get() = ShareNative.nativeQueryFailureReason(handle)

    /**
     * The peer's advertised device name, or null until its `CONNECTION_REQUEST` has been read.
     *
     * This is what a notification should be titled with: `host:port` means nothing to
     * someone deciding whether to accept a transfer.
     */
    val peerName: String? get() = ShareNative.nativeQueryPeerName(handle)

    /**
     * Recent protocol events, one per line, for diagnosing a peer that stops responding.
     */
    val trace: String? get() = ShareNative.nativeQueryTrace(handle)

    fun destroy() {
        if (!closed) {
            closed = true
            ShareNative.nativeDestroy(handle)
        }
    }

    override fun close() = destroy()
}

/**
 * Decode the received-chunk record of PROTOCOL_CONTRACT.md §6.
 *
 * ```
 * u8 version=1 | i64 payload_id | i64 offset | i64 total_size | u8 flags
 * u16 name_len | u8[] name | u32 body_len | u8[] body      // all big-endian
 * ```
 *
 * Returns null on a version or length mismatch rather than throwing: a record this
 * side cannot read is a bug in the pair, not something a transfer should crash on.
 */
private fun decodeReceivedRecord(raw: ByteArray): ReceivedChunk? {
    val buf = ByteBuffer.wrap(raw).order(ByteOrder.BIG_ENDIAN)
    return try {
        if (buf.get() != RECEIVED_RECORD_VERSION) return null
        val payloadId = buf.long
        val offset = buf.long
        val totalSize = buf.long
        val flags = buf.get().toInt()
        val name = ByteArray(buf.short.toInt() and 0xFFFF).also(buf::get).decodeToString()
        val body = ByteArray(buf.int).also(buf::get)
        ReceivedChunk(
            payloadId = payloadId,
            offset = offset,
            totalSize = totalSize,
            isLast = (flags and RECEIVED_FLAG_LAST) != 0,
            name = name,
            body = body,
        )
    } catch (_: RuntimeException) {
        // BufferUnderflowException / NegativeArraySizeException: a truncated record.
        null
    }
}

private const val RECEIVED_RECORD_VERSION: Byte = 1
private const val RECEIVED_FLAG_LAST: Int = 1
