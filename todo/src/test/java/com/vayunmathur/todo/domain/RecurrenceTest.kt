package com.vayunmathur.todo.domain

import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate
import kotlinx.datetime.minus
import kotlinx.datetime.number
import kotlinx.datetime.plus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

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
    fun `mirror rule clamps monthly on the 31st instead of skipping February`() {
        val rule = Recurrence.Monthly().toRRule(d(2025, 1, 31))!!
        assertEquals("FREQ=MONTHLY;BYMONTHDAY=31,-1", rule)
        // RFC expansion of the emitted rule (what the calendar provider does):
        // February gets an occurrence, and the anchor day returns afterwards.
        val occurrences = expandRfc(rule, d(2025, 1, 31), d(2025, 1, 1), d(2025, 4, 30))
        assertEquals(
            listOf(d(2025, 1, 31), d(2025, 2, 28), d(2025, 3, 31), d(2025, 4, 30)),
            occurrences,
        )
        // The un-encoded storage form keeps RFC skip semantics, proving the
        // clamp is gated to mirror emission rather than applied generally.
        assertFalse(
            d(2025, 2, 28) in expandRfc(
                Recurrence.Monthly(1, 31).serialize(), d(2025, 1, 31), d(2025, 2, 1), d(2025, 2, 28),
            ),
        )
    }

    @Test
    fun `mirror rule clamps monthly on the 30th without adding the 31st`() {
        val rule = Recurrence.Monthly(1, 30).toRRule(d(2025, 1, 30))!!
        assertEquals("FREQ=MONTHLY;BYMONTHDAY=30,-1;BYSETPOS=1", rule)
        assertEquals(
            listOf(d(2025, 2, 28), d(2025, 3, 30)),
            expandRfc(rule, d(2025, 1, 30), d(2025, 2, 1), d(2025, 3, 31)),
        )
    }

    @Test
    fun `mirror rule clamps yearly Feb 29 to Feb 28 in non-leap years`() {
        val rule = Recurrence.Yearly().toRRule(d(2024, 2, 29))!!
        assertEquals("FREQ=YEARLY;BYMONTH=2;BYMONTHDAY=29,-1", rule)
        val in2025 = expandRfc(rule, d(2024, 2, 29), d(2025, 1, 1), d(2025, 12, 31))
        assertEquals(listOf(d(2025, 2, 28)), in2025)
        assertTrue(d(2024, 2, 29) in expandRfc(rule, d(2024, 2, 29), d(2024, 1, 1), d(2024, 12, 31)))
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

/**
 * Minimal RFC 5545 expansion of the monthly/yearly BYMONTHDAY shapes the
 * calendar mirror emits — BYMONTHDAY (incl. -1 = last day), BYMONTH and
 * BYSETPOS over INTERVAL steps — mirroring what the calendar provider does
 * with the stored RRULE. Invalid days (e.g. Feb 31) are skipped, per RFC.
 */
private fun expandRfc(rrule: String, dtstart: LocalDate, from: LocalDate, to: LocalDate): List<LocalDate> {
    val parts = rrule.split(';').associate { it.substringBefore('=') to it.substringAfter('=') }
    val interval = parts["INTERVAL"]?.toIntOrNull() ?: 1
    val byMonthDay = parts["BYMONTHDAY"]?.split(',')?.map { it.toInt() }
    val bySetPos = parts["BYSETPOS"]?.split(',')?.map { it.toInt() }.orEmpty()

    fun monthCandidates(year: Int, month: Int): List<LocalDate> {
        val first = LocalDate(year, month, 1)
        val length = first.plus(1, DateTimeUnit.MONTH).minus(1, DateTimeUnit.DAY).day
        val days = (byMonthDay ?: listOf(dtstart.day)).mapNotNull { wanted ->
            when {
                wanted > 0 -> wanted.takeIf { it <= length }
                wanted < 0 -> (length + 1 + wanted).takeIf { it >= 1 }
                else -> null
            }
        }
        val all = days.distinct().sorted().map { LocalDate(year, month, it) }
        if (bySetPos.isEmpty()) return all
        return bySetPos.mapNotNull { pos ->
            when {
                pos > 0 -> all.getOrNull(pos - 1)
                pos < 0 -> all.getOrNull(all.size + pos)
                else -> null
            }
        }
    }

    val out = mutableListOf<LocalDate>()
    when (parts["FREQ"]) {
        "MONTHLY" -> {
            var cursor = LocalDate(dtstart.year, dtstart.month.number, 1)
            while (cursor <= to) {
                out += monthCandidates(cursor.year, cursor.month.number)
                cursor = cursor.plus(interval, DateTimeUnit.MONTH)
            }
        }
        "YEARLY" -> {
            val months = parts["BYMONTH"]?.split(',')?.map { it.toInt() } ?: listOf(dtstart.month.number)
            var year = dtstart.year
            while (LocalDate(year, 1, 1) <= to) {
                months.forEach { out += monthCandidates(year, it) }
                year += interval
            }
        }
    }
    return out.filter { it in from..to && it >= dtstart }.distinct().sorted()
}
