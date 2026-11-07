package com.vayunmathur.communicate.telephony

import android.content.ComponentName
import android.net.Uri
import android.os.Bundle
import android.telecom.Connection
import android.telecom.ConnectionRequest
import android.telecom.PhoneAccountHandle
import android.telecom.TelecomManager
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.communicate.data.CommunicateLine
import com.vayunmathur.communicate.data.call.CallCapabilities
import com.vayunmathur.communicate.data.call.InAppCallController
import com.vayunmathur.communicate.data.call.InAppCallPhase
import com.vayunmathur.communicate.data.call.InAppCallRegistry
import com.vayunmathur.communicate.data.googlevoice.call.GoogleVoiceCallManager
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner

/** Voice pass-3: Telecom Connection/ConnectionService bridges for GV and in-app calls. */
@RunWith(RobolectricTestRunner::class)
class TelecomConnectionsVoicePass3Test {

    private val ctx get() = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val actions = mutableListOf<String>()
    private val controller = object : InAppCallController {
        override fun answer() { actions += "answer" }
        override fun reject() { actions += "reject" }
        override fun hangup() { actions += "hangup" }
        override fun setMuted(muted: Boolean) { actions += "muted=$muted" }
        override fun setSpeaker(on: Boolean) { actions += "speaker=$on" }
    }

    @BeforeTest
    fun setUp() {
        actions.clear()
        InAppCallRegistry.onEnded("reset")
        InAppCallRegistry.clearEnded()
        InAppCallRegistry.videoController = null
        InAppCallRegistry.dtmfController = null
        InAppCallRegistry.holdController = null
        InAppCallRegistry.connection = null
        GoogleVoiceCallManager.connection = null
    }

    private fun handle(serviceClass: Class<*>) =
        PhoneAccountHandle(ComponentName(ctx, serviceClass), "acct")

    @Test
    fun googleVoiceConnectionLifecycle() {
        val connection = GoogleVoiceConnection()
        assertTrue(connection.audioModeIsVoip)
        assertTrue(connection.connectionCapabilities and Connection.CAPABILITY_MUTE != 0)
        assertTrue(connection.connectionCapabilities and Connection.CAPABILITY_SUPPORT_HOLD != 0)
        connection.onCallActive()
        assertEquals(Connection.STATE_ACTIVE, connection.state)
        // DTMF and answer without an initialized manager are no-ops
        connection.onPlayDtmfTone('5')
        connection.onAnswer()

        val rejected = GoogleVoiceConnection()
        rejected.onReject()
        assertEquals(Connection.STATE_DISCONNECTED, rejected.state)

        val disconnected = GoogleVoiceConnection()
        disconnected.onDisconnect()
        assertEquals(Connection.STATE_DISCONNECTED, disconnected.state)

        val aborted = GoogleVoiceConnection()
        aborted.onAbort()
        assertEquals(Connection.STATE_DISCONNECTED, aborted.state)

        val ended = GoogleVoiceConnection()
        ended.onCallEnded()
        assertEquals(Connection.STATE_DISCONNECTED, ended.state)
    }

    @Test
    fun inAppCallConnectionBridgesToRegistry() {
        InAppCallRegistry.bind(CommunicateLine.Signal, controller, CallCapabilities.AudioOnly)
        InAppCallRegistry.onCallStarting(CommunicateLine.Signal, "peer-1", "Pat Lee", false, incoming = false)
        val connection = InAppCallConnection()
        connection.onMuteStateChanged(true)
        assertEquals(listOf("muted=true"), actions.toList())
        assertTrue(InAppCallRegistry.state.value.muted)
        // Same mute state again: no toggle back
        connection.onMuteStateChanged(true)
        assertEquals(listOf("muted=true"), actions.toList())

        connection.onCallActive()
        assertEquals(Connection.STATE_ACTIVE, connection.state)

        val disconnecting = InAppCallConnection()
        disconnecting.onDisconnect()
        assertEquals("hangup", actions.last())
        assertEquals(Connection.STATE_DISCONNECTED, disconnecting.state)

        val ending = InAppCallConnection()
        ending.onCallEnded()
        assertEquals(Connection.STATE_DISCONNECTED, ending.state)
    }

    @Test
    fun inAppCallConnectionServiceCreatesMirrors() {
        InAppCallRegistry.bind(CommunicateLine.Signal, controller, CallCapabilities.AudioOnly)
        InAppCallRegistry.onCallStarting(CommunicateLine.Signal, "peer-1", "Pat Lee", false, incoming = false)
        val service = Robolectric.setupService(InAppCallConnectionService::class.java)
        val outgoing = service.onCreateOutgoingConnection(
            handle(InAppCallConnectionService::class.java),
            ConnectionRequest(handle(InAppCallConnectionService::class.java), Uri.fromParts("sip", "peer-1", null), Bundle()),
        )
        assertEquals(Connection.STATE_DIALING, outgoing.state)
        assertTrue(outgoing.connectionProperties and Connection.PROPERTY_SELF_MANAGED != 0)
        assertTrue(InAppCallRegistry.connection === outgoing)

        val incoming = service.onCreateIncomingConnection(handle(InAppCallConnectionService::class.java), null)
        assertEquals(Connection.STATE_RINGING, incoming.state)
        assertTrue(InAppCallRegistry.connection === incoming)

        // Failed mirrors must not end the underlying call
        service.onCreateOutgoingConnectionFailed(handle(InAppCallConnectionService::class.java), null)
        service.onCreateIncomingConnectionFailed(handle(InAppCallConnectionService::class.java), null)
        assertTrue(actions.isEmpty())
        service.onDestroy()
    }

    @Test
    fun telecomAccountHandlesAndRegistration() {
        val gvHandle = GoogleVoiceTelecom.handle(ctx)
        assertEquals("google_voice_line", gvHandle.id)
        GoogleVoiceTelecom.registerPhoneAccount(ctx, "+1 555 0001")
        GoogleVoiceTelecom.placeOutgoing(ctx, "+15559999")
        GoogleVoiceTelecom.addIncoming(ctx, "+15551234")
        GoogleVoiceTelecom.unregisterPhoneAccount(ctx)

        val inAppHandle = InAppCallTelecom.handle(ctx)
        assertEquals("communicate_inapp_calls", inAppHandle.id)
        assertTrue(gvHandle != inAppHandle)
        InAppCallTelecom.registerPhoneAccount(ctx, "Calls")
        InAppCallTelecom.addOutgoing(ctx, "peer-1")
        InAppCallTelecom.addIncoming(ctx, "peer-1")
        InAppCallTelecom.unregisterPhoneAccount(ctx)
        assertEquals(InAppCallConnectionService::class.java.name, inAppHandle.componentName.className)
        assertEquals(GoogleVoiceConnectionService::class.java.name, gvHandle.componentName.className)
    }
}
