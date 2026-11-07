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
import com.vayunmathur.communicate.data.whatsapp.WhatsAppE2EPreKey
import com.vayunmathur.communicate.data.whatsapp.WhatsAppProtocol
import com.vayunmathur.communicate.data.whatsapp.mex.MexResult
import com.vayunmathur.communicate.data.whatsapp.transport.WhatsAppSocket
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.runner.RunWith

/**
 * Pass-2 slice-1 (whatsapp client core): lifecycle guards, node summaries, IQ
 * plumbing, MEX without a persisted id, and the connect-success sequence, all
 * against the unconnected-socket harness with an in-memory database whose
 * prekey store is fully uploaded (so no Rust key generation is ever asked for).
 */
@RunWith(AndroidJUnit4::class)
class W5Pass2ClientCoreTest {

    private fun b64(bytes: ByteArray): String = Base64.encodeToString(bytes, Base64.NO_WRAP)

    private val auth = WhatsAppAuthData(
        phoneNumber = "+15550001", pushName = "Tester", wid = "15550001@s.whatsapp.net",
        noisePrivateKey = b64(ByteArray(32) { 1 }), noisePublicKey = b64(ByteArray(32) { 2 }),
        identityPrivateKey = b64(ByteArray(32) { 3 }), identityPublicKey = b64(ByteArray(32) { 4 }),
        registrationId = 14, signedPreKeyId = 5,
        signedPreKeyPublic = b64(ByteArray(32) { 5 }), signedPreKeyPrivate = b64(ByteArray(32) { 6 }),
        signedPreKeySignature = b64(ByteArray(64) { 7 }), lid = "",
    )

    @BeforeTest
    fun setUp() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val db = Room.inMemoryDatabaseBuilder(app, WhatsAppDatabase::class.java)
            .setSingleConnectionPool()
            .allowMainThreadQueries().build()
        runBlocking {
            db.e2ePreKeyDao().insertAll(listOf(WhatsAppE2EPreKey(1, ByteArray(64), uploaded = true)))
        }
        WhatsAppClient.appContext = app
        WhatsAppClient.authData = auth
        WhatsAppClient.db = db
        WhatsAppClient.webSocket = WhatsAppSocket(auth)
        WhatsAppClient.stateMutable.value = WhatsAppClient.State.Idle
        WhatsAppClient.pendingIqs.clear()
        WhatsAppDiag.clear()
    }

    @Test
    fun `node summary renders tags attrs and children`() {
        val leaf = WhatsAppProtocol.Node(tag = "active")
        assertEquals("<active>", WhatsAppClient.nodeSummary(leaf))
        val iq = WhatsAppProtocol.Node(
            tag = "iq",
            attrs = mapOf("id" to "1", "type" to "set"),
            content = listOf(leaf, WhatsAppProtocol.Node(tag = "privacy")),
        )
        assertEquals("<iq id=1 type=set> {active,privacy}", WhatsAppClient.nodeSummary(iq))
    }

    @Test
    fun `lifecycle guards without init are no-ops`() {
        // init() was never called in tests (it needs the SQLCipher/KeyStore path),
        // so start/stop must be silent no-ops.
        WhatsAppClient.start()
        WhatsAppClient.stop()
        assertFalse(WhatsAppClient.isConnected())
        WhatsAppClient.stateMutable.value = WhatsAppClient.State.Connected
        assertTrue(WhatsAppClient.isConnected())
        assertEquals(WhatsAppClient.State.Connected, WhatsAppClient.state.value)
        // forceResync before init() is likewise gated off entirely.
        WhatsAppClient.authData = null
        WhatsAppClient.forceResync()
        assertTrue(WhatsAppDiag.log.value.none { it.contains("forceResync") })
        WhatsAppClient.authData = auth
    }

    @Test
    fun `iq plumbing fails soft and cleans up`() = runTest {
        val node = WhatsAppProtocol.Node(tag = "iq", attrs = mapOf("id" to "iq-9", "type" to "get"))
        assertNull(WhatsAppClient.sendIqAndWait(node, timeoutMs = 100))
        assertTrue(WhatsAppClient.pendingIqs.isEmpty())
        // No id attribute: never even registers.
        assertNull(WhatsAppClient.sendIqAndWait(WhatsAppProtocol.Node(tag = "iq"), timeoutMs = 100))
        WhatsAppClient.webSocket = null
        assertNull(WhatsAppClient.sendIqAndWait(node, timeoutMs = 100))
        WhatsAppClient.webSocket = WhatsAppSocket(auth)
        assertFalse(WhatsAppClient.setPassiveActive())
        WhatsAppClient.sendUnavailablePresence()
        WhatsAppClient.webSocket = null
        WhatsAppClient.sendUnavailablePresence()
        WhatsAppClient.webSocket = WhatsAppSocket(auth)
    }

    @Test
    fun `mex without a persisted id is a typed transport failure`() = runTest {
        val result = WhatsAppClient.mexCall("definitely_not_a_real_operation_xyz", "{}")
        assertFalse(result.isSuccess)
        assertEquals("no_persisted_id:definitely_not_a_real_operation_xyz", result.transportError)
    }

    @Test
    fun `auth data persists through shared preferences`() {
        val app = WhatsAppClient.appContext
        WhatsAppAuthData.clear(app)
        assertNull(WhatsAppAuthData.load(app))
        WhatsAppAuthData.save(app, auth)
        val loaded = WhatsAppAuthData.load(app)
        assertEquals(auth.wid, loaded?.wid)
        assertEquals(auth.registrationId, loaded?.registrationId)
        WhatsAppAuthData.clear(app)
        assertNull(WhatsAppAuthData.load(app))
    }

    @Test
    fun `connect success authenticates, adopts the LID, and runs post-success steps`() = runTest {
        val success = WhatsAppProtocol.Node(tag = "success", attrs = mapOf("lid" to "12345@lid"))
        WhatsAppClient.handleConnectSuccess(success)
        assertTrue(WhatsAppClient.isConnected())
        // The server-assigned LID is adopted and persisted.
        assertEquals("12345@lid", WhatsAppClient.authData?.lid)
        assertEquals("12345@lid", WhatsAppAuthData.load(WhatsAppClient.appContext)?.lid)
        assertTrue(WhatsAppDiag.log.value.any { it.contains("AUTHENTICATED") })
        assertTrue(WhatsAppDiag.log.value.any { it.contains("awaiting pushed history") })
        // Prekeys are all uploaded locally, so the upload check logs and skips Rust.
        val deadline = System.currentTimeMillis() + 10_000
        var sawPrekeys = false
        while (System.currentTimeMillis() < deadline && !sawPrekeys) {
            sawPrekeys = WhatsAppDiag.log.value.any { it.contains("prekeys: nothing to upload") }
            if (!sawPrekeys) Thread.sleep(25)
        }
        assertTrue(sawPrekeys, WhatsAppDiag.log.value.takeLast(5).toString())
        // A second success with no lid leaves the adopted one in place.
        WhatsAppClient.handleConnectSuccess(WhatsAppProtocol.Node(tag = "success"))
        assertEquals("12345@lid", WhatsAppClient.authData?.lid)
    }
}
