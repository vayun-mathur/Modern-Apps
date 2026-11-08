package com.vayunmathur.communicate.whatsapp

import android.app.Application
import android.util.Base64
import androidx.room3.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.vayunmathur.communicate.data.whatsapp.WhatsAppAuthData
import com.vayunmathur.communicate.data.whatsapp.WhatsAppClient
import com.vayunmathur.communicate.data.whatsapp.WhatsAppDatabase
import com.vayunmathur.communicate.data.whatsapp.WhatsAppDiag
import com.vayunmathur.communicate.data.whatsapp.WhatsAppProtocol
import com.vayunmathur.communicate.data.whatsapp.buildEncryptedGroupTextNode
import com.vayunmathur.communicate.data.whatsapp.buildEncryptedTextNode
import com.vayunmathur.communicate.data.whatsapp.e2e.WhatsAppE2E
import com.vayunmathur.communicate.data.whatsapp.getUserDevices
import com.vayunmathur.communicate.data.whatsapp.padMessage
import com.vayunmathur.communicate.data.whatsapp.sendMessage
import com.vayunmathur.communicate.data.whatsapp.sendMessageAdvanced
import com.vayunmathur.communicate.data.whatsapp.transport.WhatsAppSocket
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import org.junit.runner.RunWith

/**
 * Pass-2 slice-1 (whatsapp send/E2E): the encrypted-send pipeline with a real
 * in-memory [WhatsAppDatabase] bound, so `ensureE2E` constructs and the fan-out
 * runs until device sessions turn out to be unavailable (the unconnected socket
 * refuses every IQ, so no device can be encrypted for and builds fail soft).
 * Sender-key group crypto itself needs the Rust `communicate_signal` native
 * library, which is not loadable from this module's JVM tests
 * (UnsatisfiedLinkError), so it is deliberately not exercised here.
 */
@RunWith(AndroidJUnit4::class)
class W5Pass2E2ESendTest {

    private fun b64(bytes: ByteArray): String = Base64.encodeToString(bytes, Base64.NO_WRAP)

    private val auth = WhatsAppAuthData(
        phoneNumber = "+15550001", pushName = "Tester", wid = "15550001@s.whatsapp.net",
        noisePrivateKey = b64(ByteArray(32) { 1 }), noisePublicKey = b64(ByteArray(32) { 2 }),
        identityPrivateKey = b64(ByteArray(32) { 3 }), identityPublicKey = b64(ByteArray(32) { 4 }),
        registrationId = 14, signedPreKeyId = 5,
        signedPreKeyPublic = b64(ByteArray(32) { 5 }), signedPreKeyPrivate = b64(ByteArray(32) { 6 }),
        signedPreKeySignature = b64(ByteArray(64) { 7 }), lid = "12345@lid",
    )

    private lateinit var db: WhatsAppDatabase

    @BeforeTest
    fun setUp() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        db = Room.inMemoryDatabaseBuilder(app, WhatsAppDatabase::class.java)
            .setSingleConnectionPool()
            .allowMainThreadQueries().build()
        WhatsAppClient.appContext = app
        WhatsAppClient.authData = auth
        WhatsAppClient.db = db
        WhatsAppClient.webSocket = WhatsAppSocket(auth)
        WhatsAppClient.stateMutable.value = WhatsAppClient.State.Connected
        WhatsAppClient.pendingMessageIDs.clear()
        WhatsAppClient.recentSentDMs.clear()
        WhatsAppDiag.clear()
    }

    @Test
    fun `e2e constructs against the in-memory database`() = runTest {
        val crypto = WhatsAppClient.ensureE2E(auth)
        assertNotNull(crypto)
        assertTrue(crypto === WhatsAppClient.ensureE2E(auth)) // cached instance
        assertEquals(32, crypto.ownIdentityPublicKey.size)
        assertEquals("777" to 0, crypto.signalAddress("777@s.whatsapp.net"))
        assertEquals("777" to 3, crypto.signalAddress("777:3@s.whatsapp.net"))
        assertEquals("777" to 0, crypto.signalAddress("777.1@s.whatsapp.net"))
        assertTrue(!crypto.hasSession("777@s.whatsapp.net"))
        crypto.deleteSession("777@s.whatsapp.net") // deleting an absent session is fine
    }

    @Test
    fun `dm send pipeline runs fan-out and fails soft without sessions`() = runTest {
        assertNull(WhatsAppClient.sendMessage("15550002@s.whatsapp.net", "hello there"))
        // The plaintext was cached for retry resends even though no device encrypted.
        assertEquals(1, WhatsAppClient.recentSentDMs.size)
        val cached = WhatsAppClient.recentSentDMs.values.single()
        assertEquals("15550002@s.whatsapp.net", cached.to)
        assertEquals("text", cached.type)
        assertTrue(cached.msgPlaintextPadded.isNotEmpty())
        assertNotNull(cached.dsmPlaintextPadded)
        assertNull(WhatsAppClient.buildEncryptedTextNode("15550002@s.whatsapp.net", "id-x", "body"))
        assertNull(
            WhatsAppClient.sendMessageAdvanced(
                "15550002@s.whatsapp.net", "hi @777",
                mentionedJids = listOf("777@s.whatsapp.net"),
            ),
        )
        assertTrue(WhatsAppDiag.log.value.any { it.contains("No devices could be encrypted") || it.contains("send:") })
        // Device discovery itself is an honest empty (Iq unanswered).
        assertEquals(emptyList(), WhatsAppClient.getUserDevices(listOf("777@s.whatsapp.net")))
        assertEquals(emptyList(), WhatsAppClient.getUserDevices(emptyList()))
    }

    @Test
    fun `ensure session reports false without a prekey answer`() = runTest {
        assertTrue(!WhatsAppClient.ensureSession("777@s.whatsapp.net"))
        assertTrue(!WhatsAppClient.ensureSession("777:2@s.whatsapp.net"))
    }

}
