package com.vayunmathur.communicate.telephony

import android.telecom.Connection
import android.telecom.DisconnectCause
import com.vayunmathur.communicate.data.googlevoice.call.CallConnection
import com.vayunmathur.communicate.data.googlevoice.call.GoogleVoiceCallManager

/**
 * The Telecom [Connection] representing one Google Voice call. Bridges system call actions
 * (answer/reject/hangup/DTMF) to [GoogleVoiceCallManager], and reflects SIP/WebRTC state back
 * into the system via [CallConnection].
 */
class GoogleVoiceConnection : Connection(), CallConnection {

    init {
        audioModeIsVoip = true
        connectionCapabilities = connectionCapabilities or
            CAPABILITY_MUTE or CAPABILITY_SUPPORT_HOLD
    }

    override fun onAnswer() {
        GoogleVoiceCallManager.answer()
    }

    override fun onReject() {
        GoogleVoiceCallManager.reject()
        setDisconnected(DisconnectCause(DisconnectCause.REJECTED))
        destroy()
    }

    override fun onDisconnect() {
        GoogleVoiceCallManager.hangup()
        setDisconnected(DisconnectCause(DisconnectCause.LOCAL))
        destroy()
    }

    override fun onAbort() {
        GoogleVoiceCallManager.hangup()
        setDisconnected(DisconnectCause(DisconnectCause.CANCELED))
        destroy()
    }

    /** Volume or power pressed while ringing: mute the ring, keep the call ringing on screen. */
    override fun onSilence() {
        CallRinger.silence(RingLine.GoogleVoice)
    }

    override fun onPlayDtmfTone(c: Char) {
        GoogleVoiceCallManager.sendDtmf(c.toString())
    }

    override fun onStateChanged(state: Int) {
        super.onStateChanged(state)
    }

    // ---- CallConnection (driven by GoogleVoiceCallManager) ----

    override fun onCallActive() {
        setActive()
    }

    override fun onCallEnded() {
        setDisconnected(DisconnectCause(DisconnectCause.REMOTE))
        destroy()
    }
}
