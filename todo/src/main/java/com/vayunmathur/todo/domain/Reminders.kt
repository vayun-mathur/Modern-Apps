package com.vayunmathur.todo.domain

import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.minus

/** Reminder times relative to a due value. All-day tasks remind at [ALL_DAY_TIME]. */
object Reminders {
    val ALL_DAY_TIME = LocalTime(9, 0)
    private const val MINUTE_MS = 60_000L

    enum class Offset(val minutes: Long) {
        AT_DUE(0), MINUTES_10(10), HOUR_1(60), DAY_1(24 * 60)
    }

    fun at(dueAt: Long, allDay: Boolean, offset: Offset, zone: TimeZone): Long {
        if (!allDay) return dueAt - offset.minutes * MINUTE_MS
        val date = DueDates.localDate(dueAt, zone)
        val day = if (offset == Offset.DAY_1) date.minus(1, DateTimeUnit.DAY) else date
        val base = DueDates.at(day, ALL_DAY_TIME, zone)
        return if (offset == Offset.DAY_1) base else base - offset.minutes * MINUTE_MS
    }
}
