package com.vayunmathur.communicate.signal

import androidx.room3.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.test.core.app.ApplicationProvider
import com.google.protobuf.ByteString
import com.sun.net.httpserver.HttpServer
import com.vayunmathur.communicate.data.signal.SignalAuthData
import com.vayunmathur.communicate.data.signal.SignalCachedMessage
import com.vayunmathur.communicate.data.signal.SignalClient
import com.vayunmathur.communicate.data.signal.SignalConversation
import com.vayunmathur.communicate.data.signal.SignalDatabase
import com.vayunmathur.communicate.data.signal.SignalEvent
import com.vayunmathur.communicate.data.signal.SignalGroups
import com.vayunmathur.communicate.data.signal.SignalProtocol
import com.vayunmathur.communicate.data.signal.createGroup
import com.vayunmathur.communicate.data.signal.e2e.SignalE2E
import com.vayunmathur.communicate.data.signal.e2e.ensureLocalPreKeys
import com.vayunmathur.communicate.data.signal.e2e.hasSignedPreKeyMatching
import com.vayunmathur.communicate.data.signal.ensureLocalPreKeys as clientEnsureLocalPreKeys
import com.vayunmathur.communicate.data.signal.establishSession
import com.vayunmathur.communicate.data.signal.handleInboundFrame
import com.vayunmathur.communicate.data.signal.putMessages
import com.vayunmathur.communicate.data.signal.refreshGroup
import com.vayunmathur.communicate.data.signal.registration.SignalRegistrationKeys
import com.vayunmathur.communicate.data.signal.sendContent
import com.vayunmathur.communicate.data.signal.sendMedia
import com.vayunmathur.communicate.data.signal.sendMessage
import com.vayunmathur.communicate.data.signal.setGroupName
import com.vayunmathur.communicate.data.signal.transport.SignalAttachmentUpload
import com.vayunmathur.communicate.data.signal.transport.SignalGroupsApi
import com.vayunmathur.communicate.data.signal.transport.SignalKeysApi
import com.vayunmathur.communicate.data.signal.transport.SignalPayload
import com.vayunmathur.communicate.data.signal.transport.SignalSocket
import com.vayunmathur.communicate.data.signal.updateGroupParticipants
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.MessageDigest
import java.util.Collections
import java.util.UUID
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.signal.libsignal.zkgroup.groups.ClientZkGroupCipher
import org.signal.libsignal.zkgroup.groups.GroupSecretParams
import org.signal.storageservice.storage.protos.groups.Group
import org.signal.storageservice.storage.protos.groups.GroupResponse
import org.signal.storageservice.storage.protos.groups.Member
import org.whispersystems.signalservice.internal.push.SignalServiceProtos
import signal.proto.chat_websocket.SignalChatWebsocket.WebSocketMessage
import signal.proto.chat_websocket.SignalChatWebsocket.WebSocketRequestMessage
import signal.proto.chat_websocket.SignalChatWebsocket.WebSocketResponseMessage

/**
 * Y3 pipeline: SignalGroupOps / SignalSendPipeline / SignalMessaging /
 * SignalInbound driven against scripted localhost HTTP (lead *BaseForTest
 * seams) and a raw RFC6455 WebSocket server, with a real two-party E2E store
 * and in-memory SignalDatabase. Private helpers (groupChange,
 * fetchGroupState, reconcileDevices, signedPreKeyMatchesServer,
 * putMessagesOverSocket, sendEncryptedAttempt, buildAttachmentPointer,
 * cacheOutgoingMedia, sendRetryReceipt, emitServerReceipt,
 * captureSenderKeys) are exercised through their public/internal callers.
 */
