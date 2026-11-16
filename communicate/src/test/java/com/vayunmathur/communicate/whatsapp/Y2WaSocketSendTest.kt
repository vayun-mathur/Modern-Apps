package com.vayunmathur.communicate.whatsapp

import android.app.Application
import android.util.Base64
import androidx.room3.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.vayunmathur.communicate.data.whatsapp.WhatsAppAuthData
import com.vayunmathur.communicate.data.whatsapp.WhatsAppClient
import com.vayunmathur.communicate.data.whatsapp.WhatsAppDatabase
import com.vayunmathur.communicate.data.whatsapp.WhatsAppDiag
import com.vayunmathur.communicate.data.whatsapp.WhatsAppEvent
import com.vayunmathur.communicate.data.whatsapp.WhatsAppProtocol
import com.vayunmathur.communicate.data.whatsapp.createGroup
import com.vayunmathur.communicate.data.whatsapp.e2e.RustWhatsAppCrypto
import com.vayunmathur.communicate.data.whatsapp.e2e.WhatsAppE2E
import com.vayunmathur.communicate.data.whatsapp.decodeNode
import com.vayunmathur.communicate.data.whatsapp.encodeNode
import com.vayunmathur.communicate.data.whatsapp.fetchAndEmitGroupInfo
import com.vayunmathur.communicate.data.whatsapp.getUserDevices
import com.vayunmathur.communicate.data.whatsapp.handleIncomingMessage
import com.vayunmathur.communicate.data.whatsapp.markRead
import com.vayunmathur.communicate.data.whatsapp.queryGroupParticipants
import com.vayunmathur.communicate.data.whatsapp.refreshPrivacySettings
import com.vayunmathur.communicate.data.whatsapp.rejectCall
import com.vayunmathur.communicate.data.whatsapp.sendCallOffer
import com.vayunmathur.communicate.data.whatsapp.sendContact
import com.vayunmathur.communicate.data.whatsapp.sendEdit
import com.vayunmathur.communicate.data.whatsapp.sendLocation
import com.vayunmathur.communicate.data.whatsapp.sendMedia
import com.vayunmathur.communicate.data.whatsapp.sendMessage
import com.vayunmathur.communicate.data.whatsapp.sendMessageAdvanced
import com.vayunmathur.communicate.data.whatsapp.sendPoll
import com.vayunmathur.communicate.data.whatsapp.sendPollCreation
import com.vayunmathur.communicate.data.whatsapp.sendPollVote
import com.vayunmathur.communicate.data.whatsapp.sendReaction
import com.vayunmathur.communicate.data.whatsapp.removeReaction
import com.vayunmathur.communicate.data.whatsapp.sendReadReceipt
import com.vayunmathur.communicate.data.whatsapp.sendRevoke
import com.vayunmathur.communicate.data.whatsapp.sendTyping
import com.vayunmathur.communicate.data.whatsapp.setDisappearingTimer
import com.vayunmathur.communicate.data.whatsapp.setGroupAvatar
import com.vayunmathur.communicate.data.whatsapp.setGroupName
import com.vayunmathur.communicate.data.whatsapp.setGroupTopic
import com.vayunmathur.communicate.data.whatsapp.updateGroupParticipants
import com.vayunmathur.communicate.data.whatsapp.transport.WhatsAppSocket
import java.io.ByteArrayOutputStream
import java.util.Collections
import javax.crypto.spec.SecretKeySpec
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
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.runner.RunWith

/**
 * Y2 wave-1: WhatsAppSocket transport seam + the send surface that sits on it.
 *
 * A real [WhatsAppSocket] is armed by reflection (private isConnected /
 * isHandshakeComplete / noiseSocket / output): a NoiseSocket with a known AES
 * key writes framed ciphertext into a ByteArrayOutputStream, and a twin
 * NoiseSocket (same key, read side) decrypts the frames back into binary-XML
 * nodes so stanza shapes are asserted, not just byte counts. The peer E2E
 * session is genuine: the local [WhatsAppE2E] processes the peer's pre-key
 * bundle (Rust crypto), so fan-out encryption really succeeds. IQ waits are
 * completed promptly by feeding crafted IQ-result stanzas through
 * [WhatsAppClient.handleIncomingMessage].
 */
