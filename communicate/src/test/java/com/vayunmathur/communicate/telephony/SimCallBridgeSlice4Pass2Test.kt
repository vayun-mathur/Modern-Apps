package com.vayunmathur.communicate.telephony

import android.telecom.Call
import android.telecom.CallAudioState
import com.vayunmathur.communicate.data.CommunicateLine
import com.vayunmathur.communicate.data.call.InAppCallPhase
import com.vayunmathur.communicate.data.call.InAppCallRegistry
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Slice-4 pass-2: SimCallBridge phase mapping and audio-state mirroring. */
@RunWith(RobolectricTestRunner::class)
class SimCallBridgeSlice4Pass2Test {

    @BeforeTest
    fun setUp() {
        InAppCallRegistry.onEnded("reset")
        InAppCallRegistry.clearEnded()
        InAppCallRegistry.videoController = null
        InAppCallRegistry.dtmfController = null
        InAppCallRegistry.holdController = null
        InAppCallRegistry.connection = null
    }

    @Test
    fun mapPhaseCoversEveryTelecomState() {
        assertEquals(InAppCallPhase.Incoming, SimCallBridge.mapPhase(Call.STATE_RINGING))
        assertEquals(InAppCallPhase.Outgoing, SimCallBridge.mapPhase(Call.STATE_DIALING))
        assertEquals(InAppCallPhase.Outgoing, SimCallBridge.mapPhase(Call.STATE_CONNECTING))
        assertEquals(InAppCallPhase.Outgoing, SimCallBridge.mapPhase(Call.STATE_SELECT_PHONE_ACCOUNT))
        assertEquals(InAppCallPhase.Active, SimCallBridge.mapPhase(Call.STATE_ACTIVE))
        assertEquals(InAppCallPhase.Active, SimCallBridge.mapPhase(Call.STATE_HOLDING))
        assertEquals(InAppCallPhase.Ended, SimCallBridge.mapPhase(Call.STATE_DISCONNECTED))
        assertEquals(InAppCallPhase.Ended, SimCallBridge.mapPhase(Call.STATE_DISCONNECTING))
        assertEquals(InAppCallPhase.Connecting, SimCallBridge.mapPhase(Call.STATE_NEW))
        assertEquals(InAppCallPhase.Connecting, SimCallBridge.mapPhase(999))
    }

    @Test
    fun capabilitiesOfferKeypadAndHoldOnly() {
        val caps = SimCallBridge.capabilities
        assertTrue(caps.dtmf)
        assertTrue(caps.hold)
        assertFalse(caps.video)
        assertFalse(caps.screenShare)
    }

    @Test
    fun audioStateMirrorsOnlyForSimLine() {
        // Non-SIM line: audio state is ignored
        InAppCallRegistry.bind(CommunicateLine.Signal, NoopController)
        InAppCallRegistry.onCallStarting(CommunicateLine.Signal, "p", "Pat", false, incoming = false)
        SimCallBridge.onAudioStateChanged(CallAudioState(true, CallAudioState.ROUTE_SPEAKER, CallAudioState.ROUTE_SPEAKER))
        assertFalse(InAppCallRegistry.state.value.muted)
        assertFalse(InAppCallRegistry.state.value.speaker)

        // SIM line: mute + speaker route are mirrored
        InAppCallRegistry.onEnded("reset"); InAppCallRegistry.clearEnded()
        InAppCallRegistry.bind(CommunicateLine.Sim, NoopController)
        InAppCallRegistry.onCallStarting(CommunicateLine.Sim, "+1555", "Pat", false, incoming = false)
        SimCallBridge.onAudioStateChanged(CallAudioState(true, CallAudioState.ROUTE_SPEAKER, CallAudioState.ROUTE_SPEAKER))
        assertTrue(InAppCallRegistry.state.value.muted)
        assertTrue(InAppCallRegistry.state.value.speaker)
        SimCallBridge.onAudioStateChanged(CallAudioState(false, CallAudioState.ROUTE_EARPIECE, CallAudioState.ROUTE_EARPIECE))
        assertFalse(InAppCallRegistry.state.value.muted)
        assertFalse(InAppCallRegistry.state.value.speaker)
    }

    private object NoopController : com.vayunmathur.communicate.data.call.InAppCallController {
        override fun answer() {}
        override fun reject() {}
        override fun hangup() {}
        override fun setMuted(muted: Boolean) {}
        override fun setSpeaker(on: Boolean) {}
    }
}
