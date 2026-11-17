package com.vayunmathur.parentalcontrols.platform

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import com.vayunmathur.library.log.Log
import androidx.core.content.getSystemService
import com.vayunmathur.parentalcontrols.data.BedtimeSchedule
import com.vayunmathur.parentalcontrols.data.DowntimeSchedule
import com.vayunmathur.parentalcontrols.data.SchoolTimeSchedule
import com.vayunmathur.parentalcontrols.platform.BedtimeReceiver
import java.time.LocalDateTime
import java.time.ZoneId

private const val TAG = "ParentalControlsBedtime"

private const val MINUTES_PER_HOUR = 60

/**
 * Arms the next bedtime boundary.
 *
 * One alarm at a time, always for the next transition in either direction, re-armed each time it
 * fires. A pair of repeating daily alarms would be simpler and wrong: the window can be edited,
 * disabled, or apply to only some days, and a repeating alarm outlives all three.
 *
 * Exact alarms, because the whole feature is a promise about a specific minute. That needs
 * `SCHEDULE_EXACT_ALARM`, and on a device where it has been revoked [arm] degrades to an inexact
 * alarm rather than throwing - late enforcement beats none.
 */
class BedtimeScheduler(private val context: Context) {

    private val alarms = context.getSystemService<AlarmManager>()

    fun arm(schedule: BedtimeSchedule) {
        armWindow(schedule.enabled, schedule::activeAt, REQUEST_BEDTIME)
    }

    /**
     * Arms the next boundary of every supervised window.
     *
     * One alarm per window type, each for its next transition in either direction, re-armed
     * every time any of them fires (all receivers funnel through `Enforcer.reconcile`, which
     * calls here). Separate request codes keep the three alarms from clobbering each other.
     */
    fun armAll(
        bedtime: BedtimeSchedule,
        downtime: DowntimeSchedule,
        schoolTime: SchoolTimeSchedule,
    ) {
        armWindow(bedtime.enabled, bedtime::activeAt, REQUEST_BEDTIME)
        armWindow(downtime.enabled, downtime::activeAt, REQUEST_DOWNTIME)
        armWindow(schoolTime.enabled, schoolTime::activeAt, REQUEST_SCHOOLTIME)
    }

    private fun armWindow(
        enabled: Boolean,
        activeAt: (LocalDateTime) -> Boolean,
        requestCode: Int,
    ) {
        val alarms = alarms ?: return
        val pending = pendingIntent(requestCode)
        alarms.cancel(pending)
        if (!enabled) return

        val next = nextBoundary(activeAt) ?: return
        val at = next.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val canExact = alarms.canScheduleExactAlarms()
        runCatching {
            if (canExact) {
                alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pending)
            } else {
                Log.status(TAG, "no exact-alarm permission; window boundary will be approximate")
                alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pending)
            }
        }.onFailure { Log.status(TAG, "could not arm the window alarm", it) }
    }

    /**
     * The next minute at which the window's state changes, searched forward a week.
     *
     * Evaluating the window predicate minute by minute rather than deriving the boundary
     * arithmetically is deliberate: the day mask, the midnight wrap and the "evening belongs to
     * today, morning to yesterday" rule interact, and one predicate that the enforcer also uses
     * cannot disagree with itself. A week of minutes is 10,080 cheap comparisons, run at most
     * a few times a day.
     */
    private fun nextBoundary(activeAt: (LocalDateTime) -> Boolean): LocalDateTime? {
        val now = LocalDateTime.now().withSecond(0).withNano(0)
        var state = activeAt(now)
        for (step in 1..MINUTES_IN_WEEK) {
            val at = now.plusMinutes(step.toLong())
            val next = activeAt(at)
            if (next != state) return at
            state = next
        }
        return null
    }

    private fun pendingIntent(requestCode: Int): PendingIntent = PendingIntent.getBroadcast(
        context,
        requestCode,
        BedtimeReceiver.intent(context),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private companion object {
        const val REQUEST_BEDTIME = 1
        const val REQUEST_DOWNTIME = 2
        const val REQUEST_SCHOOLTIME = 3
        const val MINUTES_IN_WEEK = 7 * 24 * 60
    }
}

/** Whether the window is open at [at], in the device's current timezone. */
fun BedtimeSchedule.activeAt(at: LocalDateTime): Boolean =
    contains(at.dayOfWeek.value - 1, at.hour * MINUTES_PER_HOUR + at.minute)

/** Whether the window is open at [at], in the device's current timezone. */
fun DowntimeSchedule.activeAt(at: LocalDateTime): Boolean =
    contains(at.dayOfWeek.value - 1, at.hour * MINUTES_PER_HOUR + at.minute)

/** Whether the window is open at [at], in the device's current timezone. */
fun SchoolTimeSchedule.activeAt(at: LocalDateTime): Boolean =
    contains(at.dayOfWeek.value - 1, at.hour * MINUTES_PER_HOUR + at.minute)
