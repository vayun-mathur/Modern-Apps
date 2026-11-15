package com.vayunmathur.share.network.airdrop

import com.vayunmathur.library.log.Log
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.SocketException
import javax.net.ssl.SSLServerSocket
import javax.net.ssl.SSLSocket
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

private const val TAG = "AirDropHttpServer"
private const val CLIENT_TIMEOUT_MS = 120_000

/**
 * The receiver's HTTPS endpoint, listening on `[::]` so it answers on whichever link the
 * sender reached us over (`mosey0` link-local or the Wi-Fi LAN).
 *
 * Each connection is served on its own coroutine and may carry several requests in a row;
 * `/Ask` can suspend for as long as the user takes to answer.
 */
class AirDropHttpServer(private val handler: Handler) {

    /** Contract for handler. */
    fun interface Handler {
        suspend fun handle(request: AirDropHttp.Request, remote: InetAddress): AirDropHttp.Response
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var server: SSLServerSocket? = null
    private var acceptJob: Job? = null

    val port: Int? get() = server?.localPort

    /** Binds a fresh TLS identity on an ephemeral port and returns it. Idempotent. */
    @Synchronized
    fun start(): Int {
        server?.let { return it.localPort }
        val socket = AirDropTls.serverContext().serverSocketFactory.createServerSocket() as SSLServerSocket
        socket.reuseAddress = true
        socket.bind(InetSocketAddress(InetAddress.getByName("::"), 0))
        socket.enabledProtocols = AirDropTls.PROTOCOLS.filter { it in socket.supportedProtocols }.toTypedArray()
        server = socket
        acceptJob = scope.launch { acceptLoop(socket) }
        Log.status(TAG, "listening on port ${socket.localPort}")
        return socket.localPort
    }

    @Synchronized
    fun stop() {
        acceptJob?.cancel()
        acceptJob = null
        runCatching { server?.close() }
        server = null
    }

    fun release() {
        stop()
        scope.cancel()
    }

    // Broad catch is deliberate: the accept loop must outlive undocumented RuntimeExceptions
    // from the TLS stack, and only stop when the socket is closed.
    @Suppress("TooGenericExceptionCaught")
    private suspend fun acceptLoop(socket: SSLServerSocket) {
        while (scope.isActive && !socket.isClosed) {
            try {
                val client = socket.accept() as SSLSocket
                client.soTimeout = CLIENT_TIMEOUT_MS
                scope.launch { serve(client) }
            } catch (e: SocketException) {
                if (!socket.isClosed) Log.status(TAG, "accept failed", e)
            } catch (e: Exception) {
                Log.status(TAG, "accept failed", e)
            }
        }
    }

    // Broad catch is deliberate: a misbehaving peer must cost one connection, not the server.
    @Suppress("TooGenericExceptionCaught")
    private suspend fun serve(client: SSLSocket) {
        val remote = client.inetAddress
        try {
            client.use {
                val input = client.inputStream.buffered()
                val output = client.outputStream.buffered()
                var keepConnectionOpen = true
                while (keepConnectionOpen) {
                    val request = AirDropHttp.readRequest(input)
                    if (request == null) {
                        keepConnectionOpen = false
                    } else {
                        Log.dev(TAG, "${request.method} ${request.path} from ${remote.hostAddress}")
                        val response = handler.handle(request, remote)
                        AirDropHttp.drain(request.body)
                        val keepAlive = request.headers.keepAlive
                        AirDropHttp.writeResponse(output, response, keepAlive)
                        keepConnectionOpen = keepAlive
                    }
                }
            }
        } catch (e: Exception) {
            Log.status(TAG, "connection from ${remote?.hostAddress} ended", e)
        }
    }
}
