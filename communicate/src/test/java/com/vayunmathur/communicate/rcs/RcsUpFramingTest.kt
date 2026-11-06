package com.vayunmathur.communicate.rcs

import com.vayunmathur.communicate.data.rcs.ImdnDisposition
import com.vayunmathur.communicate.data.rcs.MsrpSetup
import com.vayunmathur.communicate.data.rcs.RcsFileTransferHttp
import com.vayunmathur.communicate.data.rcs.RcsGbaAuth
import com.vayunmathur.communicate.data.rcs.RcsMsrp
import com.vayunmathur.communicate.data.rcs.RcsMsrpFraming
import com.vayunmathur.communicate.data.rcs.RcsMsrpListen
import com.vayunmathur.communicate.data.rcs.RcsMsrpTls
import com.vayunmathur.communicate.data.rcs.RcsSession
import com.vayunmathur.communicate.data.rcs.RcsSessionManager
import com.vayunmathur.communicate.data.rcs.RcsSipDialog
import com.vayunmathur.communicate.data.rcs.buildEditBody
import com.vayunmathur.communicate.data.rcs.buildGeopushBody
import com.vayunmathur.communicate.data.rcs.buildSdpAnswer
import com.vayunmathur.communicate.data.rcs.buildSdpOffer
import com.vayunmathur.communicate.data.rcs.e2e.parseMembers
import com.vayunmathur.communicate.data.rcs.e2e.pruneMembers
import com.vayunmathur.communicate.data.rcs.focusMembers
import com.vayunmathur.communicate.data.rcs.hostedFocusFor
import com.vayunmathur.communicate.data.rcs.noteFocusLeave
import com.vayunmathur.communicate.data.rcs.buildGeopushBody
import com.vayunmathur.communicate.data.rcs.buildImdnBody
import com.vayunmathur.communicate.data.rcs.buildIsComposingBody
import com.vayunmathur.communicate.data.rcs.buildRevokeBody
import com.vayunmathur.communicate.data.rcs.chunkLargeMessage
import com.vayunmathur.communicate.data.rcs.extractImdnMessageId
import com.vayunmathur.communicate.data.rcs.e2e.RcsKeyDirectory
import com.vayunmathur.communicate.data.rcs.parseChunkHeader
import com.vayunmathur.communicate.data.rcs.parseEditBody
import com.vayunmathur.communicate.data.rcs.parseGeopushBody
import com.vayunmathur.communicate.data.rcs.parseImdnBody
import com.vayunmathur.communicate.data.rcs.parseIsComposingBody
import com.vayunmathur.communicate.data.rcs.parseRevokeBody
import com.vayunmathur.communicate.data.rcs.reassembleLargeMessage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Unit tests for the RCS Universal Profile framing: IMDN receipts (RFC 5438),
 * is-composing (RFC 3994), and key-package publication parsing. Pure string
 * functions — no Android framework needed.
 */
class RcsUpFramingTest {
    @Test
    fun imdnRoundTrip() {
        val body = buildImdnBody("msg-123", ImdnDisposition.Delivered)
        val parsed = parseImdnBody(body)
        assertEquals("msg-123" to ImdnDisposition.Delivered, parsed)
    }

    @Test
    fun imdnDisplayAndFailed() {
        assertEquals(
            "a" to ImdnDisposition.Displayed,
            parseImdnBody(buildImdnBody("a", ImdnDisposition.Displayed)),
        )
        assertEquals(
            "b" to ImdnDisposition.Failed,
            parseImdnBody(buildImdnBody("b", ImdnDisposition.Failed)),
        )
    }

    @Test
    fun imdnRejectsNonReports() {
        assertNull(parseImdnBody("hello world"))
        assertNull(parseImdnBody("<imdn><message-id>x</message-id></imdn>"))
    }

    @Test
    fun imdnMessageIdExtraction() {
        val cpim = "NS: imdn <urn:ietf:params:imdn>\r\n" +
            "imdn.Message-ID: Test_42\r\n" +
            "Content-Type: text/plain\r\n\r\nhello"
        assertEquals("Test_42", extractImdnMessageId(cpim))
        assertNull(extractImdnMessageId("no headers here"))
    }

