package com.vayunmathur.communicate.data.whatsapp.call

import android.content.Context
import com.vayunmathur.library.log.Log
import com.vayunmathur.communicate.data.whatsapp.WhatsAppProtocol
import com.vayunmathur.communicate.data.whatsapp.padMessage
import com.vayunmathur.communicate.data.whatsapp.unpadMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Coarse call lifecycle exposed to the UI. */
enum class WhatsAppCallPhase { Idle, Outgoing, Incoming, Connecting, Active, Ended }

/** An immutable snapshot of the current WhatsApp call for the UI. */
data class WhatsAppCallState(
    val phase: WhatsAppCallPhase = WhatsAppCallPhase.Idle,
    val callId: String = "",
    val peerJid: String = "",
    val peerName: String = "",
    val isVideo: Boolean = false,
    val muted: Boolean = false,
    val speaker: Boolean = false,
)

/**
 * Orchestrates a single WhatsApp 1:1 call (Phase D 3c): wires [WhatsAppCallSignaling] to the
 * [WhatsAppCallSession] media leg and the [WhatsAppCallCrypto] call key, publishing a
 * [WhatsAppCallState] flow. Mirrors `GoogleVoiceCallManager`.
 *
 * It talks to the transport (socket send + per-device call-key encrypt/decrypt) through a [Bridge]
 * implemented by `WhatsAppClient`, so this class has no direct socket/Signal dependency and the
 * client stays the single owner of the Noise connection.
 *
 * Interop: the local SDP + ICE are carried inside our `<webrtc>` `<call>` extension, so calling
 * works **client-to-client (our app ↔ our app)** but not against official WhatsApp servers.
 */
object WhatsAppCallManager {

    private const val TAG = "WACallManager"

    /** Transport hooks the client provides. */
    interface Bridge {
        val ownJid: String
        fun newId(): String
        suspend fun sendCallStanza(node: WhatsAppProtocol.Node): Boolean
        /** Fan-out encrypt the padded call key to every peer + own device. */
        suspend fun encryptCallKey(toJid: String, paddedCallKey: ByteArray): List<WhatsAppProtocol.ParticipantEnc>
        /** Decrypt an inbound call-key `<enc>`; returns the still-padded plaintext or null. */
        suspend fun decryptCallKey(senderJid: String, encType: String, ciphertext: ByteArray): ByteArray?
        fun emit(event: com.vayunmathur.communicate.data.whatsapp.WhatsAppEvent)
        fun resolveName(jid: String): String?
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _state = MutableStateFlow(WhatsAppCallState())
    val state: StateFlow<WhatsAppCallState> = _state.asStateFlow()

    private var appContext: Context? = null
    private var bridge: Bridge? = null

    private var session: WhatsAppCallSession? = null
    private var callKey: ByteArray? = null
    private var pendingRemoteSdp: String? = null
    private var callStartMs: Long = 0L

    fun init(context: Context, bridge: Bridge) {
        if (appContext == null) appContext = context.applicationContext
        this.bridge = bridge
    }

    // ------------------------------------------------------------------ outbound

    fun placeCall(jid: String, video: Boolean = false) {
        val ctx = appContext ?: return
        val b = bridge ?: return
        val callId = b.newId()
        _state.value = WhatsAppCallState(
            phase = WhatsAppCallPhase.Outgoing, callId = callId, peerJid = jid,
            peerName = b.resolveName(jid).orEmpty(), isVideo = video,
        )
        scope.launch {
            runCatching {
                val key = WhatsAppCallCrypto.generateCallKey()
                callKey = key
                val s = newSession(ctx, video).also { it.initialize() }
                session = s
                val sdp = s.createOffer()
                val encs = b.encryptCallKey(jid, WhatsAppProtocol.padMessage(key))
                if (encs.isEmpty()) { fail(callId, "no_devices"); return@launch }
                val node = WhatsAppCallSignaling.buildOffer(
                    to = jid, callId = callId, callCreator = b.ownJid, video = video,
                    encs = encs, stanzaId = b.newId(), sdp = sdp,
                )
                b.sendCallStanza(node)
                emitPhase(callId, WhatsAppCallPhase.Outgoing, video)
            }.onFailure { fail(callId, it.message ?: "place_failed") }
        }
    }

