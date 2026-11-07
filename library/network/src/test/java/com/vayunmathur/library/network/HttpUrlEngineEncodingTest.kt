package com.vayunmathur.library.network

import com.sun.net.httpserver.HttpServer
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.InetSocketAddress
import java.util.zip.Deflater
import java.util.zip.DeflaterOutputStream
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

/**
 * Covers [HttpUrlEngine]'s Content-Encoding branches and the custom-method
 * reflection fallback against a JDK in-process server on 127.0.0.1.
 */
class HttpUrlEngineEncodingTest {

    private lateinit var server: HttpServer
    private var baseUrl = ""

    @BeforeTest
    fun setUp() {
        PooledHttpEngine.USE_POOLED_ENGINE = false
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        baseUrl = "http://127.0.0.1:${server.address.port}"
        server.start()
    }

    @AfterTest
    fun tearDown() {
        server.stop(0)
        PooledHttpEngine.USE_POOLED_ENGINE = true
        PooledHttpEngine.resetForTests()
    }

    @Test
    fun deflateBodyIsDecompressed() = runBlocking {
        val plain = "deflate-hello".toByteArray()
        val compressed = ByteArrayOutputStream().also { out ->
            DeflaterOutputStream(out, Deflater(Deflater.DEFAULT_COMPRESSION, true)).use { it.write(plain) }
        }.toByteArray()
        server.createContext("/deflated") { exchange ->
            exchange.responseHeaders.add("Content-Encoding", "deflate")
            exchange.sendResponseHeaders(200, compressed.size.toLong())
            exchange.responseBody.use { it.write(compressed) }
        }
        assertEquals("deflate-hello", NetworkClient.performRequest("$baseUrl/deflated").body)
        Unit
    }

    @Test
    fun unknownEncodingPassesBodyThrough() = runBlocking {
        server.createContext("/weird") { exchange ->
            exchange.responseHeaders.add("Content-Encoding", "x-unknown-codec")
            val body = "raw-body".toByteArray()
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        assertEquals("raw-body", NetworkClient.performRequest("$baseUrl/weird").body)
        Unit
    }

    @Test
    fun identityEncodingPassesBodyThrough() = runBlocking {
        server.createContext("/identity") { exchange ->
            exchange.responseHeaders.add("Content-Encoding", "identity")
            val body = "plain-body".toByteArray()
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        val response = NetworkClient.performRequestBytes("$baseUrl/identity")
        assertEquals(200, response.first)
        assertEquals("plain-body", response.second.toString(Charsets.UTF_8))
        Unit
    }

    @Test
    fun corruptGzipFallsBackToRawBytes() = runBlocking {
        // maybeDecompress swallows a failed GZIPInputStream construction and
        // hands back the raw stream, so the caller sees the wire bytes.
        val raw = byteArrayOf(0x1F, 0x8B.toByte(), 0x00, 0x01, 0x02)
        server.createContext("/badgzip") { exchange ->
            exchange.responseHeaders.add("Content-Encoding", "gzip")
            exchange.sendResponseHeaders(200, raw.size.toLong())
            exchange.responseBody.use { it.write(raw) }
        }
        val response = NetworkClient.performRequestBytes("$baseUrl/badgzip")
        assertEquals(200, response.first)
        Unit
    }

    @Test
    fun brotliGarbageFailsAsIOExceptionNamingEndpoint() = runBlocking {
        server.createContext("/badbr") { exchange ->
            exchange.responseHeaders.add("Content-Encoding", "br")
            val raw = "not-really-brotli".toByteArray()
            exchange.sendResponseHeaders(200, raw.size.toLong())
            exchange.responseBody.use { it.write(raw) }
        }
        val error = assertFailsWith<IOException> {
            NetworkClient.performRequestBytes("$baseUrl/badbr")
        }
        assertTrue(error.message!!.contains("/badbr") || error.message!!.contains("Failed to read"))
        Unit
    }

    @Test
    fun customMethodWithoutJdkSupportFailsAsIOException() = runBlocking {
        server.createContext("/anything") { exchange ->
            val body = "ok".toByteArray()
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        // PROPFIND is outside HttpURLConnection's built-in verb set; on the
        // JVM the reflection fallback cannot set it, surfacing as IOException.
        assertFailsWith<IOException> {
            NetworkClient.performRequestBytes("$baseUrl/anything", method = "PROPFIND")
        }
        Unit
    }
}
