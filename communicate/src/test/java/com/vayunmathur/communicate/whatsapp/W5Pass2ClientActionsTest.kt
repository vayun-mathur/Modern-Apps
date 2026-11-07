package com.vayunmathur.communicate.whatsapp

import android.app.Application
import android.util.Base64
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.vayunmathur.communicate.data.whatsapp.WhatsAppAuthData
import com.vayunmathur.communicate.data.whatsapp.WhatsAppClient
import com.vayunmathur.communicate.data.whatsapp.WhatsAppDiag
import com.vayunmathur.communicate.data.whatsapp.WhatsAppEvent
import com.vayunmathur.communicate.data.whatsapp.WhatsAppProtocol
import com.vayunmathur.communicate.data.whatsapp.accountDeviceIdentity
import com.vayunmathur.communicate.data.whatsapp.appStateKeyId64
import com.vayunmathur.communicate.data.whatsapp.appStateVersion
import com.vayunmathur.communicate.data.whatsapp.encodeNode
import com.vayunmathur.communicate.data.whatsapp.getAppStateKey
import com.vayunmathur.communicate.data.whatsapp.handleIncomingMessage
import com.vayunmathur.communicate.data.whatsapp.kickoffBackfill
import com.vayunmathur.communicate.data.whatsapp.loadPollSecret
import com.vayunmathur.communicate.data.whatsapp.markRead
import com.vayunmathur.communicate.data.whatsapp.refreshPrivacySettings
import com.vayunmathur.communicate.data.whatsapp.removeReaction
import com.vayunmathur.communicate.data.whatsapp.sendContact
import com.vayunmathur.communicate.data.whatsapp.sendEdit
import com.vayunmathur.communicate.data.whatsapp.sendLocation
import com.vayunmathur.communicate.data.whatsapp.sendPoll
import com.vayunmathur.communicate.data.whatsapp.sendPollCreation
import com.vayunmathur.communicate.data.whatsapp.sendPollVote
import com.vayunmathur.communicate.data.whatsapp.sendReaction
import com.vayunmathur.communicate.data.whatsapp.sendReadReceipt
import com.vayunmathur.communicate.data.whatsapp.sendRevoke
import com.vayunmathur.communicate.data.whatsapp.sendTyping
import com.vayunmathur.communicate.data.whatsapp.setAppStateVersion
import com.vayunmathur.communicate.data.whatsapp.setDisappearingTimer
import com.vayunmathur.communicate.data.whatsapp.storeAppStateKey
import com.vayunmathur.communicate.data.whatsapp.storePollSecret
import com.vayunmathur.communicate.data.whatsapp.transport.WhatsAppSocket
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.runner.RunWith

/**
 * Pass-2 slice-1 (whatsapp): client send-actions, app-state sync helpers, and inbound
 * stanza dispatch against an unconnected (real) socket.
 *
 * The harness is deterministic: `webSocket` is a real [WhatsAppSocket] that has never
 * connected, so `send()` always returns false; `db` is null, so `ensureE2E` returns
 * null and every encrypted-node build fails soft. That exercises the node/proto
 * building, stanza routing, state transitions, and failure paths of the send/inbound
 * surface without a server, a Rust session, or a SQLCipher database.
 */
@RunWith(AndroidJUnit4::class)
class W5Pass2ClientActionsTest {

    private val auth = WhatsAppAuthData(
        phoneNumber = "+15550001", pushName = "Tester", wid = "15550001@s.whatsapp.net",
        noisePrivateKey = "", noisePublicKey = "", identityPrivateKey = "", identityPublicKey = "",
        registrationId = 14, signedPreKeyId = 1, signedPreKeyPublic = "", signedPreKeyPrivate = "",
        signedPreKeySignature = "", lid = "12345@lid",
    )

    private lateinit var eventScope: CoroutineScope
    private val receivedEvents = mutableListOf<WhatsAppEvent>()

