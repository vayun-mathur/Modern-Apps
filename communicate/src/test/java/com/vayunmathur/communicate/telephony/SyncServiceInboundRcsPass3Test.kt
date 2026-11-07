package com.vayunmathur.communicate.telephony

import android.telephony.ims.SipMessage
import androidx.room3.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.test.core.app.ApplicationProvider
import com.sun.net.httpserver.HttpServer
import com.vayunmathur.communicate.data.rcs.RcsDatabase
import com.vayunmathur.communicate.data.rcs.RcsSession
import com.vayunmathur.communicate.data.rcs.RcsSessionManager
import com.vayunmathur.communicate.data.rcs.RcsSipTransport
import com.vayunmathur.communicate.data.rcs.hostGroupFocus
import com.vayunmathur.communicate.data.rcs.hostGroupFocus
import kotlinx.coroutines.runBlocking
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.net.InetSocketAddress
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Pass 3: the private SyncService inbound pipeline reached the way
 * production reaches it — through the transport's inbound callback —
 * covering dialog dispatch, IMDN report sending, FT auto-fetch against
 * a loopback HTTP server, focus relay, and MSRP connection sync.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SyncServiceInboundRcsPass3Test {
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

    private fun sip(
        startLine: String,
        callId: String = "in-p3@host",
        from: String = "<sip:+15550005001@rcs>;tag=ftag1",
        body: String = "",
        contentType: String? = null,
        extraHeaders: String = "",
    ): SipMessage {
        val headers = buildString {
            append("Via: SIP/2.0/TCP 10.0.0.1;branch=z9hG4bKin\r\n")
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

    /** Starts the service and waits for its inbound callback registration. */
    private fun serviceWithCallback(): RcsSyncService {
        val service = Robolectric.setupService(RcsSyncService::class.java)
        assertEquals(android.app.Service.START_STICKY, service.onStartCommand(null, 0, 3))
        val deadline = System.currentTimeMillis() + 15_000
        while (RcsSipTransport.onInboundMessage == null && System.currentTimeMillis() < deadline) {
            Thread.sleep(20)
        }
        assertNotNull(RcsSipTransport.onInboundMessage, "inbound callback never installed")
        return service
    }

    private fun awaitDb(timeoutMs: Long = 15_000, condition: suspend () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!runBlocking { condition() } && System.currentTimeMillis() < deadline) Thread.sleep(25)
        assertTrue(runBlocking { condition() }, "db condition not met")
    }

    @Test fun dialogMethodsDispatchThroughCallback() {
        val service = serviceWithCallback()
        val callback = RcsSipTransport.onInboundMessage!!
        val offer = "v=0\r\nm=message 2855 TCP/MSRP *\r\na=path:msrp://10.0.0.1:2855/s;tcp\r\n"
        callback(sip("INVITE sip:+15550005002@rcs SIP/2.0", body = offer, contentType = "application/sdp"))
        val deadline = System.currentTimeMillis() + 15_000
        while (RcsSessionManager.sessionFor("+15550005001") == null && System.currentTimeMillis() < deadline) {
            Thread.sleep(25)
        }
        assertNotNull(RcsSessionManager.sessionFor("+15550005001"))
        // OPTIONS through the same dispatch answers 200.
        sent.clear()
        callback(sip("OPTIONS sip:+15550005002@rcs SIP/2.0", callId = "in-opt@host"))
        val optDeadline = System.currentTimeMillis() + 15_000
        while (sent.none { it.first == "SIP/2.0 200 OK" } && System.currentTimeMillis() < optDeadline) {
            Thread.sleep(25)
        }
        assertTrue(sent.any { it.first == "SIP/2.0 200 OK" }, "sent=${sent.map { it.first }}")
        // BYE tears the session down again.
        callback(sip("BYE sip:+15550005002@rcs SIP/2.0", callId = "in-p3@host"))
        val byeDeadline = System.currentTimeMillis() + 15_000
        while (RcsSessionManager.sessionFor("+15550005001") != null && System.currentTimeMillis() < byeDeadline) {
            Thread.sleep(25)
        }
        assertNull(RcsSessionManager.sessionFor("+15550005001"))
        service.onDestroy()
    }

    @Test fun imdnReportSentForInboundWithMessageId() {
        val service = serviceWithCallback()
        val callback = RcsSipTransport.onInboundMessage!!
        val cpim = "Content-Type: message/cpim\r\n\r\n" +
            "From: <sip:+15550005001@rcs>\r\nimdn.Message-ID: orig-77\r\nContent-Type: text/plain\r\n\r\nhello imdn"
        callback(sip("MESSAGE sip:+15550005002@rcs SIP/2.0", body = cpim, contentType = "message/cpim"))
        awaitDb { testDb.cachedMessageDao().getForConversation("+15550005001").isNotEmpty() }
        val deadline = System.currentTimeMillis() + 15_000
        while (sent.none { String(it.third).contains("orig-77") } && System.currentTimeMillis() < deadline) {
            Thread.sleep(25)
        }
        assertTrue(sent.any { String(it.third).contains("orig-77") }, "no IMDN report sent: ${sent.size}")
        service.onDestroy()
    }

    @Test fun fileTransferAutoFetchRewritesRowToLocalFile() {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/a.jpg") { exchange ->
            val bytes = "fake-jpeg-bytes".toByteArray()
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
            exchange.close()
        }
        server.start()
        try {
            val service = serviceWithCallback()
            val callback = RcsSipTransport.onInboundMessage!!
            val url = "http://127.0.0.1:${server.address.port}/a.jpg"
            val body = "File-URL: $url\r\nFile-MIME: image/jpeg\r\n\r\nphoto"
            callback(sip("MESSAGE sip:+15550005002@rcs SIP/2.0", body = body, contentType = "text/plain"))
            awaitDb {
                testDb.cachedMessageDao().getForConversation("+15550005001")
                    .any { it.ftUrl?.startsWith("file:") == true }
            }
            service.onDestroy()
        } finally {
            server.stop(0)
        }
    }

    @Test fun focusRelayFansOutToOtherMembers() = runBlocking {
        val service = Robolectric.setupService(RcsSyncService::class.java)
        val conv = "relay-conv-p3"
        val focusUri = RcsSessionManager.hostGroupFocus(conv, "Team", listOf("+15550005001", "+15550005002"))
        assertNotNull(focusUri)
        sent.clear()
        service.relayFocusMessage(
            InboundRcs(conversationId = conv, body = "hi team", senderId = "+15550005001", messageId = "r1"),
            rawBody = "hi team",
        )
        // Only the other member receives the relay.
        assertEquals(1, sent.count { it.first.startsWith("MESSAGE sip:+15550005002@rcs") })
        assertEquals(0, sent.count { it.first.startsWith("MESSAGE sip:+15550005001@rcs") })
        // A blank raw body relays nothing.
        sent.clear()
        service.relayFocusMessage(
            InboundRcs(conversationId = conv, body = "hi", senderId = "+15550005001", messageId = "r2"),
            rawBody = "",
        )
        assertTrue(sent.isEmpty())
        service.onDestroy()
    }

    @Test fun syncMsrpConnectionAttemptsAndCleansUp() = runBlocking {
        val service = Robolectric.setupService(RcsSyncService::class.java)
        val session = RcsSession(
            dialogId = "d-p3", callId = "msrp-p3", localTag = "lt", remoteTag = "rt",
            remoteUri = "sip:+15550005001@rcs", conversationId = "msrp-conv-p3",
            msrpRemotePath = "msrp://127.0.0.1:1/none;tcp",
        )
        // Nothing listens on port 1: connect fails closed, no bound path.
        service.syncMsrpConnection("msrp-conv-p3", session, "msrp://127.0.0.1:1/none;tcp")
        assertTrue(service.boundPaths.isEmpty() || !service.boundPaths.containsKey("msrp-conv-p3"))
        // Same call again (still failing) and with a changed path: also clean.
        service.syncMsrpConnection("msrp-conv-p3", session, "msrp://127.0.0.1:2/none;tcp")
        service.onDestroy()
    }
}