@RunWith(AndroidJUnit4::class)
class Y2WaSocketSendTest {

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
        const val GROUP_JID = "12345@g.us"
        const val NEW_GROUP_JID = "120363001@g.us"
    }

    /** A WhatsAppSocket armed via reflection; captured frames decrypt to nodes. */
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
        fun setConnected(v: Boolean) = setField(socket, "isConnected", v)
        fun drainPlain(): List<ByteArray> {
            val bytes = synchronized(out) { val b = out.toByteArray(); out.reset(); b }
            val plain = mutableListOf<ByteArray>()
            var off = 0
            val decrypt = decryptor.javaClass.getDeclaredMethod("decrypt", ByteArray::class.java)
                .apply { isAccessible = true }
            while (off + 3 <= bytes.size) {
                val len = ((bytes[off].toInt() and 0xFF) shl 16) or
                    ((bytes[off + 1].toInt() and 0xFF) shl 8) or (bytes[off + 2].toInt() and 0xFF)
                assertTrue(off + 3 + len <= bytes.size, "truncated frame")
                val frame = bytes.copyOfRange(off + 3, off + 3 + len)
                plain.add(decrypt.invoke(decryptor, frame) as ByteArray)
                off += 3 + len
            }
            assertEquals(bytes.size, off, "trailing bytes after frames")
            return plain
        }
        fun drainNodes(): List<WhatsAppProtocol.Node> = drainPlain().map { WhatsAppProtocol.decodeNode(it) }
        companion object {
            fun setField(target: Any, name: String, value: Any?) {
                val f = target.javaClass.getDeclaredField(name).apply { isAccessible = true }
                f.set(target, value)
            }
        }
    }

    private lateinit var db: WhatsAppDatabase
    private lateinit var fake: FakeSocket
    private val events = Collections.synchronizedList(mutableListOf<WhatsAppEvent>())
    private val sentNodes = Collections.synchronizedList(mutableListOf<WhatsAppProtocol.Node>())

    private fun setClientField(name: String, value: Any?) {
        val f = WhatsAppClient.javaClass.getDeclaredField(name).apply { isAccessible = true }
        f.set(WhatsAppClient, value)
    }

    @BeforeTest
    fun setUp() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        db = Room.inMemoryDatabaseBuilder(app, WhatsAppDatabase::class.java)
            .setSingleConnectionPool().allowMainThreadQueries().build()
        WhatsAppClient.appContext = app
        WhatsAppClient.authData = AUTH
        WhatsAppClient.db = db
        setClientField("e2e", null)
        setClientField("mediaConnCache", null)
        fake = FakeSocket(AUTH)
        WhatsAppClient.webSocket = fake.socket
        WhatsAppClient.stateMutable.value = WhatsAppClient.State.Connected
        WhatsAppClient.suppressReconnect = false
        WhatsAppClient.readReceiptsEnabled = true
        listOf(
            WhatsAppClient.nameCache, WhatsAppClient.lidToPhoneMap, WhatsAppClient.undecryptableTracker,
            WhatsAppClient.retryResendCounts, WhatsAppClient.pollSecrets,
        ).forEach { it.clear() }
        WhatsAppClient.pendingMessageIDs.clear(); WhatsAppClient.processedEditIDs.clear()
        WhatsAppClient.knownGroups.clear(); WhatsAppClient.recentSentDMs.clear()
        WhatsAppClient.pendingIqs.clear(); WhatsAppClient.onDemandRequested.clear()
        WhatsAppClient.appStateKeyRequested.clear()
        WhatsAppDiag.clear(); events.clear(); sentNodes.clear()
        CoroutineScope(Dispatchers.Default + SupervisorJob()).let { scope ->
            scope.async { WhatsAppClient.events.collect { events.add(it) } }
        }
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

    @AfterTest
    fun tearDown() {
        WhatsAppClient.authData = AUTH
        WhatsAppClient.stateMutable.value = WhatsAppClient.State.Connected
        runCatching { db.close() }
    }

    private fun iqResult(req: WhatsAppProtocol.Node, content: List<WhatsAppProtocol.Node> = emptyList(), type: String = "result") =
        WhatsAppProtocol.Node(
            tag = "iq",
            attrs = mapOf("id" to (req.attrs["id"] ?: ""), "type" to type, "from" to (req.attrs["to"] ?: "s.whatsapp.net")),
            content = content,
        )

    private fun usyncResult(req: WhatsAppProtocol.Node): WhatsAppProtocol.Node {
        val users = req.getChildByTag("usync")?.getChildByTag("list")?.getChildren()
            ?.filter { it.tag == "user" } ?: emptyList()
        val userNodes = users.map { u ->
            val jid = u.attrs["jid"] ?: ""
            val devices = when {
                jid.startsWith("15550002") -> listOf(0, 2, 9)
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
                            WhatsAppProtocol.Node(
                                tag = "device",
                                attrs = buildMap {
                                    put("id", d.toString())
                                    if (d == 9) put("is_hosted", "true")
                                },
                            )
                        },
                    )),
                )),
            )
        }
        val all = userNodes + WhatsAppProtocol.Node(tag = "user", attrs = emptyMap())
        return iqResult(req, listOf(WhatsAppProtocol.Node(
            tag = "usync", content = listOf(WhatsAppProtocol.Node(tag = "list", content = all)))))
    }

    private fun groupResult(req: WhatsAppProtocol.Node): WhatsAppProtocol.Node = iqResult(
        req,
        listOf(WhatsAppProtocol.Node(
            tag = "group",
            attrs = mapOf("subject" to "Hikers", "id" to GROUP_JID),
            content = listOf(
                WhatsAppProtocol.Node(tag = "participant", attrs = mapOf("jid" to PEER_JID)),
                WhatsAppProtocol.Node(tag = "participant", attrs = mapOf("jid" to AUTH.wid)),
                WhatsAppProtocol.Node(tag = "participant", attrs = mapOf("jid" to "999@bot")),
                WhatsAppProtocol.Node(tag = "participant", attrs = emptyMap()),
            ),
        )),
    )

    private fun respond(req: WhatsAppProtocol.Node): WhatsAppProtocol.Node? {
        if (req.tag != "iq") return null
        val id = req.attrs["id"] ?: return null
        if (id.isEmpty()) return null
        return when (req.attrs["xmlns"]) {
            "usync" -> usyncResult(req)
            "w:m" -> iqResult(req, listOf(WhatsAppProtocol.Node(
                tag = "media_conn", attrs = mapOf("auth" to "tok123", "ttl" to "300"))))
            "privacy" -> iqResult(req, listOf(WhatsAppProtocol.Node(
                tag = "privacy",
                content = listOf(
                    WhatsAppProtocol.Node(tag = "category", attrs = mapOf("name" to "readreceipts", "value" to "none")),
                    WhatsAppProtocol.Node(tag = "category", attrs = mapOf("name" to "profile", "value" to "all")),
                ))))
            "encrypt" -> iqResult(
                req,
                listOf(WhatsAppProtocol.Node(tag = "error", attrs = mapOf("code" to "404"))),
                type = "error")
            "w:g2" -> when {
                req.getChildByTag("create") != null -> {
                    val subject = req.getChildByTag("create")?.attrs?.get("subject")
                    if (subject == "Doomed") {
                        iqResult(req, listOf(WhatsAppProtocol.Node(
                            tag = "error", attrs = mapOf("code" to "401", "text" to "forbidden"))), type = "error")
                    } else {
                        iqResult(req, listOf(WhatsAppProtocol.Node(
                            tag = "group", attrs = mapOf("id" to "120363001"))))
                    }
                }
                req.getChildByTag("query") != null -> groupResult(req)
                else -> iqResult(req)
            }
            else -> iqResult(req)
        }
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

    private fun awaitEvents(label: String, cond: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 10_000
        while (System.currentTimeMillis() < deadline) {
            if (cond()) return
            Thread.sleep(25)
        }
        error("timeout $label events=$events diag=${WhatsAppDiag.log.value.takeLast(5)}")
    }

    private fun messageStanzas(): List<WhatsAppProtocol.Node> =
        synchronized(sentNodes) { sentNodes.filter { it.tag == "message" } }

    @Test
    fun `socket send gates on handshake state and frames decrypt in order`() {
        val fresh = WhatsAppSocket(AUTH)
        assertFalse(fresh.send(byteArrayOf(1, 2, 3)))
        assertTrue(fake.socket.send("first".toByteArray()))
        assertTrue(fake.socket.send("second stanza".toByteArray()))
        val plains = fake.drainPlain()
        assertEquals(listOf("first", "second stanza"), plains.map { it.decodeToString() })
        fake.setConnected(false)
        assertFalse(fake.socket.send(byteArrayOf(9)))
        assertTrue(fake.drainPlain().isEmpty())
        fake.setConnected(true)
        assertTrue(fake.socket.send(ByteArray(0)))
        assertEquals(1, fake.drainPlain().size)
    }

    @Test
    fun `dm text and advanced sends encrypt to the peer device`() {
        val id = drive { WhatsAppClient.sendMessage("wa:$PEER_JID", "hello y2") }
        assertNotNull(id)
        assertTrue(WhatsAppClient.pendingMessageIDs.contains(id))
        assertTrue(WhatsAppClient.recentSentDMs.containsKey(id))
        val msg = messageStanzas().last { it.attrs["id"] == id }
        assertEquals(PEER_JID, msg.attrs["to"]); assertEquals("text", msg.attrs["type"])
        fun walk(n: WhatsAppProtocol.Node): List<WhatsAppProtocol.Node> =
            listOf(n) + n.getChildren().flatMap { walk(it) }
        val encs = walk(msg).filter { it.tag == "enc" }
        assertTrue(encs.isNotEmpty(), "no enc in $msg")
        assertTrue(encs.any { it.attrs["type"] == "pkmsg" || it.attrs["type"] == "msg" })

        val advId = drive {
            WhatsAppClient.sendMessageAdvanced(
                PEER_JID, "hi @15550002 with quote",
                mentionedJids = listOf(PEER_JID),
                quoted = WhatsAppProtocol.QuotedContext(
                    stanzaId = "q1", participant = PEER_JID),
                linkPreview = WhatsAppProtocol.LinkPreview(
                    matchedText = "https://example.com/x", canonicalUrl = "https://example.com/x",
                    title = "Example", description = "desc"),
            )
        }
        assertNotNull(advId)
        val adv = messageStanzas().last { it.attrs["id"] == advId }
        assertEquals(PEER_JID, adv.attrs["to"])

        val before = messageStanzas().size
        assertNull(drive { WhatsAppClient.sendMessage("wa:", "nobody") })
        WhatsAppClient.stateMutable.value = WhatsAppClient.State.Idle
        assertNull(drive { WhatsAppClient.sendMessage(PEER_JID, "offline") })
        WhatsAppClient.stateMutable.value = WhatsAppClient.State.Connected
        assertEquals(before, messageStanzas().size)
    }

    @Test
    fun `group text and advanced sends use sender key with bot participants dropped`() {
        val id = drive { WhatsAppClient.sendMessage(GROUP_JID, "group hello") }
        assertNotNull(id)
        val msg = messageStanzas().last { it.attrs["id"] == id }
        assertEquals(GROUP_JID, msg.attrs["to"])
        val allTags = msg.getChildren().flatMap { listOf(it) + it.getChildren() }
        assertTrue(allTags.any { it.tag == "enc" && it.attrs["type"] == "skmsg" }, "no skmsg in $msg")
        val participants = allTags.filter { it.tag == "enc" }.mapNotNull { it.attrs["to"] }
        assertFalse(participants.any { it.contains("bot") }, "bot in $participants")

        val advId = drive { WhatsAppClient.sendMessageAdvanced("wa:$GROUP_JID", "group adv", mentionedJids = listOf(PEER_JID)) }
        assertNotNull(advId)
        assertEquals(GROUP_JID, messageStanzas().last { it.attrs["id"] == advId }.attrs["to"])
    }

    @Test
    fun `media send fails at upload after media conn IQ and cleans pending`() {
        val sent = drive {
            WhatsAppClient.sendMedia(PEER_JID, byteArrayOf(1, 2, 3, 4), "image/png", "x.png")
        }
        assertFalse(sent)
        val iq = synchronized(sentNodes) {
            sentNodes.firstOrNull { it.tag == "iq" && it.attrs["xmlns"] == "w:m" }
        }
        assertNotNull(iq)
        assertTrue(WhatsAppDiag.log.value.any { it.contains("media:") }, "${WhatsAppDiag.log.value}")
        val huge = ByteArray(51 * 1024 * 1024)
        assertFalse(drive { WhatsAppClient.sendMedia(PEER_JID, huge, "video/mp4", "big.mp4") })
    }

    @Test
    fun `call offer encrypts a key and reject call sends its stanza`() {
        val ok = drive { WhatsAppClient.sendCallOffer(PEER_JID, video = true) }
        assertTrue(ok)
        val call = synchronized(sentNodes) { sentNodes.lastOrNull { it.tag == "call" } }
        assertNotNull(call)
        assertTrue(drive { WhatsAppClient.rejectCall(PEER_JID, "call-1", PEER_JID) })
    }

    @Test
    fun `reaction edit revoke location contact and typing go through encrypted fan out`() {
        assertTrue(drive { WhatsAppClient.sendReaction(PEER_JID, "wa:tgt-1", "👍", false, PEER_JID) })
        assertTrue(drive { WhatsAppClient.removeReaction(PEER_JID, "tgt-1", true, null) })
        assertTrue(drive { WhatsAppClient.sendEdit(PEER_JID, "wa:tgt-2", "edited body") })
        assertTrue(drive { WhatsAppClient.sendRevoke(PEER_JID, "tgt-3", AUTH.wid) })
        assertTrue(drive { WhatsAppClient.sendRevoke(PEER_JID, "tgt-4", PEER_JID) })
        assertTrue(drive { WhatsAppClient.sendLocation(PEER_JID, 37.42, -122.08, "HQ", "1 Hacker Way") })
        assertTrue(drive { WhatsAppClient.sendContact(PEER_JID, "Peer", "BEGIN:VCARD\nFN:Peer\nEND:VCARD") })
        assertTrue(drive { WhatsAppClient.sendReaction(GROUP_JID, "tgt-g", "🎉", false, PEER_JID) })
        assertTrue(drive { WhatsAppClient.sendEdit(GROUP_JID, "tgt-g2", "group edit") })
        assertTrue(drive { WhatsAppClient.sendContact(GROUP_JID, "Peer", "BEGIN:VCARD\nEND:VCARD") })
        val msgs = messageStanzas()
        assertTrue(msgs.any { it.attrs["type"] == "reaction" })
        assertTrue(msgs.any { it.attrs["edit"] == "1" })
        assertTrue(msgs.any { it.attrs["edit"] == "7" })
        assertTrue(msgs.any { it.attrs["edit"] == "8" })

        val beforeTyping = synchronized(sentNodes) { sentNodes.size }
        drive { WhatsAppClient.sendTyping(PEER_JID, true) }
        drive { WhatsAppClient.sendTyping(PEER_JID, false, WhatsAppClient.TypingType.RECORDING_AUDIO) }
        drive { WhatsAppClient.sendTyping(PEER_JID, true, WhatsAppClient.TypingType.UPLOADING_MEDIA) }
        val typing = synchronized(sentNodes) { sentNodes.drop(beforeTyping).filter { it.tag == "chatstate" } }
        assertEquals(2, typing.size)
    }

    @Test
    fun `poll creation vote read receipts markRead and disappearing timer`() {
        val pollId = drive { WhatsAppClient.sendPollCreation(PEER_JID, "Lunch?", listOf("Pizza", "Sushi"), selectableCount = 1) }
        assertNotNull(pollId)
        assertTrue(WhatsAppClient.pollSecrets.containsKey(pollId))
        assertEquals("poll", messageStanzas().last { it.attrs["id"] == pollId }.attrs["type"])

        val poll2 = drive { WhatsAppClient.sendPoll(PEER_JID, "Multi?", listOf("A", "B", "C"), allowMultiple = true) }
        assertNotNull(poll2)
        assertTrue(drive {
            WhatsAppClient.sendPollVote(PEER_JID, pollId, PEER_JID, pollFromMe = false, selectedOptionNames = listOf("Sushi"))
        })
        assertFalse(drive {
            WhatsAppClient.sendPollVote(PEER_JID, "unknown-poll", PEER_JID, false, listOf("X"))
        })

        WhatsAppClient.readReceiptsEnabled = false
        assertTrue(drive { WhatsAppClient.sendReadReceipt(PEER_JID, "wa:m1", 1_700_000_000_000L) })
        WhatsAppClient.readReceiptsEnabled = true
        assertTrue(drive { WhatsAppClient.sendReadReceipt(PEER_JID, "wa:m2", 1_700_000_000L, PEER_JID) })
        assertTrue(drive { WhatsAppClient.sendReadReceipt(PEER_JID, "m3", 1_700_000_000L) })
        assertFalse(drive { WhatsAppClient.sendReadReceipt(PEER_JID, "", 0L) })
        val receipts = synchronized(sentNodes) { sentNodes.filter { it.tag == "receipt" } }
        assertEquals(2, receipts.size)

        drive { WhatsAppClient.markRead(PEER_JID, listOf("m1", "m2")) }
        drive {
            WhatsAppClient.markRead(
                GROUP_JID, listOf("g1", "g2", "g3", "mine"),
                mapOf("g1" to PEER_JID, "g2" to PEER_JID, "g3" to "777@s.whatsapp.net", "mine" to AUTH.wid),
            )
        }
        drive { WhatsAppClient.markRead(PEER_JID, emptyList()) }
        val allReceipts = synchronized(sentNodes) { sentNodes.filter { it.tag == "receipt" } }
        assertTrue(allReceipts.size >= 5, "$allReceipts")

        assertFalse(drive { WhatsAppClient.setDisappearingTimer(PEER_JID, 12345L) })
        assertTrue(drive { WhatsAppClient.setDisappearingTimer(PEER_JID, 86400L) })
        assertTrue(drive { WhatsAppClient.setDisappearingTimer(GROUP_JID, 604800L) })
        assertTrue(drive { WhatsAppClient.setDisappearingTimer(GROUP_JID, 0L) })

        drive { WhatsAppClient.refreshPrivacySettings() }
        assertFalse(WhatsAppClient.readReceiptsEnabled)
        WhatsAppClient.readReceiptsEnabled = true
    }

    @Test
    fun `iq paths resolve devices group info creation and group management`() {
        val devices = drive { WhatsAppClient.getUserDevices(listOf(PEER_JID, AUTH.wid, "999")) }
        assertTrue(devices.contains(PEER_JID), "$devices")
        assertTrue(devices.contains("15550002:2@s.whatsapp.net"), "$devices")
        assertFalse(devices.any { it.contains(":9@") }, "hosted leaked: $devices")
        assertEquals(emptyList(), drive { WhatsAppClient.getUserDevices(emptyList()) })

        val participants = drive { WhatsAppClient.queryGroupParticipants(GROUP_JID) }
        assertEquals(listOf(PEER_JID, AUTH.wid, "999@bot"), participants)

        drive { WhatsAppClient.fetchAndEmitGroupInfo(GROUP_JID) }
        awaitEvents("group info") {
            events.filterIsInstance<WhatsAppEvent.ConversationUpdate>()
                .any { it.conversationId == "wa:$GROUP_JID" && it.peerName == "Hikers" && it.isGroup }
        }
        val upd = events.filterIsInstance<WhatsAppEvent.ConversationUpdate>()
            .first { it.conversationId == "wa:$GROUP_JID" }
        assertEquals(3, upd.participantCount)

        val created = drive { WhatsAppClient.createGroup("New Crew", listOf(PEER_JID)) }
        assertEquals(NEW_GROUP_JID, created)
        assertNull(drive { WhatsAppClient.createGroup("Doomed", listOf(PEER_JID)) })
        assertNull(drive { WhatsAppClient.createGroup("Empty", emptyList()) })

        assertTrue(drive { WhatsAppClient.setGroupName(GROUP_JID, "Renamed") })
        assertTrue(drive { WhatsAppClient.setGroupTopic(GROUP_JID, "A topic", previousTopicId = "old-1") })
        assertTrue(drive { WhatsAppClient.setGroupTopic(GROUP_JID, "", previousTopicId = null) })
        assertTrue(drive { WhatsAppClient.updateGroupParticipants(GROUP_JID, listOf(PEER_JID), "add") })
        assertTrue(drive { WhatsAppClient.updateGroupParticipants(GROUP_JID, listOf(PEER_JID), "promote") })
        assertFalse(drive { WhatsAppClient.updateGroupParticipants(GROUP_JID, listOf(PEER_JID), "explode") })
        assertFalse(drive { WhatsAppClient.setGroupName(PEER_JID, "not a group") })

        val png = ByteArrayOutputStream().also { out ->
            val bmp = android.graphics.Bitmap.createBitmap(240, 240, android.graphics.Bitmap.Config.ARGB_8888)
            bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out)
        }.toByteArray()
        assertTrue(drive { WhatsAppClient.setGroupAvatar(GROUP_JID, png) })
        // Non-group target is rejected before any image processing.
        assertFalse(drive { WhatsAppClient.setGroupAvatar(PEER_JID, png) })

        val groupIqs = synchronized(sentNodes) {
            sentNodes.filter { it.tag == "iq" && it.attrs["to"] == GROUP_JID }
        }
        assertTrue(groupIqs.size >= 6, "$groupIqs")
    }
}
