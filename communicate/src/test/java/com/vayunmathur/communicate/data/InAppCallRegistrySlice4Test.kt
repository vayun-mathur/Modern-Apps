package com.vayunmathur.communicate.data

import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.communicate.data.call.CallCapabilities
import com.vayunmathur.communicate.data.call.InAppCallController
import com.vayunmathur.communicate.data.call.InAppCallDtmfController
import com.vayunmathur.communicate.data.call.InAppCallHoldController
import com.vayunmathur.communicate.data.call.InAppCallPhase
import com.vayunmathur.communicate.data.call.InAppCallRegistry
import com.vayunmathur.communicate.data.call.isLiveCall
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Slice-4 coverage for the shared in-app call state machine ([InAppCallRegistry]).
 *
 * Robolectric (the registry logs through `android.util.Log`). Asserts the phase
 * lifecycle, capability presets, controller fan-out and the video/screenshare
 * guards — the contract every calling line depends on.
 */
@RunWith(RobolectricTestRunner::class)
class InAppCallRegistrySlice4Test {

    private val actions = mutableListOf<String>()

    private val controller = object : InAppCallController {
        override fun answer() { actions.add("answer") }
        override fun reject() { actions.add("reject") }
        override fun hangup() { actions.add("hangup") }
        override fun setMuted(muted: Boolean) { actions.add("muted=$muted") }
        override fun setSpeaker(on: Boolean) { actions.add("speaker=$on") }
    }

    private val dtmf = object : InAppCallDtmfController {
        override fun sendDtmf(digit: String) { actions.add("dtmf=$digit") }
    }

    private val hold = object : InAppCallHoldController {
        override fun setHeld(held: Boolean) { actions.add("held=$held") }
    }

    @BeforeTest
    fun setUp() {
        ApplicationProvider.getApplicationContext<android.content.Context>()
        actions.clear()
        // Reset the singleton: end any leaked call, then clear the terminal state.
        InAppCallRegistry.onEnded("reset")
        InAppCallRegistry.clearEnded()
        InAppCallRegistry.videoController = null
        InAppCallRegistry.dtmfController = null
        InAppCallRegistry.holdController = null
        InAppCallRegistry.connection = null
    }

    private fun startOutgoing(
        caps: CallCapabilities = CallCapabilities.AudioOnly,
        line: CommunicateLine = CommunicateLine.Sim,
    ) {
        InAppCallRegistry.bind(line, controller, caps)
        InAppCallRegistry.dtmfController = dtmf
        InAppCallRegistry.holdController = hold
        InAppCallRegistry.onCallStarting(
            line = line,
            peerId = "+1555",
            peerName = "Pat",
            isVideo = false,
            incoming = false,
            capabilities = caps,
        )
    }

    // -- presets + predicates ---------------------------------------------------

    @Test
    fun `capability presets expose the right controls`() {
        assertFalse(CallCapabilities.AudioOnly.video)
        assertFalse(CallCapabilities.AudioOnly.dtmf)
        assertTrue(CallCapabilities.AudioOnly.mute)
        assertTrue(CallCapabilities.AudioOnly.speaker)

        assertTrue(CallCapabilities.AudioAndVideo.video)
        assertTrue(CallCapabilities.AudioAndVideo.screenShare)
        assertFalse(CallCapabilities.AudioAndVideo.dtmf)

        assertTrue(CallCapabilities.AudioAndKeypad.dtmf)
        assertFalse(CallCapabilities.AudioAndKeypad.video)
    }

    @Test
    fun `isLiveCall covers ringing connecting and active only`() {
        assertTrue(InAppCallPhase.Incoming.isLiveCall())
        assertTrue(InAppCallPhase.Outgoing.isLiveCall())
        assertTrue(InAppCallPhase.Connecting.isLiveCall())
        assertTrue(InAppCallPhase.Active.isLiveCall())
        assertFalse(InAppCallPhase.Idle.isLiveCall())
        assertFalse(InAppCallPhase.Ended.isLiveCall())
    }