    fun hangup(reason: String = "hangup") {
        val b = bridge ?: return
        val st = _state.value
        if (st.callId.isEmpty()) { endCall(st.callId, reason); return }
        scope.launch {
            runCatching {
                b.sendCallStanza(
                    WhatsAppCallSignaling.buildTerminate(st.peerJid, st.callId, b.ownJid, reason, b.newId()),
                )
            }
            endCall(st.callId, reason)
        }
    }

    fun reject() {
        val b = bridge ?: return
        val st = _state.value
        scope.launch {
            runCatching {
                b.sendCallStanza(WhatsAppCallSignaling.buildReject(st.peerJid, st.callId, st.peerJid, b.newId()))
            }
            endCall(st.callId, "rejected")
        }
    }

    fun answer() {
        val ctx = appContext ?: return
        val b = bridge ?: return
        val st = _state.value
        val remoteSdp = pendingRemoteSdp ?: run { fail(st.callId, "no_offer_sdp"); return }
        scope.launch {
            runCatching {
                val s = session ?: newSession(ctx, st.isVideo).also { it.initialize(); session = it }
                val answerSdp = s.createAnswer(remoteSdp)
                b.sendCallStanza(
                    WhatsAppCallSignaling.buildAccept(
                        to = st.peerJid, callId = st.callId, callCreator = st.peerJid,
                        stanzaId = b.newId(), video = st.isVideo, sdp = answerSdp,
                    ),
                )
                setActive(st.callId, st.isVideo)
            }.onFailure { fail(st.callId, it.message ?: "answer_failed") }
        }
    }

    fun setMuted(muted: Boolean) {
        session?.setMuted(muted)
        _state.value = _state.value.copy(muted = muted)
    }

    fun setSpeaker(on: Boolean) {
        session?.setSpeaker(on)
        _state.value = _state.value.copy(speaker = on)
    }

    /** Flip the camera (video calls only). */
    fun switchCamera() {
        (session as? WhatsAppVideoCallSession)?.switchCamera()
    }

    /** Enable/disable the local camera track (video calls only). */
    fun setVideoEnabled(enabled: Boolean) {
        (session as? WhatsAppVideoCallSession)?.setVideoEnabled(enabled)
    }

    /** Attach UI renderers for a video call (no-op for audio). */
    fun attachVideoRenderers(local: org.webrtc.SurfaceViewRenderer?, remote: org.webrtc.SurfaceViewRenderer?) {
        (session as? WhatsAppVideoCallSession)?.attachRenderers(local, remote)
    }

    // ------------------------------------------------------------------ inbound

    /** Route a parsed inbound `<call>` from the client's message loop. */
    fun onInbound(inbound: WhatsAppCallSignaling.InboundCall) {
        val b = bridge ?: return
        when (inbound.kind) {
            WhatsAppCallSignaling.InboundCall.Kind.OFFER -> onOffer(inbound)
            WhatsAppCallSignaling.InboundCall.Kind.ACCEPT -> onAccept(inbound)
            WhatsAppCallSignaling.InboundCall.Kind.PREACCEPT -> {
                inbound.sdp?.let { sdp -> scope.launch { runCatching { session?.setRemoteAnswer(sdp) } } }
            }
            WhatsAppCallSignaling.InboundCall.Kind.RELAY -> {
                inbound.candidates.forEach { session?.addRemoteCandidate(it) }
            }
            WhatsAppCallSignaling.InboundCall.Kind.REJECT,
            WhatsAppCallSignaling.InboundCall.Kind.TERMINATE -> endCall(inbound.callId, "peer_ended")
            WhatsAppCallSignaling.InboundCall.Kind.UNKNOWN -> {}
        }
    }

