package com.vayunmathur.communicate.telephony

import android.content.ComponentName
import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.telecom.Connection
import android.telecom.ConnectionRequest
import android.telecom.ConnectionService
import android.telecom.DisconnectCause
import android.telecom.PhoneAccount
import android.telecom.PhoneAccountHandle
import android.telecom.TelecomManager
import android.telecom.VideoProfile
import com.vayunmathur.library.log.Log
import com.vayunmathur.communicate.data.call.InAppCallConnectionBridge
import com.vayunmathur.communicate.data.call.InAppCallPhase
import com.vayunmathur.communicate.data.call.InAppCallRegistry

/**
 * Registers WhatsApp and Signal calling as one self-managed [PhoneAccount], so an in-app call behaves
 * like another line: the system owns the incoming-call surface, call audio focus, Bluetooth routing and
 * interruption handling, rather than the app approximating all of it.
 *
 * One account rather than one per line because only a single in-app call can be active at a time, and
 * [InAppCallRegistry] already knows which line owns it. Google Voice keeps its own account.
 */
object InAppCallTelecom {
    private const val ACCOUNT_ID = "communicate_inapp_calls"

    fun handle(context: Context): PhoneAccountHandle =
        PhoneAccountHandle(
            ComponentName(context.applicationContext, InAppCallConnectionService::class.java),
            ACCOUNT_ID,
        )

    fun registerPhoneAccount(context: Context, label: String) {
        val tm = context.getSystemService(TelecomManager::class.java) ?: return
        val account = PhoneAccount.builder(handle(context), label)
            .setCapabilities(PhoneAccount.CAPABILITY_SELF_MANAGED)
            .addSupportedUriScheme(PhoneAccount.SCHEME_TEL)
            .addSupportedUriScheme(PhoneAccount.SCHEME_SIP)
            .build()
        runCatching { tm.registerPhoneAccount(account) }
    }

    fun unregisterPhoneAccount(context: Context) {
        val tm = context.getSystemService(TelecomManager::class.java) ?: return
        runCatching { tm.unregisterPhoneAccount(handle(context)) }
    }

    /**
     * Tell the system about a call the app has already started. The call itself is placed by the line;
     * Telecom is informed so it can own audio and the call surface.
     */
    fun addOutgoing(context: Context, peerId: String) {
        val tm = context.getSystemService(TelecomManager::class.java) ?: return
        val extras = Bundle().apply {
            putParcelable(TelecomManager.EXTRA_PHONE_ACCOUNT_HANDLE, handle(context))
            // Self-managed calls must not be dialled by the system; we only mirror our own call.
            putBoolean(TelecomManager.EXTRA_START_CALL_WITH_SPEAKERPHONE, false)
        }
        val uri = Uri.fromParts(PhoneAccount.SCHEME_SIP, peerId, null)
        runCatching { tm.placeCall(uri, extras) }
    }

    fun addIncoming(context: Context, peerId: String) {
        val tm = context.getSystemService(TelecomManager::class.java) ?: return
        val extras = Bundle().apply {
            putParcelable(
                TelecomManager.EXTRA_INCOMING_CALL_ADDRESS,
                Uri.fromParts(PhoneAccount.SCHEME_SIP, peerId, null),
            )
        }
        runCatching { tm.addNewIncomingCall(handle(context), extras) }
    }
}

/**
 * The Telecom [Connection] for one in-app call. Translates system actions into
 * [InAppCallRegistry] calls, and reflects call state back so the system surface stays in step.
 *
 * `audioModeIsVoip` is what makes Telecom manage audio mode and focus for us, which is why the app does
 * not request focus itself.
 */
class InAppCallConnection : Connection(), InAppCallConnectionBridge {
    init {
        audioModeIsVoip = true
        connectionCapabilities = connectionCapabilities or CAPABILITY_MUTE
    }

    override fun onAnswer() {
        InAppCallRegistry.answer("telecom.onAnswer")
    }

    override fun onReject() {
        InAppCallRegistry.reject("telecom.onReject")
        setDisconnected(DisconnectCause(DisconnectCause.REJECTED))
        destroy()
    }

    override fun onDisconnect() {
        InAppCallRegistry.hangup("telecom.onDisconnect")
        setDisconnected(DisconnectCause(DisconnectCause.LOCAL))
        destroy()
    }

