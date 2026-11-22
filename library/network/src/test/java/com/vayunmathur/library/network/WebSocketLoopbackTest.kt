package com.vayunmathur.library.network

import com.sun.net.httpserver.HttpServer
import com.vayunmathur.testsupport.awaitWithMainIdle
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.Base64
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Real loopback WebSocket + NetworkClient tests against a raw socket / HttpServer. */
@org.junit.runner.RunWith(org.robolectric.RobolectricTestRunner::class)
@org.robolectric.annotation.Config(sdk = [34])
class WebSocketLoopbackTest {

    private class WsServer(val status: Int = 101) {
        val server = ServerSocket(0, 50, java.net.InetAddress.getByName("127.0.0.1"))
        val port get() = server.localPort
        val received = CopyOnWriteArrayList<Pair<Int, ByteArray>>()
        @Volatile var handshakePath: String? = null
        @Volatile var handshakeHeaders: Map<String, String> = emptyMap()
        fun startRaw(frameHandler: (InputStream, OutputStream) -> Unit) {
            thread(isDaemon = true) {
                try {
                    server.accept().use { sock ->
                        val input = sock.getInputStream(); val output = sock.getOutputStream()
                        val lines = readHandshakeLines(input)
                        handshakePath = lines.firstOrNull()?.split(' ')?.getOrNull(1)
                        handshakeHeaders = lines.drop(1).mapNotNull {
                            val i = it.indexOf(':'); if (i > 0) it.substring(0, i).trim().lowercase() to it.substring(i + 1).trim() else null
                        }.toMap()
                        if (status != 101) {
                            output.write("HTTP/1.1 $status Forbidden\r\nContent-Length: 0\r\n\r\n".toByteArray(Charsets.US_ASCII)); output.flush(); return@use
                        }
                        val key = handshakeHeaders["sec-websocket-key"]!!
                        val accept = Base64.getEncoder().encodeToString(
                            MessageDigest.getInstance("SHA-1").digest((key + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11").toByteArray(Charsets.US_ASCII)),
                        )
                        output.write(
                            ("HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n" +
                                "Sec-WebSocket-Accept: $accept\r\nX-Test-Header: hello-ws\r\nX-Dup: one\r\nX-Dup: two\r\n\r\n")
                                .toByteArray(Charsets.US_ASCII),
                        ); output.flush()
                        frameHandler(input, output)
                    }
                } catch (_: Exception) {
                } finally { runCatching { server.close() } }
            }
        }
        fun close() { runCatching { server.close() } }

        fun readHandshakeLines(input: InputStream): List<String> {
            val headerBytes = ByteArrayOutputStream()
            var matched = 0
            var b = input.read()
            while (b != -1 && matched < 4) {
                headerBytes.write(b)
                matched = when {
                    b == '\r'.code && (matched == 0 || matched == 2) -> matched + 1
                    b == '\n'.code && (matched == 1 || matched == 3) -> matched + 1
                    else -> if (b == '\r'.code) 1 else 0
                }
                if (matched < 4) b = input.read()
            }
            return headerBytes.toString(Charsets.US_ASCII.name()).split("\r\n").filter { it.isNotEmpty() }
        }
    }

    private fun readClientFrame(input: InputStream): Pair<Int, ByteArray>? {
        val b1 = input.read(); if (b1 == -1) return null
        val b2 = input.read(); if (b2 == -1) return null
        val opcode = b1 and 0x0F; val masked = (b2 and 0x80) != 0
        var len = (b2 and 0x7F).toLong()
        when (len) {
            126L -> { val hi = input.read(); val lo = input.read(); len = ((hi shl 8) or lo).toLong() }
            127L -> { val buf = ByteArray(8); input.readNBytes(buf, 0, 8); len = ByteBuffer.wrap(buf).long }
        }
        val mask = if (masked) ByteArray(4).also { input.readNBytes(it, 0, 4) } else null
        val payload = ByteArray(len.toInt()); var off = 0; var r = 0
        while (off < payload.size && r != -1) { r = input.read(payload, off, payload.size - off); if (r != -1) off += r }
        if (mask != null) for (i in payload.indices) payload[i] = (payload[i].toInt() xor mask[i % 4].toInt()).toByte()
        return opcode to payload
    }

    private fun writeServerFrame(output: OutputStream, opcode: Int, payload: ByteArray) {
        val out = ByteArrayOutputStream(); out.write(0x80 or opcode)
        when {
            payload.size < 126 -> out.write(payload.size)
            payload.size <= 0xFFFF -> { out.write(126); out.write((payload.size shr 8) and 0xFF); out.write(payload.size and 0xFF) }
            else -> { out.write(127); out.write(ByteBuffer.allocate(8).putLong(payload.size.toLong()).array()) }
        }
        out.write(payload); output.write(out.toByteArray()); output.flush()
    }

    @Test
    fun webSocketEchoPingPongLengthsAndClose() = runBlocking {
        val srv = WsServer()
        val medium = "m".repeat(300); val large = ByteArray(70_000) { (it % 253).toByte() }
        srv.startRaw { input, output ->
            writeServerFrame(output, 0x1, "welcome".toByteArray())
            writeServerFrame(output, 0x1, medium.toByteArray())
            writeServerFrame(output, 0x2, large)
            writeServerFrame(output, 0x9, ByteArray(0))
            writeServerFrame(output, 0xA, ByteArray(0))
            writeServerFrame(output, 0x3, "ignored".toByteArray())
            writeServerFrame(output, 0x0, "continued".toByteArray())
            var frame = readClientFrame(input)
            while (frame != null) {
                srv.received.add(frame)
                when (frame.first) {
                    0x1, 0x2 -> writeServerFrame(output, frame.first, frame.second)
                    0x9 -> writeServerFrame(output, 0xA, ByteArray(0))
                    0x8 -> writeServerFrame(output, 0x8, frame.second)
                }
                frame = if (frame.first == 0x8) null else readClientFrame(input)
            }
        }
        try {
            var sessionFrames: List<WebSocketClient.WsFrame> = emptyList()
            webSocket(
                "ws://127.0.0.1:${srv.port}/chat?room=1",
                headers = mapOf("X-Client" to "loopback"),
                captureResponseHeaders = listOf("X-Test-Header", "x-dup", "Missing"),
            ) {
                assertEquals("hello-ws", capturedHeader["X-Test-Header"])
                assertEquals(listOf("hello-ws"), responseHeaders["X-Test-Header"])
                send("hello server"); send(byteArrayOf(9, 8, 7)); ping()
                sessionFrames = incoming.take(7).toList()
            }
            // The echoes above prove the server read the text/binary frames,
            // but the client's ping and the close written by webSocket's
            // finally are only *sent* at this point: the daemon server thread
            // may not have read them off the socket yet, and under load it is
            // starved exactly here. Wait for the server's own terminal signal
            // (it has recorded both frames) before asserting on its log.
            awaitWithMainIdle("server received ping and close frames") {
                srv.received.any { it.first == 0x9 } && srv.received.any { it.first == 0x8 }
            }
            assertEquals("/chat?room=1", srv.handshakePath)
            assertEquals("loopback", srv.handshakeHeaders["x-client"])
            val texts = sessionFrames.filterIsInstance<WebSocketClient.WsFrame.Text>().map { it.text }
            assertTrue("welcome" in texts); assertTrue(medium in texts); assertTrue("continued" in texts); assertTrue("hello server" in texts)
            assertTrue(sessionFrames.any { it is WebSocketClient.WsFrame.Pong })
            assertTrue(sessionFrames.filterIsInstance<WebSocketClient.WsFrame.Binary>().any { it.bytes.contentEquals(large) })
            assertTrue(sessionFrames.filterIsInstance<WebSocketClient.WsFrame.Binary>().any { it.bytes.contentEquals(byteArrayOf(9, 8, 7)) })
            assertTrue(srv.received.any { it.first == 0x1 && it.second.toString(Charsets.UTF_8) == "hello server" })
            assertTrue(srv.received.any { it.first == 0x9 })
            assertTrue(srv.received.any { it.first == 0x8 })
        } finally { srv.close() }
    }

    @Test
    fun webSocketDirectClientCloseCodeAndAbort() = runBlocking {
        val srv = WsServer()
        srv.startRaw { input, _ ->
            var f = readClientFrame(input)
            while (f != null) { srv.received.add(f); f = if (f.first == 0x8) null else readClientFrame(input) }
        }
        try {
            val client = WebSocketClient.connect("ws://127.0.0.1:${srv.port}/direct")
            assertFalse(client.isClosed)
            client.send("x".repeat(200))
            client.send(ByteArray(66_000) { 1 })
            client.close(1001, "going away")
            assertTrue(client.isClosed)
            client.close()
            assertFailsWith<java.io.IOException> { client.send("after close") }
            assertFailsWith<java.io.IOException> { client.ping() }
            val closeFrame = srv.received.first { it.first == 0x8 }
            assertEquals(1001, ((closeFrame.second[0].toInt() and 0xFF) shl 8) or (closeFrame.second[1].toInt() and 0xFF))
            assertEquals("going away", closeFrame.second.copyOfRange(2, closeFrame.second.size).toString(Charsets.UTF_8))
        } finally { srv.close() }
        val srv2 = WsServer()
        srv2.startRaw { input, _ -> var f = readClientFrame(input); while (f != null) { f = readClientFrame(input) } }
        try {
            val c2 = WebSocketClient.connect("ws://127.0.0.1:${srv2.port}/a")
            c2.abort(); c2.abort(); assertTrue(c2.isClosed)
        } finally { srv2.close() }
    }

    @Test
    fun webSocketHandshakeFailureCarriesStatus() {
        val srv = WsServer(status = 403)
        srv.startRaw { _, _ -> }
        try {
            val ex = assertFailsWith<WebSocketHandshakeException> {
                runBlocking { WebSocketClient.connect("ws://127.0.0.1:${srv.port}/nope") }
            }
            assertEquals(403, ex.statusCode)
            assertTrue(ex.message!!.contains("403"))
        } finally { srv.close() }
    }

    @Test
    fun webSocketServerCloseFrameEndsFlow() = runBlocking {
        val srv = WsServer()
        srv.startRaw { _, output ->
            writeServerFrame(output, 0x1, "before close".toByteArray())
            val closePayload = byteArrayOf((1000 shr 8).toByte(), (1000 and 0xFF).toByte()) + "bye now".toByteArray()
            writeServerFrame(output, 0x8, closePayload)
            Thread.sleep(200)
        }
        try {
            val client = WebSocketClient.connect("ws://127.0.0.1:${srv.port}/c")
            val frames = client.incomingFlow().toList()
            assertEquals(2, frames.size)
            assertEquals(WebSocketClient.WsFrame.Text("before close"), frames[0])
            val close = frames[1] as WebSocketClient.WsFrame.Close
            assertEquals(1000, close.code); assertEquals("bye now", close.reason); assertTrue(client.isClosed)
        } finally { srv.close() }
    }

    @Serializable data class Echo(val name: String, val count: Int)

    @Test
    fun networkClientLoopbackAllEntryPoints() = runBlocking {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val base = "http://127.0.0.1:${server.address.port}"
        server.createContext("/text") { ex ->
            val body = ex.requestBody.readBytes().toString(Charsets.UTF_8)
            ex.responseHeaders.add("X-Req-Method", ex.requestMethod)
            val out = "hello:$body".take(11).padEnd(11).toByteArray()
            ex.sendResponseHeaders(201, out.size.toLong()); ex.responseBody.use { it.write(out) }; ex.close()
        }
        server.createContext("/json") { ex -> val b = """{"name":"loop","count":42,"extra":true}""".toByteArray(); ex.sendResponseHeaders(200, b.size.toLong()); ex.responseBody.use { it.write(b) }; ex.close() }
        server.createContext("/empty") { ex -> ex.sendResponseHeaders(204, -1); ex.close() }
        server.createContext("/error") { ex -> val b = "server exploded".toByteArray(); ex.sendResponseHeaders(500, b.size.toLong()); ex.responseBody.use { it.write(b) }; ex.close() }
        server.createContext("/head") { ex -> ex.responseHeaders.add("Content-Length", "12345"); ex.sendResponseHeaders(200, -1); ex.close() }
        server.createContext("/redir") { ex -> ex.responseHeaders.add("Location", "/head"); ex.sendResponseHeaders(302, -1); ex.close() }
        server.start()
        try {
            NetworkClient.initWithFactory(null)
            val simple = NetworkClient.performRequest("$base/text", method = "POST", body = "abc")
            assertEquals(201, simple.status); assertTrue(simple.isSuccess); assertEquals("hello:abc  ", simple.body)
            assertEquals("POST", simple.header("x-req-method")); assertEquals(11L, simple.contentLength)
            val (status, bytes) = NetworkClient.performRequestBytes("$base/text", method = "POST", body = byteArrayOf(1, 2, 3))
            assertEquals(201, status); assertTrue(bytes.isNotEmpty())
            val (s2, headers, b2) = NetworkClient.performRequestBytesFull("$base/json")
            assertEquals(200, s2); assertTrue(headers.isNotEmpty()); assertTrue(b2.toString(Charsets.UTF_8).contains("loop"))
            val raw = NetworkClient.execute("$base/json", headers = mapOf("Accept" to "application/json"))
            assertEquals(200, raw.status); assertTrue(raw.text.contains("loop")); assertTrue(raw.isSuccess)
            val echo: Echo = NetworkClient.getJson("$base/json")
            assertEquals(Echo("loop", 42), echo)
            val echo2: Echo = NetworkClient.callJson("$base/json", method = "POST", body = "{}")
            assertEquals(42, echo2.count)
            assertEquals(Unit, NetworkClient.callJson<Unit>("$base/empty"))
            val ex = assertFailsWith<java.io.IOException> { NetworkClient.getJson<Echo>("$base/error") }
            assertTrue(ex.message!!.contains("500"))
            var streamed = ""
            val okResp = NetworkClient.stream("$base/json") { stream, _ ->
                val buf = ByteArray(64); val n = stream!!.read(buf); streamed = String(buf, 0, n, Charsets.UTF_8)
            }
            assertEquals(200, okResp.status); assertTrue(streamed.contains("loop"))
            var errBody: String? = null
            val errResp = NetworkClient.stream("$base/error") { stream, resp -> assertEquals(null, stream); errBody = resp.body }
            assertEquals(500, errResp.status); assertEquals("server exploded", errBody)
            val (code, _, stream) = NetworkClient.performRequestInputStream("$base/json")
            assertEquals(200, code); stream.use { assertTrue(it.readBytes().isNotEmpty()) }
            assertEquals(12345L, NetworkClient.getContentLength("$base/head"))
            assertEquals(12345L, NetworkClient.getContentLength("$base/redir"))
            val ds = "abcdef".toByteArray().asNetworkDataStream()
            assertFalse(ds.isClosedForRead)
            val buf = ByteArray(4); assertEquals(4, ds.read(buf)); assertEquals("abcd", String(buf, Charsets.UTF_8))
            assertEquals(2, ds.read(buf)); assertTrue(ds.isClosedForRead); assertEquals(-1, ds.read(buf))
            val rr = RawResponse(200, "OK", "x".toByteArray(), mapOf("Set-Cookie" to listOf("a=1", "b=2")), "u")
            assertEquals("a=1", rr.header("set-cookie")); assertEquals(listOf("a=1", "b=2"), rr.headerValues("SET-COOKIE")); assertEquals("x", rr.text)
        } finally { server.stop(0) }
    }
}