    @Test
    fun isComposingRoundTrip() {
        assertTrue(parseIsComposingBody(buildIsComposingBody(true)) == true)
        assertTrue(parseIsComposingBody(buildIsComposingBody(false)) == false)
        assertNull(parseIsComposingBody("plain text"))
    }

    @Test
    fun keyPackageParse() {
        val bytes = byteArrayOf(1, 2, 3, 4)
        val b64 = java.util.Base64.getEncoder().encodeToString(bytes)
        val body = "Content-Type: application/x-rcs-keypackage\r\n\r\n$b64"
        val parsed = RcsKeyDirectory.parsePublished(body)
        assertTrue(parsed != null && parsed.contentEquals(bytes))
    }

    @Test
    fun keyPackageRejects() {
        assertNull(RcsKeyDirectory.parsePublished("hello"))
        assertFalse(
            RcsKeyDirectory.parsePublished(
                "Content-Type: application/x-rcs-keypackage\r\n\r\n!!!not-base64!!!",
            ) != null,
        )
    }

    @Test
    fun geopushRoundTrip() {
        val body = buildGeopushBody(37.42, -122.08, "HQ")
        val parsed = parseGeopushBody(body)
        assertEquals(Triple(37.42, -122.08, "HQ"), parsed)
        assertNull(parseGeopushBody("plain text"))
    }

    @Test
    fun largeMessageChunksReassemble() {
        val text = "x".repeat(20000)
        val chunks = chunkLargeMessage("m1", text)
        assertTrue(chunks.size > 1)
        val map = mutableMapOf<Int, String>()
        var total = 0
        for (chunk in chunks) {
            val (id, part, t) = parseChunkHeader(chunk) ?: error("no header")
            assertEquals("m1", id)
            total = t
            map[part] = chunk.substringAfter("\r\n\r\n")
        }
        assertEquals(text, reassembleLargeMessage(map, total))
        assertNull(reassembleLargeMessage(mapOf(1 to "a"), 2))
    }

    @Test
    fun smallMessageNotChunked() {
        val chunks = chunkLargeMessage("m2", "hi")
        assertEquals(listOf("hi"), chunks)
    }

    @Test
    fun revokeRoundTrip() {
        val body = buildRevokeBody("orig-9")
        assertEquals("orig-9", parseRevokeBody(body))
        assertNull(parseRevokeBody("hello"))
    }

    @Test
    fun editRoundTrip() {
        val (id, text) = parseEditBody(buildEditBody("orig-7", "fixed")) ?: error("no edit")
        assertEquals("orig-7", id)
        assertEquals("fixed", text)
        assertNull(parseEditBody("plain"))
    }

    @Test
    fun gbaDigestKnownAnswer() {
        // RFC 2617 §3.5 known-answer vector: user "Mufasa", password
        // "Circle Of Life", realm "testrealm@host.com", nonce
        // "dcd98b7102dd2f0e8b11d0f600bfb0c093".
        val response = RcsGbaAuth.digestResponse(
            username = "Mufasa",
            password = "Circle Of Life",
            realm = "testrealm@host.com",
            nonce = "dcd98b7102dd2f0e8b11d0f600bfb0c093",
            method = "GET",
            uri = "/dir/index.html",
            cnonce = "0a4f113b",
        )
        assertEquals("6629fae49393a05397450978507c4ef1", response)
    }

    @Test
    fun focusBookkeepingTracksMembers() {
        // No focus hosted: lookups are null, leave is a no-op.
        assertNull(RcsSessionManager.hostedFocusFor("nope"))
        assertNull(RcsSessionManager.focusMembers("conf:missing@rcs.local"))
        RcsSessionManager.noteFocusLeave("conf:missing@rcs.local", "+1555")
    }

    @Test
    fun sdpSetupParsesAllRoles() {
        assertEquals(
            MsrpSetup.ACTIVE,
            RcsSessionManager.parseSdpSetup("m=message 2855 TCP/MSRP *\r\na=setup:active\r\n"),
        )
        assertEquals(
            MsrpSetup.PASSIVE,
            RcsSessionManager.parseSdpSetup("a=setup:passive"),
        )
        assertEquals(
            MsrpSetup.ACTPASS,
            RcsSessionManager.parseSdpSetup("a=setup:actpass"),
        )
        assertNull(RcsSessionManager.parseSdpSetup("m=message 2855 TCP/MSRP *"))
        assertNull(RcsSessionManager.parseSdpSetup(null))
        assertNull(RcsSessionManager.parseSdpSetup("a=setup:bogus"))
    }

