package com.vayunmathur.passwords.platform.cable

import com.vayunmathur.library.log.Log
import com.vayunmathur.library.network.WebSocketClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import java.io.IOException

/**
 * WebSocket client for a caBLE v2 tunnel server – migrated to Android-only
 * [WebSocketClient] (no Ktor).
 */
class CableTunnel private constructor(
    private val wsClient: WebSocketClient,
    private val frameChannel: Channel<WebSocketClient.WsFrame>,
    val routingId: ByteArray?,
    private val collectorJob: kotlinx.coroutines.Job?,
) {
    suspend fun send(data: ByteArray) {
        wsClient.send(data)
    }

    suspend fun receive(): ByteArray {
        while (true) {
            val frame = frameChannel.receive()
            when (frame) {
                is WebSocketClient.WsFrame.Binary -> return frame.bytes
                is WebSocketClient.WsFrame.Close ->
                    throw IOException("Tunnel closed by server (${frame.code} ${frame.reason})")
                else -> Unit // ping/pong/text ignored
            }
        }
    }

    suspend fun close() {
        collectorJob?.cancel()
        try {
            wsClient.close()
        } catch (expected: IOException) {
            Log.status(TAG, "tunnel close threw", expected)
        }
    }

    /** Shared constants and helpers for CableTunnel. */
    companion object {
        const val SUBPROTOCOL = "fido.cable"
        const val ROUTING_ID_HEADER = "X-caBLE-Routing-ID"

        suspend fun connectNew(domain: String, tunnelId: ByteArray): CableTunnel {
            val url = "wss://$domain/cable/new/${hex(tunnelId)}"
            Log.dev(TAG, "Opening tunnel: $url")

            // Capture routing-id header from handshake
            val client = WebSocketClient.connect(
                urlStr = url,
                headers = mapOf("Sec-WebSocket-Protocol" to SUBPROTOCOL),
                captureResponseHeaders = listOf(ROUTING_ID_HEADER)
            )

            val headerSummary = client.responseHeaders.entries.joinToString { "${it.key}=${it.value}" }
            Log.dev(TAG, "Tunnel response headers: $headerSummary")
            val routingHex = client.capturedHeaders[ROUTING_ID_HEADER]
                ?: client.responseHeaders.entries
                    .firstOrNull { it.key.equals(ROUTING_ID_HEADER, ignoreCase = true) }
                    ?.value?.firstOrNull()
            val routingId = routingHex?.let { runCatching { unhex(it) }.getOrNull() }
            Log.dev(TAG, "routingId header=$routingHex parsed=${routingId?.let { hex(it) }}")

            // Bridge incoming flow into a Channel for receive() synchronous-style
            val channel = Channel<WebSocketClient.WsFrame>(Channel.UNLIMITED)
            val job = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
                try {
                    client.incomingFlow().collect { f -> channel.trySend(f) }
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    channel.close()
                }
            }

            return CableTunnel(client, channel, routingId, job)
        }

        private const val TAG = "CableTunnel"
        private const val BYTE_MASK = 0xFF
        private const val HEX_RADIX = 16
        private const val HEX_PAIR = 2

        fun hex(bytes: ByteArray): String =
            bytes.joinToString("") { "%02x".format(it.toInt() and BYTE_MASK) }

        private fun unhex(s: String): ByteArray =
            s.chunked(HEX_PAIR).map { it.toInt(HEX_RADIX).toByte() }.toByteArray()
    }
}
