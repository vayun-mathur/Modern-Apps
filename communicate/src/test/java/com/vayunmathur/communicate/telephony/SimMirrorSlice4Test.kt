package com.vayunmathur.communicate.telephony

import android.telecom.CallAudioState
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.communicate.data.CommunicateLine
import com.vayunmathur.communicate.data.call.CallCapabilities
import com.vayunmathur.communicate.data.call.InAppCallPhase
import com.vayunmathur.communicate.data.call.InAppCallRegistry
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.runner.RunWith
import org.robolectric.shadow.api.Shadow
import org.robolectric.RobolectricTestRunner

/**
 * Slice-4 coverage for the SIM mirror state transitions ([SimCallBridge]).
 *
 * Robolectric (telecom constants + `Log`). Drives the mirror with stub
 * `Call`-free inputs where the API allows (audio state, removal of unknown
 * legs, phase mapping edge) and asserts the registry transitions.
 */
@RunWith(RobolectricTestRunner::class)
class SimMirrorSlice4Test {

    private val context: android.content.Context
        get() = ApplicationProvider.getApplicationContext()

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
    fun `audio state only applies to the sim line`() {
        // Registry on another line: ignored.
        InAppCallRegistry.bind(
            CommunicateLine.Signal,
            object : com.vayunmathur.communicate.data.call.InAppCallController {
                override fun answer() = Unit
                override fun reject() = Unit
                override fun hangup() = Unit
                override fun setMuted(muted: Boolean) = Unit
                override fun setSpeaker(on: Boolean) = Unit
            },
        )
        SimCallBridge.onAudioStateChanged(CallAudioState(false, 0, 0))
        assertFalse(InAppCallRegistry.state.value.muted)
        assertFalse(InAppCallRegistry.state.value.speaker)
    }

    @Test
    fun `removing an untracked leg ends nothing`() {
        // mirroredCall is null, so any call instance is "other" and ignored.
        val call = Shadow.newInstance<android.telecom.Call>(
            android.telecom.Call::class.java,
            emptyArray(),
            emptyArray(),
        )
        SimCallBridge.onCallRemoved(call)
        assertEquals(InAppCallPhase.Idle, InAppCallRegistry.state.value.phase)
    }

    @Test
    fun `state change for an untracked leg is ignored`() {
        val call = Shadow.newInstance<android.telecom.Call>(
            android.telecom.Call::class.java,
            emptyArray(),
            emptyArray(),
        )
        SimCallBridge.onStateChanged(call, android.telecom.Call.STATE_ACTIVE)
        assertEquals(InAppCallPhase.Idle, InAppCallRegistry.state.value.phase)
    }

    @Test
    fun `unknown telecom states map to connecting`() {
        assertEquals(InAppCallPhase.Connecting, SimCallBridge.mapPhase(999))
        assertEquals(InAppCallPhase.Connecting, SimCallBridge.mapPhase(android.telecom.Call.STATE_NEW))
    }

    @Test
    fun `sim capabilities offer keypad and hold but no video`() {
        val caps: CallCapabilities = SimCallBridge.capabilities
        assertTrue(caps.dtmf)
        assertTrue(caps.hold)
        assertFalse(caps.video)
        assertFalse(caps.screenShare)
    }
}
