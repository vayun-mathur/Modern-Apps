package com.vayunmathur.communicate.data.signal.call

import android.content.Context
import com.vayunmathur.library.log.Log
import com.vayunmathur.communicate.data.call.WebRtcInit
import com.vayunmathur.library.network.NetworkClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.signal.ringrtc.CallId
import org.signal.ringrtc.CallManager
import org.signal.ringrtc.CallSummary
import org.signal.ringrtc.HttpHeader
import org.signal.ringrtc.NetworkRoute
import org.signal.ringrtc.Remote
import org.webrtc.EglBase
import org.webrtc.PeerConnection
import org.webrtc.VideoSink
import java.time.Instant
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import javax.net.ssl.SSLSocketFactory

/**
 * Signal 1:1 calling, driven by RingRTC.
 *
 * RingRTC owns the call state machine and the media; this class is the seam to the app. Two directions:
 *  - RingRTC asks us to put signaling on the wire ([Observer.onSendOffer] and friends), which becomes a
 *    `CallMessage` sent through [signaling].
 *  - Inbound `CallMessage`s are fed back in via `received*`.
 *
 * Offers and answers carry **opaque V4 protobuf bytes**, never SDP — modern Signal removed the `sdp`
 * fields entirely, and RingRTC synthesises SDP locally on both ends.
 *
 * [CallManager.messageSent]/[CallManager.messageSendFailure] are not optional: RingRTC serialises
 * signaling one message at a time and will not proceed until one of them is called.
 *
 * Scope is 1:1 audio. Group calls, call links and video capture are not implemented; the group and
 * ad-hoc observer callbacks are refused rather than silently dropped.
 */
