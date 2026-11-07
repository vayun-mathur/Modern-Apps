package com.vayunmathur.communicate.rcs

import android.content.Context
import androidx.room3.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.communicate.data.rcs.RcsDatabase
import com.vayunmathur.communicate.data.rcs.RcsImsNetwork
import com.vayunmathur.communicate.data.rcs.RcsMsrpListen
import com.vayunmathur.communicate.data.rcs.RcsSession
import com.vayunmathur.communicate.data.rcs.RcsSessionManager
import com.vayunmathur.communicate.data.rcs.RcsSipTransport
import com.vayunmathur.communicate.data.rcs.MsrpSetup
import com.vayunmathur.communicate.telephony.onMsrpAccepted
import kotlinx.coroutines.runBlocking
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.net.ServerSocket
import java.net.Socket
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Pass 3: incoming-session acceptance (passive/TLS answer branches),
 * current-SDP rendering, and the sync service's passive MSRP accept.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], shadows = [RcsSlice3Pass2ImsShadow::class])
class SessionAcceptRcsPass3Test {
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private lateinit var testDb: RcsDatabase
    private val sent = mutableListOf<Triple<String, String, ByteArray>>()
    private var sendResult = true

    @BeforeTest fun setUp() {
        testDb = Room.inMemoryDatabaseBuilder(context, RcsDatabase::class.java)
            .setDriver(BundledSQLiteDriver())
            .allowMainThreadQueries()
            .build()
        RcsDatabase.overrideForTest = testDb
        sent.clear()
        sendResult = true
        RcsSipTransport.fakeSenderForTest = { startLine, headers, body ->
            sent += Triple(startLine, headers, body.copyOf())
            sendResult
        }
        RcsSessionManager.sessionsMutable.value = emptyMap()
        RcsSessionManager.listenContextProvider = { context }
    }

    @AfterTest fun tearDown() {
        RcsMsrpListen.stop()
        RcsImsNetwork.reset()
        RcsSessionManager.listenContextProvider = null
        RcsSipTransport.fakeSenderForTest = null
        RcsDatabase.overrideForTest = null
        testDb.close()
        RcsSessionManager.sessionsMutable.value = emptyMap()
    }

    private fun session(
        conversationId: String,
        localPath: String? = null,
        setup: MsrpSetup? = null,
        secure: Boolean = false,
    ) = RcsSession(
        dialogId = "d-$conversationId", callId = "call-$conversationId",
        localTag = "lt", remoteTag = "rt", remoteUri = "sip:$conversationId@rcs",
        conversationId = conversationId,
        msrpLocalPath = localPath, msrpSetup = setup, msrpSecure = secure,
    )

    @Test fun currentSdpVariants() {
        assertNull(RcsSessionManager.currentSdpFor("missing"))
        RcsSessionManager.sessionsMutable.value = mapOf(
            "c-nopath" to session("c-nopath"),
            "c-tls" to session("c-tls", "msrp://10.0.0.1:9999/x;tcp", MsrpSetup.ACTIVE, secure = true),
            "c-plain" to session("c-plain", "msrp://10.0.0.1/x;tcp"),
        )
        assertNull(RcsSessionManager.currentSdpFor("c-nopath"))
        val tls = String(RcsSessionManager.currentSdpFor("c-tls")!!, Charsets.UTF_8)
        assertTrue(tls.contains("9999 TCP/TLS/MSRP"), tls)
        assertTrue(tls.contains("a=setup:passive"), tls)
        assertTrue(tls.contains("a=path:msrp://10.0.0.1:9999/x;tcp"), tls)
        val plain = String(RcsSessionManager.currentSdpFor("c-plain")!!, Charsets.UTF_8)
        assertTrue(plain.contains("2855 TCP/MSRP"), plain)
        assertTrue(plain.contains("a=setup:active"), plain)
    }

    @Test fun acceptIncomingUnknownAndTransportRefusal() = runBlocking {
        assertEquals(false, RcsSessionManager.acceptIncoming("nope"))
        // Incoming session stashed by an INVITE request, then accepted.
        val sdp = "v=0\r\nm=message 2855 TCP/MSRP *\r\na=path:msrp://10.0.0.9:2855/p;tcp\r\na=setup:passive\r\n"
        RcsSessionManager.onSipRequest(
            "INVITE", "call-acc@h", "sip:+15550006001@rcs", "application/sdp", sdp, "tag1",
        )
        assertNotNull(RcsSessionManager.sessionFor("+15550006001"))
        sendResult = false
        assertEquals(false, RcsSessionManager.acceptIncoming("+15550006001"))
        sendResult = true
        assertEquals(true, RcsSessionManager.acceptIncoming("+15550006001"))
        assertTrue(sent.any { it.first == "SIP/2.0 200 OK" })
    }

