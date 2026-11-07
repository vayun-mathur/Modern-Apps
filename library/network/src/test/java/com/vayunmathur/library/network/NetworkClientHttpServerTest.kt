package com.vayunmathur.library.network

import com.sun.net.httpserver.HttpServer
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.InetSocketAddress
import java.util.zip.GZIPOutputStream
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable

/**
 * Covers the [NetworkClient] entry points that sit on [HttpUrlEngine]
 * (plus [NativeHttpBridge.request]'s single-call path) against a JDK
 * in-process server on 127.0.0.1 — hermetic, no external network.
 * The pooled engine is disabled here so `execute` exercises the
 * HttpURLConnection fallback; pooled routing has its own tests.
 */
class NetworkClientHttpServerTest {

    @Serializable
    data class Widget(val name: String, val size: Int)

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

    private fun respond(path: String, status: Int, body: ByteArray, headers: Map<String, String> = emptyMap()) {
        server.createContext(path) { exchange ->
            exchange.requestBody.use { it.readBytes() }
            headers.forEach { (k, v) -> exchange.responseHeaders.add(k, v) }
            if (body.isEmpty()) {
                exchange.sendResponseHeaders(status, -1)
                exchange.close()
            } else {
                exchange.sendResponseHeaders(status, body.size.toLong())
                exchange.responseBody.use { it.write(body) }
            }
        }
    }

    @Test
    fun performRequestBytesReturnsStatusAndBody() = runBlocking {
        respond("/data", 201, "created-body".toByteArray())
        val (status, bytes) = NetworkClient.performRequestBytes("$baseUrl/data", method = "POST", body = "x")
        assertEquals(201, status)
        assertEquals("created-body", bytes.toString(Charsets.UTF_8))
        Unit
    }

    @Test
    fun performRequestBytesFullReturnsHeaders() = runBlocking {
        respond("/full", 200, "abc".toByteArray(), mapOf("X-Marker" to "m1"))
        val (status, headers, bytes) = NetworkClient.performRequestBytesFull("$baseUrl/full")
        assertEquals(200, status)
        assertEquals("abc", bytes.toString(Charsets.UTF_8))
        assertTrue(headers.entries.any { it.key.equals("X-Marker", ignoreCase = true) && it.value.contains("m1") })
        Unit
    }

    @Test
    fun performRequestDecodesTextAndHeaders() = runBlocking {
        respond("/text", 200, "héllo".toByteArray(Charsets.UTF_8), mapOf("Content-Type" to "text/plain"))
        val response = NetworkClient.performRequest("$baseUrl/text")
        assertEquals(200, response.status)
        assertEquals("héllo", response.body)
        assertEquals("$baseUrl/text", response.url)
        assertEquals("text/plain", response.header("content-type"))
        Unit
    }

    @Test
    fun performRequestSurfacesErrorBodyWithoutThrowing() = runBlocking {
        respond("/oops", 500, "server exploded".toByteArray())
        val response = NetworkClient.performRequest("$baseUrl/oops")
        assertEquals(500, response.status)
        assertEquals("server exploded", response.body)
        Unit
    }

    @Test
    fun executeFallsBackToHttpUrlEngineWhenPoolDisabled() = runBlocking {
        respond("/exec", 200, "via-httpurl".toByteArray())
        val response = NetworkClient.execute("$baseUrl/exec", method = "POST", body = "payload")
        assertEquals(200, response.status)
        assertEquals("via-httpurl", response.text)
        Unit
    }

    @Test
    fun executeWithExplicitFactoryBypassesPool() = runBlocking {
        PooledHttpEngine.USE_POOLED_ENGINE = true
        respond("/exec2", 200, "factory-path".toByteArray())
        val response = NetworkClient.execute(
            "$baseUrl/exec2",
            sslSocketFactory = javax.net.ssl.SSLSocketFactory.getDefault() as javax.net.ssl.SSLSocketFactory,
        )
        assertEquals("factory-path", response.text)
        Unit
    }

