package com.vayunmathur.communicate.telephony

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.content.ContextCompat
import com.vayunmathur.communicate.MainActivity
import com.vayunmathur.communicate.R
import com.vayunmathur.communicate.data.googlevoice.call.CallPhase
import com.vayunmathur.communicate.data.googlevoice.call.CallState
import com.vayunmathur.communicate.data.googlevoice.call.GoogleVoiceCallManager
import com.vayunmathur.communicate.domain.dialableNumberOrNull
import com.vayunmathur.communicate.notifications.setCallFullScreenIntent
import com.vayunmathur.library.util.deleteNotificationChannel
import com.vayunmathur.library.util.ensureNotificationChannel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Foreground service (type `phoneCall`) that keeps a Google Voice call — its SIP socket, mic
 * capture, and audio focus — alive while off-screen, showing the ongoing-call notification.
 * Started by [GoogleVoiceConnectionService] and self-stops when the call reaches a terminal
 * state.
 */
class GoogleVoiceCallForegroundService : Service() {

    private val scope = CoroutineScope(SupervisorJob())
    private var observeJob: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_ANSWER -> GoogleVoiceCallManager.answer()
            ACTION_HANGUP -> {
                if (GoogleVoiceCallManager.state.value.phase == CallPhase.Incoming) {
                    GoogleVoiceCallManager.reject()
                } else {
                    GoogleVoiceCallManager.hangup()
                }
            }
        }
        startForegroundWithNotification()
        observeJob?.cancel()
        observeJob = scope.launch {
            GoogleVoiceCallManager.state.collect { state ->
                when (state.phase) {
                    CallPhase.Idle, CallPhase.Ended -> stopSelfSafely()
                    else -> startForegroundWithNotification()
                }
            }
        }
        return START_STICKY
    }

    private fun startForegroundWithNotification() {
        ensureChannels()
        val state = GoogleVoiceCallManager.state.value
        updateRinging(state)
        val contentIntent = callActivityIntent(0)
        val notification = if (state.phase == CallPhase.Incoming) {
            buildIncomingCallNotification(state.remoteNumber, contentIntent)
        } else {
            buildOngoingCallNotification(state.phase, state.remoteNumber, contentIntent)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_PHONE_CALL)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun updateRinging(state: CallState) {
        if (state.phase == CallPhase.Incoming) {
            CallRinger.start(
                this,
                RingRequest(
                    line = RingLine.GoogleVoice,
                    callKey = state.remoteNumber,
                    number = dialableNumberOrNull(state.remoteNumber),
                    callWaiting = LiveCalls.otherThan(RingLine.GoogleVoice),
                ),
            )
        } else {
            CallRinger.stop(RingLine.GoogleVoice)
        }
    }

    private fun ensureChannels() {
        // Retired: the pre-ringer channel, whose system-kept sound would ding over the ringtone loop.
        deleteNotificationChannel(LEGACY_INCOMING_CHANNEL_ID)
        ensureNotificationChannel(CHANNEL_ID, getString(R.string.gv_call_channel_name))
        ensureNotificationChannel(
            id = INCOMING_CHANNEL_ID,
            name = getString(R.string.gv_incoming_call_channel_name),
            importance = NotificationManager.IMPORTANCE_HIGH,
            description = getString(R.string.gv_incoming_call_channel_desc),
        ) {
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            // The ringer owns audio; a channel sound here would ding once over the loop.
            setSound(null, null)
            enableVibration(false)
        }
    }

    /**
     * Incoming-call notification using [Notification.CallStyle], which is what gives the system's
     * standard green answer / red decline buttons and full-screen ringing treatment. Hand-built
     * actions would render in the app's theme colours instead, which reads as an ordinary
     * notification rather than a ringing call.
     */
    private fun buildIncomingCallNotification(remoteNumber: String, contentIntent: PendingIntent): Notification {
        val caller = android.app.Person.Builder()
            .setName(remoteNumber.ifBlank { getString(R.string.account_google_voice) })
            .setImportant(true)
            .build()
        return Notification.Builder(this, INCOMING_CHANNEL_ID)
            .setStyle(
                Notification.CallStyle.forIncomingCall(
                    caller,
                    serviceActionIntent(ACTION_HANGUP, HANGUP_REQUEST_CODE),
                    serviceActionIntent(ACTION_ANSWER, 2),
                ),
            )
            .setSmallIcon(android.R.drawable.sym_call_incoming)
            .setCategory(Notification.CATEGORY_CALL)
            // Colorized plus CallStyle is what promotes it to the ringing treatment.
            .setColorized(true)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setOngoing(true)
            .setAutoCancel(false)
            .setContentIntent(contentIntent)
            .setCallFullScreenIntent(this, callActivityIntent(1))
            .build()
    }

    private fun buildOngoingCallNotification(
        phase: CallPhase,
        remoteNumber: String,
        contentIntent: PendingIntent,
    ): Notification {
        val title = when (phase) {
            CallPhase.Dialing -> getString(R.string.call_state_dialing)
            CallPhase.Ringing -> getString(R.string.call_state_ringing)
            else -> getString(R.string.gv_ongoing_call)
        }
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(remoteNumber)
            .setSmallIcon(android.R.drawable.sym_action_call)
            .setCategory(Notification.CATEGORY_CALL)
            .setOngoing(true)
            .setContentIntent(contentIntent)
            .addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                getString(R.string.call_end),
                serviceActionIntent(ACTION_HANGUP, HANGUP_REQUEST_CODE),
            )
            .build()
    }

    private fun serviceActionIntent(action: String, requestCode: Int): PendingIntent = PendingIntent.getService(
        this,
        requestCode,
        Intent(this, GoogleVoiceCallForegroundService::class.java).apply { this.action = action },
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun callActivityIntent(requestCode: Int): PendingIntent = PendingIntent.getActivity(
        this,
        requestCode,
        Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        },
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun stopSelfSafely() {
        CallRinger.stop(RingLine.GoogleVoice)
        androidx.core.app.ServiceCompat.stopForeground(
            this, androidx.core.app.ServiceCompat.STOP_FOREGROUND_REMOVE,
        )
        stopSelf()
    }

    override fun onDestroy() {
        CallRinger.stop(RingLine.GoogleVoice)
        observeJob?.cancel()
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL_ID = "gv_calls"
        // Retired pre-ringer channel: the system keeps an existing channel's sound, so muting it
        // in place would never take effect — the renamed channel below replaces it and this deletes it.
        private const val LEGACY_INCOMING_CHANNEL_ID = "gv_calls_incoming"
        private const val INCOMING_CHANNEL_ID = "gv_calls_incoming_v2"
        private const val NOTIFICATION_ID = 4711
        private const val ACTION_ANSWER = "com.vayunmathur.communicate.googlevoice.ANSWER_CALL"
        private const val ACTION_HANGUP = "com.vayunmathur.communicate.googlevoice.HANGUP_CALL"
        private const val HANGUP_REQUEST_CODE = 3

        fun start(context: Context) {
            ContextCompat.startForegroundService(
                context, Intent(context, GoogleVoiceCallForegroundService::class.java),
            )
        }
    }
}
