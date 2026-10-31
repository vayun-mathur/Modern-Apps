package com.vayunmathur.todo.domain

import com.vayunmathur.todo.data.Todo
import kotlinx.datetime.TimeZone

/** Checking a task off. A repeating task rolls forward to its next occurrence instead. */
object Completion {

    fun complete(todo: Todo, now: Long, zone: TimeZone): Todo {
        val recurrence = Recurrence.parse(todo.recurrence)
        val due = todo.dueAt
        if (recurrence == null || (due == null && recurrence !is Recurrence.AfterCompletion)) {
            return todo.copy(done = true, completedAt = now)
        }
        val today = DueDates.localDate(now, zone)
        val dueDate = due?.let { DueDates.localDate(it, zone) } ?: today
        val nextDate = recurrence.nextDate(dueDate, today)
        val nextDue = if (due == null || todo.dueAllDay) {
            DueDates.startOfDay(nextDate, zone)
        } else {
            DueDates.at(nextDate, DueDates.localTime(due, zone), zone)
        }
        val shift = nextDue - (due ?: DueDates.startOfDay(today, zone))
        return todo.copy(
            done = false,
            completedAt = now,
            dueAt = nextDue,
            startAt = todo.startAt?.plus(shift),
            reminderAt = todo.reminderAt?.plus(shift),
        )
    }

    fun uncomplete(todo: Todo): Todo = todo.copy(done = false, completedAt = null)

    /** True when [complete] rolled the task forward rather than finishing it. */
    fun rolledForward(before: Todo, after: Todo): Boolean = !after.done && before.dueAt != after.dueAt
}