    @Test
    fun `fresh state is idle with no peer`() {
        val state = InAppCallRegistry.state.value
        assertEquals(InAppCallPhase.Idle, state.phase)
        assertNull(state.line)
        assertEquals("", state.peerId)
        assertFalse(state.isRinging)
        assertFalse(state.isOngoing)
    }

    // -- call lifecycle -----------------------------------------------------------

    @Test
    fun `outgoing start seeds peer and video flags`() {
        startOutgoing()
        val state = InAppCallRegistry.state.value
        assertEquals(InAppCallPhase.Outgoing, state.phase)
        assertEquals(CommunicateLine.Sim, state.line)
        assertEquals("+1555", state.peerId)
        assertEquals("Pat", state.peerName)
        assertFalse(state.isVideo)
        assertFalse(state.localVideoEnabled)
        assertFalse(state.speaker)
    }

    @Test
    fun `outgoing video call starts with camera and speaker on`() {
        InAppCallRegistry.bind(CommunicateLine.Signal, controller, CallCapabilities.AudioAndVideo)
        InAppCallRegistry.onCallStarting(
            line = CommunicateLine.Signal,
            peerId = "aci1",
            peerName = "",
            isVideo = true,
            incoming = false,
            capabilities = CallCapabilities.AudioAndVideo,
        )
        val state = InAppCallRegistry.state.value
        assertEquals(InAppCallPhase.Outgoing, state.phase)
        assertEquals("aci1", state.peerName) // blank name falls back to the id
        assertTrue(state.localVideoEnabled)
        assertTrue(state.speaker)
    }

    @Test
    fun `incoming start does not enable camera`() {
        InAppCallRegistry.onCallStarting(
            line = CommunicateLine.Signal,
            peerId = "aci1",
            peerName = "Sam",
            isVideo = true,
            incoming = true,
            capabilities = CallCapabilities.AudioAndVideo,
        )
        val state = InAppCallRegistry.state.value
        assertEquals(InAppCallPhase.Incoming, state.phase)
        assertTrue(state.isRinging)
        assertFalse(state.localVideoEnabled)
        assertFalse(state.speaker)
    }

    @Test
    fun `phase transitions stamp connectedAt once`() {
        startOutgoing()
        InAppCallRegistry.onPhase(InAppCallPhase.Connecting)
        assertTrue(InAppCallRegistry.state.value.isOngoing)
        InAppCallRegistry.onPhase(InAppCallPhase.Active)
        val first = InAppCallRegistry.state.value.connectedAtMs
        assertTrue(first > 0)
        InAppCallRegistry.onPhase(InAppCallPhase.Active)
        assertEquals(first, InAppCallRegistry.state.value.connectedAtMs)
    }

    @Test
    fun `phase while idle is ignored except incoming`() {
        InAppCallRegistry.onPhase(InAppCallPhase.Active)
        assertEquals(InAppCallPhase.Idle, InAppCallRegistry.state.value.phase)
        InAppCallRegistry.onPhase(InAppCallPhase.Incoming)
        assertEquals(InAppCallPhase.Incoming, InAppCallRegistry.state.value.phase)
    }

    @Test
    fun `end then clear resets to a fresh idle`() {
        startOutgoing()
        InAppCallRegistry.onPhase(InAppCallPhase.Active)
        InAppCallRegistry.onEnded("busy")
        assertEquals(InAppCallPhase.Ended, InAppCallRegistry.state.value.phase)
        assertEquals("busy", InAppCallRegistry.state.value.endReason)
        InAppCallRegistry.clearEnded()
        val state = InAppCallRegistry.state.value
        assertEquals(InAppCallPhase.Idle, state.phase)
        assertNull(state.line)
        assertNull(state.endReason)
    }

    @Test
    fun `end while idle is a no-op and clear only acts on ended`() {
        InAppCallRegistry.onEnded("nothing")
        assertEquals(InAppCallPhase.Idle, InAppCallRegistry.state.value.phase)
        startOutgoing()
        InAppCallRegistry.clearEnded()
        assertEquals(InAppCallPhase.Outgoing, InAppCallRegistry.state.value.phase)
    }

