package com.vayunmathur.communicate.data.signal.call

import androidx.core.util.size
import android.content.Context
import com.vayunmathur.library.log.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.signal.ringrtc.CallManager
import org.signal.ringrtc.CallSummary
import org.signal.ringrtc.GroupCall
import org.webrtc.EglBase
import java.util.UUID

/**
 * A Signal group call, via RingRTC's SFU client.
 *
 * Unlike a 1:1 call there is no offer/answer over the chat channel: everyone connects to Signal's calling
 * server, and the group's identity is proved with a short-lived membership token rather than by naming the
 * participants. The SFU never learns who is in the group — RingRTC matches SFU-reported participants back to
 * people using each member's ACI paired with its ciphertext.
 *
 * RingRTC asks for the proof and the member list rather than being given them up front
 * ([GroupCall.Observer.requestMembershipProof] / [requestGroupMembers]), because both expire.
 */
class SignalGroupCallManager(
    private val appContext: Context,
    private val callManager: CallManager,
    private val eglBase: EglBase,
    private val signaling: Signaling,
) : GroupCall.Observer {

    /** What the group call needs from the app, and what it reports back. */
    interface Signaling {
        /** A fresh membership proof for [groupId], or null if one cannot be obtained. */
        suspend fun membershipProof(groupId: ByteArray): ByteArray?

        /** The group's members, as ACI/ciphertext pairs. */
        suspend fun groupMembers(groupId: ByteArray): List<Pair<UUID, ByteArray>>

        fun onGroupCallStateChanged(groupId: ByteArray, joined: Boolean, participants: Int)

        fun onGroupCallEnded(groupId: ByteArray, reason: CallManager.CallEndReason?)
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private var groupCall: GroupCall? = null
    private var groupId: ByteArray? = null

    val localVideoSink = SwappableVideoSink()

    /** True while a group call object exists, whether or not we have joined the media session. */
    val isActive: Boolean get() = groupCall != null

    /**
     * Create and connect a group call. [join] happens separately so the UI can show who is already on the call
     * before committing to being seen and heard.
     */
    fun connect(groupIdentifier: ByteArray): Boolean {
        if (groupCall != null) {
            Log.status(TAG, "a group call is already active")
            return false
        }
        return try {
            val call = callManager.createGroupCall(
                groupIdentifier,
                SFU_URL,
                // No extra HKDF info: the group's own key material is the whole input.
                ByteArray(0),
                AUDIO_LEVEL_INTERVAL_MS,
                null,
                org.signal.ringrtc.AudioConfig(),
                null,
                this,
            ) ?: return false
            groupCall = call
            groupId = groupIdentifier
            call.connect()
            Log.status(TAG, "connecting to the group call for ${groupIdentifier.toHex()}")
            true
        } catch (expected: Throwable) {
            Log.status(TAG, "could not create the group call", expected)
            groupCall = null
            groupId = null
            false
        }
    }

    /** Join the media session, so we can be heard. */
    fun join() {
        val call = groupCall ?: return
        try {
            call.setOutgoingVideoSource(localVideoSink, NoCameraControl)
            call.join()
        } catch (expected: Throwable) {
            Log.status(TAG, "could not join the group call", expected)
        }
    }

    fun leave() {
        val call = groupCall ?: return
        try {
            call.leave()
            call.disconnect()
        } catch (expected: Throwable) {
            Log.status(TAG, "could not leave the group call", expected)
        } finally {
            groupCall = null
            groupId = null
            localVideoSink.attach(null)
        }
    }

    fun setAudioMuted(muted: Boolean) {
        try {
            groupCall?.setOutgoingAudioMuted(muted)
        } catch (expected: Throwable) {
            Log.status(TAG, "could not change the group call mute state", expected)
        }
    }

    fun setVideoMuted(muted: Boolean, isScreenShare: Boolean = false) {
        try {
            groupCall?.setOutgoingVideoMuted(muted, isScreenShare)
        } catch (expected: Throwable) {
            Log.status(TAG, "could not change the group call video state", expected)
        }
    }

    // -- GroupCall.Observer. Both request callbacks must be answered or the call never establishes. --

    override fun requestMembershipProof(groupCall: GroupCall) {
        val id = groupId ?: return
        scope.launch {
            val proof = signaling.membershipProof(id)
            if (proof == null) {
                Log.status(TAG, "no membership proof; the SFU will refuse this call")
                return@launch
            }
            try {
                groupCall.setMembershipProof(proof)
            } catch (expected: Throwable) {
                Log.status(TAG, "could not set the membership proof", expected)
            }
        }
    }

    override fun requestGroupMembers(groupCall: GroupCall) {
        val id = groupId ?: return
        scope.launch {
            val members = signaling.groupMembers(id)
            if (members.isEmpty()) {
                Log.status(TAG, "no group members; participants cannot be identified")
                return@launch
            }
            try {
                groupCall.setGroupMembers(
                    members.map { (uuid, ciphertext) -> GroupCall.GroupMemberInfo(uuid, ciphertext) },
                )
            } catch (expected: Throwable) {
                Log.status(TAG, "could not set the group members", expected)
            }
        }
    }

    override fun onLocalDeviceStateChanged(groupCall: GroupCall) {
        val id = groupId ?: return
        val state = try { groupCall.localDeviceState } catch (_: Throwable) { null }
        val joined = state?.joinState == GroupCall.JoinState.JOINED
        val participants = try { groupCall.remoteDeviceStates?.size ?: 0 } catch (_: Throwable) { 0 }
        Log.status(TAG, "group call state: joinState=${state?.joinState} participants=$participants")
        signaling.onGroupCallStateChanged(id, joined, participants)
    }

    override fun onRemoteDeviceStatesChanged(groupCall: GroupCall) {
        val id = groupId ?: return
        val participants = try { groupCall.remoteDeviceStates?.size ?: 0 } catch (_: Throwable) { 0 }
        val joined = try {
            groupCall.localDeviceState?.joinState == GroupCall.JoinState.JOINED
        } catch (_: Throwable) {
            false
        }
        signaling.onGroupCallStateChanged(id, joined, participants)
    }

    /** Who is already on the call, before joining. */
    override fun onPeekChanged(groupCall: GroupCall) {
        val peek = try { groupCall.peekInfo } catch (_: Throwable) { null }
        Log.status(TAG, "group call peek: devices=${peek?.deviceCount} creator=${peek?.creator}")
    }

    override fun onEnded(
        groupCall: GroupCall,
        reason: CallManager.CallEndReason,
        summary: CallSummary,
    ) {
        val id = groupId
        Log.status(TAG, "group call ended: $reason")
        this.groupCall = null
        this.groupId = null
        localVideoSink.attach(null)
        if (id != null) signaling.onGroupCallEnded(id, reason)
    }

    // Media-detail callbacks the UI does not surface yet. Explicit rather than absent, so it is clear they were
    // considered and are simply not wired.
    override fun onAudioLevels(groupCall: GroupCall) = Unit

    override fun onSpeakingNotification(groupCall: GroupCall, event: GroupCall.SpeechEvent?) = Unit

    override fun onLowBandwidthForVideo(groupCall: GroupCall, recovered: Boolean) = Unit

    override fun onReactions(groupCall: GroupCall, reactions: List<GroupCall.Reaction>?) = Unit

    override fun onRaisedHands(groupCall: GroupCall, raisedHands: List<Long>?) = Unit

    override fun onRemoteMuteRequest(groupCall: GroupCall, sourceDemuxId: Long) = Unit

    override fun onObservedRemoteMute(groupCall: GroupCall, sourceDemuxId: Long, targetDemuxId: Long) = Unit

    private fun ByteArray.toHex(): String =
        take(HEX_PREFIX_BYTES).joinToString("") { "%02x".format(it) }

    private companion object {
        const val TAG = "SignalGroupCall"
        const val AUDIO_LEVEL_INTERVAL_MS = 200
        const val SFU_URL = "https://sfu.voip.signal.org"
        const val HEX_PREFIX_BYTES = 8
    }
}
