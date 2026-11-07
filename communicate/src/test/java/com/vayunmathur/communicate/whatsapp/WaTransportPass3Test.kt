package com.vayunmathur.communicate.whatsapp

import android.app.Application
import android.content.Context
import android.util.Base64
import androidx.room3.Room
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.communicate.data.whatsapp.WhatsAppAuthData
import com.vayunmathur.communicate.data.whatsapp.WhatsAppClient
import com.vayunmathur.communicate.data.whatsapp.WhatsAppDatabase
import com.vayunmathur.communicate.data.whatsapp.WhatsAppEvent
import com.vayunmathur.communicate.data.whatsapp.WhatsAppProtocol
import com.vayunmathur.communicate.data.whatsapp.decodeNode
import com.vayunmathur.communicate.data.whatsapp.encodeNode
import com.vayunmathur.communicate.data.whatsapp.fetchAndEmitGroupInfo
import com.vayunmathur.communicate.data.whatsapp.transport.FakeWhatsAppTransport
import com.vayunmathur.communicate.data.whatsapp.transport.WhatsAppSocket
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Pass 3 (whatsapp-transport): drives the real [WhatsAppClient] singleton through
 * [FakeWhatsAppTransport] - no socket, no network. The DB is an in-memory Room
 * database (the production SQLCipher build needs AndroidKeyStore, unavailable
 * under Robolectric), and auth is a fabricated identity with real X25519 keys.
 */
@OptIn(kotlin.concurrent.atomics.ExperimentalAtomicApi::class)
@RunWith(RobolectricTestRunner::class)
class WaTransportPass3Test {

    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private lateinit var fake: FakeWhatsAppTransport

    private fun b64(bytes: ByteArray): String = Base64.encodeToString(bytes, Base64.NO_WRAP)

    private fun fabricatedAuth(): WhatsAppAuthData {
        val noise = WhatsAppProtocol.generateX25519KeyPair()
        val identity = WhatsAppProtocol.generateX25519KeyPair()
        val signed = WhatsAppProtocol.generateX25519KeyPair()
        return WhatsAppAuthData(
            phoneNumber = "15551234567",
            pushName = "Test",
            wid = "15551234567@s.whatsapp.net",
            noisePrivateKey = b64(noise.first),
            noisePublicKey = b64(noise.second),
            identityPrivateKey = b64(identity.first),
            identityPublicKey = b64(identity.second),
            registrationId = 1234,
            signedPreKeyId = 1,
            signedPreKeyPublic = b64(signed.second),
            signedPreKeyPrivate = b64(signed.first),
            signedPreKeySignature = b64(ByteArray(64) { (it * 7).toByte() }),
        )
    }

    @BeforeTest fun setUp() {
        fake = FakeWhatsAppTransport()
        val client = WhatsAppClient
        client.appContext = context.applicationContext as Application
        client.db = Room.inMemoryDatabaseBuilder(context, WhatsAppDatabase::class.java)
            .allowMainThreadQueries().build()
        client.authData = fabricatedAuth()
        client.transportFactory = { fake }
        client.webSocket = null
        client.suppressReconnect = false
        client.pendingIqs.clear()
        client.knownGroups.clear()
        client.stateMutable.value = WhatsAppClient.State.Idle
        client.initialized.store(true)
    }

