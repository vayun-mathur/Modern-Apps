package com.vayunmathur.communicate.whatsapp

import android.app.Application
import android.util.Base64
import androidx.room3.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.protobuf.ByteString
import com.vayunmathur.communicate.data.whatsapp.SentDM
import com.vayunmathur.communicate.data.whatsapp.WhatsAppAuthData
import com.vayunmathur.communicate.data.whatsapp.WhatsAppClient
import com.vayunmathur.communicate.data.whatsapp.WhatsAppDatabase
import com.vayunmathur.communicate.data.whatsapp.WhatsAppDiag
import com.vayunmathur.communicate.data.whatsapp.WhatsAppEvent
import com.vayunmathur.communicate.data.whatsapp.WhatsAppProtocol
import com.vayunmathur.communicate.data.whatsapp.buildIncomingMediaAttachment
import com.vayunmathur.communicate.data.whatsapp.buildPollVoteMessage
import com.vayunmathur.communicate.data.whatsapp.createGroup
import com.vayunmathur.communicate.data.whatsapp.cropResizeAvatar
import com.vayunmathur.communicate.data.whatsapp.e2e.RustWhatsAppCrypto
import com.vayunmathur.communicate.data.whatsapp.e2e.WhatsAppE2E
import com.vayunmathur.communicate.data.whatsapp.encodeNode
import com.vayunmathur.communicate.data.whatsapp.extractJid
import com.vayunmathur.communicate.data.whatsapp.fetchAndEmitGroupInfo
import com.vayunmathur.communicate.data.whatsapp.generateClientId
import com.vayunmathur.communicate.data.whatsapp.generateRef
import com.vayunmathur.communicate.data.whatsapp.generateToken
import com.vayunmathur.communicate.data.whatsapp.getContactSuggestions
import com.vayunmathur.communicate.data.whatsapp.getUserDevices
import com.vayunmathur.communicate.data.whatsapp.handleAccountSync
import com.vayunmathur.communicate.data.whatsapp.handleChatPresence
import com.vayunmathur.communicate.data.whatsapp.handleDirty
import com.vayunmathur.communicate.data.whatsapp.handleIdentityChange
import com.vayunmathur.communicate.data.whatsapp.handleIncomingMessage
import com.vayunmathur.communicate.data.whatsapp.handleNotification
import com.vayunmathur.communicate.data.whatsapp.handlePictureUpdate
import com.vayunmathur.communicate.data.whatsapp.handleRetryReceipt
import com.vayunmathur.communicate.data.whatsapp.isLoggedIn
import com.vayunmathur.communicate.data.whatsapp.isOwnJid
import com.vayunmathur.communicate.data.whatsapp.logoutRemote
import com.vayunmathur.communicate.data.whatsapp.padMessage
import com.vayunmathur.communicate.data.whatsapp.proto.WhatsAppE2EProto
import com.vayunmathur.communicate.data.whatsapp.queryGroupParticipants
import com.vayunmathur.communicate.data.whatsapp.resolveChatSender
import com.vayunmathur.communicate.data.whatsapp.resolveDeviceContactName
import com.vayunmathur.communicate.data.whatsapp.resolveJID
import com.vayunmathur.communicate.data.whatsapp.resolveMentionsInBody
import com.vayunmathur.communicate.data.whatsapp.resolveName
import com.vayunmathur.communicate.data.whatsapp.sendRetryReceipt
import com.vayunmathur.communicate.data.whatsapp.setGroupAvatar
import com.vayunmathur.communicate.data.whatsapp.setGroupName
import com.vayunmathur.communicate.data.whatsapp.setGroupTopic
import com.vayunmathur.communicate.data.whatsapp.transport.WhatsAppSocket
import com.vayunmathur.communicate.data.whatsapp.updateGroupParticipants
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.util.Collections
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.runner.RunWith

/**
 * Y1: real inbound behaviour for WhatsAppClientInbound/Retry/Groups/Utils.
 * Plain-body stanzas drive the no-crypto routing; encrypted stanzas are produced
 * by a genuine peer [WhatsAppE2E] (host Rust crypto) so pkmsg/skmsg decrypt,
 * edit/revoke/reaction/poll/history/app-state branches run end to end offline.
 */
