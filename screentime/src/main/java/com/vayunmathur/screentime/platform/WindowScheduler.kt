package com.vayunmathur.screentime.platform

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import com.vayunmathur.library.log.Log
import androidx.core.content.getSystemService
import com.vayunmathur.screentime.data.WindDownSchedule
import com.vayunmathur.screentime.receiver.ScheduleReceiver
import java.time.LocalDateTime
import java.time.ZoneId

private const val TAG = "ScreenTimeScheduler"

/**
 * Arms the next wind-down boundary.
 *
 * One alarm for its next transition in either direction, re-armed every time it fires (all
 * receivers funnel through the coordinator, which calls back here).
 *
 * Same minute-scan predicate-sharing as parental controls' `BedtimeScheduler`: the boundary
 * search evaluates the exact `contains` predicate the enforcer uses, so the two cannot
 * disagree about when a window opens. Exact alarms - a schedule is a promise about a
 * specific minute - degrading to inexact when the permission is revoked.
 */
class WindowScheduler(private val context: Context) {

    private val alarms = context.getSystemService<AlarmManager>()

    fun armAll(windDown: WindDownSchedule) {
        armWindow(windDown.enabled, windDown::activeAt, REQUEST_WINDDOWN)
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
        ScheduleReceiver.intent(context),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private companion object {
        const val REQUEST_WINDDOWN = 12
        const val MINUTES_IN_WEEK = 7 * 24 * 60
    }
}

/** Whether wind-down is active at [at], in the device's current timezone. */
fun WindDownSchedule.activeAt(at: LocalDateTime): Boolean {
    if (!enabled) return false
    val minute = at.hour * MINUTES_PER_HOUR + at.minute
    val day = at.dayOfWeek.value - 1
    val set = (daysMask shr day) and 1 == 1
    return if (startMinute <= endMinute) {
        set && minute >= startMinute && minute < endMinute
    } else {
        // Overnight: the evening half belongs to today's bit, the morning tail to
        // yesterday's - so "Mon 22:00-06:00" still applies at 02:00 on Tuesday.
        // Same predicate as parental controls' TimeWindow.contains.
        (set && minute >= startMinute) || (minute < endMinute && isYesterdaySet(day))
    }
}

private fun WindDownSchedule.isYesterdaySet(day: Int): Boolean {
    val yesterday = (day + DAYS_PER_WEEK - 1) % DAYS_PER_WEEK
    return (daysMask shr yesterday) and 1 == 1
}

private const val MINUTES_PER_HOUR = 60
private const val DAYS_PER_WEEK = 7