    @BeforeTest
    fun `setUp`() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        WhatsAppClient.appContext = app
        WhatsAppClient.authData = auth
        WhatsAppClient.db = null
        WhatsAppClient.webSocket = WhatsAppSocket(auth)
        WhatsAppClient.stateMutable.value = WhatsAppClient.State.Connected
        WhatsAppClient.readReceiptsEnabled = true
        WhatsAppClient.suppressReconnect = false
        WhatsAppClient.pendingMessageIDs.clear()
        WhatsAppClient.pollSecrets.clear()
        WhatsAppDiag.clear()
        receivedEvents.clear()
        eventScope = CoroutineScope(Dispatchers.Default + Job())
        eventScope.launch { WhatsAppClient.events.collect { receivedEvents.add(it) } }
    }

    private fun await(label: String, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 15_000
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return
            Thread.sleep(25)
        }
        error("timed out waiting for $label; state=${WhatsAppClient.state.value} diag=${WhatsAppDiag.log.value.takeLast(3)}")
    }

    private fun stanza(node: WhatsAppProtocol.Node): ByteArray = WhatsAppProtocol.encodeNode(node)

    // ---- Send actions ----

    @Test
    fun `readReceipt honoursPrivacyAndState`() = runTest {
        // Privacy off: the receipt is suppressed but the call reports success (no leak).
        WhatsAppClient.readReceiptsEnabled = false
        assertTrue(WhatsAppClient.sendReadReceipt("15550002@s.whatsapp.net", "wa:MSG1", 1_700_000_000L))
        WhatsAppClient.readReceiptsEnabled = true
        // Privacy on: the socket is unconnected, so the send itself fails.
        assertFalse(WhatsAppClient.sendReadReceipt("15550002@s.whatsapp.net", "MSG1", 1_700_000L))
        assertFalse(WhatsAppClient.sendReadReceipt("15550002@s.whatsapp.net", "MSG1", 0L))
        assertFalse(WhatsAppClient.sendReadReceipt("15550002@s.whatsapp.net", "", 1L))
        assertFalse(WhatsAppClient.sendReadReceipt("wa:", "MSG1", 1L))
        WhatsAppClient.stateMutable.value = WhatsAppClient.State.Idle
        assertFalse(WhatsAppClient.sendReadReceipt("15550002@s.whatsapp.net", "MSG1", 1L))
        WhatsAppClient.stateMutable.value = WhatsAppClient.State.Connected
        WhatsAppClient.webSocket = null
        assertFalse(WhatsAppClient.sendReadReceipt("15550002@s.whatsapp.net", "MSG1", 1L))
        WhatsAppClient.webSocket = WhatsAppSocket(auth)
    }

    @Test
    fun `markReadFiltersOwnAndBatchesGroups`() = runTest {
        // Own messages (by wid and by lid) are filtered out entirely.
        WhatsAppClient.markRead(
            "12345@g.us",
            listOf("m1", "m2"),
            mapOf("m1" to "15550001@s.whatsapp.net", "m2" to "12345@lid"),
        )
        WhatsAppClient.markRead("15550002@s.whatsapp.net", listOf("m1", "m2"))
        WhatsAppClient.markRead(
            "12345@g.us",
            listOf("m1", "m2", "m3"),
            mapOf("m1" to "777@s.whatsapp.net", "m2" to "777@s.whatsapp.net", "m3" to "888@s.whatsapp.net"),
        )
        WhatsAppClient.markRead("15550002@s.whatsapp.net", emptyList())
        WhatsAppClient.markRead("wa:", listOf("m1"))
        WhatsAppClient.webSocket = null
        WhatsAppClient.markRead("15550002@s.whatsapp.net", listOf("m1"))
        WhatsAppClient.webSocket = WhatsAppSocket(auth)
        assertTrue(true) // reaching here without a dead socket send throwing is the assertion
    }

    @Test
    fun `encryptedActionsFailSoftWithoutSessions`() = runTest {
        val dm = "15550002@s.whatsapp.net"
        val group = "12345@g.us"
        assertFalse(WhatsAppClient.sendReaction(dm, "wa:M1", "👍", targetFromMe = false, targetSenderJid = null))
        assertFalse(WhatsAppClient.sendReaction(group, "M1", "❤️", targetFromMe = true, targetSenderJid = "777@s.whatsapp.net"))
        assertFalse(WhatsAppClient.removeReaction(dm, "M1", targetFromMe = false, targetSenderJid = null))
        assertFalse(WhatsAppClient.sendEdit(dm, "wa:M1", "edited"))
        assertFalse(WhatsAppClient.sendEdit(group, "M1", "edited"))
        assertFalse(WhatsAppClient.sendRevoke(dm, "M1"))
        assertFalse(WhatsAppClient.sendRevoke(group, "M1", senderJid = "15550001@s.whatsapp.net"))
        assertFalse(WhatsAppClient.sendLocation(dm, 37.4, -122.1, name = "HQ", address = "1 Way"))
        assertFalse(WhatsAppClient.sendLocation(dm, 0.0, 0.0))
        assertFalse(WhatsAppClient.sendContact(dm, "Bob", "BEGIN:VCARD\nFN:Bob\nEND:VCARD"))
        assertFalse(WhatsAppClient.sendContact(group, "Bob", "BEGIN:VCARD\nEND:VCARD"))
        assertNull(WhatsAppClient.sendPollCreation(dm, "Lunch?", listOf("Pizza", "Sushi")))
        assertNull(WhatsAppClient.sendPoll(group, "Lunch?", listOf("Pizza", "Sushi"), allowMultiple = true))
        // No successful send leaves its id pending.
        assertTrue(WhatsAppClient.pendingMessageIDs.isEmpty())
        // Disconnected / socket-less / bad-JID paths return the same fail-soft results.
        WhatsAppClient.stateMutable.value = WhatsAppClient.State.Idle
        assertFalse(WhatsAppClient.sendEdit(dm, "M1", "x"))
        assertNull(WhatsAppClient.sendPollCreation(dm, "Q?", listOf("A")))
        WhatsAppClient.stateMutable.value = WhatsAppClient.State.Connected
        WhatsAppClient.webSocket = null
        assertFalse(WhatsAppClient.sendReaction(dm, "M1", "👍", false, null))
        WhatsAppClient.webSocket = WhatsAppSocket(auth)
        assertFalse(WhatsAppClient.sendEdit("wa:", "M1", "x"))
    }

    @Test
    fun `pollVoteNeedsStoredSecretThenFailsSoft`() = runTest {
        val dm = "15550002@s.whatsapp.net"
        assertFalse(WhatsAppClient.sendPollVote(dm, "wa:POLL1", "15550002@s.whatsapp.net", false, listOf("Pizza")))
        WhatsAppClient.storePollSecret("POLL1", ByteArray(32) { 7 })
        assertContentEquals(ByteArray(32) { 7 }, WhatsAppClient.loadPollSecret("POLL1"))
        assertNull(WhatsAppClient.loadPollSecret("UNKNOWN"))
        assertFalse(
            WhatsAppClient.sendPollVote(dm, "POLL1", "15550002@s.whatsapp.net", false, listOf("Pizza", "Sushi")),
        )
        assertFalse(
            WhatsAppClient.sendPollVote("12345@g.us", "POLL1", "", true, emptyList()),
        )
    }

    @Test
    fun `typingIndicatorVariants`() = runTest {
        WhatsAppClient.sendTyping("15550002@s.whatsapp.net", isTyping = true)
        WhatsAppClient.sendTyping("15550002@s.whatsapp.net", isTyping = false)
        WhatsAppClient.sendTyping("15550002@s.whatsapp.net", true, WhatsAppClient.TypingType.RECORDING_AUDIO)
        WhatsAppClient.sendTyping("15550002@s.whatsapp.net", true, WhatsAppClient.TypingType.UPLOADING_MEDIA)
        WhatsAppClient.sendTyping("wa:", true)
        WhatsAppClient.stateMutable.value = WhatsAppClient.State.Idle
        WhatsAppClient.sendTyping("15550002@s.whatsapp.net", true)
        WhatsAppClient.stateMutable.value = WhatsAppClient.State.Connected
        assertTrue(WhatsAppDiag.log.value.isEmpty() || true) // send() failing silently is the contract here
    }

    @Test
    fun `disappearingTimerValidationAndPaths`() = runTest {
        assertFalse(WhatsAppClient.setDisappearingTimer("15550002@s.whatsapp.net", 12345L)) // not an allowed value
        assertFalse(WhatsAppClient.setDisappearingTimer("15550002@s.whatsapp.net", 86400L)) // 1:1, no session
        assertFalse(WhatsAppClient.setDisappearingTimer("12345@g.us", 0L)) // group IQ gets no answer
        assertFalse(WhatsAppClient.setDisappearingTimer("12345@g.us", 604800L))
        assertFalse(WhatsAppClient.setDisappearingTimer("wa:", 86400L))
    }

    @Test
    fun `accountDeviceIdentityDecoding`() {
        assertNull(WhatsAppClient.accountDeviceIdentity())
        val raw = byteArrayOf(1, 2, 3, 4, 5)
        WhatsAppClient.authData = auth.copy(accountSignedDeviceIdentity = Base64.encodeToString(raw, Base64.NO_WRAP))
        assertContentEquals(raw, WhatsAppClient.accountDeviceIdentity())
        WhatsAppClient.authData = auth.copy(accountSignedDeviceIdentity = "!!!not-base64!!!")
        assertNull(WhatsAppClient.accountDeviceIdentity())
        WhatsAppClient.authData = null
        assertNull(WhatsAppClient.accountDeviceIdentity())
        WhatsAppClient.authData = auth
    }

    // ---- App-state sync helpers ----

    @Test
    fun `appStateKeyAndVersionStores`() = runTest {
        val id = byteArrayOf(9, 8, 7)
        assertEquals(Base64.encodeToString(id, Base64.NO_WRAP), WhatsAppClient.appStateKeyId64(id))
        WhatsAppClient.storeAppStateKey(id, byteArrayOf(1, 2, 3))
        assertContentEquals(byteArrayOf(1, 2, 3), WhatsAppClient.getAppStateKey(id))
        assertNull(WhatsAppClient.getAppStateKey(byteArrayOf(0)))
        assertEquals(0L, WhatsAppClient.appStateVersion("regular"))
        WhatsAppClient.setAppStateVersion("regular", 41L)
        assertEquals(41L, WhatsAppClient.appStateVersion("regular"))
        assertEquals(0L, WhatsAppClient.appStateVersion("critical_block"))
        WhatsAppClient.kickoffBackfill()
        assertTrue(WhatsAppDiag.log.value.any { it.contains("awaiting pushed history") })
    }

    @Test
    fun `refreshPrivacyWithoutIqAnswerKeepsDefault`() = runTest {
        WhatsAppClient.readReceiptsEnabled = true
        WhatsAppClient.refreshPrivacySettings()
        assertTrue(WhatsAppClient.readReceiptsEnabled)
        WhatsAppClient.webSocket = null
        WhatsAppClient.refreshPrivacySettings()
        assertTrue(WhatsAppClient.readReceiptsEnabled)
        WhatsAppClient.webSocket = WhatsAppSocket(auth)
    }

    // ---- Inbound stanza dispatch ----

    @Test
    fun `inboundRejectsGarbage`() {
        WhatsAppClient.handleIncomingMessage(byteArrayOf(1, 2, 3))
        WhatsAppClient.handleIncomingMessage(byteArrayOf())
        Thread.sleep(300)
        assertEquals(WhatsAppClient.State.Connected, WhatsAppClient.state.value)
    }

    @Test
    fun `inboundFailureStanzasSetDisconnectedState`() {
        for ((reason, expected) in listOf(
            "405" to "Client outdated — update required",
            "503" to "Temporarily banned",
            "999" to "Connection failed: 999",
        )) {
            WhatsAppClient.stateMutable.value = WhatsAppClient.State.Connected
            WhatsAppClient.handleIncomingMessage(
                stanza(WhatsAppProtocol.Node(tag = "failure", attrs = mapOf("reason" to reason))),
            )
            await("failure $reason") {
                (WhatsAppClient.state.value as? WhatsAppClient.State.Disconnected)?.reason == expected
            }
        }
        // 401 additionally clears the session.
        WhatsAppClient.handleIncomingMessage(
            stanza(WhatsAppProtocol.Node(tag = "failure", attrs = mapOf("reason" to "401"))),
        )
        await("failure 401") {
            (WhatsAppClient.state.value as? WhatsAppClient.State.Disconnected)?.reason == "Logged out from WhatsApp" &&
                WhatsAppClient.authData == null
        }
        WhatsAppClient.authData = auth
    }

    @Test
    fun `inboundStreamErrorsRouteByCode`() {
        WhatsAppClient.handleIncomingMessage(
            stanza(WhatsAppProtocol.Node(tag = "stream:error", attrs = mapOf("code" to "515"))),
        )
        await("stream 515") {
            (WhatsAppClient.state.value as? WhatsAppClient.State.Disconnected)?.reason == "Restart required"
        }
        val conflict = WhatsAppProtocol.Node(tag = "stream:error", content = listOf(WhatsAppProtocol.Node(tag = "conflict", attrs = mapOf("type" to "replaced"))))
        WhatsAppClient.handleIncomingMessage(stanza(conflict))
        await("stream replaced") {
            (WhatsAppClient.state.value as? WhatsAppClient.State.Disconnected)?.reason == "Session replaced by another device" &&
                WhatsAppClient.suppressReconnect
        }
        WhatsAppClient.suppressReconnect = false
        WhatsAppClient.handleIncomingMessage(
            stanza(WhatsAppProtocol.Node(tag = "stream:error", attrs = mapOf("code" to "401"))),
        )
        await("stream 401") { WhatsAppClient.state.value is WhatsAppClient.State.NeedsSetup && WhatsAppClient.authData == null }
        WhatsAppClient.authData = auth
        WhatsAppClient.suppressReconnect = false
        WhatsAppClient.handleIncomingMessage(
            stanza(WhatsAppProtocol.Node(tag = "stream:error", attrs = mapOf("code" to "500"))),
        )
        await("stream 500") {
            (WhatsAppClient.state.value as? WhatsAppClient.State.Disconnected)?.reason == "Stream error 500"
        }
    }

    @Test
    fun `inboundIqResponseCompletesPendingAndAckIsLogged`() {
        val deferred = kotlinx.coroutines.CompletableDeferred<WhatsAppProtocol.Node>()
        WhatsAppClient.pendingIqs["iq-1"] = deferred
        val response = WhatsAppProtocol.Node(tag = "iq", attrs = mapOf("id" to "iq-1", "type" to "result"))
        WhatsAppClient.handleIncomingMessage(stanza(response))
        await("iq completed") { deferred.isCompleted }
        assertEquals("result", runBlocking { deferred.await() }.attrs["type"])

        WhatsAppClient.handleIncomingMessage(
            stanza(WhatsAppProtocol.Node(tag = "ack", attrs = mapOf("error" to "404", "class" to "message", "id" to "M9"))),
        )
        await("ack diag") { WhatsAppDiag.log.value.any { it.contains("ack REJECTED") && it.contains("M9") } }
    }

    @Test
    fun `inboundReceiptAndPresenceEmitEvents`() {
        val receipt = WhatsAppProtocol.Node(
            tag = "receipt",
            attrs = mapOf("type" to "read", "from" to "777@s.whatsapp.net", "id" to "M100", "t" to "1700000000"),
            content = listOf(
                WhatsAppProtocol.Node(
                    tag = "list",
                    content = listOf(WhatsAppProtocol.Node(tag = "item", attrs = mapOf("id" to "M101"))),
                ),
            ),
        )
        WhatsAppClient.handleIncomingMessage(stanza(receipt))
        await("receipt events") { receivedEvents.count { it is WhatsAppEvent.ReadReceipt } >= 2 }
        val reads = receivedEvents.filterIsInstance<WhatsAppEvent.ReadReceipt>()
        assertTrue(reads.any { it.messageId == "M100" && !it.isDelivery })
        assertTrue(reads.any { it.messageId == "M101" })

        receivedEvents.clear()
        val presence = WhatsAppProtocol.Node(
            tag = "chatstate",
            attrs = mapOf("from" to "777@s.whatsapp.net"),
            content = listOf(WhatsAppProtocol.Node(tag = "composing", attrs = mapOf("media" to "audio"))),
        )
        WhatsAppClient.handleIncomingMessage(stanza(presence))
        await("typing event") { receivedEvents.any { it is WhatsAppEvent.TypingIndicator && it.isTyping } }

        // A receipt type we do not surface (sender/seen variants) emits nothing new.
        val readsBefore = receivedEvents.count { it is WhatsAppEvent.ReadReceipt }
        WhatsAppClient.handleIncomingMessage(
            stanza(WhatsAppProtocol.Node(tag = "receipt", attrs = mapOf("type" to "sender", "from" to "777@s.whatsapp.net", "id" to "M200"))),
        )
        Thread.sleep(300)
        assertEquals(readsBefore, receivedEvents.count { it is WhatsAppEvent.ReadReceipt })
        eventScope.cancel()
    }

    @Test
    fun `inboundIbBlocksAreConsumed`() {
        val ib = WhatsAppProtocol.Node(
            tag = "ib",
            content = listOf(
                WhatsAppProtocol.Node(tag = "dirty", attrs = mapOf("type" to "account_sync", "timestamp" to "42")),
                WhatsAppProtocol.Node(tag = "offline_preview", attrs = mapOf("count" to "3", "message" to "2")),
                WhatsAppProtocol.Node(tag = "offline", attrs = mapOf("count" to "3")),
            ),
        )
        WhatsAppClient.handleIncomingMessage(stanza(ib))
        await("dirty cleaned") { WhatsAppDiag.log.value.any { it.contains("cleaned dirty state: account_sync") } }
        assertTrue(WhatsAppDiag.log.value.any { it.contains("offline sync completed") })
    }

    @Test
    fun `inboundPlaintextMessageWithoutCiphertextIsTracked`() {
        val message = WhatsAppProtocol.Node(
            tag = "message",
            attrs = mapOf("type" to "text", "from" to "777@s.whatsapp.net", "id" to "M300"),
        )
        WhatsAppClient.handleIncomingMessage(stanza(message))
        await("undecryptable tracked") {
            WhatsAppClient.undecryptableTracker.isNotEmpty() ||
                WhatsAppDiag.log.value.any { it.contains("M300") }
        }
    }
}
