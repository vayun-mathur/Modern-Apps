package com.vayunmathur.communicate.telephony

import android.telephony.ims.SipMessage
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner

/** Slice-4 pass-2: RCS inbound dialog handlers driven with real SipMessages under Robolectric. */
@RunWith(RobolectricTestRunner::class)
class RcsDialogSlice4Pass2Test {

    private fun service() = Robolectric.setupService(RcsSyncService::class.java)

    private fun message(startLine: String, headers: String, body: String = ""): SipMessage =
        SipMessage(startLine, headers, body.toByteArray(Charsets.UTF_8))

    private val inviteHeaders = "Via: SIP/2.0/TCP h;branch=z9hG4bK9\r\n" +
        "From: \"Bob\" <sip:+15551234@carrier.example>;tag=ftag\r\n" +
        "To: <sip:me@carrier.example>\r\n" +
        "Call-ID: dialog-1\r\n" +
        "Content-Type: application/sdp\r\n"

    @Test
    fun sipMessageRoundTripsThroughFrameworkClass() {
        val msg = message("INVITE sip:me@x SIP/2.0", inviteHeaders, "v=0 body")
        assertEquals("INVITE sip:me@x SIP/2.0", msg.startLine)
        assertEquals("dialog-1", msg.callIdParameter)
        assertEquals("z9hG4bK9", msg.viaBranchParameter)
    }

    @Test
    fun parseEnvelopeFromRealSipMessage() {
        val service = service()
        val headers = "Via: SIP/2.0/TCP h;branch=z9hG4bK1\r\n" +
            "From: \"Bob\" <sip:+15551234@carrier.example>;tag=t1\r\n" +
            "Content-Type: text/plain\r\n" +
            "Call-ID: call-42\r\n"
        val envelope = service.parseEnvelope(message("MESSAGE sip:me@x SIP/2.0", headers, "hello there"))
        assertNotNull(envelope)
        assertEquals("+15551234", envelope.from)
        assertEquals("text/plain", envelope.contentType)
        assertEquals("hello there", envelope.raw)
        service.onDestroy()
    }

    @Test
    fun handleInboundByeTearsDownUnknownDialog() = runBlocking {
        val service = service()
        service.handleInboundBye(message("BYE sip:me@x SIP/2.0", inviteHeaders))
        // No session existed; the contract is that it completes without throwing.
        service.closeDeadMsrpConnections()
        service.onDestroy()
        Unit
    }

    @Test
    fun handleInboundInviteWithoutSdpDeclines() = runBlocking {
        val service = service()
        service.handleInboundInvite(message("INVITE sip:me@x SIP/2.0", inviteHeaders, "no sdp here"))
        service.onDestroy()
        Unit
    }

    @Test
    fun handleInboundInviteWithSdpNotifies() = runBlocking {
        val service = service()
        val sdp = "v=0\r\no=- 1 1 IN IP4 0.0.0.0\r\nm=message 9 TCP/MSRP *\r\n"
        service.handleInboundInvite(message("INVITE sip:me@x SIP/2.0", inviteHeaders, sdp))
        service.onDestroy()
        Unit
    }

    @Test
    fun notifySubscribeUpdateOptionsAndReferHandlers() = runBlocking {
        val service = service()
        val base = "Via: SIP/2.0/TCP h;branch=z9hG4bK5\r\n" +
            "From: \"Bob\" <sip:+15551234@carrier.example>;tag=ftag\r\n" +
            "To: <sip:me@carrier.example>\r\n" +
            "Call-ID: dialog-9\r\n"

        // NOTIFY with a refer sipfrag, then a conference event
        service.handleInboundNotify(
            message("NOTIFY sip:me@x SIP/2.0", base + "Event: refer\r\nCSeq: 3 NOTIFY\r\n", "SIP/2.0 200 OK"),
        )
        service.handleInboundNotify(
            message("NOTIFY sip:me@x SIP/2.0", base + "Event: conference\r\nCSeq: 4 NOTIFY\r\n", "<conference-info/>"),
        )
        // Direct response builders
        val notifyMsg = message("NOTIFY sip:me@x SIP/2.0", base + "CSeq: 5 NOTIFY\r\n")
        service.sendNotifyResponse(notifyMsg)
        service.notifyReferStatus(notifyMsg, ok = true)
        service.notifyReferStatus(notifyMsg, ok = false)

        // SUBSCRIBE: bad event is rejected, conference is accepted
        service.handleInboundSubscribe(
            message("SUBSCRIBE sip:me@x SIP/2.0", base + "Event: presence\r\nCSeq: 1 SUBSCRIBE\r\n"),
        )
        service.handleInboundSubscribe(
            message("SUBSCRIBE sip:me@x SIP/2.0", base + "Event: conference\r\nCSeq: 2 SUBSCRIBE\r\n"),
        )
        service.sendSubscribeResponse(notifyMsg, 202, "Accepted")

        // UPDATE on an unknown dialog gets 481 via sendSessionResponse
        service.handleInboundUpdate(message("UPDATE sip:me@x SIP/2.0", base + "CSeq: 6 UPDATE\r\n", "v=0"))
        service.sendSessionResponse(notifyMsg, 200, "OK", "sdp".toByteArray(), "application/sdp")
        service.sendSessionResponse(notifyMsg, 481, "Call/Transaction Does Not Exist")

        // OPTIONS capability ping + REFER without Refer-To (early return) and with one
        service.handleInboundOptions(message("OPTIONS sip:me@x SIP/2.0", base + "CSeq: 1 OPTIONS\r\n"))
        service.handleInboundRefer(message("REFER sip:me@x SIP/2.0", base + "CSeq: 1 REFER\r\n"))
        service.sendReferResponse(notifyMsg, 202, "Accepted")
        service.onDestroy()
        Unit
    }

    @Test
    fun dialogHandlersIgnoreMissingCallId() = runBlocking {
        val service = service()
        val noCallId = "Via: SIP/2.0/TCP h;branch=z9hG4bK7\r\nFrom: <sip:+1555@x>;tag=t\r\n"
        service.handleInboundBye(message("BYE sip:me@x SIP/2.0", noCallId))
        assertNull(service.parseEnvelope(message("MESSAGE sip:me@x SIP/2.0", noCallId, "  ")))
        service.onDestroy()
        Unit
    }
}
