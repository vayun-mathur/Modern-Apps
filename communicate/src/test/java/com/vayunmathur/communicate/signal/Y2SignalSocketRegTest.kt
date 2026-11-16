package com.vayunmathur.communicate.signal

import androidx.test.core.app.ApplicationProvider
import com.google.protobuf.ByteString
import com.sun.net.httpserver.HttpServer
import com.vayunmathur.communicate.data.signal.SignalAuthData
import com.vayunmathur.communicate.data.signal.SignalGroups
import com.vayunmathur.communicate.data.signal.registration.RegistrationHttpClient
import com.vayunmathur.communicate.data.signal.registration.SignalRegistrationKeys
import com.vayunmathur.communicate.data.signal.transport.SignalAttachmentUpload
import com.vayunmathur.communicate.data.signal.transport.SignalCdsi
import com.vayunmathur.communicate.data.signal.transport.SignalGroupsApi
import com.vayunmathur.communicate.data.signal.transport.SignalKeysApi
import com.vayunmathur.communicate.data.signal.transport.SignalSocket
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.MessageDigest
import java.util.Collections
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.signal.libsignal.zkgroup.groups.GroupSecretParams
import signal.proto.chat_websocket.SignalChatWebsocket.WebSocketMessage
import signal.proto.chat_websocket.SignalChatWebsocket.WebSocketRequestMessage
import signal.proto.chat_websocket.SignalChatWebsocket.WebSocketResponseMessage