@OptIn(ExperimentalEncodingApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class Y3SignalPipelineServerTest {

    private val ctx get() = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val selfAci = "123e4567-e89b-42d3-a456-426614174000"
    private val bobAci = "7c1d9b4e-aaaa-bbbb-cccc-ddddeeeeffff"
    private val carolAci = "123e4567-e89b-42d3-a456-426614174001"

    private lateinit var db: SignalDatabase
    private lateinit var dbBob: SignalDatabase
    private lateinit var authA: SignalAuthData
    private lateinit var authB: SignalAuthData
    private lateinit var alice: SignalE2E
    private lateinit var bob: SignalE2E
    private val servers = mutableListOf<HttpScript>()
    private val wsServers = mutableListOf<RawWs>()
    private val sockets = mutableListOf<SignalSocket>()

    @BeforeTest fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ctx, SignalDatabase::class.java)
            .setDriver(BundledSQLiteDriver()).allowMainThreadQueries().build()
        dbBob = Room.inMemoryDatabaseBuilder(ctx, SignalDatabase::class.java)
            .setDriver(BundledSQLiteDriver()).allowMainThreadQueries().build()
        authA = SignalRegistrationKeys.generate("+14155552671").authScaffold.copy(aci = selfAci, pni = "PNI:$carolAci")
        authB = SignalRegistrationKeys.generate("+14155550000").authScaffold.copy(aci = bobAci)
        alice = SignalE2E(db, authA); bob = SignalE2E(dbBob, authB)
        SignalClient.db = db; SignalClient.authData = authA; SignalClient.e2e = alice
        SignalClient.socket = null; SignalClient.unauthSocket = null; SignalClient.appContext = null
        SignalClient.preKeysPrepared.set(false); SignalClient.discoveryRunning.set(false)
        SignalClient.pendingIdentityChanges.clear()
        resetSeams()
    }
    @AfterTest fun tearDown() {
        sockets.forEach { runCatching { it.disconnect() } }; sockets.clear()
        wsServers.forEach { it.close() }; wsServers.clear()
        servers.forEach { it.close() }; servers.clear()
        resetSeams()
        SignalClient.db = null; SignalClient.authData = null; SignalClient.e2e = null
        SignalClient.socket = null; SignalClient.unauthSocket = null
        SignalClient.pendingIdentityChanges.clear()
        db.close(); dbBob.close()
    }
    private fun resetSeams() {
        SignalGroupsApi.storageBaseForTest = null; SignalGroupsApi.chatBaseForTest = null
        SignalKeysApi.chatBaseForTest = null
        SignalAttachmentUpload.chatBaseForTest = null
    }

    // ---------- scripted HTTP ----------
    data class HReq(val method: String, val path: String, val query: String?, val headers: Map<String, String>, val body: ByteArray)
    data class HResp(val status: Int, val body: ByteArray, val type: String = "application/json") {
        constructor(status: Int, text: String) : this(status, text.encodeToByteArray())
    }
    class HttpScript(private val handler: (HReq) -> HResp) : AutoCloseable {
        val requests = Collections.synchronizedList(mutableListOf<HReq>())
        private val http = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val base: String get() = "http://127.0.0.1:${http.address.port}"
        init {
            http.createContext("/") { ex ->
                val req = HReq(ex.requestMethod, ex.requestURI.path, ex.requestURI.rawQuery,
                    ex.requestHeaders.mapValues { it.value.firstOrNull() ?: "" }, ex.requestBody.readBytes())
                requests.add(req)
                val resp = runCatching { handler(req) }.getOrElse { HResp(500, "handler: $it") }
                ex.responseHeaders.add("Content-Type", resp.type)
                ex.sendResponseHeaders(resp.status, resp.body.size.toLong())
                ex.responseBody.use { it.write(resp.body) }
            }; http.start()
        }
        override fun close() = http.stop(0)
    }
    private fun http(handler: (HReq) -> HResp) = HttpScript(handler).also { servers.add(it) }

    // ---------- raw WebSocket ----------
    class RawWs : AutoCloseable {
        private val server = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
        val port: Int = server.localPort
        val requests = Collections.synchronizedList(mutableListOf<WebSocketRequestMessage>())
        val rawFrames = Collections.synchronizedList(mutableListOf<ByteArray>())
        @Volatile var responder: (WebSocketRequestMessage) -> Pair<Int, ByteArray> =
            { 200 to "ok".encodeToByteArray() }
        @Volatile private var running = true
        private val thread = Thread {
            while (running) {
                try { val s = server.accept(); Thread { handle(s) }.start() } catch (_: Exception) { break }
            }
        }.apply { isDaemon = true; start() }

        private fun handle(s: Socket) {
            try {
                val input = s.getInputStream(); val output = s.getOutputStream()
                val header = readHandshake(input)
                val key = Regex("Sec-WebSocket-Key:\\s*(\\S+)").find(header)?.groupValues?.get(1) ?: ""
                val accept = java.util.Base64.getEncoder().encodeToString(
                    MessageDigest.getInstance("SHA-1").digest((key + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11").toByteArray(Charsets.US_ASCII)))
                output.write(("HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Accept: $accept\r\nx-signal-timestamp: 1700000000\r\n\r\n").toByteArray(Charsets.US_ASCII)); output.flush()
                var open = true
                while (open) {
                    val frame = readFrame(input)
                    if (frame == null || frame.first == 0x8) {
                        open = false
                    } else {
                        rawFrames.add(frame.second)
                        val msg = runCatching { WebSocketMessage.parseFrom(frame.second) }.getOrNull()
                        if (msg != null && msg.type == WebSocketMessage.Type.REQUEST && msg.hasRequest()) {
                            val req = msg.request; requests.add(req)
                            val (status, body) = responder(req)
                            val resp = WebSocketMessage.newBuilder().setType(WebSocketMessage.Type.RESPONSE)
                                .setResponse(WebSocketResponseMessage.newBuilder().setId(req.id).setStatus(status)
                                    .setMessage(if (status in 200..299) "OK" else "err")
                                    .setBody(ByteString.copyFrom(body)).build()).build()
                            synchronized(output) { writeFrame(output, 0x2, resp.toByteArray()) }
                        }
                    }
                }
            } catch (_: Exception) { } finally { runCatching { s.close() } }
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
            val opcode = b1 and 0x0F; val masked = (b2 and 0x80) != 0
            var len = (b2 and 0x7F).toLong()
            if (len == 126L) len = ((input.read() shl 8) or input.read()).toLong()
            else if (len == 127L) { var v = 0L; repeat(8) { v = (v shl 8) or input.read().toLong() }; len = v }
            val mask = if (masked) ByteArray(4) { input.read().toByte() } else null
            val payload = ByteArray(len.toInt()); var off = 0
            while (off < payload.size) {
                val n = input.read(payload, off, payload.size - off)
                if (n == -1) return null
                off += n
            }
            if (mask != null) for (i in payload.indices) payload[i] = (payload[i].toInt() xor mask[i % 4].toInt()).toByte()
            return opcode to payload
        }
        private fun writeFrame(output: OutputStream, opcode: Int, payload: ByteArray) {
            val out = java.io.ByteArrayOutputStream(); out.write(0x80 or opcode)
            when { payload.size < 126 -> out.write(payload.size)
                payload.size <= 0xFFFF -> { out.write(126); out.write(payload.size shr 8); out.write(payload.size) }
                else -> { out.write(127); repeat(8) { out.write((payload.size.toLong() shr (56 - it * 8)).toInt()) } } }
            out.write(payload); output.write(out.toByteArray()); output.flush()
        }
        fun messageRequests(): List<WebSocketRequestMessage> =
            synchronized(requests) { requests.filter { it.path.contains("/v1/messages") } }
        override fun close() { running = false; runCatching { server.close() } }
    }
    private fun ws() = RawWs().also { wsServers.add(it) }

    private fun await(label: String, timeoutMs: Long = 12_000, cond: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (cond()) return
            Thread.sleep(20)
        }
        error("timeout $label")
    }
    private fun connectWs(server: RawWs, authenticated: Boolean = true): SignalSocket {
        val sock = SignalSocket(ctx, authA, host = "127.0.0.1", port = server.port, useTls = false, authenticated = authenticated)
        sockets.add(sock)
        val states = Collections.synchronizedList(mutableListOf<SignalSocket.ConnectionState>())
        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        val job = scope.launch { sock.connectionState.collect { states.add(it) } }
        sock.connect()
        await("ws connected") { states.any { it is SignalSocket.ConnectionState.Connected } }
        job.cancel()
        return sock
    }

    // ---------- key bundles over HTTP ----------
    private fun b64u(bytes: ByteArray): String = Base64.Default.encode(bytes).trimEnd('=')
    private fun bundleJson(deviceId: Int, identity: ByteArray, regId: Int,
        signed: SignalE2E.PreKeyUpload.KeyEntity, kyber: SignalE2E.PreKeyUpload.KeyEntity,
        one: SignalE2E.PreKeyUpload.KeyEntity?): String {
        val oneJson = one?.let { ""","preKey":{"keyId":${it.id},"publicKey":"${b64u(it.publicKey)}"}""" } ?: ""
        return """{"identityKey":"${b64u(identity)}","devices":[{"deviceId":$deviceId,"registrationId":$regId,""" +
            """"signedPreKey":{"keyId":${signed.id},"publicKey":"${b64u(signed.publicKey)}","signature":"${b64u(signed.signature!!)}"},""" +
            """"pqPreKey":{"keyId":${kyber.id},"publicKey":"${b64u(kyber.publicKey)}","signature":"${b64u(kyber.signature!!)}"}$oneJson}]}"""
    }
    private var bobSigned: SignalE2E.PreKeyUpload.KeyEntity? = null
    private var bobKyber: SignalE2E.PreKeyUpload.KeyEntity? = null
    private var bobOne: SignalE2E.PreKeyUpload.KeyEntity? = null
    private fun bobBundleJson(deviceIds: List<Int> = listOf(1)): String {
        val up = bob.ensureLocalPreKeys()
        // First ensure rotates signed + generates kyber/one-time; later
        // ensures report nothing new, so cache the first batch.
        if (bobSigned == null) {
            bobSigned = up.signedPreKey; bobKyber = up.lastResortKyber; bobOne = up.oneTimeEcPreKeys.firstOrNull()
        }
        val signed = bobSigned ?: error("bob signed")
        val kyber = bobKyber ?: error("bob kyber")
        val identity = android.util.Base64.decode(authB.effectiveAciPublic(), android.util.Base64.NO_WRAP)
        // Rebuild a multi-device document from the single-device helper.
        val per = deviceIds.map { id ->
            val single = bundleJson(id, identity, authB.effectiveAciRegId(), signed, kyber, bobOne)
            org.json.JSONObject(single).getJSONArray("devices").getJSONObject(0).toString()
        }
        return """{"identityKey":"${b64u(identity)}","devices":[${per.joinToString(",")}]}"""
    }
    // After the first ensure, signed rotates once and is then stored; recover it
    // by rotating is wrong, so tests call bobBundleJson before any other ensure.
    private fun bobRotatedSigned(): SignalE2E.PreKeyUpload.KeyEntity = error("signed already consumed")
    private fun keysServerForBob(deviceIds: List<Int> = listOf(1)): HttpScript {
        val json = bobBundleJson(deviceIds)
        return http { req -> if (req.path.startsWith("/v2/keys/")) HResp(200, json) else HResp(404, "{}") }
    }
    private fun establishBob(): HttpScript {
        val srv = keysServerForBob(); SignalKeysApi.chatBaseForTest = srv.base
        return srv
    }
    private fun content(text: String, ts: Long): SignalServiceProtos.Content =
        SignalServiceProtos.Content.newBuilder()
            .setDataMessage(SignalServiceProtos.DataMessage.newBuilder().setBody(text).setTimestamp(ts)).build()

    private fun collectEvents(block: suspend () -> Unit): List<SignalEvent> {
        val seen = Collections.synchronizedList(mutableListOf<SignalEvent>())
        val job = CoroutineScope(Dispatchers.Unconfined + SupervisorJob()).launch { SignalClient.events.collect { seen.add(it) } }
        kotlinx.coroutines.runBlocking { block() }
        Thread.sleep(150); job.cancel()
        return seen
    }

    // ---------- reconcileDevices / signedPreKeyMatchesServer / putMessagesOverSocket ----------

    @Test fun send_reconciles409MissingDeviceThenRetriesOverSocket() = runBlocking {
        val keys = establishBobWithDevices()
        val server = ws()
        val statuses = ConcurrentLinkedQueue(listOf(409, 200))
        server.responder = { req ->
            if (req.path.contains("/v1/messages")) {
                val s = statuses.poll() ?: 200
                if (s == 409) s to """{"missingDevices":[2],"extraDevices":[]}""".encodeToByteArray() else s to ByteArray(0)
            } else 200 to ByteArray(0)
        }
        SignalClient.socket = connectWs(server)
        assertTrue(SignalClient.establishSession(alice, bobAci))
        assertFalse(alice.hasSession(bobAci, 2))
        assertTrue(SignalClient.sendContent(bobAci, content("reconcile me", 4242L)))
        assertEquals(2, server.messageRequests().size)
        assertTrue(alice.hasSession(bobAci, 2))
        assertTrue(keys.requests.any { it.path == "/v2/keys/$bobAci/*" })
        // Second attempt encrypts for both devices.
        val second = org.json.JSONObject(server.messageRequests()[1].body.toByteArray().decodeToString())
        assertEquals(2, second.getJSONArray("messages").length())
    }
    private fun establishBobWithDevices(): HttpScript {
        // First fetch (establishSession) sees device 1 only; the reconcile
        // fetch that follows a 409/410 sees devices 1+2.
        val json1 = bobBundleJson(listOf(1))
        val json12 = bobBundleJson(listOf(1, 2))
        val gets = java.util.concurrent.atomic.AtomicInteger(0)
        val srv = http { req ->
            if (req.path.startsWith("/v2/keys/")) HResp(200, if (gets.incrementAndGet() == 1) json1 else json12)
            else HResp(404, "{}")
        }
        SignalKeysApi.chatBaseForTest = srv.base; return srv
    }

    @Test fun send_reconciles410StaleDeviceThenRetries() = runBlocking {
        establishBobWithDevices()
        val server = ws()
        val statuses = ConcurrentLinkedQueue(listOf(410, 200))
        server.responder = { req ->
            if (req.path.contains("/v1/messages")) {
                val s = statuses.poll() ?: 200
                if (s == 410) s to """{"staleDevices":[1]}""".encodeToByteArray() else s to ByteArray(0)
            } else 200 to ByteArray(0)
        }
        SignalClient.socket = connectWs(server)
        assertTrue(SignalClient.establishSession(alice, bobAci))
        assertTrue(SignalClient.sendContent(bobAci, content("stale", 4343L)))
        assertEquals(2, server.messageRequests().size)
        assertTrue(alice.hasSession(bobAci, 1))
    }

    @Test fun send_reconcileWithNoFetchableDeviceGivesUpAfterOneAttempt() = runBlocking {
        establishBob() // only device 1 on offer
        val server = ws()
        server.responder = { req ->
            if (req.path.contains("/v1/messages")) 409 to """{"missingDevices":[9],"extraDevices":[]}""".encodeToByteArray()
            else 200 to ByteArray(0)
        }
        SignalClient.socket = connectWs(server)
        assertTrue(SignalClient.establishSession(alice, bobAci))
        assertFalse(SignalClient.sendContent(bobAci, content("no device 9", 4444L)))
        assertEquals(1, server.messageRequests().size)
    }

    @Test fun putMessagesOverSocket_statusMappingAndSealedFallback() = runBlocking {
        val server = ws(); SignalClient.socket = connectWs(server)
        val body = SignalPayload.buildPutMessagesBody(bobAci,
            listOf(SignalPayload.OutgoingPushMessage(1, 1, 1234, byteArrayOf(1))), timestamp = 1L)
        server.responder = { 200 to ByteArray(0) }
        assertTrue(SignalClient.putMessages(bobAci, body, null) is SignalClient.SendOutcome.Success)
        server.responder = { req -> if (req.path.contains("/v1/messages")) 409 to """{"missingDevices":[2]}""".encodeToByteArray() else 200 to ByteArray(0) }
        val changed = SignalClient.putMessages(bobAci, body, null)
        assertTrue(changed is SignalClient.SendOutcome.DeviceSetChanged); assertEquals(409, changed.status)
        server.responder = { req -> if (req.path.contains("/v1/messages")) 500 to ByteArray(0) else 200 to ByteArray(0) }
        assertEquals(SignalClient.SendOutcome.Failed(500), SignalClient.putMessages(bobAci, body, null))

        // Sealed: unauth socket refuses 401, identified socket accepts.
        val unauthServer = ws(); SignalClient.unauthSocket = connectWs(unauthServer, authenticated = false)
        unauthServer.responder = { req -> if (req.path.contains("/v1/messages")) 401 to ByteArray(0) else 200 to ByteArray(0) }
        server.responder = { 200 to ByteArray(0) }
        assertTrue(SignalClient.putMessages(bobAci, body, ByteArray(16) { 3 }) is SignalClient.SendOutcome.Success)
        assertEquals(1, unauthServer.messageRequests().size)
        assertTrue(unauthServer.messageRequests()[0].headersList.any { it.startsWith("Unidentified-Access-Key:") },
            "${unauthServer.messageRequests()[0].headersList}")
    }

    @Test fun ensureLocalPreKeys_freshUploadsEverythingToLocalhost() = runTest {
        var putBody: String? = null
        val srv = http { req ->
            if (req.method == "PUT" && req.path == "/v2/keys") { putBody = req.body.decodeToString(); HResp(200, "{}") } else HResp(404, "{}")
        }
        SignalKeysApi.chatBaseForTest = srv.base
        SignalClient.clientEnsureLocalPreKeys()
        assertTrue(SignalClient.preKeysPrepared.get())
        val json = org.json.JSONObject(assertNotNull(putBody))
        assertTrue(json.has("signedPreKey")); assertTrue(json.has("pqLastResortPreKey"))
        assertTrue(json.getJSONArray("preKeys").length() > 0)
        // markPreKeysUploaded ran: stored one-time keys are flagged uploaded.
        assertTrue(db.e2ePreKeyDao().getAll().all { it.uploaded })
    }

    @Test fun ensureLocalPreKeys_signedMatchSkipsUploadMismatchRotatesAndUploads() = runTest {
        val first = alice.ensureLocalPreKeys()
        val signed = first.signedPreKey ?: error("rotated signed")
        val kyber = first.lastResortKyber ?: error("kyber")
        fun ownJson(signedPublic: ByteArray): String = bundleJson(1, alice.ownIdentityPublicKey,
            authA.effectiveAciRegId(),
            SignalE2E.PreKeyUpload.KeyEntity(signed.id, signedPublic, signed.signature),
            kyber, null)
        var served = ownJson(signed.publicKey)
        val alice2 = SignalE2E(db, authA.copy(signedPreKeyId = signed.id))
        SignalClient.e2e = alice2
        val srv = http { req ->
            when {
                req.method == "GET" && req.path.startsWith("/v2/keys/") -> HResp(200, served)
                req.method == "PUT" && req.path == "/v2/keys" -> HResp(200, "{}")
                else -> HResp(404, "{}")
            }
        }
        SignalKeysApi.chatBaseForTest = srv.base
        // Matching server copy: GET happens, no PUT, guard stays latched.
        SignalClient.preKeysPrepared.set(false); SignalClient.clientEnsureLocalPreKeys()
        assertTrue(srv.requests.any { it.method == "GET" })
        srv.requests.filter { it.method == "PUT" }.forEach { putReq ->
            assertNull(org.json.JSONObject(putReq.body.decodeToString()).optJSONObject("signedPreKey"))
        }
        assertTrue(alice2.hasSignedPreKeyMatching(signed.id, signed.publicKey))
        // Mismatched server copy (Bob's signed public under Alice's id): rotate + PUT.
        val bobUp = bob.ensureLocalPreKeys()
        served = ownJson((bobUp.signedPreKey ?: signed).publicKey)
        srv.requests.clear(); SignalClient.preKeysPrepared.set(false); SignalClient.clientEnsureLocalPreKeys()
        val put = srv.requests.single { it.method == "PUT" }
        val putJson = org.json.JSONObject(put.body.decodeToString())
        assertEquals(signed.id + 1, putJson.getJSONObject("signedPreKey").getInt("keyId"))
        assertTrue(alice2.hasSignedPreKeyMatching(signed.id + 1,
            Base64.Default.decode(putJson.getJSONObject("signedPreKey").getString("publicKey")
                .let { it + "=".repeat((4 - it.length % 4) % 4) })))
    }

    @Test fun sendEncryptedAttempt_successCachesMessageViaSendMessage() = runBlocking {
        establishBob()
        val server = ws(); server.responder = { 200 to ByteArray(0) }
        SignalClient.socket = connectWs(server)
        assertTrue(SignalClient.establishSession(alice, bobAci))
        val id = SignalClient.sendMessage(bobAci, "delivered y3")
        assertNotNull(id)
        val rows = db.cachedMessageDao().getForConversation(bobAci)
        assertEquals(1, rows.size); assertEquals("delivered y3", rows[0].body); assertTrue(rows[0].outgoing)
        assertEquals(1, server.messageRequests().size)
    }

    // ---------- SignalGroupOps (server-accepted) ----------

    private fun todayAligned(): Long { val now = System.currentTimeMillis() / 1000; return now - (now % 86_400) }
    /** Mint a zkgroup auth credential locally with libsignal's server ops. */
    private fun mintCredential(): ByteArray? = try {
        val secret = org.signal.libsignal.zkgroup.ServerSecretParams.generate()
        val ops = org.signal.libsignal.zkgroup.auth.ServerZkAuthOperations(secret)
        val uuid = Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")
        val pni = authA.pni.takeIf { uuid.matches(it) } ?: carolAci
        val aci = runCatching { org.signal.libsignal.protocol.ServiceId.Aci.parseFromString(selfAci) }
            .getOrElse { org.signal.libsignal.protocol.ServiceId.Aci(java.util.UUID.fromString(selfAci)) }
        val pniObj = runCatching { org.signal.libsignal.protocol.ServiceId.Pni.parseFromString(pni) }
            .getOrElse { org.signal.libsignal.protocol.ServiceId.Pni(java.util.UUID.fromString(pni)) }
        ops.issueAuthCredentialWithPniZkc(
            java.security.SecureRandom(), aci, pniObj,
            java.time.Instant.ofEpochSecond(todayAligned()),
        ).serialize()
    } catch (e: Throwable) {
        System.err.println("mintCredential unavailable: $e")
        null
    }

    private class GroupWorld(val convId: String, val masterKey: ByteArray, val secretParams: GroupSecretParams)

    private fun groupWorld(name: String = "Before", revision: Int = 0): GroupWorld {
        val (masterKey, _) = SignalGroups.generateMasterKeyAndSecretParams()
        val convId = SignalProtocol.toConversationId("", masterKey)
        kotlinx.coroutines.runBlocking {
            db.conversationDao().upsert(SignalConversation(chatId = convId, isGroup = true, name = name,
                participants = bobAci, groupMasterKey = masterKey, groupRevision = revision))
        }
        return GroupWorld(convId, masterKey, GroupSecretParams(SignalGroups.secretParamsFor(masterKey)))
    }

    /** Chat credential server + storage server wired into the group seams. */
    private fun wireGroupServers(storage: (HReq) -> HResp): Pair<HttpScript, HttpScript> {
        val credential = mintCredential()
        assertNotNull(credential, "zkgroup server credential minting unavailable on this host")
        val credB64 = Base64.Default.encode(credential)
        val chat = http { req ->
            if (req.path == "/v1/certificate/auth/group")
                HResp(200, """{"credentials":[{"redemptionTime":${todayAligned()},"credential":"$credB64"}]}""")
            else HResp(404, "{}")
        }
        val store = http(storage)
        SignalGroupsApi.chatBaseForTest = chat.base; SignalGroupsApi.storageBaseForTest = store.base
        return chat to store
    }

    // NOTE (unreachable accepted path): groupChange/refreshGroup first fetch
    // credentials from chat, then SignalGroupsApi.authorizationFor verifies
    // the credential against the *hardcoded production* zkgroup
    // ServerPublicParams. A credential minted locally (random server secret)
    // cannot verify against those params, and there is no seam for them, so
    // the server-accepted PATCH/GET-storage step cannot be reached offline.
    // What IS covered here, and was offline-only before: the credential fetch
    // really goes to the scripted chat server, the op then fails closed, and
    // local state is untouched. Accepted PATCH/GET at the transport level is
    // covered in Y3SignalServerApiTest (patchGroup/fetchGroup/proof).
    @Test fun groupOps_setGroupName_fetchesCredentialsThenFailsClosedAtZkVerification() = runTest {
        val world = groupWorld()
        val (chat, store) = wireGroupServers { req ->
            if (req.method == "PATCH") HResp(200, byteArrayOf(), "application/x-protobuf") else HResp(404, "{}")
        }
        assertFalse(SignalClient.setGroupName(world.convId, "After"))
        val row = db.conversationDao().getConversation(world.convId)!!
        assertEquals("Before", row.name); assertEquals(0, row.groupRevision)
        assertTrue(chat.requests.any { it.path == "/v1/certificate/auth/group" })
        assertTrue(store.requests.none { it.method == "PATCH" }) // authorization precedes storage
    }

    @Test fun groupOps_setGroupName_noCredentialsKeepsRowAndSkipsStorage() = runTest {
        val world = groupWorld()
        val chat = http { HResp(200, """{"credentials":[]}""") }
        val store = http { HResp(200, byteArrayOf(), "application/x-protobuf") }
        SignalGroupsApi.chatBaseForTest = chat.base; SignalGroupsApi.storageBaseForTest = store.base
        assertFalse(SignalClient.setGroupName(world.convId, "After"))
        val row = db.conversationDao().getConversation(world.convId)!!
        assertEquals("Before", row.name); assertEquals(0, row.groupRevision)
        assertEquals(1, chat.requests.size); assertTrue(store.requests.isEmpty())
    }

    @Test fun groupOps_updateGroupParticipants_fetchesCredentialsThenFailsClosed() = runTest {
        val world = groupWorld()
        val (chat, store) = wireGroupServers { req ->
            if (req.method == "PATCH") HResp(200, byteArrayOf(), "application/x-protobuf") else HResp(404, "{}")
        }
        val seen = mutableListOf<SignalEvent>()
        val job = launch(Dispatchers.Unconfined) { SignalClient.events.collect { seen.add(it) } }
        assertFalse(SignalClient.updateGroupParticipants(world.convId, listOf(carolAci), "add"))
        job.cancel()
        // No participant event is emitted for a change the server never accepted.
        assertTrue(seen.none { it is SignalEvent.ParticipantAdded }, "$seen")
        assertTrue(chat.requests.any { it.path == "/v1/certificate/auth/group" })
        assertTrue(store.requests.none { it.method == "PATCH" })
    }

    @Test fun groupOps_refreshGroup_fetchesCredentialsThenFailsClosed() = runTest {
        val world = groupWorld(name = "Old", revision = 0)
        val (chat, store) = wireGroupServers { HResp(404, "{}") }
        assertFalse(SignalClient.refreshGroup(world.convId))
        val row = db.conversationDao().getConversation(world.convId)!!
        assertEquals("Old", row.name); assertEquals(0, row.groupRevision)
        assertEquals(bobAci, row.participants)
        assertTrue(chat.requests.any { it.path == "/v1/certificate/auth/group" })
        assertTrue(store.requests.none { it.method == "GET" }) // authorization precedes storage
    }

    @Test fun groupOps_createGroup_persistsWithServerReachableChat() = runTest {
        // createGroup itself uses SignalGroups.putNewGroup (fixed chat host),
        // so offline it still persists locally; assert the row/key contract.
        val id = SignalClient.createGroup("Y3 Group", listOf(bobAci, carolAci))
        assertNotNull(id)
        val row = db.conversationDao().getConversation(id)!!
        assertTrue(row.isGroup); assertEquals("Y3 Group", row.name)
        assertEquals(32, row.groupMasterKey!!.size); assertEquals(0, row.groupRevision)
        assertTrue(row.participants.contains(bobAci))
    }

    // ---------- SignalMessaging sendMedia ----------

    @Test fun sendMedia_uploadsEncryptedBlobAndCachesPointerMessage() = runBlocking {
        establishBob()
        val wsServer = ws(); wsServer.responder = { 200 to ByteArray(0) }
        SignalClient.socket = connectWs(wsServer)
        assertTrue(SignalClient.establishSession(alice, bobAci))
        val cdn = http { req -> if (req.method == "POST") HResp(201, byteArrayOf()) else HResp(404, byteArrayOf()) }
        val chat = http { HResp(200, """{"cdn":3,"key":"media-key-1","signedUploadLocation":"${cdn.base}/upload/m","headers":{"x-amz-meta":"mm"}}""") }
        SignalAttachmentUpload.chatBaseForTest = chat.base

        val plain = "y3 media bytes \u0001\u0002".encodeToByteArray()
        val id = SignalClient.sendMedia(bobAci, plain, "image/png", "pic.png")
        assertNotNull(id)
        // CDN received encrypted bytes (not plaintext), form was requested.
        assertEquals(1, cdn.requests.size); assertFalse(cdn.requests[0].body.contentEquals(plain))
        assertTrue(cdn.requests[0].body.size > plain.size)
        assertTrue(chat.requests.any { it.path == "/v4/attachments/form/upload" })
        // Cached outgoing media row from cacheOutgoingMedia.
        val rows = db.cachedMessageDao().getForConversation(bobAci)
        assertEquals(1, rows.size); assertEquals("[Media: image/png]", rows[0].body); assertTrue(rows[0].outgoing)
        // The encrypted WS payload decrypts on Bob's side to a pointer for our form.
        val putJson = org.json.JSONObject(wsServer.messageRequests().single().body.toByteArray().decodeToString())
        val msg = putJson.getJSONArray("messages").getJSONObject(0)
        val cipherBytes = Base64.Default.decode(msg.getString("content"))
        val isPreKey = msg.getInt("type") == SignalServiceProtos.Envelope.Type.PREKEY_MESSAGE.number
        val decrypted = bob.decryptDM(selfAci, 1, isPreKey, cipherBytes)
        val contentProto = SignalServiceProtos.Content.parseFrom(SignalProtocol.stripMessagePadding(decrypted))
        val pointer = contentProto.dataMessage.attachmentsList.single()
        assertEquals("media-key-1", pointer.cdnKey); assertEquals(3, pointer.cdnNumber)
        assertEquals("image/png", pointer.contentType); assertEquals(plain.size, pointer.size)
        assertEquals("pic.png", pointer.fileName); assertTrue(pointer.key.size() > 0); assertTrue(pointer.digest.size() > 0)
    }

    @Test fun sendMedia_formRefusedFailsWithoutUploadOrCache() = runTest {
        val chat = http { HResp(404, "{}") }; SignalAttachmentUpload.chatBaseForTest = chat.base
        val seen = mutableListOf<SignalEvent>()
        val job = launch(Dispatchers.Unconfined) { SignalClient.events.collect { seen.add(it) } }
        assertNull(SignalClient.sendMedia(bobAci, byteArrayOf(1, 2, 3), "image/png"))
        job.cancel()
        assertTrue(seen.any { it is SignalEvent.SendFailed }, "$seen")
        assertTrue(db.cachedMessageDao().getForConversation(bobAci).isEmpty())
    }

    // ---------- SignalInbound ----------

    private fun wsFrame(envelope: SignalServiceProtos.Envelope, id: Long = 99L): ByteArray =
        WebSocketMessage.newBuilder().setType(WebSocketMessage.Type.REQUEST)
            .setRequest(WebSocketRequestMessage.newBuilder().setVerb("PUT").setPath("/api/v1/message")
                .setId(id).setBody(ByteString.copyFrom(envelope.toByteArray())).build())
            .build().toByteArray()

    @Test fun inbound_serverDeliveryReceipt_emitsDeliveryReadReceipt() = runTest {
        val seen = mutableListOf<SignalEvent>()
        val job = launch(Dispatchers.Unconfined) { SignalClient.events.collect { seen.add(it) } }
        val envelope = SignalServiceProtos.Envelope.newBuilder()
            .setType(SignalServiceProtos.Envelope.Type.SERVER_DELIVERY_RECEIPT)
            .setSourceServiceId(bobAci).setSourceDeviceId(1)
            .setClientTimestamp(1_700_000_111_000L).setServerGuid("guid-y3")
            .build()
        SignalClient.handleInboundFrame(wsFrame(envelope))
        job.cancel()
        val receipt = seen.filterIsInstance<SignalEvent.ReadReceipt>().single()
        assertTrue(receipt.isDelivery); assertEquals(bobAci, receipt.conversationId)
        assertEquals("guid-y3", receipt.messageId); assertEquals(1_700_000_111_000L, receipt.timestamp)
    }

    @Test fun inbound_undecryptableEnvelope_emitsErrorAndSendsRetryReceipt() = runBlocking {
        establishBob()
        val server = ws(); server.responder = { 200 to ByteArray(0) }
        SignalClient.socket = connectWs(server)
        assertTrue(SignalClient.establishSession(alice, bobAci))
        val seen = mutableListOf<SignalEvent>()
        val job = launch(Dispatchers.Unconfined) { SignalClient.events.collect { seen.add(it) } }
        // A real Bob->Alice whisper ciphertext with a flipped MAC byte: it
        // parses as a SignalMessage (so a retry receipt can be built for it)
        // but Alice's decrypt must reject it.
        val aliceUp2 = alice.ensureLocalPreKeys()
        val aSigned2 = aliceUp2.signedPreKey ?: error("alice signed")
        bob.processPreKeyBundle(selfAci, 1, SignalE2E.ParsedPreKeyBundle(
            registrationId = authA.effectiveAciRegId(),
            preKeyId = aliceUp2.oneTimeEcPreKeys.firstOrNull()?.id,
            preKeyPublic = aliceUp2.oneTimeEcPreKeys.firstOrNull()?.publicKey,
            signedPreKeyId = aSigned2.id, signedPreKeyPublic = aSigned2.publicKey,
            signedPreKeySignature = aSigned2.signature!!,
            identityKey = android.util.Base64.decode(authA.effectiveAciPublic(), android.util.Base64.NO_WRAP),
            kyberPreKeyId = (aliceUp2.lastResortKyber ?: error("alice kyber")).id,
            kyberPreKeyPublic = aliceUp2.lastResortKyber!!.publicKey,
            kyberPreKeySignature = aliceUp2.lastResortKyber!!.signature!!))
        val genuine = bob.encryptDM(selfAci, 1, SignalProtocol.padMessageBody(content("tamper", 3L).toByteArray()))
        val tampered = genuine.data.copyOf().also { it[it.lastIndex] = (it[it.lastIndex].toInt() xor 0xFF).toByte() }
        val envelope = SignalServiceProtos.Envelope.newBuilder()
            .setType(SignalServiceProtos.Envelope.Type.PREKEY_MESSAGE)
            .setSourceServiceId(bobAci).setSourceDeviceId(1)
            .setClientTimestamp(1_700_000_222_000L)
            .setContent(ByteString.copyFrom(tampered))
            .build()
        SignalClient.handleInboundFrame(wsFrame(envelope))
        job.cancel()
        assertTrue(seen.any { it is SignalEvent.DecryptionError && it.senderAci == bobAci }, "$seen")
        // sendRetryReceipt put a PLAINTEXT_CONTENT message to Bob over the socket.
        val puts = server.messageRequests().filter { it.path.contains(bobAci) }
        assertEquals(1, puts.size)
        val putJson = org.json.JSONObject(puts[0].body.toByteArray().decodeToString())
        val msg = putJson.getJSONArray("messages").getJSONObject(0)
        assertEquals(SignalServiceProtos.Envelope.Type.PLAINTEXT_CONTENT.number, msg.getInt("type"))
        assertEquals(1, msg.getInt("destinationDeviceId"))
        assertTrue(Base64.Default.decode(msg.getString("content")).isNotEmpty())
    }

    @Test fun inbound_captureSenderKeys_storesProfileKeyAndSenderKey() = runBlocking {
        // Establish Bob->Alice double-ratchet: Alice consumes Bob's bundle,
        // Bob opens Alice's pre-key message, then Bob can send whisper.
        assertTrue(run {
            val srv = keysServerForBob(); SignalKeysApi.chatBaseForTest = srv.base
            SignalClient.establishSession(alice, bobAci)
        })
        val aliceUp = alice.ensureLocalPreKeys()
        val aSigned = aliceUp.signedPreKey ?: error("alice signed")
        // Bob consumes Alice's bundle directly (his side of the session).
        bob.processPreKeyBundle(selfAci, 1, SignalE2E.ParsedPreKeyBundle(
            registrationId = authA.effectiveAciRegId(),
            preKeyId = aliceUp.oneTimeEcPreKeys.firstOrNull()?.id,
            preKeyPublic = aliceUp.oneTimeEcPreKeys.firstOrNull()?.publicKey,
            signedPreKeyId = aSigned.id, signedPreKeyPublic = aSigned.publicKey,
            signedPreKeySignature = aSigned.signature!!,
            identityKey = android.util.Base64.decode(authA.effectiveAciPublic(), android.util.Base64.NO_WRAP),
            kyberPreKeyId = (aliceUp.lastResortKyber ?: error("alice kyber")).id,
            kyberPreKeyPublic = aliceUp.lastResortKyber!!.publicKey,
            kyberPreKeySignature = aliceUp.lastResortKyber!!.signature!!))
        val setup = alice.encryptDM(bobAci, 1, SignalProtocol.padMessageBody(content("setup", 1L).toByteArray()))
        bob.decryptDM(selfAci, 1, isPreKey = true, ciphertext = setup.data)

        val distId = UUID.randomUUID()
        val skdm = bob.createSenderKeyDistribution(distId)
        val profileKey = ByteArray(32) { (it + 40).toByte() }
        val dm = SignalPayload.buildDataMessage(body = "keys attached", timestamp = 1_700_000_333_000L, profileKey = profileKey)
        val contentProto = SignalPayload.buildContentWithDataMessage(dm, senderKeyDistributionMessage = skdm)
        val enc = bob.encryptDM(selfAci, 1, SignalProtocol.padMessageBody(contentProto.toByteArray()))

        val seen = mutableListOf<SignalEvent>()
        val job = launch(Dispatchers.Unconfined) { SignalClient.events.collect { seen.add(it) } }
        val envelope = SignalServiceProtos.Envelope.newBuilder()
            .setType(SignalServiceProtos.Envelope.Type.DOUBLE_RATCHET)
            .setSourceServiceId(bobAci).setSourceDeviceId(1)
            .setClientTimestamp(1_700_000_333_000L)
            .setContent(ByteString.copyFrom(enc.data))
            .build()
        SignalClient.handleInboundFrame(wsFrame(envelope))
        job.cancel()
        assertTrue(seen.any { it is SignalEvent.IncomingMessage && it.body == "keys attached" }, "$seen")
        // captureSenderKeys: profile key row + sender-key record usable by Alice.
        assertTrue(db.profileKeyDao().get(bobAci)!!.profileKey.contentEquals(profileKey))
        val groupCipher = bob.encryptGroup(distId, "group secret".toByteArray())
        assertEquals("group secret", alice.decryptGroup(bobAci, 1, groupCipher.data).decodeToString())
    }
}
