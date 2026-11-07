package com.vayunmathur.cast.network

import com.vayunmathur.library.log.Log
import com.vayunmathur.cast.protocol.ControlCodec
import com.vayunmathur.cast.protocol.ControlFraming
import com.vayunmathur.cast.protocol.ControlMessage
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException

private const val TAG = "ControlSocket"

private const val CONNECT_TIMEOUT_MS = 8_000

/**
 * How long a read may block before the TV is treated as gone.
 *
 * Generous, because the longest wait in a session is a human typing six digits, and a session that
 * timed out while the user was reading the code would be worse than one that never started.
 */
private const val READ_TIMEOUT_MS = 60_000

/**
 * The phone's end of the control channel: a plain TCP socket with length-prefixed messages.
 *
 * The framing is `CastChannel`'s `int32be(len) ‖ payload` idiom, kept because it was written and
 * tested - **minus the permissive TLS**, which is the point. CastV2 had no PKI, so speaking it at all
 * meant a socket that trusted any certificate. Owning both ends means the trust is our own ML-KEM
 * handshake instead, and nothing in this app has to be told to skip verification.
 *
 * [send] and [receive] hand back the body bytes as well as the message, because the handshake
 * transcript must be the bytes that crossed the wire - re-encoding a decoded message is not
 * guaranteed to be byte-stable, and a transcript the two ends disagree on fails pairing with no clue
 * as to why.
 */
class ControlSocket(private val host: String, private val port: Int) {

    private var socket: Socket? = null
    private var input: DataInputStream? = null
    private var output: DataOutputStream? = null

    val codec = ControlCodec()

    /**
     * Our own address on the interface that reaches the TV.
     *
     * The media proxy has to tell the TV where to fetch from, and a phone can have several
     * addresses - Wi-Fi, a VPN, a tethering bridge. This is the one the kernel actually chose to
     * reach this TV, so it is the only one guaranteed to be reachable back.
     */
    val localAddress: java.net.InetAddress?
        get() = socket?.localAddress

    /** Throws when the TV is not reachable, which the caller turns into a user-facing message. */
    fun connect() {
        val plain = Socket()
        try {
            plain.connect(InetSocketAddress(host, port), CONNECT_TIMEOUT_MS)
            // Nagle would hold a small control frame back waiting for more to send, adding a round
            // trip of latency to every step of a handshake made entirely of small frames.
            plain.tcpNoDelay = true
            plain.soTimeout = READ_TIMEOUT_MS
            input = DataInputStream(plain.inputStream.buffered())
            output = DataOutputStream(plain.outputStream.buffered())
            socket = plain
            Log.status(TAG, "control channel open to $host:$port")
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
     * The body bytes for the transcript, or null when this socket is not connected.
     *
     * **Null rather than a throw, symmetrically with [receive].** A closed socket is a race, not a
     * caller's mistake: [close] is deliberately called from teardown without holding the mutex a
     * handshake holds - so a send already in flight can legitimately find its stream gone. Signalling
     * that with `IllegalStateException` made it look like a programming error and, because the
     * handshake path has nothing to catch such a thing, took the whole process with it.
     */
    fun send(message: ControlMessage): ByteArray? {
        val stream = output ?: return null
        val body = codec.encode(message)
        return try {
            stream.write(ControlFraming.encode(body))
            stream.flush()
            body
        } catch (e: IOException) {
            // The peer hung up first: a write into that socket answers with a
            // broken pipe rather than a clean refusal, which is the same
            // "not connected" answer the null above gives - see the KDoc.
            Log.status(TAG, "the TV closed the control channel before a send", e)
            null
        } catch (e: IllegalArgumentException) {
            Log.status(TAG, "could not frame a control message", e)
            null
        }
    }

    /**
     * The next message, or null when the TV closed or sent something unreadable.
     *
     * Every failure mode still collapses into null, because every one of them ends the session -
     * but they are logged apart. A starved read and a TV that hung up are indistinguishable to the
     * caller and were indistinguishable in the log too, which is what made a missing heartbeat look
     * like the receiver dropping us.
     */
    fun receive(): Received? {
        val stream = input ?: return null
        val body = readFrameBody(stream) ?: return null
        val message = codec.decode(body) ?: run {
            Log.status(TAG, "could not decode a ${body.size}-byte control frame")
            return null
        }
        return Received(message, body)
    }

    private fun readFrameBody(stream: DataInputStream): ByteArray? = try {
        ControlFraming.read(stream)
    } catch (e: SocketTimeoutException) {
        Log.status(TAG, "no control frame for ${READ_TIMEOUT_MS}ms; treating the TV as gone", e)
        null
    } catch (e: IOException) {
        Log.status(TAG, "the TV closed the control channel", e)
        null
    } catch (e: IllegalArgumentException) {
        // A length prefix outside the bound. The stream cannot be resynchronised after one.
        Log.status(TAG, "unframeable control traffic", e)
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

    data class Received(val message: ControlMessage, val body: ByteArray) {
        override fun equals(other: Any?): Boolean =
            other is Received && message == other.message && body.contentEquals(other.body)

        override fun hashCode(): Int = 31 * message.hashCode() + body.contentHashCode()
    }
}