    @Test
    fun streamDeliversBytesForSuccess() = runBlocking {
        respond("/stream", 200, "streamed".toByteArray())
        var seen: ByteArray? = null
        val response = NetworkClient.stream("$baseUrl/stream") { stream, _ ->
            val buf = ByteArray(64)
            val n = stream!!.read(buf)
            seen = buf.copyOf(n)
        }
        assertEquals(200, response.status)
        assertEquals("streamed", seen!!.toString(Charsets.UTF_8))
        Unit
    }

    @Test
    fun streamDeliversPartialContent() = runBlocking {
        respond("/partial", 206, "part".toByteArray())
        var seen: String? = null
        val response = NetworkClient.stream("$baseUrl/partial") { stream, _ ->
            val buf = ByteArray(16)
            val n = stream!!.read(buf)
            seen = buf.copyOf(n).toString(Charsets.UTF_8)
        }
        assertEquals(206, response.status)
        assertEquals("part", seen)
        Unit
    }

    @Test
    fun streamReportsErrorBodyPrefixWithoutStream() = runBlocking {
        respond("/errstream", 404, "missing-thing".toByteArray())
        var streamWasNull = false
        var bodySeen: String? = null
        val response = NetworkClient.stream("$baseUrl/errstream") { stream, simple ->
            streamWasNull = stream == null
            bodySeen = simple.body
        }
        assertEquals(404, response.status)
        assertTrue(streamWasNull)
        assertEquals("missing-thing", bodySeen)
        Unit
    }

    @Test
    fun performRequestInputStreamReadsLiveBody() = runBlocking {
        respond("/live", 200, "live-body".toByteArray(), mapOf("X-Live" to "1"))
        val (status, headers, stream) = NetworkClient.performRequestInputStream("$baseUrl/live")
        assertEquals(200, status)
        assertTrue(headers.entries.any { it.key.equals("X-Live", ignoreCase = true) })
        val text = stream.use { it.readBytes().toString(Charsets.UTF_8) }
        assertEquals("live-body", text)
        Unit
    }

    @Test
    fun getContentLengthReadsHead() = runBlocking {
        server.createContext("/sized") { exchange ->
            exchange.responseHeaders.add("Content-Length", "4321")
            exchange.sendResponseHeaders(200, -1)
            exchange.close()
        }
        assertEquals(4321L, NetworkClient.getContentLength("$baseUrl/sized"))
        Unit
    }

    @Test
    fun getContentLengthFollowsRedirect() = runBlocking {
        server.createContext("/len-redir") { exchange ->
            exchange.responseHeaders.add("Location", "$baseUrl/len-target")
            exchange.sendResponseHeaders(302, -1)
            exchange.close()
        }
        server.createContext("/len-target") { exchange ->
            exchange.responseHeaders.add("Content-Length", "99")
            exchange.sendResponseHeaders(200, -1)
            exchange.close()
        }
        assertEquals(99L, NetworkClient.getContentLength("$baseUrl/len-redir"))
        Unit
    }

    @Test
    fun getContentLengthUsesContentRangeFallback() = runBlocking {
        server.createContext("/range-len") { exchange ->
            exchange.responseHeaders.add("Content-Range", "bytes 0-9/777")
            exchange.sendResponseHeaders(200, -1)
            exchange.close()
        }
        assertEquals(777L, NetworkClient.getContentLength("$baseUrl/range-len"))
        Unit
    }

    @Test
    fun getContentLengthMissingHeadersReturnsNullOrZero() = runBlocking {
        server.createContext("/unsized") { exchange ->
            exchange.sendResponseHeaders(200, -1)
            exchange.close()
        }
        val length = NetworkClient.getContentLength("$baseUrl/unsized")
        assertTrue(length == null || length == 0L)
        Unit
    }

    @Test
    fun performRequestToDeadPortWrapsConnectFailure() = runBlocking {
        val dead = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val port = dead.address.port
        dead.stop(0)
        val error = assertFailsWith<IOException> {
            NetworkClient.performRequestBytes("http://127.0.0.1:$port/x")
        }
        assertTrue(error.message!!.contains("Failed to connect"))
        Unit
    }

