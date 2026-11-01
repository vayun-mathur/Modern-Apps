package com.vayunmathur.todo.domain

import com.vayunmathur.todo.data.Todo
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.minus

/** Reminder times relative to a due value. All-day tasks remind at [ALL_DAY_TIME]. */
object Reminders {
    val ALL_DAY_TIME = LocalTime(9, 0)
    private const val MINUTE_MS = 60_000L
    private const val DAY_MS = 86_400_000L
    private const val MAX_OCCURRENCES = 400

    enum class Offset(val minutes: Long) {
        AT_DUE(0), MINUTES_10(10), HOUR_1(60), DAY_1(24 * 60)
    }

    /** Offsets that make sense for the due kind: minutes-before an all-day task is meaningless. */
    fun offsetsFor(allDay: Boolean): List<Offset> =
        if (allDay) listOf(Offset.AT_DUE, Offset.DAY_1) else Offset.entries

    fun at(dueAt: Long, allDay: Boolean, offset: Offset, zone: TimeZone): Long {
        if (!allDay) return dueAt - offset.minutes * MINUTE_MS
        val date = DueDates.localDate(dueAt, zone)
        val day = if (offset == Offset.DAY_1) date.minus(1, DateTimeUnit.DAY) else date
        val base = DueDates.at(day, ALL_DAY_TIME, zone)
        return if (offset == Offset.DAY_1) base else base - offset.minutes * MINUTE_MS
    }

    /**
     * When [todo]'s reminder should next go off, or null for never. A one-time reminder fires
     * once at its `reminderAt`. A repeating task keeps the same lead before every later
     * occurrence, so a daily task left unchecked still reminds tomorrow instead of going quiet.
     */
    fun nextFire(todo: Todo, now: Long, zone: TimeZone): Long? {
        val reminderAt = todo.reminderAt ?: return null
        if (todo.done || todo.deleted) return null
        if (reminderAt > now) return reminderAt
        val dueAt = todo.dueAt
        val recurrence = Recurrence.parse(todo.recurrence)
        if (dueAt == null || recurrence == null || recurrence is Recurrence.AfterCompletion) return null
        val lead = dueAt - reminderAt
        val dueDate = DueDates.localDate(dueAt, zone)
        val time = DueDates.localTime(dueAt, zone)
        // Jump close to now first so a long-overdue task doesn't walk every missed occurrence.
        val leadDays = (lead / DAY_MS).coerceAtLeast(0).toInt()
        val floor = DueDates.localDate(now, zone).minus(leadDays + 2, DateTimeUnit.DAY)
        var date = recurrence.nextDate(dueDate, floor)
        repeat(MAX_OCCURRENCES) {
            val due = if (todo.dueAllDay) DueDates.startOfDay(date, zone) else DueDates.at(date, time, zone)
            if (due - lead > now) return due - lead
            date = recurrence.nextDate(dueDate, date)
        }
        return null
    }
}
