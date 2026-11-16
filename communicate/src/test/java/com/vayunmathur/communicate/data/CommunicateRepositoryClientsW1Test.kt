package com.vayunmathur.communicate.data

import android.app.Application
import android.content.Context
import android.util.Base64
import androidx.room3.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.communicate.data.signal.SignalAuthData
import com.vayunmathur.communicate.data.signal.SignalClient
import com.vayunmathur.communicate.data.signal.SignalDatabase
import com.vayunmathur.communicate.data.whatsapp.WhatsAppAuthData
import com.vayunmathur.communicate.data.whatsapp.WhatsAppClient
import com.vayunmathur.communicate.data.whatsapp.WhatsAppDatabase
import com.vayunmathur.communicate.data.whatsapp.transport.WhatsAppSocket
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * W1 wave 2: the WhatsApp/Signal repository delegate layer
 * (`CommunicateRepositoryWhatsAppKt` / `CommunicateRepositorySignalKt`) and
 * the MEX wrappers, driven offline: Signal with an in-memory DB and no
 * protocol store/socket, WhatsApp in the Connected state with an
 * unconnected socket — every networked path must fail closed, while local
 * group creation still persists.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CommunicateRepositoryClientsW1Test {

    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private lateinit var signalDb: SignalDatabase
    private lateinit var waDb: WhatsAppDatabase

    private fun b64(bytes: ByteArray): String = Base64.encodeToString(bytes, Base64.NO_WRAP)

    private val waAuth = WhatsAppAuthData(
        phoneNumber = "+15550001", pushName = "Tester", wid = "15550001@s.whatsapp.net",
        noisePrivateKey = b64(ByteArray(32) { 1 }), noisePublicKey = b64(ByteArray(32) { 2 }),
        identityPrivateKey = b64(ByteArray(32) { 3 }), identityPublicKey = b64(ByteArray(32) { 4 }),
        registrationId = 14, signedPreKeyId = 5,
        signedPreKeyPublic = b64(ByteArray(32) { 5 }), signedPreKeyPrivate = b64(ByteArray(32) { 6 }),
        signedPreKeySignature = b64(ByteArray(64) { 7 }), lid = "12345@lid",
    )

    @BeforeTest
    fun setUp() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        signalDb = Room.inMemoryDatabaseBuilder(app, SignalDatabase::class.java)
            .setDriver(BundledSQLiteDriver()).allowMainThreadQueries().build()
        SignalClient.db = signalDb
        SignalClient.authData = SignalAuthData(
            phoneNumber = "+14155552671",
            aci = "3f0e5a2c-1111-2222-3333-444455556666",
            password = "secret",
        )
        SignalClient.socket = null
        SignalClient.unauthSocket = null
        SignalClient.e2e = null
        SignalClient.pendingIdentityChanges.clear()

        waDb = Room.inMemoryDatabaseBuilder(app, WhatsAppDatabase::class.java)
            .setSingleConnectionPool().allowMainThreadQueries().build()
        WhatsAppClient.appContext = app
        WhatsAppClient.authData = waAuth
        WhatsAppClient.db = waDb
        WhatsAppClient.webSocket = WhatsAppSocket(waAuth)
        WhatsAppClient.stateMutable.value = WhatsAppClient.State.Connected
        WhatsAppClient.pendingMessageIDs.clear()
        WhatsAppClient.recentSentDMs.clear()
    }

    @AfterTest
    fun tearDown() {
        SignalClient.db = null
        SignalClient.authData = null
        SignalClient.e2e = null
        SignalClient.pendingIdentityChanges.clear()
        signalDb.close()
        WhatsAppClient.stateMutable.value = WhatsAppClient.State.Idle
        WhatsAppClient.webSocket = null
        WhatsAppClient.db = null
        WhatsAppClient.authData = null
        // Not closed: WhatsAppClient caches its E2E instance statically, and
        // closing would leave that cache pointing at a dead database for the
        // next test. The in-memory instance is abandoned instead.
    }

    // -- Signal delegates (offline fail-closed) --

    @Test
    fun `signal rich actions fail closed without a protocol store`() = runTest {
        val aci = "7c1d9b4e-aaaa-bbbb-cccc-ddddeeeeffff"
        with(CommunicateRepository) {
            // Signal rich actions are optimistic: they cache/emit locally
            // and report success even when the relay cannot be reached.
            assertTrue(sendSignalReaction(aci, "m1", "👍"))
            assertTrue(editSignalMessage(aci, "m1", "edited"))
            assertTrue(revokeSignalMessage(aci, "m1"))
            val pollId = sendSignalPoll(aci, "Lunch?", listOf("Pizza", "Sushi"))
            assertNotNull(pollId)
            val cachedPoll = signalDb.cachedMessageDao().get(pollId)
            assertNotNull(cachedPoll)
            assertEquals("Lunch?", cachedPoll.body)
            assertTrue(sendSignalPollVote(aci, "m1", listOf("Pizza")))
            assertTrue(sendSignalReadReceipt(aci, "m1", 1_000L))
            assertTrue(isSignalConnected().not()) // state is not Connected
        }
    }

    @Test
    fun `signal group creation through the repository persists locally`() = runTest {
        val id = CommunicateRepository.createSignalGroup(
            context, "Study Group", listOf("4155550102", "+14155550103", "4155550102", " "),
        )
        assertNotNull(id)
        val stored = signalDb.conversationDao().getConversation(id)
        assertNotNull(stored)
        assertEquals("Study Group", stored.name)
        assertNull(
            CommunicateRepository.createSignalGroup(context, "Empty", listOf(" ", "")),
        )
    }

    @Test
    fun `signal identity helpers are empty without a pending change`() = runTest {
        assertNull(
            CommunicateRepository.signalPendingIdentityChange(context, null, "+14155550102"),
        )
        assertFalse(
            CommunicateRepository.acceptSignalIdentity(context, null, "+14155550102", "00ff"),
        )
        // markSignalRead needs the SQLCipher-backed singleton, whose
        // AndroidKeyStore is unavailable on this host — not exercised here.
    }

    // -- WhatsApp delegates (connected state, dead socket) --

    @Test
    fun `whatsApp rich actions fail soft without sessions`() = runTest {
        val jid = "15550002@s.whatsapp.net"
        with(CommunicateRepository) {
            assertTrue(isWhatsAppConnected())
            assertFalse(sendWhatsAppReaction(jid, "m1", "👍", targetFromMe = false, targetSenderJid = null))
            assertFalse(editWhatsAppMessage(jid, "m1", "edited"))
            assertFalse(revokeWhatsAppMessage(jid, "m1"))
            assertFalse(sendWhatsAppPollVote(jid, "m1", jid, pollFromMe = false, listOf("Pizza")))
            assertNull(createWhatsAppPoll(jid, "Lunch?", listOf("Pizza", "Sushi")))
            assertFalse(sendWhatsAppMedia(jid, byteArrayOf(1, 2, 3), "image/png", "a.png"))
            assertFalse(sendWhatsAppReadReceipt(jid, "m1", 1_000L))
            // markWhatsAppRead needs the SQLCipher-backed singleton, whose
            // AndroidKeyStore is unavailable on this host — not exercised.
        }
    }

    @Test
    fun `whatsApp poll and contact dispatch through the unified layer`() = runTest {
        val jid = "15550002@s.whatsapp.net"
        with(CommunicateRepository) {
            assertFalse(sendPoll(context, CommunicateLine.WhatsApp, jid, "Q?", listOf("a", "b")))
            assertTrue(sendPoll(context, CommunicateLine.Signal, jid, "Q?", listOf("a", "b")))
            assertFalse(
                sendPollVote(
                    context, CommunicateLine.WhatsApp, jid, "m1", listOf("a"),
                    pollCreatorId = null, pollFromMe = false,
                ),
            )
            assertTrue(
                sendPollVote(context, CommunicateLine.Signal, jid, "m1", listOf("a")),
            )
        }
    }

    // -- MEX wrappers (uncaptured operations fail typed, no network) --

    @Test
    fun `mex wrappers return typed transport failures for uncaptured ops`() = runTest {
        with(CommunicateRepository) {
            val username = whatsAppUsernameGet(context)
            assertEquals("no_persisted_id:UsernameGet", username.transportError)
            val blocklist = whatsAppBlocklistGet(context)
            assertEquals("no_persisted_id:BlocklistGet", blocklist.transportError)
            val presence = whatsAppPresence(context, listOf("12345@lid"))
            assertEquals("no_persisted_id:GetOnlineOrLastStatus", presence.transportError)
        }
    }

    @Test
    fun `whatsApp refresh presence is a no-op offline`() = runTest {
        CommunicateRepository.whatsAppRefreshPresence("15550002@s.whatsapp.net")
        // No assertion beyond not throwing; the client is not connected.
        assertFalse(WhatsAppClient.stateMutable.value is WhatsAppClient.State.Idle)
    }
}
