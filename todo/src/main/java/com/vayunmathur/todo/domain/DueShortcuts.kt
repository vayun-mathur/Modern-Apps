package com.vayunmathur.todo.domain

import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.TimeZone
import kotlinx.datetime.isoDayNumber
import kotlinx.datetime.plus

/** One-tap due dates offered by the editor and the selection bar. */
enum class DueShortcuts {
    TODAY, TOMORROW, NEXT_WEEK, NONE;

    /** Local midnight of the target day, or null for [NONE]. */
    fun dueAt(now: Long = System.currentTimeMillis(), zone: TimeZone = TimeZone.currentSystemDefault()): Long? {
        val today = DueDates.localDate(now, zone)
        val date = when (this) {
            TODAY -> today
            TOMORROW -> today.plus(1, DateTimeUnit.DAY)
            NEXT_WEEK -> today.plus(DayOfWeek.SUNDAY.isoDayNumber - today.dayOfWeek.isoDayNumber + 1, DateTimeUnit.DAY)
            NONE -> return null
        }
        return DueDates.startOfDay(date, zone)
    }
}
