package com.vayunmathur.communicate.data.call

import com.vayunmathur.communicate.data.CommunicateLine
import com.vayunmathur.library.log.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * What the line behind the active call can actually do.
 *
 * The call screen is shared across lines, so it renders from this rather than branching on the line:
 * Google Voice has a DTMF keypad but no video, WhatsApp and Signal have video but no keypad. A control the
 * line cannot honour is simply not shown, instead of being present and doing nothing.
 */
data class CallCapabilities(
    val mute: Boolean = true,
    val speaker: Boolean = true,
    val video: Boolean = false,
    val dtmf: Boolean = false,
    val screenShare: Boolean = false,
    val hold: Boolean = false,
) {
    /** Prebuilt capability sets for audio-only and audio+video calls. */
    companion object {
        val AudioOnly = CallCapabilities()
        val AudioAndVideo = CallCapabilities(video = true, screenShare = true)
        val AudioAndKeypad = CallCapabilities(dtmf = true)
    }
}

/** Phases an in-app VoIP call moves through, shared by every line that isn't handled by the SIM. */
enum class InAppCallPhase { Idle, Outgoing, Incoming, Connecting, Active, Ended }

/** True while a call is live (ringing, connecting, or connected). */
fun InAppCallPhase.isLiveCall(): Boolean = this == InAppCallPhase.Incoming ||
    this == InAppCallPhase.Outgoing ||
    this == InAppCallPhase.Connecting ||
    this == InAppCallPhase.Active

/**
 * The single active in-app call, in the shape the UI and the Telecom bridge need.
 *
 * Deliberately richer than what the underlying stacks expose: RingRTC reports only
 * ringing/connecting/connected and drops the call id on most callbacks, so mute, speaker, the peer's
 * name and the connect time have to be tracked here or the UI has nothing to show.
 */
data class InAppCallState(
    val phase: InAppCallPhase = InAppCallPhase.Idle,
    val line: CommunicateLine? = null,
    /** Line-specific id (ACI, JID) used to address call actions. */
    val peerId: String = "",
    val peerName: String = "",
    val isVideo: Boolean = false,
    val capabilities: CallCapabilities = CallCapabilities.AudioOnly,
    /** Whether the peer is currently sending video, which is independent of whether we are. */
    val remoteVideoEnabled: Boolean = false,
    /** Whether our own camera is on. */
    val localVideoEnabled: Boolean = false,
    /** Whether we are sending the screen rather than the camera. */
    val screenSharing: Boolean = false,
    /** Whether the peer is sharing their screen. */
    val remoteScreenSharing: Boolean = false,
    val muted: Boolean = false,
    val speaker: Boolean = false,
    /** Whether the carrier call is held (GSM hold / Telecom STATE_HOLDING). Only the SIM line sets this. */
    val held: Boolean = false,
    /** When the call became [InAppCallPhase.Active], for the duration readout. 0 until then. */
    val connectedAtMs: Long = 0L,
    val endReason: String? = null,
) {
    val isRinging: Boolean get() = phase == InAppCallPhase.Incoming
    val isOngoing: Boolean get() = phase == InAppCallPhase.Active || phase == InAppCallPhase.Connecting
}

/**
 * What the call UI and the system call surface can do to a call, implemented per line.
 *
 * Kept minimal on purpose: anything a line cannot do is a no-op rather than an error, so the shared
 * screen does not need to know which line it is driving.
 */
interface InAppCallController {
    fun answer()
    fun reject()
    fun hangup()
    fun setMuted(muted: Boolean)
    fun setSpeaker(on: Boolean)
}

/** Implemented by the Telecom `Connection` so a line's manager can drive the system call surface. */
interface InAppCallConnectionBridge {
    fun onCallActive()

    fun onCallEnded()
}

/**
 * Tone dialling, for lines that carry a call to the PSTN. Separate from [InAppCallController] because only
 * Google Voice can act on it.
 */
interface InAppCallDtmfController {
    fun sendDtmf(digit: String)
}

