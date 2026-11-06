package com.vayunmathur.communicate.rcs

import com.vayunmathur.communicate.data.rcs.RcsSessionManager
import com.vayunmathur.communicate.data.rcs.RcsSession
import com.vayunmathur.communicate.data.rcs.buildInvite
import com.vayunmathur.communicate.data.rcs.failSession
import com.vayunmathur.communicate.data.rcs.withScheme
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Slice-3 tests for [RcsSessionManager] dialog routing: inbound INVITE /
 * MESSAGE / BYE / ACK handling, session establishment + teardown, backoff,
 * sweeps, re-INVITEs and SDP answers. Transport sends fail closed (no
 * delegate), so these assert pure state-machine behavior: sends return
 * false/null while the session map still transitions correctly.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RcsSlice3SessionManagerTest {

    private fun offerSdp(setup: String = "passive"): String =
        "v=0\r\no=- 1 1 IN IP4 10.0.0.9\r\ns=-\r\nc=IN IP4 10.0.0.9\r\nt=0 0\r\n" +
            "m=message 2855 TCP/MSRP *\r\na=path:msrp://10.0.0.9:2855/sess-9;tcp\r\na=setup:$setup\r\n"

    @Test
    fun inboundInviteCreatesIncomingSession() {
        val callId = "slice3-in-${UUID.randomUUID()}"
        val session = RcsSessionManager.onSipRequest(
            method = "INVITE",
            callId = callId,
            fromUri = "sip:+15550001111@rcs",
            contentType = "application/sdp",
            body = offerSdp(),
            fromTag = "peer-tag-1",
        )
        assertNotNull(session)
        assertEquals(callId, session.callId)
        assertEquals("peer-tag-1", session.remoteTag)
        assertEquals("+15550001111", session.conversationId)
        assertTrue(RcsSessionManager.isKnownDialog(callId))
        // Cleanup.
        RcsSessionManager.onSipRequest("BYE", callId, "sip:+15550001111@rcs", null, "")
        assertFalse(RcsSessionManager.isKnownDialog(callId))
    }

    @Test
    fun inviteConversationIdFallsBackToFullUri() {
        val callId = "slice3-in-${UUID.randomUUID()}"
        val session = RcsSessionManager.onSipRequest(
            "INVITE", callId, "weird-uri-without-sip", "application/sdp", offerSdp(), "t1",
        )
        assertNotNull(session)
        assertEquals("weird-uri-without-sip", session.conversationId)
        RcsSessionManager.onSipRequest("BYE", callId, "weird-uri-without-sip", null, "")
    }

    @Test
    fun inboundMessageImdnAndTyping() {
        val imdnBody = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" +
            "<imdn xmlns=\"urn:ietf:params:xml:ns:imdn\">\n" +
            "  <message-id>slice3-report-1</message-id>\n" +
            "  <status><positive-delivery/></status>\n" +
            "</imdn>"
        // IMDN report routes to the tracker without crashing.
        assertNull(
            RcsSessionManager.onSipRequest(
                "MESSAGE", "c1", "sip:+15550002222@rcs", "message/imdn+xml", imdnBody,
            ),
        )
        assertEquals(
            com.vayunmathur.communicate.data.rcs.ImdnDisposition.Delivered,
            com.vayunmathur.communicate.data.rcs.RcsImdn.statusFor("slice3-report-1"),
        )
        // Typing active then idle.
        RcsSessionManager.onSipRequest(
            "MESSAGE", "c2", "sip:+15550003333@rcs", "application/im-iscomposing+xml",
            "<?xml version=\"1.0\"?><isComposing xmlns=\"urn:ietf:params:xml:ns:im-composing\">" +
                "<state>active</state></isComposing>",
        )
        assertEquals(true, RcsSessionManager.typing.value["+15550003333"])
        RcsSessionManager.onSipRequest(
            "MESSAGE", "c3", "sip:+15550003333@rcs", null,
            "<?xml version=\"1.0\"?><isComposing xmlns=\"urn:ietf:params:xml:ns:im-composing\">" +
                "<state>idle</state></isComposing>",
        )
        assertEquals(false, RcsSessionManager.typing.value["+15550003333"])
    }

    @Test
    fun ackConfirmsIncomingDialog() {
        val callId = "slice3-ack-${UUID.randomUUID()}"
        RcsSessionManager.onSipRequest("INVITE", callId, "sip:+15550004444@rcs", "application/sdp", offerSdp(), "t9")
        val before = RcsSessionManager.sessionFor("+15550004444")
        assertNotNull(before)
        assertFalse(before.confirmed)
        RcsSessionManager.onSipRequest("ACK", callId, "sip:+15550004444@rcs", null, "")
        assertTrue(RcsSessionManager.sessionFor("+15550004444")?.confirmed == true)
        RcsSessionManager.onSipRequest("BYE", callId, "sip:+15550004444@rcs", null, "")
    }

    @Test
    fun unknownMethodReturnsNull() {
        assertNull(RcsSessionManager.onSipRequest("OPTIONS", "c", "sip:x@rcs", null, ""))
        assertNull(RcsSessionManager.onSipRequest("CANCEL", "c", "sip:x@rcs", null, ""))
    }

    @Test
    fun sipResponseEstablishesAndAcks() = runTest {
        val callId = "slice3-est-${UUID.randomUUID()}"
        // Seed a pending outgoing session directly in the map.
        RcsSessionManager.sessionsMutable.value = RcsSessionManager.sessionsMutable.value +
            ("slice3-conv-est" to RcsSession(
                dialogId = "$callId:lt",
                callId = callId,
                localTag = "lt",
                remoteTag = "",
                remoteUri = "sip:+15550005555@rcs",
                conversationId = "slice3-conv-est",
            ))
        // 200 OK with a passive answer: dialog completes (ACK send fails
        // closed without transport, but the dialog still confirms).
        RcsSessionManager.onSipResponse(
            statusCode = 200,
            callId = callId,
            remoteTag = "peer-tag-est",
            sdpAnswer = offerSdp("passive"),
            responseHeaders = "Contact: <sip:+15550005555@10.0.0.9>;expires=600\r\n",
        )
        val established = RcsSessionManager.sessionFor("slice3-conv-est")
        assertNotNull(established)
        assertEquals("peer-tag-est", established.remoteTag)
        assertTrue(established.confirmed)
        assertEquals("msrp://10.0.0.9:2855/sess-9;tcp", established.msrpRemotePath)
        // Teardown of a confirmed dialog (BYE send fails closed, map still drops).
        RcsSessionManager.terminateSession("slice3-conv-est")
        assertNull(RcsSessionManager.sessionFor("slice3-conv-est"))
    }

    @Test
    fun sipResponseFailureTearsDown() = runTest {
        val callId = "slice3-fail-${UUID.randomUUID()}"
        RcsSessionManager.sessionsMutable.value = RcsSessionManager.sessionsMutable.value +
            ("slice3-conv-fail" to RcsSession(
                dialogId = "$callId:lt",
                callId = callId,
                localTag = "lt",
                remoteTag = "",
                remoteUri = "sip:+15550006666@rcs",
                conversationId = "slice3-conv-fail",
            ))
        RcsSessionManager.onSipResponse(486, callId, null, null)
        assertNull(RcsSessionManager.sessionFor("slice3-conv-fail"))
    }

    @Test
    fun dialogErrorBacksOffAndRecovers() {
        val callId = "slice3-bo-${UUID.randomUUID()}"
        RcsSessionManager.sessionsMutable.value = RcsSessionManager.sessionsMutable.value +
            ("slice3-conv-bo" to RcsSession(
                dialogId = "$callId:lt",
                callId = callId,
                localTag = "lt",
                remoteTag = "rt",
                remoteUri = "sip:+15550007777@rcs",
                conversationId = "slice3-conv-bo",
            ))
        assertFalse(RcsSessionManager.inDialogBackoff("slice3-conv-bo"))
        RcsSessionManager.onDialogError(callId, 481)
        assertNull(RcsSessionManager.sessionFor("slice3-conv-bo"))
        assertTrue(RcsSessionManager.inDialogBackoff("slice3-conv-bo"))
        // Unknown call ids are safe no-ops.
        RcsSessionManager.onDialogError("no-such-call", 408)
        assertFalse(RcsSessionManager.inDialogBackoff("slice3-conv-unknown"))
    }

    @Test
    fun sweepDropsStaleIncompleteSessions() {
        val old = System.currentTimeMillis() - 60 * 60 * 1000L
        RcsSessionManager.sessionsMutable.value = RcsSessionManager.sessionsMutable.value +
            ("slice3-conv-stale" to RcsSession(
                dialogId = "stale:lt",
                callId = "slice3-stale-call",
                localTag = "lt",
                remoteTag = "",
                remoteUri = "sip:+15550008888@rcs",
                conversationId = "slice3-conv-stale",
                createdAt = old,
            ))
        val dropped = RcsSessionManager.sweepStaleSessions()
        assertTrue(dropped.contains("slice3-conv-stale"))
        assertNull(RcsSessionManager.sessionFor("slice3-conv-stale"))
    }

    @Test
    fun sweepKeepsFreshSessions() {
        RcsSessionManager.sessionsMutable.value = RcsSessionManager.sessionsMutable.value +
            ("slice3-conv-fresh" to RcsSession(
                dialogId = "fresh:lt",
                callId = "slice3-fresh-call",
                localTag = "lt",
                remoteTag = "",
                remoteUri = "sip:+15550009999@rcs",
                conversationId = "slice3-conv-fresh",
            ))
        val dropped = RcsSessionManager.sweepStaleSessions()
        assertFalse(dropped.contains("slice3-conv-fresh"))
        // Cleanup.
        RcsSessionManager.onSipRequest("BYE", "slice3-fresh-call", "sip:+15550009999@rcs", null, "")
    }

    @Test
    fun reInviteUpdatesDialog() {
        val callId = "slice3-reinv-${UUID.randomUUID()}"
        RcsSessionManager.sessionsMutable.value = RcsSessionManager.sessionsMutable.value +
            ("slice3-conv-reinv" to RcsSession(
                dialogId = "$callId:lt",
                callId = callId,
                localTag = "lt",
                remoteTag = "rt",
                remoteUri = "sip:+15550001010@rcs",
                conversationId = "slice3-conv-reinv",
                confirmed = true,
            ))
        RcsSessionManager.onReInvite(callId, offerSdp("active"))
        val updated = RcsSessionManager.sessionFor("slice3-conv-reinv")
        assertNotNull(updated)
        assertEquals("msrp://10.0.0.9:2855/sess-9;tcp", updated.msrpRemotePath)
        assertEquals(com.vayunmathur.communicate.data.rcs.MsrpSetup.ACTIVE, updated.msrpSetup)
        assertFalse(updated.msrpSecure)
        // Unknown re-INVITE is a no-op.
        RcsSessionManager.onReInvite("no-such-call", offerSdp())
        RcsSessionManager.onSipRequest("BYE", callId, "sip:+15550001010@rcs", null, "")
    }

    @Test
    fun currentSdpRoundTripsBoundPath() {
        val callId = "slice3-csdp-${UUID.randomUUID()}"
        assertNull(RcsSessionManager.currentSdpFor("slice3-conv-nosession"))
        RcsSessionManager.sessionsMutable.value = RcsSessionManager.sessionsMutable.value +
            ("slice3-conv-csdp" to RcsSession(
                dialogId = "$callId:lt",
                callId = callId,
                localTag = "lt",
                remoteTag = "rt",
                remoteUri = "sip:+15550001110@rcs",
                conversationId = "slice3-conv-csdp",
                confirmed = true,
                msrpLocalPath = "msrp://10.0.0.1:2855/sess-local;tcp",
                msrpSetup = com.vayunmathur.communicate.data.rcs.MsrpSetup.ACTIVE,
            ))
        val sdpBytes = RcsSessionManager.currentSdpFor("slice3-conv-csdp")
        assertNotNull(sdpBytes)
        val sdp = sdpBytes.toString(Charsets.UTF_8)
        assertTrue(sdp.contains("a=path:msrp://10.0.0.1:2855/sess-local;tcp"))
        assertTrue(sdp.contains("a=setup:passive"))
        RcsSessionManager.onSipRequest("BYE", callId, "sip:+15550001110@rcs", null, "")
    }

    @Test
    fun startSessionFailsClosedWithoutTransport() = runTest {
        assertNull(RcsSessionManager.startSession("tel:+15550001212", "slice3-conv-noleg"))
        assertNull(RcsSessionManager.startGroupSession("conf:x@rcs.local", "subj", "slice3-conv-noleg2"))
    }

    @Test
    fun sendInDialogFailsClosedWithoutConfirmedSession() = runTest {
        assertFalse(
            RcsSessionManager.sendInDialogMessage("slice3-conv-nothing", "hi".toByteArray()),
        )
        // Unconfirmed session also fails closed.
        RcsSessionManager.sessionsMutable.value = RcsSessionManager.sessionsMutable.value +
            ("slice3-conv-unc" to RcsSession(
                dialogId = "unc:lt",
                callId = "slice3-unc-call",
                localTag = "lt",
                remoteTag = "rt",
                remoteUri = "sip:+15550001313@rcs",
                conversationId = "slice3-conv-unc",
                confirmed = false,
            ))
        assertFalse(RcsSessionManager.sendInDialogMessage("slice3-conv-unc", "hi".toByteArray()))
        RcsSessionManager.onSipRequest("BYE", "slice3-unc-call", "sip:+15550001313@rcs", null, "")
    }

    @Test
    fun terminateUnknownSessionIsTrue() = runTest {
        assertTrue(RcsSessionManager.terminateSession("slice3-conv-never-existed"))
    }

    @Test
    fun failSessionDropsMapEntry() {
        val session = RcsSession(
            dialogId = "fs:lt",
            callId = "slice3-fs-call",
            localTag = "lt",
            remoteTag = "",
            remoteUri = "sip:+15550001414@rcs",
            conversationId = "slice3-conv-fs",
        )
        RcsSessionManager.sessionsMutable.value = RcsSessionManager.sessionsMutable.value +
            ("slice3-conv-fs" to session)
        RcsSessionManager.failSession("slice3-conv-fs", session, "slice3-fs-call", 500)
        assertNull(RcsSessionManager.sessionFor("slice3-conv-fs"))
    }

    @Test
    fun inviteBuilderCarriesConversationHeaders() {
        val (startLine, headers, content) = RcsSessionManager.buildInvite(
            targetUri = "sip:+15550001515@rcs",
            callId = "slice3-inv-call",
            localTag = "ltag1234",
            branch = "z9hG4bKslice3",
            conversationId = "slice3-conv-inv",
            subject = "hello group",
        )
        assertEquals("INVITE sip:+15550001515@rcs SIP/2.0", startLine)
        assertTrue(headers.contains("Call-ID: slice3-inv-call\r\n"))
        assertTrue(headers.contains("Conversation-ID: slice3-conv-inv\r\n"))
        assertTrue(headers.contains("Subject: hello group\r\n"))
        val body = content.toString(Charsets.UTF_8)
        assertTrue(body.contains("m=message "))
        assertTrue(body.contains("a=setup:active"))
    }

    @Test
    fun contactHeaderCarriesInstanceAndCpmTag() {
        val header = RcsSessionManager.contactHeader("sip:me@rcs", "12345")
        assertTrue(header.startsWith("Contact: <sip:me@rcs>"))
        assertTrue(header.contains("urn:gsma:imei:12345"))
        assertTrue(header.contains("cpm.session"))
        val unknown = RcsSessionManager.contactHeader("sip:me@rcs", null)
        assertTrue(unknown.contains("urn:gsma:imei:unknown"))
    }

    @Test
    fun withSchemeRewritesMsrpScheme() {
        assertEquals(
            "msrps://10.0.0.1:2855/x;tcp",
            "msrp://10.0.0.1:2855/x;tcp".withScheme("msrps"),
        )
        assertEquals("noscheme", "noscheme".withScheme("msrps"))
    }
}
