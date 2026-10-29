package com.vayunmathur.everysync.core.network

import com.vayunmathur.everysync.core.platform.SecureStore
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import java.io.ByteArrayInputStream
import java.security.KeyStore
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

/**
 * `RelayHttpClient` over OkHttp3 with FIRST_PARTY pinning (ISRG X1/X2 +
 * GTS R1-R4 for `api.maproj.org`). Relay hosts migrated from findfamily.cc
 * to api.maproj.org; same Cloudflare pins cover both — findfamily.cc remains
 * valid for backward compatibility during the transition.
 *
 * TWIN: `androidMain/.../OkHttpRelayClient.kt` is the same file for Android
 * (classloader resources + OkHttp are identical on both). Keep them in sync;
 * the only intended difference is the factory + SecureStore wiring below.
 */
class OkHttpRelayClient private constructor(
    private val client: OkHttpClient,
) : RelayHttpClient {

    override suspend fun post(url: String, jsonBody: String): RelayHttpResponse {
        val request = Request.Builder()
            .url(url)
            .post(jsonBody.toRequestBody(JSON_MEDIA_TYPE))
            .header("Content-Type", "application/json")
            .build()
        client.newCall(request).execute().use { response ->
            return RelayHttpResponse(response.code, response.body?.string().orEmpty())
        }
    }

    override fun subscribe(wsUrl: String, channel: String): Flow<String> = callbackFlow {
        val request = Request.Builder().url(wsUrl).build()
        val socket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: okhttp3.Response) {
                webSocket.send("""{"t":"sub","channel":"$channel"}""")
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                trySend(text)
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                trySend(bytes.utf8())
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: okhttp3.Response?) {
                close(t)
            }
        })
        awaitClose { socket.close(1000, null) }
    }

    companion object {
        private val JSON_MEDIA_TYPE = "application/json".toMediaType()

        /** Builds a pinned client: FIRST_PARTY DERs from classloader resources + system issuers. */
        fun pinned(): OkHttpRelayClient {
            val pins = FirstPartyPins.fileNames.mapNotNull { name ->
                loadResource("ca/$name")
            }
            val trustManager = FirstPartyPins.trustManager(pins)
            val sslContext = SSLContext.getInstance("TLS").apply {
                init(null, arrayOf(trustManager), null)
            }
            val client = OkHttpClient.Builder()
                .sslSocketFactory(sslContext.socketFactory, trustManager)
                .build()
            return OkHttpRelayClient(client)
        }

        private fun loadResource(path: String): ByteArray? =
            runCatching {
                OkHttpRelayClient::class.java.classLoader?.getResourceAsStream(path)?.use { it.readBytes() }
            }.getOrNull()
    }
}

/**
 * FIRST_PARTY pinning roots (ISRG X1/X2 + GTS R1-R4) for `api.maproj.org`.
 * Relay hosts migrated from findfamily.cc to api.maproj.org; same Cloudflare
 * pins cover both — findfamily.cc remains valid for backward compatibility
 * during the transition. Copied by `syncCaResources` into
 * `src/commonMain/resources/ca/`.
 */
object FirstPartyPins {
    val fileNames = listOf(
        "isrgrootx1.der",
        "isrgrootx2.der",
        "gts-root-r1.der",
        "gts-root-r2.der",
        "gts-root-r3.der",
        "gts-root-r4.der",
    )

    /**
     * Builds a trust manager over the pinned roots plus system issuers
     * (rotation headroom — a freshly rotated relay cert chains to a public
     * root while pins catch up). When [pinnedDer] is empty (resources missing
     * from the jar), falls back to system trust rather than failing closed
     * at the TLS layer — the relay is untrusted anyway; E2EE does not depend
     * on TLS.
     */
    fun trustManager(pinnedDer: List<ByteArray>): X509TrustManager {
        val cf = CertificateFactory.getInstance("X.509")
        val ks = KeyStore.getInstance(KeyStore.getDefaultType()).apply { load(null, null) }
        pinnedDer.forEachIndexed { idx, der ->
            val cert = cf.generateCertificate(ByteArrayInputStream(der)) as X509Certificate
            ks.setCertificateEntry("pin-$idx", cert)
        }
        TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
            .apply { init(null as KeyStore?) }
            .trustManagers.filterIsInstance<X509TrustManager>()
            .flatMap { it.acceptedIssuers.toList() }
            .forEachIndexed { idx, cert -> ks.setCertificateEntry("system-$idx", cert) }
        val tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
            .apply { init(ks) }
        return tmf.trustManagers.first { it is X509TrustManager } as X509TrustManager
    }
}

/** Desktop JVM entry point: pinned transport over the caller's store + production PQC. */
class JvmRelayTransport(
    store: SecureStore,
    crypto: PqcCrypto = JvmPqcCrypto(),
    private val http: RelayHttpClient = OkHttpRelayClient.pinned(),
) : SharedRelayTransport(store, crypto) {
    override fun client(): RelayHttpClient = http
}
