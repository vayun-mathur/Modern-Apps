package com.vayunmathur.communicate.whatsapp

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.vayunmathur.communicate.data.whatsapp.WhatsAppAuthData
import com.vayunmathur.communicate.data.whatsapp.WhatsAppClient
import com.vayunmathur.communicate.data.whatsapp.WhatsAppContactSync
import com.vayunmathur.communicate.data.whatsapp.WhatsAppMessage
import com.vayunmathur.communicate.data.whatsapp.buildIncomingMediaAttachment
import com.vayunmathur.communicate.data.whatsapp.cacheNotifyName
import com.vayunmathur.communicate.data.whatsapp.downloadMedia
import com.vayunmathur.communicate.data.whatsapp.inflateZlib
import com.vayunmathur.communicate.data.whatsapp.isLoggedIn
import com.vayunmathur.communicate.data.whatsapp.logoutRemote
import com.vayunmathur.communicate.data.whatsapp.proto.WhatsAppE2EProto
import com.vayunmathur.communicate.data.whatsapp.rememberInboundPoll
import com.vayunmathur.communicate.data.whatsapp.resolveDeviceContactName
import com.vayunmathur.communicate.data.whatsapp.resolveName
import com.vayunmathur.communicate.data.whatsapp.transport.WhatsAppSocket
import com.google.protobuf.ByteString
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import org.junit.runner.RunWith

/**
 * Pass-2 slice-1 (whatsapp): contact-sync phone normalization and the remaining
 * client utility surface (zlib, media-attachment gating, login state, names).
 */
@RunWith(AndroidJUnit4::class)
class W5Pass2ContactUtilsTest {

    private val auth = WhatsAppAuthData(
        phoneNumber = "+15550001", pushName = "Tester", wid = "15550001@s.whatsapp.net",
        noisePrivateKey = "", noisePublicKey = "", identityPrivateKey = "", identityPublicKey = "",
        registrationId = 14, signedPreKeyId = 1, signedPreKeyPublic = "", signedPreKeyPrivate = "",
        signedPreKeySignature = "", lid = "12345@lid",
    )

    private lateinit var app: Application

    @BeforeTest
    fun setUp() {
        app = ApplicationProvider.getApplicationContext<Application>()
        WhatsAppClient.appContext = app
        WhatsAppClient.authData = auth
        WhatsAppClient.db = null
        WhatsAppClient.webSocket = WhatsAppSocket(auth)
        WhatsAppClient.stateMutable.value = WhatsAppClient.State.Connected
        WhatsAppClient.nameCache.clear()
    }

    // ---- Contact sync normalization ----

    @Test
    fun `normalize E164 handles national and international forms`() {
        assertEquals("+16502530000", WhatsAppContactSync.normalizeE164("(650) 253-0000", "US"))
        assertEquals("+16502530000", WhatsAppContactSync.normalizeE164("+1 650-253-0000", "US"))
        assertEquals("+442071234567", WhatsAppContactSync.normalizeE164("020 7123 4567", "GB"))
        assertEquals("+442071234567", WhatsAppContactSync.normalizeE164("+44 20 7123 4567", "US"))
        assertEquals("+919876543210", WhatsAppContactSync.normalizeE164("098765 43210", "IN"))
    }

    @Test
    fun `normalize E164 rejects garbage`() {
        assertNull(WhatsAppContactSync.normalizeE164("", "US"))
        assertNull(WhatsAppContactSync.normalizeE164("not a number", "US"))
        assertNull(WhatsAppContactSync.normalizeE164("123", "US"))
        assertNull(WhatsAppContactSync.normalizeE164("555-123", "ZZ"))
    }

    @Test
    fun `contact sync device reads are permission gated`() {
        assertEquals("US", WhatsAppContactSync.defaultRegion(app).let { it.ifEmpty { "US" } })
        assertTrue(WhatsAppContactSync.readDeviceContacts(app).isEmpty()) // no READ_CONTACTS in tests
        val result = WhatsAppContactSync.DeviceContact("+1555", "A")
        assertEquals("+1555", result.e164)
        val sync = WhatsAppContactSync.SyncResult(deviceCount = 3, e164Count = 2, onWhatsAppCount = 1)
        assertEquals(3, sync.deviceCount)
        assertNull(sync.transportError)
        assertEquals("x", WhatsAppContactSync.SyncResult(0, 0, 0, "x").transportError)
    }

