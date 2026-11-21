package com.vayunmathur.clock.platform

import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.vayunmathur.library.util.DataStoreUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/** Handles stopwatch notification actions (toggle, lap, stop) against DataStore-backed stopwatch state. */
class StopwatchActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        val pendingResult = goAsync()
        val ds = DataStoreUtils.getInstance(context)

        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                when (action) {
                    ACTION_TOGGLE -> {
                        val isRunning = ds.getBoolean(KEY_STOPWATCH_RUNNING, false)
                        val totalMs = ds.getLong(KEY_STOPWATCH_TOTAL) ?: 0L
                        val startMs = ds.getLong(KEY_STOPWATCH_START) ?: 0L
                        val startMonoMs = (ds.getLong(KEY_STOPWATCH_START_MONO) ?: 0L).takeIf { it > 0L }

                        if (isRunning) {
                            // Pause: add elapsed time to total (dual base)
                            val elapsed = DualTimeBase.elapsedMs(
                                startMs, startMonoMs,
                                Clock.System.now().toEpochMilliseconds(),
                                DualTimeBase.elapsedRealtimeMs(),
                            )
                            ds.setLong(KEY_STOPWATCH_TOTAL, totalMs + elapsed)
                            ds.setBoolean(KEY_STOPWATCH_RUNNING, false)
                        } else {
                            // Resume: set both anchors to now
                            ds.setLong(KEY_STOPWATCH_START, Clock.System.now().toEpochMilliseconds())
                            ds.setLong(KEY_STOPWATCH_START_MONO, DualTimeBase.elapsedRealtimeMs())
                            ds.setBoolean(KEY_STOPWATCH_RUNNING, true)
                        }
                        // Update notification
                        StopwatchNotificationHelper.updateNotification(context)
                    }
                    ACTION_LAP -> {
                        val isRunning = ds.getBoolean(KEY_STOPWATCH_RUNNING, false)
                        if (!isRunning) return@launch

                        val totalMs = ds.getLong(KEY_STOPWATCH_TOTAL) ?: 0L
                        val startMs = ds.getLong(KEY_STOPWATCH_START) ?: 0L
                        val startMonoMs = (ds.getLong(KEY_STOPWATCH_START_MONO) ?: 0L).takeIf { it > 0L }
                        val currentTotal = DualTimeBase.stopwatchElapsedMs(
                            totalMs, startMs, startMonoMs,
                            Clock.System.now().toEpochMilliseconds(),
                            DualTimeBase.elapsedRealtimeMs(),
                        )

                        val lapsStr = ds.getString(KEY_STOPWATCH_LAPS) ?: ""
                        val newLapsStr = if (lapsStr.isEmpty()) currentTotal.toString() else "$lapsStr,$currentTotal"
                        ds.setString(KEY_STOPWATCH_LAPS, newLapsStr)
                        
                        StopwatchNotificationHelper.updateNotification(context)
                    }
                    ACTION_STOP -> {
                        ds.setBoolean(KEY_STOPWATCH_RUNNING, false)
                        ds.setLong(KEY_STOPWATCH_TOTAL, 0L)
                        ds.setString(KEY_STOPWATCH_LAPS, "")
                        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                        nm.cancel(STOPWATCH_NOTIFICATION_ID)
                    }
                }
            } catch (_: Exception) {
            } finally {
                pendingResult.finish()
            }
        }
    }

        /** Notification action names, DataStore keys, and notification id for the stopwatch. */
    companion object {
        const val ACTION_TOGGLE = "com.vayunmathur.clock.STOPWATCH_TOGGLE"
        const val ACTION_LAP = "com.vayunmathur.clock.STOPWATCH_LAP"
        const val ACTION_STOP = "com.vayunmathur.clock.STOPWATCH_STOP"
        
        const val KEY_STOPWATCH_RUNNING = "stopwatch_running"
        const val KEY_STOPWATCH_TOTAL = "stopwatch_total_ms"
        const val KEY_STOPWATCH_START = "stopwatch_start_ms"
        /** Monotonic (`elapsedRealtime`) anchor paired with [KEY_STOPWATCH_START]; 0 = none stored. */
        const val KEY_STOPWATCH_START_MONO = "stopwatch_start_elapsed_realtime_ms"
        const val KEY_STOPWATCH_LAPS = "stopwatch_laps"
        const val STOPWATCH_NOTIFICATION_ID = 9001
    }
}
