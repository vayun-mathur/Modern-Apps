package com.vayunmathur.communicate.data.signal.transport

import com.vayunmathur.library.log.Log
import com.vayunmathur.library.network.NetworkClient
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.webrtc.PeerConnection
import javax.net.ssl.SSLSocketFactory

/**
 * TURN/STUN relays for calling (`GET /v2/calling/relays`).
 *
 * Without these a call can only use host candidates, so it connects on a shared LAN and fails behind any
 * NAT — which is most real networks. The credentials are short-lived (the response carries a ttl), so they
 * are fetched per call rather than cached.
 */
object SignalCallingApi {
    /** Test seam: localhost base replacing the fixed chat host. */
    internal var chatBaseForTest: String? = null
    private const val TAG = "SignalCallingApi"

    private val json = Json { ignoreUnknownKeys = true }

    suspend fun fetchIceServers(
        authHeader: String,
        sslSocketFactory: SSLSocketFactory?,
    ): List<PeerConnection.IceServer> {
        val resp = try {
            NetworkClient.execute(
                "${chatBaseForTest ?: "https://chat.signal.org"}/v2/calling/relays",
                method = "GET",
                headers = mapOf("Authorization" to "Basic $authHeader"),
                sslSocketFactory = sslSocketFactory,
            )
        } catch (expected: Throwable) {
            Log.status(TAG, "could not fetch calling relays", expected)
            return emptyList()
        }
        if (!resp.isSuccess) {
            Log.status(TAG, "calling relays request failed: ${resp.status} ${resp.statusMessage}")
            return emptyList()
        }
        val servers = parseRelays(resp.text)
        Log.status(TAG, "fetched ${servers.size} ICE servers")
        return servers
    }

    internal fun parseRelays(
        body: String,
        warn: (String) -> Unit = { Log.status(TAG, it) },
    ): List<PeerConnection.IceServer> {
        val relays = try {
            json.parseToJsonElement(body).jsonObject["relays"]?.jsonArray
        } catch (expected: Exception) {
            warn("unparseable calling relays response: ${expected.message}")
            return emptyList()
        } ?: return emptyList()

        return relays.mapNotNull { element ->
            val relay = try { element.jsonObject } catch (_: Exception) { return@mapNotNull null }
            val username = relay["username"]?.jsonPrimitive?.contentOrNull()
            val password = relay["password"]?.jsonPrimitive?.contentOrNull()
            // Prefer urlsWithIps: they skip a DNS lookup, which matters on a call setup path.
            val urls = (relay["urlsWithIps"] ?: relay["urls"])?.let { urlsElement ->
                val parsed = try {
                    urlsElement.jsonArray.mapNotNull { it.jsonPrimitive.contentOrNull() }
                } catch (_: Exception) {
                    null
                }
                parsed
            }?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            val hostname = relay["hostname"]?.jsonPrimitive?.contentOrNull()

            try {
                PeerConnection.IceServer.builder(urls)
                    .apply {
                        if (username != null) setUsername(username)
                        if (password != null) setPassword(password)
                        // Needed when connecting to an IP while the certificate names the host.
                        if (!hostname.isNullOrEmpty()) setHostname(hostname)
                    }
                    .createIceServer()
            } catch (expected: Throwable) {
                warn("skipping an unusable ICE server: ${expected.message}")
                null
            }
        }
    }

    private fun kotlinx.serialization.json.JsonPrimitive.contentOrNull(): String? =
        content.takeIf { it.isNotEmpty() && it != "null" }
}
