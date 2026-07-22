package com.vayunmathur.clock.util

import android.app.AlarmManager
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.vayunmathur.clock.R
import com.vayunmathur.clock.data.ClockDatabase
import com.vayunmathur.clock.data.Timer
import com.vayunmathur.library.room.buildDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlin.time.Clock
import kotlin.time.Duration

class TimerActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        val timerId = intent.getLongExtra("timer_id", 0L)
        val pendingResult = goAsync()

        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                val db = context.buildDatabase<ClockDatabase>(useDeviceProtectedStorage = true)
                val timer = db.timerDao().get(timerId)
                val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
                val notificationId = timerId.hashCode()

                when (action) {
                    ACTION_PAUSE -> {
                        // Pause the timer: calculate remaining, update DB, cancel alarm and notification
                        val now = Clock.System.now()
                        val remaining = if (timer.isRunning) {
                            timer.remainingLength - (now - timer.remainingStartTime)
                        } else {
                            timer.remainingLength
                        }
                        val pausedTimer = timer.copy(isRunning = false, remainingLength = remaining.coerceAtLeast(Duration.ZERO))
                        db.timerDao().upsert(pausedTimer)
                        
                        // Cancel alarm
                        val alarmIntent = Intent(context, TimerReceiver::class.java).apply {
                            putExtra("timer_id", timerId)
                            putExtra("timer_name", timer.name)
                        }
                        val pendingAlarm = PendingIntent.getBroadcast(
                            context, notificationId, alarmIntent,
                            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                        )
                        am.cancel(pendingAlarm)
                        nm.cancel(notificationId)
                    }
                    ACTION_RESUME -> {
                        // Resume the timer: update DB with new start time, reschedule notification
                        val resumedTimer = timer.copy(isRunning = true, remainingStartTime = Clock.System.now())
                        db.timerDao().upsert(resumedTimer)
                        com.vayunmathur.clock.ui.sendTimerNotification(context, resumedTimer, true)
                    }
                    ACTION_CANCEL -> {
                        // Delete timer, cancel alarm and notification
                        db.timerDao().delete(timer)
                        val alarmIntent = Intent(context, TimerReceiver::class.java).apply {
                            putExtra("timer_id", timerId)
                            putExtra("timer_name", timer.name)
                        }
                        val pendingAlarm = PendingIntent.getBroadcast(
                            context, notificationId, alarmIntent,
                            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                        )
                        am.cancel(pendingAlarm)
                        nm.cancel(notificationId)
                    }
                    ACTION_RESET -> {
                        // Reset timer to original total length, pause it
                        val resetTimer = timer.copy(
                            isRunning = false,
                            remainingLength = timer.totalLength,
                            remainingStartTime = Clock.System.now()
                        )
                        db.timerDao().upsert(resetTimer)
                        
                        val alarmIntent = Intent(context, TimerReceiver::class.java).apply {
                            putExtra("timer_id", timerId)
                            putExtra("timer_name", timer.name)
                        }
                        val pendingAlarm = PendingIntent.getBroadcast(
                            context, notificationId, alarmIntent,
                            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                        )
                        am.cancel(pendingAlarm)
                        nm.cancel(notificationId)
                    }
                }
            } catch (_: Exception) {
            } finally {
                pendingResult.finish()
            }
        }
    }

    companion object {
        const val ACTION_PAUSE = "com.vayunmathur.clock.TIMER_PAUSE"
        const val ACTION_RESUME = "com.vayunmathur.clock.TIMER_RESUME"
        const val ACTION_CANCEL = "com.vayunmathur.clock.TIMER_CANCEL"
        const val ACTION_RESET = "com.vayunmathur.clock.TIMER_RESET"
    }
}