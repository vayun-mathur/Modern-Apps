package com.vayunmathur.communicate.data.googlevoice.call

import android.content.Context
import com.vayunmathur.communicate.data.googlevoice.GoogleVoiceClient
import com.vayunmathur.communicate.data.googlevoice.GoogleVoiceSession
import com.vayunmathur.library.log.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Coarse call lifecycle exposed to the UI + Telecom bridge. */
enum class CallPhase { Idle, Dialing, Ringing, Active, Incoming, Ended }

/** An immutable snapshot of the current Google Voice call for the UI. */
data class CallState(
    val phase: CallPhase = CallPhase.Idle,
    val remoteNumber: String = "",
    val muted: Boolean = false,
    val speaker: Boolean = false,
)

/** Implemented by the Telecom [android.telecom.Connection] so the manager can drive system UI. */
interface CallConnection {
    fun onCallActive()
    fun onCallEnded()
}

/**
 * Orchestrates a single Google Voice VoIP call: wires [SipClient] signaling to the
 * [WebRtcAudioSession] media leg and publishes a [CallState] flow. Also the entry point the
 * Telecom bridge and in-app call UI both talk to. Singleton because a call outlives any one
 * screen and the self-managed [android.telecom.Connection].
 *
 * See `voice-documentation.md` → "Voice Calling (SIP over WebSocket)". Phase 5 is expected to
 * need on-device iteration (SDP/codec/ICE + SIP auth specifics).
 */
object GoogleVoiceCallManager {

    private const val SIP_RINGING = 180
    private const val SIP_SESSION_PROGRESS = 183
    private const val REDIAL_DELAY_MS = 50 * 60 * 1000L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _state = MutableStateFlow(CallState())
    val state: StateFlow<CallState> = _state.asStateFlow()

    private var appContext: Context? = null
    private var audio: WebRtcAudioSession? = null
    private var sip: SipClient? = null
    private var gvNumber: String = ""
    private var remoteNumber: String = ""
    private var registered = false
    private var registrationJob: Job? = null
    private var remoteApplied = false
    private var pendingInviteTarget: String? = null
    private var pendingInviteSdp: String? = null

    /** Set by the Telecom bridge so SIP-driven transitions reflect into system call UI. */
    var connection: CallConnection? = null

    /** Set by the registration service to surface inbound INVITEs to Telecom. */
    var onIncomingCall: ((from: String) -> Unit)? = null

    fun init(context: Context) {
        if (appContext == null) appContext = context.applicationContext
    }

    fun placeCall(number: String) {
        val ctx = appContext ?: return
        remoteNumber = number
        _state.value = CallState(phase = CallPhase.Dialing, remoteNumber = number)
        scope.launch {
            runCatching {
                val session = GoogleVoiceSession.get(ctx)
                val info = GoogleVoiceClient.get(ctx).getSipRegisterInfo()
                gvNumber = session.phoneNumber() ?: info.phoneNumber.orEmpty()
                val a = WebRtcAudioSession(ctx).also { it.initialize() }
                audio = a
                val offer = a.createOffer()
                pendingInviteTarget = number
                pendingInviteSdp = offer
                val s = ensureSipClient(info)
                s.connect()
                if (registered) {
                    s.invite(number, offer)
                } else {
                    s.register()
                }
            }.onFailure {
                Log.error("GoogleVoiceCall", "placeCall setup failed", it)
                fail(it.message ?: "call setup failed")
            }
        }
    }

    /** Connect + register without placing a call, so inbound INVITEs can arrive. */
    fun startRegistration() {
        val ctx = appContext ?: return
        if (registrationJob?.isActive == true || registered) return
        registrationJob = scope.launch {
            runCatching {
                val session = GoogleVoiceSession.get(ctx)
                val info = GoogleVoiceClient.get(ctx).getSipRegisterInfo()
                gvNumber = session.phoneNumber() ?: info.phoneNumber.orEmpty()
                val s = ensureSipClient(info)
                s.connect()
                s.register()
            }.onFailure { reason ->
                Log.error("GoogleVoiceCall", "registration failed", reason)
                stopRegistration()
            }
        }
    }

    fun stopRegistration() {
        registrationJob?.cancel()
        registrationJob = null
        registered = false
        pendingInviteTarget = null
        pendingInviteSdp = null
        runCatching { sip?.close() }
        sip = null
        runCatching { audio?.close() }
        audio = null
        _state.value = CallState()
    }

