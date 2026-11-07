package com.vayunmathur.clock.platform

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.vayunmathur.clock.data.ClockRepository
import com.vayunmathur.clock.ui.components.sendTimerNotification
import com.vayunmathur.library.log.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlin.time.Clock
import kotlin.time.Duration

/**
 * Re-arms every enabled alarm whenever the instants AlarmManager holds stop
 * meaning what the database says: after a reboot (AlarmManager is wiped),
 * and after the timezone or the wall clock changes (AlarmManager entries are
 * absolute epoch millis, so a 07:00 alarm computed in the old zone would
 * otherwise fire at 07:00-old-zone in the new one). Timers need no such
 * handling for time changes — their end is a duration from a stored instant,
 * which a zone change does not move.
 */
class BootReceiver : BroadcastReceiver() {
    // Broad catch is deliberate: onReceive must not throw, and Room throws
    // undocumented RuntimeExceptions (not just SQLiteException).
    @Suppress("TooGenericExceptionCaught")
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_LOCKED_BOOT_COMPLETED,
            Intent.ACTION_TIMEZONE_CHANGED,
            Intent.ACTION_TIME_CHANGED,
            "android.intent.action.QUICKBOOT_POWERON" -> {
                val repository = ClockRepository.get(context)
                val pendingResult = goAsync()
                CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
                    try {
                        val alarms = repository.getAllAlarms()
                        var rings = 0
                        var snoozes = 0
                        val now = Clock.System.now()
                        alarms.filter { it.enabled }.forEach { alarm ->
                            AlarmScheduler.schedule(context, alarm)
                            rings++
                            // Snoozes live in AlarmManager, which does not survive a
                            // reboot; the trigger time is persisted on the alarm, so
                            // re-arm those still in the future. Spent ones just clear.
                            val snoozedUntil = alarm.snoozedUntil
                            if (snoozedUntil != null) {
                                if (snoozedUntil > now) {
                                    AlarmScheduler.scheduleSnooze(
                                        context,
                                        repository,
                                        alarm,
                                        // scheduleSnooze recomputes from now; pass the
                                        // remaining whole minutes, at least one.
                                        ((snoozedUntil - now).inWholeMinutes).toInt()
                                            .coerceAtLeast(1),
                                    )
                                    snoozes++
                                } else {
                                    repository.upsertAlarm(alarm.copy(snoozedUntil = null))
                                }
                            }
                        }
                        // Running timers are AlarmManager entries too, so a reboot
                        // kills them exactly like alarms; nothing else ever re-armed
                        // them, and the row would sit at isRunning=true counting
                        // into negative time with no finish notification. Re-arm
                        // those still in the future and complete those that
                        // expired while the device was off. (On a timezone/clock
                        // change this same pass is a no-op repost: a timer's end
                        // is a duration from a stored instant, which neither
                        // change moves.)
                        var timersRearmed = 0
                        var timersCompleted = 0
                        repository.getAllTimers().filter { it.isRunning }.forEach { timer ->
                            // One bad timer must not take the others down with it.
                            runCatching {
                                if (timerRemaining(timer, now) > Duration.ZERO) {
                                    sendTimerNotification(context, timer, true)
                                    timersRearmed++
                                } else {
                                    repository.upsertTimer(
                                        timer.copy(
                                            isRunning = false,
                                            remainingLength = Duration.ZERO,
                                            remainingStartTime = now,
                                        )
                                    )
                                    // TimerReceiver posts the "time's up"
                                    // notification and re-writes the completed
                                    // row. It is not direct-boot aware, so at
                                    // LOCKED_BOOT_COMPLETED this broadcast may
                                    // go nowhere; the completed row above is
                                    // what the timer list then shows.
                                    context.sendBroadcast(
                                        Intent(context, TimerReceiver::class.java).apply {
                                            putExtra("timer_id", timer.id)
                                            putExtra("timer_name", timer.name)
                                        }
                                    )
                                    timersCompleted++
                                }
                            }.onFailure {
                                Log.error(TAG, "Timer ${timer.id}: could not restore after ${intent.action}", it)
                            }
                        }
                        Log.status(
                            TAG,
                            "${intent.action}: rescheduled $rings alarm(s) and $snoozes snooze(s); " +
                                "rearmed $timersRearmed timer(s), completed $timersCompleted",
                        )
                    } catch (e: Exception) {
                        // Not expected at either action: ClockRepository is built with
                        // useDeviceProtectedStorage = true precisely so the alarm database is
                        // readable while the device is still locked. A failure here means no
                        // alarm survives the reboot.
                        Log.error(TAG, "${intent.action}: could not reschedule alarms", e)
                    } finally {
                        pendingResult.finish()
                    }
                }
            }
        }
    }

    private companion object {
        const val TAG = "ClockBootReceiver"
    }
}
