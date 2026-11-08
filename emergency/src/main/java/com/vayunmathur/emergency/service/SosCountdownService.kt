package com.vayunmathur.emergency.service

import android.app.AlarmManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.IBinder
import android.os.VibrationEffect
import android.os.Vibrator
import android.telecom.TelecomManager
import com.vayunmathur.library.log.Log
import androidx.core.content.getSystemService
import com.vayunmathur.emergency.notifications.SosNotifications
import com.vayunmathur.emergency.platform.EmergencyNumberLookup
import com.vayunmathur.emergency.platform.GestureProvider
import com.vayunmathur.emergency.platform.SosSound
import com.vayunmathur.emergency.receiver.SosActionReceiver
import com.vayunmathur.emergency.ui.SosActivity

private const val TAG = "EmergencySosService"

/**
 * Counts down to the emergency call in the background.
 *
 * Mirrors GrapheneOS's `EmergencyActionForegroundService`: started when the SOS screen is
 * dismissed mid-countdown (or directly with remaining time), posts the ongoing countdown
 * notification, schedules the exact alarm that fires `MAKE_EMERGENCY_CALL`, and plays the
 * vibration waveform + warning sound. No telephony on the device (or an invalid remaining
 * time) stops the service immediately - there is nothing to count down to.
 */
class SosCountdownService : Service() {

    private var vibrator: Vibrator? = null
    private var sound: SosSound? = null

    override fun onCreate() {
        super.onCreate()
        vibrator = getSystemService()
        sound = SosSound(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val remainingMs = intent?.getLongExtra(EXTRA_REMAINING_MS, -1) ?: -1
        if (remainingMs <= 0) {
            Log.status(TAG, "invalid remaining countdown time; nothing to do")
            stopSelf()
            return START_NOT_STICKY
        }
        if (!packageManager.hasSystemFeature(PackageManager.FEATURE_TELEPHONY)) {
            Log.status(TAG, "no telephony on this device; nothing to do")
            stopSelf()
            return START_NOT_STICKY
        }
        // Touching TelecomManager here (instead of only in the receiver) fails fast when the
        // telecom stack is missing, the way GrapheneOS's onCreate null-check does.
        if (getSystemService<TelecomManager>() == null) {
            Log.status(TAG, "no TelecomManager; nothing to do")
            stopSelf()
            return START_NOT_STICKY
        }

        val number = EmergencyNumberLookup(this)
            .policeNumber(GestureProvider.numberOverrideStatic(this))
        // NotificationCompat's setWhen is a wall-clock (epoch) timestamp, and the
        // chronometer counts down to it; an elapsedRealtime value lands in 1970 and
        // the countdown row shows a meaningless time. Same clock as scheduleCall.
        val targetWallClock = System.currentTimeMillis() + remainingMs
        val contentIntent = PendingIntent.getActivity(
            this, 0, SosActivity.intentForDeadline(this, targetWallClock),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = SosNotifications.countdown(this, number, targetWallClock, contentIntent)
        SosNotifications.notify(this, notification)
        startForeground(SosNotifications.NOTIFICATION_ID, notification)

        scheduleCall(remainingMs)
        vibrator?.vibrate(VIBRATION_EFFECT)
        if (GestureProvider.isSoundEnabledStatic(this)) sound?.play()

        return START_NOT_STICKY
    }

    override fun onDestroy() {
        sound?.stop()
        vibrator?.cancel()
        SosNotifications.cancel(this)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun scheduleCall(remainingMs: Long) {
        val alarms = getSystemService<AlarmManager>() ?: return
        val at = System.currentTimeMillis() + remainingMs
        val pending = SosActionReceiver.makeCallPendingIntent(this)
        // SCHEDULE_EXACT_ALARM is revocable on modern Android. Dropping the call
        // when the exact grant is gone would fail the SOS silently - the user
        // watches the countdown end and no call is placed - so fall back to an
        // inexact wakeup alarm (the screentime/parentalcontrols pattern): a
        // slightly late call beats no call.
        val exact = runCatching { alarms.canScheduleExactAlarms() }.getOrDefault(false)
        if (exact) {
            runCatching {
                alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pending)
            }.onSuccess { return }
                .onFailure { Log.status(TAG, "exact SOS alarm refused; using inexact", it) }
        } else {
            Log.status(TAG, "no exact-alarm permission; SOS call will be approximate")
        }
        runCatching { alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pending) }
            .onFailure { Log.status(TAG, "could not schedule the SOS call alarm", it) }
    }

    companion object {
        private const val EXTRA_REMAINING_MS = "service.extra.remaining_time_ms"

        /** Alarm + vibration pattern from GrapheneOS's service (timings/amplitudes verbatim). */
        private val VIBRATION_EFFECT: VibrationEffect =
            VibrationEffect.createWaveform(
                longArrayOf(200, 20, 20, 20, 20, 100, 20, 600),
                intArrayOf(0, 39, 82, 139, 213, 0, 127, 0),
                -1,
            )

        /** Starts the background countdown with [remainingMs] left on the clock. */
        fun start(context: Context, remainingMs: Long) {
            val intent = Intent(context, SosCountdownService::class.java)
                .putExtra(EXTRA_REMAINING_MS, remainingMs)
            runCatching { context.startForegroundService(intent) }
                .onFailure { Log.status(TAG, "could not start the SOS countdown service", it) }
        }

        /** Cancels the scheduled call and stops the service. */
        fun stop(context: Context) {
            val app = context.applicationContext
            runCatching {
                app.getSystemService<AlarmManager>()
                    ?.cancel(SosActionReceiver.makeCallPendingIntent(app))
            }
            runCatching { app.stopService(Intent(app, SosCountdownService::class.java)) }
        }
    }
}
