package com.vayunmathur.messages.gvoice.voice

import android.net.Uri
import android.telecom.CallAudioState
import android.telecom.Connection
import android.telecom.DisconnectCause
import android.telecom.TelecomManager
import android.util.Log
import com.vayunmathur.messages.gvoice.sip.SipManager

/**
 * Telecom Connection implementation for Google Voice calls.
 * Bridges Android Telecom framework callbacks to SipManager SIP operations.
 */
class GVoiceConnection(private val phoneNumber: String) : Connection() {

    companion object {
        private const val TAG = "GVoiceConnection"
    }

    init {
        // Set initial connection properties
        connectionCapabilities = CAPABILITY_HOLD or CAPABILITY_SUPPORT_HOLD or CAPABILITY_MUTE
        setCallerDisplayName(phoneNumber, TelecomManager.PRESENTATION_ALLOWED)
        val uri = Uri.fromParts("tel", phoneNumber, null)
        setAddress(uri, TelecomManager.PRESENTATION_ALLOWED)
    }

    override fun onAnswer() {
        Log.i(TAG, "onAnswer() called")
        // For outgoing calls, answer is a no-op (already dialing)
        // For incoming calls, we would answer the SIP call here
        if (SipManager.answerCall()) {
            setActive()
        } else {
            setDisconnected(DisconnectCause(DisconnectCause.ERROR))
            destroy()
        }
    }

    override fun onReject() {
        Log.i(TAG, "onReject() called")
        SipManager.endCall()
        setDisconnected(DisconnectCause(DisconnectCause.REJECTED))
        destroy()
    }

    override fun onDisconnect() {
        Log.i(TAG, "onDisconnect() called")
        SipManager.endCall()
        setDisconnected(DisconnectCause(DisconnectCause.LOCAL))
        destroy()
    }

    override fun onHold() {
        Log.i(TAG, "onHold() called")
        if (SipManager.holdCall()) {
            setOnHold()
        }
    }

    override fun onUnhold() {
        Log.i(TAG, "onUnhold() called")
        if (SipManager.unholdCall()) {
            setActive()
        }
    }

    override fun onCallAudioStateChanged(state: CallAudioState) {
        Log.i(TAG, "onCallAudioStateChanged() called: route=${state.route}")
        // Route audio via SipManager or AudioManager
        SipManager.setAudioRoute(state.route)
        super.onCallAudioStateChanged(state)
    }

    /**
     * Called when outgoing call is placed via Telecom.
     * Initiates SIP call and updates connection state.
     */
    fun placeOutgoingCall() {
        Log.i(TAG, "Placing outgoing call to $phoneNumber")
        setDialing()
        if (SipManager.makeCall(phoneNumber)) {
            // SipManager will notify us via callback when call connects
            // For now, set to active optimistically
            setActive()
        } else {
            setDisconnected(DisconnectCause(DisconnectCause.ERROR, "Failed to place call"))
            destroy()
        }
    }
}
