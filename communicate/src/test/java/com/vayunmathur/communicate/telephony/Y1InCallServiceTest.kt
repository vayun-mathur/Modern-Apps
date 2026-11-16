package com.vayunmathur.communicate.telephony

import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.telecom.Call
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.communicate.data.CommunicateLine
import com.vayunmathur.communicate.data.call.InAppCallPhase
import com.vayunmathur.communicate.data.call.InAppCallRegistry
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.After
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config

/**
 * Y1 coverage for `CommunicateInCallService` and its pure projections:
 * [simLegState]/[SimRingController] (ring / call-waiting / silence state
 * machine) and [SimCallBridge.mapPhase] (Telecom → shared-phase mapping).
 *
 * The service itself is exercised through Robolectric's service controller:
 * intent actions with no current call, ringer silencing, and teardown. The
 * `Call`-carrying legs (notification CallStyle, `SimCallBridge.onCallAdded`)
 * need a live Telecom `Call`, which Robolectric cannot fabricate faithfully,
 * so those are covered at the projection layer they delegate to.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class Y1InCallServiceTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @After fun resetCallState() {
        LiveCalls.simCallLive = false
        InAppCallRegistry.videoController = null
        runCatching { InAppCallRegistry.onEnded(null) }
        runCatching { InAppCallRegistry.clearEnded() }
    }

    private class FakeRinger : IncomingRinger {
        val rings = mutableListOf<RingRequest>()
        val stops = mutableListOf<RingLine>()
        override fun ring(request: RingRequest) { rings += request }
        override fun stop(line: RingLine) { stops += line }
    }

    // ---- simLegState / SimCallBridge.mapPhase ----

    @Test fun `telecom states classify into ringing live and other legs`() {
        assertEquals(SimLegState.Ringing, simLegState(Call.STATE_RINGING))
        assertEquals(SimLegState.Ringing, simLegState(Call.STATE_SIMULATED_RINGING))
        assertEquals(SimLegState.Live, simLegState(Call.STATE_ACTIVE))
        assertEquals(SimLegState.Live, simLegState(Call.STATE_HOLDING))
        assertEquals(SimLegState.Live, simLegState(Call.STATE_DIALING))
        assertEquals(SimLegState.Live, simLegState(Call.STATE_CONNECTING))
        assertEquals(SimLegState.Live, simLegState(Call.STATE_PULLING_CALL))
        assertEquals(SimLegState.Other, simLegState(Call.STATE_DISCONNECTED))
        assertEquals(SimLegState.Other, simLegState(Call.STATE_NEW))
    }

    @Test fun `telecom states map onto the shared call phases`() {
        assertEquals(InAppCallPhase.Incoming, SimCallBridge.mapPhase(Call.STATE_RINGING))
        assertEquals(InAppCallPhase.Outgoing, SimCallBridge.mapPhase(Call.STATE_DIALING))
        assertEquals(InAppCallPhase.Outgoing, SimCallBridge.mapPhase(Call.STATE_CONNECTING))
        assertEquals(InAppCallPhase.Outgoing, SimCallBridge.mapPhase(Call.STATE_SELECT_PHONE_ACCOUNT))
        assertEquals(InAppCallPhase.Active, SimCallBridge.mapPhase(Call.STATE_ACTIVE))
        assertEquals(InAppCallPhase.Active, SimCallBridge.mapPhase(Call.STATE_HOLDING))
        assertEquals(InAppCallPhase.Ended, SimCallBridge.mapPhase(Call.STATE_DISCONNECTED))
        assertEquals(InAppCallPhase.Ended, SimCallBridge.mapPhase(Call.STATE_DISCONNECTING))
        assertEquals(InAppCallPhase.Connecting, SimCallBridge.mapPhase(Call.STATE_NEW))
    }

    @Test fun `sim bridge advertises keypad and hold capabilities`() {
        assertTrue(SimCallBridge.capabilities.dtmf)
        assertTrue(SimCallBridge.capabilities.hold)
        assertFalse(SimCallBridge.capabilities.video)
    }

    // ---- SimRingController ----

    @Test fun `a lone ringing leg rings once and stops when it goes away`() {
        val ringer = FakeRinger()
        val controller = SimRingController(ringer) { false }
        val leg = SimLeg(key = "call-1", state = SimLegState.Ringing, number = "+14155550102")
        controller.onCallsChanged(listOf(leg))
        controller.onCallsChanged(listOf(leg)) // repeat delivery is a no-op
        assertEquals(1, ringer.rings.size)
        assertEquals(RingRequest(RingLine.Sim, "call-1", "+14155550102", callWaiting = false), ringer.rings.single())
        controller.onCallsChanged(emptyList())
        assertEquals(listOf(RingLine.Sim), ringer.stops)
    }

    @Test fun `a ringing leg beside a live leg is call waiting and upgrades when the live leg ends`() {
        val ringer = FakeRinger()
        val controller = SimRingController(ringer) { false }
        val live = SimLeg(key = "call-1", state = SimLegState.Live, number = "+14155550102")
        val waiting = SimLeg(key = "call-2", state = SimLegState.Ringing, number = "+14155550103")
        controller.onCallsChanged(listOf(live, waiting))
        assertEquals(true, ringer.rings.single().callWaiting)
        // Live leg ends: the same ringing leg now rings in full.
        controller.onCallsChanged(listOf(waiting))
        assertEquals(2, ringer.rings.size)
        assertEquals(false, ringer.rings.last().callWaiting)
    }

    @Test fun `another line being live also makes the ring call waiting`() {
        val ringer = FakeRinger()
        val controller = SimRingController(ringer) { true }
        controller.onCallsChanged(listOf(SimLeg(key = "c", state = SimLegState.Ringing, number = null)))
        assertEquals(true, ringer.rings.single().callWaiting)
        assertEquals(null, ringer.rings.single().number)
    }

    @Test fun `screening-silenced and user-silenced legs never ring again`() {
        val ringer = FakeRinger()
        val controller = SimRingController(ringer) { false }
        val screened = SimLeg(
            key = "call-1", state = SimLegState.Ringing, number = "+1",
            silentRingingRequested = true,
        )
        controller.onCallsChanged(listOf(screened))
        assertTrue(ringer.rings.isEmpty())

        val leg = SimLeg(key = "call-2", state = SimLegState.Ringing, number = "+2")
        controller.onCallsChanged(listOf(leg))
        assertEquals(1, ringer.rings.size)
        controller.silence() // volume/power key
        assertEquals(listOf(RingLine.Sim), ringer.stops)
        controller.onCallsChanged(listOf(leg)) // still ringing on screen, stays muted
        assertEquals(1, ringer.rings.size)
        controller.release()
        assertEquals(2, ringer.stops.size)
    }

    @Test fun `no live line is reported until the service publishes one`() {
        assertFalse(LiveCalls.otherThan(RingLine.Sim))
        assertFalse(LiveCalls.otherThan(RingLine.InApp))
        LiveCalls.simCallLive = true
        assertTrue(LiveCalls.otherThan(RingLine.InApp))
        assertFalse(LiveCalls.otherThan(RingLine.Sim))
    }

    // ---- The service itself ----

    @Test fun `service intent actions with no current call are sticky-free no-ops`() {
        val controller = Robolectric.buildService(CommunicateInCallService::class.java).create()
        val service = controller.get()
        for (action in listOf(
            "com.vayunmathur.communicate.regularcall.ANSWER",
            "com.vayunmathur.communicate.regularcall.DECLINE",
            "com.vayunmathur.communicate.regularcall.DISCONNECT",
        )) {
            val intent = Intent(context, CommunicateInCallService::class.java).setAction(action)
            assertEquals(android.app.Service.START_NOT_STICKY, service.onStartCommand(intent, 0, 1))
        }
        assertEquals(android.app.Service.START_NOT_STICKY, service.onStartCommand(null, 0, 2))
        // No call, so no call notification was posted.
        val nm = context.getSystemService(NotificationManager::class.java)
        assertTrue(Shadows.shadowOf(nm).allNotifications.isEmpty())
        controller.destroy()
    }

    @Test fun `silencing the ringer before any call and destroying the service are safe`() {
        val controller = Robolectric.buildService(CommunicateInCallService::class.java).create()
        val service = controller.get()
        service.onSilenceRinger() // lazily builds the ring controller; nothing ringing
        controller.destroy() // releases the ringer and clears the SIM-live flag
        assertFalse(LiveCalls.simCallLive)
        assertEquals(InAppCallPhase.Idle, InAppCallRegistry.state.value.phase)
        assertEquals(CommunicateLine.entries.size, 5)
    }
}
