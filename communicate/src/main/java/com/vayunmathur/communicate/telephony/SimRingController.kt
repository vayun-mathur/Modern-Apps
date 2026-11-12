package com.vayunmathur.communicate.telephony

import android.telecom.Call

/** How a carrier call leg bears on ringing. */
enum class SimLegState {
    /** Ringing, so it should alert. */
    Ringing,

    /** Up or being set up (active, held, dialing), so a ringing leg beside it is call waiting. */
    Live,

    /** Anything else: new, screening, disconnecting, gone. */
    Other,
}

/** Classify a Telecom `Call.STATE_*`. Simulated ringing (after call-screening audio processing) rings too. */
fun simLegState(telecomState: Int): SimLegState = when (telecomState) {
    Call.STATE_RINGING, Call.STATE_SIMULATED_RINGING -> SimLegState.Ringing
    Call.STATE_ACTIVE, Call.STATE_HOLDING, Call.STATE_DIALING, Call.STATE_CONNECTING, Call.STATE_PULLING_CALL ->
        SimLegState.Live
    else -> SimLegState.Other
}

/**
 * One carrier call leg as [SimRingController] needs it.
 *
 * @property key identifies the leg (the Telecom `Call` itself in production).
 * @property silentRingingRequested a call-screening service asked Telecom to ring this call silently
 *   (`Call.EXTRA_SILENT_RINGING_REQUESTED`), which a dialer that rings must honour.
 */
data class SimLeg(
    val key: Any,
    val state: SimLegState,
    val number: String?,
    val silentRingingRequested: Boolean = false,
)

/**
 * Starts and stops the ringer for carrier calls, from the full set of legs [CommunicateInCallService]
 * holds. Feed it every leg after any change (added, removed, state change); it works out the transition.
 *
 * Follows AOSP Telecom, whose `Ringer` this replaces once the dialer declares `IN_CALL_SERVICE_RINGING`:
 * - The ringing leg alerts per [com.vayunmathur.communicate.domain.ringPlan].
 * - A ringing leg while another leg (or another line's call, via [otherLineLive]) is up is call
 *   waiting: the call-waiting tone instead of the ringtone.
 * - If the live call ends while the waiting leg still rings, the waiting leg switches to a full ring.
 * - Answer, reject, disconnect, or answering another call ends the ring.
 * - The volume/power key ([silence]) and call screening's silent-ringing request mute that leg for good.
 *
 * Pure state over an [IncomingRinger] so the transitions are unit-tested with fakes.
 */
class SimRingController(
    private val ringer: IncomingRinger,
    private val otherLineLive: () -> Boolean,
) {
    private var ringingKey: Any? = null
    private var ringingWaiting = false
    private var silencedKey: Any? = null

    fun onCallsChanged(legs: List<SimLeg>) {
        val ringing = legs.firstOrNull { it.state == SimLegState.Ringing }
        if (ringing == null) {
            silencedKey = null
            stopRinging()
            return
        }
        if (ringing.silentRingingRequested || ringing.key == silencedKey) {
            stopRinging()
            return
        }
        val waiting = legs.any { it.key != ringing.key && it.state == SimLegState.Live } || otherLineLive()
        if (ringing.key == ringingKey && waiting == ringingWaiting) return
        ringingKey = ringing.key
        ringingWaiting = waiting
        ringer.ring(RingRequest(RingLine.Sim, ringing.key, ringing.number, callWaiting = waiting))
    }

    /** The user pressed volume or power while ringing: mute it; the call keeps ringing on screen. */
    fun silence() {
        val key = ringingKey ?: return
        silencedKey = key
        stopRinging()
    }

    /** The service is going away; nothing may keep ringing after it. */
    fun release() {
        silencedKey = null
        ringingKey = null
        ringer.stop(RingLine.Sim)
    }

    private fun stopRinging() {
        if (ringingKey == null) return
        ringingKey = null
        ringingWaiting = false
        ringer.stop(RingLine.Sim)
    }
}
