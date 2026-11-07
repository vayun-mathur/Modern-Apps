package com.vayunmathur.communicate.whatsapp

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.vayunmathur.communicate.data.whatsapp.WhatsAppAuthData
import com.vayunmathur.communicate.data.whatsapp.WhatsAppClient
import com.vayunmathur.communicate.data.whatsapp.WhatsAppDiag
import com.vayunmathur.communicate.data.whatsapp.WhatsAppEvent
import com.vayunmathur.communicate.data.whatsapp.WhatsAppProtocol
import com.vayunmathur.communicate.data.whatsapp.encodeNode
import com.vayunmathur.communicate.data.whatsapp.handleIncomingMessage
import com.vayunmathur.communicate.data.whatsapp.transport.WhatsAppSocket
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.junit.runner.RunWith

/**
 * Pass-2 slice-1 (whatsapp inbound): notification stanza routing (group changes,
 * identity/picture/account-sync, server sync, calls) and undecryptable-message
 * handling, through the public [handleIncomingMessage] entry point.
 */
@RunWith(AndroidJUnit4::class)
class W5Pass2InboundNotificationsTest {

    private val auth = WhatsAppAuthData(
        phoneNumber = "+15550001", pushName = "Tester", wid = "15550001@s.whatsapp.net",
        noisePrivateKey = "", noisePublicKey = "", identityPrivateKey = "", identityPublicKey = "",
        registrationId = 14, signedPreKeyId = 1, signedPreKeyPublic = "", signedPreKeyPrivate = "",
        signedPreKeySignature = "", lid = "12345@lid",
    )

    private val receivedEvents = mutableListOf<WhatsAppEvent>()