/**
 * Call hold, separate from [InAppCallController] because only the carrier line can act on it.
 */
interface InAppCallHoldController {
    fun setHeld(held: Boolean)
}

/**
 * Video capability, separate from [InAppCallController] because not every line has it and the shared screen
 * should only offer the control when something can act on it.
 */
interface InAppCallVideoController {
    fun setVideoEnabled(enabled: Boolean)

    fun flipCamera()

    /** EGL context the renderers must share with the decoder, or null if video is unavailable. */
    fun eglContext(): org.webrtc.EglBase.Context?

    /**
     * Attached separately rather than as a pair: the two renderers are created and disposed independently by
     * the UI, and a combined setter meant whichever recomposed last cleared the other's sink — which showed up
     * as remote video freezing when the self-view appeared.
     */
    fun attachLocalRenderer(sink: org.webrtc.VideoSink?)

    fun attachRemoteRenderer(sink: org.webrtc.VideoSink?)

    /** Share the screen instead of the camera. */
    fun setScreenShareEnabled(enabled: Boolean, permission: android.content.Intent?)
}

/**
 * The one place that knows whether a call is up, regardless of line.
 *
 * A single registry rather than one per line because only one in-app call can be active at a time, and
 * because the call screen, the notification and the Telecom connection all need the same view of it.
 * Google Voice deliberately stays outside this: it already has its own Telecom integration.
 */
object InAppCallRegistry {
    private const val TAG = "InAppCallRegistry"

    private val _state = MutableStateFlow(InAppCallState())
    val state: StateFlow<InAppCallState> = _state.asStateFlow()

    @Volatile
    private var controller: InAppCallController? = null

    /** Set only by lines that can carry video, so the UI can offer the control. */
    @Volatile
    var videoController: InAppCallVideoController? = null

    /** Set only by lines that can send tones. */
    @Volatile
    var dtmfController: InAppCallDtmfController? = null

    /** Set only by lines that can hold (the carrier line). */
    @Volatile
    var holdController: InAppCallHoldController? = null

    /** Set by the Telecom ConnectionService so state changes can be reflected into the system UI. */
    @Volatile
    var connection: InAppCallConnectionBridge? = null

    fun bind(
        line: CommunicateLine,
        controller: InAppCallController,
        capabilities: CallCapabilities = CallCapabilities.AudioOnly,
    ) {
        this.controller = controller
        _state.value = _state.value.copy(line = line, capabilities = capabilities)
    }

    /** Called by a line when its call starts, so the UI can appear before any media is up. */
    fun onCallStarting(
        line: CommunicateLine,
        peerId: String,
        peerName: String,
        isVideo: Boolean,
        incoming: Boolean,
        capabilities: CallCapabilities = _state.value.capabilities,
    ) {
        Log.status(TAG, "call starting: line=$line incoming=$incoming video=$isVideo peer=$peerId")
        _state.value = InAppCallState(
            phase = if (incoming) InAppCallPhase.Incoming else InAppCallPhase.Outgoing,
            line = line,
            peerId = peerId,
            peerName = peerName.ifBlank { peerId },
            isVideo = isVideo,
            // A call placed as video starts with the camera and speaker on; RingRTC is told the same via
            // proceed()'s enableCamera, so the UI must not claim otherwise.
            localVideoEnabled = isVideo && !incoming,
            speaker = isVideo && !incoming,
            capabilities = capabilities,
        )
    }

    fun onPhase(phase: InAppCallPhase) {
        val current = _state.value
        if (current.phase == InAppCallPhase.Idle && phase != InAppCallPhase.Incoming) {
            Log.status(TAG, "ignoring phase $phase while idle")
            return
        }
        if (current.phase != phase) Log.status(TAG, "phase ${current.phase} -> $phase")
        _state.value = current.copy(
            phase = phase,
            // Stamped once, on the transition into Active, so the duration does not restart.
            connectedAtMs = if (phase == InAppCallPhase.Active && current.connectedAtMs == 0L) {
                System.currentTimeMillis()
            } else {
                current.connectedAtMs
            },
        )
        if (phase == InAppCallPhase.Active) connection?.onCallActive()
    }