    @Test
    fun acceptedHeadReplays() {
        val head = RcsMsrpListen.AcceptedHead(
            listOf("MSRP abc SEND", "To-Path: msrp://1.2.3.4:2855/x;tcp", "From-Path: msrp://5.6.7.8:9/y;tcp"),
        )
        val replayed = head.replayBytes().toString(Charsets.UTF_8)
        assertTrue(replayed.startsWith("MSRP abc SEND\r\n"))
        assertTrue(replayed.contains("To-Path: msrp://1.2.3.4:2855/x;tcp\r\n"))
        assertTrue(replayed.endsWith("\r\n"))
    }

    @Test
    fun contentServerParses() {
        val xml = "<rcs><ftHTTPCSURI>https://ft.example.com/upload</ftHTTPCSURI></rcs>"
            .toByteArray(Charsets.UTF_8)
        assertEquals(
            "https://ft.example.com/upload",
            RcsFileTransferHttp.parseContentServer(xml),
        )
        assertNull(RcsFileTransferHttp.parseContentServer("<rcs/>".toByteArray(Charsets.UTF_8)))
    }

    @Test
    fun sweepDropsOnlyStale() {
        // Empty manager: nothing to drop, no crash.
        assertTrue(RcsSessionManager.sweepStaleSessions().isEmpty())
    }

    @Test
    fun fingerprintParses() {
        val sdp = "m=message 2855 TCP/TLS/MSRP *\r\n" +
            "a=path:msrps://10.0.0.1:2855/abc;tcp\r\n" +
            "a=fingerprint:SHA-256 AA:BB:CC:01\r\n"
        val (hash, value) = RcsMsrpTls.parseFingerprint(sdp) ?: error("no fingerprint")
        assertEquals("SHA-256", hash)
        assertEquals("AA:BB:CC:01", value)
        assertNull(RcsMsrpTls.parseFingerprint("m=message 2855 TCP/MSRP *"))
        assertNull(RcsMsrpTls.parseFingerprint(null))
    }

    @Test
    fun secureSdpDetection() {
        assertTrue(
            RcsSessionManager.isSecureSdp("m=message 2855 TCP/TLS/MSRP *\r\na=setup:actpass\r\n"),
        )
        assertTrue(
            RcsSessionManager.isSecureSdp("a=path:msrps://10.0.0.1:9/x;tcp\r\n"),
        )
        assertTrue(
            RcsSessionManager.isSecureSdp("a=fingerprint:SHA-256 AA:BB\r\n"),
        )
        assertFalse(
            RcsSessionManager.isSecureSdp("m=message 2855 TCP/MSRP *\r\na=setup:active\r\n"),
        )
        assertFalse(RcsSessionManager.isSecureSdp(null))
    }

    @Test
    fun securePathScheme() {
        assertTrue(RcsMsrp.isSecurePath("msrps://10.0.0.1:2855/abc;tcp"))
        assertTrue(RcsMsrp.isSecurePath("MSRPS://10.0.0.1:2855/abc;tcp"))
        assertFalse(RcsMsrp.isSecurePath("msrp://10.0.0.1:2855/abc;tcp"))
        assertFalse(RcsMsrp.isSecurePath(null))
        assertFalse(RcsMsrp.isSecurePath(""))
        // Scheme-agnostic host/port parsing still works for msrps.
        assertEquals(
            "10.0.0.1" to 2855,
            RcsMsrp.parseMsrpPath("msrps://10.0.0.1:2855/abc;tcp"),
        )
    }