    override fun onAbort() {
        InAppCallRegistry.hangup("telecom.onAbort")
        setDisconnected(DisconnectCause(DisconnectCause.CANCELED))
        destroy()
    }

    /** Volume or power pressed while ringing: mute the ring, keep the call ringing on screen. */
    override fun onSilence() {
        CallRinger.silence(RingLine.InApp)
    }

    /** The system mute button, as opposed to the in-app one. */
    override fun onMuteStateChanged(isMuted: Boolean) {
        if (InAppCallRegistry.state.value.muted != isMuted) InAppCallRegistry.toggleMuted()
    }

    override fun onCallActive() {
        setActive()
    }

    override fun onCallEnded() {
        setDisconnected(DisconnectCause(DisconnectCause.REMOTE))
        destroy()
    }
}

/**
 * Self-managed [ConnectionService] for WhatsApp and Signal calls.
 *
 * Both directions are mirrors of a call the line has already begun, so neither creates one: the
 * connection just binds the system surface to [InAppCallRegistry].
 */
class InAppCallConnectionService : ConnectionService() {
    override fun onCreateOutgoingConnection(
        connectionManagerPhoneAccount: PhoneAccountHandle?,
        request: ConnectionRequest?,
    ): Connection {
        val state = InAppCallRegistry.state.value
        Log.status(TAG, "onCreateOutgoingConnection for ${state.line} phase=${state.phase}")
        val connection = InAppCallConnection().apply {
            setAddress(
                request?.address ?: Uri.fromParts(PhoneAccount.SCHEME_SIP, state.peerId, null),
                TelecomManager.PRESENTATION_ALLOWED,
            )
            setCallerDisplayName(state.peerName, TelecomManager.PRESENTATION_ALLOWED)
            videoState = VideoProfile.STATE_AUDIO_ONLY
            setDialing()
            connectionProperties = connectionProperties or Connection.PROPERTY_SELF_MANAGED
        }
        InAppCallRegistry.connection = connection
        // Already Active by the time Telecom catches up on a fast connect.
        if (state.phase == InAppCallPhase.Active) connection.setActive()
        InAppCallForegroundService.start(applicationContext)
        return connection
    }

    override fun onCreateIncomingConnection(
        connectionManagerPhoneAccount: PhoneAccountHandle?,
        request: ConnectionRequest?,
    ): Connection {
        val state = InAppCallRegistry.state.value
        Log.status(TAG, "onCreateIncomingConnection for ${state.line} phase=${state.phase}")
        val address = request?.extras?.getParcelable(TelecomManager.EXTRA_INCOMING_CALL_ADDRESS) as? Uri
        val connection = InAppCallConnection().apply {
            setAddress(
                address ?: Uri.fromParts(PhoneAccount.SCHEME_SIP, state.peerId, null),
                TelecomManager.PRESENTATION_ALLOWED,
            )
            setCallerDisplayName(state.peerName, TelecomManager.PRESENTATION_ALLOWED)
            videoState = VideoProfile.STATE_AUDIO_ONLY
            setInitializing()
            setRinging()
            connectionProperties = connectionProperties or Connection.PROPERTY_SELF_MANAGED
        }
        InAppCallRegistry.connection = connection
        InAppCallForegroundService.start(applicationContext)
        return connection
    }

    /**
     * Telecom could not create the mirror connection.
     *
     * Deliberately does **not** end the call. Telecom here is a surface for a call the line already owns,
     * so a failure to mirror must not terminate working media - doing so turned every outgoing call into an
     * instant "missed call" on the other end.
     */
    override fun onCreateOutgoingConnectionFailed(
        connectionManagerPhoneAccount: PhoneAccountHandle?,
        request: ConnectionRequest?,
    ) {
        Log.status(TAG, "Telecom could not create the outgoing connection; the call continues without it")
    }

    override fun onCreateIncomingConnectionFailed(
        connectionManagerPhoneAccount: PhoneAccountHandle?,
        request: ConnectionRequest?,
    ) {
        Log.status(TAG, "Telecom could not create the incoming connection; the call continues without it")
        // The system surface is gone, but the line still owns a ringing call — surface it directly
        // so it still rings instead of degrading to a silent heads-up.
        runCatching { InAppCallForegroundService.start(applicationContext) }
    }

    private companion object {
        const val TAG = "InAppCallService"
    }
}
