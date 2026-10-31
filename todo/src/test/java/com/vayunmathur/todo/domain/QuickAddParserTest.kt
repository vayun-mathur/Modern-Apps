package com.vayunmathur.todo.domain

import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class QuickAddParserTest {
    private val zone = TimeZone.of("America/Los_Angeles")

    // Wednesday 2026-03-04, 10:00.
    private val now = DueDates.at(LocalDate(2026, 3, 4), LocalTime(10, 0), zone)
    private val lists = listOf(
        QuickAddParser.ListName("l-home", "Home"),
        QuickAddParser.ListName("l-gs", "Grocery Store"),
    )

    private fun parse(text: String) = QuickAddParser.parse(text, now, zone, lists)
    private fun day(m: Int, d: Int, y: Int = 2026) = DueDates.startOfDay(LocalDate(y, m, d), zone)
    private fun at(m: Int, d: Int, h: Int, min: Int = 0) = DueDates.at(LocalDate(2026, m, d), LocalTime(h, min), zone)

    @Test
    fun `plain text is just a title`() {
        val r = parse("Buy 2 apples")
        assertEquals("Buy 2 apples", r.title)
        assertNull(r.dueAt)
        assertTrue(r.tokens.isEmpty())
    }

    @Test
    fun `the full example`() {
        val r = parse("Pay rent every 1st #home !1 tomorrow 9am @Home")
        assertEquals("Pay rent", r.title)
        assertEquals(Recurrence.Monthly(1, 1), r.recurrence)
        assertEquals(listOf("home"), r.tags)
        assertEquals(1, r.priority)
        assertEquals("l-home", r.listSyncId)
        assertEquals(at(3, 5, 9), r.dueAt)
        assertEquals(false, r.dueAllDay)
    }

    @Test
    fun `relative days`() {
        assertEquals(day(3, 4), parse("x today").dueAt)
        assertEquals(day(3, 5), parse("x tomorrow").dueAt)
        assertEquals(day(3, 5), parse("x tmrw").dueAt)
        assertEquals(day(3, 7), parse("x in 3 days").dueAt)
        assertEquals(day(3, 18), parse("x in 2 weeks").dueAt)
        assertEquals(day(3, 11), parse("x in a week").dueAt)
    }

    @Test
    fun `weekdays and next`() {
        assertEquals(day(3, 6), parse("x friday").dueAt)
        assertEquals(day(3, 4), parse("x wednesday").dueAt)
        assertEquals(day(3, 13), parse("x next friday").dueAt)
        assertEquals(day(3, 9), parse("x next week").dueAt)
        assertEquals(day(4, 1), parse("x next month").dueAt)
        assertEquals(day(3, 7), parse("x this weekend").dueAt)
        assertEquals(day(3, 6), parse("x on fri").dueAt)
    }

    @Test
    fun `weekday abbreviations are not dates on their own`() {
        val r = parse("Wear sun hat")
        assertEquals("Wear sun hat", r.title)
        assertNull(r.dueAt)
    }

    @Test
    fun `absolute dates roll to next year when past`() {
        assertEquals(day(3, 10), parse("x mar 10").dueAt)
        assertEquals(day(1, 5, 2027), parse("x jan 5th").dueAt)
        assertEquals(day(12, 25), parse("x 25 dec").dueAt)
        assertEquals(day(7, 4, 2028), parse("x july 4 2028").dueAt)
        assertEquals(day(5, 6), parse("x 2026-05-06").dueAt)
        assertEquals(day(3, 5), parse("x 3/5").dueAt)
        assertEquals(day(3, 5, 2027), parse("x 3/5/27").dueAt)
    }

    @Test
    fun `times attach to the date or to the next occurrence of that time`() {
        assertEquals(at(3, 4, 17), parse("call at 5pm").dueAt)
        assertEquals(at(3, 4, 17, 30), parse("call 17:30").dueAt)
        assertEquals(at(3, 5, 9), parse("call 9am").dueAt)
        assertEquals(at(3, 4, 17), parse("call at 5").dueAt)
        assertEquals(at(3, 4, 12), parse("lunch at noon").dueAt)
        assertEquals(at(3, 6, 9, 15), parse("standup friday 9:15 am").dueAt)
        assertEquals(at(3, 4, 20), parse("read tonight").dueAt)
    }

    @Test
    fun `numbers are not times without a marker`() {
        assertNull(parse("Read 5 pages").dueAt)
        assertEquals("Read 5 pages", parse("Read 5 pages").title)
    }

    @Test
    fun `recurrences set the first occurrence when no date is given`() {
        val weekly = parse("Gym every mon, wed and fri")
        assertEquals("Gym", weekly.title)
        assertEquals(Recurrence.Weekly(1, setOf(DayOfWeek.MONDAY, DayOfWeek.WEDNESDAY, DayOfWeek.FRIDAY)), weekly.recurrence)
        assertEquals(day(3, 4), weekly.dueAt)
        assertEquals(Recurrence.Daily(), parse("x daily").recurrence)
        assertEquals(Recurrence.Daily(3), parse("x every 3 days").recurrence)
        assertEquals(Recurrence.Weekly(2), parse("x every other week").recurrence)
        assertEquals(Recurrence.AfterCompletion(14), parse("x every! 2 weeks").recurrence)
        assertEquals(Recurrence.Monthly(1, Recurrence.LAST_DAY), parse("x every last day").recurrence)
        assertEquals(day(3, 31), parse("x every last day").dueAt)
        assertEquals(day(4, 1), parse("x every 1st").dueAt)
    }

    @Test
    fun `multi-word list names and unknown lists`() {
        val r = parse("Milk @grocery store")
        assertEquals("l-gs", r.listSyncId)
        assertEquals("Milk", r.title)
        val unknown = parse("Email @bob")
        assertNull(unknown.listSyncId)
        assertEquals("Email @bob", unknown.title)
    }

    @Test
    fun `tokens report their ranges in the input`() {
        val text = "Milk #shop tomorrow"
        val r = parse(text)
        assertEquals(listOf("#shop", "tomorrow"), r.tokens.map { text.substring(it.range) })
        assertEquals(listOf(QuickAddParser.Kind.TAG, QuickAddParser.Kind.DATE), r.tokens.map { it.kind })
    }
}