    @Test
    fun selfSignedCertParsesAndVerifies() {
        // Hand-rolled DER must decode as X.509, verify with its own key, and
        // fingerprint-match its DER bytes.
        val kpg = java.security.KeyPairGenerator.getInstance("EC")
        kpg.initialize(java.security.spec.ECGenParameterSpec("secp256r1"))
        val kp = kpg.generateKeyPair()
        val now = System.currentTimeMillis()
        val der = RcsMsrpTls.selfSignedCert(
            publicKey = kp.public.encoded,
            privateKey = kp.private,
            notBeforeMs = now - 1000,
            notAfterMs = now + 3600_000,
            commonName = "test",
        )
        val cf = java.security.cert.CertificateFactory.getInstance("X.509")
        val cert = cf.generateCertificate(der.inputStream()) as java.security.cert.X509Certificate
        cert.verify(kp.public)
        assertEquals("CN=test", cert.subjectX500Principal.name)
        val fp = RcsMsrpTls.fingerprintOf(der)
        assertTrue(fp.matches(Regex("([0-9A-F]{2}:){31}[0-9A-F]{2}")))
        // Fingerprint binds the exact bytes: flipping one flips the print.
        val mutated = der.copyOf().also { it[der.size - 1] = (it[der.size - 1] + 1).toByte() }
        assertFalse(RcsMsrpTls.fingerprintOf(mutated) == fp)
    }

    @Test
    fun msrpSplitSingleChunk() {
        val payload = "hello".toByteArray(Charsets.UTF_8)
        val chunks = RcsMsrpFraming.splitSend(
            toPath = "msrp://a:1/x;tcp",
            fromPath = "msrp://b:2/y;tcp",
            payload = payload,
        )
        assertEquals(1, chunks.size)
        val wire = RcsMsrpFraming.serializeChunk(chunks[0]).toString(Charsets.UTF_8)
        assertTrue(wire.startsWith("MSRP ${chunks[0].txid} SEND\r\n"))
        assertTrue(wire.contains("Byte-Range: 1-5/5\r\n"))
        assertTrue(wire.endsWith("\r\n-------${chunks[0].txid}\$\r\n"))
    }

    @Test
    fun msrpSplitMultiChunkRanges() {
        val payload = ByteArray(5000) { (it % 251).toByte() }
        val chunks = RcsMsrpFraming.splitSend(
            toPath = "msrp://a:1/x;tcp",
            fromPath = "msrp://b:2/y;tcp",
            payload = payload,
            chunkSize = 2048,
        )
        assertEquals(3, chunks.size)
        val ranges = chunks.map {
            Regex("Byte-Range: (\\S+)").find(it.head.toString(Charsets.UTF_8))!!.groupValues[1]
        }
        assertEquals(listOf("1-2048/5000", "2049-4096/5000", "4097-5000/5000"), ranges)
        // Same Message-ID across chunks; distinct txids.
        assertTrue(chunks.map { it.messageId }.distinct().size == 1)
        assertEquals(3, chunks.map { it.txid }.distinct().size)
        // Reassembly: concatenated slices equal the payload.
        assertTrue(chunks.flatMap { it.body.toList() }.toByteArray().contentEquals(payload))
    }

    @Test
    fun msrpReportAndKeepaliveShapes() {
        val report = RcsMsrpFraming.buildReport(
            toPath = "msrp://a:1/x;tcp",
            fromPath = "msrp://b:2/y;tcp",
            messageId = "m1",
            statusCode = 200,
            reason = "OK",
            byteRange = "1-100/100",
        ).toString(Charsets.UTF_8)
        assertTrue(report.contains(" REPORT\r\n"))
        assertTrue(report.contains("Status: 000 200 OK\r\n"))
        val keepalive = RcsMsrpFraming.buildKeepalive("msrp://a:1/x;tcp", "msrp://b:2/y;tcp")
            .toString(Charsets.UTF_8)
        assertTrue(keepalive.contains("Byte-Range: 1-0/0\r\n"))
        assertTrue(keepalive.contains("Failure-Report: no\r\n"))
        // Response/request discrimination.
        assertEquals(
            Triple("abc123", 200, "OK"),
            RcsMsrpFraming.parseResponseStart("MSRP abc123 200 OK"),
        )
        assertNull(RcsMsrpFraming.parseResponseStart("MSRP abc123 SEND"))
        assertTrue(RcsMsrpFraming.isRequest("MSRP abc123 SEND"))
        assertFalse(RcsMsrpFraming.isRequest("MSRP abc123 200 OK"))
        assertEquals(
            Triple(0, 200, "OK"),
            RcsMsrpFraming.parseStatus("000 200 OK"),
        )
    }

