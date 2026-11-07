package com.vayunmathur.todo.domain

import com.vayunmathur.todo.data.Todo
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DomainHelpersTest {
    private val zone = TimeZone.of("America/Los_Angeles")
    private val date = LocalDate(2026, 3, 4)
    private val now = DueDates.at(date, LocalTime(10, 0), zone)

    @Test fun dueDatesConversions() {
        assertEquals(date, DueDates.localDate(now, zone))
        assertEquals(LocalTime(10, 0), DueDates.localTime(now, zone))
        assertEquals(now, DueDates.at(date, LocalTime(10, 0), zone))
        val start = DueDates.startOfDay(date, zone)
        assertEquals(LocalTime(0, 0), DueDates.localTime(start, zone))
        // picker round-trip
        assertEquals(start, DueDates.fromPickerMillis(DueDates.toPickerMillis(start, zone), zone))
        // legacy UTC midnight migrates, other values pass through
        val utcMidnight = DueDates.startOfDay(date, TimeZone.UTC)
        assertEquals(start, DueDates.migrateLegacyAllDay(utcMidnight, zone))
        assertEquals(now, DueDates.migrateLegacyAllDay(now, zone))
    }
    @Test fun dueBuckets() {
        fun todo(due: Long?, done: Boolean = false, allDay: Boolean = true) = Todo(dueAt = due, done = done, dueAllDay = allDay)
        assertEquals(DueBucket.NO_DATE, DueBuckets.bucket(todo(null), now, zone))
        assertEquals(DueBucket.TODAY, DueBuckets.bucket(todo(DueDates.startOfDay(date, zone)), now, zone))
        assertEquals(DueBucket.OVERDUE, DueBuckets.bucket(todo(DueDates.startOfDay(LocalDate(2026, 3, 3), zone)), now, zone))
        assertEquals(DueBucket.TOMORROW, DueBuckets.bucket(todo(DueDates.startOfDay(LocalDate(2026, 3, 5), zone)), now, zone))
        assertEquals(DueBucket.THIS_WEEK, DueBuckets.bucket(todo(DueDates.startOfDay(LocalDate(2026, 3, 8), zone)), now, zone))
        assertEquals(DueBucket.LATER, DueBuckets.bucket(todo(DueDates.startOfDay(LocalDate(2026, 3, 20), zone)), now, zone))
        assertFalse(DueBuckets.isOverdue(todo(DueDates.startOfDay(date, zone), done = true), now, zone))
        assertTrue(DueBuckets.isOverdue(todo(now - 1000, allDay = false), now, zone))
        assertFalse(DueBuckets.isOverdue(todo(null), now, zone))
        assertTrue(DueBuckets.isDueByToday(todo(DueDates.startOfDay(date, zone)), now, zone))
        assertFalse(DueBuckets.isDueByToday(todo(DueDates.startOfDay(LocalDate(2026, 3, 5), zone)), now, zone))
        assertEquals(0, DueBuckets.dayDelta(DueDates.startOfDay(date, zone), now, zone))
        assertEquals(-2, DueBuckets.dayDelta(DueDates.startOfDay(LocalDate(2026, 3, 2), zone), now, zone))
        assertTrue(DueBuckets.endOfToday(now, zone) > now)
    }
    @Test fun dueShortcuts() {
        assertEquals(DueDates.startOfDay(date, zone), DueShortcuts.TODAY.dueAt(now, zone))
        assertEquals(DueDates.startOfDay(LocalDate(2026, 3, 5), zone), DueShortcuts.TOMORROW.dueAt(now, zone))
        assertNull(DueShortcuts.NONE.dueAt(now, zone))
        val nextWeek = DueShortcuts.NEXT_WEEK.dueAt(now, zone)!!
        assertTrue(nextWeek > now)
        // 2026-03-04 is Wednesday; next-week shortcut lands on the following Monday
        assertEquals(LocalDate(2026, 3, 9), DueDates.localDate(nextWeek, zone))
    }
    @Test fun tombstoneCutoff() {
        assertEquals(now - TombstonePolicy.RETENTION_MS, TombstonePolicy.cutoff(now))
        assertTrue(TombstonePolicy.RETENTION_MS > 0)
    }
    @Test fun todoViewKeysRoundTrip() {
        val views = listOf(TodoView.Inbox, TodoView.Today, TodoView.Upcoming, TodoView.Priority, TodoView.All, TodoView.Completed, TodoView.InList("abc"), TodoView.Tagged("home"))
        views.forEach { assertEquals(it, TodoView.fromKey(it.key)) }
        assertEquals(TodoView.Inbox, TodoView.fromKey(null))
        assertEquals(TodoView.Inbox, TodoView.fromKey("bogus"))
        assertEquals(TodoView.Inbox, TodoView.fromKey(""))
        assertEquals(6, TodoView.smartViews.size)
        assertEquals("list:abc", TodoView.InList("abc").key)
        assertEquals("tag:home", TodoView.Tagged("home").key)
    }
    @Test fun sortModeEntries() {
        assertEquals(listOf(SortMode.MANUAL, SortMode.DUE, SortMode.PRIORITY, SortMode.CREATED, SortMode.TITLE), SortMode.entries.toList())
    }
    @Test fun recurrenceFirstOnOrAfterAndRRule() {
        assertEquals(date, Recurrence.Daily().firstOnOrAfter(date))
        assertNull(Recurrence.AfterCompletion(3).toRRule())
        assertEquals("FREQ=DAILY", Recurrence.Daily().toRRule())
        assertTrue(Recurrence.Weekly(1, setOf(kotlinx.datetime.DayOfWeek.MONDAY)).firstOnOrAfter(date) >= date)
        assertTrue(Recurrence.Monthly(1, 15).firstOnOrAfter(date) >= date)
        assertTrue(Recurrence.Monthly(1, Recurrence.LAST_DAY).firstOnOrAfter(LocalDate(2026, 1, 31)) == LocalDate(2026, 1, 31))
    }
}
