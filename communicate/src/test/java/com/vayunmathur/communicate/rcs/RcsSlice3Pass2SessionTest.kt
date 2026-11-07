package com.vayunmathur.communicate.rcs

import com.vayunmathur.communicate.data.rcs.MsrpSetup
import com.vayunmathur.communicate.data.rcs.RcsSession
import com.vayunmathur.communicate.data.rcs.RcsSessionManager
import com.vayunmathur.communicate.data.rcs.buildSdpAnswer
import com.vayunmathur.communicate.data.rcs.buildSdpOffer
import com.vayunmathur.communicate.data.rcs.hostedFocusFor
import com.vayunmathur.communicate.data.rcs.noteFocusJoin
import com.vayunmathur.communicate.data.rcs.noteFocusLeave
import com.vayunmathur.communicate.data.rcs.destroyHostedFocus
import com.vayunmathur.communicate.data.rcs.dropFocusSubscriber
import com.vayunmathur.communicate.data.rcs.focusMembers
import com.vayunmathur.communicate.data.rcs.hostGroupFocus
import com.vayunmathur.communicate.data.rcs.inviteFocusMember
import com.vayunmathur.communicate.data.rcs.noteFocusSubscriber
import com.vayunmathur.communicate.data.rcs.onConferenceNotify
import com.vayunmathur.communicate.data.rcs.publishConferenceInfo
import com.vayunmathur.communicate.data.rcs.refreshEventSubscriptions
import com.vayunmathur.communicate.data.rcs.removeFocusMember
import com.vayunmathur.communicate.data.rcs.subscribeConferenceEvents
import java.util.UUID
import kotlinx.coroutines.test.runTest
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Slice-3 pass 2: session-manager edges not reached in pass 1 — SDP
 * offer/answer matrices, re-INVITE application, current-SDP emission,
 * error backoff, focus bookkeeping on a hosted session, and the
 * transport-gated entry points.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RcsSlice3Pass2SessionTest {

    private fun seedSession(
        conversationId: String,
        callId: String = "pass2-${UUID.randomUUID()}",
        session: RcsSession = RcsSession(
            dialogId = "$callId:d", callId = callId, localTag = "lt", remoteTag = "rt",
            remoteUri = "sip:peer@rcs", conversationId = conversationId,
        ),
    ): RcsSession {
        RcsSessionManager.sessionsMutable.value =
            RcsSessionManager.sessionsMutable.value + (conversationId to session)
        return session
    }

    private fun drop(conversationId: String) {
        RcsSessionManager.sessionsMutable.value =
            RcsSessionManager.sessionsMutable.value - conversationId
    }

    @Test
    fun sdpOfferAnswerMatrices() {
        // Plain offer without a listen path: active connect-out, placeholder host.
        val plain = with(RcsSessionManager) { buildSdpOffer(null, null) }
        assertTrue(plain.contains("m=message 2855 TCP/MSRP *"))
        assertTrue(plain.contains("a=setup:active"))
        assertTrue(plain.contains("a=path:msrp://"))
        assertFalse(plain.contains("a=fingerprint:"))
        // With a listen path: actpass + our path, explicit IP.
        val listened = with(RcsSessionManager) {
            buildSdpOffer("10.0.0.5", "msrp://10.0.0.5:2855/sess-1;tcp")
        }
        assertTrue(listened.contains("c=IN IP4 10.0.0.5"))
        assertTrue(listened.contains("a=setup:actpass"))
        assertTrue(listened.contains("a=path:msrp://10.0.0.5:2855/sess-1;tcp"))
        // TLS offer: TLS proto, msrps path rewrite, fingerprint line.
        val secure = with(RcsSessionManager) {
            buildSdpOffer("10.0.0.5", "msrp://10.0.0.5:2855/sess-1;tcp", "AA:BB:CC")
        }
        assertTrue(secure.contains("TCP/TLS/MSRP"))
        assertTrue(secure.contains("a=path:msrps://10.0.0.5:2855/sess-1;tcp"))
        assertTrue(secure.contains("a=fingerprint:SHA-256 AA:BB:CC"))
        // Blank IP falls back to 0.0.0.0.
        val blankIp = with(RcsSessionManager) { buildSdpOffer(" ", null) }
        assertTrue(blankIp.contains("c=IN IP4 0.0.0.0"))

        // Answers mirror the offerer's security only with a fingerprint.
        val answer = with(RcsSessionManager) { buildSdpAnswer(null, null) }
        assertTrue(answer.contains("a=setup:active"))
        assertTrue(answer.contains("TCP/MSRP"))
        val passive = with(RcsSessionManager) {
            buildSdpAnswer("10.0.0.6", "msrp://10.0.0.6:2855/sess-2;tcp")
        }
        assertTrue(passive.contains("a=setup:passive"))
        val tlsAnswer = with(RcsSessionManager) {
            buildSdpAnswer("10.0.0.6", "msrp://10.0.0.6:2855/sess-2;tcp", secure = true, tlsFingerprint = "DD:EE")
        }
        assertTrue(tlsAnswer.contains("TCP/TLS/MSRP"))
        assertTrue(tlsAnswer.contains("a=fingerprint:SHA-256 DD:EE"))
        // Secure requested but no fingerprint: downgraded answer.
        val downgraded = with(RcsSessionManager) {
            buildSdpAnswer("10.0.0.6", "msrp://10.0.0.6:2855/sess-2;tcp", secure = true, tlsFingerprint = null)
        }
        assertTrue(downgraded.contains("m=message 2855 TCP/MSRP *"))
        assertFalse(downgraded.contains("a=fingerprint:"))
    }

    @Test
    fun currentSdpForSeededSession() {
        val conv = "pass2-curr-${UUID.randomUUID()}"
        assertNull(RcsSessionManager.currentSdpFor(conv))
        // No local path yet: no SDP.
        seedSession(conv)
        assertNull(RcsSessionManager.currentSdpFor(conv))
        // Bind a plaintext passive-side path (peer active => we are passive).
        seedSession(
            conv,
            session = RcsSession(
                dialogId = "d2", callId = "pass2-curr-call", localTag = "lt", remoteTag = "rt",
                remoteUri = "sip:peer@rcs", conversationId = conv,
                msrpLocalPath = "msrp://10.0.0.7:9999/self;tcp",
                msrpRemotePath = "msrp://10.0.0.9:2855/peer;tcp",
                msrpSetup = MsrpSetup.ACTIVE,
            ),
        )
        val sdp = RcsSessionManager.currentSdpFor(conv)?.toString(Charsets.UTF_8)
        assertNotNull(sdp)
        assertTrue(sdp.contains("m=message 9999 TCP/MSRP *"))
        assertTrue(sdp.contains("a=path:msrp://10.0.0.7:9999/self;tcp"))
        assertTrue(sdp.contains("a=setup:passive"))
        // Secure + non-active peer: TLS proto, active role.
        seedSession(
            conv,
            session = RcsSession(
                dialogId = "d3", callId = "pass2-curr-call", localTag = "lt", remoteTag = "rt",
                remoteUri = "sip:peer@rcs", conversationId = conv,
                msrpLocalPath = "msrps://10.0.0.7:9999/self;tcp",
                msrpRemotePath = "msrps://10.0.0.9:2855/peer;tcp",
                msrpSetup = MsrpSetup.PASSIVE, msrpSecure = true,
            ),
        )
        val secureSdp = RcsSessionManager.currentSdpFor(conv)?.toString(Charsets.UTF_8)
        assertNotNull(secureSdp)
        assertTrue(secureSdp.contains("TCP/TLS/MSRP"))
        assertTrue(secureSdp.contains("a=setup:active"))
        drop(conv)
    }

    @Test
    fun reInviteAppliesNewSdp() {
        val conv = "pass2-reinv-${UUID.randomUUID()}"
        val callId = "pass2-reinv-call-${UUID.randomUUID()}"
        seedSession(conv, callId)
        assertNull(RcsSessionManager.peerFingerprint(callId))
        val sdp = "v=0\r\nm=message 2855 TCP/TLS/MSRP *\r\n" +
            "a=path:msrps://10.0.0.9:2855/new;tcp\r\na=setup:passive\r\n" +
            "a=fingerprint:SHA-256 11:22:33\r\n"
        RcsSessionManager.onReInvite(callId, sdp)
        val updated = RcsSessionManager.sessionFor(conv)
        assertNotNull(updated)
        assertEquals("msrps://10.0.0.9:2855/new;tcp", updated.msrpRemotePath)
        assertEquals(MsrpSetup.PASSIVE, updated.msrpSetup)
        assertTrue(updated.msrpSecure)
        assertEquals("11:22:33", RcsSessionManager.peerFingerprint(callId))
        // Unknown call id is a no-op.
        RcsSessionManager.onReInvite("no-such-call", sdp)
        drop(conv)
    }

    @Test
    fun dialogErrorSetsBackoffAndDropsSession() {
        val conv = "pass2-err-${UUID.randomUUID()}"
        val callId = "pass2-err-call-${UUID.randomUUID()}"
        assertFalse(RcsSessionManager.inDialogBackoff(conv))
        seedSession(conv, callId)
        assertTrue(RcsSessionManager.isKnownDialog(callId))
        RcsSessionManager.onDialogError(callId, 481)
        assertNull(RcsSessionManager.sessionFor(conv))
        assertFalse(RcsSessionManager.isKnownDialog(callId))
        assertTrue(RcsSessionManager.inDialogBackoff(conv))
        // Errors for unknown calls do not create backoff for other ids.
        RcsSessionManager.onDialogError("unknown-call", 408)
        assertFalse(RcsSessionManager.inDialogBackoff("pass2-other"))
    }

    @Test
    fun focusBookkeepingOnSeededFocusSession() = runTest {
        val conv = "pass2-focus-${UUID.randomUUID()}"
        val focusUri = "conf:pass2@rcs.local"
        seedSession(
            conv,
            session = RcsSession(
                dialogId = "focus:$focusUri", callId = "focus-call", localTag = "lt",
                remoteTag = "", remoteUri = focusUri, conversationId = conv,
                isGroup = true, isFocus = true,
            ),
        )
        assertEquals(focusUri, RcsSessionManager.hostedFocusFor(conv))
        assertNull(RcsSessionManager.hostedFocusFor("no-such-conv"))
        assertNull(RcsSessionManager.focusMembers(focusUri))
        // Hosting lookups by target URI (INVITE-to-focus routing).
        assertNotNull(RcsSessionManager.hostedConversationFor("INVITE $focusUri SIP/2.0"))
        assertNull(RcsSessionManager.hostedConversationFor(""))
        assertNull(RcsSessionManager.hostedConversationFor("tel:+1555"))
        // invite/remove need transport; they fail closed.
        assertFalse(RcsSessionManager.inviteFocusMember(conv, "+1555"))
        drop(conv)
        // Non-focus sessions are not hosted foci.
        val conv2 = "pass2-plain-${UUID.randomUUID()}"
        seedSession(conv2)
        assertNull(RcsSessionManager.hostedFocusFor(conv2))
        assertFalse(RcsSessionManager.removeFocusMember(conv2, "+1555"))
        drop(conv2)
    }

    @Test
    fun noteJoinLeaveOnUnknownFocusAreSafe() {
        RcsSessionManager.noteFocusJoin("conf:unknown@rcs.local", " +1555 ")
        RcsSessionManager.noteFocusLeave("conf:unknown@rcs.local", "+1555")
        assertNull(RcsSessionManager.focusMembers("conf:unknown@rcs.local"))
    }

    @Test
    fun transportGatedEntryPointsFailClosed() = runTest {
        val conv = "pass2-gated-${UUID.randomUUID()}"
        assertNull(RcsSessionManager.startSession("tel:+1555", conv))
        assertNull(RcsSessionManager.startGroupSession("sip:focus@rcs", "subject", conv))
        assertFalse(RcsSessionManager.acceptIncoming(conv))
        assertFalse(RcsSessionManager.declineIncoming(conv))
        assertFalse(RcsSessionManager.sendInDialogMessage(conv, byteArrayOf(1)))
        // Terminating an unknown conversation is trivially successful.
        assertTrue(RcsSessionManager.terminateSession(conv))
        assertNull(RcsSessionManager.sessionFor(conv))
        // Group focus hosting also needs the transport.
        assertNull(RcsSessionManager.hostGroupFocus(conv, "s", listOf("+1555")))
        assertFalse(RcsSessionManager.subscribeConferenceEvents("sip:focus@rcs", conv))
        RcsSessionManager.refreshEventSubscriptions()
        // No hosted session exists yet, so destroying is trivially done.
        assertTrue(RcsSessionManager.destroyHostedFocus(conv))
    }

    @Test
    fun terminateSeededUnconfirmedSession() = runTest {
        val conv = "pass2-term-${UUID.randomUUID()}"
        seedSession(conv)
        // The BYE send fails (no transport) but the session is dropped.
        val ok = RcsSessionManager.terminateSession(conv)
        assertFalse(ok)
        assertNull(RcsSessionManager.sessionFor(conv))
    }

    @Test
    fun destroyHostedFocusOnNonFocusDelegatesToTerminate() = runTest {
        val conv = "pass2-destroy-${UUID.randomUUID()}"
        seedSession(conv)
        assertFalse(RcsSessionManager.destroyHostedFocus(conv))
        assertNull(RcsSessionManager.sessionFor(conv))
        // Unknown conversation: destroys trivially.
        assertTrue(RcsSessionManager.destroyHostedFocus("pass2-unknown-${UUID.randomUUID()}"))
    }

    @Test
    fun sessionResponsesIgnoreUnknownAndProvisional() = runTest {
        // Unknown call ids and dialog-less codes are absorbed.
        RcsSessionManager.onSipResponse(200, "unknown-call", "tag", null)
        RcsSessionManager.onSipResponse(100, "unknown-call", null, null)
        val conv = "pass2-resp-${UUID.randomUUID()}"
        val callId = "pass2-resp-call-${UUID.randomUUID()}"
        seedSession(conv, callId)
        // Provisional responses (< 300 without success handling) leave the session.
        RcsSessionManager.onSipResponse(180, callId, null, null)
        assertNotNull(RcsSessionManager.sessionFor(conv))
        // 2xx without a remote tag does not establish.
        RcsSessionManager.onSipResponse(200, callId, null, null)
        assertNotNull(RcsSessionManager.sessionFor(conv))
        assertFalse(RcsSessionManager.sessionFor(conv)!!.confirmed)
        drop(conv)
    }

    @Test
    fun establishedPeerActiveWithoutListenPathIsUnusableButConfirmed() = runTest {
        // With a fake transport, establish a session whose peer answers
        // active-only: media cannot bind, so the remote path is dropped
        // but the dialog still confirms.
        com.vayunmathur.communicate.data.rcs.RcsSipTransport.fakeSenderForTest = { _, _, _ -> true }
        try {
            val conv = "pass2-unusable-${UUID.randomUUID()}"
            val dialog = RcsSessionManager.startSession("tel:+1555", conv)
            assertNotNull(dialog)
            val session = RcsSessionManager.sessionFor(conv)
            assertNotNull(session)
            RcsSessionManager.onSipResponse(
                200, session.callId, "rtag",
                "v=0\r\nm=message 2855 TCP/MSRP *\r\na=path:msrp://10.0.0.9:2855/p;tcp\r\na=setup:active\r\n",
            )
            val established = RcsSessionManager.sessionFor(conv)
            assertNotNull(established)
            assertTrue(established.confirmed)
            assertNull(established.msrpRemotePath)
            assertTrue(RcsSessionManager.terminateSession(conv))
        } finally {
            com.vayunmathur.communicate.data.rcs.RcsSipTransport.fakeSenderForTest = null
        }
    }

    @Test
    fun subscribeAndPublishConferenceWithoutTransport() = runTest {
        val conv = "pass2-conf-${UUID.randomUUID()}"
        RcsSessionManager.noteFocusSubscriber("sub-call-1", "conf:x@rcs.local")
        RcsSessionManager.publishConferenceInfo("conf:x@rcs.local")
        RcsSessionManager.dropFocusSubscriber("sub-call-1")
        RcsSessionManager.noteFocusSubscriber("sub-call-1", "conf:x@rcs.local")
        RcsSessionManager.dropFocusSubscriber("sub-call-1")
        // Notify routing for an unparseable body reconciles nothing.
        val (added, removed) = RcsSessionManager.onConferenceNotify(conv, "not a conference doc")
        assertEquals(emptyList(), added)
        assertEquals(emptyList(), removed)
    }
}