    @Test
    fun sipDialogBuildersSequence() {
        val session = RcsSession(
            dialogId = "d",
            callId = "c@rcs",
            localTag = "lt",
            remoteTag = "rt",
            remoteUri = "sip:peer@rcs",
            conversationId = "peer",
            remoteContact = "sip:peer@10.0.0.1",
            routeSet = listOf("sip:proxy@rcs"),
            nextCseq = 2,
        )
        val (ackLine, ackHeaders) = RcsSipDialog.buildAck(session)
        assertEquals("ACK sip:peer@10.0.0.1 SIP/2.0", ackLine)
        assertTrue(ackHeaders.contains("CSeq: 1 ACK\r\n"))
        assertTrue(ackHeaders.contains("To: <sip:peer@rcs>;tag=rt\r\n"))
        val req = RcsSipDialog.buildInDialogMessage(
            session, "hi".toByteArray(Charsets.UTF_8),
        )
        assertTrue(req.startLine.startsWith("MESSAGE sip:peer@10.0.0.1 "))
        assertTrue(req.headers.contains("CSeq: 2 MESSAGE\r\n"))
        assertTrue(req.headers.contains("Route: <sip:proxy@rcs>\r\n"))
        assertEquals(3L, req.nextCseq)
        val bye = RcsSipDialog.buildInDialogBye(session.copy(nextCseq = req.nextCseq))
        assertTrue(bye.headers.contains("CSeq: 3 BYE\r\n"))
        assertEquals(4L, bye.nextCseq)
        val (contact, routes) = RcsSipDialog.parseDialogRoute(
            "Contact: <sip:peer@10.0.0.1>;expires=3600\r\n" +
                "Record-Route: <sip:p1@rcs>\r\n" +
                "Record-Route: <sip:p2@rcs>\r\n",
        )
        assertEquals("sip:peer@10.0.0.1", contact)
        assertEquals(listOf("sip:p2@rcs", "sip:p1@rcs"), routes)
    }

    @Test
    fun sdpOfferMatrix() {
        val listenPath = "msrp://10.0.0.1:2855/sess-1;tcp"
        // Plaintext active-only offer (no listener, no identity).
        val plain = RcsSessionManager.buildSdpOffer(null, null, null)
        assertTrue(plain.contains("m=message 2855 TCP/MSRP *\r\n"))
        assertTrue(plain.contains("a=setup:active\r\n"))
        assertFalse(plain.contains("a=fingerprint:"))
        assertFalse(plain.contains("msrps://"))
        // Listener up, no identity: actpass + plaintext path.
        val actpass = RcsSessionManager.buildSdpOffer("10.0.0.1", listenPath, null)
        assertTrue(actpass.contains("m=message 2855 TCP/MSRP *\r\n"))
        assertTrue(actpass.contains("a=setup:actpass\r\n"))
        assertTrue(actpass.contains("a=path:$listenPath\r\n"))
        // Listener + identity: TLS offer, proto and path agree.
        val tls = RcsSessionManager.buildSdpOffer("10.0.0.1", listenPath, "AA:BB")
        assertTrue(tls.contains("m=message 2855 TCP/TLS/MSRP *\r\n"))
        assertTrue(tls.contains("a=path:msrps://10.0.0.1:2855/sess-1;tcp\r\n"))
        assertTrue(tls.contains("a=fingerprint:SHA-256 AA:BB\r\n"))
        assertFalse(tls.contains(" TCP/MSRP "))
    }

