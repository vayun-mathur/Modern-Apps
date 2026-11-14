package com.vayunmathur.sdk.controller

import com.vayunmathur.library.log.Log
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException

private const val TAG = "ControllerSocket"

private const val CONNECT_TIMEOUT_MS = 8_000

/**
 * How long a read may block before the peer is treated as gone.
 *
 * Generous, because the longest wait in a session is a human typing six digits, and a session that
 * timed out while the user was reading the code would be worse than one that never started. Live
 * sessions stay alive on the 20 s PING, not on this bound.
 */
private const val READ_TIMEOUT_MS = 60_000

/**
 * Either end of a controller connection: a plain TCP socket with length-prefixed messages.
 *
 * The framing is `:cast:protocol`'s `int32be(len) ‖ payload` idiom, kept because it was written
 * and tested - minus the media half, which a controller never needs.
 *
 * [send] and [receive] hand back the body bytes as well as the message, because the handshake
 * transcript must be the bytes that crossed the wire - re-encoding a decoded message is not
 * guaranteed to be byte-stable, and a transcript the two ends disagree on fails pairing with no clue
 * as to why.
 */
class ControllerSocket {

    private var socket: Socket? = null
    private var input: DataInputStream? = null
    private var output: DataOutputStream? = null

    val codec = ControllerCodec()

    /** Throws when the peer is not reachable, which the caller turns into a user-facing message. */
    fun connect(host: String, port: Int) {
        val plain = Socket()
        try {
            plain.connect(InetSocketAddress(host, port), CONNECT_TIMEOUT_MS)
            // Nagle would hold a small control frame back waiting for more to send, adding a round
            // trip of latency to every step of a handshake made entirely of small frames - and to
            // every 30 Hz input sample after it.
            plain.tcpNoDelay = true
            plain.soTimeout = READ_TIMEOUT_MS
            input = DataInputStream(plain.inputStream.buffered())
            output = DataOutputStream(plain.outputStream.buffered())
            socket = plain
            Log.status(TAG, "controller channel open to $host:$port")
        } catch (e: IOException) {
            // Nothing else holds `plain` yet, so a failure here would leak the descriptor.
            runCatching { plain.close() }
            throw e
        } catch (e: IllegalArgumentException) {
            runCatching { plain.close() }
            throw e
        }
    }

    /**
     * Wrap an accepted server socket.
     *
     * The mirror of [connect] for the TV side: Nagle off and the same read bound, so both ends of
     * every connection behave identically whichever side dialed.
     */
    fun attach(accepted: Socket) {
        accepted.tcpNoDelay = true
        accepted.soTimeout = READ_TIMEOUT_MS
        input = DataInputStream(accepted.inputStream.buffered())
        output = DataOutputStream(accepted.outputStream.buffered())
        socket = accepted
    }

    /**
     * The body bytes for the transcript, or null when this socket is not connected.
     *
     * **Null rather than a throw, symmetrically with [receive].** A closed socket is a race, not a
     * caller's mistake: [close] is deliberately called from teardown without holding the mutex a
     * handshake holds - so a send already in flight can legitimately find its stream gone.
     */
    fun send(message: ControllerMessage): ByteArray? {
        val stream = output ?: return null
        val body = synchronized(stream) {
            val encoded = codec.encode(message)
            output?.let {
                it.write(ControllerFraming.encode(encoded))
                it.flush()
            } ?: return null
            encoded
        }
        return body
    }

    /**
     * The next message, or null when the peer closed or sent something unreadable.
     *
     * Every failure mode still collapses into null, because every one of them ends the session -
     * but they are logged apart. A starved read and a peer that hung up are indistinguishable to the
     * caller and were indistinguishable in the log too, which is what made a missing heartbeat look
     * like the other end dropping us.
     */
    fun receive(): Received? {
        val stream = input ?: return null
        val body = readFrameBody(stream) ?: return null
        val message = codec.decode(body) ?: run {
            Log.status(TAG, "could not decode a ${body.size}-byte controller frame")
            return null
        }
        return Received(message, body)
    }

    private fun readFrameBody(stream: DataInputStream): ByteArray? = try {
        ControllerFraming.read(stream)
    } catch (e: SocketTimeoutException) {
        Log.status(TAG, "no controller frame for ${READ_TIMEOUT_MS}ms; treating the peer as gone", e)
        null
    } catch (e: IOException) {
        Log.status(TAG, "the peer closed the controller channel", e)
        null
    } catch (e: IllegalArgumentException) {
        // A length prefix outside the bound. The stream cannot be resynchronised after one.
        Log.status(TAG, "unframeable controller traffic", e)
        null
    }

    fun close() {
        // Closing the socket is what unblocks a reader parked in receive; the streams are dropped
        // afterwards so a late send fails fast instead of writing into a dead socket.
        runCatching { socket?.close() }
        socket = null
        input = null
        output = null
    }

    /** One decoded message plus the raw body bytes it arrived as (for transcript binding). */
    data class Received(val message: ControllerMessage, val body: ByteArray) {
        override fun equals(other: Any?): Boolean =
            other is Received && message == other.message && body.contentEquals(other.body)

        override fun hashCode(): Int = 31 * message.hashCode() + body.contentHashCode()
    }
}
