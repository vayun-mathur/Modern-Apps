package com.vayunmathur.communicate.telephony

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.telecom.Call
import android.telecom.CallAudioState
import android.telecom.InCallService
import android.telecom.VideoProfile
import com.vayunmathur.communicate.MainActivity
import com.vayunmathur.communicate.R
import com.vayunmathur.communicate.data.CommunicateRepository
import com.vayunmathur.communicate.data.findContactName
import com.vayunmathur.communicate.notifications.setCallFullScreenIntent
import com.vayunmathur.library.util.deleteNotificationChannel
import com.vayunmathur.library.util.ensureNotificationChannel

/**
 * In-call bridge for carrier/SIM calls.
 *
 * Owns the system call surface (notification controls) and mirrors the call into [SimCallBridge] so the
 * shared in-app call screen serves regular phone calls too — with the dial pad voicemail needs.
 *
 * Also rings: the manifest declares `IN_CALL_SERVICE_RINGING`, so Telecom plays neither the ringtone nor
 * the call-waiting tone for carrier calls and [SimRingController] drives [CallRinger] instead (#856).
 * Every leg gets the callback, not just [currentCall], so the ringer sees a call-waiting leg arrive and
 * the call it is waiting behind end.
 */
class CommunicateInCallService : InCallService() {

    private val ringController by lazy {
        SimRingController(CallRinger.asIncomingRinger(this)) { LiveCalls.otherThan(RingLine.Sim) }
    }

    private val callback = object : Call.Callback() {
        override fun onStateChanged(call: Call, state: Int) {
            if (call == currentCall) showOrClearNotification(call)
            SimCallBridge.onStateChanged(call, state)
            updateRinging()
        }

        override fun onDetailsChanged(call: Call, details: Call.Details) {
            if (call == currentCall) showOrClearNotification(call)
            updateRinging()
        }
    }

    override fun onCallAudioStateChanged(audioState: CallAudioState) {
        super.onCallAudioStateChanged(audioState)
        SimCallBridge.onAudioStateChanged(audioState)
    }

    override fun onCallAdded(call: Call) {
        super.onCallAdded(call)
        currentCall = call
        call.registerCallback(callback)
        showOrClearNotification(call)
        val number = call.details.handle?.schemeSpecificPart.orEmpty()
        SimCallBridge.onCallAdded(this, call, number, callerLabel(number))
        updateRinging()
        // Ringing must surface the screen, not just a notification: bring the app forward so the
        // InAppCallOverlay shows the incoming call with answer / decline.
        if (call.details.state == Call.STATE_RINGING) bringCallScreenForward()
    }

    override fun onCallRemoved(call: Call) {
        val wasTracked = currentCall == call
        call.unregisterCallback(callback)
        if (wasTracked) currentCall = null
        clearNotification()
        SimCallBridge.onCallRemoved(call)
        updateRinging(removed = call)
        super.onCallRemoved(call)
    }

    /** Volume or power pressed while ringing: Telecom asks the ringing dialer to go quiet. */
    override fun onSilenceRinger() {
        super.onSilenceRinger()
        ringController.silence()
    }

    override fun onDestroy() {
        ringController.release()
        LiveCalls.simCallLive = false
        super.onDestroy()
    }

    /** Re-derive ringing from every leg Telecom holds ([removed] may still be listed mid-removal). */
    private fun updateRinging(removed: Call? = null) {
        val legs = calls.filter { it != removed }.map { it.toSimLeg() }
        LiveCalls.simCallLive = legs.any { it.state == SimLegState.Live }
        ringController.onCallsChanged(legs)
    }

    private fun Call.toSimLeg(): SimLeg {
        val callDetails = details
        // Telecom puts a call-screening service's silence request in the intent extras.
        val silentRingingRequested = listOfNotNull(callDetails.intentExtras, callDetails.extras)
            .any { it.getBoolean(Call.EXTRA_SILENT_RINGING_REQUESTED) }
        return SimLeg(
            key = this,
            state = simLegState(callDetails.state),
            number = callDetails.handle?.schemeSpecificPart?.takeIf { it.isNotBlank() },
            silentRingingRequested = silentRingingRequested,
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_ANSWER -> currentCall?.answer(VideoProfile.STATE_AUDIO_ONLY)
            ACTION_DECLINE -> currentCall?.reject(false, null)
            ACTION_DISCONNECT -> currentCall?.disconnect()
        }
        currentCall?.let(::showOrClearNotification) ?: clearNotification()
        return START_NOT_STICKY
    }

    private fun showOrClearNotification(call: Call) {
        when (call.details.state) {
            Call.STATE_RINGING -> showNotification(call, incoming = true)
            Call.STATE_DIALING, Call.STATE_CONNECTING, Call.STATE_ACTIVE, Call.STATE_HOLDING -> {
                showNotification(
                    call,
                    incoming = false)
            }
            else -> {
                clearNotification()
            }
        }
    }

    /** Tapping the call notification opens the call screen rather than the message list. */
    private fun bringCallScreenForward() {
        startActivity(
            Intent(this, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            },
        )
    }

