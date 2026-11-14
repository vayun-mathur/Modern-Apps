package com.vayunmathur.clock.platform

import android.app.AlarmManager
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.vayunmathur.library.log.Log
import androidx.core.app.NotificationCompat
import com.vayunmathur.clock.R
import com.vayunmathur.clock.data.ClockRepository
import com.vayunmathur.clock.data.Timer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlin.time.Clock
import kotlin.time.Duration

/** Handles timer notification actions (pause, resume, cancel, reset) against persisted timers. */
class TimerActionReceiver : BroadcastReceiver() {
    // Broad catch is deliberate: onReceive must not throw, and Room throws
    // undocumented RuntimeExceptions (not just SQLiteException).
    @Suppress("TooGenericExceptionCaught")
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        val timerId = intent.getLongExtra("timer_id", 0L)
        val pendingResult = goAsync()

        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                handleAction(context, action, timerId)
            } catch (e: Exception) {
                Log.error(TAG, "Timer $timerId: action $action failed", e)
            } finally {
                pendingResult.finish()
            }
        }
    }

    private suspend fun handleAction(context: Context, action: String, timerId: Long) {
        val repository = ClockRepository.get(context)
        val timer = repository.getTimer(timerId)
        when (action) {
            ACTION_PAUSE -> pauseTimer(context, repository, timer)
            ACTION_RESUME -> resumeTimer(context, repository, timer)
            ACTION_CANCEL -> cancelTimer(context, repository, timer)
            ACTION_RESET -> resetTimer(context, repository, timer)
        }
    }

    private suspend fun pauseTimer(
        context: Context,
        repository: ClockRepository,
        timer: Timer,
    ) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        // Pause the timer: calculate remaining, update DB, cancel alarm and notification
        val now = Clock.System.now()
        val remaining = if (timer.isRunning) {
            timer.remainingLength - (now - timer.remainingStartTime)
        } else {
            timer.remainingLength
        }
        val pausedTimer = timer.copy(
            isRunning = false,
            remainingLength = remaining.coerceAtLeast(Duration.ZERO),
        )
        repository.upsertTimer(pausedTimer)
        cancelAlarm(context, am, timer)
        nm.cancel(timer.id.hashCode())
    }

    private suspend fun resumeTimer(
        context: Context,
        repository: ClockRepository,
        timer: Timer,
    ) {
        // Resume the timer: update DB with new start time, reschedule notification
        val resumedTimer = timer.copy(isRunning = true, remainingStartTime = Clock.System.now())
        repository.upsertTimer(resumedTimer)
        com.vayunmathur.clock.ui.components.sendTimerNotification(
            context,
            resumedTimer,
            true,
        )
    }

    private suspend fun cancelTimer(
        context: Context,
        repository: ClockRepository,
        timer: Timer,
    ) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        // Delete timer, cancel alarm and notification
        repository.deleteTimer(timer)
        cancelAlarm(context, am, timer)
        nm.cancel(timer.id.hashCode())
    }

    private suspend fun resetTimer(
        context: Context,
        repository: ClockRepository,
        timer: Timer,
    ) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        // Reset timer to original total length, pause it
        val resetTimer = timer.copy(
            isRunning = false,
            remainingLength = timer.totalLength,
            remainingStartTime = Clock.System.now()
        )
        repository.upsertTimer(resetTimer)
        cancelAlarm(context, am, timer)
        nm.cancel(timer.id.hashCode())
    }

    private fun cancelAlarm(context: Context, am: AlarmManager, timer: Timer) {
        val alarmIntent = Intent(context, TimerReceiver::class.java).apply {
            putExtra("timer_id", timer.id)
            putExtra("timer_name", timer.name)
        }
        val pendingAlarm = PendingIntent.getBroadcast(
            context,
            timer.id.hashCode(),
            alarmIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        am.cancel(pendingAlarm)
    }

        /** Notification action names broadcast to this receiver. */
    companion object {
        const val ACTION_PAUSE = "com.vayunmathur.clock.TIMER_PAUSE"
        const val ACTION_RESUME = "com.vayunmathur.clock.TIMER_RESUME"
        const val ACTION_CANCEL = "com.vayunmathur.clock.TIMER_CANCEL"
        const val ACTION_RESET = "com.vayunmathur.clock.TIMER_RESET"
        private const val TAG = "TimerActionReceiver"
    }
}