/**
 * Y2 wave-1: SignalSocket against a raw RFC6455 server on 127.0.0.1
 * (HTTP upgrade + Sec-WebSocket-Accept, masked client frames, unmasked
 * binary WebSocketMessage replies), private plumbing via reflection, and
 * RegistrationHttpClient branches not covered by the pass-3 suite, plus
 * parse/decrypt edges for Groups/Keys/CDSI/Attachment APIs whose network
 * entry points are hardcoded to https://chat.signal.org / storage hosts
 * (no baseUrl seam, so their live fetch paths are unreachable offline).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class Y2SignalSocketRegTest {

    private val ctx get() = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val aci = "3f0e5a2c-1111-2222-3333-444455556666"
    private val pni = "4f0e5a2c-1111-2222-3333-444455556666"

    private fun auth(aciValue: String = aci, password: String = "pw-secret", authenticatedAci: Boolean = true) =
        SignalAuthData(
            phoneNumber = "+14155552671",
            aci = if (authenticatedAci) aciValue else "",
            pni = pni, deviceId = 3, password = password, registered = true,
        )

    // ---------------- raw WebSocket server ----------------

    private class RawWsServer(
        private val closeCode: Int? = null,
    ) : AutoCloseable {
        private val server = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
        val port: Int = server.localPort
        val handshakes = Collections.synchronizedList(mutableListOf<String>())
        val frames = Collections.synchronizedList(mutableListOf<Pair<Int, ByteArray>>())
        @Volatile var acceptCount = 0
        @Volatile private var clientOut: OutputStream? = null
        @Volatile private var running = true
        private val thread = Thread {
            while (running) {
                try {
                    val s = server.accept()
                    acceptCount++
                    Thread { handle(s) }.start()
                } catch (_: Exception) { break }
            }
        }.apply { isDaemon = true; start() }

        private fun handle(s: Socket) {
            try {
                val input = s.getInputStream()
                val output = s.getOutputStream()
                clientOut = output
                sendUpgrade(input, output)
                if (closeAfterHandshake(output, s)) return
                serveFrames(input, output)
            } catch (_: Exception) {
            } finally {
                runCatching { s.close() }
            }
        }

        private fun sendUpgrade(input: InputStream, output: OutputStream) {
            val header = readHandshake(input)
            handshakes.add(header)
            val key = Regex("Sec-WebSocket-Key:\\s*(\\S+)").find(header)?.groupValues?.get(1) ?: ""
            val accept = java.util.Base64.getEncoder().encodeToString(
                MessageDigest.getInstance("SHA-1")
                    .digest((key + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11").toByteArray(Charsets.US_ASCII)))
            output.write(
                ("HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n" +
                    "Sec-WebSocket-Accept: $accept\r\nx-signal-timestamp: 1700000000\r\n\r\n")
                    .toByteArray(Charsets.US_ASCII))
            output.flush()
        }

        private fun closeAfterHandshake(output: OutputStream, s: Socket): Boolean {
            val code = closeCode ?: return false
            Thread.sleep(120)
            writeFrame(output, 0x8, byteArrayOf((code shr 8).toByte(), code.toByte()))
            Thread.sleep(120)
            s.close()
            return true
        }

        private fun serveFrames(input: InputStream, output: OutputStream) {
            var open = true
            while (open) {
                val frame = readFrame(input)
                if (frame == null || frame.first == 0x8) {
                    open = false
                } else {
                    frames.add(frame)
                    maybeReply(output, frame)
                }
            }
        }

        private fun maybeReply(output: OutputStream, frame: Pair<Int, ByteArray>) {
            if (frame.first != 0x2 && frame.first != 0x1) return
            val msg = runCatching { WebSocketMessage.parseFrom(frame.second) }.getOrNull() ?: return
            if (msg.type != WebSocketMessage.Type.REQUEST || !msg.hasRequest()) return
            val req = msg.request
            if (req.path.contains("noreply")) return
            val resp = WebSocketMessage.newBuilder()
                .setType(WebSocketMessage.Type.RESPONSE)
                .setResponse(
                    WebSocketResponseMessage.newBuilder()
                        .setId(req.id).setStatus(200).setMessage("OK")
                        .setBody(ByteString.copyFrom("resp:".toByteArray() + req.body.toByteArray()))
                        .build())
                .build()
            synchronized(output) { writeFrame(output, 0x2, resp.toByteArray()) }
        }

        private fun readHandshake(input: InputStream): String {
            val buf = StringBuilder()
            var matched = 0
            val end = "\r\n\r\n"
            while (matched < 4) {
                val b = input.read()
                if (b == -1) break
                buf.append(b.toChar())
                matched = if (b.toChar() == end[matched]) matched + 1 else if (b.toChar() == '\r') 1 else 0
            }
            return buf.toString()
        }

        private fun readFrame(input: InputStream): Pair<Int, ByteArray>? {
            val b1 = input.read(); if (b1 == -1) return null
            val b2 = input.read(); if (b2 == -1) return null
            val opcode = b1 and 0x0F
            val masked = (b2 and 0x80) != 0
            var len = (b2 and 0x7F).toLong()
            if (len == 126L) {
                len = ((input.read() shl 8) or input.read()).toLong()
            } else if (len == 127L) {
                var v = 0L; repeat(8) { v = (v shl 8) or input.read().toLong() }; len = v
            }
            val mask = if (masked) ByteArray(4) { input.read().toByte() } else null
            val payload = ByteArray(len.toInt())
            var off = 0
            while (off < payload.size) {
                val n = input.read(payload, off, payload.size - off)
                if (n == -1) return null
                off += n
            }
            if (mask != null) for (i in payload.indices) {
                payload[i] = (payload[i].toInt() xor mask[i % 4].toInt()).toByte()
            }
            return opcode to payload
        }

        fun writeFrame(output: OutputStream, opcode: Int, payload: ByteArray) {
            val out = java.io.ByteArrayOutputStream()
            out.write(0x80 or opcode)
            when {
                payload.size < 126 -> out.write(payload.size)
                payload.size <= 0xFFFF -> { out.write(126); out.write(payload.size shr 8); out.write(payload.size) }
                else -> { out.write(127); repeat(8) { out.write((payload.size.toLong() shr (56 - it * 8)).toInt()) } }
            }
            out.write(payload)
            output.write(out.toByteArray()); output.flush()
        }

        fun pushToClient(bytes: ByteArray) {
            val out = clientOut ?: error("no client yet")
            synchronized(out) { writeFrame(out, 0x2, bytes) }
        }

        fun requestFrames(): List<WebSocketRequestMessage> = synchronized(frames) {
            frames.mapNotNull { (_, payload) ->
                runCatching { WebSocketMessage.parseFrom(payload) }.getOrNull()
                    ?.takeIf { it.type == WebSocketMessage.Type.REQUEST && it.hasRequest() }?.request
            }
        }

        override fun close() {
            running = false
            runCatching { server.close() }
        }
    }

    private fun await(label: String, timeoutMs: Long = 12_000, cond: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (cond()) return
            Thread.sleep(20)
        }
        error("timeout $label")
    }

    private fun socketFor(port: Int, authenticated: Boolean = true, aciValue: String = aci, password: String = "pw-secret", passwordOverride: String? = null) =
        SignalSocket(
            context = ctx, authData = auth(aciValue, password, authenticated),
            host = "127.0.0.1", port = port, useTls = false,
            passwordOverride = passwordOverride, authenticated = authenticated,
        )

    @Test
    fun `raw websocket handshake keepalive and raw sends`() = runBlocking {
        RawWsServer().use { server ->
            val socket = socketFor(server.port)
            val states = Collections.synchronizedList(mutableListOf<SignalSocket.ConnectionState>())
            val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
            val stateJob = scope.async { socket.connectionState.collect { states.add(it) } }
            socket.connect()
            await("connected") { states.any { it is SignalSocket.ConnectionState.Connected } }

            // Handshake carried Basic aci.deviceId auth.
            await("handshake") { server.handshakes.isNotEmpty() }
            val hs = server.handshakes.first()
            assertTrue(hs.contains("GET /v1/websocket/"), hs)
            val authLine = hs.lines().first { it.startsWith("Authorization:") }
            val decoded = String(
                java.util.Base64.getDecoder().decode(authLine.substringAfter("Basic ").trim()),
                Charsets.UTF_8)
            assertEquals("$aci.3:pw-secret", decoded)
            assertTrue(hs.contains("X-Signal-Receive-Stories: true"), hs)

            // The immediate keepalive has the real Signal shape.
            await("keepalive") { server.requestFrames().any { it.path == "/v1/keepalive" } }
            val keepalive = server.requestFrames().first { it.path == "/v1/keepalive" }
            assertEquals("PUT", keepalive.verb); assertTrue(keepalive.id != 0L)

            // Raw binary + text sends arrive as frames.
            assertTrue(socket.send(byteArrayOf(9, 8, 7)))
            assertTrue(socket.sendText("plain-text"))
            await("raw frames") {
                server.frames.any { it.second.contentEquals(byteArrayOf(9, 8, 7)) } &&
                    server.frames.any { it.second.decodeToString() == "plain-text" }
            }
            socket.disconnect()
            stateJob.cancel()
        }
    }

    @Test
    fun `raw websocket request response push and disconnect`() = runBlocking {
        RawWsServer().use { server ->
            val socket = socketFor(server.port)
            val states = Collections.synchronizedList(mutableListOf<SignalSocket.ConnectionState>())
            val inbox = Collections.synchronizedList(mutableListOf<ByteArray>())
            val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
            val stateJob = scope.async { socket.connectionState.collect { states.add(it) } }
            val msgJob = scope.async { socket.messages.collect { inbox.add(it) } }
            socket.connect()
            await("connected") { states.any { it is SignalSocket.ConnectionState.Connected } }

            // Request/response correlation: generated id and explicit id.
            val req = WebSocketRequestMessage.newBuilder()
                .setVerb("GET").setPath("/v1/test").setBody(ByteString.copyFrom("hello".toByteArray())).build()
            val result = socket.sendRequestAwaitingResponse(req, timeoutMs = 8_000)
            assertNotNull(result); assertTrue(result.isSuccess); assertEquals(200, result.status)
            assertEquals("resp:hello", result.body.decodeToString())
            val req2 = WebSocketRequestMessage.newBuilder()
                .setVerb("POST").setPath("/v1/test2").setId(4242L).build()
            assertEquals(200, socket.sendRequestAwaitingResponse(req2, timeoutMs = 8_000)?.status)
            assertTrue(server.requestFrames().any { it.id == 4242L })

            // sendResponse is framed as a RESPONSE message.
            assertTrue(socket.sendResponse(
                WebSocketResponseMessage.newBuilder().setId(77L).setStatus(204).build()))
            await("response frame") {
                synchronized(server.frames) {
                    server.frames.any { (_, p) ->
                        runCatching { WebSocketMessage.parseFrom(p) }.getOrNull()
                            ?.let { it.type == WebSocketMessage.Type.RESPONSE && it.response.id == 77L } == true
                    }
                }
            }

            // A server-pushed request lands on the messages flow verbatim.
            val push = WebSocketMessage.newBuilder()
                .setType(WebSocketMessage.Type.REQUEST)
                .setRequest(WebSocketRequestMessage.newBuilder()
                    .setId(999L).setVerb("GET").setPath("/v1/messages").build())
                .build().toByteArray()
            server.pushToClient(push)
            await("push") { inbox.any { it.contentEquals(push) } }

            socket.disconnect()
            await("disconnected state") {
                states.any { it is SignalSocket.ConnectionState.Disconnected && it.reason == "client disconnect" }
            }
            assertFalse(socket.send(byteArrayOf(1)))
            stateJob.cancel(); msgJob.cancel()
        }
    }

    @Test
    fun `request timeout returns null without leaking the pending entry`() = runBlocking {
        RawWsServer().use { server ->
            val socket = socketFor(server.port)
            val states = Collections.synchronizedList(mutableListOf<SignalSocket.ConnectionState>())
            val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
            val job = scope.async { socket.connectionState.collect { states.add(it) } }
            socket.connect()
            await("connected") { states.any { it is SignalSocket.ConnectionState.Connected } }
            val start = System.currentTimeMillis()
            val result = socket.sendRequestAwaitingResponse(
                WebSocketRequestMessage.newBuilder().setVerb("GET").setPath("/noreply").build(),
                timeoutMs = 400L)
            val elapsed = System.currentTimeMillis() - start
            assertNull(result)
            assertTrue(elapsed < 6_000, "elapsed ${elapsed}ms")
            val pending = getField<Map<*, *>>(socket, "pending")
            assertTrue(pending.isEmpty(), "pending leaked: $pending")
            socket.disconnect(); job.cancel()
        }
    }

    @Test
    fun `close 4401 surfaces invalid auth and stops the reconnect loop`() {
        RawWsServer(closeCode = 4401).use { server ->
            val socket = socketFor(server.port)
            val states = Collections.synchronizedList(mutableListOf<SignalSocket.ConnectionState>())
            val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
            val job = scope.async { socket.connectionState.collect { states.add(it) } }
            socket.connect()
            await("4401 disconnected") {
                states.any { it is SignalSocket.ConnectionState.Disconnected && it.reason.contains("4401") }
            }
            val accepts = server.acceptCount
            Thread.sleep(1_500)
            assertEquals(accepts, server.acceptCount, "4401 must not reconnect")
            socket.disconnect(); job.cancel()
        }
    }

    @Test
    fun `unauthenticated socket sends no credentials on the wire`() {
        RawWsServer().use { server ->
            val socket = socketFor(server.port, authenticated = false)
            val states = Collections.synchronizedList(mutableListOf<SignalSocket.ConnectionState>())
            val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
            val job = scope.async { socket.connectionState.collect { states.add(it) } }
            socket.connect()
            await("connected") { states.any { it is SignalSocket.ConnectionState.Connected } }
            await("handshake") { server.handshakes.isNotEmpty() }
            assertFalse(server.handshakes.first().contains("Authorization:"), server.handshakes.first())
            socket.disconnect(); job.cancel()
        }
    }

    // ---------------- reflection plumbing ----------------

    @Suppress("UNCHECKED_CAST")
    private fun <T> getField(target: Any, name: String): T {
        val f = target.javaClass.getDeclaredField(name).apply { isAccessible = true }
        return f.get(target) as T
    }

    private fun invoke(target: Any, name: String, vararg args: Pair<Class<*>, Any?>): Any? {
        val m = target.javaClass.getDeclaredMethod(name, *args.map { it.first }.toTypedArray())
            .apply { isAccessible = true }
        return m.invoke(target, *args.map { it.second }.toTypedArray())
    }

    @Test
    fun `wsUrl authHeaders and close error mapping via reflection`() {
        val insecure = SignalSocket(ctx, auth(), host = "example.test", port = 8080, useTls = false)
        assertEquals("ws://example.test:8080/v1/websocket/", invoke(insecure, "wsUrl"))
        val secure = SignalSocket(ctx, auth(), host = "example.test", port = 443, useTls = true)
        assertEquals("wss://example.test:443/v1/websocket/", invoke(secure, "wsUrl"))

        val headers = invoke(insecure, "authHeaders") as Map<*, *>
        val basic = headers["Authorization"] as String
        val decoded = String(
            android.util.Base64.decode(basic.removePrefix("Basic "), android.util.Base64.NO_WRAP),
            Charsets.UTF_8)
        assertEquals("$aci.3:pw-secret", decoded)
        assertEquals("true", headers["X-Signal-Receive-Stories"])

        // Phone-number login when ACI is empty, override password wins, unauth has no credentials.
        val phone = SignalSocket(ctx, auth(authenticatedAci = false), host = "h", port = 1, useTls = false)
        val phoneHeaders = invoke(phone, "authHeaders") as Map<*, *>
        val phoneDecoded = String(
            android.util.Base64.decode(
                (phoneHeaders["Authorization"] as String).removePrefix("Basic "),
                android.util.Base64.NO_WRAP), Charsets.UTF_8)
        assertEquals("+14155552671:pw-secret", phoneDecoded)
        val override = SignalSocket(
            ctx, auth(), host = "h", port = 1, useTls = false, passwordOverride = "override-pw")
        val overrideHeaders = invoke(override, "authHeaders") as Map<*, *>
        val overrideDecoded = String(
            android.util.Base64.decode(
                (overrideHeaders["Authorization"] as String).removePrefix("Basic "),
                android.util.Base64.NO_WRAP), Charsets.UTF_8)
        assertEquals("$aci.3:override-pw", overrideDecoded)
        val unauth = SignalSocket(ctx, auth(), host = "h", port = 1, useTls = false, authenticated = false)
        val unauthHeaders = invoke(unauth, "authHeaders") as Map<*, *>
        assertFalse(unauthHeaders.containsKey("Authorization"))
        assertTrue(unauthHeaders.containsKey("User-Agent"))

        assertEquals("ws close 4401 invalid auth",
            (invoke(insecure, "closeError", Int::class.java to 4401) as Throwable).message)
        assertEquals("ws close 4409 connected elsewhere",
            (invoke(insecure, "closeError", Int::class.java to 4409) as Throwable).message)
        assertEquals("ws close 1006",
            (invoke(insecure, "closeError", Int::class.java to 1006) as Throwable).message)
        val id1 = invoke(insecure, "nextRequestId") as Long
        val id2 = invoke(insecure, "nextRequestId") as Long
        assertTrue(id1 != 0L && id2 != 0L && id1 != id2)
    }

    @Test
    fun `completePending and failAllPending resolve only matching waiters`() = runBlocking {
        val socket = socketFor(port = 9)
        val pending: ConcurrentHashMap<Long, CompletableDeferred<WebSocketResponseMessage?>> =
            getField(socket, "pending")
        val waiter = CompletableDeferred<WebSocketResponseMessage?>()
        pending[55L] = waiter
        // Garbage, a REQUEST frame, and an unknown id must not complete it.
        invoke(socket, "completePending", ByteArray::class.java to byteArrayOf(1, 2, 3))
        val requestMsg = WebSocketMessage.newBuilder()
            .setType(WebSocketMessage.Type.REQUEST)
            .setRequest(WebSocketRequestMessage.newBuilder().setId(55L).build()).build().toByteArray()
        invoke(socket, "completePending", ByteArray::class.java to requestMsg)
        val unknown = WebSocketMessage.newBuilder()
            .setType(WebSocketMessage.Type.RESPONSE)
            .setResponse(WebSocketResponseMessage.newBuilder().setId(99L).setStatus(200).build())
            .build().toByteArray()
        invoke(socket, "completePending", ByteArray::class.java to unknown)
        assertFalse(waiter.isCompleted)
        val mine = WebSocketMessage.newBuilder()
            .setType(WebSocketMessage.Type.RESPONSE)
            .setResponse(WebSocketResponseMessage.newBuilder().setId(55L).setStatus(201).setMessage("made").build())
            .build().toByteArray()
        invoke(socket, "completePending", ByteArray::class.java to mine)
        assertEquals(201, waiter.await()?.status)
        assertTrue(pending.isEmpty())

        val w1 = CompletableDeferred<WebSocketResponseMessage?>()
        val w2 = CompletableDeferred<WebSocketResponseMessage?>()
        pending[1L] = w1; pending[2L] = w2
        invoke(socket, "failAllPending")
        assertNull(w1.await()); assertNull(w2.await()); assertTrue(pending.isEmpty())
        Unit
    }

    // ---------------- registration (scripted localhost) ----------------

    private data class Req(val method: String, val path: String, val body: String, val auth: String?)

    private class RegServer : AutoCloseable {
        val requests = Collections.synchronizedList(mutableListOf<Req>())
        @Volatile var handler: (String, String, String) -> Pair<Int, String> =
            { _, _, _ -> 404 to """{"error":"unscripted"}""" }
        private val server: HttpServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val baseUrl: String get() = "http://127.0.0.1:${server.address.port}"
        init {
            server.createContext("/") { exchange ->
                val body = exchange.requestBody.readBytes().decodeToString()
                requests.add(Req(
                    exchange.requestMethod, exchange.requestURI.path, body,
                    exchange.requestHeaders.getFirst("Authorization")))
                val (status, response) = handler(exchange.requestMethod, exchange.requestURI.path, body)
                val bytes = response.encodeToByteArray()
                exchange.responseHeaders.add("Content-Type", "application/json")
                exchange.sendResponseHeaders(status, bytes.size.toLong())
                exchange.responseBody.use { it.write(bytes) }
            }
            server.start()
        }
        override fun close() { server.stop(0) }
    }

    private fun sessionJson(id: String = "sess1", allowed: Boolean = true, info: List<String> = emptyList(), verified: Boolean = false) =
        """{"id":"$id","allowedToRequestCode":$allowed,"requestedInformation":[${info.joinToString(",") { "\"$it\"" }}],"verified":$verified,"nextSms":30,"nextCall":60}"""

    private fun regClient(server: RegServer) = RegistrationHttpClient(ctx).apply { baseUrl = server.baseUrl }

    @Test
    fun `registration code request lock rate-limit and captcha-after-code branches`() = runBlocking {
        RegServer().use { server ->
            val client = regClient(server)
            // 423 on session creation.
            server.handler = { _, path, _ ->
                if (path == "/v1/verification/session") 423 to """{"timeRemaining":9}""" else 404 to "{}"
            }
            var r = client.requestSmsCode("+15550102030")
            assertEquals("error", r.status); assertTrue(r.reason!!.contains("423"), "${r.reason}")

            // Session ok, code request 429 then 423.
            server.handler = { _, path, _ ->
                when {
                    path.endsWith("/code") -> 429 to """{"retryAfter":5}"""
                    else -> 200 to sessionJson()
                }
            }
            r = client.requestVoiceCode("+15550102030")
            assertEquals("error", r.status); assertTrue(r.reason!!.contains("429"), "${r.reason}")
            server.handler = { _, path, _ ->
                when {
                    path.endsWith("/code") -> 423 to """{}"""
                    else -> 200 to sessionJson()
                }
            }
            r = client.requestSmsCode("+15550102030")
            assertTrue(r.reason!!.contains("423"), "${r.reason}")

            // Code response asking for captcha (structured, then raw-body variant).
            server.handler = { _, path, _ ->
                when {
                    path.endsWith("/code") -> 200 to sessionJson(allowed = false, info = listOf("captcha"))
                    else -> 200 to sessionJson()
                }
            }
            r = client.requestSmsCode("+15550102030")
            assertEquals("captcha", r.status); assertTrue(r.needsCaptcha)
            server.handler = { _, path, _ ->
                when {
                    path.endsWith("/code") -> 200 to
                        """{"id":"sess1","allowedToRequestCode":false,"requestedInformation":[],"verified":false,"note":"CAPTCHA needed"}"""
                    else -> 200 to sessionJson()
                }
            }
            assertEquals("captcha", client.requestSmsCode("+15550102030").status)

            // Challenges listed but code allowed: the gate must not short-circuit.
            server.handler = { _, path, _ ->
                when {
                    path.endsWith("/code") -> 200 to sessionJson()
                    else -> 200 to sessionJson(allowed = true, info = listOf("captcha", "pushChallenge"))
                }
            }
            assertEquals("sent", client.requestSmsCode("+15550102030").status)
        }
    }

    @Test
    fun `registration scaffold reuse regeneration and sessionId alias`() = runBlocking {
        RegServer().use { server ->
            val client = regClient(server)
            server.handler = { _, path, _ ->
                when {
                    path.contains("/sess-alias/code") -> 200 to sessionJson(id = "sess-alias")
                    path.endsWith("/code") -> 200 to sessionJson()
                    // Session id carried as sessionId, not id.
                    else -> 200 to """{"sessionId":"sess-alias","allowedToRequestCode":true,"requestedInformation":[],"verified":false}"""
                }
            }
            // Registered scaffold for the same number is reused verbatim.
            val registered = SignalRegistrationKeys.generate("+15550102030").authScaffold
                .copy(registered = true, password = "keep-me")
            SignalAuthData.save(ctx, registered)
            assertEquals("sent", client.requestSmsCode("+15550102030").status)
            val afterReuse = SignalAuthData.load(ctx)!!
            assertEquals("keep-me", afterReuse.password)
            assertEquals("sess-alias", afterReuse.verificationSessionId)
            assertTrue(server.requests.any { it.path == "/v1/verification/session/sess-alias/code" },
                "${server.requests}")

            // Stale 32-byte UAK scaffold is regenerated for the same number.
            val stale = SignalRegistrationKeys.generate("+15550102030").authScaffold.copy(
                registered = false,
                unidentifiedAccessKey = android.util.Base64.encodeToString(ByteArray(32) { 7 }, android.util.Base64.NO_WRAP),
                password = "stale-pw")
            SignalAuthData.save(ctx, stale)
            assertEquals("sent", client.requestSmsCode("+15550102030").status)
            val afterRegen = SignalAuthData.load(ctx)!!
            assertTrue(afterRegen.password != "stale-pw")
            assertEquals(16, android.util.Base64.decode(afterRegen.unidentifiedAccessKey, android.util.Base64.NO_WRAP).size)

            // A scaffold for a different number is not reused.
            val other = SignalRegistrationKeys.generate("+19999999999").authScaffold.copy(password = "other-pw")
            SignalAuthData.save(ctx, other)
            assertEquals("sent", client.requestSmsCode("+15550102030").status)
            assertEquals("+15550102030", SignalAuthData.load(ctx)!!.phoneNumber)
        }
    }

    @Test
    fun `verify code filtering and 409-verified registration success`() = runBlocking {
        RegServer().use { server ->
            val client = regClient(server)
            // No scaffold at all.
            SignalAuthData.clear(ctx)
            val noKeys = client.verifyCode("123456")
            assertEquals("error", noKeys.status); assertEquals("no_keys", noKeys.reason)

            val scaffold = SignalRegistrationKeys.generate("+15550102030").authScaffold
                .copy(verificationSessionId = "sessV")
            SignalAuthData.save(ctx, scaffold)
            // 409 whose body says verified:true still proceeds to registration; code is digit-filtered.
            server.handler = { _, path, _ ->
                when {
                    path.endsWith("/code") -> 409 to sessionJson(id = "sessV", verified = true)
                    path == "/v1/registration" -> 200 to """{"aci":"aci-only-1"}"""
                    else -> 404 to "{}"
                }
            }
            val ok = client.verifyCode("12-34 56")
            assertTrue(ok.ok, "$ok"); assertEquals("aci-only-1", ok.aci); assertNull(ok.pni)
            val codeReq = server.requests.first { it.method == "PUT" }
            assertTrue(codeReq.body.contains("\"code\":\"123456\""), codeReq.body)
            val regReq = server.requests.first { it.path == "/v1/registration" }
            assertNotNull(regReq.auth); assertTrue(regReq.auth!!.startsWith("Basic "))
            val regBody = org.json.JSONObject(regReq.body)
            assertEquals("sessV", regBody.getString("sessionId"))
            assertTrue(regBody.has("aciIdentityKey")); assertTrue(regBody.has("pniIdentityKey"))
            assertTrue(regBody.has("aciPqLastResortPreKey")); assertTrue(regBody.getBoolean("requireAtomic"))
        }
    }

    @Test
    fun `verify code registration failures missing password and http errors`() = runBlocking {
        RegServer().use { server ->
            val client = regClient(server)
            val scaffold = SignalRegistrationKeys.generate("+15550102030").authScaffold
                .copy(verificationSessionId = "sessV")
            // Registration HTTP error is reported with the raw body and does not persist registration.
            SignalAuthData.save(ctx, scaffold)
            server.handler = { _, path, _ ->
                when {
                    path.endsWith("/code") -> 200 to sessionJson(id = "sessV", verified = true)
                    else -> 422 to """{"error":"bad uak"}"""
                }
            }
            val failed = client.verifyCode("654321")
            assertEquals("error", failed.status); assertEquals("HTTP 422", failed.reason)
            assertTrue(failed.raw.contains("bad uak")); assertNull(failed.auth)

            // A 200 registration body with no account id is not success.
            server.handler = { _, path, _ ->
                when {
                    path.endsWith("/code") -> 200 to sessionJson(id = "sessV", verified = true)
                    else -> 200 to """{"storageCapable":true}"""
                }
            }
            val noId = client.verifyCode("654321")
            assertEquals("error", noId.status); assertNull(noId.auth)

            // Missing password stops before the registration POST.
            SignalAuthData.save(ctx, scaffold.copy(password = ""))
            server.requests.clear()
            server.handler = { _, path, _ ->
                when {
                    path.endsWith("/code") -> 200 to sessionJson(id = "sessV", verified = true)
                    else -> 200 to """{"uuid":"x"}"""
                }
            }
            val noPw = client.verifyCode("654321")
            assertEquals("missing_password", noPw.reason)
            assertTrue(server.requests.none { it.path == "/v1/registration" })

            // Unverified code submission HTTP error propagates as an error result.
            SignalAuthData.save(ctx, scaffold)
            server.handler = { _, _, _ -> 500 to """{"error":"boom"}""" }
            val httpErr = client.verifyCode("000000")
            assertEquals("error", httpErr.status); assertTrue(httpErr.reason!!.contains("500"), "${httpErr.reason}")
        }
    }

    @Test
    fun `captcha submit guards and whoami variants`() { runBlocking {
        RegServer().use { server ->
            val client = regClient(server)
            SignalAuthData.clear(ctx)
            var r = client.submitCaptcha("+15550102030", "signalcaptcha://tok")
            assertEquals("no_session", r.reason)
            assertTrue(r.raw.contains("request a code first"))
            val scaffold = SignalRegistrationKeys.generate("+15550102030").authScaffold
                .copy(verificationSessionId = "sessC")
            SignalAuthData.save(ctx, scaffold)
            r = client.submitCaptcha("+15550102030", "signalcaptcha://")
            assertEquals("empty_captcha", r.reason)
            r = client.submitCaptcha("+15550102030", "   ")
            assertEquals("empty_captcha", r.reason)

            // whoami: registered+password probes; 500 is not existence; no password stays live-only.
            SignalAuthData.save(ctx, scaffold.copy(registered = true))
            server.handler = { _, _, _ -> 200 to """{"number":"+15550102030","aci":"aci-9","pni":"pni-9"}""" }
            assertTrue(client.checkExists("+15550102030").exists)
            server.handler = { _, _, _ -> 500 to """{}""" }
            assertFalse(client.checkExists("+15550102030").exists)
            SignalAuthData.save(ctx, scaffold.copy(registered = true, password = ""))
            val liveOnly = client.checkExists("+15550102030")
            assertFalse(liveOnly.exists); assertTrue(liveOnly.reason!!.contains("CDSIv2"))

            // whoami against a dead port is a caught error result, not a throw.
            val dead = RegistrationHttpClient(ctx).apply { baseUrl = "http://127.0.0.1:1" }
            SignalAuthData.save(ctx, scaffold.copy(registered = true))
            val err = dead.checkExists("+15550102030")
            assertFalse(err.exists); assertNotNull(err.reason)
        }
    } }

    // ---------------- groups / keys / cdsi / attachment parse edges ----------------

    private fun kb64(bytes: ByteArray): String =
        kotlin.io.encoding.Base64.Default.encode(bytes).trimEnd('=')

    @Test
    fun `groups keys cdsi and attachment uncovered parse and crypto branches`() = runBlocking {
        // Groups credentials: entries missing time/credential are skipped individually.
        val cred = kotlin.io.encoding.Base64.Default.encode(ByteArray(48) { 3 })
        val parsedCreds = SignalGroupsApi.parseCredentials(
            """{"credentials":[{"credential":"$cred"},{"redemptionTime":5},{"redemptionTime":6,"credential":"$cred"}]}""",
            warn = {})
        assertEquals(listOf(6L), parsedCreds.map { it.redemptionTimeSeconds })

        // authorizationFor fails closed on unparseable ACI and on a bogus credential.
        val masterKey = ByteArray(32) { (it + 1).toByte() }
        val secretParams = GroupSecretParams(SignalGroups.secretParamsFor(masterKey))
        val badAuth = SignalAuthData(phoneNumber = "+1", aci = "not-a-uuid", pni = pni)
        assertNull(SignalGroupsApi.authorizationFor(
            badAuth, secretParams, SignalGroupsApi.DayCredential(1_700_000_000L, ByteArray(48) { 1 })))
        val goodIdsBadCred = SignalAuthData(phoneNumber = "+1", aci = aci, pni = pni)
        assertNull(SignalGroupsApi.authorizationFor(
            goodIdsBadCred, secretParams, SignalGroupsApi.DayCredential(1_700_000_000L, ByteArray(10) { 1 })))

        // decryptGroup: presented + undecryptable members skipped, garbage title becomes "".
        val cipher = org.signal.libsignal.zkgroup.groups.ClientZkGroupCipher(secretParams)
        val validUserId = cipher.encrypt(
            org.signal.libsignal.protocol.ServiceId.Aci.parseFromString(aci)).serialize()
        val group = org.signal.storageservice.storage.protos.groups.Group.newBuilder()
            .setTitle(ByteString.copyFrom(byteArrayOf(1, 2, 3)))
            .setVersion(3)
            .addMembers(org.signal.storageservice.storage.protos.groups.Member.newBuilder()
                .setUserId(ByteString.copyFrom(validUserId))
                .setPresentation(ByteString.copyFrom(byteArrayOf(9)))
                .build())
            .addMembers(org.signal.storageservice.storage.protos.groups.Member.newBuilder()
                .setUserId(ByteString.copyFrom(byteArrayOf(4, 5, 6))).build())
            .addMembers(org.signal.storageservice.storage.protos.groups.Member.newBuilder()
                .setUserId(ByteString.copyFrom(validUserId)).build())
            .build()
        val state = SignalGroupsApi.decryptGroup(secretParams, group)!!
        assertEquals("", state.title); assertEquals(3, state.revision)
        assertEquals(1, state.memberAcis.size)

        // Keys: a device whose one-time key is undecodable keeps its bundle with no pre-key;
        // a device with no deviceId is skipped; non-array devices yields empty.
        fun device(extra: String) = """{"deviceId":2,"registrationId":7,"signedPreKey":{"keyId":11,"publicKey":"${kb64(ByteArray(33) { 1 })}","signature":"${kb64(ByteArray(64) { 2 })}"},"pqPreKey":{"keyId":31,"publicKey":"${kb64(ByteArray(33) { 3 })}","signature":"${kb64(ByteArray(64) { 4 })}"}$extra}"""
        val body = """{"identityKey":"${kb64(ByteArray(33) { 9 })}","devices":[${device(",\"preKey\":{\"keyId\":21,\"publicKey\":\"A\"}")},{"registrationId":1}]}"""
        val bundles = SignalKeysApi.parse(body, aci, warn = {})
        assertEquals(1, bundles.size)
        assertNull(bundles[0].bundle.preKeyId); assertNull(bundles[0].bundle.preKeyPublic)
        assertEquals(31, bundles[0].bundle.kyberPreKeyId)
        assertTrue(SignalKeysApi.parse("""{"identityKey":"${kb64(ByteArray(33) { 9 })}","devices":"nope"}""", aci, warn = {}).isEmpty())
        assertTrue(SignalKeysApi.parse("""{"identityKey":"${kb64(ByteArray(33) { 9 })}","devices":[{"deviceId":0}]}""", aci, warn = {}).isEmpty())

        // CDSI credentials: non-string fields are rejected; empty lookup preserves the token.
        assertNull(SignalCdsi.parseCredentials("""{"username":["u"],"password":"p"}""", warn = {}))
        assertNull(SignalCdsi.parseCredentials("""{"username":"u","password":["p"]}""", warn = {}))
        val token = byteArrayOf(5, 6, 7)
        val empty = SignalCdsi.lookup(SignalCdsi.Credentials("u", "p"), emptySet(), emptySet(), token)
        assertNotNull(empty); assertTrue(empty.discovered.isEmpty())
        assertTrue(empty.token!!.contentEquals(token))

        // Attachment form: numeric header values stringify, string cdn rejects the form,
        // non-object headers degrade to empty, and CDN2 upload refuses without network.
        val form = SignalAttachmentUpload.parseForm(
            """{"cdn":3,"key":"k2","signedUploadLocation":"https://cdn.example/x","headers":{"x-amz-meta":42,"host":"h"}}""",
            warn = {})!!
        assertEquals("42", form.headers["x-amz-meta"])
        // kotlinx's JsonPrimitive.int coerces a quoted numeric cdn, so this form parses.
        assertEquals(3, SignalAttachmentUpload.parseForm(
            """{"cdn":"3","key":"k","signedUploadLocation":"https://x"}""", warn = {})!!.cdn)
        val noHeaders = SignalAttachmentUpload.parseForm(
            """{"cdn":3,"key":"k","signedUploadLocation":"https://x","headers":[1]}""", warn = {})!!
        assertTrue(noHeaders.headers.isEmpty())
        assertFalse(SignalAttachmentUpload.upload(
            SignalAttachmentUpload.UploadForm(2, "k", emptyMap(), "https://cdn.example/x"),
            byteArrayOf(1, 2), null))
        Unit
    }
}