    private fun showNotification(call: Call, incoming: Boolean) {
        // Retired: the pre-CallStyle channel, whose hand-built actions read as an ordinary
        // notification rather than a ringing call.
        deleteNotificationChannel(LEGACY_CHANNEL_ID)
        ensureNotificationChannel(
            id = CHANNEL_ID,
            name = getString(R.string.regular_call_channel_name),
            importance = NotificationManager.IMPORTANCE_HIGH,
            description = getString(R.string.regular_call_channel_desc),
        ) {
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            // CallRinger owns ringing audio for carrier calls (IN_CALL_SERVICE_RINGING turns
            // Telecom's off); this notification is only the answer/decline surface, so it stays
            // silent and never re-alerts — otherwise its ding would double up with the ringtone.
            setSound(null, null)
            enableVibration(false)
        }

        if (incoming) {
            showIncomingCallNotification(call)
            return
        }
        val number = call.details.handle?.schemeSpecificPart.orEmpty()
        val builder = Notification.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.inapp_ongoing_call))
            .setContentText(callerLabel(number).ifBlank { getString(R.string.app_name) })
            .setSmallIcon(android.R.drawable.sym_action_call)
            .setCategory(Notification.CATEGORY_CALL)
            .setPriority(Notification.PRIORITY_HIGH)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setOngoing(true)
            .setContentIntent(activityIntent())
            .addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                getString(R.string.call_end),
                actionIntent(ACTION_DISCONNECT, DISCONNECT_REQUEST_CODE),
            )

        val nm = getSystemService(NotificationManager::class.java) ?: return
        nm.notify(NOTIFICATION_ID, builder.build())
    }

    /**
     * Incoming-call notification using [Notification.CallStyle], which is what gives the system's
     * standard green answer / red decline buttons and full-screen ringing treatment. Hand-built
     * actions would render in the app's theme colours instead, which reads as an ordinary
     * notification rather than a ringing call.
     */
    private fun showIncomingCallNotification(call: Call) {
        val number = call.details.handle?.schemeSpecificPart.orEmpty()
        val caller = android.app.Person.Builder()
            .setName(callerLabel(number).ifBlank { getString(R.string.call_state_incoming_generic) })
            .setImportant(true)
            .build()
        val notification = Notification.Builder(this, CHANNEL_ID)
            .setStyle(
                Notification.CallStyle.forIncomingCall(
                    caller,
                    actionIntent(ACTION_DECLINE, 2),
                    actionIntent(ACTION_ANSWER, 1),
                ),
            )
            .setSmallIcon(android.R.drawable.sym_call_incoming)
            .setCategory(Notification.CATEGORY_CALL)
            // Colorized plus CallStyle is what promotes it to the ringing treatment.
            .setColorized(true)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setOngoing(true)
            .setAutoCancel(false)
            .setContentIntent(activityIntent())
            .setCallFullScreenIntent(this, fullScreenIntent())
            .build()
        val nm = getSystemService(NotificationManager::class.java) ?: return
        nm.notify(NOTIFICATION_ID, notification)
    }

    private fun fullScreenIntent(): PendingIntent = PendingIntent.getActivity(
        this,
        FULL_SCREEN_REQUEST_CODE,
        Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        },
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    /**
     * Memoised because the notification is rebuilt on every state and details change, and the
     * lookup is a synchronous provider query on the main thread.
     */
    private fun callerLabel(number: String): String {
        if (number != lookedUpNumber) {
            lookedUpNumber = number
            lookedUpName = CommunicateRepository.findContactName(this, number)
        }
        return lookedUpName ?: number
    }

    private fun clearNotification() {
        getSystemService(NotificationManager::class.java)?.cancel(NOTIFICATION_ID)
    }

    private fun actionIntent(action: String, requestCode: Int): PendingIntent = PendingIntent.getService(
        this,
        requestCode,
        Intent(this, CommunicateInCallService::class.java).apply { this.action = action },
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun activityIntent(): PendingIntent = PendingIntent.getActivity(
        this,
        0,
        Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        },
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    companion object {
        // Retired pre-CallStyle channel: the system keeps an existing channel's sound, so silencing
        // it in place would never take effect — the renamed channel below replaces it and this deletes it.
        private const val LEGACY_CHANNEL_ID = "regular_calls"
        private const val CHANNEL_ID = "regular_calls_v2"
        private const val NOTIFICATION_ID = 4811
        private const val ACTION_ANSWER = "com.vayunmathur.communicate.regularcall.ANSWER"
        private const val ACTION_DECLINE = "com.vayunmathur.communicate.regularcall.DECLINE"
        private const val ACTION_DISCONNECT = "com.vayunmathur.communicate.regularcall.DISCONNECT"
        private const val DISCONNECT_REQUEST_CODE = 3
        private const val FULL_SCREEN_REQUEST_CODE = 4

        private var currentCall: Call? = null
        private var lookedUpNumber: String? = null
        private var lookedUpName: String? = null
    }
}