class SignalCallManager(
    internal val appContext: Context,
    internal val signaling: SignalCallSignaling,
    private val sslSocketFactory: () -> SSLSocketFactory?,
) : CallManager.Observer {

    /** The lifecycle phase of the current Signal call. */
    enum class CallState { Ringing, Connecting, Connected, Ended }

    internal val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    internal val initialized = AtomicBoolean(false)

    internal var callManager: CallManager? = null
    internal var eglBase: EglBase? = null

    /**
     * Created per call. Present even for audio, because Signal's offer always carries a video m-line and an
     * answer must match it; only [SignalCamera.setEnabled] differs between audio and video.
     */
    internal var camera: SignalCamera? = null

    /** Tracked so a later video toggle keeps telling RingRTC the source is a screencast. */
    internal var screenSharing = false

    /** Exposed so the call UI can attach renderers once it exists. */
    val localVideoSink = SwappableVideoSink()
    val remoteVideoSink = SwappableVideoSink()

    /** Media type per call, so an answer knows whether video was offered. */
    internal val callMediaTypes = java.util.concurrent.ConcurrentHashMap<Long, CallManager.CallMediaType>()

    @Synchronized


    /** The EGL context group calls must share, so both call types use one GL thread. */

    /** RingRTC keeps one factory per CallManager, so group calls must reuse this instance. */

    /** The EGL context renderers must share with the decoder, so frames can be drawn. */

    override fun onStartCall(
        remote: Remote?,
        callId: CallId?,
        isOutgoing: Boolean?,
        callMediaType: CallManager.CallMediaType?,
    ) {
        val aci = (remote as? SignalRemote)?.aci ?: return
        val id = callId?.longValue() ?: return
        val mediaType = callMediaType ?: CallManager.CallMediaType.AUDIO_CALL
        callMediaTypes[id] = mediaType
        signaling.onCallStateChanged(aci, id, CallState.Ringing, mediaType.isVideo())
        // RingRTC waits for proceed() before touching media, and proceed() needs relays, so both happen
        // off the callback thread.
        scope.launch { proceed(id, mediaType) }
    }

    private suspend fun proceed(callId: Long, mediaType: CallManager.CallMediaType) {
        val manager = callManager ?: return
        val egl = eglBase ?: return
        val iceServers = try {
            signaling.iceServers()
        } catch (expected: Throwable) {
            Log.status(TAG, "could not fetch ICE servers; the call will likely fail behind NAT", expected)
            emptyList()
        }
        if (iceServers.isEmpty()) {
            Log.status(TAG, "proceeding with no ICE servers; expect connection failure unless both ends are local")
        }
        val cameraControl = camera ?: SignalCamera(appContext, egl).also { camera = it }
        try {
            manager.proceed(
                CallId(callId),
                appContext,
                egl,
                org.signal.ringrtc.AudioConfig(),
                org.signal.ringrtc.VideoConfig(),
                localVideoSink,
                remoteVideoSink,
                cameraControl,
                iceServers,
                false,
                CallManager.DataMode.NORMAL,
                AUDIO_LEVEL_INTERVAL_MS,
                null,
                mediaType.isVideo(),
                null,
            )
            Log.status(
                TAG,
                "proceed ok for call $callId with ${iceServers.size} ICE servers, " +
                    "camera=${cameraControl.hasCapturer()} video=${mediaType.isVideo()}",
            )
            // proceed()'s enableCamera only starts the local track: it calls the inner
            // CallContext.setVideoEnabled, not ringrtcSetVideoEnable, so the peer is never told we are
            // sending video and shows nothing. The public setVideoEnable is what emits the sender status.
            if (mediaType.isVideo()) assertOutgoingVideo(true)
        } catch (expected: Throwable) {
            Log.status(TAG, "proceed failed for call $callId", expected)
        }
    }

    /**
     * Tell the peer whether we are sending video.
     *
     * The sender status travels over the RTP data channel, so it only reaches the peer once the connection is
     * up — which is why it is re-asserted on connect rather than only when the call is set up.
     */
    private fun assertOutgoingVideo(enabled: Boolean) {
        withManager("setVideoEnable") { it.setVideoEnable(enabled, screenSharing) }
    }

    internal fun signalRemoteVideo(enabled: Boolean) {
        signaling.onRemoteVideo(enabled)
    }

    override fun onCallEnded(remote: Remote?, reason: CallManager.CallEndReason, summary: CallSummary) {
        val aci = (remote as? SignalRemote)?.aci ?: return
        Log.status(TAG, "call with $aci ended: $reason")
        signaling.onCallStateChanged(aci, 0, CallState.Ended, false)
    }

    override fun onCallEvent(remote: Remote?, event: CallManager.CallEvent?) {
        val aci = (remote as? SignalRemote)?.aci ?: return
        Log.status(TAG, "call event for $aci: $event")
        // Media-state events change what the UI renders without changing the call phase.
        when (event) {
            CallManager.CallEvent.REMOTE_VIDEO_ENABLE -> {
                signalRemoteVideo(true)
                return
            }
            CallManager.CallEvent.REMOTE_VIDEO_DISABLE -> {
                signalRemoteVideo(false)
                return
            }
            CallManager.CallEvent.REMOTE_SHARING_SCREEN_ENABLE -> {
                signaling.onRemoteScreenShare(true)
                return
            }
            CallManager.CallEvent.REMOTE_SHARING_SCREEN_DISABLE -> {
                signaling.onRemoteScreenShare(false)
                return
            }
            else -> Unit
        }
        val state = when (event) {
            // Either side accepting is what makes a call active; missing these leaves the UI on "dialing"
            // while media is already flowing.
            CallManager.CallEvent.LOCAL_CONNECTED,
            CallManager.CallEvent.REMOTE_CONNECTED,
            CallManager.CallEvent.RECONNECTED,
            -> {
                // The data channel exists now, so re-state what we are sending. Without this a video call
                // that was set up before the connection came up shows no video at the far end.
                if (callMediaTypes.values.any { it.isVideo() } || screenSharing) assertOutgoingVideo(true)
                CallState.Connected
            }
            CallManager.CallEvent.LOCAL_RINGING, CallManager.CallEvent.REMOTE_RINGING -> CallState.Ringing
            CallManager.CallEvent.RECONNECTING -> CallState.Connecting
            else -> {
                // Media-state and screen-share events do not change the call phase.
                return
            }
        }
        signaling.onCallStateChanged(aci, 0, state, false)
    }

    override fun onCallConcluded(remote: Remote?) {
        callMediaTypes.clear()
        screenSharing = false
        camera?.dispose()
        camera = null
        localVideoSink.attach(null)
        remoteVideoSink.attach(null)
    }

    override fun onNetworkRouteChanged(remote: Remote?, networkRoute: NetworkRoute?) = Unit

    override fun onAudioLevels(remote: Remote?, capturedLevel: Int, receivedLevel: Int) = Unit

    override fun onLowBandwidthForVideo(remote: Remote?, recovered: Boolean) = Unit

    // -- Observer: outbound signaling --

    override fun onSendOffer(
        callId: CallId?,
        remote: Remote?,
        remoteDeviceId: Int?,
        broadcast: Boolean?,
        opaque: ByteArray,
        callMediaType: CallManager.CallMediaType?,
    ) {
        val id = callId?.longValue() ?: return
        // Offers are urgent so the callee's device wakes and rings.
        dispatch(
            id,
            remote,
            remoteDeviceId,
            broadcast,
            urgent = true,
            message = SignalCallMessage.Offer(
                callId = id,
                opaque = opaque,
                video = (callMediaType ?: CallManager.CallMediaType.AUDIO_CALL).isVideo(),
            ),
        )
    }

    override fun onSendAnswer(
        callId: CallId?,
        remote: Remote?,
        remoteDeviceId: Int?,
        broadcast: Boolean?,
        opaque: ByteArray,
    ) {
        val id = callId?.longValue() ?: return
        dispatch(id, remote, remoteDeviceId, broadcast, urgent = false, message = SignalCallMessage.Answer(id, opaque))
    }

    override fun onSendIceCandidates(
        callId: CallId?,
        remote: Remote?,
        remoteDeviceId: Int?,
        broadcast: Boolean?,
        iceCandidates: List<ByteArray>?,
    ) {
        val id = callId?.longValue() ?: return
        val candidates = iceCandidates ?: emptyList()
        dispatch(id, remote, remoteDeviceId, broadcast, urgent = false, message = SignalCallMessage.Ice(id, candidates))
    }

    override fun onSendHangup(
        callId: CallId?,
        remote: Remote?,
        remoteDeviceId: Int?,
        broadcast: Boolean?,
        hangupType: CallManager.HangupType?,
        deviceId: Int?,
    ) {
        val id = callId?.longValue() ?: return
        dispatch(
            id,
            remote,
            remoteDeviceId,
            broadcast,
            urgent = true,
            message = SignalCallMessage.Hangup(
                callId = id,
                type = hangupType ?: CallManager.HangupType.NORMAL,
                deviceId = deviceId ?: 0,
            ),
        )
    }

    override fun onSendBusy(callId: CallId?, remote: Remote?, remoteDeviceId: Int?, broadcast: Boolean?) {
        val id = callId?.longValue() ?: return
        dispatch(id, remote, remoteDeviceId, broadcast, urgent = false, message = SignalCallMessage.Busy(id))
    }

    /**
     * Send one signaling message and report the outcome back. RingRTC blocks its signaling queue until
     * [CallManager.messageSent] or [CallManager.messageSendFailure] is called, so every path must end in
     * one of them.
     */
    private fun dispatch(
        callId: Long,
        remote: Remote?,
        remoteDeviceId: Int?,
        broadcast: Boolean?,
        urgent: Boolean,
        message: SignalCallMessage,
    ) {
        val aci = (remote as? SignalRemote)?.aci
        if (aci == null) {
            reportSendResult(callId, false)
            return
        }
        // broadcast means "every device"; otherwise the message is addressed to one.
        val deviceId = if (broadcast == true) null else remoteDeviceId
        scope.launch {
            val ok = try {
                signaling.sendCallMessage(aci, deviceId, message, urgent)
            } catch (expected: Throwable) {
                Log.status(TAG, "call signaling send failed", expected)
                false
            }
            Log.status(
                TAG,
                "sent ${message::class.simpleName} callId=$callId to $aci device=${deviceId ?: "all"} " +
                    "urgent=$urgent ok=$ok" +
                    when (message) {
                        is SignalCallMessage.Offer -> " opaque=${message.opaque.size}B video=${message.video}"
                        is SignalCallMessage.Answer -> " opaque=${message.opaque.size}B"
                        is SignalCallMessage.Ice -> " candidates=${message.candidates.size}"
                        else -> ""
                    },
            )
            reportSendResult(callId, ok)
        }
    }

    private fun reportSendResult(callId: Long, ok: Boolean) {
        val manager = callManager ?: return
        try {
            if (ok) manager.messageSent(CallId(callId)) else manager.messageSendFailure(CallId(callId))
        } catch (expected: Throwable) {
            Log.status(TAG, "could not report the signaling send result", expected)
        }
    }

    // -- Observer: RingRTC uses the app as its HTTP stack --

    override fun onSendHttpRequest(
        requestId: Long,
        url: String,
        method: CallManager.HttpMethod,
        headers: List<HttpHeader>?,
        body: ByteArray?,
    ) {
        val manager = callManager ?: return
        scope.launch {
            try {
                val resp = NetworkClient.execute(
                    url,
                    method = when (method) {
                        CallManager.HttpMethod.GET -> "GET"
                        CallManager.HttpMethod.PUT -> "PUT"
                        CallManager.HttpMethod.POST -> "POST"
                        CallManager.HttpMethod.DELETE -> "DELETE"
                    },
                    headers = headers?.associate { it.name to it.value }.orEmpty(),
                    body = body,
                    sslSocketFactory = sslSocketFactory(),
                )
                manager.receivedHttpResponse(requestId, resp.status, resp.bytes)
            } catch (expected: Throwable) {
                Log.status(TAG, "RingRTC HTTP request failed", expected)
                try { manager.httpRequestFailed(requestId) } catch (_: Throwable) {}
            }
        }
    }

    // -- Observer: group calling, out of scope --

    override fun onSendCallMessage(
        recipientUuid: UUID,
        message: ByteArray,
        urgency: CallManager.CallMessageUrgency,
    ) {
        Log.status(TAG, "onSendCallMessage is only used by group calling, which is not implemented")
    }

    override fun onSendCallMessageToGroup(
        groupId: ByteArray,
        message: ByteArray,
        urgency: CallManager.CallMessageUrgency,
        overrideRecipients: List<UUID>,
    ) {
        Log.status(TAG, "group calling is not implemented")
    }

    override fun onSendCallMessageToAdhocGroup(
        message: ByteArray,
        urgency: CallManager.CallMessageUrgency,
        expiration: Instant,
        recipientsToEndorsements: Map<UUID, ByteArray>,
    ) {
        Log.status(TAG, "ad-hoc call groups are not implemented")
    }

    override fun onGroupCallRingUpdate(
        groupId: ByteArray,
        ringId: Long,
        sender: UUID,
        update: CallManager.RingUpdate?,
    ) {
        Log.status(TAG, "group call ring updates are not implemented")
    }

    internal inline fun withManager(what: String, block: (CallManager) -> Unit): Boolean {
        val manager = callManager ?: return false
        return try {
            block(manager)
            true
        } catch (expected: Throwable) {
            Log.status(TAG, "$what failed", expected)
            false
        }
    }

    internal companion object {
        internal const val TAG = "SignalCallManager"
        const val AUDIO_LEVEL_INTERVAL_MS = 500
    }
}