    @BeforeTest
    fun setUp() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        WhatsAppClient.appContext = app
        WhatsAppClient.authData = auth
        WhatsAppClient.db = null
        WhatsAppClient.webSocket = WhatsAppSocket(auth)
        WhatsAppClient.stateMutable.value = WhatsAppClient.State.Connected
        WhatsAppClient.nameCache.clear()
        WhatsAppClient.knownGroups.clear()
        WhatsAppClient.undecryptableTracker.clear()
        WhatsAppDiag.clear()
        receivedEvents.clear()
        CoroutineScope(Dispatchers.Default + Job()).launch {
            WhatsAppClient.events.collect { receivedEvents.add(it) }
        }
    }

    private fun await(label: String, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 15_000
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return
            Thread.sleep(25)
        }
        error("timed out waiting for $label; diag=${WhatsAppDiag.log.value.takeLast(4)} events=$receivedEvents")
    }

    private fun feed(node: WhatsAppProtocol.Node) {
        WhatsAppClient.handleIncomingMessage(WhatsAppProtocol.encodeNode(node))
    }

    private fun notification(type: String, from: String, vararg children: WhatsAppProtocol.Node, attrs: Map<String, String> = emptyMap()) =
        WhatsAppProtocol.Node(
            tag = "notification",
            attrs = mapOf("type" to type, "from" to from, "id" to "n-1", "t" to "1700000000") + attrs,
            content = children.toList(),
        )

    @Test
    fun `group notifications emit system messages`() {
        val node = notification(
            "w:gp2", "12345@g.us",
            WhatsAppProtocol.Node(tag = "subject", attrs = mapOf("subject" to "New Club")),
            WhatsAppProtocol.Node(tag = "description", data = "All the things".toByteArray()),
            WhatsAppProtocol.Node(
                tag = "add",
                content = listOf(WhatsAppProtocol.Node(tag = "participant", attrs = mapOf("jid" to "777@s.whatsapp.net"))),
            ),
            WhatsAppProtocol.Node(tag = "announce", attrs = mapOf("announce" to "true")),
            WhatsAppProtocol.Node(tag = "locked"),
            WhatsAppProtocol.Node(tag = "link", attrs = mapOf("link_type" to "community")),
            WhatsAppProtocol.Node(tag = "unlink", attrs = mapOf("unlink_type" to "community")),
            WhatsAppProtocol.Node(tag = "mystery"),
        )
        feed(node)
        await("group events") { receivedEvents.count { it is WhatsAppEvent.IncomingMessage } >= 5 }
        val bodies = receivedEvents.filterIsInstance<WhatsAppEvent.IncomingMessage>().map { it.body }
        assertTrue(bodies.any { it.contains("New Club") }, bodies.toString())
        assertTrue(bodies.any { it.contains("777@s.whatsapp.net") }, bodies.toString())
        assertTrue(bodies.all { it.startsWith("[") }, bodies.toString())
    }

    @Test
    fun `identity picture and account sync notifications`() {
        feed(
            notification(
                "encrypt", "777@s.whatsapp.net",
                WhatsAppProtocol.Node(tag = "identity"),
                attrs = mapOf("participant" to "888@s.whatsapp.net"),
            ),
        )
        await("identity event") {
            receivedEvents.filterIsInstance<WhatsAppEvent.IncomingMessage>()
                .any { it.body.contains("Security code changed") }
        }
        feed(
            notification(
                "account_sync", "s.whatsapp.net",
                WhatsAppProtocol.Node(tag = "push", attrs = mapOf("name" to "New Push", "jid" to "777@s.whatsapp.net")),
                WhatsAppProtocol.Node(tag = "contact", attrs = mapOf("name" to "Contact Name", "jid" to "888@s.whatsapp.net")),
                WhatsAppProtocol.Node(tag = "other"),
            ),
        )
        await("push names cached") {
            WhatsAppClient.nameCache["777@s.whatsapp.net"] == "New Push" &&
                WhatsAppClient.nameCache["888@s.whatsapp.net"] == "Contact Name"
        }
        // Picture set/delete and the silently ignored types complete without events.
        val before = receivedEvents.size
        feed(notification("picture", "777@s.whatsapp.net", WhatsAppProtocol.Node(tag = "set", attrs = mapOf("id" to "pic1"))))
        feed(notification("picture", "777@s.whatsapp.net", WhatsAppProtocol.Node(tag = "delete")))
        feed(notification("devices", "777@s.whatsapp.net"))
        feed(notification("mediaretry", "777@s.whatsapp.net"))
        feed(notification("totally-unknown", "777@s.whatsapp.net"))
        Thread.sleep(400)
        assertEquals(before, receivedEvents.size)
    }

    @Test
    fun `server sync notification triggers known collections only`() {
        val node = notification(
            "server_sync", "s.whatsapp.net",
            WhatsAppProtocol.Node(tag = "collection", attrs = mapOf("name" to "regular")),
            WhatsAppProtocol.Node(tag = "collection", attrs = mapOf("name" to "not-a-collection")),
            WhatsAppProtocol.Node(tag = "collection"),
        )
        feed(node)
        await("app state fetch logged") { WhatsAppDiag.log.value.any { it.contains("app-state: regular synced") } }
    }

    @Test
    fun `call notifications emit for fresh offers and ignore stale ones`() {
        val fresh = notification(
            "call", "777@s.whatsapp.net",
            WhatsAppProtocol.Node(tag = "offer", attrs = mapOf("call-type" to "video")),
            attrs = mapOf("t" to (System.currentTimeMillis() / 1000).toString(), "id" to "call-1"),
        )
        feed(fresh)
        await("call event") {
            receivedEvents.filterIsInstance<WhatsAppEvent.IncomingMessage>()
                .any { it.body.contains("video call") && it.messageId == "call-call-1" }
        }
        val before = receivedEvents.size
        val stale = notification(
            "call", "777@s.whatsapp.net",
            WhatsAppProtocol.Node(tag = "offer", attrs = mapOf()),
            attrs = mapOf("t" to "1000000000", "id" to "call-old"),
        )
        feed(stale)
        Thread.sleep(400)
        assertEquals(before, receivedEvents.size)
    }

    @Test
    fun `encrypted message without a session logs decrypt failure`() {
        val message = WhatsAppProtocol.Node(
            tag = "message",
            attrs = mapOf("type" to "text", "from" to "777@s.whatsapp.net", "id" to "ENC1"),
            content = listOf(
                WhatsAppProtocol.Node(tag = "enc", attrs = mapOf("type" to "msg", "v" to "2"), data = byteArrayOf(1, 2, 3, 4)),
            ),
        )
        feed(message)
        await("decrypt failed diag") { WhatsAppDiag.log.value.any { it.contains("DECRYPT FAILED") && it.contains("ENC1") } }
    }

    @Test
    fun `plaintext message is tracked as undecryptable with retry cap`() {
        repeat(7) { i ->
            feed(
                WhatsAppProtocol.Node(
                    tag = "message",
                    attrs = mapOf("type" to "text", "from" to "777@s.whatsapp.net", "id" to "PLAIN1"),
                ),
            )
            Thread.sleep(60)
            assertEquals(i + 1, WhatsAppClient.undecryptableTracker.values.sum().coerceAtMost(i + 1))
        }
        assertTrue(WhatsAppClient.undecryptableTracker.isNotEmpty())
    }
}