@RunWith(AndroidJUnit4::class)
class Y1WaInboundTest {

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
    }

    private lateinit var db: WhatsAppDatabase
    private val events = Collections.synchronizedList(mutableListOf<WhatsAppEvent>())

    @BeforeTest
    fun setUp() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        db = Room.inMemoryDatabaseBuilder(app, WhatsAppDatabase::class.java)
            .setSingleConnectionPool().allowMainThreadQueries().build()
        WhatsAppClient.appContext = app
        WhatsAppClient.authData = AUTH
        WhatsAppClient.db = db
        WhatsAppClient.webSocket = WhatsAppSocket(AUTH)
        WhatsAppClient.stateMutable.value = WhatsAppClient.State.Connected
        WhatsAppClient.suppressReconnect = false
        WhatsAppClient.fullHistorySync = false
        listOf(
            WhatsAppClient.nameCache, WhatsAppClient.lidToPhoneMap, WhatsAppClient.undecryptableTracker,
            WhatsAppClient.retryResendCounts, WhatsAppClient.pollSecrets,
        ).forEach { it.clear() }
        WhatsAppClient.pendingMessageIDs.clear(); WhatsAppClient.processedEditIDs.clear()
        WhatsAppClient.knownGroups.clear(); WhatsAppClient.recentSentDMs.clear()
        WhatsAppClient.pendingIqs.clear(); WhatsAppClient.onDemandRequested.clear()
        WhatsAppClient.appStateKeyRequested.clear()
        WhatsAppDiag.clear(); events.clear()
        CoroutineScope(Dispatchers.Default + Job()).launch { WhatsAppClient.events.collect { events.add(it) } }
        // WhatsAppClient.events is a SharedFlow with no replay: an event
        // emitted before this collector's subscription lands is dropped, so
        // under load the first feed could race the subscription and its
        // event was simply never recorded. Wait for the subscription itself.
        com.vayunmathur.testsupport.awaitWithMainIdle(
            "events collector subscribed", maxSteps = 100_000,
        ) { WhatsAppClient.eventsMutable.subscriptionCount.value > 0 }
    }

    @AfterTest
    fun tearDown() {
        WhatsAppClient.fullHistorySync = true
        WhatsAppClient.authData = AUTH
    }

    private fun await(label: String, cond: () -> Boolean) {
        // Terminal-signal wait, not a wall clock: each step idles the main
        // looper and yields so the IO-scope stanza pipeline and the Default
        // event collector can actually run under CPU caps. The old 15 s
        // sleep-poll expired while that work was merely starved.
        try {
            // cond reads `events`, a synchronizedList whose iterators are
            // not themselves synchronized: evaluating the filter without
            // holding the list's lock races the collector's adds (a tight
            // pumped loop hits that race far sooner than a sleep-poll did).
            com.vayunmathur.testsupport.awaitWithMainIdle(label, maxSteps = 100_000) {
                synchronized(events) { cond() }
            }
        } catch (e: IllegalStateException) {
            error("timeout $label diag=${WhatsAppDiag.log.value.takeLast(5)} events=$events")
        }
    }

    private fun feed(node: WhatsAppProtocol.Node) =
        WhatsAppClient.handleIncomingMessage(WhatsAppProtocol.encodeNode(node))

    private fun peerEncryptNode(msg: WhatsAppE2EProto.Message, id: String, from: String = PEER_JID, participant: String? = null, extra: Map<String, String> = emptyMap()): WhatsAppProtocol.Node {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val peerDb = Room.inMemoryDatabaseBuilder(app, WhatsAppDatabase::class.java)
            .setSingleConnectionPool().allowMainThreadQueries().build()
        val peer = WhatsAppE2E(peerDb, PEER)
        peer.processPreKeyBundle(
            AUTH.wid,
            WhatsAppE2E.ParsedPreKeyBundle(
                registrationId = AUTH.registrationId, preKeyId = null, preKeyPublic = null,
                signedPreKeyId = AUTH.signedPreKeyId, signedPreKeyPublic = unb64(AUTH.signedPreKeyPublic),
                signedPreKeySignature = unb64(AUTH.signedPreKeySignature), identityKey = unb64(AUTH.identityPublicKey),
            ),
        )
        val enc = peer.encryptDM(AUTH.wid, WhatsAppProtocol.padMessage(msg.toByteArray()))
        val attrs = mutableMapOf("from" to from, "id" to id, "type" to "text", "t" to "1700000000")
        if (participant != null) attrs["participant"] = participant
        attrs.putAll(extra)
        return WhatsAppProtocol.Node(
            tag = "message", attrs = attrs,
            content = listOf(WhatsAppProtocol.Node(tag = "enc", attrs = mapOf("type" to enc.type, "v" to "2"), data = enc.data)),
        )
    }

    private fun feedEncrypted(msg: WhatsAppE2EProto.Message, id: String, from: String = PEER_JID, participant: String? = null, extra: Map<String, String> = emptyMap()) =
        feed(peerEncryptNode(msg, id, from, participant, extra))

    private fun conv(text: String) = WhatsAppE2EProto.Message.newBuilder().setConversation(text).build()

    @Test
    fun `encrypted DM emits incoming with notify name and group DM attributes sender`() {
        // Note: a body-only stanza is (correctly) treated as undecryptable by the
        // client, so live routing is exercised with real encrypted stanzas.
        feedEncrypted(conv("hello plain"), "p1", extra = mapOf("notify" to "Peer Name"))
        await("dm") { events.filterIsInstance<WhatsAppEvent.IncomingMessage>().any { it.messageId == "p1" } }
        val dm = events.filterIsInstance<WhatsAppEvent.IncomingMessage>().first { it.messageId == "p1" }
        assertEquals("wa:$PEER_JID", dm.conversationId); assertEquals("hello plain", dm.body)
        assertEquals("Peer Name", dm.peerName); assertEquals(1_700_000_000_000L, dm.timestamp)
        assertNull(dm.senderId)
        assertEquals("Peer Name", WhatsAppClient.nameCache[PEER_JID])
        feedEncrypted(conv("group hi"), "p2", from = GROUP_JID, participant = PEER_JID)
        await("group") { events.filterIsInstance<WhatsAppEvent.IncomingMessage>().any { it.messageId == "p2" } }
        val g = events.filterIsInstance<WhatsAppEvent.IncomingMessage>().first { it.messageId == "p2" }
        assertEquals(PEER_JID, g.senderId); assertNotNull(g.senderName)
        assertFalse(WhatsAppClient.knownGroups.contains(GROUP_JID))
    }

    @Test
    fun `encrypted own group message renders as outgoing`() {
        feedEncrypted(conv("mine"), "p3", from = GROUP_JID, participant = AUTH.wid)
        await("own") { events.filterIsInstance<WhatsAppEvent.MessageUpdate>().any { it.messageId == "p3" } }
        val own = events.filterIsInstance<WhatsAppEvent.MessageUpdate>().first { it.messageId == "p3" }
        assertTrue(own.outgoing); assertEquals("mine", own.body)
        assertTrue(events.filterIsInstance<WhatsAppEvent.IncomingMessage>().none { it.messageId == "p3" })
    }

    @Test
    fun `status broadcast and pending dedup are consumed silently`() {
        feedEncrypted(conv("story"), "p4", from = "status@broadcast")
        WhatsAppClient.pendingMessageIDs.add("p5")
        feedEncrypted(conv("dup"), "p5")
        Thread.sleep(700)
        assertTrue(events.none { (it as? WhatsAppEvent.IncomingMessage)?.messageId in setOf("p4", "p5") })
        assertFalse(WhatsAppClient.pendingMessageIDs.contains("p5"))
    }

    @Test
    fun `sender_pn normalises LID addressed encrypted message to phone conversation`() {
        feedEncrypted(conv("lid hi"), "p6", from = "99999@lid", extra = mapOf("sender_pn" to PEER_JID))
        await("lid") { events.filterIsInstance<WhatsAppEvent.IncomingMessage>().any { it.messageId == "p6" } }
        assertEquals("wa:$PEER_JID", events.filterIsInstance<WhatsAppEvent.IncomingMessage>().first { it.messageId == "p6" }.conversationId)
    }

    @Test
    fun `failure stanzas map reasons to disconnected states`() {
        fun failure(reason: String) = feed(WhatsAppProtocol.Node(tag = "failure", attrs = mapOf("reason" to reason)))
        failure("405"); await("405") { WhatsAppClient.stateMutable.value.toString().contains("Client outdated") }
        failure("503"); await("503") { WhatsAppClient.stateMutable.value.toString().contains("Temporarily banned") }
        failure("999"); await("999") { WhatsAppClient.stateMutable.value.toString().contains("Connection failed: 999") }
        failure("401"); await("401") { WhatsAppClient.stateMutable.value.toString().contains("Logged out") }
        Thread.sleep(300); WhatsAppClient.authData = AUTH
        WhatsAppClient.stateMutable.value = WhatsAppClient.State.Connected
    }

    @Test
    fun `stream error conflict 401 515 and generic branches`() {
        fun stream(attrs: Map<String, String>, child: WhatsAppProtocol.Node? = null) = feed(
            WhatsAppProtocol.Node(tag = "stream:error", attrs = attrs, content = listOfNotNull(child)),
        )
        stream(mapOf("code" to "515")); await("515") { WhatsAppClient.stateMutable.value.toString().contains("Restart required") }
        stream(emptyMap()); await("generic") { WhatsAppClient.stateMutable.value.toString().contains("Stream error") }
        stream(mapOf("code" to "499"), WhatsAppProtocol.Node(tag = "conflict", attrs = mapOf("type" to "replaced")))
        await("replaced") { WhatsAppClient.stateMutable.value.toString().contains("Session replaced") }
        assertTrue(WhatsAppClient.suppressReconnect)
        stream(mapOf("code" to "401"), WhatsAppProtocol.Node(tag = "conflict", attrs = mapOf("type" to "device_removed")))
        await("removed") { WhatsAppClient.stateMutable.value == WhatsAppClient.State.NeedsSetup }
        await("logged out event") { events.any { it is WhatsAppEvent.SourceLoggedOut } }
        WhatsAppClient.suppressReconnect = false; WhatsAppClient.authData = AUTH
    }

    @Test
    fun `success stanza authenticates and ib blocks are handled`() {
        feed(WhatsAppProtocol.Node(tag = "success", attrs = mapOf("lid" to "12345@lid")))
        await("auth diag") { WhatsAppDiag.log.value.any { it.contains("AUTHENTICATED") } }
        assertEquals(WhatsAppClient.State.Connected, WhatsAppClient.stateMutable.value)
        feed(
            WhatsAppProtocol.Node(
                tag = "ib", content = listOf(
                    WhatsAppProtocol.Node(tag = "dirty", attrs = mapOf("type" to "account_sync", "timestamp" to "42")),
                    WhatsAppProtocol.Node(tag = "offline_preview", attrs = mapOf("count" to "3", "message" to "2")),
                    WhatsAppProtocol.Node(tag = "offline", attrs = mapOf("count" to "3")),
                ),
            ),
        )
        await("dirty") { WhatsAppDiag.log.value.any { it.contains("cleaned dirty state: account_sync") } }
        await("offline") { WhatsAppDiag.log.value.any { it.contains("offline sync completed") } }
    }

    @Test
    fun `ack error iq completion and garbage bytes do not emit chat events`() {
        val deferred = CompletableDeferred<WhatsAppProtocol.Node>()
        WhatsAppClient.pendingIqs["iq-1"] = deferred
        feed(WhatsAppProtocol.Node(tag = "iq", attrs = mapOf("id" to "iq-1", "type" to "result")))
        await("iq done") { deferred.isCompleted }
        feed(WhatsAppProtocol.Node(tag = "iq", attrs = mapOf("id" to "iq-unknown", "type" to "result")))
        feed(WhatsAppProtocol.Node(tag = "ack", attrs = mapOf("class" to "message", "id" to "x", "error" to "404")))
        await("ack diag") { WhatsAppDiag.log.value.any { it.contains("ack REJECTED") } }
        WhatsAppClient.handleIncomingMessage(byteArrayOf(0x7f, 0x01, 0x02))
        WhatsAppClient.handleIncomingMessage(ByteArray(0))
        Thread.sleep(400)
        assertTrue(events.filterIsInstance<WhatsAppEvent.IncomingMessage>().isEmpty())
    }

    @Test
    fun `receipts delivered read read-self list and ignored types`() {
        fun receipt(type: String?, id: String = "m1", list: List<String> = emptyList()) = WhatsAppProtocol.Node(
            tag = "receipt",
            attrs = buildMap { put("from", PEER_JID); put("id", id); put("t", "1700000000"); if (type != null) put("type", type) },
            content = if (list.isEmpty()) emptyList() else listOf(
                WhatsAppProtocol.Node(tag = "list", content = list.map { WhatsAppProtocol.Node(tag = "item", attrs = mapOf("id" to it)) }),
            ),
        )
        feed(receipt(null)); feed(receipt("read", "m2")); feed(receipt("read-self", "m3"))
        feed(receipt("read", "m4", listOf("m5", "m6"))); feed(receipt("played", "m7"))
        feed(WhatsAppProtocol.Node(tag = "receipt", attrs = mapOf("type" to "read")))
        await("receipts") { events.filterIsInstance<WhatsAppEvent.ReadReceipt>().size >= 5 }
        val r = events.filterIsInstance<WhatsAppEvent.ReadReceipt>()
        assertTrue(r.first { it.messageId == "m1" }.isDelivery)
        assertFalse(r.first { it.messageId == "m2" }.isDelivery)
        assertEquals(setOf("m4", "m5", "m6"), r.mapNotNull { it.messageId }.filter { it in setOf("m4", "m5", "m6") }.toSet())
        assertTrue(r.none { it.messageId == "m7" })
        assertTrue(r.all { it.conversationId == "wa:$PEER_JID" && it.senderId == PEER_JID })
    }

    @Test
    fun `chat presence composing paused and LID participant reroute`() = runTest {
        WhatsAppClient.lidToPhoneMap["67890@lid"] = PEER_JID
        WhatsAppClient.handleChatPresence(
            WhatsAppProtocol.Node(tag = "chatstate", attrs = mapOf("from" to GROUP_JID, "participant" to "67890@lid"),
                content = listOf(WhatsAppProtocol.Node(tag = "composing", attrs = mapOf("media" to "audio")))),
        )
        WhatsAppClient.handleChatPresence(
            WhatsAppProtocol.Node(tag = "chatstate", attrs = mapOf("from" to PEER_JID),
                content = listOf(WhatsAppProtocol.Node(tag = "paused"))),
        )
        WhatsAppClient.handleChatPresence(WhatsAppProtocol.Node(tag = "chatstate"))
        await("typing") { events.filterIsInstance<WhatsAppEvent.TypingIndicator>().size >= 2 }
        val t = events.filterIsInstance<WhatsAppEvent.TypingIndicator>()
        assertTrue(t[0].isTyping); assertEquals(PEER_JID, t[0].senderId); assertEquals("wa:$GROUP_JID", t[0].conversationId)
        assertFalse(t[1].isTyping)
    }

    @Test
    fun `identity picture and account sync direct handlers`() = runTest {
        val notif = WhatsAppProtocol.Node(tag = "notification", attrs = mapOf("from" to PEER_JID, "participant" to "777@s.whatsapp.net", "t" to "1700000000"),
            content = listOf(WhatsAppProtocol.Node(tag = "identity")))
        WhatsAppClient.handleIdentityChange(notif, PEER_JID)
        await("idchange") { events.filterIsInstance<WhatsAppEvent.IncomingMessage>().any { it.body.contains("Security code changed") } }
        WhatsAppClient.handleIdentityChange(WhatsAppProtocol.Node(tag = "notification", attrs = mapOf("from" to PEER_JID)), PEER_JID)
        WhatsAppClient.handlePictureUpdate(WhatsAppProtocol.Node(tag = "notification", content = listOf(WhatsAppProtocol.Node(tag = "set"))), PEER_JID)
        WhatsAppClient.handlePictureUpdate(WhatsAppProtocol.Node(tag = "notification", content = listOf(WhatsAppProtocol.Node(tag = "delete"))), PEER_JID)
        WhatsAppClient.handlePictureUpdate(WhatsAppProtocol.Node(tag = "notification"), PEER_JID)
        WhatsAppClient.handleAccountSync(
            WhatsAppProtocol.Node(tag = "notification", attrs = mapOf("from" to "s.whatsapp.net"), content = listOf(
                WhatsAppProtocol.Node(tag = "push", attrs = mapOf("name" to "Pushy", "jid" to "777@s.whatsapp.net")),
                WhatsAppProtocol.Node(tag = "contact", attrs = mapOf("name" to "Connie", "jid" to "888@s.whatsapp.net")),
                WhatsAppProtocol.Node(tag = "push", attrs = mapOf("jid" to "999@s.whatsapp.net")),
            )),
        )
        assertEquals("Pushy", WhatsAppClient.nameCache["777@s.whatsapp.net"])
        assertEquals("Connie", WhatsAppClient.nameCache["888@s.whatsapp.net"])
        assertNull(WhatsAppClient.nameCache["999@s.whatsapp.net"])
        WhatsAppClient.handleNotification(WhatsAppProtocol.Node(tag = "notification"))
        WhatsAppClient.handleNotification(WhatsAppProtocol.Node(tag = "notification", attrs = mapOf("type" to "devices", "from" to PEER_JID)))
        WhatsAppClient.handleDirty(WhatsAppProtocol.Node(tag = "dirty"))
    }

    @Test
    fun `undecryptable plaintext and garbage ciphertext fail closed with retry receipts`() {
        repeat(2) { feed(WhatsAppProtocol.Node(tag = "message", attrs = mapOf("from" to PEER_JID, "id" to "u1", "type" to "text"))) }
        await("tracker") { (WhatsAppClient.undecryptableTracker[PEER_JID] ?: 0) >= 2 }
        assertTrue(events.filterIsInstance<WhatsAppEvent.IncomingMessage>().none { it.messageId == "u1" })
        feed(
            WhatsAppProtocol.Node(tag = "message", attrs = mapOf("from" to PEER_JID, "id" to "bad1", "type" to "text"),
                content = listOf(WhatsAppProtocol.Node(tag = "enc", attrs = mapOf("type" to "msg"), data = byteArrayOf(9, 9, 9)))),
        )
        await("decrypt failed") { WhatsAppDiag.log.value.any { it.contains("DECRYPT FAILED") && it.contains("bad1") } }
        assertTrue(events.filterIsInstance<WhatsAppEvent.IncomingMessage>().none { it.messageId == "bad1" })
    }

    @Test
    fun `sendRetryReceipt caps at five and drops id-less nodes`() = runTest {
        val node = WhatsAppProtocol.Node(tag = "message", attrs = mapOf("id" to "retry-me", "from" to PEER_JID, "type" to "text"))
        repeat(6) { WhatsAppClient.sendRetryReceipt(node) }
        assertTrue((WhatsAppClient.undecryptableTracker["retry:retry-me"] ?: 0) > 5)
        WhatsAppClient.sendRetryReceipt(WhatsAppProtocol.Node(tag = "message", attrs = mapOf("from" to PEER_JID)))
        WhatsAppClient.webSocket = null; WhatsAppClient.sendRetryReceipt(node)
        WhatsAppClient.webSocket = WhatsAppSocket(AUTH)
    }

    @Test
    fun `retry receipt resends cached DM then exhausts budget and ignores groups`() = runTest {
        feedEncrypted(conv("seed session"), "seed-1")
        await("seed") { events.filterIsInstance<WhatsAppEvent.IncomingMessage>().any { it.messageId == "seed-1" } }
        val padded = WhatsAppProtocol.padMessage(conv("cached hello").toByteArray())
        WhatsAppClient.recentSentDMs["cached-1"] = SentDM(to = PEER_JID, type = "text", msgPlaintextPadded = padded, dsmPlaintextPadded = null)
        fun retry(id: String, from: String, participant: String? = null) = WhatsAppProtocol.Node(
            tag = "receipt", attrs = buildMap { put("id", id); put("from", from); put("type", "retry"); if (participant != null) put("participant", participant) },
        )
        WhatsAppClient.handleRetryReceipt(retry("cached-1", PEER_JID))
        await("resent") { WhatsAppDiag.log.value.any { it.contains("retry: resent cached-1") } }
        WhatsAppClient.handleRetryReceipt(retry("nope-1", PEER_JID))
        await("uncached") { WhatsAppDiag.log.value.any { it.contains("no cached message for nope-1") } }
        WhatsAppClient.handleRetryReceipt(retry("cached-1", GROUP_JID))
        WhatsAppClient.handleRetryReceipt(WhatsAppProtocol.Node(tag = "receipt", attrs = mapOf("type" to "retry")))
        repeat(6) { WhatsAppClient.handleRetryReceipt(retry("cached-1", PEER_JID)) }
        await("budget") { WhatsAppDiag.log.value.any { it.contains("retry: giving up on cached-1") } }
    }

    @Test
    fun `encrypted DM text media view-once forwarded and own-device echo`() {
        feedEncrypted(conv("secret hello"), "e1")
        await("e1") { events.filterIsInstance<WhatsAppEvent.IncomingMessage>().any { it.messageId == "e1" } }
        assertEquals("secret hello", events.filterIsInstance<WhatsAppEvent.IncomingMessage>().first { it.messageId == "e1" }.body)
        val image = WhatsAppE2EProto.Message.newBuilder()
            .setImageMessage(WhatsAppE2EProto.ImageMessage.newBuilder().setMimetype("image/jpeg").setCaption("a pic")).build()
        feedEncrypted(image, "e2")
        await("e2") { events.filterIsInstance<WhatsAppEvent.IncomingMessage>().any { it.messageId == "e2" } }
        val img = events.filterIsInstance<WhatsAppEvent.IncomingMessage>().first { it.messageId == "e2" }
        assertEquals("a pic", img.body); assertTrue(img.attachments.isEmpty())
        val voInner = WhatsAppE2EProto.Message.newBuilder()
            .setImageMessage(WhatsAppE2EProto.ImageMessage.newBuilder().setMimetype("image/jpeg")).build()
        val vo = WhatsAppE2EProto.Message.newBuilder()
            .setViewOnceMessageV2(WhatsAppE2EProto.FutureProofMessage.newBuilder().setMessage(voInner)).build()
        feedEncrypted(vo, "e3")
        await("e3") { events.filterIsInstance<WhatsAppEvent.IncomingMessage>().any { it.messageId == "e3" } }
        assertTrue(events.filterIsInstance<WhatsAppEvent.IncomingMessage>().first { it.messageId == "e3" }.body.contains("View once"))
        val ctx = WhatsAppE2EProto.ContextInfo.newBuilder().setIsForwarded(true).setForwardingScore(2)
            .addMentionedJid(AUTH.wid).build()
        val fwd = WhatsAppE2EProto.Message.newBuilder()
            .setExtendedTextMessage(WhatsAppE2EProto.ExtendedTextMessage.newBuilder().setText("hi @15550001").setContextInfo(ctx)).build()
        feedEncrypted(fwd, "e4")
        await("e4") { events.filterIsInstance<WhatsAppEvent.IncomingMessage>().any { it.messageId == "e4" } }
        val f = events.filterIsInstance<WhatsAppEvent.IncomingMessage>().first { it.messageId == "e4" }
        assertTrue(f.body.contains("Forwarded") && f.body.contains("@You"), f.body)
        val dsm = WhatsAppE2EProto.Message.newBuilder().setDeviceSentMessage(
            WhatsAppE2EProto.DeviceSentMessage.newBuilder().setDestinationJid("15550009@s.whatsapp.net").setMessage(conv("from laptop")),
        ).build()
        feedEncrypted(dsm, "e5")
        await("e5") { events.filterIsInstance<WhatsAppEvent.MessageUpdate>().any { it.messageId == "e5" } }
        val own = events.filterIsInstance<WhatsAppEvent.MessageUpdate>().first { it.messageId == "e5" }
        assertTrue(own.outgoing); assertEquals("wa:15550009@s.whatsapp.net", own.conversationId)
    }

    @Test
    fun `encrypted revoke edit dedup ephemeral and reactions consume the stanza`() {
        fun key(id: String) = WhatsAppE2EProto.MessageKey.newBuilder().setId(id).setFromMe(false).setRemoteJid(PEER_JID).build()
        val revoke = WhatsAppE2EProto.Message.newBuilder().setProtocolMessage(
            WhatsAppE2EProto.ProtocolMessage.newBuilder().setType(WhatsAppE2EProto.ProtocolMessage.Type.REVOKE).setKey(key("target-1")),
        ).build()
        feedEncrypted(revoke, "r1")
        await("revoke") { events.filterIsInstance<WhatsAppEvent.MessageDeleted>().any { it.messageId == "target-1" } }
        fun edit(body: String) = WhatsAppE2EProto.Message.newBuilder().setProtocolMessage(
            WhatsAppE2EProto.ProtocolMessage.newBuilder().setType(WhatsAppE2EProto.ProtocolMessage.Type.MESSAGE_EDIT)
                .setKey(key("target-2")).setEditedMessage(conv(body)),
        ).build()
        feedEncrypted(edit("edited body"), "edit-1")
        await("edit") { events.filterIsInstance<WhatsAppEvent.MessageEdited>().any { it.newBody == "edited body" } }
        feedEncrypted(edit("edited again"), "edit-1")
        // No settle wait needed for the count: dedup is an atomic
        // ConcurrentHashMap-set add (processedEditIDs), so whenever the
        // duplicate stanza is processed it emits nothing — the count is 1
        // at every instant after the first edit above, loaded or not.
        assertEquals(1, events.filterIsInstance<WhatsAppEvent.MessageEdited>().size)
        val eph = WhatsAppE2EProto.Message.newBuilder().setProtocolMessage(
            WhatsAppE2EProto.ProtocolMessage.newBuilder().setType(WhatsAppE2EProto.ProtocolMessage.Type.EPHEMERAL_SETTING).setEphemeralExpiration(86400),
        ).build()
        feedEncrypted(eph, "eph1")
        await("eph") { events.filterIsInstance<WhatsAppEvent.IncomingMessage>().any { it.messageId == "eph1" } }
        fun reaction(emoji: String) = WhatsAppE2EProto.Message.newBuilder().setReactionMessage(
            WhatsAppE2EProto.ReactionMessage.newBuilder().setKey(key("target-3")).setText(emoji),
        ).build()
        feedEncrypted(reaction("👍"), "rx1")
        await("reaction") { events.filterIsInstance<WhatsAppEvent.ReactionReceived>().any { it.messageId == "target-3" } }
        feedEncrypted(reaction(""), "rx2")
        await("reaction removed") { events.filterIsInstance<WhatsAppEvent.ReactionRemoved>().any { it.messageId == "target-3" } }
    }

    @Test
    fun `encrypted poll creation stores options and poll vote decrypts to names`() {
        val secret = ByteArray(32) { (it * 3 + 5).toByte() }
        val poll = WhatsAppE2EProto.Message.newBuilder()
            .setPollCreationMessageV3(
                WhatsAppE2EProto.PollCreationMessage.newBuilder().setName("Lunch?")
                    .addOptions(WhatsAppE2EProto.PollCreationMessage.Option.newBuilder().setOptionName("Pizza"))
                    .addOptions(WhatsAppE2EProto.PollCreationMessage.Option.newBuilder().setOptionName("Sushi")),
            )
            .setMessageContextInfo(WhatsAppE2EProto.MessageContextInfo.newBuilder().setMessageSecret(ByteString.copyFrom(secret)))
            .build()
        feedEncrypted(poll, "poll-1")
        await("poll") { events.filterIsInstance<WhatsAppEvent.IncomingMessage>().any { it.messageId == "poll-1" } }
        val p = events.filterIsInstance<WhatsAppEvent.IncomingMessage>().first { it.messageId == "poll-1" }
        assertEquals("Lunch?", p.pollQuestion); assertEquals(listOf("Pizza", "Sushi"), p.pollOptions)
        val hash = MessageDigest.getInstance("SHA-256").digest("Sushi".toByteArray())
        val voteMsg = WhatsAppProtocol.buildPollVoteMessage(PEER_JID, "poll-1", PEER_JID, pollFromMe = false, PEER_JID, listOf(hash), secret)
        feedEncrypted(voteMsg, "vote-1")
        await("vote") { events.filterIsInstance<WhatsAppEvent.PollVote>().any { it.pollMessageId == "poll-1" } }
        val v = events.filterIsInstance<WhatsAppEvent.PollVote>().first { it.pollMessageId == "poll-1" }
        assertEquals(listOf("Sushi"), v.optionNames); assertEquals(PEER_JID, v.voterId)
        val orphan = WhatsAppProtocol.buildPollVoteMessage(PEER_JID, "poll-unknown", PEER_JID, false, PEER_JID, listOf(hash), secret)
        feedEncrypted(orphan, "vote-2"); Thread.sleep(400)
        assertEquals(1, events.filterIsInstance<WhatsAppEvent.PollVote>().size)
    }

    @Test
    fun `encrypted SKDM establishes sender key then group skmsg decrypts`() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val peerDb = Room.inMemoryDatabaseBuilder(app, WhatsAppDatabase::class.java)
            .setSingleConnectionPool().allowMainThreadQueries().build()
        val peer = WhatsAppE2E(peerDb, PEER)
        peer.processPreKeyBundle(
            AUTH.wid,
            WhatsAppE2E.ParsedPreKeyBundle(AUTH.registrationId, null, null, AUTH.signedPreKeyId,
                unb64(AUTH.signedPreKeyPublic), unb64(AUTH.signedPreKeySignature), unb64(AUTH.identityPublicKey)),
        )
        val skdmBytes = peer.createSenderKeyDistribution(GROUP_JID)
        val skdmMsg = WhatsAppE2EProto.Message.newBuilder().setSenderKeyDistributionMessage(
            WhatsAppE2EProto.SenderKeyDistributionMessage.newBuilder().setGroupId(GROUP_JID)
                .setAxolotlSenderKeyDistributionMessage(ByteString.copyFrom(skdmBytes)),
        ).build()
        val enc1 = peer.encryptDM(AUTH.wid, WhatsAppProtocol.padMessage(skdmMsg.toByteArray()))
        feed(
            WhatsAppProtocol.Node(tag = "message", attrs = mapOf("from" to GROUP_JID, "id" to "skdm-1", "type" to "text", "participant" to PEER_JID),
                content = listOf(WhatsAppProtocol.Node(tag = "enc", attrs = mapOf("type" to enc1.type), data = enc1.data))),
        )
        Thread.sleep(600)
        assertTrue(events.filterIsInstance<WhatsAppEvent.IncomingMessage>().none { it.messageId == "skdm-1" })
        val groupCipher = peer.encryptGroup(GROUP_JID, WhatsAppProtocol.padMessage(conv("group secret").toByteArray()))
        feed(
            WhatsAppProtocol.Node(tag = "message", attrs = mapOf("from" to GROUP_JID, "id" to "g1", "type" to "text", "participant" to PEER_JID),
                content = listOf(WhatsAppProtocol.Node(tag = "enc", attrs = mapOf("type" to "skmsg"), data = groupCipher))),
        )
        await("group msg") { events.filterIsInstance<WhatsAppEvent.IncomingMessage>().any { it.messageId == "g1" } }
        assertEquals("group secret", events.filterIsInstance<WhatsAppEvent.IncomingMessage>().first { it.messageId == "g1" }.body)
    }

    @Test
    fun `encrypted history sync and app-state key share protocol messages are routed`() {
        val history = WhatsAppE2EProto.HistorySync.newBuilder().setSyncType(0)
            .addConversations(
                WhatsAppE2EProto.HsConversation.newBuilder().setId(PEER_JID).setName("Peer")
                    .addMessages(
                        WhatsAppE2EProto.HistorySyncMsg.newBuilder().setMessage(
                            WhatsAppE2EProto.WebMessageInfo.newBuilder()
                                .setKey(WhatsAppE2EProto.HsMsgKey.newBuilder().setId("h1").setFromMe(false).setRemoteJid(PEER_JID))
                                .setMessage(conv("hist hello")).setMessageTimestamp(1700000000L),
                        ),
                    ),
            ).build()
        fun deflate(data: ByteArray): ByteArray {
            val d = java.util.zip.Deflater(); d.setInput(data); d.finish()
            val out = ByteArrayOutputStream(); val buf = ByteArray(4096)
            while (!d.finished()) out.write(buf, 0, d.deflate(buf)); d.end(); return out.toByteArray()
        }
        val histMsg = WhatsAppE2EProto.Message.newBuilder().setProtocolMessage(
            WhatsAppE2EProto.ProtocolMessage.newBuilder().setType(WhatsAppE2EProto.ProtocolMessage.Type.HISTORY_SYNC_NOTIFICATION)
                .setHistorySyncNotification(
                    WhatsAppE2EProto.HistorySyncNotification.newBuilder()
                        .setInitialHistBootstrapInlinePayload(ByteString.copyFrom(deflate(history.toByteArray()))),
                ),
        ).build()
        feedEncrypted(histMsg, "hist-notif")
        await("hist") { events.filterIsInstance<WhatsAppEvent.MessageUpdate>().any { it.body == "hist hello" } }
        val shareMsg = WhatsAppE2EProto.Message.newBuilder().setProtocolMessage(
            WhatsAppE2EProto.ProtocolMessage.newBuilder().setType(WhatsAppE2EProto.ProtocolMessage.Type.APP_STATE_SYNC_KEY_SHARE)
                .setAppStateSyncKeyShare(
                    WhatsAppE2EProto.AppStateSyncKeyShare.newBuilder().addKeys(
                        WhatsAppE2EProto.AppStateSyncKey.newBuilder()
                            .setKeyId(WhatsAppE2EProto.AppStateSyncKeyId.newBuilder().setKeyId(ByteString.copyFrom(byteArrayOf(7, 7))))
                            .setKeyData(WhatsAppE2EProto.AppStateSyncKeyData.newBuilder().setKeyData(ByteString.copyFrom(ByteArray(32) { 9 }))),
                    ),
                ),
        ).build()
        feedEncrypted(shareMsg, "share-1")
        await("share") { WhatsAppDiag.log.value.any { it.contains("stored 1 sync key(s)") } }
    }

    @Test
    fun `group operations validate state jid action and fail soft on dead socket`() = runTest {
        assertEquals(emptyList(), WhatsAppClient.queryGroupParticipants(GROUP_JID))
        assertEquals(emptyList(), WhatsAppClient.getUserDevices(emptyList()))
        assertEquals(emptyList(), WhatsAppClient.getUserDevices(listOf("777", PEER_JID, "777:2@s.whatsapp.net")))
        assertFalse(WhatsAppClient.setGroupName(GROUP_JID, "Name"))
        assertFalse(WhatsAppClient.setGroupName(PEER_JID, "Name"))
        assertFalse(WhatsAppClient.setGroupName("wa:", "Name"))
        assertFalse(WhatsAppClient.setGroupTopic(GROUP_JID, "Topic", previousTopicId = "old"))
        assertFalse(WhatsAppClient.updateGroupParticipants(GROUP_JID, listOf(PEER_JID), "add"))
        assertFalse(WhatsAppClient.updateGroupParticipants(GROUP_JID, listOf(PEER_JID), "demote"))
        assertFalse(WhatsAppClient.updateGroupParticipants(GROUP_JID, listOf(PEER_JID), "explode"))
        assertFalse(WhatsAppClient.updateGroupParticipants(PEER_JID, listOf(PEER_JID), "add"))
        assertNull(WhatsAppClient.createGroup("Subject", listOf(PEER_JID)))
        assertNull(WhatsAppClient.createGroup("Subject", emptyList()))
        WhatsAppClient.stateMutable.value = WhatsAppClient.State.Idle
        assertFalse(WhatsAppClient.setGroupName(GROUP_JID, "Name"))
        assertFalse(WhatsAppClient.setGroupTopic(GROUP_JID, "Topic"))
        assertFalse(WhatsAppClient.updateGroupParticipants(GROUP_JID, listOf(PEER_JID), "add"))
        assertFalse(WhatsAppClient.setGroupAvatar(GROUP_JID, ByteArray(4)))
        assertNull(WhatsAppClient.createGroup("Subject", listOf(PEER_JID)))
        WhatsAppClient.stateMutable.value = WhatsAppClient.State.Connected
        WhatsAppClient.fetchAndEmitGroupInfo(PEER_JID)
        WhatsAppClient.knownGroups.add(GROUP_JID); WhatsAppClient.fetchAndEmitGroupInfo(GROUP_JID)
        WhatsAppClient.knownGroups.remove(GROUP_JID); WhatsAppClient.fetchAndEmitGroupInfo(GROUP_JID)
        assertFalse(WhatsAppClient.knownGroups.contains(GROUP_JID))
    }

    @Test
    fun `avatar crop scales small and large images to jpeg bounds`() = runTest {
        fun png(w: Int, h: Int): ByteArray {
            val bmp = android.graphics.Bitmap.createBitmap(w, h, android.graphics.Bitmap.Config.ARGB_8888)
            return ByteArrayOutputStream().also { bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
        }
        for ((w, h) in listOf(100 to 50, 800 to 400, 300 to 300)) {
            val out = WhatsAppClient.cropResizeAvatar(png(w, h))
            assertNotNull(out); assertTrue(out.size > 2)
            assertEquals(0xFF.toByte(), out[0]); assertEquals(0xD8.toByte(), out[1])
        }
        assertFalse(WhatsAppClient.setGroupAvatar(PEER_JID, png(200, 200)))
        assertFalse(WhatsAppClient.setGroupAvatar(GROUP_JID, png(200, 200)))
    }

    @Test
    fun `utils jid name mention sender and generator edge cases`() = runTest {
        assertEquals(PEER_JID, WhatsAppClient.extractJid("wa:wa:$PEER_JID")); assertNull(WhatsAppClient.extractJid("wa:"))
        WhatsAppClient.lidToPhoneMap["777@lid.extra"] = PEER_JID
        assertEquals(PEER_JID, WhatsAppClient.resolveJID("777@lid"))
        assertEquals(AUTH.wid, WhatsAppClient.resolveJID("12345@lid"))
        assertEquals("zzz@lid", WhatsAppClient.resolveJID("zzz@lid"))
        assertEquals(GROUP_JID, WhatsAppClient.resolveJID(GROUP_JID))
        assertTrue(WhatsAppClient.isOwnJid("15550001:3@s.whatsapp.net"))
        assertTrue(WhatsAppClient.isOwnJid("12345@lid")); assertFalse(WhatsAppClient.isOwnJid("999@s.whatsapp.net"))
        assertFalse(WhatsAppClient.isOwnJid(null)); assertFalse(WhatsAppClient.isOwnJid("@s.whatsapp.net"))
        assertNull(WhatsAppClient.resolveDeviceContactName(null)); assertNull(WhatsAppClient.resolveDeviceContactName(""))
        assertEquals("Meta AI", WhatsAppClient.resolveName("0@bot"))
        assertEquals("+12345", WhatsAppClient.resolveName("12345-678@g.us"))
        assertEquals("hi @You and @Meta AI", WhatsAppClient.resolveMentionsInBody("hi @15550001 and @0", listOf(AUTH.wid, "0@bot")))
        assertEquals("plain", WhatsAppClient.resolveMentionsInBody("plain", listOf(PEER_JID)))
        val (sender, fromMe) = WhatsAppClient.resolveChatSender(
            com.vayunmathur.communicate.data.whatsapp.WhatsAppMessage(id = "x", from = GROUP_JID, to = "", body = "b", timestamp = 1, type = "text", participant = "67890@lid"),
        )
        assertEquals("67890@lid", sender); assertFalse(fromMe)
        assertEquals(24, WhatsAppClient.generateRef().length); assertEquals(24, WhatsAppClient.generateClientId().length)
        assertEquals(32, WhatsAppClient.generateToken().length)
        assertTrue(WhatsAppClient.isLoggedIn())
        WhatsAppClient.stateMutable.value = WhatsAppClient.State.Idle; assertFalse(WhatsAppClient.isLoggedIn())
        WhatsAppClient.stateMutable.value = WhatsAppClient.State.Connected
        WhatsAppClient.nameCache.clear()
        repeat(12) { WhatsAppClient.nameCache["u$it@s.whatsapp.net"] = "User $it" }
        assertEquals(10, WhatsAppClient.getContactSuggestions("user").size)
        assertEquals("User 3", WhatsAppClient.getContactSuggestions("user 3").single().displayName)
        assertNull(WhatsAppClient.buildIncomingMediaAttachment(conv("hi"), "m1"))
        val noKey = WhatsAppE2EProto.Message.newBuilder()
            .setImageMessage(WhatsAppE2EProto.ImageMessage.newBuilder().setUrl("https://example.invalid/x")).build()
        assertNull(WhatsAppClient.buildIncomingMediaAttachment(noKey, "m2"))
        val noUrl = WhatsAppE2EProto.Message.newBuilder()
            .setDocumentMessage(WhatsAppE2EProto.DocumentMessage.newBuilder().setMediaKey(ByteString.copyFrom(ByteArray(32)))).build()
        assertNull(WhatsAppClient.buildIncomingMediaAttachment(noUrl, "m3"))
        WhatsAppClient.logoutRemote(); Thread.sleep(300)
    }
}
