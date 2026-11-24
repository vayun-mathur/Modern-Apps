package com.vayunmathur.library.wearlink

import java.io.ByteArrayOutputStream
import java.security.MessageDigest

/**
 * Large byte streams (the ChannelClient replacement): an offer precedes the
 * stream, raw chunks follow on the same RFCOMM session (no separate channel
 * socket needed — frames multiplex over one stream, ordered by TCP-like
 * RFCOMM delivery), and the receiver completes only when size + SHA-256
 * match the header.
 *
 * Resumability — yes, cheaply, and included: the receiver reports its
 * durable received offset in STREAM_ACK (once on open, then periodically),
 * and the sender seeks its source to that offset. The sink owns the partial
 * file, so an interrupted APK transfer resumes where it stopped across a
 * reconnect instead of restarting, provided the sink kept the `.partial`.
 * The hash is verified over the *whole* stream at close; a resumed receiver
 * re-reads its retained prefix into the digest (see [StreamReceiver]).
 */
data class StreamHeader(
    val totalSize: Long,
    val sha256: String,
    /** Offset the sender proposes to start from (usually 0; ack overrides). */
    val startOffset: Long = 0L,
) {
    fun encodeBody(): ByteArray {
        val sha = sha256.encodeToByteArray()
        val out = ByteArrayOutputStream()
        val fixed = ByteArray(8 + 8 + 2)
        FrameCodec.writeLong(fixed, 0, totalSize)
        FrameCodec.writeLong(fixed, 8, startOffset)
        fixed[16] = ((sha.size ushr 8) and 0xFF).toByte()
        fixed[17] = (sha.size and 0xFF).toByte()
        out.write(fixed); out.write(sha)
        return out.toByteArray()
    }

    companion object {
        fun decodeBody(body: ByteArray): StreamHeader? = runCatching {
            if (body.size < 18) return null
            val total = FrameCodec.readLong(body, 0)
            val start = FrameCodec.readLong(body, 8)
            val shaLen = ((body[16].toInt() and 0xFF) shl 8) or (body[17].toInt() and 0xFF)
            if (18 + shaLen > body.size) return null
            StreamHeader(total, body.decodeToString(18, 18 + shaLen), start)
        }.getOrNull()
    }
}

fun encodeOffsetBody(offset: Long): ByteArray =
    ByteArray(8).also { FrameCodec.writeLong(it, 0, offset) }

fun decodeOffsetBody(body: ByteArray): Long? =
    if (body.size >= 8) FrameCodec.readLong(body, 0) else null

/**
 * Receiver state machine. Sequential offsets only (RFCOMM is ordered): an
 * out-of-order chunk fails the transfer rather than buffering unboundedly.
 */
class StreamReceiver(
    val header: StreamHeader,
    initialOffset: Long = 0L,
    prefixStream: java.io.InputStream? = null,
) {
    private val digest = MessageDigest.getInstance("SHA-256")

    var received: Long = initialOffset
        private set

    var failed: Boolean = false
        private set

    init {
        require(initialOffset in 0..header.totalSize) { "bad resume offset" }
        // Fold the retained prefix into the digest so close-verification
        // covers the whole stream, not only the resumed tail. Streamed in
        // chunks: an APK prefix is never held in memory whole.
        if (prefixStream != null) {
            var read = 0L
            val buffer = ByteArray(64 * 1024)
            prefixStream.use { input ->
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    if (n > 0) { digest.update(buffer, 0, n); read += n }
                }
            }
            require(read == initialOffset) { "prefix/offset mismatch" }
        }
    }

    /** Offset to advertise in STREAM_ACK. */
    val ackOffset: Long get() = received

    /** Accept the next sequential chunk; false means abort the transfer. */
    fun onChunk(offset: Long, bytes: ByteArray): Boolean {
        if (failed) return false
        if (offset != received) { failed = true; return false }
        if (received + bytes.size > header.totalSize) { failed = true; return false }
        digest.update(bytes)
        received += bytes.size
        return true
    }

    /** True only when every byte arrived and the announced hash matches. */
    fun verifyComplete(): Boolean {
        if (failed || received != header.totalSize) return false
        val actual = digest.digest().joinToString("") { "%02x".format(it) }
        return actual.equals(header.sha256, ignoreCase = true)
    }
}

/** Sender state machine: chunk slicing + resume seek over a byte source. */
class StreamSender(
    val header: StreamHeader,
    private val sourceSize: Long,
) {
    var nextOffset: Long = header.startOffset
        private set

    /** Honour the receiver's durable offset (STREAM_ACK) before/while sending. */
    fun seekTo(offset: Long) {
        require(offset in 0..sourceSize) { "resume offset out of range" }
        nextOffset = offset
    }

    /** Next slice of [source] (a random-access read at [nextOffset]), or null at end. */
    fun nextChunk(readAt: (offset: Long, length: Int) -> ByteArray): ByteArray? {
        if (nextOffset >= sourceSize) return null
        val length = minOf(
            WearLinkProtocol.STREAM_CHUNK_BYTES.toLong(), sourceSize - nextOffset,
        ).toInt()
        val bytes = readAt(nextOffset, length)
        nextOffset += bytes.size
        return bytes
    }

    val complete: Boolean get() = nextOffset >= sourceSize
}
