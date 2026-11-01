package com.vayunmathur.todo.domain

import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class RecurrenceTest {
    private fun d(y: Int, m: Int, day: Int) = LocalDate(y, m, day)

    @Test
    fun `daily advances past both the due date and today`() {
        val r = Recurrence.Daily()
        assertEquals(d(2026, 3, 2), r.nextDate(due = d(2026, 3, 1), today = d(2026, 3, 1)))
        // Three days overdue: lands tomorrow, not back in the past.
        assertEquals(d(2026, 3, 5), r.nextDate(due = d(2026, 3, 1), today = d(2026, 3, 4)))
        // Completed early: next is after the due date.
        assertEquals(d(2026, 3, 11), r.nextDate(due = d(2026, 3, 10), today = d(2026, 3, 4)))
    }

    @Test
    fun `every n days stays on the cadence`() {
        val r = Recurrence.Daily(3)
        assertEquals(d(2026, 3, 7), r.nextDate(due = d(2026, 3, 1), today = d(2026, 3, 5)))
    }

    @Test
    fun `weekly on chosen days picks the next matching weekday`() {
        val r = Recurrence.Weekly(1, setOf(DayOfWeek.MONDAY, DayOfWeek.THURSDAY))
        // 2026-03-02 is a Monday.
        assertEquals(d(2026, 3, 5), r.nextDate(due = d(2026, 3, 2), today = d(2026, 3, 2)))
        assertEquals(d(2026, 3, 9), r.nextDate(due = d(2026, 3, 5), today = d(2026, 3, 5)))
    }

    @Test
    fun `every other week skips the off week`() {
        val r = Recurrence.Weekly(2, setOf(DayOfWeek.MONDAY))
        assertEquals(d(2026, 3, 16), r.nextDate(due = d(2026, 3, 2), today = d(2026, 3, 2)))
    }

    @Test
    fun `weekly with no days repeats the due weekday`() {
        assertEquals(d(2026, 3, 9), Recurrence.Weekly().nextDate(d(2026, 3, 2), d(2026, 3, 2)))
    }

    @Test
    fun `monthly clamps to short months and keeps the anchor day afterwards`() {
        val r = Recurrence.Monthly()
        assertEquals(d(2026, 2, 28), r.nextDate(due = d(2026, 1, 31), today = d(2026, 1, 31)))
        assertEquals(d(2026, 3, 31), r.nextDate(due = d(2026, 1, 31), today = d(2026, 2, 28)))
    }

    @Test
    fun `monthly on the last day`() {
        val r = Recurrence.Monthly(1, Recurrence.LAST_DAY)
        assertEquals(d(2028, 2, 29), r.nextDate(due = d(2028, 1, 31), today = d(2028, 1, 31)))
    }

    @Test
    fun `yearly handles leap days`() {
        assertEquals(d(2029, 2, 28), Recurrence.Yearly().nextDate(due = d(2028, 2, 29), today = d(2028, 2, 29)))
    }

    @Test
    fun `after completion counts from today`() {
        val r = Recurrence.AfterCompletion(10)
        assertEquals(d(2026, 3, 14), r.nextDate(due = d(2026, 1, 1), today = d(2026, 3, 4)))
    }

    @Test
    fun `serialize and parse round trip`() {
        val all = listOf(
            Recurrence.Daily(),
            Recurrence.Daily(4),
            Recurrence.Weekly(2, setOf(DayOfWeek.MONDAY, DayOfWeek.FRIDAY)),
            Recurrence.Monthly(1, 15),
            Recurrence.Monthly(3, Recurrence.LAST_DAY),
            Recurrence.Yearly(),
            Recurrence.AfterCompletion(7),
        )
        for (r in all) assertEquals(r, Recurrence.parse(r.serialize()), r.serialize())
    }

    @Test
    fun `serialized forms are standard rrules`() {
        assertEquals("FREQ=WEEKLY;BYDAY=MO,FR", Recurrence.Weekly(1, setOf(DayOfWeek.FRIDAY, DayOfWeek.MONDAY)).serialize())
        assertNull(Recurrence.AfterCompletion(3).toRRule())
        assertEquals("FREQ=MONTHLY;BYMONTHDAY=-1", Recurrence.Monthly(1, -1).toRRule())
    }

    @Test
    fun `garbage parses to null and bad intervals are clamped`() {
        assertNull(Recurrence.parse(null))
        assertNull(Recurrence.parse(""))
        assertNull(Recurrence.parse("FREQ=HOURLY"))
        assertEquals(Recurrence.Daily(1), Recurrence.parse("RRULE:FREQ=DAILY;INTERVAL=0"))
    }

    @Test
    fun `first occurrence is today when the schedule allows, else the next matching day`() {
        // 2026-03-04 is a Wednesday.
        val today = d(2026, 3, 4)
        assertEquals(today, Recurrence.Daily().firstOnOrAfter(today))
        assertEquals(today, Recurrence.Weekly().firstOnOrAfter(today))
        assertEquals(d(2026, 3, 6), Recurrence.Weekly(1, setOf(DayOfWeek.FRIDAY)).firstOnOrAfter(today))
        assertEquals(d(2026, 3, 15), Recurrence.Monthly(1, 15).firstOnOrAfter(today))
        assertEquals(d(2026, 3, 31), Recurrence.Monthly(1, Recurrence.LAST_DAY).firstOnOrAfter(today))
        assertEquals(d(2026, 3, 31), Recurrence.Monthly(1, Recurrence.LAST_DAY).firstOnOrAfter(d(2026, 3, 31)))
    }
}
