package com.vayunmathur.cast.tv.platform

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.sun.net.httpserver.HttpsServer
import com.vayunmathur.cast.protocol.ContentSession
import com.vayunmathur.cast.protocol.EphemeralTls
import com.vayunmathur.cast.protocol.ProtocolBase64
import java.net.InetAddress
import java.net.InetSocketAddress
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * ArtworkFetcher against a real local HTTPS server presenting an ephemeral
 * certificate: the pinned fetch succeeds and a non-200 answer fails soft.
 */
@RunWith(RobolectricTestRunner::class)
@org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.NATIVE)
class ArtworkFetchDeepTest {

    private val ctx: Context get() = ApplicationProvider.getApplicationContext()

    init {
        // Loopback must not go through the corporate proxy injected via JAVA_TOOL_OPTIONS.
        System.setProperty("http.nonProxyHosts", "127.0.0.1|localhost")
        System.setProperty("https.nonProxyHosts", "127.0.0.1|localhost")
    }

    /** A 2x2 PNG written by ImageIO, so the bytes are a valid image by construction. */
    private val pngBytes: ByteArray by lazy {
        val image = java.awt.image.BufferedImage(2, 2, java.awt.image.BufferedImage.TYPE_INT_ARGB)
        val out = java.io.ByteArrayOutputStream()
        javax.imageio.ImageIO.write(image, "png", out)
        out.toByteArray()
    }

    private fun withServer(
        status: Int,
        block: (port: Int, fingerprint: String) -> Unit,
    ) {
        val credentials = EphemeralTls.server(listOf(InetAddress.getByName("127.0.0.1")))
        val server = HttpsServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.setHttpsConfigurator(com.sun.net.httpserver.HttpsConfigurator(credentials.sslContext))
        server.createContext("/") { exchange ->
            val body = if (status == 200) pngBytes else ByteArray(0)
            exchange.sendResponseHeaders(status, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        server.start()
        try {
            block(server.address.port, ProtocolBase64.encode(credentials.fingerprint))
        } finally {
            server.stop(0)
        }
    }

    @Test fun fetchDecodesPngFromPinnedServer() {
        withServer(200) { port, fingerprint ->
            val fetcher = ArtworkFetcher(
                ContentSession(
                    host = "127.0.0.1", port = port,
                    certificateFingerprint = fingerprint,
                    token = "tok", video = true,
                ),
            )
            val bitmap = fetcher.fetch("cover-9")
            assertNotNull(bitmap)
            assertEqualsDims(bitmap.width, bitmap.height)
        }
    }

    @Test fun fetchReturnsNullOnHttpError() {
        withServer(404) { port, fingerprint ->
            val fetcher = ArtworkFetcher(
                ContentSession(
                    host = "127.0.0.1", port = port,
                    certificateFingerprint = fingerprint,
                    token = "tok", video = true,
                ),
            )
            assertNull(fetcher.fetch("missing"))
        }
    }

    private fun assertEqualsDims(width: Int, height: Int) {
        kotlin.test.assertEquals(2, width)
        kotlin.test.assertEquals(2, height)
    }
}
