package com.vayunmathur.clock.ui.components

import android.app.AlarmManager
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.vayunmathur.clock.MainActivity
import com.vayunmathur.clock.R
import com.vayunmathur.library.ui.R as UiR
import com.vayunmathur.clock.data.Timer
import com.vayunmathur.clock.platform.TimerReceiver
import com.vayunmathur.library.log.Log
import kotlin.time.Clock

fun sendTimerNotification(context: Context, timer: Timer, isStarting: Boolean) {
    val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
    val notificationId = timer.id.hashCode()

    val alarmIntent = Intent(context, TimerReceiver::class.java).apply {
        putExtra("timer_id", timer.id)
        putExtra("timer_name", timer.name)
    }

    val pendingAlarm = PendingIntent.getBroadcast(
        context, notificationId, alarmIntent,
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    if (!isStarting) {
        nm.cancel(notificationId)
        am.cancel(pendingAlarm)
        return
    }

    val remaining = if (timer.isRunning) timer.remainingLength - (Clock.System.now() - timer.remainingStartTime) else timer.remainingLength
    val endTimestamp = System.currentTimeMillis() + remaining.inWholeMilliseconds

    val pauseIntent = PendingIntent.getBroadcast(context, notificationId + 1, Intent(context, com.vayunmathur.clock.platform.TimerActionReceiver::class.java).apply { action = com.vayunmathur.clock.platform.TimerActionReceiver.ACTION_PAUSE; putExtra("timer_id", timer.id) }, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    val resumeIntent = PendingIntent.getBroadcast(context, notificationId + 2, Intent(context, com.vayunmathur.clock.platform.TimerActionReceiver::class.java).apply { action = com.vayunmathur.clock.platform.TimerActionReceiver.ACTION_RESUME; putExtra("timer_id", timer.id) }, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    val cancelIntent = PendingIntent.getBroadcast(context, notificationId + 3, Intent(context, com.vayunmathur.clock.platform.TimerActionReceiver::class.java).apply { action = com.vayunmathur.clock.platform.TimerActionReceiver.ACTION_CANCEL; putExtra("timer_id", timer.id) }, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    val resetIntent = PendingIntent.getBroadcast(context, notificationId + 4, Intent(context, com.vayunmathur.clock.platform.TimerActionReceiver::class.java).apply { action = com.vayunmathur.clock.platform.TimerActionReceiver.ACTION_RESET; putExtra("timer_id", timer.id) }, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

    val contentIntent = PendingIntent.getActivity(context, notificationId + 5, Intent(context, MainActivity::class.java).apply { flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP }, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

    val builder = NotificationCompat.Builder(context, "active_timers_channel")
        .setSmallIcon(R.drawable.outline_timer_24)
        .setContentTitle(timer.name.ifBlank { context.getString(R.string.label_timer) })
        .setUsesChronometer(true)
        .setChronometerCountDown(true)
        .setWhen(endTimestamp)
        .setOngoing(true)
        .setCategory(NotificationCompat.CATEGORY_ALARM)
        .setContentIntent(contentIntent)
        .setOnlyAlertOnce(true)

    if (timer.isRunning) builder.addAction(R.drawable.ic_pause_24, context.getString(R.string.action_pause), pauseIntent)
    else builder.addAction(R.drawable.ic_play_24, context.getString(R.string.action_resume), resumeIntent)
    builder.addAction(R.drawable.ic_cancel_24, context.getString(UiR.string.cancel), cancelIntent)
    builder.addAction(R.drawable.ic_reset_24, context.getString(UiR.string.reset), resetIntent)

    nm.notify(notificationId, builder.build())
    try {
        am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, endTimestamp, pendingAlarm)
    } catch (e: SecurityException) {
        // Exact-alarm special access revoked after the first-launch gate:
        // callers include Compose click handlers and the assistant-intent
        // path, where this throw crashed the app. An inexact while-idle
        // alarm still finishes the timer, just without exact timing.
        Log.error("TimerNotification", "Timer ${timer.id}: exact alarm denied; falling back to inexact", e)
        am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, endTimestamp, pendingAlarm)
    }
}