    fun answer() {
        val ctx = appContext ?: return
        scope.launch {
            runCatching {
                val a = audio ?: WebRtcAudioSession(ctx).also { it.initialize(); audio = it }
                val offer = pendingInviteSdp ?: return@launch
                val answerSdp = a.createAnswer(offer)
                sip?.answerInbound(answerSdp)
                setActive()
            }.onFailure { fail(it.message ?: "answer failed") }
        }
    }

    fun reject() {
        scope.launch {
            runCatching { sip?.declineInbound() }
            endCall()
        }
    }

    fun hangup() {
        scope.launch {
            runCatching { sip?.bye(remoteNumber) }
            endCall()
        }
    }

    fun sendDtmf(digit: String) {
        audio?.sendDtmf(digit)
    }

    fun setMuted(muted: Boolean) {
        audio?.setMuted(muted)
        _state.value = _state.value.copy(muted = muted)
    }

    fun setSpeaker(on: Boolean) {
        audio?.setSpeaker(on)
        _state.value = _state.value.copy(speaker = on)
    }

    // ------------------------------------------------------------------

    private fun sipListener() = object : SipClient.Listener {
        override fun onRegistered() {
            registered = true
            scheduleRegistrationRefresh()
            val target = pendingInviteTarget
            val sdp = pendingInviteSdp
            if (target != null && sdp != null) {
                scope.launch { runCatching { sip?.invite(target, sdp) } }
            }
        }

        override fun onProvisional(code: Int) {
            if (code == SIP_RINGING || code == SIP_SESSION_PROGRESS) {
                _state.value = _state.value.copy(phase = CallPhase.Ringing)
            }
        }

        override fun onEarlyMedia(remoteSdp: String) {
            // Apply the answer as soon as it arrives (183) so ICE/DTLS + audio can start during
            // ringback; the final 200 just confirms + ACKs.
            scope.launch { runCatching { applyRemote(remoteSdp) } }
        }

        override fun onAnswered(remoteSdp: String) {
            scope.launch {
                runCatching {
                    applyRemote(remoteSdp)
                    sip?.ack(remoteNumber)
                    setActive()
                }.onFailure { fail(it.message ?: "media negotiation failed") }
            }
        }

        override fun onEnded() = endCall()

        override fun onFailed(reason: String) = fail(reason)

        override fun onIncomingInvite(remoteSdp: String, from: String) {
            remoteNumber = from
            pendingInviteSdp = remoteSdp
            _state.value = CallState(phase = CallPhase.Incoming, remoteNumber = from)
            onIncomingCall?.invoke(from)
            // Telecom can silently drop the mirror (no failure callback); the ringing surface
            // still has to come up, so start it directly as well. Idempotent with the
            // ConnectionService path — the service collects the same manager state.
            appContext?.let { ctx ->
                runCatching {
                    com.vayunmathur.communicate.telephony.GoogleVoiceCallForegroundService.start(ctx)
                }
            }
        }
    }

    private fun setActive() {
        _state.value = _state.value.copy(phase = CallPhase.Active)
        connection?.onCallActive()
    }

    /** Apply the remote SDP answer to the WebRTC session exactly once (183 or 200). */
    private suspend fun applyRemote(sdp: String) {
        if (remoteApplied) return
        remoteApplied = true
        audio?.setRemoteAnswer(sdp)
    }

    private fun endCall() {
        cleanupCall()
        _state.value = CallState(phase = CallPhase.Ended, remoteNumber = remoteNumber)
        connection?.onCallEnded()
    }

    private fun fail(reason: String) {
        Log.error("GoogleVoiceCall", "call failed: $reason")
        cleanupCall()
        _state.value = CallState(phase = CallPhase.Ended, remoteNumber = remoteNumber)
        connection?.onCallEnded()
        if (!registered) stopRegistration()
    }

    private fun cleanupCall() {
        runCatching { audio?.close() }
        audio = null
        remoteApplied = false
        pendingInviteTarget = null
        pendingInviteSdp = null
        remoteNumber = ""
    }

    private fun ensureSipClient(info: com.vayunmathur.communicate.data.googlevoice.GvSipRegisterInfo): SipClient {
        sip?.let { return it }
        return SipClient(info, gvNumber, scope, sipListener()).also { sip = it }
    }

    private fun scheduleRegistrationRefresh() {
        registrationJob?.cancel()
        registrationJob = scope.launch {
            delay(REDIAL_DELAY_MS)
            registered = false
            runCatching { sip?.register() }.onFailure {
                Log.error("GoogleVoiceCall", "registration refresh failed", it)
                stopRegistration()
            }
        }
    }

    /** Reset to Idle once the UI has consumed a terminal state. */
    fun clearEnded() {
        if (_state.value.phase == CallPhase.Ended) _state.value = CallState()
    }
}
