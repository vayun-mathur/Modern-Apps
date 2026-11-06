package com.vayunmathur.communicate.googlevoice

import com.vayunmathur.communicate.data.googlevoice.GvSipRegisterInfo
import com.vayunmathur.communicate.data.googlevoice.call.InboundInvite
import com.vayunmathur.communicate.data.googlevoice.call.SipClient
import com.vayunmathur.communicate.data.googlevoice.call.buildByeOk
import com.vayunmathur.communicate.data.googlevoice.call.buildCancelOk
import com.vayunmathur.communicate.data.googlevoice.call.buildInboundResponse
import com.vayunmathur.communicate.data.googlevoice.call.buildRequest
import com.vayunmathur.communicate.data.googlevoice.call.fromUri
import com.vayunmathur.communicate.data.googlevoice.call.headerLines
import com.vayunmathur.communicate.data.googlevoice.call.parseHeaders
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Slice-4 coverage for the SIP message builders ([SipClient] extensions).
 *
 * Pure JVM: asserts the exact framing (request line, dialog headers, CSeq
 * accounting, content length) so a malformed REGISTER/INVITE/BYE is caught
 * without a live registrar.
 */
class SipMessagesSlice4Test {

    private val listener = object : SipClient.Listener {
        override fun onRegistered() = Unit
        override fun onProvisional(code: Int) = Unit
        override fun onAnswered(remoteSdp: String) = Unit
        override fun onEarlyMedia(remoteSdp: String) = Unit
        override fun onEnded() = Unit
        override fun onFailed(reason: String) = Unit
        override fun onIncomingInvite(remoteSdp: String, from: String) = Unit
    }

    private fun client() = SipClient(
        GvSipRegisterInfo(phoneNumber = "+1555", credentials = listOf("alice", "secret")),
        "+1555",
        CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
        listener,
    )

    // -- outbound requests ----------------------------------------------------

    @Test
    fun `buildRequest frames a register with incrementing cseq`() {
        val c = client()
        val first = c.buildRequest("REGISTER", "sip:example.com", "<sip:alice@example.com>")
        val second = c.buildRequest("REGISTER", "sip:example.com", "<sip:alice@example.com>")
        assertTrue(first.startsWith("REGISTER sip:example.com SIP/2.0\r\n"), first)
        assertTrue(first.contains("CSeq: 2 REGISTER\r\n"), first)
        assertTrue(second.contains("CSeq: 3 REGISTER\r\n"), second)
        // Fixed framing present on both.
        for (msg in listOf(first, second)) {
            assertTrue(msg.contains("Max-Forwards: 70\r\n"))
            assertTrue(msg.contains("From: <sip:alice@${SipClient.SIP_DOMAIN}>;tag="))
            assertTrue(msg.contains("To: <sip:alice@example.com>\r\n"))
            assertTrue(msg.contains("Call-ID: "))
            assertTrue(msg.contains("Allow: INVITE,ACK,CANCEL,BYE,UPDATE,MESSAGE,OPTIONS,REFER,INFO,PRACK\r\n"))
            assertTrue(msg.contains("User-Agent: Communicate GoogleVoice\r\n"))
            assertTrue(msg.endsWith("Content-Length: 0\r\n\r\n"), msg)
        }
    }

    @Test
    fun `buildRequest carries body content-type and exact byte length`() {
        val c = client()
        val sdp = "v=0\r\no=alice 1 1 IN IP4 10.0.0.1\r\n"
        val msg = c.buildRequest(
            method = "INVITE",
            requestUri = "sip:bob@example.com",
            to = "<sip:bob@example.com>",
            body = sdp,
            contentType = "application/sdp",
        )
        assertTrue(msg.startsWith("INVITE sip:bob@example.com SIP/2.0\r\n"))
        assertTrue(msg.contains("Content-Type: application/sdp\r\n"))
        assertTrue(msg.contains("Content-Length: ${sdp.toByteArray().size}\r\n\r\n$sdp"), msg)
    }

    @Test
    fun `buildRequest honours cseqOverride and extra headers`() {
        val c = client()
        val msg = c.buildRequest(
            method = "ACK",
            requestUri = "sip:bob@example.com",
            to = "<sip:bob@example.com>;tag=t1",
            extraHeaders = listOf("Route: <sip:r1>", "Route: <sip:r2>"),
            cseqOverride = 7,
        )
        assertTrue(msg.contains("CSeq: 7 ACK\r\n"), msg)
        assertTrue(msg.contains("Route: <sip:r1>\r\nRoute: <sip:r2>\r\n"), msg)
    }

    @Test
    fun `buildRequest without increment reuses the cseq`() {
        val c = client()
        val before = c.buildRequest("OPTIONS", "sip:example.com", "x", incrementCseq = false)
        val after = c.buildRequest("OPTIONS", "sip:example.com", "x", incrementCseq = false)
        assertEquals(
            before.substringAfter("CSeq: ").substringBefore("\r\n"),
            after.substringAfter("CSeq: ").substringBefore("\r\n"),
        )
    }

    @Test
    fun `fromUri builds the aor`() {
        assertEquals("<sip:alice@example.com>".replace("example.com", SipClient.SIP_DOMAIN), client().fromUri())
    }

