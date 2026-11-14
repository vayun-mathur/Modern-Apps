package com.vayunmathur.cast.platform

import com.vayunmathur.library.log.Log
import com.vayunmathur.cast.protocol.EphemeralTls
import com.vayunmathur.cast.protocol.ExchangeOutcome
import com.vayunmathur.cast.protocol.MediaProxyExchange
import com.vayunmathur.cast.protocol.MediaResourceResolver
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.io.IOException
import java.security.GeneralSecurityException
import java.security.SecureRandom
import java.util.Base64
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

private const val TAG = "MediaProxyServer"

/**
 * How long a connection may sit idle between requests before it is reaped.
 *
 * Keep-alive is worth having - scrubbing is a burst of range requests - but a connection nobody
 * is using is a thread and a descriptor, and the TV opens a fresh one whenever it needs to.
 */
private const val IDLE_TIMEOUT_MS = 30_000

/**
 * More connections than any session needs, and few enough that a hostile peer on the LAN cannot
 * exhaust the process by opening sockets.
 *
 * A worst case is video, audio, a caption track and artwork at once, so this is generous.
 */
private const val MAX_CONNECTIONS = 16

/**
 * Serves app media to the TV over HTTPS, so the phone does not have to re-encode it.
 *
 * This is the whole reason the pixel path can go away for app content. The TV asks for byte
 * ranges of the original file and plays them with its own decoder, clock and buffer, which means
 * seeking is an offset rather than a key-frame renegotiation, pausing is the TV's business
 * entirely, and the phone neither decodes nor encodes a frame.
 *
 * The server belongs in `:cast` and not in the app being cast. `:sdk:cast` exists so that a
 * consumer owns no sockets and needs no network permission - `:music` has no `INTERNET`
 * permission at all - so the app supplies bytes through a [MediaResourceResolver] and this end
 * puts them on the wire.
 *
 * Everything that decides *what* to answer lives in `:cast:protocol`
 * ([MediaProxyExchange], [EphemeralTls]), where it can be tested without a TV. What is left here
 * is sockets, threads and lifecycle.
 *
 * **Single-use: an instance that has been [stop]ped cannot be started again**, because [stop]
 * cancels [scope] and a later [start] would bind a port and then accept nothing. Every session
 * builds a fresh one, so this is a property worth stating rather than a bug worth guarding.
 */
