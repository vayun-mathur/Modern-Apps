package com.vayunmathur.cast.platform

import com.vayunmathur.cast.protocol.EphemeralTls
import com.vayunmathur.cast.protocol.MediaResource
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.net.InetAddress
import javax.net.ssl.SSLSocket
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** The serving half of the proxy: real TLS sockets against a real resource. */
class MediaProxyServeTest {

    private class BytesResource(val data: ByteArray, override val contentType: String = "audio/mpeg") :
        MediaResource {
        override val length: Long = data.size.toLong()
        override fun open(offset: Long): InputStream =
            ByteArrayInputStream(data, offset.toInt(), (data.size - offset).toInt())
    }

    /** One response as head + body; the proxy keeps connections alive, so read by Content-Length. */
    private fun readResponse(socket: SSLSocket): ByteArray {
        val input = socket.inputStream
        val head = java.io.ByteArrayOutputStream()
        val window = IntArray(4)
        while (true) {
            val b = input.read()
            check(b >= 0) { "eof in head" }
            head.write(b)
            window[0] = window[1]; window[1] = window[2]; window[2] = window[3]; window[3] = b
            if (window.contentEquals(intArrayOf('\r'.code, '\n'.code, '\r'.code, '\n'.code))) break
        }
        val headText = head.toString(Charsets.ISO_8859_1)
        val length = Regex("Content-Length: (\\d+)", RegexOption.IGNORE_CASE)
            .find(headText)?.groupValues?.get(1)?.toInt() ?: 0
        val body = ByteArray(length)
        var read = 0
        while (read < length) {
            val n = input.read(body, read, length - read)
            check(n >= 0) { "eof in body" }
            read += n
        }
        return head.toByteArray() + body
    }

    private fun get(port: Int, fingerprint: ByteArray, token: String, path: String): String {
        val socket = EphemeralTls.client(fingerprint)
            .socketFactory.createSocket(InetAddress.getByName("127.0.0.1"), port) as SSLSocket
        return socket.use {
            it.soTimeout = 5_000
            it.startHandshake()
            it.outputStream.write(
                "GET /$token/$path HTTP/1.1\r\nHost: 127.0.0.1\r\n\r\n"
                    .toByteArray(Charsets.ISO_8859_1),
            )
            it.outputStream.flush()
            readResponse(it).toString(Charsets.ISO_8859_1)
        }
    }

    @Test
    fun `serves a resource over tls and refuses a wrong token`() {
        val data = "hello cast proxy world".toByteArray()
        val token = MediaProxyServer.randomToken()
        val server = MediaProxyServer(token) { id ->
            if (id == "song") BytesResource(data) else null
        }
        val endpoint = assertNotNull(server.start(listOf(InetAddress.getByName("127.0.0.1"))))
        try {
            val ok = get(endpoint.port, endpoint.certificateFingerprint, token, "song")
            assertTrue(ok.startsWith("HTTP/1.1 200"), ok.lineSequence().firstOrNull())
            assertTrue(ok.endsWith("hello cast proxy world"), ok.takeLast(40))

            val refused = get(endpoint.port, endpoint.certificateFingerprint, "wrong-token", "song")
            assertTrue(
                refused.startsWith("HTTP/1.1 4") || refused.startsWith("HTTP/1.1 5"),
                refused.lineSequence().firstOrNull(),
            )

            val missing = get(endpoint.port, endpoint.certificateFingerprint, token, "nope")
            assertTrue(
                missing.startsWith("HTTP/1.1 4") || missing.startsWith("HTTP/1.1 5"),
                missing.lineSequence().firstOrNull(),
            )
        } finally {
            server.stop()
        }
        assertEquals(0, server.openConnections)
    }

    private fun rawRequest(port: Int, fingerprint: ByteArray, request: String): ByteArray {
        val socket = EphemeralTls.client(fingerprint)
            .socketFactory.createSocket(InetAddress.getByName("127.0.0.1"), port) as SSLSocket
        return socket.use {
            it.soTimeout = 5_000
            it.startHandshake()
            it.outputStream.write(request.toByteArray(Charsets.ISO_8859_1))
            it.outputStream.flush()
            readResponse(it)
        }
    }

    @Test
    fun `head answers without a body and malformed requests are refused`() {
        val data = "body-bytes".toByteArray()
        val token = MediaProxyServer.randomToken()
        val server = MediaProxyServer(token) { BytesResource(data) }
        val endpoint = assertNotNull(server.start(listOf(InetAddress.getByName("127.0.0.1"))))
        try {
            val headSocket = EphemeralTls.client(endpoint.certificateFingerprint)
                .socketFactory.createSocket(InetAddress.getByName("127.0.0.1"), endpoint.port) as SSLSocket
            val head = headSocket.use {
                it.soTimeout = 5_000
                it.startHandshake()
                it.outputStream.write(
                    "HEAD /$token/song HTTP/1.1\r\nHost: x\r\n\r\n"
                        .toByteArray(Charsets.ISO_8859_1),
                )
                it.outputStream.flush()
                val bytes = java.io.ByteArrayOutputStream()
                val buf = ByteArray(1024)
                // Head only ends at the blank line; a body must NOT follow, so
                // read exactly until then and stop (Content-Length is a lie here).
                while (!bytes.toString(Charsets.ISO_8859_1).endsWith("\r\n\r\n")) {
                    val n = it.inputStream.read(buf)
                    kotlin.test.assertTrue(n > 0, "eof inside head")
                    bytes.write(buf, 0, n)
                }
                bytes.toString(Charsets.ISO_8859_1)
            }
            kotlin.test.assertTrue(head.startsWith("HTTP/1.1 200"), head.lineSequence().firstOrNull())
            kotlin.test.assertTrue(head.contains("Content-Length: ${data.size}"))
            kotlin.test.assertFalse(head.endsWith("body-bytes"))

            val malformed = rawRequest(
                endpoint.port, endpoint.certificateFingerprint,
                "GARBAGE WITH NO SENSE\r\n\r\n",
            ).toString(Charsets.ISO_8859_1)
            kotlin.test.assertTrue(
                malformed.startsWith("HTTP/1.1 4") || malformed.isEmpty(),
                malformed.lineSequence().firstOrNull(),
            )
        } finally {
            server.stop()
        }
    }