    @Test
    fun `headerLines picks one header case-insensitively before the body`() {
        val c = client()
        val msg = "INVITE sip:x SIP/2.0\r\nVia: a\r\nvIa: b\r\nFrom: f\r\n\r\nVia: body\r\n"
        assertEquals(listOf("Via: a", "vIa: b"), c.headerLines(msg, "via"))
        assertEquals(listOf("From: f"), c.headerLines(msg, "FROM"))
        assertEquals(emptyList(), c.headerLines(msg, "Contact"))
    }

    // -- inbound responses ------------------------------------------------------

    private fun invite(): InboundInvite = InboundInvite(
        viaLines = listOf("Via: SIP/2.0/WSS r.example.com;branch=z9hG4bK1"),
        recordRouteLines = listOf("Record-Route: <sip:r1;lr>"),
        from = "\"Bob\" <sip:bob@example.com>;tag=f1",
        to = "<sip:alice@example.com>",
        callId = "call-1",
        cseq = "1 INVITE",
        responseToTag = "resp1",
    )

    @Test
    fun `provisional responses carry no tag final ones do`() {
        val c = client()
        val trying = c.buildInboundResponse(invite(), 100, "Trying")
        assertTrue(trying.startsWith("SIP/2.0 100 Trying\r\n"), trying)
        assertTrue(trying.contains("To: <sip:alice@example.com>\r\n"), trying)

        val ringing = c.buildInboundResponse(invite(), 180, "Ringing")
        assertTrue(ringing.contains("To: <sip:alice@example.com>;tag=resp1\r\n"), ringing)

        val ok = c.buildInboundResponse(invite(), 200, "OK", body = "v=0", contentType = "application/sdp")
        assertTrue(ok.contains("To: <sip:alice@example.com>;tag=resp1\r\n"), ok)
        assertTrue(ok.contains("Contact: <sip:"), ok)
        assertTrue(ok.contains("Content-Type: application/sdp\r\n"), ok)
        assertTrue(ok.endsWith("\r\n\r\nv=0"), ok)
    }

    @Test
    fun `inbound response echoes dialog state`() {
        val c = client()
        val msg = c.buildInboundResponse(invite(), 200, "OK")
        assertTrue(msg.contains("Via: SIP/2.0/WSS r.example.com;branch=z9hG4bK1\r\n"), msg)
        assertTrue(msg.contains("From: \"Bob\" <sip:bob@example.com>;tag=f1\r\n"), msg)
        assertTrue(msg.contains("Call-ID: call-1\r\n"), msg)
        assertTrue(msg.contains("CSeq: 1 INVITE\r\n"), msg)
        assertTrue(msg.contains("Record-Route: <sip:r1;lr>\r\n"), msg)
    }

    @Test
    fun `inbound invite to-tag logic`() {
        // Already-tagged To passes through untouched for every code.
        val tagged = invite().copy(to = "<sip:alice@example.com>;tag=orig")
        assertEquals("", tagged.toTagForResponse(100))
        assertEquals("", tagged.toTagForResponse(200))
        // Untagged: no tag at/below 100, tag above.
        assertEquals("", invite().toTagForResponse(100))
        assertEquals(";tag=resp1", invite().toTagForResponse(180))
        assertEquals(";tag=resp1", invite().toTagForResponse(200))
    }

    @Test
    fun `inbound invite parses from raw message`() {
        val c = client()
        val raw = "INVITE sip:alice@example.com SIP/2.0\r\n" +
            "Via: SIP/2.0/WSS r1;branch=1\r\n" +
            "Via: SIP/2.0/WSS r2;branch=2\r\n" +
            "From: \"Bob\" <sip:bob@example.com>;tag=f1\r\n" +
            "To: <sip:alice@example.com>\r\n" +
            "Call-ID: call-9\r\n" +
            "CSeq: 4 INVITE\r\n" +
            "Record-Route: <sip:rr;lr>\r\n" +
            "Contact: <sip:bob@1.2.3.4>\r\n" +
            "Content-Length: 3\r\n" +
            "\r\n" +
            "v=0"
        val parsed = InboundInvite.from(raw, c.parseHeaders(raw))
        assertEquals(2, parsed.viaLines.size)
        assertEquals(1, parsed.recordRouteLines.size)
        assertEquals("call-9", parsed.callId)
        assertEquals("4 INVITE", parsed.cseq)
    }

    @Test
    fun `cancel and bye acks mirror the request headers`() {
        val c = client()
        val raw = "CANCEL sip:alice@example.com SIP/2.0\r\n" +
            "Via: SIP/2.0/WSS r1;branch=1\r\n" +
            "From: f\r\nTo: t\r\nCall-ID: c1\r\nCSeq: 4 CANCEL\r\n\r\n"
        val headers = mapOf(
            "from" to "f", "to" to "t", "call-id" to "c1", "cseq" to "4 CANCEL",
        )
        for (ack in listOf(c.buildCancelOk(raw, headers), c.buildByeOk(raw, headers))) {
            assertTrue(ack.startsWith("SIP/2.0 200 OK\r\n"), ack)
            assertTrue(ack.contains("Via: SIP/2.0/WSS r1;branch=1\r\n"), ack)
            assertTrue(ack.contains("From: f\r\n"), ack)
            assertTrue(ack.contains("To: t\r\n"), ack)
            assertTrue(ack.contains("Call-ID: c1\r\n"), ack)
            assertTrue(ack.contains("CSeq: 4 CANCEL\r\n"), ack)
            assertTrue(ack.endsWith("Content-Length: 0\r\n\r\n"), ack)
        }
    }
}
