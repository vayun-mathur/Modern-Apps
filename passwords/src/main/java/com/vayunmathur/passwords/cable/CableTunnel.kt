package com.vayunmathur.passwords.cable

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.websocket.DefaultClientWebSocketSession
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocketSession
import io.ktor.client.request.header
import io.ktor.client.request.url
import io.ktor.http.HttpHeaders
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readBytes
import java.io.IOException

/**
 * WebSocket client for a caBLE v2 tunnel server. In the QR flow this app is the authenticator,
 * so it opens the "new" endpoint, learns a routing id, and then relays raw binary frames (the Noise
 * handshake messages, then the encrypted CTAP traffic) to/from the browser via the tunnel.
 *
 * Endpoints (Chromium `tunnelserver`):
 *  - authenticator: `wss://<domain>/cable/new/<hex(tunnelId)>`
 *  - browser:       `wss://<domain>/cable/connect/<hex(routingId)>/<hex(tunnelId)>`
 *
 * WebSocket subprotocol is [SUBPROTOCOL] (`fido.cable`).
 *
 * Verified against Chromium (2024): the "new" URL path, the `fido.cable` subprotocol, and the
 * routing-id response header ([ROUTING_ID_HEADER]) all match `tunnelserver::GetNewTunnelURL`,
 * `kCableWebSocketProtocol`, and `kCableRoutingIdHeader`. (Live tunnel-server behaviour still can't
 * be exercised offline.)
 */
class CableTunnel private constructor(
    private val client: HttpClient,
    private val session: DefaultClientWebSocketSession,
    /** Server-assigned routing id (typically 3 bytes) to embed in the BLE EID, or null. */
    val routingId: ByteArray?,
) {
    /** Sends one binary tunnel frame. */
    suspend fun send(data: ByteArray) {
        session.send(Frame.Binary(true, data))
    }

    /** Receives the next binary tunnel frame, skipping control frames. */
    suspend fun receive(): ByteArray {
        while (true) {
            when (val frame = session.incoming.receive()) {
                is Frame.Binary -> return frame.readBytes()
                is Frame.Close -> throw IOException("Tunnel closed by server")
                else -> Unit // ping/pong/text: ignore
            }
        }
    }

    suspend fun close() {
        runCatching { session.close() }
        client.close()
    }

    companion object {
        const val SUBPROTOCOL = "fido.cable"
        const val ROUTING_ID_HEADER = "X-caBLE-Routing-ID"

        /** Opens the authenticator "new" tunnel and captures the routing id. */
        suspend fun connectNew(domain: String, tunnelId: ByteArray): CableTunnel {
            val client = HttpClient(CIO) { install(WebSockets) }
            val url = "wss://$domain/cable/new/${hex(tunnelId)}"
            val session = client.webSocketSession {
                url(url)
                header(HttpHeaders.SecWebSocketProtocol, SUBPROTOCOL)
            }
            val routingHex = session.call.response.headers[ROUTING_ID_HEADER]
            val routingId = routingHex?.let { runCatching { unhex(it) }.getOrNull() }
            return CableTunnel(client, session, routingId)
        }

        fun hex(bytes: ByteArray): String =
            bytes.joinToString("") { "%02x".format(it.toInt() and 0xFF) }

        private fun unhex(s: String): ByteArray =
            s.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    }
}
