package com.vayunmathur.calendar.data

import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlin.test.Test
import kotlin.test.assertEquals

class InstanceTest {

    private fun midnightUtc(date: String): Long =
        LocalDate.parse(date).atStartOfDayIn(TimeZone.UTC).toEpochMilliseconds()

    private fun allDayInstance(beginDate: String, endDateExclusive: String) = Instance(
        id = 1L,
        eventID = 1L,
        begin = midnightUtc(beginDate),
        end = midnightUtc(endDateExclusive),
        timezone = "UTC",
        allDay = true,
        eventTitle = "Test",
        color = 0,
        rrule = null,
    )

    // Regression test: a single-day all-day event (stored with an exclusive next-midnight
    // end per RFC 5545) must occupy exactly one calendar day, not two.
    @Test
    fun singleDayAllDaySpansOneDay() {
        val instance = allDayInstance("2026-07-15", "2026-07-16")
        assertEquals(listOf(LocalDate.parse("2026-07-15")), instance.spanDays)
    }

    // Regression test: a zero-duration instance at midnight (begin == end, e.g. an
    // imported timed event with no DTEND) must still span its start day. Before the
    // fix spanDays was empty, so the event showed in no view.
    @Test
    fun zeroDurationAtMidnightSpansStartDay() {
        val date = LocalDate.parse("2026-07-15")
        val midnight = date.atStartOfDayIn(TimeZone.currentSystemDefault()).toEpochMilliseconds()
        val timed = Instance(
            id = 1L, eventID = 1L, begin = midnight, end = midnight,
            timezone = "UTC", allDay = false, eventTitle = "Test", color = 0, rrule = null,
        )
        assertEquals(listOf(date), timed.spanDays)
        val allDay = allDayInstance("2026-07-15", "2026-07-15")
        assertEquals(listOf(date), allDay.spanDays)
    }

    @Test
    fun multiDayAllDaySpansInclusiveDays() {
        val instance = allDayInstance("2026-07-15", "2026-07-18")
        assertEquals(
            listOf(
                LocalDate.parse("2026-07-15"),
                LocalDate.parse("2026-07-16"),
                LocalDate.parse("2026-07-17"),
            ),
            instance.spanDays,
        )
    }
}
