package com.vayunmathur.library.network

import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.MessageDigest
import java.util.Base64
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Covers [WebSocketClient] / [WsSession] / [webSocket] against a raw loopback
 * RFC 6455 peer. The peer is a plain [ServerSocket] on 127.0.0.1 speaking just
 * enough of the handshake and frame codec to drive the client deterministically
 * — no external network, no extra test dependency. Robolectric supplies the
 * real `android.util.Base64` the handshake depends on.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class WebSocketLoopbackTest {

    private data class ServerFrame(val opcode: Int, val payload: ByteArray)

    /** One-shot loopback WS peer. [script] runs on the peer thread after accept. */
    private class WsPeer(
        private val handshakeStatus: String = "HTTP/1.1 101 Switching Protocols",
        private val extraHeaders: Map<String, String> = emptyMap(),
        private val corruptAccept: Boolean = false,
        private val closeImmediately: Boolean = false,
        private val script: (InputStream, OutputStream) -> Unit,
    ) {
        private val server = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        val port: Int = server.localPort
        val requestLines = CopyOnWriteArrayList<String>()
        @Volatile var failure: Throwable? = null
        private val done = CountDownLatch(1)

        fun start(): WsPeer {
            thread(isDaemon = true) {
                try {
                    server.accept().use { socket ->
                        socket.soTimeout = 15_000
                        if (closeImmediately) return@use
                        handshake(socket)
                        script(socket.getInputStream(), socket.getOutputStream())
                    }
                } catch (t: Throwable) {
                    failure = t
                } finally {
                    done.countDown()
                    runCatching { server.close() }
                }
            }
            return this
        }

        private fun handshake(socket: Socket) {
            val input = socket.getInputStream()
            val raw = ByteArrayOutputStream()
            var matched = 0
            val terminator = byteArrayOf('\r'.code.toByte(), '\n'.code.toByte(), '\r'.code.toByte(), '\n'.code.toByte())
            while (matched < terminator.size) {
                val b = input.read()
                check(b >= 0) { "EOF during handshake read" }
                raw.write(b)
                matched = if (b.toByte() == terminator[matched]) matched + 1 else if (b.toByte() == terminator[0]) 1 else 0
            }
            val text = raw.toString(Charsets.US_ASCII.name())
            requestLines.addAll(text.split("\r\n").filter { it.isNotEmpty() })
            val key = requestLines.firstOrNull { it.startsWith("Sec-WebSocket-Key:", ignoreCase = true) }
                ?.substringAfter(':')?.trim().orEmpty()
            val accept = if (corruptAccept) {
                "bogus-accept-value"
            } else {
                Base64.getEncoder().encodeToString(
                    MessageDigest.getInstance("SHA-1")
                        .digest((key + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11").toByteArray(Charsets.US_ASCII)),
                )
            }
            val out = socket.getOutputStream()
            val head = buildString {
                append(handshakeStatus).append("\r\n")
                if (handshakeStatus.contains("101")) {
                    append("Upgrade: websocket\r\nConnection: Upgrade\r\n")
                    append("Sec-WebSocket-Accept: ").append(accept).append("\r\n")
                }
                extraHeaders.forEach { (k, v) -> append(k).append(": ").append(v).append("\r\n") }
                append("\r\n")
            }
            out.write(head.toByteArray(Charsets.US_ASCII))
            out.flush()
        }

        fun awaitDone() {
            assertTrue(done.await(20, TimeUnit.SECONDS), "peer script finished")
            failure?.let { throw AssertionError("peer failed: ${it.message}", it) }
        }

        fun closeServer() = runCatching { server.close() }
    }

    private fun readOrEof(input: InputStream, message: String): Int {
        val b = input.read()
        if (b < 0) throw EOFException(message)
        return b
    }

    private fun readExact(input: InputStream, size: Int): ByteArray {
        val payload = ByteArray(size)
        var off = 0
        while (off < payload.size) {
            val n = input.read(payload, off, payload.size - off)
            if (n < 0) throw EOFException("eof payload")
            off += n
        }
        return payload
    }

    private fun readFrame(input: InputStream): ServerFrame {
        val b1 = readOrEof(input, "eof b1")
        val b2 = readOrEof(input, "eof b2")
        var len = (b2 and 0x7F).toLong()
        if (len == 126L) {
            val hi = input.read(); val lo = input.read()
            len = ((hi shl 8) or lo).toLong()
        } else if (len == 127L) {
            len = 0
            repeat(8) { len = (len shl 8) or input.read().toLong() }
        }
        val masked = (b2 and 0x80) != 0
        val mask = if (masked) ByteArray(4) { input.read().toByte() } else null
        val payload = readExact(input, len.toInt())
        if (mask != null) {
            for (i in payload.indices) {
                payload[i] = (payload[i].toInt() xor mask[i % 4].toInt()).toByte()
            }
        }
        return ServerFrame(b1 and 0x0F, payload)
    }

    private fun writeFrame(out: OutputStream, opcode: Int, payload: ByteArray, masked: Boolean = false) {
        val head = ByteArrayOutputStream()
        head.write(0x80 or opcode)
        val maskBit = if (masked) 0x80 else 0
        when {
            payload.size < 126 -> head.write(maskBit or payload.size)
            payload.size <= 0xFFFF -> {
                head.write(maskBit or 126)
                head.write((payload.size shr 8) and 0xFF)
                head.write(payload.size and 0xFF)
            }
            else -> {
                head.write(maskBit or 127)
                val v = payload.size.toLong()
                for (shift in 56 downTo 0 step 8) head.write(((v shr shift) and 0xFF).toInt())
            }
        }
        synchronized(out) {
            if (masked) {
                val mask = byteArrayOf(0x11, 0x22, 0x33, 0x44)
                head.write(mask)
                out.write(head.toByteArray())
                out.write(ByteArray(payload.size) { (payload[it].toInt() xor mask[it % 4].toInt()).toByte() })
            } else {
                out.write(head.toByteArray())
                out.write(payload)
            }
            out.flush()
        }
    }

    private fun url(peer: WsPeer, path: String = "/chat?room=1") = "ws://127.0.0.1:${peer.port}$path"

    @Test
    fun receivesTextBinaryPongAndCloseFrames() = runBlocking {
        val peer = WsPeer(extraHeaders = mapOf("X-Test" to "yes")) { _, out ->
            writeFrame(out, 0x1, "hello".toByteArray())
            writeFrame(out, 0x2, byteArrayOf(1, 2, 3))
            writeFrame(out, 0xA, ByteArray(0))
            writeFrame(out, 0x8, byteArrayOf(0x03, 0xE9.toByte()) + "bye".toByteArray())
        }.start()
        try {
            val client = WebSocketClient.connect(
                url(peer),
                headers = mapOf("Sec-WebSocket-Protocol" to "chat"),
                captureResponseHeaders = listOf("X-Test"),
            )
            assertEquals("yes", client.capturedHeaders["X-Test"])
            assertTrue(client.responseHeaders.keys.any { it.equals("X-Test", ignoreCase = true) })
            val frames = client.incomingFlow().toList()
            assertEquals(4, frames.size)
            assertEquals(WebSocketClient.WsFrame.Text("hello"), frames[0])
            assertTrue((frames[1] as WebSocketClient.WsFrame.Binary).bytes.contentEquals(byteArrayOf(1, 2, 3)))
            assertEquals(WebSocketClient.WsFrame.Pong, frames[2])
            assertEquals(WebSocketClient.WsFrame.Close(1001, "bye"), frames[3])
            assertTrue(client.isClosed)
            // Handshake request carried the path, protocol and non-default-port Host.
            assertTrue(peer.requestLines.first().startsWith("GET /chat?room=1 "))
            assertTrue(peer.requestLines.any { it.equals("Sec-WebSocket-Protocol: chat", ignoreCase = true) })
            assertTrue(peer.requestLines.any { it.startsWith("Host: 127.0.0.1:") })
            Unit
        } finally {
            peer.awaitDone()
        }
    }

    @Test
    fun sendsTextBinaryPingAndCloseWithReason() = runBlocking {
        val received = CopyOnWriteArrayList<ServerFrame>()
        val peer = WsPeer { input, _ ->
            repeat(4) { received.add(readFrame(input)) }
        }.start()
        try {
            val client = WebSocketClient.connect(url(peer))
            client.send("ping-text")
            client.send(byteArrayOf(9, 8, 7))
            client.ping()
            client.close(1000, "done")
            assertTrue(client.isClosed)
            assertFailsWith<IOException> { client.send("after close") }
            assertFailsWith<IOException> { client.send(byteArrayOf(1)) }
            assertFailsWith<IOException> { client.ping() }
            // close() is idempotent.
            client.close()
            peer.awaitDone()
            assertEquals(listOf(0x1, 0x2, 0x9, 0x8), received.map { it.opcode })
            assertEquals("ping-text", received[0].payload.toString(Charsets.UTF_8))
            assertTrue(received[1].payload.contentEquals(byteArrayOf(9, 8, 7)))
            assertEquals(0, received[2].payload.size)
            val closePayload = received[3].payload
            assertEquals(1000, ((closePayload[0].toInt() and 0xFF) shl 8) or (closePayload[1].toInt() and 0xFF))
            assertEquals("done", closePayload.copyOfRange(2, closePayload.size).toString(Charsets.UTF_8))
            Unit
        } finally {
            peer.closeServer()
        }
    }

    @Test
    fun extendedLengthFramesRoundTrip() = runBlocking {
        val medium = "m".repeat(300)
        val large = ByteArray(70_000) { (it % 251).toByte() }
        val peer = WsPeer { input, out ->
            writeFrame(out, 0x1, medium.toByteArray())
            writeFrame(out, 0x2, large)
            val echoed = readFrame(input)
            assertEquals(0x1, echoed.opcode)
            assertEquals(medium, echoed.payload.toString(Charsets.UTF_8))
            writeFrame(out, 0x8, byteArrayOf(0x03, 0xE8.toByte()))
        }.start()
        try {
            val client = WebSocketClient.connect(url(peer))
            val collector = async { client.incomingFlow().toList() }
            // Send only after peer wrote its frames; ordering on the wire is
            // peer->client frames first, so collect first two via flow below.
            client.send(medium)
            val frames = collector.await()
            assertEquals(WebSocketClient.WsFrame.Text(medium), frames[0])
            assertTrue((frames[1] as WebSocketClient.WsFrame.Binary).bytes.contentEquals(large))
            assertEquals(WebSocketClient.WsFrame.Close(1000, ""), frames[2])
            Unit
        } finally {
            peer.awaitDone()
        }
    }

    @Test
    fun serverPingIsAutoPongedAndMaskedFramesDecode() = runBlocking {
        val peer = WsPeer { input, out ->
            writeFrame(out, 0x9, ByteArray(0))
            val pong = readFrame(input)
            assertEquals(0xA, pong.opcode)
            assertEquals(0, pong.payload.size)
            writeFrame(out, 0x1, "masked!".toByteArray(), masked = true)
            writeFrame(out, 0x8, byteArrayOf(0x03, 0xE8.toByte()))
        }.start()
        try {
            val client = WebSocketClient.connect(url(peer))
            val frames = client.incomingFlow().toList()
            assertEquals(listOf(WebSocketClient.WsFrame.Text("masked!"), WebSocketClient.WsFrame.Close(1000, "")), frames)
            Unit
        } finally {
            peer.awaitDone()
        }
    }

    @Test
    fun continuationAndUnknownOpcodesMapPerCodec() = runBlocking {
        val peer = WsPeer { input, out ->
            writeFrame(out, 0x0, "cont".toByteArray())
            writeFrame(out, 0x3, "mystery".toByteArray())
            // Unknown opcodes decode as Ping, which is auto-ponged, not emitted.
            val pong = readFrame(input)
            assertEquals(0xA, pong.opcode)
            assertEquals(0, pong.payload.size)
            writeFrame(out, 0x8, byteArrayOf())
        }.start()
        try {
            val client = WebSocketClient.connect(url(peer))
            val frames = client.incomingFlow().toList()
            assertEquals(WebSocketClient.WsFrame.Text("cont"), frames[0])
            // A close frame with no payload defaults to the normal code.
            assertEquals(WebSocketClient.WsFrame.Close(1000, ""), frames[1])
            assertEquals(2, frames.size)
            Unit
        } finally {
            peer.awaitDone()
        }
    }

    @Test
    fun rejectedHandshakeThrowsStatusException() {
        val peer = WsPeer(handshakeStatus = "HTTP/1.1 403 Forbidden") { _, _ -> }.start()
        try {
            val error = assertFailsWith<WebSocketHandshakeException> {
                runBlocking { WebSocketClient.connect(url(peer)) }
            }
            assertEquals(403, error.statusCode)
            assertTrue(error.message!!.contains("403"))
            Unit
        } finally {
            peer.awaitDone()
        }
    }

    @Test
    fun emptyHandshakeResponseThrows() {
        val peer = WsPeer(closeImmediately = true) { _, _ -> }.start()
        try {
            assertFailsWith<IOException> {
                runBlocking { WebSocketClient.connect(url(peer)) }
            }
            Unit
        } finally {
            peer.awaitDone()
        }
    }

    @Test
    fun mismatchedAcceptStillConnects() = runBlocking {
        val peer = WsPeer(corruptAccept = true) { input, _ ->
            readFrame(input) // client's close frame
        }.start()
        try {
            // No path in the URL: the handshake must default to "/".
            val client = WebSocketClient.connect("ws://127.0.0.1:${peer.port}")
            client.close()
            assertTrue(client.isClosed)
            assertTrue(peer.requestLines.first().startsWith("GET / "))
            Unit
        } finally {
            peer.awaitDone()
        }
    }

    @Test
    fun webSocketSessionEchoAndPing() = runBlocking {
        val peer = WsPeer { input, out ->
            var open = true
            while (open) {
                val frame = readFrame(input)
                when (frame.opcode) {
                    0x1 -> writeFrame(out, 0x1, ("echo:" + frame.payload.toString(Charsets.UTF_8)).toByteArray())
                    0x8 -> open = false
                }
            }
        }.start()
        try {
            var seen: WebSocketClient.WsFrame? = null
            webSocket(url(peer)) {
                send("hi")
                seen = incoming.first()
                ping()
                assertEquals(emptyMap(), capturedHeader)
                assertTrue(responseHeaders.isNotEmpty())
                close()
            }
            assertEquals(WebSocketClient.WsFrame.Text("echo:hi"), seen)
            Unit
        } finally {
            peer.awaitDone()
        }
    }

    @Test
    fun sessionSendBytesAndAbort() = runBlocking {
        val received = CopyOnWriteArrayList<ServerFrame>()
        val peer = WsPeer { input, _ ->
            try {
                while (true) received.add(readFrame(input))
            } catch (_: Exception) {
                // Client abort closes the socket mid-read.
            }
        }.start()
        try {
            val client = WebSocketClient.connect(url(peer))
            val session = WsSession(client)
            session.send(byteArrayOf(4, 5, 6))
            session.send("via-session")
            session.abort()
            assertTrue(client.isClosed)
            assertFailsWith<IOException> { session.send("gone") }
            peer.awaitDone()
            assertEquals(listOf(0x2, 0x1), received.map { it.opcode })
            assertTrue(received[0].payload.contentEquals(byteArrayOf(4, 5, 6)))
            assertEquals("via-session", received[1].payload.toString(Charsets.UTF_8))
            Unit
        } finally {
            peer.closeServer()
        }
    }
}
