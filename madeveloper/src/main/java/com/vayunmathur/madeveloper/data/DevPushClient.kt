package com.vayunmathur.madeveloper.data

import com.vayunmathur.library.network.WebSocketClient
import com.vayunmathur.madeveloper.domain.DevMessage
import com.vayunmathur.madeveloper.domain.DevProtocol
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.mapNotNull

/** Transport seam for the dev push socket, so protocol logic tests never open a socket. */
interface DevSocketTransport {
    /** Inbound server messages, already parsed. */
    val messages: Flow<DevMessage>
    /** Sends one text frame. */
    suspend fun send(text: String)
    /** Closes the socket. */
    suspend fun close()
}

/** [DevSocketTransport] over the shared [WebSocketClient]. */
class WebSocketDevTransport private constructor(private val client: WebSocketClient) : DevSocketTransport {
    override val messages: Flow<DevMessage> = client.incomingFlow().mapNotNull { frame ->
        (frame as? WebSocketClient.WsFrame.Text)?.let { DevProtocol.parse(it.text) }
    }
    override suspend fun send(text: String) = client.send(text)
    override suspend fun close() = client.close()

    /** Opens the socket to [url] with an optional bearer [token]. */
    companion object {
        suspend fun connect(url: String, token: String?): WebSocketDevTransport {
            val headers = if (token.isNullOrBlank()) emptyMap() else mapOf("Authorization" to "Bearer $token")
            return WebSocketDevTransport(WebSocketClient.connect(url, headers))
        }
    }
}
