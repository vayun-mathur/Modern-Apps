package com.vayunmathur.communicate.telephony

import android.telecom.Call
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SimRingControllerTest {
    /** Records what the controller asked of the ringer, in order. */
    private class FakeRinger : IncomingRinger {
        val events = mutableListOf<String>()
        val requests = mutableListOf<RingRequest>()

        override fun ring(request: RingRequest) {
            requests += request
            events += if (request.callWaiting) "waiting:${request.callKey}" else "ring:${request.callKey}"
        }

        override fun stop(line: RingLine) {
            events += "stop:$line"
        }
    }

    private val ringer = FakeRinger()
    private var otherLineLive = false
    private val controller = SimRingController(ringer) { otherLineLive }

    private fun ringing(key: String, number: String? = "+15551234567") = SimLeg(key, SimLegState.Ringing, number)

    private fun live(key: String) = SimLeg(key, SimLegState.Live, "+15550000000")

    private fun other(key: String) = SimLeg(key, SimLegState.Other, null)

    @Test
    fun `an incoming call rings once however often it is reported`() {
        controller.onCallsChanged(listOf(ringing("a")))
        controller.onCallsChanged(listOf(ringing("a")))
        controller.onCallsChanged(listOf(ringing("a")))

        assertEquals(listOf("ring:a"), ringer.events)
        val request = ringer.requests.single()
        assertEquals(RingLine.Sim, request.line)
        assertEquals("+15551234567", request.number)
    }

    @Test
    fun `answering stops the ring`() {
        controller.onCallsChanged(listOf(ringing("a")))
        controller.onCallsChanged(listOf(live("a")))

        assertEquals(listOf("ring:a", "stop:Sim"), ringer.events)
    }

    @Test
    fun `rejecting or a remote hangup stops the ring`() {
        controller.onCallsChanged(listOf(ringing("a")))
        controller.onCallsChanged(listOf(other("a")))
        controller.onCallsChanged(emptyList())

        assertEquals(listOf("ring:a", "stop:Sim"), ringer.events)
    }

    @Test
    fun `nothing stops when nothing was ringing`() {
        controller.onCallsChanged(listOf(live("a")))
        controller.onCallsChanged(emptyList())

        assertTrue(ringer.events.isEmpty())
    }

    @Test
    fun `an outgoing call never rings`() {
        controller.onCallsChanged(listOf(other("a")))
        controller.onCallsChanged(listOf(live("a")))

        assertTrue(ringer.events.isEmpty())
    }

    @Test
    fun `a second call during an active call gets the call-waiting tone`() {
        controller.onCallsChanged(listOf(live("a")))
        controller.onCallsChanged(listOf(live("a"), ringing("b")))

        assertEquals(listOf("waiting:b"), ringer.events)
    }

    @Test
    fun `answering the waiting call stops the tone`() {
        controller.onCallsChanged(listOf(live("a"), ringing("b")))
        controller.onCallsChanged(listOf(live("a"), live("b")))

        assertEquals(listOf("waiting:b", "stop:Sim"), ringer.events)
    }

    @Test
    fun `declining the waiting call stops the tone and leaves the active call alone`() {
        controller.onCallsChanged(listOf(live("a"), ringing("b")))
        controller.onCallsChanged(listOf(live("a")))

        assertEquals(listOf("waiting:b", "stop:Sim"), ringer.events)
    }

    @Test
    fun `the waiting call switches to a full ring when the active call ends`() {
        controller.onCallsChanged(listOf(live("a"), ringing("b")))
        controller.onCallsChanged(listOf(ringing("b")))

        assertEquals(listOf("waiting:b", "ring:b"), ringer.events)
    }

    @Test
    fun `a call on another line makes a carrier call waiting`() {
        otherLineLive = true
        controller.onCallsChanged(listOf(ringing("a")))

        assertEquals(listOf("waiting:a"), ringer.events)
    }

    @Test
    fun `silencing stops the ring and later reports of that call stay silent`() {
        controller.onCallsChanged(listOf(ringing("a")))
        controller.silence()
        controller.onCallsChanged(listOf(ringing("a")))

        assertEquals(listOf("ring:a", "stop:Sim"), ringer.events)
    }

    @Test
    fun `a new call after a silenced one rings again`() {
        controller.onCallsChanged(listOf(ringing("a")))
        controller.silence()
        controller.onCallsChanged(emptyList())
        controller.onCallsChanged(listOf(ringing("b")))

        assertEquals(listOf("ring:a", "stop:Sim", "ring:b"), ringer.events)
    }

    @Test
    fun `silence with nothing ringing does nothing`() {
        controller.silence()

        assertTrue(ringer.events.isEmpty())
    }

    @Test
    fun `call screening's silent-ringing request is honoured`() {
        controller.onCallsChanged(listOf(ringing("a").copy(silentRingingRequested = true)))

        assertTrue(ringer.events.isEmpty())
    }

    @Test
    fun `release always stops the ringer`() {
        controller.onCallsChanged(listOf(ringing("a")))
        controller.release()

        assertEquals(listOf("ring:a", "stop:Sim"), ringer.events)
    }

    @Test
    fun `telecom states map to ringing, live and other`() {
        assertEquals(SimLegState.Ringing, simLegState(Call.STATE_RINGING))
        assertEquals(SimLegState.Ringing, simLegState(Call.STATE_SIMULATED_RINGING))
        listOf(
            Call.STATE_ACTIVE,
            Call.STATE_HOLDING,
            Call.STATE_DIALING,
            Call.STATE_CONNECTING,
            Call.STATE_PULLING_CALL,
        ).forEach { assertEquals(SimLegState.Live, simLegState(it)) }
        listOf(
            Call.STATE_NEW,
            Call.STATE_AUDIO_PROCESSING,
            Call.STATE_DISCONNECTING,
            Call.STATE_DISCONNECTED,
            Call.STATE_SELECT_PHONE_ACCOUNT,
        ).forEach { assertEquals(SimLegState.Other, simLegState(it)) }
    }
}
