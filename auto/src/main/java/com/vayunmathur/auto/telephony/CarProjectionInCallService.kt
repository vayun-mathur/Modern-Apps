package com.vayunmathur.auto.telephony

import android.content.Intent
import android.os.IBinder
import android.telecom.Call
import android.telecom.InCallService
import android.telecom.VideoProfile
import com.vayunmathur.library.log.Log
import com.vayunmathur.auto.platform.ActiveCallInfo
import com.vayunmathur.auto.platform.AutoSessionState
import com.vayunmathur.auto.platform.CallCardPush

/**
 * The car-projection call path: an [InCallService] the platform binds while
 * the device is in car mode, mirroring gearhead's
 * `CarProjectionInCallServiceImpl` (CAR_MODE_UI) half of the NonCar /
 * CarProjection split.
 *
 * Call snapshots mirror into [AutoSessionState.activeCall] (the single
 * source the projected call card reads): added/changed replace the snapshot
 * wholesale, removed clears it. Audio routing stays with the platform --
 * this never touches the ch4/ch5 sinks or any GAL channel directly. The head
 * unit renders its own call UI from the control-24 availability verdict plus
 * these callbacks; without the projection role and the telephony grants
 * (CALL_PHONE, READ_PHONE_STATE, CONTROL_INCALL_EXPERIENCE) the platform
 * never binds this and calls stay on the phone.
 *
 * The bound instance publishes itself as [current] so the session can wire
 * the card's answer/end/hold/mute actions; cleared on unbind.
 */
class CarProjectionInCallService : InCallService() {

    private val callback = object : Call.Callback() {
        override fun onStateChanged(call: Call, state: Int) {
            Log.status(TAG, "car-projection call state: $state")
            pushSnapshot(call, state)
        }

        override fun onDetailsChanged(call: Call, details: Call.Details?) {
            pushSnapshot(call, call.state)
        }
    }

    override fun onCallAdded(call: Call) {
        // Projected calls only: a call added while the session is not
        // projecting stays on the phone UI (the NonCar half owns it there).
        // Registering the callback is the whole claim; unregistering on
        // remove is the whole release.
        call.registerCallback(callback)
        val info = snapshotOf(call, call.state)
        lastAcceptedMs = if (info.acceptedMs > 0L) info.acceptedMs else 0L
        AutoSessionState.onCallAdded(info)
        CallCardPush.push(info)
        Log.status(TAG, "car-projection call added")
    }

    override fun onCallRemoved(call: Call) {
        runCatching { call.unregisterCallback(callback) }
        lastAcceptedMs = 0L
        AutoSessionState.onCallRemoved()
        CallCardPush.push(null)
        Log.status(TAG, "car-projection call removed")
    }

    override fun onBind(intent: Intent?): IBinder? {
        // The platform binds this only in car mode with the InCall grant;
        // anything else is a misdirected bind and must be safely rejected.
        Log.status(TAG, "car-projection InCall bound")
        current = this
        return super.onBind(intent)
    }

    override fun onUnbind(intent: Intent?): Boolean {
        if (current === this) current = null
        return super.onUnbind(intent)
    }

    private fun pushSnapshot(call: Call, state: Int) {
        val info = snapshotOf(call, state)
        if (info.acceptedMs > 0L) lastAcceptedMs = info.acceptedMs
        if (state == Call.STATE_DISCONNECTED) lastAcceptedMs = 0L
        AutoSessionState.onCallChanged(info)
        CallCardPush.push(info)
    }

    override fun onCallAudioStateChanged(state: android.telecom.CallAudioState) {
        lastMuted = state.isMuted
    }

    private fun snapshotOf(call: Call, state: Int): ActiveCallInfo {
        val details = runCatching { call.details }.getOrNull()
        // The handle's scheme part is the dialable number; withheld handles
        // stay null (never faked) and the card shows the unknown string.
        val number = runCatching { details?.handle?.schemeSpecificPart }
            .getOrNull()?.takeIf { it.isNotBlank() }
        val held = state == Call.STATE_HOLDING ||
            runCatching { details?.let { it.callCapabilities and Call.Details.CAPABILITY_HOLD } != 0 }
                .getOrDefault(false) && state != Call.STATE_ACTIVE
        return ActiveCallInfo(
            telecomState = state,
            number = number,
            acceptedMs = if (state == Call.STATE_RINGING) 0L else lastAcceptedMs(state),
            held = held,
            muted = lastMuted,
        )
    }

    /** Latest mute state from `onCallAudioStateChanged`; false until first report. */
    @Volatile private var lastMuted = false

    private var lastAcceptedMs = 0L

    private fun lastAcceptedMs(state: Int): Long {
        // First non-ringing sighting starts the duration ticker; later
        // states keep it (hold preserves, disconnect clears via remove).
        if (lastAcceptedMs == 0L && state != Call.STATE_RINGING) {
            lastAcceptedMs = System.currentTimeMillis()
        }
        if (state == Call.STATE_DISCONNECTED) lastAcceptedMs = 0L
        return lastAcceptedMs
    }

    /** Shared state and constants for [CarProjectionInCallService]. */
    companion object {
        const val TAG = "MaAuto.InCallCar"

        /** Latest bound instance, or null before bind / after unbind. */
        @Volatile
        var current: CarProjectionInCallService? = null
            private set

        /** Ends the foreground call, no-op with nothing bound. */
        fun endCall() {
            val service = current ?: return
            runCatching {
                service.calls.firstOrNull()?.disconnect()
            }.onFailure { Log.status(TAG, "end call failed", it) }
        }

        /** Answers a ringing call, no-op with nothing bound. */
        fun answerCall() {
            val service = current ?: return
            runCatching {
                service.calls.firstOrNull { it.state == Call.STATE_RINGING }
                    ?.answer(VideoProfile.STATE_AUDIO_ONLY)
            }.onFailure { Log.status(TAG, "answer call failed", it) }
        }

        /** Holds or resumes the foreground call, no-op with nothing bound. */
        fun toggleHold() {
            val service = current ?: return
            runCatching {
                val call = service.calls.firstOrNull() ?: return
                if (call.state == Call.STATE_HOLDING) call.unhold() else call.hold()
            }.onFailure { Log.status(TAG, "hold toggle failed", it) }
        }

        /** Flips the InCall mute, no-op with nothing bound. */
        fun toggleMute() {
            val service = current ?: return
            runCatching { service.setMuted(!(service.callAudioState?.isMuted ?: false)) }
                .onFailure { Log.status(TAG, "mute toggle failed", it) }
        }
    }
}
