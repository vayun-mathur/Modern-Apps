package com.vayunmathur.madeveloper.data

import com.vayunmathur.library.network.NetworkClient
import com.vayunmathur.library.network.WebSocketClient
import com.vayunmathur.madeveloper.domain.DevMessage
import com.vayunmathur.madeveloper.domain.DevProtocol
import com.vayunmathur.madeveloper.domain.DevRegistration
import java.io.IOException
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

/**
 * Registers this device with the server (`POST /dev/register`). The server
 * returns the pairing code the owner approves in its `/dev` panel and the
 * push token the socket later presents as a bearer token; until approval
 * the device is `pending` server-side and the socket is refused with 403.
 */
class DevRegistrar(
    // The one network seam: how a URL + JSON body becomes its reply text.
    private val poster: suspend (String, String) -> String = { url, body -> postViaNetwork(url, body) },
) {
    /** Registers [deviceName] and returns the server-issued credentials. */
    suspend fun register(deviceName: String): DevRegistration {
        val body = poster(GitHostRepository.DEV_REGISTER_URL, DevProtocol.registerRequestBody(deviceName))
        return DevProtocol.parseRegistration(body)
            ?: throw IOException("Malformed /dev/register reply")
    }
}

/** Production [DevRegistrar] post: NetworkClient (FIRST_PARTY, set by the activity). */
private suspend fun postViaNetwork(url: String, body: String): String {
    val headers = mapOf("Content-Type" to "application/json", "Accept" to "application/json")
    val response = NetworkClient.performRequest(url, method = "POST", headers = headers, body = body)
    if (!response.isSuccess) throw IOException("HTTP " + response.status + " for " + url)
    return response.body
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
