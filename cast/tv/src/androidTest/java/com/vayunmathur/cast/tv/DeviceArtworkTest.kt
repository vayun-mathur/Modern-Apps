package com.vayunmathur.cast.tv

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.vayunmathur.cast.protocol.ContentSession
import com.vayunmathur.cast.protocol.EphemeralTls
import com.vayunmathur.cast.tv.platform.ArtworkFetcher
import java.net.InetAddress
import javax.net.ssl.SSLServerSocket
import kotlin.concurrent.thread
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Artwork fetch against a real pinned-TLS loopback server: real sockets, real bitmap decode. */
@RunWith(AndroidJUnit4::class)
class DeviceArtworkTest {
    private val pngBytes: ByteArray = java.io.ByteArrayOutputStream().also { out ->
        android.graphics.Bitmap.createBitmap(4, 4, android.graphics.Bitmap.Config.ARGB_8888)
            .apply { eraseColor(android.graphics.Color.MAGENTA) }
            .compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out)
    }.toByteArray()

    private fun serveOnce(status: Int, body: ByteArray): ContentSession {
        val creds = EphemeralTls.server(listOf(InetAddress.getByName("127.0.0.1")))
        val server = creds.sslContext.serverSocketFactory.createServerSocket(0, 1, InetAddress.getByName("127.0.0.1")) as SSLServerSocket
        thread(isDaemon = true) {
            runCatching {
                server.accept().use { socket ->
                    val reader = socket.inputStream.bufferedReader()
                    while (true) { val line = reader.readLine(); if (line.isNullOrBlank()) break }
                    val out = socket.outputStream
                    out.write("HTTP/1.1 $status X\r\nContent-Type: image/png\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n".toByteArray())
                    out.write(body); out.flush(); Thread.sleep(500)
                }
            }
            runCatching { server.close() }
        }
        return ContentSession(host = "127.0.0.1", port = server.localPort, certificateFingerprint = com.vayunmathur.cast.protocol.ProtocolBase64.encode(creds.fingerprint), token = "tok", video = true, appLabel = "App")
    }

    @Test fun fetchDecodesPngFromPinnedLocalServer() {
        val session = serveOnce(200, pngBytes)
        val bitmap = ArtworkFetcher(session).fetch("art1")
        assertNotNull(bitmap); assertTrue(bitmap!!.width > 0)
    }

    @Test fun fetchReturnsNullOnHttpErrorAndGarbage() {
        assertNull(ArtworkFetcher(serveOnce(404, byteArrayOf(1, 2))).fetch("missing"))
        assertNull(ArtworkFetcher(serveOnce(200, byteArrayOf(9, 9, 9))).fetch("garbage"))
    }
}
