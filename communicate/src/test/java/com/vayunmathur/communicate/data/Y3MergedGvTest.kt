package com.vayunmathur.communicate.data

import android.app.Application
import android.content.Context
import android.util.Base64
import androidx.room3.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.test.core.app.ApplicationProvider
import com.sun.net.httpserver.HttpServer
import com.vayunmathur.communicate.data.googlevoice.GoogleVoiceClient
import com.vayunmathur.communicate.data.googlevoice.GoogleVoiceParser
import com.vayunmathur.communicate.data.googlevoice.GoogleVoiceSession
import com.vayunmathur.communicate.data.signal.SignalAuthData
import com.vayunmathur.communicate.data.signal.SignalClient
import com.vayunmathur.communicate.data.signal.SignalDatabase
import com.vayunmathur.communicate.data.whatsapp.WhatsAppAuthData
import com.vayunmathur.communicate.data.whatsapp.WhatsAppClient
import com.vayunmathur.communicate.data.whatsapp.WhatsAppDatabase
import com.vayunmathur.communicate.data.whatsapp.WhatsAppDiag
import com.vayunmathur.communicate.data.whatsapp.WhatsAppProtocol
import com.vayunmathur.communicate.data.whatsapp.decodeNode
import com.vayunmathur.communicate.data.whatsapp.encodeNode
import com.vayunmathur.communicate.data.whatsapp.e2e.RustWhatsAppCrypto
import com.vayunmathur.communicate.data.whatsapp.e2e.WhatsAppE2E
import com.vayunmathur.communicate.data.whatsapp.handleIncomingMessage
import com.vayunmathur.communicate.data.whatsapp.proto.WhatsAppE2EProto
import com.vayunmathur.communicate.data.whatsapp.transport.WhatsAppSocket
import com.vayunmathur.communicate.data.whatsapp.unpadMessage
import com.vayunmathur.communicate.ui.SharedContactCard
import java.io.ByteArrayOutputStream
import java.net.InetSocketAddress
import java.util.Collections
import javax.crypto.spec.SecretKeySpec
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Y3: merged Google Voice surface in CommunicateRepositoryMergedKt /
 * CommunicateRepositorySendKt. GV runs over a localhost HttpServer through
 * GoogleVoiceClient.baseOverrideForTest (the companion seam used by
 * CommunicateRepository's GoogleVoiceClient.get) after GoogleVoiceSession.save;
 * shareContact / WhatsApp sends run over the Y2 NoiseSocket seam and are
 * decrypted as the peer so the exact vCard text is asserted.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class Y3MergedGvTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private lateinit var server: HttpServer
    private val requests = Collections.synchronizedList(mutableListOf<String>())
    @Volatile private var failBatch = false

    // Folder bodies are selected by the folder id in the api2thread/list request body.
    private val threadBody = "[[[\"t.+14152124034\",1,[[\"h1\",1786409649630,\"+12133945548\"," +
        "[\"+14152124034\",\"+14152124034\",null,null,null,null,0],10,1,null,null,null," +
        "\"Hello there\",null,null,5,1,null,\"+14152124034\",0]],null,[[\"+14152124034\"]]]]]"
    private val callsBody = "[[[\"c.call1\",1,[[\"call1\",1786409649000,\"+12133945548\"," +
        "[\"+14155550199\"],14,1,null,null,65,null,null,null,null,null,null,\"+14155550199\"]],null,[[\"+14155550199\"]]]]]"
    private val voicemailBody = "[[[" +
        "\"c.vm1\",1,[[\"vm1\",1786409650000,\"+12133945548\",[\"+14155550199\"],2,1," +
        "[0.9,[[\"hello\",null,null,0.9],[\"world\",null,null,0.8]]],null,42,null,null," +
        "\"https://voice.google.com/media/vm1.mp3\",null,8,null,\"+14155550199\"]],null,[[\"+14155550199\"]]]," +
        "[\"c.vm2\",1,[[\"vm2\",1786409600000,\"+12133945548\",[\"+14155550198\"],2,1," +
        "null,null,12,null,null,null,null,1,null,\"+14155550198\"]],null,[[\"+14155550198\"]]]" +
        "]]"

    @BeforeTest
    fun setUp() {
        requests.clear(); failBatch = false
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { ex ->
            val path = ex.requestURI.path
            val body = ex.requestBody.readBytes().decodeToString()
            requests += "$path $body"
            val (status, resp) = when {
                path.contains("batchupdateattributes") && failBatch -> 500 to "oops"
                path.contains("api2thread/list") && body.startsWith("[4") -> 200 to voicemailBody
                path.contains("api2thread/list") && body.startsWith("[3") -> 200 to callsBody
                path.contains("api2thread/list") -> 200 to threadBody
                else -> 200 to "[]"
            }
            val bytes = resp.encodeToByteArray()
            ex.sendResponseHeaders(status, bytes.size.toLong())
            ex.responseBody.use { it.write(bytes) }
        }
        server.start()
        runBlocking {
            GoogleVoiceSession.get(context).save("SAPISID=abc123; other=x", "APIKEY", "0")
        }
        GoogleVoiceClient.baseOverrideForTest = "http://127.0.0.1:${server.address.port}/"
    }

    @AfterTest
    fun tearDown() {
        GoogleVoiceClient.baseOverrideForTest = null
        runCatching { server.stop(0) }
        // Leave WhatsApp/Signal singletons inert for later tests in this sandbox.
        WhatsAppClient.stateMutable.value = WhatsAppClient.State.Idle
        WhatsAppClient.webSocket = null
        WhatsAppDatabase.overrideForTest = null
        SignalDatabase.overrideForTest = null
        SignalClient.authData = null
        SignalClient.e2e = null
        SignalClient.socket = null
    }

    // ---------- Google Voice voicemails / merged ----------

    @Test
    fun `google voice voicemails parse folder four and merge newest first`() = runTest {
        val entries = CommunicateRepository.loadGoogleVoiceVoicemails(context)
        assertEquals(2, entries.size)
        val first = entries.first { it.remoteId == "c.vm1" }
        assertEquals("+14155550199", first.phoneNumber)
        assertEquals("hello world", first.transcript)
        assertEquals(42L, first.durationSeconds)
        assertTrue(first.hasAudio)
        assertEquals("https://voice.google.com/media/vm1.mp3", first.contentUri)
        assertFalse(first.isRead) // slot 13 == 8 is unread
        assertEquals(CommunicateLine.GoogleVoice, first.line)
        assertTrue(first.audioHeaders["Cookie"]?.contains("SAPISID=abc123") == true)
        val second = entries.first { it.remoteId == "c.vm2" }
        assertTrue(second.isRead)
        assertEquals(null, second.transcript)
        assertFalse(second.hasAudio)

        val merged = CommunicateRepository.loadVoicemailsMerged(context)
        assertEquals(listOf("c.vm1", "c.vm2"), merged.map { it.remoteId })
        assertTrue(merged.all { it.line == CommunicateLine.GoogleVoice })
        assertTrue(requests.any { it.contains("api2thread/list") && it.contains("[4,") }, "$requests")
    }

    @Test
    fun `mark and delete voicemail entries route to batchupdateattributes`() = runTest {
        val entries = CommunicateRepository.loadGoogleVoiceVoicemails(context)
        val vm = entries.first { it.remoteId == "c.vm1" }
        requests.clear()
        assertTrue(CommunicateRepository.markVoicemailEntryRead(context, vm))
        assertTrue(
            requests.any { it.contains("batchupdateattributes") && it.contains("c.vm1") && it.contains(",null,null,1") },
            "$requests",
        )
        requests.clear()
        assertTrue(CommunicateRepository.deleteVoicemailEntry(context, vm))
        assertTrue(
            requests.any { it.contains("batchupdateattributes") && it.contains("c.vm1") && it.contains(",null,1]") },
            "$requests",
        )
        // No remote id: both operations fail without a request.
        requests.clear()
        val orphan = vm.copy(remoteId = null)
        assertFalse(CommunicateRepository.markVoicemailEntryRead(context, orphan))
        assertFalse(CommunicateRepository.deleteVoicemailEntry(context, orphan))
        assertTrue(requests.isEmpty())
    }

    @Test
    fun `updateGoogleVoiceThread sends the action and reports server failure`() = runTest {
        assertTrue(
            CommunicateRepository.updateGoogleVoiceThread(
                context, "t.+14152124034", GoogleVoiceParser.ThreadAction.MarkUnread,
            ),
        )
        assertTrue(
            requests.any {
                it.contains("batchupdateattributes") && it.contains("t.+14152124034") && it.contains(",null,null,0")
            },
            "$requests",
        )
        assertTrue(
            CommunicateRepository.updateGoogleVoiceThread(
                context, "t.+14152124034", GoogleVoiceParser.ThreadAction.Archive,
            ),
        )
        failBatch = true
        assertFalse(
            CommunicateRepository.updateGoogleVoiceThread(
                context, "t.+14152124034", GoogleVoiceParser.ThreadAction.MarkRead,
            ),
        )
    }

    @Test
    fun `google voice threads and calls load through merged loaders`() = runTest {
        val threads = CommunicateRepository.loadGoogleVoiceThreads(context)
        assertEquals(1, threads.size)
        assertEquals("t.+14152124034", threads.single().remoteId)
        assertEquals("+14152124034", threads.single().address)
        assertEquals("Hello there", threads.single().snippet)
        assertEquals(CommunicateLine.GoogleVoice, threads.single().line)

        val calls = CommunicateRepository.loadGoogleVoiceCalls(context)
        assertEquals(1, calls.size)
        assertEquals("+14155550199", calls.single().phoneNumber)
        assertEquals(65L, calls.single().durationSeconds)
        assertEquals(CommunicateCallType.Incoming, calls.single().type)
        assertEquals(CommunicateLine.GoogleVoice, calls.single().line)

        val mergedThreads = CommunicateRepository.loadSmsThreadsMerged(context)
        assertTrue(mergedThreads.any { it.remoteId == "t.+14152124034" && it.line == CommunicateLine.GoogleVoice })
        val mergedCalls = CommunicateRepository.loadCallLogsMerged(context)
        assertTrue(mergedCalls.any { it.phoneNumber == "+14155550199" && it.line == CommunicateLine.GoogleVoice })
    }

    @Test
    fun `google voice send fails closed without an activity for the web token`() = runTest {
        // Application context is not an Activity: mintToken cannot run, send is false.
        assertFalse(
            CommunicateRepository.sendMessage(
                context, LineChoice.GoogleVoice, "+14155550199", "gv hello y3",
            ),
        )
    }

    // ---------- Signal send (offline, fail-closed through the repository) ----------

    @Test
    fun `signal send through repository fails closed offline and caches nothing`() = runTest {
        val ctx = context
        val sigDb = Room.inMemoryDatabaseBuilder(ctx, SignalDatabase::class.java)
            .setDriver(BundledSQLiteDriver()).allowMainThreadQueries().build()
        try {
            SignalClient.get(ctx)
            SignalClient.db = sigDb
            SignalDatabase.overrideForTest = sigDb
            SignalClient.e2e = null
            SignalClient.socket = null
            SignalClient.unauthSocket = null
            SignalClient.authData = SignalAuthData(
                phoneNumber = "+14155552671",
                aci = "aaaaaaaa-1111-2222-3333-444455556666",
                password = "secret",
            )
            val peer = "3f0e5a2c-1111-2222-3333-444455556666"
            assertFalse(
                CommunicateRepository.sendMessage(ctx, LineChoice.Signal, peer, "signal hello y3"),
            )
            assertFalse(
                CommunicateRepository.sendMessage(ctx, LineChoice.Signal, peer, " "),
            )
            assertTrue(sigDb.cachedMessageDao().getForConversation(peer).isEmpty())
        } finally {
            SignalDatabase.overrideForTest = null
            sigDb.close()
        }
    }

    // ---------- WhatsApp: socket seam, shareContact vCard, text send ----------

    companion object {
        fun b64(b: ByteArray): String = Base64.encodeToString(b, Base64.NO_WRAP)
        fun unb64(s: String): ByteArray = Base64.decode(s, Base64.NO_WRAP)
        fun authFor(idSeed: Int, spkSeed: Int, wid: String, lid: String): WhatsAppAuthData {
            val idPriv = ByteArray(32) { (idSeed + it).toByte() }
            val idPub = RustWhatsAppCrypto.publicFromPrivate(idPriv)!!
            val spkPriv = ByteArray(32) { (spkSeed + it).toByte() }
            val spkPub = RustWhatsAppCrypto.publicFromPrivate(spkPriv)!!
            val sig = WhatsAppE2E.signSignedPreKey(idPriv, spkPub)
            return WhatsAppAuthData(
                phoneNumber = "+15550001", pushName = "Tester", wid = wid,
                noisePrivateKey = b64(ByteArray(32) { 1 }), noisePublicKey = b64(ByteArray(32) { 2 }),
                identityPrivateKey = b64(idPriv), identityPublicKey = b64(idPub),
                registrationId = 14, signedPreKeyId = 5,
                signedPreKeyPublic = b64(spkPub), signedPreKeyPrivate = b64(spkPriv),
                signedPreKeySignature = b64(sig), lid = lid,
            )
        }
        val AUTH: WhatsAppAuthData by lazy { authFor(1, 41, "15550001@s.whatsapp.net", "12345@lid") }
        val PEER: WhatsAppAuthData by lazy { authFor(101, 141, "15550002@s.whatsapp.net", "67890@lid") }
        const val PEER_JID = "15550002@s.whatsapp.net"
    }

    private class FakeSocket(auth: WhatsAppAuthData) {
        val socket = WhatsAppSocket(auth)
        private val out = ByteArrayOutputStream()
        private val decryptor: Any
        init {
            val key = SecretKeySpec(ByteArray(32) { 42 }, "AES")
            val noiseClass = Class.forName(
                "com.vayunmathur.communicate.data.whatsapp.transport.WhatsAppSocket\$NoiseSocket")
            val ctor = noiseClass.getDeclaredConstructor(
                SecretKeySpec::class.java, SecretKeySpec::class.java).apply { isAccessible = true }
            val noise = ctor.newInstance(key, key)
            decryptor = ctor.newInstance(key, key)
            setField(socket, "noiseSocket", noise)
            setField(socket, "output", out)
            setField(socket, "isConnected", true)
            setField(socket, "isHandshakeComplete", true)
        }
        fun drainNodes(): List<WhatsAppProtocol.Node> {
            val bytes = synchronized(out) { val b = out.toByteArray(); out.reset(); b }
            val decrypt = decryptor.javaClass.getDeclaredMethod("decrypt", ByteArray::class.java)
                .apply { isAccessible = true }
            val nodes = mutableListOf<WhatsAppProtocol.Node>()
            var off = 0
            while (off + 3 <= bytes.size) {
                val len = ((bytes[off].toInt() and 0xFF) shl 16) or
                    ((bytes[off + 1].toInt() and 0xFF) shl 8) or (bytes[off + 2].toInt() and 0xFF)
                val frame = bytes.copyOfRange(off + 3, off + 3 + len)
                nodes += WhatsAppProtocol.decodeNode(decrypt.invoke(decryptor, frame) as ByteArray)
                off += 3 + len
            }
            return nodes
        }
        companion object {
            fun setField(target: Any, name: String, value: Any?) {
                val f = target.javaClass.getDeclaredField(name).apply { isAccessible = true }
                f.set(target, value)
            }
        }
    }

    private lateinit var waDb: WhatsAppDatabase
    private lateinit var peerDb: WhatsAppDatabase
    private lateinit var fake: FakeSocket
    private val sentNodes = Collections.synchronizedList(mutableListOf<WhatsAppProtocol.Node>())

    private fun newWaDb(): WhatsAppDatabase {
        val app = ApplicationProvider.getApplicationContext<Application>()
        return Room.inMemoryDatabaseBuilder(app, WhatsAppDatabase::class.java)
            .setSingleConnectionPool().allowMainThreadQueries().build()
    }

    private fun setUpWhatsApp() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        waDb = newWaDb(); peerDb = newWaDb()
        WhatsAppClient.appContext = app
        WhatsAppClient.authData = AUTH
        WhatsAppClient.db = waDb
        WhatsAppDatabase.overrideForTest = waDb
        FakeSocket.setField(WhatsAppClient, "e2e", null)
        FakeSocket.setField(WhatsAppClient, "mediaConnCache", null)
        fake = FakeSocket(AUTH)
        WhatsAppClient.webSocket = fake.socket
        WhatsAppClient.stateMutable.value = WhatsAppClient.State.Connected
        WhatsAppClient.suppressReconnect = false
        WhatsAppClient.readReceiptsEnabled = true
        WhatsAppClient.pendingMessageIDs.clear()
        WhatsAppClient.knownGroups.clear()
        WhatsAppClient.recentSentDMs.clear()
        WhatsAppClient.pendingIqs.clear()
        WhatsAppDiag.clear(); sentNodes.clear()
        val crypto = WhatsAppClient.ensureE2E(AUTH)
        assertNotNull(crypto)
        crypto.processPreKeyBundle(
            PEER_JID,
            WhatsAppE2E.ParsedPreKeyBundle(
                registrationId = PEER.registrationId, preKeyId = null, preKeyPublic = null,
                signedPreKeyId = PEER.signedPreKeyId, signedPreKeyPublic = unb64(PEER.signedPreKeyPublic),
                signedPreKeySignature = unb64(PEER.signedPreKeySignature),
                identityKey = unb64(PEER.identityPublicKey),
            ),
        )
        assertTrue(crypto.hasSession(PEER_JID))
    }

    private fun iqResult(req: WhatsAppProtocol.Node, content: List<WhatsAppProtocol.Node> = emptyList()) =
        WhatsAppProtocol.Node(
            tag = "iq",
            attrs = mapOf(
                "id" to (req.attrs["id"] ?: ""), "type" to "result",
                "from" to (req.attrs["to"] ?: "s.whatsapp.net"),
            ),
            content = content,
        )

    private fun respond(req: WhatsAppProtocol.Node): WhatsAppProtocol.Node? {
        if (req.tag != "iq") return null
        if (req.attrs["xmlns"] == "usync") {
            val users = req.getChildByTag("usync")?.getChildByTag("list")?.getChildren()
                ?.filter { it.tag == "user" } ?: emptyList()
            val userNodes = users.map { u ->
                val jid = u.attrs["jid"] ?: ""
                val devices = when {
                    jid.startsWith("15550002") -> listOf(0)
                    jid.startsWith("15550001") -> listOf(0)
                    else -> emptyList()
                }
                WhatsAppProtocol.Node(
                    tag = "user", attrs = mapOf("jid" to jid),
                    content = listOf(WhatsAppProtocol.Node(
                        tag = "devices",
                        content = listOf(WhatsAppProtocol.Node(
                            tag = "device-list",
                            content = devices.map { d ->
                                WhatsAppProtocol.Node(tag = "device", attrs = mapOf("id" to d.toString()))
                            },
                        )),
                    )),
                )
            }
            return iqResult(req, listOf(WhatsAppProtocol.Node(
                tag = "usync", content = listOf(WhatsAppProtocol.Node(tag = "list", content = userNodes)))))
        }
        return iqResult(req)
    }

    private fun <T> drive(block: suspend () -> T): T {
        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        val deferred = scope.async { block() }
        val deadline = System.currentTimeMillis() + 30_000
        while (!deferred.isCompleted) {
            val nodes = fake.drainNodes()
            if (nodes.isNotEmpty()) {
                sentNodes.addAll(nodes)
                nodes.forEach { n -> respond(n)?.let { WhatsAppClient.handleIncomingMessage(WhatsAppProtocol.encodeNode(it)) } }
            }
            if (System.currentTimeMillis() > deadline) {
                deferred.cancel()
                error("drive timeout; sent=${sentNodes.map { it.tag to it.attrs }} diag=${WhatsAppDiag.log.value.takeLast(6)}")
            }
            Thread.sleep(15)
        }
        sentNodes.addAll(fake.drainNodes())
        return runBlocking { deferred.await() }
    }

    private fun decryptAsPeer(message: WhatsAppProtocol.Node): String {
        fun walk(n: WhatsAppProtocol.Node): List<WhatsAppProtocol.Node> =
            listOf(n) + n.getChildren().flatMap { walk(it) }
        val enc = walk(message).first { it.tag == "enc" }
        val peerE2e = WhatsAppE2E(peerDb, PEER)
        val plain = peerE2e.decryptDM(AUTH.wid, isPreKey = enc.attrs["type"] == "pkmsg", ciphertext = enc.data!!)
        val proto = WhatsAppE2EProto.Message.parseFrom(WhatsAppProtocol.unpadMessage(plain))
        return proto.contactMessage.vcard
    }

    @Test
    fun `shareContact builds exact vCard and peer decrypts it`() {
        setUpWhatsApp()
        try {
            val card = SharedContactCard(
                givenName = "Ada", familyName = "Lovelace",
                phoneNumbers = listOf("+14155550101" to "Work", "+14155550102" to null, "+14155550103" to ""),
                emails = listOf("ada@example.com", "countess@example.com"),
            )
            val ok = drive {
                CommunicateRepository.shareContact(context, CommunicateLine.WhatsApp, PEER_JID, card)
            }
            assertTrue(ok)
            val messages = synchronized(sentNodes) { sentNodes.filter { it.tag == "message" } }
            assertEquals(1, messages.size)
            val vcard = decryptAsPeer(messages.single())
            assertTrue(vcard.startsWith("BEGIN:VCARD\nVERSION:3.0"), vcard)
            assertTrue(vcard.contains("N:Lovelace;Ada;;;"), vcard)
            assertTrue(vcard.contains("FN:Ada Lovelace"), vcard)
            assertTrue(vcard.contains("TEL;TYPE=Work:+14155550101"), vcard)
            assertTrue(vcard.contains("TEL;TYPE=CELL:+14155550102"), vcard)
            assertTrue(vcard.contains("TEL;TYPE=CELL:+14155550103"), vcard)
            assertTrue(vcard.contains("EMAIL:ada@example.com"), vcard)
            assertTrue(vcard.contains("EMAIL:countess@example.com"), vcard)
            assertTrue(vcard.trimEnd().endsWith("END:VCARD"), vcard)

            // Blank names: FN/N stay empty, the number still reaches the card.
            val blank = SharedContactCard(
                givenName = "", familyName = null,
                phoneNumbers = listOf("+14155550109" to null), emails = emptyList(),
            )
            val before = synchronized(sentNodes) { sentNodes.count { it.tag == "message" } }
            assertTrue(drive { CommunicateRepository.shareContact(context, CommunicateLine.WhatsApp, PEER_JID, blank) })
            val all = synchronized(sentNodes) { sentNodes.filter { it.tag == "message" } }
            assertEquals(before + 1, all.size)
            val blankCard = decryptAsPeer(all.last())
            assertTrue(blankCard.contains("N:;;;;"), blankCard)
            assertTrue(blankCard.contains("FN:\n") || blankCard.contains("FN:\r"), blankCard)
            assertTrue(blankCard.contains("TEL;TYPE=CELL:+14155550109"), blankCard)
            assertFalse(blankCard.contains("EMAIL:"))

            // Unsupported lines never send.
            assertFalse(runBlocking {
                CommunicateRepository.shareContact(context, CommunicateLine.GoogleVoice, PEER_JID, card)
            })
            assertFalse(runBlocking {
                CommunicateRepository.shareContact(context, CommunicateLine.Sim, PEER_JID, card)
            })
        } finally {
            WhatsAppDatabase.overrideForTest = null
            runCatching { waDb.close() }; runCatching { peerDb.close() }
        }
    }

    @Test
    fun `whatsapp text send through repository succeeds and caches the echo`() {
        setUpWhatsApp()
        try {
            val ok = drive {
                CommunicateRepository.sendMessage(
                    context, LineChoice.WhatsApp, PEER_JID, "wa hello y3",
                    threadRemoteId = PEER_JID,
                )
            }
            assertTrue(ok)
            val messages = synchronized(sentNodes) { sentNodes.filter { it.tag == "message" } }
            assertTrue(messages.isNotEmpty())
            // cacheOutgoingWhatsApp writes to the on-disk WhatsAppDatabase singleton.
            val cached = runBlocking { CommunicateRepository.loadWhatsAppMessages(context, PEER_JID) }
            assertTrue(cached.any { it.body == "wa hello y3" && it.outgoing }, "$cached")
        } finally {
            WhatsAppDatabase.overrideForTest = null
            runCatching { waDb.close() }; runCatching { peerDb.close() }
        }
    }
}
