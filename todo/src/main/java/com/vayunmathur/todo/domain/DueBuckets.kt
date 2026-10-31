package com.vayunmathur.todo.domain

import com.vayunmathur.todo.data.Todo
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.TimeZone
import kotlinx.datetime.daysUntil
import kotlinx.datetime.plus

enum class DueBucket { OVERDUE, TODAY, TOMORROW, THIS_WEEK, LATER, NO_DATE }

/** Where a due value falls relative to now, in the user's zone. */
object DueBuckets {
    private const val WEEK_DAYS = 7

    /** Calendar days from today to the due date (negative when past). */
    fun dayDelta(dueAt: Long, now: Long, zone: TimeZone): Int =
        DueDates.localDate(now, zone).daysUntil(DueDates.localDate(dueAt, zone))

    fun isOverdue(todo: Todo, now: Long, zone: TimeZone): Boolean {
        val due = todo.dueAt ?: return false
        if (todo.done) return false
        return if (todo.dueAllDay) dayDelta(due, now, zone) < 0 else due < now
    }

    fun bucket(todo: Todo, now: Long, zone: TimeZone): DueBucket {
        val due = todo.dueAt ?: return DueBucket.NO_DATE
        if (isOverdue(todo, now, zone)) return DueBucket.OVERDUE
        val delta = dayDelta(due, now, zone)
        return when {
            delta <= 0 -> DueBucket.TODAY
            delta == 1 -> DueBucket.TOMORROW
            delta < WEEK_DAYS -> DueBucket.THIS_WEEK
            else -> DueBucket.LATER
        }
    }

    /** Due today or earlier (the Today view). */
    fun isDueByToday(todo: Todo, now: Long, zone: TimeZone): Boolean {
        val due = todo.dueAt ?: return false
        return dayDelta(due, now, zone) <= 0
    }

    fun endOfToday(now: Long, zone: TimeZone): Long =
        DueDates.startOfDay(DueDates.localDate(now, zone).plus(1, DateTimeUnit.DAY), zone)
}
