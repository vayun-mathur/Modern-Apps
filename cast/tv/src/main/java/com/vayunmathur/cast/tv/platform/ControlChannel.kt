package com.vayunmathur.cast.tv.platform

import com.vayunmathur.library.log.Log
import com.vayunmathur.cast.protocol.ControlCodec
import com.vayunmathur.cast.protocol.ControlFraming
import com.vayunmathur.cast.protocol.ControlMessage
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.Socket

private const val TAG = "ControlChannel"

/**
 * One phone's control connection: length-prefixed messages, and the transcript bytes they contribute.
 *
 * [send] and [receive] hand the *body* bytes back alongside the message, because the handshake
 * transcript has to be the bytes that actually crossed the wire. Re-encoding a decoded message is not
 * guaranteed to be byte-stable, and a transcript the two ends disagree on fails every pair attempt
 * with no clue as to why.
 */
class ControlChannel(private val socket: Socket) {

    private val input = DataInputStream(socket.inputStream.buffered())
    private val output = DataOutputStream(socket.outputStream.buffered())

    val codec = ControlCodec()

    val remoteAddress: String get() = socket.inetAddress?.hostAddress ?: "?"

    init {
        // Nagle would hold a small control frame back waiting for more to send, adding a round trip
        // of latency to every step of a handshake made entirely of small frames.
        socket.tcpNoDelay = true
        socket.soTimeout = READ_TIMEOUT_MS
    }

    /**
     * Returns the body bytes, for the transcript.
     *
     * Synchronised because there are now three writers: the session coroutine, the media loop's `Bye`
     * path, and the UI thread whenever the remote is pressed. Two encodes interleaving would also
     * advance the cipher's nonce twice for one frame, which is not a corrupted message but an
     * undecryptable one.
     */
    fun send(message: ControlMessage): ByteArray = synchronized(output) {
        val body = codec.encode(message)
        output.write(ControlFraming.encode(body))
        output.flush()
        body
    }

    /**
     * The next message, or null when the peer closed or sent something unreadable.
     *
     * Null is treated the same way by every caller - end the session - because after the cipher is
     * installed there is no benign reason for a frame that will not decode.
     */
    fun receive(): Received? {
        val body = runCatching { ControlFraming.read(input) }.getOrNull() ?: return null
        val message = codec.decode(body) ?: return null
        return Received(message, body)
    }

    fun close() {
        runCatching { socket.close() }
    }

    /** One received control message together with the exact body bytes that carried it on the wire. */
    data class Received(val message: ControlMessage, val body: ByteArray) {
        override fun equals(other: Any?): Boolean =
            other is Received && message == other.message && body.contentEquals(other.body)

        override fun hashCode(): Int = 31 * message.hashCode() + body.contentHashCode()
    }

    private companion object {
        /**
         * How long a read may block before the phone is treated as gone.
         *
         * This is also what frees the TV after a phone crashes mid-session: only one connection is
         * accepted at a time, so without a bound a dead socket would lock the receiver out until it
         * was restarted. Long enough that a user thinking about a pair code is not timed out.
         */
        const val READ_TIMEOUT_MS = 60_000
    }
}
