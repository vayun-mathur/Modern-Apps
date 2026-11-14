package com.vayunmathur.communicate.data.signal.call

import com.google.protobuf.ByteString
import org.signal.ringrtc.CallManager
import org.whispersystems.signalservice.internal.push.SignalServiceProtos

/**
 * The signaling messages a 1:1 call exchanges, as RingRTC produces them.
 *
 * Offers, answers and ICE updates carry **opaque** bytes — a `signaling.ConnectionParametersV4`
 * protobuf that RingRTC builds and parses. The `sdp` fields these used to occupy are `reserved` in the
 * proto and cannot be populated.
 */
sealed interface SignalCallMessage {
    val callId: Long

    /** A call offer: opaque SDP blob and whether video is offered. */
    data class Offer(override val callId: Long, val opaque: ByteArray, val video: Boolean) : SignalCallMessage
    /** A call answer: opaque SDP blob. */
    data class Answer(override val callId: Long, val opaque: ByteArray) : SignalCallMessage
    /** ICE candidates for a call, as opaque blobs. */
    data class Ice(override val callId: Long, val candidates: List<ByteArray>) : SignalCallMessage
    /** A call hangup, with the hangup kind. */
    data class Hangup(
        override val callId: Long,
        val type: CallManager.HangupType,
        val deviceId: Int,
    ) : SignalCallMessage
    /** The callee is busy. */
    data class Busy(override val callId: Long) : SignalCallMessage
}

/**
 * Wrap a call message in a `Content`. [destinationDeviceId] is application-level addressing, not
 * transport routing — the server fans out to every device regardless, and the recipient drops messages
 * whose `destinationDeviceId` is not theirs. Broadcast messages simply omit it.
 */
fun SignalCallMessage.toContent(destinationDeviceId: Int?): SignalServiceProtos.Content {
    val call = SignalServiceProtos.CallMessage.newBuilder()
    when (this) {
        is SignalCallMessage.Offer -> call.setOffer(
            SignalServiceProtos.CallMessage.Offer.newBuilder()
                .setId(callId)
                .setType(
                    if (video) {
                        SignalServiceProtos.CallMessage.Offer.Type.OFFER_VIDEO_CALL
                    } else {
                        SignalServiceProtos.CallMessage.Offer.Type.OFFER_AUDIO_CALL
                    },
                )
                .setOpaque(ByteString.copyFrom(opaque)),
        )
        is SignalCallMessage.Answer -> call.setAnswer(
            SignalServiceProtos.CallMessage.Answer.newBuilder()
                .setId(callId)
                .setOpaque(ByteString.copyFrom(opaque)),
        )
        is SignalCallMessage.Ice -> candidates.forEach { candidate ->
            call.addIceUpdate(
                SignalServiceProtos.CallMessage.IceUpdate.newBuilder()
                    .setId(callId)
                    .setOpaque(ByteString.copyFrom(candidate)),
            )
        }
        is SignalCallMessage.Hangup -> call.setHangup(
            SignalServiceProtos.CallMessage.Hangup.newBuilder()
                .setId(callId)
                .setType(type.toProto())
                .setDeviceId(deviceId),
        )
        is SignalCallMessage.Busy -> call.setBusy(
            SignalServiceProtos.CallMessage.Busy.newBuilder().setId(callId),
        )
    }
    if (destinationDeviceId != null) call.setDestinationDeviceId(destinationDeviceId)
    return SignalServiceProtos.Content.newBuilder().setCallMessage(call).build()
}

fun CallManager.HangupType.toProto(): SignalServiceProtos.CallMessage.Hangup.Type = when (this) {
    CallManager.HangupType.NORMAL -> SignalServiceProtos.CallMessage.Hangup.Type.HANGUP_NORMAL
    CallManager.HangupType.ACCEPTED -> SignalServiceProtos.CallMessage.Hangup.Type.HANGUP_ACCEPTED
    CallManager.HangupType.DECLINED -> SignalServiceProtos.CallMessage.Hangup.Type.HANGUP_DECLINED
    CallManager.HangupType.BUSY -> SignalServiceProtos.CallMessage.Hangup.Type.HANGUP_BUSY
    CallManager.HangupType.NEED_PERMISSION -> SignalServiceProtos.CallMessage.Hangup.Type.HANGUP_NEED_PERMISSION
}

fun SignalServiceProtos.CallMessage.Hangup.Type.toRingRtc(): CallManager.HangupType = when (this) {
    SignalServiceProtos.CallMessage.Hangup.Type.HANGUP_NORMAL -> CallManager.HangupType.NORMAL
    SignalServiceProtos.CallMessage.Hangup.Type.HANGUP_ACCEPTED -> CallManager.HangupType.ACCEPTED
    SignalServiceProtos.CallMessage.Hangup.Type.HANGUP_DECLINED -> CallManager.HangupType.DECLINED
    SignalServiceProtos.CallMessage.Hangup.Type.HANGUP_BUSY -> CallManager.HangupType.BUSY
    SignalServiceProtos.CallMessage.Hangup.Type.HANGUP_NEED_PERMISSION -> CallManager.HangupType.NEED_PERMISSION
}
