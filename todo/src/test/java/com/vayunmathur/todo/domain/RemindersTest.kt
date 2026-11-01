package com.vayunmathur.todo.domain

import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import com.vayunmathur.todo.data.Todo
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class RemindersTest {
    private val zone = TimeZone.of("Europe/Berlin")
    private fun at(d: Int, h: Int, m: Int = 0) = DueDates.at(LocalDate(2026, 3, d), LocalTime(h, m), zone)

    @Test
    fun `timed tasks remind relative to the due instant`() {
        val due = at(10, 15)
        assertEquals(due, Reminders.at(due, false, Reminders.Offset.AT_DUE, zone))
        assertEquals(at(10, 14, 50), Reminders.at(due, false, Reminders.Offset.MINUTES_10, zone))
        assertEquals(at(9, 15), Reminders.at(due, false, Reminders.Offset.DAY_1, zone))
    }

    @Test
    fun `all-day tasks remind at nine on the day, or the day before`() {
        val due = DueDates.startOfDay(LocalDate(2026, 3, 10), zone)
        assertEquals(at(10, 9), Reminders.at(due, true, Reminders.Offset.AT_DUE, zone))
        assertEquals(at(10, 8), Reminders.at(due, true, Reminders.Offset.HOUR_1, zone))
        assertEquals(at(9, 9), Reminders.at(due, true, Reminders.Offset.DAY_1, zone))
    }

    @Test
    fun `all-day tasks only offer day-level offsets`() {
        assertEquals(listOf(Reminders.Offset.AT_DUE, Reminders.Offset.DAY_1), Reminders.offsetsFor(allDay = true))
        assertEquals(Reminders.Offset.entries, Reminders.offsetsFor(allDay = false))
    }

    @Test
    fun `a future reminder fires as set`() {
        val todo = Todo(dueAt = at(10, 15), dueAllDay = false, reminderAt = at(10, 14))
        assertEquals(at(10, 14), Reminders.nextFire(todo, now = at(10, 8), zone = zone))
    }

    @Test
    fun `a past one-time reminder never fires again`() {
        val todo = Todo(dueAt = at(10, 15), dueAllDay = false, reminderAt = at(10, 14))
        assertNull(Reminders.nextFire(todo, now = at(10, 16), zone = zone))
    }

    @Test
    fun `an unchecked daily task still reminds at the next occurrence`() {
        val todo = Todo(dueAt = at(10, 8), dueAllDay = false, reminderAt = at(10, 7, 50), recurrence = "FREQ=DAILY")
        assertEquals(at(11, 7, 50), Reminders.nextFire(todo, now = at(10, 12), zone = zone))
        // Days overdue: picks up at the next upcoming occurrence, not a missed one.
        assertEquals(at(15, 7, 50), Reminders.nextFire(todo, now = at(15, 7), zone = zone))
        assertEquals(at(16, 7, 50), Reminders.nextFire(todo, now = at(15, 9), zone = zone))
    }

    @Test
    fun `weekly reminders keep their day-before lead`() {
        // 2026-03-10 is a Tuesday; remind Monday at 9.
        val due = DueDates.startOfDay(LocalDate(2026, 3, 10), zone)
        val todo = Todo(dueAt = due, dueAllDay = true, reminderAt = at(9, 9), recurrence = "FREQ=WEEKLY")
        assertEquals(at(16, 9), Reminders.nextFire(todo, now = at(9, 10), zone = zone))
    }

    @Test
    fun `done and after-completion tasks have no next reminder`() {
        val base = Todo(dueAt = at(10, 8), dueAllDay = false, reminderAt = at(10, 8), recurrence = "FREQ=DAILY")
        assertNull(Reminders.nextFire(base.copy(done = true), now = at(9, 0), zone = zone))
        assertNull(Reminders.nextFire(base.copy(recurrence = "FREQ=DAILY;X-FROM=COMPLETION"), now = at(10, 9), zone = zone))
    }
}
