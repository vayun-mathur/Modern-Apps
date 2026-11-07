package com.vayunmathur.communicate.rcs

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.communicate.data.CommunicateAttachment
import com.vayunmathur.communicate.data.CommunicateRepository
import com.vayunmathur.communicate.data.rcs.RcsDatabase
import com.vayunmathur.communicate.data.rcs.RcsFileTransfer
import com.vayunmathur.communicate.data.rcs.RcsSessionManager
import com.vayunmathur.communicate.data.rcs.RcsSipTransport
import com.vayunmathur.communicate.data.rcs.RcsTyping
import com.vayunmathur.communicate.data.rcs.destroyHostedFocus
import com.vayunmathur.communicate.data.rcs.dropFocusSubscriber
import com.vayunmathur.communicate.data.rcs.focusMembers
import com.vayunmathur.communicate.data.rcs.hostGroupFocus
import com.vayunmathur.communicate.data.rcs.hostedFocusFor
import com.vayunmathur.communicate.data.rcs.inviteFocusMember
import com.vayunmathur.communicate.data.rcs.noteFocusJoin
import com.vayunmathur.communicate.data.rcs.noteFocusLeave
import com.vayunmathur.communicate.data.rcs.noteFocusSubscriber
import com.vayunmathur.communicate.data.rcs.onConferenceNotify
import com.vayunmathur.communicate.data.rcs.publishConferenceInfo
import com.vayunmathur.communicate.data.rcs.refreshEventSubscriptions
import com.vayunmathur.communicate.data.rcs.removeFocusMember
import com.vayunmathur.communicate.data.rcs.e2e.RcsE2E
import com.vayunmathur.communicate.data.rcs.e2e.RcsKeyDirectory
import com.vayunmathur.communicate.data.rcs.e2e.RustMlsCrypto
import com.vayunmathur.communicate.data.rcs.sendRcsDisplayReports
import com.vayunmathur.communicate.data.rcs.subscribeConferenceEvents
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.test.runTest
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Slice-3 pass 2: everything above the SIP transport, driven through the
 * transport's fake-sender test seam — outgoing sessions, focus hosting,
 * conference events, typing and display reports, file envelopes.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RcsSlice3Pass2FakeTransportTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private lateinit var testDb: RcsDatabase

    data class Sent(val startLine: String, val headers: String, val body: ByteArray)

    private val sent = CopyOnWriteArrayList<Sent>()
    private var sendResult = true

    @BeforeTest
    fun setUp() {
        testDb = androidx.room3.Room.inMemoryDatabaseBuilder(context, RcsDatabase::class.java)
            .setDriver(androidx.sqlite.driver.bundled.BundledSQLiteDriver())
            .allowMainThreadQueries()
            .build()
        RcsDatabase.overrideForTest = testDb
        sent.clear()
        sendResult = true
        RcsSipTransport.fakeSenderForTest = { startLine, headers, body ->
            sent += Sent(startLine, headers, body.copyOf())
            sendResult
        }
        RcsSessionManager.sessionsMutable.value = emptyMap()
    }

    @AfterTest
    fun tearDown() {
        RcsSipTransport.fakeSenderForTest = null
        RcsSessionManager.sessionsMutable.value = emptyMap()
        RcsDatabase.overrideForTest = null
        testDb.close()
    }

    private fun sdpAnswer(setup: String = "passive"): String =
        "v=0\r\nm=message 2855 TCP/MSRP *\r\n" +
            "a=path:msrp://10.0.0.9:2855/peer;tcp\r\na=setup:$setup\r\n"

    @Test
    fun outgoingSessionLifecycleThroughFakeTransport() = runTest {
        val conv = "fake-conv-1"
        val dialogId = RcsSessionManager.startSession("tel:+15551234567", conv)
        assertNotNull(dialogId)
        val invite = sent.first()
        assertTrue(invite.startLine.startsWith("INVITE tel:+15551234567 SIP/2.0"), invite.startLine)
        assertTrue(invite.headers.contains("Conversation-ID: $conv"))
        assertTrue(invite.body.toString(Charsets.UTF_8).contains("a=setup:active"))
        val session = RcsSessionManager.sessionFor(conv)
        assertNotNull(session)
        assertFalse(session.confirmed)

        // The 200 OK answer establishes the dialog and triggers an ACK send.
        sent.clear()
        RcsSessionManager.onSipResponse(
            200, session.callId, "remote-tag-1", sdpAnswer(),
            "Contact: <sip:peer@10.0.0.9>\r\nRecord-Route: <sip:proxy1;lr>\r\nRecord-Route: <sip:proxy2;lr>\r\n",
        )
        val established = RcsSessionManager.sessionFor(conv)
        assertNotNull(established)
        assertEquals("remote-tag-1", established.remoteTag)
        assertEquals("msrp://10.0.0.9:2855/peer;tcp", established.msrpRemotePath)
        assertEquals("sip:peer@10.0.0.9", established.remoteContact)
        assertEquals(listOf("sip:proxy2;lr", "sip:proxy1;lr"), established.routeSet)
        assertTrue(established.confirmed)
        assertTrue(sent.any { it.startLine.startsWith("ACK ") }, sent.map { it.startLine }.toString())

        // In-dialog MESSAGE advances the CSeq.
        val cseqBefore = RcsSessionManager.sessionFor(conv)!!.nextCseq
        assertTrue(RcsSessionManager.sendInDialogMessage(conv, "cpim-body".toByteArray()))
        val msg = sent.last { it.startLine.startsWith("MESSAGE ") }
        assertTrue(msg.headers.contains("CSeq: $cseqBefore MESSAGE"))
        assertEquals(cseqBefore + 1, RcsSessionManager.sessionFor(conv)!!.nextCseq)

        // Terminate sends an in-dialog BYE and drops the session.
        assertTrue(RcsSessionManager.terminateSession(conv))
        assertTrue(sent.any { it.startLine.startsWith("BYE ") })
        assertNull(RcsSessionManager.sessionFor(conv))
    }

    @Test
    fun outgoingSessionFailureModes() = runTest {
        // Transport refuses the INVITE: no session is tracked.
        sendResult = false
        assertNull(RcsSessionManager.startSession("tel:+1555", "fake-conv-fail"))
        assertNull(RcsSessionManager.sessionFor("fake-conv-fail"))
        assertNull(RcsSessionManager.startGroupSession("sip:focus@rcs", "s", "fake-conv-fail"))
        sendResult = true

        // A failure response tears a pending session down.
        val conv = "fake-conv-2"
        val dialogId = RcsSessionManager.startGroupSession("sip:focus.example@rcs", "Weekly sync", conv)
        assertNotNull(dialogId)
        val groupInvite = sent.last { it.startLine.startsWith("INVITE ") }
        assertTrue(groupInvite.headers.contains("Subject: Weekly sync"))
        val session = RcsSessionManager.sessionFor(conv)
        assertNotNull(session)
        assertTrue(session.isGroup)
        RcsSessionManager.onSipResponse(486, session.callId, null, null)
        assertNull(RcsSessionManager.sessionFor(conv))
    }

    @Test
    fun acceptAndDeclineIncomingThroughFakeTransport() = runTest {
        val callId = "fake-in-${UUID.randomUUID()}"
        val offer = "v=0\r\nm=message 2855 TCP/MSRP *\r\n" +
            "a=path:msrp://10.0.0.9:2855/offer;tcp\r\na=setup:passive\r\n"
        val session = RcsSessionManager.onSipRequest(
            "INVITE", callId, "sip:+15557654321@rcs", "application/sdp", offer, "peer-tag",
        )
        assertNotNull(session)
        val conv = session.conversationId
        sent.clear()
        assertTrue(RcsSessionManager.acceptIncoming(conv))
        val ok200 = sent.first()
        assertEquals("SIP/2.0 200 OK", ok200.startLine)
        val answered = RcsSessionManager.sessionFor(conv)
        assertNotNull(answered)
        assertEquals("msrp://10.0.0.9:2855/offer;tcp", answered.msrpRemotePath)

        // Declining another incoming invite sends 488 and drops it.
        val callId2 = "fake-in2-${UUID.randomUUID()}"
        val session2 = RcsSessionManager.onSipRequest(
            "INVITE", callId2, "sip:+15557654322@rcs", "application/sdp", offer, "peer-tag2",
        )
        assertNotNull(session2)
        sent.clear()
        assertTrue(RcsSessionManager.declineIncoming(session2.conversationId))
        assertEquals("SIP/2.0 488 Not Acceptable Here", sent.first().startLine)
        assertNull(RcsSessionManager.sessionFor(session2.conversationId))
        assertFalse(RcsSessionManager.acceptIncoming("no-such-conv"))
    }

    @Test
    fun hostedFocusLifecycleThroughFakeTransport() = runTest {
        val conv = "fake-focus-1"
        val focusUri = RcsSessionManager.hostGroupFocus(
            conv, "Team chat", listOf("+15551001", " +15551002 ", "+15551001", ""),
        )
        assertNotNull(focusUri)
        assertTrue(focusUri.startsWith("conf:"))
        assertEquals(focusUri, RcsSessionManager.hostedFocusFor(conv))
        assertEquals(setOf("+15551001", "+15551002"), RcsSessionManager.focusMembers(focusUri))
        // One REFER per distinct peer.
        assertEquals(2, sent.count { it.startLine.startsWith("REFER ") })
        assertTrue(sent.all { it.headers.contains("Refer-To: <$focusUri>") })

        // Local join/leave bookkeeping.
        RcsSessionManager.noteFocusJoin(focusUri, " +15551003 ")
        assertTrue(RcsSessionManager.focusMembers(focusUri)!!.contains("+15551003"))
        RcsSessionManager.noteFocusLeave(focusUri, "+15551003")
        assertFalse(RcsSessionManager.focusMembers(focusUri)!!.contains("+15551003"))

        // Invite adds a member (REFER accepted); a failing REFER does not.
        assertTrue(RcsSessionManager.inviteFocusMember(conv, "+15551004"))
        assertTrue(RcsSessionManager.focusMembers(focusUri)!!.contains("+15551004"))
        sendResult = false
        assertFalse(RcsSessionManager.inviteFocusMember(conv, "+15551005"))
        assertFalse(RcsSessionManager.focusMembers(focusUri)!!.contains("+15551005"))
        sendResult = true
        assertFalse(RcsSessionManager.inviteFocusMember(conv, "  "))
        assertFalse(RcsSessionManager.inviteFocusMember("unknown-conv", "+1555"))

        // Remove drops the member even though the BYE is best-effort.
        assertTrue(RcsSessionManager.removeFocusMember(conv, "+15551004"))
        assertFalse(RcsSessionManager.focusMembers(focusUri)!!.contains("+15551004"))
        assertFalse(RcsSessionManager.removeFocusMember(conv, "+15551004"))

        // Destroy tears the focus down.
        assertTrue(RcsSessionManager.destroyHostedFocus(conv))
        assertNull(RcsSessionManager.hostedFocusFor(conv))
        assertNull(RcsSessionManager.focusMembers(focusUri))
    }

    @Test
    fun hostedFocusFailsWhenNoReferAccepted() = runTest {
        sendResult = false
        assertNull(RcsSessionManager.hostGroupFocus("fake-focus-2", "s", listOf("+15551001")))
        assertNull(RcsSessionManager.hostedFocusFor("fake-focus-2"))
        sendResult = true
        // No participants: nothing to host.
        assertNull(RcsSessionManager.hostGroupFocus("fake-focus-3", "s", emptyList()))
        assertNull(RcsSessionManager.hostGroupFocus("fake-focus-3", "s", listOf(" ", "")))
    }

    @Test
    fun conferenceSubscribeNotifyAndRefresh() = runTest {
        val conv = "fake-conf-1"
        val focusUri = "sip:focus.example@rcs"
        assertTrue(RcsSessionManager.subscribeConferenceEvents(focusUri, conv))
        val sub = sent.first { it.startLine.startsWith("SUBSCRIBE ") }
        assertTrue(sub.headers.contains("Event: conference"))
        assertTrue(sub.headers.contains("Accept: application/conference-info+xml"))
        // Idempotent re-subscribe.
        val sendsBefore = sent.size
        assertTrue(RcsSessionManager.subscribeConferenceEvents(focusUri, conv))
        assertEquals(sendsBefore, sent.size)

        // A NOTIFY reconciles members and records the version; stale repeats are ignored.
        fun conferenceInfo(version: Int, users: String, deleted: String = ""): String =
            "<conference-info entity=\"$focusUri\" version=\"$version\">" +
                "<users>$users$deleted</users></conference-info>"
        val userA = "<user entity=\"sip:+15551001@rcs\"><state>full</state></user>"
        val userB = "<user entity=\"sip:+15551002@rcs\"><state>full</state></user>"
        val (added1, removed1) = RcsSessionManager.onConferenceNotify(conv, conferenceInfo(1, userA))
        assertEquals(listOf("sip:+15551001@rcs"), added1)
        assertEquals(emptyList(), removed1)
        // Same version again: stale.
        val (addedStale, _) = RcsSessionManager.onConferenceNotify(conv, conferenceInfo(1, userB))
        assertEquals(emptyList(), addedStale)
        // Newer version with a deletion.
        val deletedB = "<user entity=\"sip:+15551002@rcs\" state=\"deleted\"></user>"
        val (added2, removed2) = RcsSessionManager.onConferenceNotify(
            conv, conferenceInfo(2, userA, deletedB),
        )
        assertEquals(listOf("sip:+15551001@rcs"), added2)
        assertEquals(listOf("sip:+15551002@rcs"), removed2)

        // Refresh re-SUBSCRIBEs on the same dialog.
        sent.clear()
        RcsSessionManager.refreshEventSubscriptions()
        assertTrue(sent.any { it.startLine.startsWith("SUBSCRIBE ") && it.headers.contains("CSeq: 2 SUBSCRIBE") })
    }

    @Test
    fun publishConferenceInfoToSubscribers() = runTest {
        val conv = "fake-pub-1"
        val focusUri = RcsSessionManager.hostGroupFocus(conv, "s", listOf("+15551001"))
        assertNotNull(focusUri)
        // No subscribers yet: publish is a no-op (but must not throw).
        sent.clear()
        RcsSessionManager.publishConferenceInfo(focusUri)
        assertTrue(sent.isEmpty())
        RcsSessionManager.noteFocusSubscriber("sub-call-9", focusUri)
        RcsSessionManager.publishConferenceInfo(focusUri)
        val notify = sent.first { it.startLine.startsWith("NOTIFY ") }
        assertTrue(notify.headers.contains("Call-ID: sub-call-9"))
        assertTrue(notify.headers.contains("Event: conference"))
        val body = notify.body.toString(Charsets.UTF_8)
        assertTrue(body.contains(focusUri), body)
        assertTrue(body.contains("+15551001"), body)
        // Version increments per publish.
        sent.clear()
        RcsSessionManager.publishConferenceInfo(focusUri)
        assertTrue(sent.first().body.toString(Charsets.UTF_8).contains("version=\"2\""))
        RcsSessionManager.dropFocusSubscriber("sub-call-9")
        sent.clear()
        RcsSessionManager.publishConferenceInfo(focusUri)
        assertTrue(sent.isEmpty())
        assertTrue(RcsSessionManager.destroyHostedFocus(conv))
    }

    @Test
    fun typingAndDisplayReportsThroughFakeTransport() = runTest {
        assertTrue(RcsSipTransport.canSend())
        // Pager-mode typing (no dialog for this recipient).
        assertTrue(RcsTyping.sendComposing("+15554001", true))
        val typingMsg = sent.last { it.startLine.startsWith("MESSAGE ") }
        assertTrue(typingMsg.headers.contains("Content-Type: application/im-iscomposing+xml"))
        assertTrue(typingMsg.body.toString(Charsets.UTF_8).contains("active"))
        assertTrue(RcsTyping.sendComposing("+15554001", false))

        // Display reports: cache inbound rows, then report each once.
        testDb.cachedMessageDao().upsert(
            com.vayunmathur.communicate.data.rcs.RcsCachedMessage(
                messageId = "fake-disp-1", conversationId = "+15554002", body = "hi",
                timestamp = 1, outgoing = false, imdnId = "imdn-fake-1",
            ),
        )
        testDb.cachedMessageDao().upsert(
            com.vayunmathur.communicate.data.rcs.RcsCachedMessage(
                messageId = "fake-disp-2", conversationId = "+15554002", body = "out",
                timestamp = 2, outgoing = true, imdnId = "imdn-fake-2",
            ),
        )
        sent.clear()
        with(CommunicateRepository) { sendRcsDisplayReports(context, "+15554002") }
        // Only the inbound row is reported.
        assertEquals(1, sent.count { it.startLine.startsWith("MESSAGE ") })
        assertTrue(sent.first().body.toString(Charsets.UTF_8).contains("imdn-fake-1"))
        // A second pass reports nothing new.
        sent.clear()
        with(CommunicateRepository) { sendRcsDisplayReports(context, "+15554002") }
        assertTrue(sent.isEmpty())
    }

    @Test
    fun conferenceNotifyReconcilesHostedFocusMembers() = runTest {
        val conv = "fake-conf-host"
        val focusUri = RcsSessionManager.hostGroupFocus(conv, "s", listOf("+15551001"))
        assertNotNull(focusUri)
        // A carrier-style NOTIFY naming our focus reconciles the member set.
        val body = "<conference-info entity=\"$focusUri\" version=\"1\">" +
            "<users>" +
            "<user entity=\"sip:+15551001@rcs\"><state>full</state></user>" +
            "<user entity=\"sip:+15551009@rcs\"><state>full</state></user>" +
            "</users></conference-info>"
        val (added, removed) = RcsSessionManager.onConferenceNotify(conv, body)
        assertEquals(listOf("sip:+15551001@rcs", "sip:+15551009@rcs"), added)
        assertEquals(emptyList(), removed)
        assertTrue(RcsSessionManager.focusMembers(focusUri)!!.contains("sip:+15551009@rcs"))
        // A newer version deleting a member removes it locally too.
        val body2 = "<conference-info entity=\"$focusUri\" version=\"2\">" +
            "<users>" +
            "<user entity=\"sip:+15551009@rcs\" state=\"deleted\"></user>" +
            "</users></conference-info>"
        val (_, removed2) = RcsSessionManager.onConferenceNotify(conv, body2)
        assertEquals(listOf("sip:+15551009@rcs"), removed2)
        assertFalse(RcsSessionManager.focusMembers(focusUri)!!.contains("sip:+15551009@rcs"))
        assertTrue(RcsSessionManager.destroyHostedFocus(conv))
    }

    @Test
    fun e2eEnvelopesThroughFakeTransportWhenNativeWorks() = runTest {
        // Key-package publication and encrypted setup ride the fake sender
        // when the native MLS crate works; without it they fail closed.
        val published = RcsKeyDirectory.publishTo(context, "+15557001", "+15557002")
        if (!RustMlsCrypto.isAvailable || RcsE2E.identityFor(context, "+15557001") == null) {
            assertFalse(published)
            // The key *request* envelope needs no crypto: it still sends.
            assertTrue(RcsE2E.requestKeyPackage("+15557002"))
            assertTrue(sent.any { it.body.toString(Charsets.UTF_8).contains(RcsE2E.CT_KEY_REQUEST) })
            return@runTest
        }
        assertTrue(published)
        assertNotNull(RcsKeyDirectory.lastPublishedFor("+15557001"))
        val kpMessage = sent.first { it.headers.contains(RcsE2E.CT_KEY_PACKAGE) || it.body.toString(Charsets.UTF_8).contains(RcsE2E.CT_KEY_PACKAGE) }
        assertTrue(kpMessage.body.toString(Charsets.UTF_8).contains(RcsE2E.CT_KEY_PACKAGE))
        assertTrue(RcsE2E.requestEncryptedChat(context, "+15557001", "+15557002"))
        assertTrue(sent.any { it.body.toString(Charsets.UTF_8).contains(RcsE2E.CT_KEY_REQUEST) })
    }

    @Test
    fun fileTransferEnvelopeThroughFakeTransport() = runTest {
        val attachment = CommunicateAttachment(
            contentUri = "content://media/42", mimeType = "image/png", fileName = "photo.png",
        )
        assertTrue(
            RcsFileTransfer.sendFiles(
                context, CommunicateRepository, "+15555001", "look at this", listOf(attachment),
            ),
        )
        val msg = sent.first { it.startLine.startsWith("MESSAGE ") }
        val wireBody = msg.body.toString(Charsets.UTF_8)
        assertTrue(wireBody.contains("FT-URL: content://media/42"), wireBody)
        assertTrue(wireBody.contains("FT-MIME: image/png"), wireBody)
        assertTrue(wireBody.contains("FT-NAME: photo.png"), wireBody)
        // The outgoing row was cached with the FT metadata.
        val rows = testDb.cachedMessageDao().getForConversation("+15555001")
        assertEquals(1, rows.size)
        assertEquals("look at this", rows.first().body)
        assertEquals("content://media/42", rows.first().ftUrl)
        assertEquals("image/png", rows.first().ftMime)

        // A rejected envelope does not cache and does not count as sent.
        sent.clear()
        sendResult = false
        assertFalse(
            RcsFileTransfer.sendFiles(
                context, CommunicateRepository, "+15555002", "", listOf(attachment),
            ),
        )
        assertTrue(testDb.cachedMessageDao().getForConversation("+15555002").isEmpty())
        sendResult = true
    }
}