    @Test
    fun getJsonDecodesSerializable() = runBlocking {
        respond("/widget", 200, """{"name":"gear","size":42,"unknown":true}""".toByteArray())
        val widget = NetworkClient.getJson<Widget>("$baseUrl/widget")
        assertEquals(Widget("gear", 42), widget)
        Unit
    }

    @Test
    fun callJsonPostsAndDecodes() = runBlocking {
        server.createContext("/make") { exchange ->
            val received = exchange.requestBody.readBytes().toString(Charsets.UTF_8)
            assertTrue(received.contains("bolt"))
            val body = """{"name":"bolt","size":3}""".toByteArray()
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        val widget = NetworkClient.callJson<Widget>("$baseUrl/make", method = "POST", body = """{"name":"bolt"}""")
        assertEquals(Widget("bolt", 3), widget)
        Unit
    }

    @Test
    fun callJsonUnitOnNoContent() = runBlocking {
        respond("/nocontent", 204, ByteArray(0))
        NetworkClient.callJson<Unit>("$baseUrl/nocontent")
        Unit
    }

    @Test
    fun callJsonBooleanReflectsSuccess() = runBlocking {
        respond("/boolok", 200, ByteArray(0))
        assertEquals(true, NetworkClient.callJson<Boolean>("$baseUrl/boolok"))
        Unit
    }

    @Test
    fun callJsonUnitOnWhitespaceOnlyBody() = runBlocking {
        respond("/whitespace", 200, "  \n".toByteArray())
        NetworkClient.callJson<Unit>("$baseUrl/whitespace")
        Unit
    }

    @Test
    fun callJsonBooleanOnEmptySuccessBody() = runBlocking {
        respond("/emptybool", 200, ByteArray(0))
        assertEquals(true, NetworkClient.callJson<Boolean>("$baseUrl/emptybool"))
        Unit
    }

    @Test
    fun callJsonErrorThrowsWithBodyPrefix() = runBlocking {
        respond("/jsonerr", 500, "kaboom-detail".toByteArray())
        val error = assertFailsWith<IOException> {
            NetworkClient.callJson<Widget>("$baseUrl/jsonerr")
        }
        assertTrue(error.message!!.contains("500"))
        assertTrue(error.message!!.contains("kaboom-detail"))
        Unit
    }

    @Test
    fun gzipBodyIsDecompressed() = runBlocking {
        val plain = "gzip-hello".toByteArray()
        val compressed = ByteArrayOutputStream().also { out ->
            GZIPOutputStream(out).use { it.write(plain) }
        }.toByteArray()
        respond("/gz", 200, compressed, mapOf("Content-Encoding" to "gzip"))
        val response = NetworkClient.performRequest("$baseUrl/gz")
        assertEquals("gzip-hello", response.body)
        Unit
    }

    @Test
    fun redirect303RewritesPostToGet() = runBlocking {
        var targetMethod: String? = null
        server.createContext("/see-other") { exchange ->
            exchange.requestBody.use { it.readBytes() }
            exchange.responseHeaders.add("Location", "$baseUrl/after-303")
            exchange.sendResponseHeaders(303, -1)
            exchange.close()
        }
        server.createContext("/after-303") { exchange ->
            targetMethod = exchange.requestMethod
            val body = "landed".toByteArray()
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        val response = NetworkClient.performRequest("$baseUrl/see-other", method = "POST", body = "data")
        assertEquals("landed", response.body)
        assertEquals("GET", targetMethod)
        Unit
    }

    @Test
    fun redirectLoopStopsAtMaxHops() = runBlocking {
        server.createContext("/loop") { exchange ->
            exchange.responseHeaders.add("Location", "$baseUrl/loop")
            exchange.sendResponseHeaders(302, -1)
            exchange.close()
        }
        val response = NetworkClient.performRequest("$baseUrl/loop")
        assertEquals(302, response.status)
        Unit
    }

    @Test
    fun nativeBridgeSingleRequestPacksStatusBodyAndHeaders() {
        respond("/native", 200, "native-body".toByteArray(), mapOf("X-Native" to "yes"))
        val packedHeaders = packPairs("X-Sent" to "1", "Accept" to "text/plain")
        val reply = NativeHttpBridge.request(0, "$baseUrl/native", packedHeaders, null)
        val parsed = parseReply(reply)
        assertEquals(200, parsed.status)
        assertEquals("$baseUrl/native", parsed.url)
        assertEquals("native-body", parsed.body.toString(Charsets.UTF_8))
        assertTrue(parsed.headers.keys.any { it.equals("X-Native", ignoreCase = true) })
        Unit
    }

    @Test
    fun nativeBridgeHeadAndPostMethods() {
        server.createContext("/methods") { exchange ->
            val received = exchange.requestBody.readBytes()
            val body = "${exchange.requestMethod}:${received.size}".toByteArray()
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        val post = parseReply(NativeHttpBridge.request(1, "$baseUrl/methods", null, "abc".toByteArray()))
        assertEquals("POST:3", post.body.toString(Charsets.UTF_8))
        val unknown = parseReply(NativeHttpBridge.request(99, "$baseUrl/methods", null, null))
        assertEquals("GET:0", unknown.body.toString(Charsets.UTF_8))
        Unit
    }

    @Test
    fun nativeBridgeFailureIsStatusZero() {
        val dead = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val port = dead.address.port
        dead.stop(0)
        val reply = parseReply(NativeHttpBridge.request(0, "http://127.0.0.1:$port/x", null, null))
        assertEquals(0, reply.status)
        assertTrue(reply.body.isNotEmpty())
        Unit
    }

    @Test
    fun nativeBridgeTruncatedHeadersAreIgnored() {
        respond("/native2", 200, "ok2".toByteArray())
        // One valid pair followed by a dangling name length with no bytes.
        val good = packPairs("X-A" to "b")
        val truncated = good + byteArrayOf(0, 5, 'x'.code.toByte())
        val reply = parseReply(NativeHttpBridge.request(0, "$baseUrl/native2", truncated, null))
        assertEquals(200, reply.status)
        assertEquals("ok2", reply.body.toString(Charsets.UTF_8))
        Unit
    }

    private data class ParsedReply(
        val status: Int,
        val url: String,
        val headers: Map<String, List<String>>,
        val body: ByteArray,
    )

    private fun packPairs(vararg pairs: Pair<String, String>): ByteArray {
        val out = ByteArrayOutputStream()
        for ((name, value) in pairs) {
            val n = name.toByteArray(Charsets.UTF_8)
            val v = value.toByteArray(Charsets.UTF_8)
            out.write((n.size shr 8) and 0xFF); out.write(n.size and 0xFF); out.write(n)
            out.write((v.size shr 8) and 0xFF); out.write(v.size and 0xFF); out.write(v)
        }
        return out.toByteArray()
    }

    private fun parseReply(frame: ByteArray): ParsedReply {
        var pos = 0
        fun u16(): Int {
            val v = ((frame[pos].toInt() and 0xFF) shl 8) or (frame[pos + 1].toInt() and 0xFF)
            pos += 2
            return v
        }
        fun u32(): Int {
            val v = ((frame[pos].toInt() and 0xFF) shl 24) or ((frame[pos + 1].toInt() and 0xFF) shl 16) or
                ((frame[pos + 2].toInt() and 0xFF) shl 8) or (frame[pos + 3].toInt() and 0xFF)
            pos += 4
            return v
        }
        val status = u16()
        val urlLen = u32()
        val url = frame.copyOfRange(pos, pos + urlLen).toString(Charsets.UTF_8); pos += urlLen
        val hdrLen = u32()
        val hdrEnd = pos + hdrLen
        val headers = mutableMapOf<String, MutableList<String>>()
        while (pos < hdrEnd) {
            val nameLen = u16()
            val name = frame.copyOfRange(pos, pos + nameLen).toString(Charsets.UTF_8); pos += nameLen
            val valueLen = u16()
            val value = frame.copyOfRange(pos, pos + valueLen).toString(Charsets.UTF_8); pos += valueLen
            headers.getOrPut(name) { mutableListOf() }.add(value)
        }
        val bodyLen = u32()
        val body = frame.copyOfRange(pos, pos + bodyLen)
        return ParsedReply(status, url, headers, body)
    }
}