    private fun onOffer(inbound: WhatsAppCallSignaling.InboundCall) {
        val b = bridge ?: return
        if (_state.value.phase != WhatsAppCallPhase.Idle && _state.value.phase != WhatsAppCallPhase.Ended) {
            // Busy — auto-reject a second call.
            scope.launch {
                runCatching {
                    b.sendCallStanza(WhatsAppCallSignaling.buildReject(
                        inbound.from,
                        inbound.callId,
                        inbound.creator,
                        b.newId()))
                }
            }
            return
        }
        scope.launch {
            runCatching {
                val enc = inbound.enc
                if (enc != null) {
                    val padded = b.decryptCallKey(inbound.creator.ifEmpty { inbound.from }, enc.type, enc.ciphertext)
                    callKey = padded?.let { WhatsAppProtocol.unpadMessage(it) }
                }
                pendingRemoteSdp = inbound.sdp
                _state.value = WhatsAppCallState(
                    phase = WhatsAppCallPhase.Incoming, callId = inbound.callId, peerJid = inbound.from,
                    peerName = b.resolveName(inbound.from).orEmpty(), isVideo = inbound.isVideo,
                )
                b.emit(
                    com.vayunmathur.communicate.data.whatsapp.WhatsAppEvent.CallOffer(
                        callId = inbound.callId, from = inbound.from, callCreator = inbound.creator,
                        isVideo = inbound.isVideo, peerName = b.resolveName(inbound.from),
                    ),
                )
            }.onFailure { fail(inbound.callId, it.message ?: "offer_failed") }
        }
    }

    private fun onAccept(inbound: WhatsAppCallSignaling.InboundCall) {
        val sdp = inbound.sdp ?: return
        scope.launch {
            runCatching {
                session?.setRemoteAnswer(sdp)
                setActive(inbound.callId, inbound.isVideo)
            }.onFailure { fail(inbound.callId, it.message ?: "accept_failed") }
        }
    }

    // ------------------------------------------------------------------ helpers

    /** Overridable session factory (Phase E swaps in a video session). */
    private fun newSession(ctx: Context, video: Boolean): WhatsAppCallSession =
        if (video) WhatsAppVideoCallSession(ctx) else WhatsAppCallSession(ctx)

    private fun setActive(callId: String, video: Boolean) {
        callStartMs = System.currentTimeMillis()
        _state.value = _state.value.copy(phase = WhatsAppCallPhase.Active, callId = callId, isVideo = video)
        emitPhase(callId, WhatsAppCallPhase.Active, video)
    }

    private fun emitPhase(callId: String, phase: WhatsAppCallPhase, video: Boolean) {
        bridge?.emit(
            com.vayunmathur.communicate.data.whatsapp.WhatsAppEvent.CallStateChanged(
                callId = callId, phase = phase.name, isVideo = video,
            ),
        )
    }

    private fun endCall(callId: String, reason: String) {
        val duration = if (callStartMs > 0) (System.currentTimeMillis() - callStartMs) / 1000 else 0L
        cleanup()
        _state.value = WhatsAppCallState(phase = WhatsAppCallPhase.Ended, callId = callId)
        bridge?.emit(
            com.vayunmathur.communicate.data.whatsapp.WhatsAppEvent.CallEnded(
                callId = callId, reason = reason, durationSeconds = duration,
            ),
        )
    }

    private fun fail(callId: String, reason: String) {
        Log.error(TAG, "call $callId failed: $reason")
        endCall(callId, reason)
    }

    private fun cleanup() {
        runCatching { session?.close() }
        session = null
        callKey = null
        pendingRemoteSdp = null
        callStartMs = 0L
    }

    /** Reset to Idle once the UI consumed the terminal state. */
    fun clearEnded() {
        if (_state.value.phase == WhatsAppCallPhase.Ended) _state.value = WhatsAppCallState()
    }
}
