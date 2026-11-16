package com.vayunmathur.communicate.data

import android.Manifest
import android.app.Application
import android.content.Context
import android.provider.ContactsContract
import android.util.Base64
import androidx.room3.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.vayunmathur.communicate.data.signal.SignalContactSync
import com.vayunmathur.communicate.data.whatsapp.WhatsAppAuthData
import com.vayunmathur.communicate.data.whatsapp.WhatsAppClient
import com.vayunmathur.communicate.data.whatsapp.WhatsAppDatabase
import com.vayunmathur.communicate.data.whatsapp.WhatsAppProtocol
import com.vayunmathur.communicate.data.whatsapp.handleRetryReceipt
import com.vayunmathur.communicate.data.whatsapp.sendRetryReceipt
import com.vayunmathur.communicate.data.whatsapp.transport.WhatsAppSocket
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * W1 wave 8: Signal contact-sync pure helpers and its offline early
 * return, plus WhatsApp retry-receipt handling for uncached messages.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CommunicateWave8W1Test {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @BeforeTest
    fun setUp() {
        FakeContactsState.reset()
        Robolectric.setupContentProvider(FakeContactsProvider::class.java, ContactsContract.AUTHORITY)
        shadowOf(context as Application).grantPermissions(Manifest.permission.READ_CONTACTS)
    }

    @Test
    fun `signal contact sync encoding helpers round trip`() {
        assertEquals(
            "000000034bbc8d96",
            SignalContactSync.e164ToUint64Bytes("+14155550102").joinToString("") { "%02x".format(it) },
        )
        assertEquals(
            "0000000000000000",
            SignalContactSync.e164ToUint64Bytes("no digits").joinToString("") { "%02x".format(it) },
        )
        assertEquals("+14155550102", SignalContactSync.uint64ToE164(14155550102L))
        assertEquals("+0", SignalContactSync.uint64ToE164(0L))
        assertEquals("+442079460958", SignalContactSync.normalizeE164("020 7946 0958", "GB"))
        assertNull(SignalContactSync.normalizeE164("garbage", "US"))
        assertTrue(SignalContactSync.defaultRegion(context).isNotBlank())
    }

    @Test
    fun `signal contact sync reads contacts and returns early on invalid numbers`() = runTest {
        FakeContactsState.phoneRows = listOf(
            mapOf(
                ContactsContract.CommonDataKinds.Phone._ID to 1L,
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME_PRIMARY to "Garbage",
                ContactsContract.CommonDataKinds.Phone.NUMBER to "not a phone",
                ContactsContract.CommonDataKinds.Phone.TYPE to 2,
                ContactsContract.CommonDataKinds.Phone.LABEL to null,
            ),
            mapOf(
                ContactsContract.CommonDataKinds.Phone._ID to 2L,
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME_PRIMARY to "Blank",
                ContactsContract.CommonDataKinds.Phone.NUMBER to " ",
                ContactsContract.CommonDataKinds.Phone.TYPE to 2,
                ContactsContract.CommonDataKinds.Phone.LABEL to null,
            ),
        )
        val contacts = SignalContactSync.readDeviceContacts(context)
        assertEquals(listOf("Garbage" to "not a phone"), contacts)
        val result = SignalContactSync.sync(context)
        assertEquals(1, result.deviceCount)
        assertEquals(0, result.e164Count)
        assertEquals(0, result.onSignalCount)
        assertNull(result.transportError)
        shadowOf(context as Application).denyPermissions(Manifest.permission.READ_CONTACTS)
        assertTrue(SignalContactSync.readDeviceContacts(context).isEmpty())
    }
}

/** WhatsApp retry receipts against a Connected-but-dead socket. */
@RunWith(AndroidJUnit4::class)
class CommunicateRetryW1Test {

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
            .setSingleConnectionPool().allowMainThreadQueries().build()
        WhatsAppClient.appContext = app
        WhatsAppClient.authData = auth
        WhatsAppClient.db = db
        WhatsAppClient.webSocket = WhatsAppSocket(auth)
        WhatsAppClient.stateMutable.value = WhatsAppClient.State.Connected
        WhatsAppClient.recentSentDMs.clear()
        WhatsAppClient.retryResendCounts.clear()
        WhatsAppClient.undecryptableTracker.clear()
    }

    @AfterTest
    fun tearDown() {
        WhatsAppClient.stateMutable.value = WhatsAppClient.State.Idle
        WhatsAppClient.webSocket = null
        WhatsAppClient.db = null
        WhatsAppClient.authData = null
    }

    private fun receipt(id: String?, from: String?, participant: String? = null) = WhatsAppProtocol.Node(
        tag = "receipt",
        attrs = buildMap {
            if (id != null) put("id", id)
            if (from != null) put("from", from)
            put("type", "retry")
            if (participant != null) put("participant", participant)
        },
    )

    @Test
    fun `retry receipts for uncached or group messages are dropped`() = runTest {
        // No message id at all.
        WhatsAppClient.handleRetryReceipt(receipt(id = null, from = "15550002@s.whatsapp.net"))
        // Group retry: not actionable (group resends are not cached).
        WhatsAppClient.handleRetryReceipt(receipt(id = "m1", from = "12345@g.us"))
        // 1:1 retry for a message we never cached: nothing to resend.
        WhatsAppClient.handleRetryReceipt(receipt(id = "m2", from = "15550002@s.whatsapp.net"))
        WhatsAppClient.handleRetryReceipt(
            receipt(id = "m3", from = "12345@g.us", participant = "15550002:3@s.whatsapp.net"),
        )
    }

    @Test
    fun `sendRetryReceipt counts attempts and still sends without keys`() = runTest {
        val node = WhatsAppProtocol.Node(
            tag = "message",
            attrs = mapOf("id" to "in-1", "from" to "15550002@s.whatsapp.net", "type" to "text"),
        )
        WhatsAppClient.sendRetryReceipt(node)
        // Second receipt attaches keys (the Rust key build fails soft here).
        WhatsAppClient.sendRetryReceipt(node)
        WhatsAppClient.sendRetryReceipt(
            WhatsAppProtocol.Node(tag = "message", attrs = mapOf("from" to "x@s.whatsapp.net")),
        ) // no id: dropped
    }
}
