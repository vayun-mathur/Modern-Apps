package com.vayunmathur.communicate.telephony

import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.communicate.data.call.InAppCallPhase
import com.vayunmathur.communicate.data.call.InAppCallRegistry
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Slice-4 coverage for the in-app call Telecom mirror ([InAppCallConnection],
 * [InAppCallConnectionService]).
 *
 * Robolectric: drives the connection callbacks directly (the paths the system
 * would invoke) and asserts they fan out to the shared registry, plus that the
 * service builds outgoing/incoming mirrors from registry state.
 */
@RunWith(RobolectricTestRunner::class)
class InAppCallConnectionSlice4Test {

    private val context: android.content.Context
        get() = ApplicationProvider.getApplicationContext()

    @BeforeTest
    fun setUp() {
        InAppCallRegistry.onEnded("reset")
        InAppCallRegistry.clearEnded()
        InAppCallRegistry.connection = null
    }

    private fun incomingCall(): InAppCallConnection = InAppCallConnection().apply {
        setRinging()
    }

    @Test
    fun `reject and disconnect mark the connection disconnected`() {
        val rejected = incomingCall()
        rejected.onReject()
        assertEquals(
            android.telecom.DisconnectCause.REJECTED,
            rejected.disconnectCause.code,
        )

        val hungUp = InAppCallConnection()
        hungUp.onDisconnect()
        assertEquals(
            android.telecom.DisconnectCause.LOCAL,
            hungUp.disconnectCause.code,
        )

        val aborted = InAppCallConnection()
        aborted.onAbort()
        assertEquals(
            android.telecom.DisconnectCause.CANCELED,
            aborted.disconnectCause.code,
        )
    }

    @Test
    fun `bridge active then remote end transitions the connection`() {
        val connection = InAppCallConnection()
        connection.onCallActive()
        assertEquals(android.telecom.Connection.STATE_ACTIVE, connection.state)

        connection.onCallEnded()
        assertEquals(
            android.telecom.DisconnectCause.REMOTE,
            connection.disconnectCause.code,
        )
    }

    @Test
    fun `system mute syncs the registry`() {
        val fake = object : com.vayunmathur.communicate.data.call.InAppCallController {
            override fun answer() = Unit
            override fun reject() = Unit
            override fun hangup() = Unit
            override fun setMuted(muted: Boolean) = Unit
            override fun setSpeaker(on: Boolean) = Unit
        }
        InAppCallRegistry.bind(com.vayunmathur.communicate.data.CommunicateLine.Sim, fake)
        InAppCallRegistry.onCallStarting(
            line = com.vayunmathur.communicate.data.CommunicateLine.Sim,
            peerId = "+1555",
            peerName = "Pat",
            isVideo = false,
            incoming = true,
            capabilities = com.vayunmathur.communicate.data.call.CallCapabilities.AudioOnly,
        )
        val connection = InAppCallConnection()
        connection.onMuteStateChanged(true)
        assertTrue(InAppCallRegistry.state.value.muted)
        connection.onMuteStateChanged(false)
        assertEquals(false, InAppCallRegistry.state.value.muted)
        // Same value again: no toggle, still false.
        connection.onMuteStateChanged(false)
        assertEquals(false, InAppCallRegistry.state.value.muted)
    }

    @Test
    fun `service builds outgoing and incoming mirrors`() {
        val service = org.robolectric.Robolectric.setupService(InAppCallConnectionService::class.java)
        val fake = object : com.vayunmathur.communicate.data.call.InAppCallController {
            override fun answer() = Unit
            override fun reject() = Unit
            override fun hangup() = Unit
            override fun setMuted(muted: Boolean) = Unit
            override fun setSpeaker(on: Boolean) = Unit
        }
        InAppCallRegistry.bind(com.vayunmathur.communicate.data.CommunicateLine.Signal, fake)
        InAppCallRegistry.onCallStarting(
            line = com.vayunmathur.communicate.data.CommunicateLine.Signal,
            peerId = "aci1",
            peerName = "Sam",
            isVideo = false,
            incoming = false,
            capabilities = com.vayunmathur.communicate.data.call.CallCapabilities.AudioOnly,
        )

        val outgoing = service.onCreateOutgoingConnection(null, null)
        assertEquals(android.telecom.Connection.STATE_DIALING, outgoing.state)

        val incoming = service.onCreateIncomingConnection(null, null)
        assertEquals(android.telecom.Connection.STATE_RINGING, incoming.state)

        // A failed mirror must not end the live call.
        service.onCreateOutgoingConnectionFailed(null, null)
        service.onCreateIncomingConnectionFailed(null, null)
        assertEquals(InAppCallPhase.Outgoing, InAppCallRegistry.state.value.phase)
    }

    @Test
    fun `telecom account lifecycle and call placement do not throw`() {
        // Under Robolectric the TelecomManager shadow records (or ignores)
        // these; the contract is they never throw and use our account handle.
        val handle = InAppCallTelecom.handle(context)
        kotlin.test.assertEquals(
            InAppCallConnectionService::class.java.name,
            handle.componentName.className,
        )
        InAppCallTelecom.registerPhoneAccount(context, "In-app calls")
        InAppCallTelecom.addOutgoing(context, "+1555")
        InAppCallTelecom.addIncoming(context, "+1555")
        InAppCallTelecom.unregisterPhoneAccount(context)
    }
}
