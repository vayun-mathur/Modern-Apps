package com.vayunmathur.communicate.data

import android.content.Context
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.room3.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.communicate.data.signal.SignalAuthData
import com.vayunmathur.communicate.data.signal.SignalCachedMessage
import com.vayunmathur.communicate.data.signal.SignalClient
import com.vayunmathur.communicate.data.signal.SignalDatabase
import com.vayunmathur.communicate.data.signal.SignalEvent
import com.vayunmathur.communicate.data.signal.markRead
import com.vayunmathur.communicate.data.signal.sendTyping
import com.vayunmathur.communicate.ui.rememberLineChoices
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * W1 wave 9 (micro): per-line call routing in `CommunicateRepositoryHelperKt`,
 * Signal typing/read-receipt emitters, and the line-choices composition.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CommunicateWave9W1Test {

    @get:Rule
    val rule = createComposeRule()

    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private lateinit var signalDb: SignalDatabase
    private val aci = "7c1d9b4e-aaaa-bbbb-cccc-ddddeeeeffff"

    @BeforeTest
    fun setUp() {
        signalDb = Room.inMemoryDatabaseBuilder(context, SignalDatabase::class.java)
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
    }

    @AfterTest
    fun tearDown() {
        SignalClient.db = null
        SignalClient.authData = null
        SignalClient.e2e = null
        SignalClient.pendingIdentityChanges.clear()
        signalDb.close()
    }

    @Test
    fun `placeCallForLine routes per line`() = runTest {
        with(CommunicateRepository) {
            assertTrue(placeCallForLine(context, CommunicateLine.Sim, "+14155550102", null))
            assertTrue(placeCallForLine(context, CommunicateLine.GoogleVoice, "+14155550102", null))
            assertFalse(placeCallForLine(context, CommunicateLine.WhatsApp, " ", null))
            assertFalse(placeCallForLine(context, CommunicateLine.WhatsApp, "", ""))
            assertTrue(
                placeCallForLine(context, CommunicateLine.WhatsApp, "+14155550102", null),
            ) // manager is unbridged on this host; routing still reports placed
            assertFalse(placeCallForLine(context, CommunicateLine.Signal, " ", null))
            assertTrue(placeCallForLine(context, CommunicateLine.Signal, "+14155550102", null))
            assertFalse(
                placeCallForLine(context, CommunicateLine.Signal, "+14155550102", "group:unknown"),
            )
            assertFalse(placeCallForLine(context, CommunicateLine.Rcs, "+14155550102", null))
            // WhatsApp/Signal call-control pass-throughs are inert unbridged.
            whatsAppAnswerCall()
            whatsAppRejectCall()
            whatsAppHangupCall()
            whatsAppSetCallMuted(true)
            whatsAppSetCallSpeaker(true)
            assertNotNull(whatsAppCallState.value)
        }
    }

    @Test
    fun `signal typing and markRead emit events and update the cache`() = runTest {
        signalDb.cachedMessageDao().upsert(
            SignalCachedMessage(
                messageId = "m1", conversationId = aci, body = "hi",
                timestamp = 1_700_000_000_000L, outgoing = false, senderId = aci, status = 1,
            ),
        )
        val typing = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Unconfined).async { SignalClient.events.filterIsInstance<SignalEvent.TypingIndicator>().first() }
        SignalClient.sendTyping(aci, isTyping = true)
        assertEquals(true, withTimeoutOrNull(5_000) { typing.await() }?.isTyping)
        val receipt = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Unconfined).async { SignalClient.events.filterIsInstance<SignalEvent.ReadReceipt>().first() }
        SignalClient.markRead(aci, listOf("m1", "missing"))
        assertNotNull(receipt.await())
        assertEquals(3, signalDb.cachedMessageDao().get("m1")!!.status) // read
        assertFalse(SignalClient.isConnected())
    }

    @Test
    fun `line choices fall back to a single sim when signed out`() {
        var captured: List<LineChoice>? = null
        rule.setContent { captured = rememberLineChoices() }
        rule.waitForIdle()
        val choices = captured
        assertTrue(choices != null && choices.isNotEmpty())
        assertTrue(choices!!.all { it is LineChoice.Sim })
    }
}