    @Test
    fun `one connection serves two requests and the seventeenth is refused`() {
        val data = "keep-alive-body".toByteArray()
        val token = MediaProxyServer.randomToken()
        val server = MediaProxyServer(token) { BytesResource(data) }
        val endpoint = assertNotNull(server.start(listOf(InetAddress.getByName("127.0.0.1"))))
        val held = mutableListOf<java.net.Socket>()
        try {
            val socket = EphemeralTls.client(endpoint.certificateFingerprint)
                .socketFactory.createSocket(InetAddress.getByName("127.0.0.1"), endpoint.port) as SSLSocket
            socket.use {
                it.soTimeout = 5_000
                it.startHandshake()
                for (path in listOf("a", "b")) {
                    it.outputStream.write(
                        "GET /$token/$path HTTP/1.1\r\nHost: x\r\n\r\n"
                            .toByteArray(Charsets.ISO_8859_1),
                    )
                    it.outputStream.flush()
                    val response = readResponse(it).toString(Charsets.ISO_8859_1)
                    kotlin.test.assertTrue(response.startsWith("HTTP/1.1 200"), response.lineSequence().firstOrNull())
                    kotlin.test.assertTrue(response.endsWith("keep-alive-body"))
                }
            }

            // Fill every slot with sockets parked in their TLS handshake, then
            // one more: admission is refused and the socket is closed for us.
            repeat(16) {
                held += java.net.Socket(InetAddress.getByName("127.0.0.1"), endpoint.port)
            }
            val deadline = System.currentTimeMillis() + 5_000
            while (server.openConnections < 16 && System.currentTimeMillis() < deadline) {
                Thread.sleep(20)
            }
            kotlin.test.assertEquals(16, server.openConnections)
            val extra = java.net.Socket(InetAddress.getByName("127.0.0.1"), endpoint.port)
            extra.soTimeout = 5_000
            val firstRead = runCatching { extra.getInputStream().read() }.getOrElse { -2 }
            kotlin.test.assertTrue(firstRead == -1 || firstRead == -2, "refused socket read $firstRead")
            extra.close()
        } finally {
            held.forEach { runCatching { it.close() } }
            server.stop()
        }
    }

    @Test
    fun `a resource that ends early truncates the response`() {
        val lying = object : MediaResource {
            override val length: Long = 100
            override val contentType: String = "audio/mpeg"
            override fun open(offset: Long): InputStream =
                java.io.ByteArrayInputStream(ByteArray(10) { 1 })
        }
        val token = MediaProxyServer.randomToken()
        val server = MediaProxyServer(token) { lying }
        val endpoint = assertNotNull(server.start(listOf(InetAddress.getByName("127.0.0.1"))))
        try {
            val socket = EphemeralTls.client(endpoint.certificateFingerprint)
                .socketFactory.createSocket(InetAddress.getByName("127.0.0.1"), endpoint.port) as SSLSocket
            val response = socket.use {
                it.soTimeout = 5_000
                it.startHandshake()
                it.outputStream.write(
                    "GET /$token/song HTTP/1.1\r\nHost: x\r\n\r\n"
                        .toByteArray(Charsets.ISO_8859_1),
                )
                it.outputStream.flush()
                // The connection closes after the short body: read to EOF.
                val out = java.io.ByteArrayOutputStream()
                val buf = ByteArray(1024)
                var n = it.inputStream.read(buf)
                while (n >= 0) {
                    out.write(buf, 0, n)
                    n = it.inputStream.read(buf)
                }
                out.toByteArray()
            }
            val text = response.toString(Charsets.ISO_8859_1)
            kotlin.test.assertTrue(text.startsWith("HTTP/1.1 200"), text.lineSequence().firstOrNull())
            kotlin.test.assertTrue(text.contains("Content-Length: 100"))
        } finally {
            server.stop()
        }
    }

    @Test
    fun `range request returns the requested slice`() {
        val data = ByteArray(1_000) { (it % 251).toByte() }
        val token = MediaProxyServer.randomToken()
        val server = MediaProxyServer(token) { BytesResource(data, "video/mp4") }
        val endpoint = assertNotNull(server.start(listOf(InetAddress.getByName("127.0.0.1"))))
        try {
            val socket = EphemeralTls.client(endpoint.certificateFingerprint)
                .socketFactory.createSocket(InetAddress.getByName("127.0.0.1"), endpoint.port) as SSLSocket
            val response = socket.use {
                it.soTimeout = 5_000
                it.startHandshake()
                it.outputStream.write(
                    "GET /$token/vid HTTP/1.1\r\nHost: x\r\nRange: bytes=100-199\r\n\r\n"
                        .toByteArray(Charsets.ISO_8859_1),
                )
                it.outputStream.flush()
                readResponse(it)
            }
            val text = response.toString(Charsets.ISO_8859_1)
            assertTrue(text.startsWith("HTTP/1.1 206"), text.lineSequence().firstOrNull())
            val body = response.copyOfRange(response.size - 100, response.size)
            assertTrue(data.copyOfRange(100, 200).contentEquals(body))
        } finally {
            server.stop()
        }
    }
}
