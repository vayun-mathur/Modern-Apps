package com.vayunmathur.communicate.googlevoice

import com.vayunmathur.communicate.data.googlevoice.GvSipRegisterInfo
import com.vayunmathur.communicate.data.googlevoice.call.SipClient
import com.vayunmathur.communicate.data.googlevoice.call.digestAuth
import com.vayunmathur.communicate.data.googlevoice.call.extractContact
import com.vayunmathur.communicate.data.googlevoice.call.extractParam
import com.vayunmathur.communicate.data.googlevoice.call.extractRecordRoutes
import com.vayunmathur.communicate.data.googlevoice.call.extractTag
import com.vayunmathur.communicate.data.googlevoice.call.extractUserFromHeader
import com.vayunmathur.communicate.data.googlevoice.call.md5
import com.vayunmathur.communicate.data.googlevoice.call.parseHeaders
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Slice-4 coverage for the SIP plumbing ([SipClient] extensions + digest auth).
 *
 * Pure JVM: no socket is ever opened — the client is constructed with a stub
 * listener and only its message builders/parsers are exercised, so registrar
 * framing regressions are caught without a live WebSocket.
 */
class SipHelpersSlice4Test {

    private val listener = object : SipClient.Listener {
        override fun onRegistered() = Unit
        override fun onProvisional(code: Int) = Unit
        override fun onAnswered(remoteSdp: String) = Unit
        override fun onEarlyMedia(remoteSdp: String) = Unit
        override fun onEnded() = Unit
        override fun onFailed(reason: String) = Unit
        override fun onIncomingInvite(remoteSdp: String, from: String) = Unit
    }

    private fun client(
        creds: List<String> = listOf("alice", "secret"),
        gvNumber: String = "+1555",
    ) = SipClient(
        GvSipRegisterInfo(phoneNumber = gvNumber, credentials = creds),
        gvNumber,
        CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
        listener,
    )

    // -- identity derivation ------------------------------------------------

    @Test
    fun `credentials map to username password and url-encoded aor`() {
        val c = client(listOf("alice=x", "secret"))
        assertEquals("alice=x", c.authUsername)
        assertEquals("secret", c.authPassword)
        assertEquals("alice%3Dx", c.aorUser)
    }

    @Test
    fun `missing credentials fall back to the gv number and empty password`() {
        val c = client(creds = emptyList(), gvNumber = "+1555")
        assertEquals("+1555", c.authUsername)
        assertEquals("", c.authPassword)
        assertEquals("+1555", c.aorUser)
    }

    // -- digest auth ----------------------------------------------------------

    @Test
    fun `md5 matches the known vector`() {
        assertEquals("900150983cd24fb0d6963f7d28e17f72", client().md5("abc"))
    }

    @Test
    fun `digestAuth builds the rfc2069 response`() {
        val c = client(listOf("alice", "secret"))
        // ha1=md5("alice:example.com:secret"), ha2=md5("REGISTER:sip:example.com"),
        // response=md5("$ha1:n123:$ha2") — verified against an independent md5.
        assertEquals(
            "Digest algorithm=MD5, username=\"alice\", realm=\"example.com\", " +
                "nonce=\"n123\", uri=\"sip:example.com\", " +
                "response=\"9b40ed3e8851a750677d0c45eae3d029\"",
            c.digestAuth("REGISTER", "sip:example.com", "n123", "example.com"),
        )
    }

    @Test
    fun `digestAuth binds the method so invite and register differ`() {
        val c = client(listOf("alice", "secret"))
        val register = c.digestAuth("REGISTER", "sip:example.com", "n123", "example.com")
        val invite = c.digestAuth("INVITE", "sip:example.com", "n123", "example.com")
        assertTrue(register != invite)
    }

    // -- header parsing -------------------------------------------------------

    private val response200 = "SIP/2.0 200 OK\r\n" +
        "Via: SIP/2.0/WSS df12.invalid;branch=z9hG4bK1\r\n" +
        "Via: SIP/2.0/WSS df12.invalid;branch=z9hG4bK2\r\n" +
        "From: <sip:alice@example.com>;tag=from1\r\n" +
        "To: <sip:bob@example.com>;tag=to9\r\n" +
        "Call-ID: abc-123\r\n" +
        "CSeq: 2 INVITE\r\n" +
        "Contact: <sip:bob@10.0.0.1;transport=wss>\r\n" +
        "Record-Route: <sip:r1.example.com;lr>\r\n" +
        "Record-Route: <sip:r2.example.com;lr>\r\n" +
        "Content-Length: 0\r\n" +
        "\r\n"

    @Test
    fun `parseHeaders lowercases names and stops at the body gap`() {
        val headers = client().parseHeaders(response200 + "BODY-SHOULD-NOT-PARSE: x")
        assertEquals("2 INVITE", headers["cseq"])
        assertEquals("<sip:alice@example.com>;tag=from1", headers["from"])
        assertEquals("<sip:bob@example.com>;tag=to9", headers["to"])
        assertEquals("abc-123", headers["call-id"])
        assertEquals("0", headers["content-length"])
        assertNull(headers["body-should-not-parse"])
    }

    @Test
    fun `parseHeaders keeps the first Via and ignores malformed lines`() {
        val headers = client().parseHeaders("SIP/2.0 200 OK\r\nNoColonHere\r\nVia: a\r\n\r\n")
        assertEquals("a", headers["via"])
    }

    @Test
    fun `extractContact prefers the bracketed uri`() {
        assertEquals(
            "sip:bob@10.0.0.1;transport=wss",
            client().extractContact(response200),
        )
    }

    @Test
    fun `extractContact falls back to the bare value and null when absent`() {
        assertEquals(
            "sip:bob@10.0.0.1",
            client().extractContact("SIP/2.0 200 OK\r\nContact: sip:bob@10.0.0.1\r\n\r\n"),
        )
        assertNull(client().extractContact("SIP/2.0 200 OK\r\nContent-Length: 0\r\n\r\n"))
    }

    @Test
    fun `extractRecordRoutes preserves order for later reversal`() {
        assertEquals(
            listOf("<sip:r1.example.com;lr>", "<sip:r2.example.com;lr>"),
            client().extractRecordRoutes(response200),
        )
        assertEquals(
            emptyList(),
            client().extractRecordRoutes("SIP/2.0 200 OK\r\nContent-Length: 0\r\n\r\n"),
        )
    }

    @Test
    fun `extractTag pulls the dialog tag`() {
        assertEquals("to9", client().extractTag("<sip:bob@example.com>;tag=to9"))
        assertNull(client().extractTag("<sip:bob@example.com>"))
    }

    @Test
    fun `extractParam reads quoted and bare challenge params`() {
        val challenge = "Digest realm=\"example.com\", nonce=\"n123\", algorithm=MD5, stale=false"
        assertEquals("example.com", client().extractParam(challenge, "realm"))
        assertEquals("n123", client().extractParam(challenge, "nonce"))
        assertEquals("MD5", client().extractParam(challenge, "algorithm"))
        assertEquals("false", client().extractParam(challenge, "stale"))
        assertNull(client().extractParam(challenge, "qop"))
    }

    @Test
    fun `extractUserFromHeader pulls the caller number`() {
        assertEquals(
            "+1415",
            client().extractUserFromHeader("\"Bob\" <sip:+1415@example.com>;tag=x"),
        )
        assertNull(client().extractUserFromHeader("no-sip-here"))
    }
}