    @Test
    fun sdpAnswerMatrix() {
        // Plaintext passive answer.
        val passive = RcsSessionManager.buildSdpAnswer(
            "10.0.0.1", "msrp://10.0.0.1:2855/s2;tcp", secure = false, tlsFingerprint = null,
        )
        assertTrue(passive.contains("a=setup:passive\r\n"))
        assertTrue(passive.contains("m=message 2855 TCP/MSRP *\r\n"))
        assertFalse(passive.contains("a=fingerprint:"))
        // TLS answer without identity degrades to plaintext (no bare msrps path).
        val degraded = RcsSessionManager.buildSdpAnswer(
            "10.0.0.1", "msrp://10.0.0.1:2855/s2;tcp", secure = true, tlsFingerprint = null,
        )
        assertTrue(degraded.contains("m=message 2855 TCP/MSRP *\r\n"))
        assertFalse(degraded.contains("msrps://"))
        // Full TLS answer.
        val tls = RcsSessionManager.buildSdpAnswer(
            "10.0.0.1", "msrp://10.0.0.1:2855/s2;tcp", secure = true, tlsFingerprint = "CC:DD",
        )
        assertTrue(tls.contains("m=message 2855 TCP/TLS/MSRP *\r\n"))
        assertTrue(tls.contains("a=path:msrps://10.0.0.1:2855/s2;tcp\r\n"))
        assertTrue(tls.contains("a=fingerprint:SHA-256 CC:DD\r\n"))
    }

    @Test
    fun conferenceInfoRoundTrip() {
        val body = com.vayunmathur.communicate.data.rcs.RcsConferenceEvents.buildConferenceInfo(
            "conf:abc@rcs.local", 3, listOf("sip:a@rcs", "sip:b@rcs"),
        )
        val info = com.vayunmathur.communicate.data.rcs.RcsConferenceEvents
            .parseConferenceInfo(body) ?: error("no info")
        assertEquals("conf:abc@rcs.local", info.entity)
        assertEquals(3, info.version)
        assertEquals(2, info.users.size)
        assertTrue(com.vayunmathur.communicate.data.rcs.RcsConferenceEvents
            .deletedUris(body).isEmpty())
        // Deleted user reconciles out.
        val withDelete = body.replace(
            "<user entity=\"sip:b@rcs\"",
            "<user entity=\"sip:b@rcs\" state=\"deleted\"",
        )
        assertEquals(
            listOf("sip:b@rcs"),
            com.vayunmathur.communicate.data.rcs.RcsConferenceEvents.deletedUris(withDelete),
        )
        assertEquals(
            1,
            com.vayunmathur.communicate.data.rcs.RcsConferenceEvents
                .parseConferenceInfo(withDelete)?.users?.size,
        )
        // Sipfrag round trip.
        assertEquals(
            200 to "OK",
            com.vayunmathur.communicate.data.rcs.RcsConferenceEvents.parseSipfrag("SIP/2.0 200 OK"),
        )
        assertNull(
            com.vayunmathur.communicate.data.rcs.RcsConferenceEvents.parseSipfrag("hello"),
        )
    }

    @Test
    fun digestHeaderKnownAnswer() {
        // Same RFC 2617 vector as the hash test, through the full header builder.
        val header = RcsGbaAuth.digestAuthorizationHeader(
            challenge =
                "Digest realm=\"testrealm@host.com\", nonce=\"dcd98b7102dd2f0e8b11d0f600bfb0c093\", qop=\"auth\"",
            method = "GET",
            uri = "/dir/index.html",
            username = "Mufasa",
            password = "Circle Of Life",
            cnonce = "0a4f113b",
        )
        assertTrue(header.startsWith("Digest "))
        assertTrue(header.contains("response=\"6629fae49393a05397450978507c4ef1\""))
        assertTrue(header.contains("username=\"Mufasa\""))
        assertNull(
            RcsGbaAuth.digestAuthorizationHeader(
                challenge = "Basic realm=\"x\"",
                method = "GET",
                uri = "/",
                username = "u",
            ).takeIf { it.isNotEmpty() },
        )
    }

    @Test
    fun memberMapTracksAndPrunes() {
        val e2e = com.vayunmathur.communicate.data.rcs.e2e.RcsE2E
        val tracked = e2e.trackAddedMembers("", listOf("+1", "+2"))
        assertEquals(mapOf("+1" to 1, "+2" to 2), e2e.parseMembers(tracked))
        val grown = e2e.trackAddedMembers(tracked, listOf("+3"))
        assertEquals(3, e2e.parseMembers(grown)["+3"])
        val pruned = e2e.pruneMembers(grown, setOf(1))
        assertEquals(mapOf("+2" to 2, "+3" to 3), e2e.parseMembers(pruned))
    }
}
