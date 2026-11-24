package com.vayunmathur.clock.wear.platform

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import com.vayunmathur.clock.wear.domain.WatchTimer
import com.vayunmathur.clock.wear.domain.watchTimerEndWallMillis
import com.vayunmathur.library.log.Log

/**
 * Arms watch-native timers with the watch's AlarmManager: elapsed-realtime
 * while the monotonic anchor is still usable, wall-clock after a reboot.
 * A timer that is not scheduled here does not expire — the UI start path
 * only marks a timer running after [schedule] accepted it, and says so
 * when it could not.
 */
object WatchTimerScheduler {
    private const val TAG = "WatchTimerScheduler"
    const val EXTRA_TIMER_ID = "timer_id"
    const val EXTRA_LABEL = "timer_label"

    /** Schedule [timer]'s expiry; false when AlarmManager refused both forms. */
    fun schedule(context: Context, timer: WatchTimer): Boolean {
        val manager = context.getSystemService(AlarmManager::class.java) ?: return false
        val pending = pendingIntent(context, timer, create = true) ?: return false
        val elapsedNow = SystemClock.elapsedRealtime()
        val startElapsed = timer.startElapsedMillis
        return if (startElapsed != null && elapsedNow >= startElapsed) {
            val triggerElapsed = startElapsed + timer.remainingMillis
            try {
                manager.setExactAndAllowWhileIdle(
                    AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerElapsed, pending,
                )
                true
            } catch (e: SecurityException) {
                Log.status(TAG, "exact timer ${timer.id} denied, falling back: ${e.message}")
                runCatching {
                    manager.setAndAllowWhileIdle(
                        AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerElapsed, pending,
                    )
                    true
                }.getOrDefault(false)
            }
        } else {
            val triggerWall = watchTimerEndWallMillis(timer)
            try {
                manager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerWall, pending)
                true
            } catch (e: SecurityException) {
                Log.status(TAG, "exact timer ${timer.id} denied, falling back: ${e.message}")
                runCatching {
                    manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerWall, pending)
                    true
                }.getOrDefault(false)
            }
        }
    }

    /** Cancel [timerId]'s expiry schedule. */
    fun cancel(context: Context, timerId: Long) {
        val manager = context.getSystemService(AlarmManager::class.java) ?: return
        pendingIntent(
            context, WatchTimer(id = timerId, totalMillis = 0, remainingMillis = 0), create = false,
        )?.let {
            manager.cancel(it)
            it.cancel()
        }
    }

    private fun pendingIntent(context: Context, timer: WatchTimer, create: Boolean): PendingIntent? {
        val intent = Intent(context, WatchTimerReceiver::class.java).apply {
            putExtra(EXTRA_TIMER_ID, timer.id)
            putExtra(EXTRA_LABEL, timer.label)
        }
        return PendingIntent.getBroadcast(
            context, timer.id.toInt(), intent,
            if (create) {
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            } else {
                PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
            },
        )
    }
}