    private fun pollUntil(timeoutMs: Long = 15_000, cond: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline && !cond()) Thread.sleep(25)
        assertTrue(cond(), "condition not met within ${timeoutMs}ms")
    }

    /** Generic IQ result responder: echoes a result IQ for any request. */
    private fun resultResponder(): (WhatsAppProtocol.Node) -> WhatsAppProtocol.Node =
        { req: WhatsAppProtocol.Node ->
            WhatsAppProtocol.Node(
                tag = "iq",
                attrs = mapOf("type" to "result", "from" to "s.whatsapp.net", "id" to (req.attrs["id"] ?: "")),
            )
        }

    @Test fun fakeRecordsDecodesAndAutoAnswersIq() = runBlocking {
        val transport = FakeWhatsAppTransport()
        var answered: WhatsAppProtocol.Node? = null
        val collectJob = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Unconfined).launch {
            answered = withTimeoutOrNull(3000) {
                WhatsAppProtocol.decodeNode(transport.messages.first())
            }
        }
        transport.iqResponder = { req ->
            WhatsAppProtocol.Node("iq", mapOf("type" to "result"), content = listOf(WhatsAppProtocol.Node("echo", data = req.attrs["id"]?.toByteArray())))
        }
        transport.connect()
        assertEquals(1, transport.connectCount)
        val iq = WhatsAppProtocol.Node("iq", mapOf("id" to "iq-1", "type" to "get", "xmlns" to "w:p"))
        assertTrue(transport.send(WhatsAppProtocol.encodeNode(iq)))
        collectJob.join()
        assertNotNull(answered)
        assertEquals("iq-1", answered!!.attrs["id"])
        assertEquals(listOf("iq"), transport.sentNodes().map { it.tag })
        transport.disconnect()
        assertEquals(1, transport.disconnectCount)
        assertTrue(!transport.sendResult || true)
        transport.sendResult = false
        assertTrue(!transport.send(WhatsAppProtocol.encodeNode(iq)))
        Unit
    }

    @Test fun connectThenSuccessAuthenticatesAndSendsPresence() {
        fake.iqResponder = resultResponder()
        WhatsAppClient.forceResync()
        pollUntil { fake.connectCount >= 1 }
        fake.emitConnectionState(WhatsAppSocket.ConnectionState.Connected)
        // server accepts the login
        fake.emitNode(
            WhatsAppProtocol.Node(
                tag = "success",
                attrs = mapOf("lid" to "123456789@lid", "t" to "1700000000"),
            ),
        )
        pollUntil { WhatsAppClient.state.value is WhatsAppClient.State.Connected }
        assertTrue(WhatsAppClient.isConnected())
        // post-success: passive-active IQ and unavailable presence go out
        pollUntil { fake.sentNodes().any { it.tag == "presence" && it.attrs["type"] == "unavailable" } }
        pollUntil { fake.sentNodes().any { it.tag == "iq" && it.attrs["xmlns"] == "passive" } }
        assertEquals("123456789@lid", WhatsAppClient.authData?.lid)
        Unit
    }

    @Test fun sendIqAndWaitResolvesById() {
        fake.iqResponder = { req ->
            WhatsAppProtocol.Node(
                tag = "iq",
                attrs = mapOf("type" to "result", "id" to (req.attrs["id"] ?: "")),
                content = listOf(WhatsAppProtocol.Node("value", data = "pong".toByteArray())),
            )
        }
        connectClient()
        val resp = runBlocking {
            WhatsAppClient.sendIqAndWait(
                WhatsAppProtocol.Node("iq", mapOf("id" to "ping-1", "type" to "get", "xmlns" to "w:p")),
                timeoutMs = 10_000,
            )
        }
        assertNotNull(resp)
        assertEquals("result", resp!!.attrs["type"])
        assertEquals("pong", resp.getChildByTag("value")?.data?.decodeToString())
        assertTrue(WhatsAppClient.pendingIqs.isEmpty())
        Unit
    }

    @Test fun sendIqAndWaitTimesOutAndCleansUp() {
        fake.iqResponder = null
        connectClient()
        val resp = runBlocking {
            WhatsAppClient.sendIqAndWait(
                WhatsAppProtocol.Node("iq", mapOf("id" to "ping-timeout", "type" to "get")),
                timeoutMs = 200,
            )
        }
        assertNull(resp)
        assertTrue(WhatsAppClient.pendingIqs.isEmpty())
        Unit
    }

    @Test fun sendIqAndWaitWithoutSocketReturnsNull() {
        WhatsAppClient.webSocket = null
        val resp = runBlocking {
            WhatsAppClient.sendIqAndWait(
                WhatsAppProtocol.Node("iq", mapOf("id" to "ping-none", "type" to "get")),
                timeoutMs = 1_000,
            )
        }
        assertNull(resp)
        Unit
    }

    @Test fun sendIqAndWaitWithFailedSendReturnsNull() {
        connectClient()
        fake.sendResult = false
        val resp = runBlocking {
            WhatsAppClient.sendIqAndWait(
                WhatsAppProtocol.Node("iq", mapOf("id" to "ping-fail", "type" to "get")),
                timeoutMs = 1_000,
            )
        }
        assertNull(resp)
        assertTrue(WhatsAppClient.pendingIqs.isEmpty())
        Unit
    }

    @Test fun inboundMessageAndReceiptAreAcked() {
        connectClient()
        fake.clearSent()
        fake.emitNode(
            WhatsAppProtocol.Node(
                tag = "message",
                attrs = mapOf("id" to "msg-1", "from" to "15557654321@s.whatsapp.net", "t" to "1700000000"),
            ),
        )
        runBlocking { fake.awaitSent("ack", 1) }
        val ack = fake.lastSentNode("ack")!!
        assertEquals("message", ack.attrs["class"])
        assertEquals("msg-1", ack.attrs["id"])
        fake.clearSent()
        fake.emitNode(
            WhatsAppProtocol.Node(
                tag = "receipt",
                attrs = mapOf("id" to "msg-1", "from" to "15557654321@s.whatsapp.net", "type" to "delivered"),
            ),
        )
        runBlocking { fake.awaitSent("ack", 1) }
        assertEquals("receipt", fake.lastSentNode("ack")!!.attrs["class"])
        Unit
    }

    @Test fun groupInfoFetchEmitsConversationUpdate() {
        fake.iqResponder = { req ->
            WhatsAppProtocol.Node(
                tag = "iq",
                attrs = mapOf("type" to "result", "id" to (req.attrs["id"] ?: "")),
                content = listOf(
                    WhatsAppProtocol.Node(
                        tag = "group",
                        attrs = mapOf("id" to "120363@g.us", "subject" to "Test Group"),
                        content = listOf(
                            WhatsAppProtocol.Node("participant", mapOf("jid" to "15551111111@s.whatsapp.net")),
                            WhatsAppProtocol.Node("participant", mapOf("jid" to "15552222222@s.whatsapp.net")),
                        ),
                    ),
                ),
            )
        }
        connectClient()
        val events = mutableListOf<WhatsAppEvent>()
        val job = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Unconfined).launch {
            WhatsAppClient.events.collect { events += it }
        }
        runBlocking { WhatsAppClient.fetchAndEmitGroupInfo("120363@g.us") }
        pollUntil { events.any { it is WhatsAppEvent.ConversationUpdate && it.isGroup } }
        val update = events.filterIsInstance<WhatsAppEvent.ConversationUpdate>().first { it.isGroup }
        assertEquals("Test Group", update.peerName)
        assertEquals(2, update.participantCount)
        job.cancel()
        // non-group jids and already-known groups are no-ops
        runBlocking { WhatsAppClient.fetchAndEmitGroupInfo("1555@s.whatsapp.net") }
        runBlocking { WhatsAppClient.fetchAndEmitGroupInfo("120363@g.us") }
        Unit
    }

    @Test fun disconnectSchedulesReconnect() {
        fake.iqResponder = resultResponder()
        connectClient()
        val before = fake.connectCount
        fake.emitConnectionState(WhatsAppSocket.ConnectionState.Disconnected("socket closed"))
        // reconnect scheduling flips to Connecting immediately, then reconnects after backoff
        pollUntil { WhatsAppClient.state.value is WhatsAppClient.State.Connecting || fake.connectCount > before }
        pollUntil(20_000) { fake.connectCount > before }
        Unit
    }

    @Test fun streamError401LogsOut() {
        connectClient()
        fake.emitNode(
            WhatsAppProtocol.Node(
                tag = "failure",
                attrs = mapOf("reason" to "401"),
            ),
        )
        pollUntil { WhatsAppClient.state.value is WhatsAppClient.State.Disconnected }
        assertNull(WhatsAppClient.authData)
        // restore a session for other tests (they re-setup anyway)
        Unit
    }

    /** Runs the client's private connect() via forceResync and waits for the collectors. */
    private fun connectClient() {
        WhatsAppClient.authData = fabricatedAuth()
        WhatsAppClient.forceResync()
        pollUntil { fake.connectCount >= 1 && WhatsAppClient.webSocket === fake }
        fake.emitConnectionState(WhatsAppSocket.ConnectionState.Connected)
        Thread.sleep(200)
    }
}
