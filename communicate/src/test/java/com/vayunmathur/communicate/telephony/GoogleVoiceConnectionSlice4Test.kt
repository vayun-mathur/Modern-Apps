package com.vayunmathur.communicate.telephony

import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.communicate.data.googlevoice.call.GoogleVoiceCallManager
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Slice-4 coverage for the Google Voice Telecom connection ([GoogleVoiceConnection]).
 *
 * Robolectric. Only the synchronous contract is asserted (system actions fan
 * out to the manager; active/end reflect into Telecom state) — the async SIP
 * leg needs a live registrar and is out of reach on the JVM.
 */
@RunWith(RobolectricTestRunner::class)
class GoogleVoiceConnectionSlice4Test {

    private val context: android.content.Context
        get() = ApplicationProvider.getApplicationContext()

    @BeforeTest
    fun setUp() {
        ApplicationProvider.getApplicationContext<android.content.Context>()
        GoogleVoiceCallManager.stopRegistration()
        GoogleVoiceCallManager.clearEnded()
    }

    @Test
    fun `answer and dtmf delegate without touching telecom state`() {
        val connection = GoogleVoiceConnection()
        connection.onAnswer()
        connection.onPlayDtmfTone('5')
        // No disconnect, no active: a fresh Connection sits in STATE_NEW.
        assertEquals(
            android.telecom.Connection.STATE_NEW,
            connection.state,
        )
    }

    @Test
    fun `reject disconnect and abort mark the matching cause`() {
        val rejected = GoogleVoiceConnection()
        rejected.onReject()
        assertEquals(
            android.telecom.DisconnectCause.REJECTED,
            rejected.disconnectCause.code,
        )

        val hungUp = GoogleVoiceConnection()
        hungUp.onDisconnect()
        assertEquals(
            android.telecom.DisconnectCause.LOCAL,
            hungUp.disconnectCause.code,
        )

        val aborted = GoogleVoiceConnection()
        aborted.onAbort()
        assertEquals(
            android.telecom.DisconnectCause.CANCELED,
            aborted.disconnectCause.code,
        )
    }

    @Test
    fun `manager active and end reflect into the connection`() {
        val connection = GoogleVoiceConnection()
        GoogleVoiceCallManager.connection = null
        connection.onCallActive()
        assertEquals(android.telecom.Connection.STATE_ACTIVE, connection.state)

        connection.onCallEnded()
        assertEquals(
            android.telecom.DisconnectCause.REMOTE,
            connection.disconnectCause.code,
        )
    }

    @Test
    fun `telecom account lifecycle and call placement do not throw`() {
        val handle = GoogleVoiceTelecom.handle(context)
        kotlin.test.assertEquals(
            GoogleVoiceConnectionService::class.java.name,
            handle.componentName.className,
        )
        GoogleVoiceTelecom.registerPhoneAccount(context, "+1555")
        GoogleVoiceTelecom.placeOutgoing(context, "+1555")
        GoogleVoiceTelecom.addIncoming(context, "+1555")
        GoogleVoiceTelecom.unregisterPhoneAccount(context)
    }
}
