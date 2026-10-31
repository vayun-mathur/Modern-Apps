package com.vayunmathur.todo.domain

import com.vayunmathur.todo.data.Todo
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CompletionTest {
    private val zone = TimeZone.of("America/Los_Angeles")
    private fun day(y: Int, m: Int, d: Int) = DueDates.startOfDay(LocalDate(y, m, d), zone)
    private fun at(y: Int, m: Int, d: Int, h: Int) = DueDates.at(LocalDate(y, m, d), LocalTime(h, 0), zone)

    @Test
    fun `a one-off task is simply marked done`() {
        val done = Completion.complete(Todo(title = "x", dueAt = day(2026, 3, 1)), now = 5L, zone = zone)
        assertTrue(done.done)
        assertEquals(5L, done.completedAt)
        assertEquals(day(2026, 3, 1), done.dueAt)
    }

    @Test
    fun `a repeating task rolls forward and stays open`() {
        val todo = Todo(title = "x", dueAt = day(2026, 3, 1), recurrence = "FREQ=DAILY")
        val next = Completion.complete(todo, now = at(2026, 3, 1, 10), zone = zone)
        assertFalse(next.done)
        assertEquals(day(2026, 3, 2), next.dueAt)
        assertTrue(Completion.rolledForward(todo, next))
    }

    @Test
    fun `timed occurrences keep their wall-clock time across a DST change`() {
        // DST starts 2026-03-08 in Los Angeles.
        val todo = Todo(title = "x", dueAt = at(2026, 3, 7, 9), dueAllDay = false, recurrence = "FREQ=DAILY")
        val next = Completion.complete(todo, now = at(2026, 3, 7, 9), zone = zone)
        assertEquals(at(2026, 3, 8, 9), next.dueAt)
    }

    @Test
    fun `reminder and start keep their offset from the due date`() {
        val due = at(2026, 3, 2, 9)
        val todo = Todo(
            dueAt = due,
            dueAllDay = false,
            reminderAt = due - 3_600_000L,
            startAt = due - 86_400_000L,
            recurrence = "FREQ=WEEKLY",
        )
        val next = Completion.complete(todo, now = due, zone = zone)
        assertEquals(next.dueAt!! - 3_600_000L, next.reminderAt)
        assertEquals(next.dueAt!! - 86_400_000L, next.startAt)
    }

    @Test
    fun `after-completion repeats work without a due date`() {
        val todo = Todo(recurrence = "FREQ=DAILY;INTERVAL=3;X-FROM=COMPLETION")
        val next = Completion.complete(todo, now = at(2026, 3, 1, 12), zone = zone)
        assertEquals(day(2026, 3, 4), next.dueAt)
        assertFalse(next.done)
    }

    @Test
    fun `a fixed repeat without a due date just completes`() {
        val done = Completion.complete(Todo(recurrence = "FREQ=DAILY"), now = 1L, zone = zone)
        assertTrue(done.done)
    }

    @Test
    fun `uncomplete clears the completion stamp`() {
        val todo = Completion.uncomplete(Todo(done = true, completedAt = 4L))
        assertFalse(todo.done)
        assertEquals(null, todo.completedAt)
    }
}
