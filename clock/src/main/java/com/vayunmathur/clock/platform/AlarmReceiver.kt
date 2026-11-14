package com.vayunmathur.clock.platform
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ActivityNotFoundException
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import com.vayunmathur.clock.R
import com.vayunmathur.clock.data.ClockRepository
import com.vayunmathur.clock.service.AlarmSoundService
import com.vayunmathur.library.log.Log
import com.vayunmathur.library.ui.RINGTONE_SILENT
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Receives a fired alarm, posts the insistent ring notification, starts the sound service,
 * and re-arms repeating alarms.
 */
class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        createNotificationChannels(context)
        val alarmId = intent.getLongExtra("ALARM_ID", -1L)
        if (alarmId == -1L) {
            Log.error(TAG, "Alarm fired without an id; ignoring")
            return
        }
        val isSnooze = intent.getBooleanExtra(AlarmScheduler.EXTRA_IS_SNOOZE, false)
        Log.status(TAG, "Alarm $alarmId fired (snooze=$isSnooze)")

        // 1. Create the Intent for your "Ringing" Activity
        val ringIntent = Intent(context, AlarmActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra("ALARM_ID", alarmId)
        }

        // 2. Wrap it in a PendingIntent
        val pendingIntent = PendingIntent.getActivity(
            context,
            alarmId.toInt(),
            ringIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // Only one alarm can ring at a time: retire whatever the previous ring left
        // behind (stale notification, second service start) before posting our own.
        stopPreviousRing(context)

        postRingNotification(context, intent, pendingIntent)
        rescheduleAlarm(context, alarmId, isSnooze)
        startSoundService(context, alarmId)
        startRingActivity(context, alarmId, ringIntent)
    }

    private fun postRingNotification(
        context: Context,
        intent: Intent,
        pendingIntent: PendingIntent,
    ) {
        // 3. Build the Notification. For most alarms this is the backstop: FLAG_INSISTENT
        // below repeats the ring channel's sound until something cancels it, so the alarm sounds
        // even when AlarmSoundService never reaches startForeground.
        //
        // Gradual-volume alarms normally opt out. The ring channel's sound is fixed and plays at
        // full volume, so they would blast, cut to silence and then fade in from zero - the
        // opposite of the setting. That trade is only worth it when the service is likely to
        // take over: when its start was denied (or the fire carries no ringtone), an insistent
        // notification is the only sound there will ever be, so ring anyway.
        val gradual = intent.getIntExtra(AlarmScheduler.EXTRA_GRADUAL_SECONDS, 0) > 0
        val silent = intent.getStringExtra(AlarmScheduler.EXTRA_RINGTONE_URI) == RINGTONE_SILENT
        val canStartService = canStartForegroundService(context)
        // The label rides in the broadcast (like gradual seconds) so the ring shows it
        // without a database read on the latency-sensitive fire path.
        val label = intent.getStringExtra(AlarmScheduler.EXTRA_LABEL).orEmpty()
        val builder = NotificationCompat.Builder(
            context,
            if (gradual && canStartService && !silent) ALARM_CHANNEL_ID else ALARM_RING_CHANNEL_ID,
        )
            .setSmallIcon(R.drawable.baseline_access_alarm_24)
            .setContentTitle(context.getString(R.string.label_alarm))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            // This is the key: it launches the activity automatically if the phone is locked
            .setFullScreenIntent(pendingIntent, true)
            // Without a content intent, tapping the notification does nothing and autoCancel
            // never fires, so the shade offers no way to stop the ring but a swipe.
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            // Backstop: an insistent notification rings forever, so bound it in case every
            // dismiss path is missed (process killed before the UI ever appeared, say).
            .setTimeoutAfter(RING_TIMEOUT_MS)

        // Without the full-screen permission the ringing screen can only appear if the
        // background activity start below happens to be allowed, which off-screen it is
        // not. Say so on the notification itself: a log line is invisible to the user
        // sleeping through the alarm they thought they set.
        if (!canUseFullScreenIntent(context)) {
            builder.setContentText(
                label.ifBlank { context.getString(R.string.alarm_missing_fullscreen_text) }
            )
        } else if (label.isNotBlank()) {
            // Show the alarm's own label; omit the line entirely when it is empty.
            builder.setContentText(label)
        }

        val notificationManager =
            context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(
            ALARM_RING_NOTIFICATION_ID,
            builder.build().apply {
                if (!gradual || !canStartService || silent) {
                    flags = flags or Notification.FLAG_INSISTENT
                }
            },
        )
    }

    private fun stopPreviousRing(context: Context) {
        // The service owns the sound once it is up; a second fire while it is still
        // playing used to leave both alarms ringing on top of each other.
        context.stopService(Intent(context, AlarmSoundService::class.java))
        (context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
            .cancel(ALARM_RING_NOTIFICATION_ID)
    }

    private fun canUseFullScreenIntent(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return true
        val notificationManager =
            context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        return notificationManager.canUseFullScreenIntent()
    }

    private fun canStartForegroundService(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true
        return (context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
            .areNotificationsEnabled()
    }

    // Broad catch is deliberate: onReceive must not throw, and Room plus the
    // scheduler throw undocumented RuntimeExceptions (not just SQLiteException).
    @Suppress("TooGenericExceptionCaught")
    private fun rescheduleAlarm(context: Context, alarmId: Long, isSnooze: Boolean) {
        val repository = ClockRepository.get(context)
        val pendingResult = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                val alarm = repository.getAlarm(alarmId)
                if (isSnooze) {
                    // The snooze that just fired is spent; the alarm's own schedule was
                    // never touched (it lives on its own request code), so just clear it.
                    if (alarm.snoozedUntil != null) {
                        repository.upsertAlarm(alarm.copy(snoozedUntil = null))
                    }
                } else {
                    if (alarm.days == 0) {
                        repository.upsertAlarm(alarm.copy(enabled = false))
                    } else {
                        AlarmScheduler.schedule(context, alarm)
                    }
                }
            } catch (e: Exception) {
                Log.error(TAG, "Alarm $alarmId: could not reschedule", e)
            } finally {
                pendingResult.finish()
            }
        }
    }

    private fun startSoundService(context: Context, alarmId: Long) {
        // 4. Start the Sound Service immediately so we hear it even if Activity doesn't launch
        val serviceIntent = Intent(context, AlarmSoundService::class.java).apply {
            putExtra("ALARM_ID", alarmId)
        }
        try {
            context.startForegroundService(serviceIntent)
        } catch (e: SecurityException) {
            // Losing this used to throw out of onReceive, which took the notification's
            // rescheduling and the activity start below down with it.
            Log.error(TAG, "Alarm $alarmId: could not start AlarmSoundService", e)
        } catch (e: IllegalStateException) {
            Log.error(TAG, "Alarm $alarmId: could not start AlarmSoundService", e)
        }
    }

    private fun startRingActivity(context: Context, alarmId: Long, ringIntent: Intent) {
        // 5. Try to start the activity explicitly (useful if screen is already on)
        try {
            context.startActivity(ringIntent)
        } catch (e: SecurityException) {
            Log.status(
                TAG,
                "Alarm $alarmId: background activity start refused; relying on full-screen intent",
                e,
            )
        } catch (e: ActivityNotFoundException) {
            Log.status(
                TAG,
                "Alarm $alarmId: background activity start refused; relying on full-screen intent",
                e,
            )
        }
    }

    private companion object {
        const val TAG = "AlarmReceiver"

        /** How long the insistent notification may ring unattended before the system drops it. */
        const val RING_TIMEOUT_MS = 10 * 60 * 1000L
    }
}