    // -- toggles + controller fan-out ----------------------------------------------

    @Test
    fun `mute and speaker toggle through the controller`() {
        startOutgoing()
        InAppCallRegistry.toggleMuted()
        assertTrue(InAppCallRegistry.state.value.muted)
        InAppCallRegistry.toggleSpeaker()
        assertTrue(InAppCallRegistry.state.value.speaker)
        assertEquals(listOf("muted=true", "speaker=true"), actions)
        InAppCallRegistry.toggleMuted()
        assertFalse(InAppCallRegistry.state.value.muted)
    }

    @Test
    fun `answer reject hangup forward with source`() {
        startOutgoing()
        InAppCallRegistry.answer("test")
        InAppCallRegistry.reject("test")
        InAppCallRegistry.hangup("test")
        assertEquals(listOf("answer", "reject", "hangup"), actions)
    }

    @Test
    fun `hold toggles only off idle`() {
        // No call: held stays false and the controller is not poked.
        InAppCallRegistry.toggleHeld()
        assertFalse(InAppCallRegistry.state.value.held)
        assertTrue(actions.isEmpty())

        startOutgoing()
        InAppCallRegistry.toggleHeld()
        assertTrue(InAppCallRegistry.state.value.held)
        InAppCallRegistry.onHeld(false)
        assertFalse(InAppCallRegistry.state.value.held)
        assertEquals(listOf("held=true"), actions)
    }

    @Test
    fun `dtmf forwards to the dtmf controller`() {
        startOutgoing()
        InAppCallRegistry.sendDtmf("5")
        assertEquals(listOf("dtmf=5"), actions)
    }

    @Test
    fun `remote video and screenshare flags track the peer`() {
        startOutgoing()
        InAppCallRegistry.onRemoteVideo(true)
        assertTrue(InAppCallRegistry.state.value.remoteVideoEnabled)
        InAppCallRegistry.onRemoteScreenShare(true)
        assertTrue(InAppCallRegistry.state.value.remoteScreenSharing)
        // Idle guards: with no call these are ignored.
        InAppCallRegistry.onEnded(null)
        InAppCallRegistry.clearEnded()
        InAppCallRegistry.onRemoteVideo(true)
        assertFalse(InAppCallRegistry.state.value.remoteVideoEnabled)
    }

    @Test
    fun `local video implies video and forces speaker`() {
        startOutgoing()
        InAppCallRegistry.onLocalVideo(true)
        val state = InAppCallRegistry.state.value
        assertTrue(state.localVideoEnabled)
        assertTrue(state.isVideo)
    }

    @Test
    fun `enabling video routes hands-free through the line controller`() {
        startOutgoing()
        assertFalse(InAppCallRegistry.state.value.speaker)
        // Video on routes hands-free through the line controller.
        InAppCallRegistry.toggleVideo()
        assertTrue(InAppCallRegistry.state.value.localVideoEnabled)
        assertTrue(InAppCallRegistry.state.value.speaker)
        assertEquals(listOf("speaker=true"), actions.filter { it.startsWith("speaker") })
    }

    @Test
    fun `screenshare without permission is refused`() {
        startOutgoing()
        InAppCallRegistry.setScreenShare(true, null)
        assertFalse(InAppCallRegistry.state.value.screenSharing)
    }

    @Test
    fun `bind updates the line and the given capabilities`() {
        startOutgoing(caps = CallCapabilities.AudioAndKeypad)
        InAppCallRegistry.bind(CommunicateLine.GoogleVoice, controller, CallCapabilities.AudioAndVideo)
        assertTrue(InAppCallRegistry.state.value.capabilities.video)
        assertEquals(CommunicateLine.GoogleVoice, InAppCallRegistry.state.value.line)
        // The default resets to audio-only.
        InAppCallRegistry.bind(CommunicateLine.Signal, controller)
        assertEquals(CallCapabilities.AudioOnly, InAppCallRegistry.state.value.capabilities)
    }
}