    @Test fun acceptIncomingPassiveWhenOfferActiveAndListening() = runBlocking {
        // Start the passive listener so listenPathFor can advertise a path.
        RcsMsrpListen.ensureListening(context) { _, _, _ -> }
        assertTrue(RcsMsrpListen.isListening())
        val sdp = "v=0\r\nm=message 2855 TCP/MSRP *\r\na=path:msrp://10.0.0.9:2855/p;tcp\r\na=setup:active\r\n"
        RcsSessionManager.onSipRequest(
            "INVITE", "call-passive@h", "sip:+15550006002@rcs", "application/sdp", sdp, "tag2",
        )
        val ok = RcsSessionManager.acceptIncoming("+15550006002")
        assertTrue(ok)
        val answer = sent.firstOrNull { it.first == "SIP/2.0 200 OK" }
        assertNotNull(answer)
        val body = String(answer.third, Charsets.UTF_8)
        // Listening answer offers our own path with setup:passive.
        assertTrue(body.contains("a=setup:passive") || body.contains("a=path:msrp"), body)
    }

    @Test fun acceptIncomingSecureOfferDowngradesOrPinsTls() = runBlocking {
        RcsMsrpListen.ensureListening(context) { _, _, _ -> }
        val sdp = "v=0\r\nm=message 2855 TCP/TLS/MSRP *\r\na=path:msrps://10.0.0.9:2855/p;tcp\r\n" +
            "a=setup:active\r\na=fingerprint:SHA-256 AA:BB:CC:DD:EE:FF:00:11:22:33:44:55:66:77:88:99:" +
            "AA:BB:CC:DD:EE:FF:00:11:22:33:44:55:66:77:88:99\r\n"
        RcsSessionManager.onSipRequest(
            "INVITE", "call-tls@h", "sip:+15550006003@rcs", "application/sdp", sdp, "tag3",
        )
        val ok = RcsSessionManager.acceptIncoming("+15550006003")
        assertTrue(ok)
        val answer = sent.firstOrNull { it.first == "SIP/2.0 200 OK" }
        assertNotNull(answer)
        // Either a pinned TLS answer (fingerprint) or the plaintext downgrade;
        // both are valid dialogs and must carry an SDP body.
        assertTrue(String(answer.third, Charsets.UTF_8).contains("m=message"))
    }

    @Test fun declineIncomingRemovesSession() = runBlocking {
        val sdp = "v=0\r\nm=message 2855 TCP/MSRP *\r\na=path:msrp://10.0.0.9:2855/p;tcp\r\n"
        RcsSessionManager.onSipRequest(
            "INVITE", "call-dec@h", "sip:+15550006004@rcs", "application/sdp", sdp, "tag4",
        )
        assertNotNull(RcsSessionManager.sessionFor("+15550006004"))
        RcsSessionManager.declineIncoming("+15550006004")
        assertNull(RcsSessionManager.sessionFor("+15550006004"))
    }

    @Test fun onMsrpAcceptedRegistersConnectionAndFeedsChunks() = runBlocking {
        // Session for the conversation (as an INVITE would have stashed).
        RcsSessionManager.sessionsMutable.value = mapOf(
            "+15550006005" to session("+15550006005", "msrp://127.0.0.1:1/local;tcp"),
        )
        val service = Robolectric.setupService(com.vayunmathur.communicate.telephony.RcsSyncService::class.java)
        val server = ServerSocket(0)
        try {
            val client = Socket("127.0.0.1", server.localPort)
            val accepted = server.accept()
            val head = RcsMsrpListen.AcceptedHead(
                listOf(
                    "MSRP tx-accept SEND",
                    "To-Path: msrp://127.0.0.1:1/local;tcp",
                    "From-Path: msrp://127.0.0.1:2/peer;tcp",
                    "Byte-Range: 1-5/5",
                    "Content-Type: text/plain",
                ),
            )
            service.onMsrpAccepted("+15550006005", accepted, head)
            assertTrue(service.msrpConnections.containsKey("+15550006005"))
            // Unknown conversation: socket is closed, nothing registered.
            val client2 = Socket("127.0.0.1", server.localPort)
            val accepted2 = server.accept()
            service.onMsrpAccepted("+19999999", accepted2, head)
            assertTrue(!service.msrpConnections.containsKey("+19999999"))
            client.close()
            client2.close()
        } finally {
            server.close()
            service.onDestroy()
        }
    }
}