    fun onEnded(reason: String?) {
        val current = _state.value
        if (current.phase == InAppCallPhase.Idle) return
        _state.value = current.copy(phase = InAppCallPhase.Ended, endReason = reason)
        connection?.onCallEnded()
    }

    fun onMuted(muted: Boolean) {
        _state.value = _state.value.copy(muted = muted)
    }

    fun onSpeaker(on: Boolean) {
        _state.value = _state.value.copy(speaker = on)
    }

    fun onHeld(held: Boolean) {
        if (_state.value.phase == InAppCallPhase.Idle) return
        _state.value = _state.value.copy(held = held)
    }

    fun onRemoteVideo(enabled: Boolean) {
        if (_state.value.phase == InAppCallPhase.Idle) return
        Log.status(TAG, "remote video enabled=$enabled")
        _state.value = _state.value.copy(remoteVideoEnabled = enabled)
    }

    fun onRemoteScreenShare(enabled: Boolean) {
        if (_state.value.phase == InAppCallPhase.Idle) return
        Log.status(TAG, "remote screen share enabled=$enabled")
        _state.value = _state.value.copy(remoteScreenSharing = enabled)
    }

    /**
     * Start or stop sharing the screen. [permission] is the result of the MediaProjection consent dialog and
     * is required to start; stopping needs nothing.
     */
    fun setScreenShare(enabled: Boolean, permission: android.content.Intent?) {
        if (enabled && permission == null) return
        videoController?.setScreenShareEnabled(enabled, permission)
        _state.value = _state.value.copy(
            screenSharing = enabled,
            // Sharing the screen is sending video, so the peer-facing state has to agree.
            localVideoEnabled = enabled || _state.value.localVideoEnabled,
        )
    }

    fun onLocalVideo(enabled: Boolean) {
        if (_state.value.phase == InAppCallPhase.Idle) return
        _state.value = _state.value.copy(localVideoEnabled = enabled, isVideo = _state.value.isVideo || enabled)
    }

    /** Turn our own camera on or off mid-call. */
    fun toggleVideo() {
        val next = !_state.value.localVideoEnabled
        videoController?.setVideoEnabled(next)
        onLocalVideo(next)
        // Sending video implies hands-free: nobody holds a phone to their ear to be seen. The speaker
        // control is hidden while video is on, so the route is forced here rather than left stale.
        if (next && !_state.value.speaker) {
            controller?.setSpeaker(true)
            onSpeaker(true)
        }
    }

    fun flipCamera() {
        videoController?.flipCamera()
    }

    fun sendDtmf(digit: String) {
        dtmfController?.sendDtmf(digit)
    }

    /** Called by the UI once a terminal state has been shown, returning to Idle. */
    fun clearEnded() {
        if (_state.value.phase == InAppCallPhase.Ended) {
            _state.value = InAppCallState()
            controller = null
            videoController = null
            dtmfController = null
            holdController = null
            connection = null
        }
    }

    // -- Actions, forwarded to whichever line owns the call --

    /** [source] is logged so it is clear what ended a call; several paths can. */
    fun answer(source: String = "ui") {
        Log.status(TAG, "answer requested by $source")
        controller?.answer()
    }

    fun reject(source: String = "ui") {
        Log.status(TAG, "reject requested by $source")
        controller?.reject()
    }

    fun hangup(source: String = "ui") {
        Log.status(TAG, "hangup requested by $source")
        controller?.hangup()
    }

    fun toggleMuted() {
        val next = !_state.value.muted
        controller?.setMuted(next)
        onMuted(next)
    }

    fun toggleSpeaker() {
        val next = !_state.value.speaker
        controller?.setSpeaker(next)
        onSpeaker(next)
    }

    fun toggleHeld() {
        val next = !_state.value.held
        holdController?.setHeld(next)
        onHeld(next)
    }
}