    // ---- Client utils ----

    @Test
    fun `inflate zlib round trips and tolerates garbage`() {
        val raw = "history blob contents, repeated repeated repeated".toByteArray()
        val compressed = java.io.ByteArrayOutputStream().also { out ->
            java.util.zip.DeflaterOutputStream(out).use { it.write(raw) }
        }.toByteArray()
        assertContentEquals(raw, WhatsAppClient.inflateZlib(compressed))
        assertEquals(0, WhatsAppClient.inflateZlib(byteArrayOf()).size)
        kotlin.test.assertFailsWith<java.util.zip.DataFormatException> { WhatsAppClient.inflateZlib(byteArrayOf(1, 2, 3)) }
    }

    @Test
    fun `login state follows auth and connection`() {
        assertTrue(WhatsAppClient.isLoggedIn())
        WhatsAppClient.stateMutable.value = WhatsAppClient.State.Idle
        assertFalse(WhatsAppClient.isLoggedIn())
        WhatsAppClient.stateMutable.value = WhatsAppClient.State.Connected
        WhatsAppClient.authData = null
        assertFalse(WhatsAppClient.isLoggedIn())
        WhatsAppClient.authData = auth
        assertTrue(WhatsAppClient.isLoggedIn())
    }

    @Test
    fun `media attachment gating rejects non media and keyless media`() = runTest {
        val text = WhatsAppE2EProto.Message.newBuilder().setConversation("hi").build()
        assertNull(WhatsAppClient.buildIncomingMediaAttachment(text, "m1"))
        // An image with no media key and no URL cannot be fetched.
        val image = WhatsAppE2EProto.Message.newBuilder()
            .setImageMessage(WhatsAppE2EProto.ImageMessage.newBuilder().setMimetype("image/jpeg"))
            .build()
        assertNull(WhatsAppClient.buildIncomingMediaAttachment(image, "m2"))
        // A document with a key but no URL/direct path also stops before any download.
        val doc = WhatsAppE2EProto.Message.newBuilder()
            .setDocumentMessage(
                WhatsAppE2EProto.DocumentMessage.newBuilder()
                    .setMediaKey(ByteString.copyFrom(ByteArray(32)))
                    .setFileName("report.pdf"),
            )
            .build()
        assertNull(WhatsAppClient.buildIncomingMediaAttachment(doc, "m3"))
        // A direct download to an unreachable loopback port fails soft to null.
        assertNull(WhatsAppClient.downloadMedia("http://127.0.0.1:1/media", ByteArray(32), "image"))
    }

    @Test
    fun `name resolution prefers cache then falls back to the number`() = runTest {
        WhatsAppClient.nameCache["777@s.whatsapp.net"] = "Cached Name"
        assertEquals("Cached Name", WhatsAppClient.resolveName("777@s.whatsapp.net"))
        val resolved = WhatsAppClient.resolveName("999@s.whatsapp.net")
        assertTrue(resolved.contains("999"), resolved)
        assertNull(WhatsAppClient.resolveDeviceContactName(null))
        assertNull(WhatsAppClient.resolveDeviceContactName(""))
        // No device contact in the harness: lookup resolves to null, never throws.
        assertNull(WhatsAppClient.resolveDeviceContactName("+1999"))
    }

    @Test
    fun `notify name caching and inbound poll memory are safe without a database`() = runTest {
        val node = com.vayunmathur.communicate.data.whatsapp.WhatsAppProtocol.Node(
            tag = "message",
            attrs = mapOf("from" to "777@s.whatsapp.net", "notify" to "Notify Name"),
        )
        WhatsAppClient.cacheNotifyName(node, "777@s.whatsapp.net")
        assertEquals("Notify Name", WhatsAppClient.nameCache["777@s.whatsapp.net"])
        val message = WhatsAppMessage(
            id = "p1", from = "777@s.whatsapp.net", to = "15550001@s.whatsapp.net",
            body = "vote?", timestamp = 1L, type = "text", messageType = "poll",
        )
        WhatsAppClient.rememberInboundPoll(message)
        // Logging out sends the stanza best-effort; stop() itself is gated on
        // init() having run, which tests never do, so the state is untouched.
        WhatsAppClient.logoutRemote()
        Thread.sleep(300)
        assertTrue(WhatsAppClient.state.value is WhatsAppClient.State.Connected)
    }
}