class MediaProxyServer(
    private val token: String,
    private val resolver: MediaResourceResolver,
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val exchange = MediaProxyExchange(token, resolver)
    private val connections = AtomicInteger(0)

    /**
     * A plain listening socket, with TLS layered on each connection after it is accepted.
     *
     * **Not an `SSLServerSocket`, and that is what makes [stop] work.** Closing an `SSLSocket` is not
     * a close, it is a *write*: the JDK sends a `close_notify` first, which takes the socket's write
     * lock - the very lock held by the thread parked in the body transfer this needs to interrupt. So
     * `stop()` deadlocked against the connection it was trying to end. Owning the underlying TCP
     * socket means the teardown can close *that*, which needs no lock and unblocks the writer at once.
     */
    private var server: ServerSocket? = null

    private var tls: SSLSocketFactory? = null

    /**
     * The connections currently being served, as their underlying TCP sockets, so [stop] can end them.
     *
     * **Closing the socket is the only thing that can end a serving thread**, and an unknown-length
     * body makes that thread's whole life one `write` call: once the TV's buffer is full it parks in
     * TCP backpressure for as long as the track lasts. Coroutine cancellation cannot interrupt a
     * thread blocked in socket I/O, and `soTimeout` is a *read* timeout that never applies to it -
     * so before this existed a session left a thread and a descriptor behind, [connections] never
     * came back down, and enough reconnects turned [MAX_CONNECTIONS] into a ratchet that refused to
     * serve at all.
     */
    private val live: MutableSet<Socket> = Collections.newSetFromMap(ConcurrentHashMap())

    /**
     * How many connections are currently being served.
     *
     * Exposed for the test that proves [stop] unwinds them. The ratchet this fixes is invisible from
     * outside - a leaked thread and descriptor look like nothing at all until the seventeenth
     * session is refused - so the count is what the test can actually assert on.
     */
    internal val openConnections: Int get() = connections.get()

    /** What the TV has to be told to be able to fetch anything. */
    data class Endpoint(val port: Int, val certificateFingerprint: ByteArray) {
        override fun equals(other: Any?): Boolean = other is Endpoint &&
            port == other.port && certificateFingerprint.contentEquals(other.certificateFingerprint)

        override fun hashCode(): Int = 31 * port + certificateFingerprint.contentHashCode()
    }

    /**
     * Binds an ephemeral port and starts accepting.
     *
     * [addresses] end up as subject alternative names in the certificate, so they have to be the
     * addresses the TV will actually dial. Returns null when the port cannot be bound, which the
     * caller turns into a refused session rather than a session that silently plays nothing.
     */
    fun start(addresses: List<InetAddress> = localAddresses()): Endpoint? {
        if (server != null) return null
        return try {
            val credentials = EphemeralTls.server(addresses)
            tls = credentials.sslContext.socketFactory
            val socket = ServerSocket(0, MAX_CONNECTIONS, InetAddress.getByName("0.0.0.0"))
            server = socket
            val boundAddresses = addresses.joinToString { it.hostAddress ?: "?" }
            Log.status(TAG, "media proxy listening on ${socket.localPort} for $boundAddresses")
            accept(socket)
            Endpoint(socket.localPort, credentials.fingerprint)
        } catch (e: IOException) {
            failStart(e)
        } catch (e: IllegalArgumentException) {
            failStart(e)
        } catch (e: SecurityException) {
            failStart(e)
        } catch (e: GeneralSecurityException) {
            failStart(e)
        }
    }

    private fun failStart(e: Exception): Endpoint? {
        Log.status(TAG, "could not start the media proxy", e)
        stop()
        return null
    }

    fun stop() {
        // Closing the server socket is what unblocks the accept loop; cancelling first would
        // leave it parked in a blocking accept until a connection happened to arrive.
        runCatching { server?.close() }
        server = null
        tls = null
        // And closing each connection's TCP socket is what unblocks the thread serving it - see
        // [live] and [server] for why it is the TCP socket and not the TLS one. Before the scope is
        // cancelled, because a cancelled scope cannot reach them at all.
        for (client in live) runCatching { client.close() }
        live.clear()
        scope.cancel()
    }

    private fun accept(socket: ServerSocket) {
        scope.launch {
            while (isActive) {
                val client = acceptClient(socket) ?: break
                admitAndServe(client)
            }
        }
    }

    /**
     * Claim a connection slot and serve, or refuse when full.
     *
     * The count is claimed before the comparison, so admission is atomic: two accepts that each
     * read the count before either raised it would both have been let in.
     */
    private fun admitAndServe(client: Socket) {
        if (connections.incrementAndGet() > MAX_CONNECTIONS) {
            refuseClient(client)
            return
        }
        // Registered here rather than inside `serve`, so a socket accepted moments before a
        // [stop] is still closed by it even if its coroutine never runs.
        live += client
        scope.launch { serve(client) }
    }

    private fun CoroutineScope.acceptClient(socket: ServerSocket): Socket? = try {
        socket.accept()
    } catch (e: IOException) {
        failAccept(e)
    } catch (e: SecurityException) {
        failAccept(e)
    } catch (e: IllegalArgumentException) {
        failAccept(e)
    }

    private fun CoroutineScope.failAccept(e: Exception): Socket? {
        // A failed accept after [stop] closed the server socket is the teardown itself, not news.
        if (isActive) Log.status(TAG, "accept failed", e)
        return null
    }

    private fun refuseClient(client: Socket) {
        Log.status(TAG, "refusing a connection: already serving $MAX_CONNECTIONS")
        connections.decrementAndGet()
        runCatching { client.close() }
    }

    /**
     * One connection: the TLS handshake, then requests until the client is done or the socket dies.
     *
     * The handshake happens here rather than in the accept loop because it is a round trip on the
     * network, and a loop that waited for one would refuse to accept the next connection while it did.
     */
    private fun serve(client: Socket) {
        var secure: SSLSocket? = null
        try {
            client.soTimeout = IDLE_TIMEOUT_MS
            // Nagle would hold back the tail of a response waiting for more to send, which on a
            // small range request is pure added latency.
            client.tcpNoDelay = true
            val factory = tls ?: return
            // autoClose, so closing either one closes the other and no descriptor outlives this.
            val layered = factory.createSocket(
                client,
                client.inetAddress?.hostAddress,
                client.port,
                true,
            ) as SSLSocket
            layered.useClientMode = false
            restrictProtocols(layered.supportedProtocols) { layered.enabledProtocols = it }
            secure = layered
            val input = layered.inputStream.buffered()
            val output = layered.outputStream.buffered()
            while (true) {
                val result = exchange.serve(input, output)
                report(result.outcome, client)
                if (!result.reusable) break
            }
        } catch (e: IOException) {
            endConnection(client, e)
        } catch (e: IllegalStateException) {
            endConnection(client, e)
        } catch (e: IllegalArgumentException) {
            endConnection(client, e)
        } finally {
            live -= client
            // The TLS close first, which writes a `close_notify`: an unknown-length body is delimited
            // by the connection closing, so without it the TV sees a truncation rather than a clean
            // end of track. Safe from here and only from here - this is the thread that was writing,
            // so the write lock it needs is this thread's own.
            runCatching { secure?.close() }
            runCatching { client.close() }
            connections.decrementAndGet()
        }
    }

    private fun endConnection(client: Socket, e: Exception) {
        // A player that has finished with a range simply closes, so this is the ordinary end
        // of a connection as often as it is a fault.
        Log.dev(TAG, "connection from ${client.inetAddress?.hostAddress} ended: ${e.javaClass.simpleName}")
    }

    private fun report(outcome: ExchangeOutcome, client: Socket) {
        val peer = client.inetAddress?.hostAddress
        when (outcome) {
            is ExchangeOutcome.Served ->
                Log.dev(TAG, "served ${outcome.resourceId} ${outcome.bytes} bytes to $peer")
            is ExchangeOutcome.Rejected ->
                // Logged as a warning because on a pinned, tokenised connection there is no
                // legitimate source of a rejected request: it is either a bug at our end or a
                // peer that should not be talking to us.
                Log.status(TAG, "refused $peer with ${outcome.status}: ${outcome.detail}")
            is ExchangeOutcome.Truncated ->
                Log.status(
                    TAG,
                    "${outcome.resourceId} gave ${outcome.actual} of ${outcome.expected} bytes; " +
                        "closing so the player sees a short body rather than a stall",
                )
            ExchangeOutcome.Closed -> Unit
        }
    }

        /** Session-token generation and local-address lookup for the media proxy. */
    companion object {

        /**
         * A capability token for one session.
         *
         * URL-safe because it becomes a path segment, and unpadded so it needs no escaping. 32
         * bytes rather than something shorter because the token is the only thing between a LAN
         * peer that has guessed the port and the user's media.
         */
        fun randomToken(): String {
            val bytes = ByteArray(32)
            SecureRandom().nextBytes(bytes)
            return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
        }

        /** Every non-loopback address the TV might reach us on. */
        fun localAddresses(): List<InetAddress> =
            runCatching {
                NetworkInterface.getNetworkInterfaces().asSequence()
                    .filter { it.isUp && !it.isLoopback }
                    .flatMap { it.inetAddresses.asSequence() }
                    .filterNot { it.isLoopbackAddress || it.isLinkLocalAddress }
                    .toList()
            }.getOrDefault(emptyList())

        /**
         * TLS 1.2 at the oldest.
         *
         * Both ends are ours, so there is no legacy peer to accommodate, and the older protocols
         * are enabled by default on some platform versions.
         */
        private inline fun restrictProtocols(supported: Array<String>, apply: (Array<String>) -> Unit) {
            val wanted = supported.filter { it == "TLSv1.2" || it == "TLSv1.3" }
            if (wanted.isNotEmpty()) apply(wanted.toTypedArray())
        }
    }
}
