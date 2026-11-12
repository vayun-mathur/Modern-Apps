package com.vayunmathur.communicate.telephony

import com.vayunmathur.communicate.data.CommunicateLine
import com.vayunmathur.communicate.data.call.InAppCallPhase
import com.vayunmathur.communicate.data.call.InAppCallRegistry
import com.vayunmathur.communicate.data.googlevoice.call.CallPhase
import com.vayunmathur.communicate.data.googlevoice.call.GoogleVoiceCallManager

/**
 * Which lines have a call up, so an incoming call on one line can tell it is call waiting behind a
 * call on another (a WhatsApp call arriving during a carrier call, and the reverse).
 *
 * "Up" means connected or being placed — not ringing: two calls ringing at once is not call waiting.
 */
object LiveCalls {
    /** Whether a carrier leg is active, held or dialing; published by [CommunicateInCallService]. */
    @Volatile
    var simCallLive: Boolean = false
        internal set

    /** Whether a call on any line other than [line] is up. */
    fun otherThan(line: RingLine): Boolean =
        (line != RingLine.Sim && simCallLive) ||
            (line != RingLine.GoogleVoice && googleVoiceLive()) ||
            (line != RingLine.InApp && inAppLive())

    private fun googleVoiceLive(): Boolean = when (GoogleVoiceCallManager.state.value.phase) {
        CallPhase.Dialing, CallPhase.Ringing, CallPhase.Active -> true
        CallPhase.Idle, CallPhase.Incoming, CallPhase.Ended -> false
    }

    /**
     * A WhatsApp or Signal call that is up. The registry also mirrors SIM and Google Voice calls, which
     * the other two checks already cover.
     */
    private fun inAppLive(): Boolean {
        val state = InAppCallRegistry.state.value
        val line = state.line ?: return false
        if (line == CommunicateLine.Sim || line == CommunicateLine.GoogleVoice) return false
        return state.phase == InAppCallPhase.Outgoing ||
            state.phase == InAppCallPhase.Connecting ||
            state.phase == InAppCallPhase.Active
    }
}
