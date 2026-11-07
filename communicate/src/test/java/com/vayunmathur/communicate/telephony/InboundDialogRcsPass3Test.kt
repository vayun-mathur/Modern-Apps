package com.vayunmathur.communicate.telephony

import android.telephony.ims.SipMessage
import androidx.room3.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.communicate.data.rcs.RcsDatabase
import com.vayunmathur.communicate.data.rcs.RcsSessionManager
import com.vayunmathur.communicate.data.rcs.RcsSipTransport
import kotlinx.coroutines.runBlocking
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Pass 3: inbound SIP dialog methods (INVITE/re-INVITE/BYE/REFER/NOTIFY/
 * SUBSCRIBE/UPDATE/OPTIONS) driven with real [SipMessage] objects through
 * the fake SIP sender, plus the full handleInbound chain reached through
 * the transport's inbound callback.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class InboundDialogRcsPass3Test {
    private val app get() = ApplicationProvider.getApplicationContext<android.app.Application>()
    private lateinit var testDb: RcsDatabase
    private val sent = mutableListOf<Triple<String, String, ByteArray>>()

    @BeforeTest fun setUp() {
        testDb = Room.inMemoryDatabaseBuilder(app, RcsDatabase::class.java)
            .setDriver(BundledSQLiteDriver())
            .allowMainThreadQueries()
            .build()
        RcsDatabase.overrideForTest = testDb
        sent.clear()
        RcsSipTransport.fakeSenderForTest = { startLine, headers, body ->
            sent += Triple(startLine, headers, body.copyOf())
            true
        }
        RcsSessionManager.sessionsMutable.value = emptyMap()
    }

    @AfterTest fun tearDown() {
        RcsSipTransport.fakeSenderForTest = null
        RcsDatabase.overrideForTest = null
        testDb.close()
        RcsSessionManager.sessionsMutable.value = emptyMap()
    }

    private fun service() = Robolectric.setupService(RcsSyncService::class.java)

    private fun sip(
        startLine: String,
        callId: String = "call-p3@host",
        from: String = "<sip:+15550005001@rcs>;tag=ftag1",
        extraHeaders: String = "",
        body: String = "",
        contentType: String? = null,
    ): SipMessage {
        val headers = buildString {
            append("Via: SIP/2.0/TCP 10.0.0.1;branch=z9hG4bKp3\r\n")
            append("From: $from\r\n")
            append("To: <sip:+15550005002@rcs>\r\n")
            append("Call-ID: $callId\r\n")
            append("CSeq: 1 ${startLine.substringBefore(' ')}\r\n")
            if (contentType != null) append("Content-Type: $contentType\r\n")
            append(extraHeaders)
            append("Content-Length: ${body.toByteArray().size}\r\n")
        }
        return SipMessage(startLine, headers, body.toByteArray(Charsets.UTF_8))
    }

    private fun offerSdp(): String =
        "v=0\r\no=- 1 1 IN IP4 10.0.0.1\r\ns=-\r\nc=IN IP4 10.0.0.1\r\nt=0 0\r\n" +
            "m=message 2855 TCP/MSRP *\r\na=path:msrp://10.0.0.1:2855/sess1;tcp\r\na=setup:active\r\n"

    @Test fun inviteWithSdpCreatesSessionAndAccepts() = runBlocking {
        val service = service()
        service.handleInboundInvite(sip("INVITE sip:+15550005002@rcs SIP/2.0", body = offerSdp(), contentType = "application/sdp"))
        val session = RcsSessionManager.sessionFor("+15550005001")
        assertNotNull(session)
        assertEquals("call-p3@host", session.callId)
        assertTrue(sent.any { it.first.startsWith("SIP/2.0 200") }, "sent=${sent.map { it.first }}")
        service.onDestroy()
    }

    @Test fun inviteWithoutSdpDeclines() = runBlocking {
        val service = service()
        service.handleInboundInvite(sip("INVITE sip:+15550005002@rcs SIP/2.0", body = "no sdp here"))
        assertTrue(
            sent.any { it.first.startsWith("SIP/2.0 603") || it.first.startsWith("SIP/2.0 488") },
            "sent=${sent.map { it.first }}",
        )
        service.onDestroy()
    }

    @Test fun inviteWithoutFromIsDropped() = runBlocking {
        val service = service()
        val msg = sip("INVITE sip:x@rcs SIP/2.0", from = "<sip:@rcs>;tag=t", body = offerSdp())
        service.handleInboundInvite(msg)
        assertTrue(sent.isEmpty())
        assertNull(RcsSessionManager.sessionFor(""))
        service.onDestroy()
    }

    @Test fun reInviteOnKnownDialogAnswersWithoutNewSession() = runBlocking {
        val service = service()
        service.handleInboundInvite(sip("INVITE sip:+15550005002@rcs SIP/2.0", body = offerSdp(), contentType = "application/sdp"))
        assertEquals(1, RcsSessionManager.sessions.value.size)
        sent.clear()
        service.handleInboundInvite(
            sip("INVITE sip:+15550005002@rcs SIP/2.0", body = offerSdp(), contentType = "application/sdp"),
        )
        // The re-INVITE is answered on the existing dialog (200 with current
        // SDP, or a decline when the session never produced one) — crucially
        // it must not create a second session entry.
        assertTrue(RcsSessionManager.sessions.value.size <= 1)
        assertTrue(sent.isNotEmpty(), "re-INVITE produced no response: $sent")
        service.onDestroy()
    }

    @Test fun byeTearsDownSession() = runBlocking {
        val service = service()
        service.handleInboundInvite(sip("INVITE sip:+15550005002@rcs SIP/2.0", body = offerSdp(), contentType = "application/sdp"))
        assertNotNull(RcsSessionManager.sessionFor("+15550005001"))
        service.handleInboundBye(sip("BYE sip:+15550005002@rcs SIP/2.0"))
        assertNull(RcsSessionManager.sessionFor("+15550005001"))
        // BYE without Call-ID is ignored (build one with an empty header set).
        service.handleInboundBye(SipMessage("BYE sip:x SIP/2.0", "Via: SIP/2.0/TCP h;branch=z\r\n", ByteArray(0)))
        service.onDestroy()
    }

    @Test fun optionsAnswersWithFeatureTags() = runBlocking {
        val service = service()
        service.handleInboundOptions(sip("OPTIONS sip:+15550005002@rcs SIP/2.0"))
        val ok = sent.firstOrNull { it.first == "SIP/2.0 200 OK" }
        assertNotNull(ok)
        assertTrue(ok.second.contains("Accept-Contact:"))
        service.onDestroy()
    }

    @Test fun notifyReferAndConferencePaths() = runBlocking {
        val service = service()
        // Refer status NOTIFY: parses the sipfrag and answers 200.
        service.handleInboundNotify(
            sip(
                "NOTIFY sip:+15550005002@rcs SIP/2.0",
                extraHeaders = "Event: refer\r\n",
                body = "SIP/2.0 200 OK\r\n",
                contentType = "message/sipfrag",
            ),
        )
        assertTrue(sent.any { it.first == "SIP/2.0 200 OK" }, "sent=${sent.map { it.first }}")
        sent.clear()
        // Conference NOTIFY without a subscription still answers 200.
        service.handleInboundNotify(
            sip(
                "NOTIFY sip:+15550005002@rcs SIP/2.0",
                extraHeaders = "Event: conference\r\n",
                body = "<conference-info xmlns=\"urn:ietf:params:xml:ns:conference-info\"/>",
                contentType = "application/conference-info+xml",
            ),
        )
        assertTrue(sent.any { it.first == "SIP/2.0 200 OK" })
        service.onDestroy()
    }

    @Test fun subscribeConferenceAcceptedAndBadEventRejected() = runBlocking {
        val service = service()
        service.handleInboundSubscribe(
            sip("SUBSCRIBE sip:conf@rcs SIP/2.0", extraHeaders = "Event: conference\r\n"),
        )
        assertTrue(sent.any { it.first.startsWith("SIP/2.0 202") }, "sent=${sent.map { it.first }}")
        sent.clear()
        service.handleInboundSubscribe(
            sip("SUBSCRIBE sip:conf@rcs SIP/2.0", extraHeaders = "Event: presence\r\n"),
        )
        assertTrue(sent.any { it.first.startsWith("SIP/2.0 489") }, "sent=${sent.map { it.first }}")
        service.onDestroy()
    }

    @Test fun updateKnownAndUnknownDialogs() = runBlocking {
        val service = service()
        // Unknown Call-ID: 481.
        service.handleInboundUpdate(sip("UPDATE sip:+15550005002@rcs SIP/2.0", callId = "unknown@host"))
        assertTrue(sent.any { it.first.startsWith("SIP/2.0 481") }, "sent=${sent.map { it.first }}")
        // Known dialog from an INVITE: 200 or 488 depending on SDP state.
        service.handleInboundInvite(sip("INVITE sip:+15550005002@rcs SIP/2.0", body = offerSdp(), contentType = "application/sdp"))
        sent.clear()
        service.handleInboundUpdate(sip("UPDATE sip:+15550005002@rcs SIP/2.0"))
        assertTrue(
            sent.any { it.first.startsWith("SIP/2.0 200") || it.first.startsWith("SIP/2.0 488") },
            "sent=${sent.map { it.first }}",
        )
        service.onDestroy()
    }

    @Test fun referJoinsConferenceAndReportsStatus() = runBlocking {
        val service = service()
        service.handleInboundRefer(
            sip(
                "REFER sip:+15550005002@rcs SIP/2.0",
                extraHeaders = "Refer-To: <sip:conf-42@rcs.example>\r\n",
            ),
        )
        assertTrue(sent.any { it.first.startsWith("SIP/2.0 202") }, "sent=${sent.map { it.first }}")
        // Status NOTIFY follows (200 when the join leg was accepted).
        assertTrue(sent.any { it.first.startsWith("NOTIFY") }, "sent=${sent.map { it.first }}")
        sent.clear()
        // REFER with no Refer-To goes nowhere.
        service.handleInboundRefer(sip("REFER sip:+15550005002@rcs SIP/2.0", callId = "refer2@host"))
        assertTrue(sent.isEmpty())
        service.onDestroy()
    }

    @Test fun headerValueParsing() {
        val headers = "Via: SIP/2.0/TCP h;branch=z9\r\nfrom: <sip:+1@x>;tag=t\r\nX-Empty: \r\n"
        assertEquals("<sip:+1@x>;tag=t", headerValue(headers, "From:"))
        assertEquals("SIP/2.0/TCP h;branch=z9", headerValue(headers, "Via:"))
        assertNull(headerValue(headers, "Missing:"))
        assertNull(headerValue(headers, "X-Empty:"))
        assertNull(headerValue("", "From:"))
    }

    @Test fun handleInboundThroughTransportCallbackCachesMessage() {
        val service = service()
        // Plain start kicks off registration in the service scope, which
        // installs the inbound callback on the transport.
        assertEquals(android.app.Service.START_STICKY, service.onStartCommand(null, 0, 3))
        val deadline = System.currentTimeMillis() + 15_000
        while (RcsSipTransport.onInboundMessage == null && System.currentTimeMillis() < deadline) {
            Thread.sleep(20)
        }
        val callback = RcsSipTransport.onInboundMessage
        assertNotNull(callback, "inbound callback was never installed")
        // A SIP response start line routes to the response handler, no row.
        callback(SipMessage("SIP/2.0 200 OK", "Via: SIP/2.0/TCP h;branch=z9hG4bKx\r\nCall-ID: cb1@h\r\n", ByteArray(0)))
        // A MESSAGE is parsed and cached into the RCS database.
        callback(
            sip(
                "MESSAGE sip:+15550005002@rcs SIP/2.0",
                callId = "cb-msg@host",
                body = "inbound hello",
                contentType = "text/plain",
            ),
        )
        val dbDeadline = System.currentTimeMillis() + 15_000
        var rows = runBlocking { testDb.cachedMessageDao().getForConversation("+15550005001") }
        while (rows.isEmpty() && System.currentTimeMillis() < dbDeadline) {
            Thread.sleep(25)
            rows = runBlocking { testDb.cachedMessageDao().getForConversation("+15550005001") }
        }
        assertEquals(1, rows.size)
        assertEquals("inbound hello", rows[0].body)
        service.onDestroy()
    }
}
