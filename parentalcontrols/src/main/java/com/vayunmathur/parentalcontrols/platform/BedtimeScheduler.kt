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
import java.time.Duration
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZonedDateTime

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
    ) = armAll(bedtime, downtime, schoolTime, deviceLimitActive = false)

    /**
     * Arms every recurring trigger: the three window boundaries, the midnight reset and —
     * while a device-wide daily limit exists — a periodic budget check.
     *
     * The midnight alarm is not optional decoration. Per-app spent marks and usage
     * observers are day-scoped, but nothing else is guaranteed to run at midnight: without
     * it an app that spent its budget stayed blocked into the next day until some unrelated
     * trigger happened to reconcile. The periodic check is the device limit's only trigger
     * of its own (the platform has no device-total observer); it is inexact and re-armed by
     * every reconcile, so a spent device budget is noticed within one interval instead of
     * only at the next window boundary.
     */
    fun armAll(
        bedtime: BedtimeSchedule,
        downtime: DowntimeSchedule,
        schoolTime: SchoolTimeSchedule,
        deviceLimitActive: Boolean,
    ) {
        armWindow(bedtime.enabled, bedtime::activeAt, REQUEST_BEDTIME)
        armWindow(downtime.enabled, downtime::activeAt, REQUEST_DOWNTIME)
        armWindow(schoolTime.enabled, schoolTime::activeAt, REQUEST_SCHOOLTIME)
        armMidnight()
        armPeriodicCheck(deviceLimitActive)
    }

    private fun armMidnight() {
        val alarms = alarms ?: return
        val pending = pendingIntent(REQUEST_MIDNIGHT)
        alarms.cancel(pending)
        val nextMidnight = ZonedDateTime.now(ZoneId.systemDefault())
            .toLocalDate().plusDays(1).atStartOfDay(ZoneId.systemDefault())
            .plusSeconds(MIDNIGHT_GRACE_SECONDS)
        runCatching {
            if (alarms.canScheduleExactAlarms()) {
                alarms.setExactAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP, nextMidnight.toInstant().toEpochMilli(), pending,
                )
            } else {
                alarms.setAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP, nextMidnight.toInstant().toEpochMilli(), pending,
                )
            }
        }.onFailure { Log.status(TAG, "could not arm the midnight alarm", it) }
    }

    private fun armPeriodicCheck(enabled: Boolean) {
        val alarms = alarms ?: return
        val pending = periodicIntent()
        alarms.cancel(pending)
        if (!enabled) return
        val at = System.currentTimeMillis() + DEVICE_CHECK_INTERVAL_MINUTES * 60_000L
        // Deliberately inexact: an approximate quarter-hourly budget check is the honest
        // trade for not holding a wakeup all day. Doze may defer it further; observers and
        // window boundaries still enforce in the meantime.
        runCatching { alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pending) }
            .onFailure { Log.status(TAG, "could not arm the device-limit check", it) }
    }

    private fun periodicIntent(): PendingIntent = PendingIntent.getBroadcast(
        context,
        REQUEST_DEVICE_CHECK,
        BedtimeReceiver.periodicIntent(context),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private fun armWindow(
        enabled: Boolean,
        activeAt: (ZonedDateTime) -> Boolean,
        requestCode: Int,
    ) {
        val alarms = alarms ?: return
        val pending = pendingIntent(requestCode)
        alarms.cancel(pending)
        if (!enabled) return

        val next = nextBoundary(activeAt) ?: return
        val at = next.toInstant().toEpochMilli()
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
     *
     * The scan runs in instant space ([ZonedDateTime] stepped by [Duration]), not over naive
     * [LocalDateTime]. Stepping wall-clock minutes and only converting with `atZone` at the
     * end breaks across DST: a boundary inside the spring-forward gap names a local time that
     * never occurs and `atZone` shifts it by the gap (~1h), and a boundary in the fall-back
     * overlap resolves to only one of its two occurrences. Stepping real minutes evaluates
     * exactly the instants that will occur - the gap contributes no phantom minutes and the
     * overlap contributes both passes - so the armed instant is the true boundary.
     */
    private fun nextBoundary(activeAt: (ZonedDateTime) -> Boolean): ZonedDateTime? {
        val now = ZonedDateTime.now(ZoneId.systemDefault()).withSecond(0).withNano(0)
        var state = activeAt(now)
        for (step in 1..MINUTES_IN_WEEK) {
            val at = now.plus(Duration.ofMinutes(step.toLong()))
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
        const val REQUEST_MIDNIGHT = 4
        const val REQUEST_DEVICE_CHECK = 5
        const val MINUTES_IN_WEEK = 7 * 24 * 60
        const val MIDNIGHT_GRACE_SECONDS = 30L
        const val DEVICE_CHECK_INTERVAL_MINUTES = 15L
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

// ZonedDateTime overloads for the boundary scan: the predicate is defined on
// local wall fields, and a ZonedDateTime carries the offset that disambiguates
// them, so each instant in the scan (including both passes through a fall-back
// overlap) is judged by the wall time it actually displays.

/** Whether the window is open at [at]. */
fun BedtimeSchedule.activeAt(at: ZonedDateTime): Boolean = activeAt(at.toLocalDateTime())

/** Whether the window is open at [at]. */
fun DowntimeSchedule.activeAt(at: ZonedDateTime): Boolean = activeAt(at.toLocalDateTime())

/** Whether the window is open at [at]. */
fun SchoolTimeSchedule.activeAt(at: ZonedDateTime): Boolean = activeAt(at.toLocalDateTime())
