package com.vayunmathur.communicate.data

import com.vayunmathur.communicate.data.call.CallCapabilities
import com.vayunmathur.communicate.data.call.InAppCallPhase
import com.vayunmathur.communicate.data.call.InAppCallState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Slice-4 coverage for the call-state data ([InAppCallState]).
 *
 * Pure JVM: pins the defaults, the ringing/ongoing derivations and copy
 * semantics — the shape every call screen renders from.
 */
class InAppCallStateSlice4Test {

    @Test
    fun `defaults describe no call`() {
        val state = InAppCallState()
        assertEquals(InAppCallPhase.Idle, state.phase)
        assertEquals("", state.peerId)
        assertEquals("", state.peerName)
        assertFalse(state.isVideo)
        assertFalse(state.muted)
        assertFalse(state.speaker)
        assertFalse(state.held)
        assertEquals(0L, state.connectedAtMs)
        assertFalse(state.isRinging)
        assertFalse(state.isOngoing)
    }

    @Test
    fun `ringing only while incoming`() {
        for (phase in InAppCallPhase.entries) {
            assertEquals(
                phase == InAppCallPhase.Incoming,
                InAppCallState(phase = phase).isRinging,
                phase.name,
            )
        }
    }

    @Test
    fun `ongoing while active or connecting`() {
        assertTrue(InAppCallState(phase = InAppCallPhase.Active).isOngoing)
        assertTrue(InAppCallState(phase = InAppCallPhase.Connecting).isOngoing)
        assertFalse(InAppCallState(phase = InAppCallPhase.Outgoing).isOngoing)
        assertFalse(InAppCallState(phase = InAppCallPhase.Incoming).isOngoing)
        assertFalse(InAppCallState(phase = InAppCallPhase.Ended).isOngoing)
        assertFalse(InAppCallState(phase = InAppCallPhase.Idle).isOngoing)
    }

    @Test
    fun `copy preserves untouched fields`() {
        val state = InAppCallState(
            phase = InAppCallPhase.Active,
            line = CommunicateLine.Signal,
            peerId = "aci",
            peerName = "Sam",
            isVideo = true,
            capabilities = CallCapabilities.AudioAndVideo,
            connectedAtMs = 123L,
        )
        val muted = state.copy(muted = true)
        assertTrue(muted.muted)
        assertEquals("Sam", muted.peerName)
        assertEquals(123L, muted.connectedAtMs)
        assertEquals(state.copy(), state)
    }
}
