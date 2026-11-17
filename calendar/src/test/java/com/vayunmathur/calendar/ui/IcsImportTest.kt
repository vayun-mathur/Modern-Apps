package com.vayunmathur.calendar.ui

import com.vayunmathur.calendar.domain.RRule
import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class IcsImportTest {

    /**
     * Properties are passed as separate lines rather than one indented block: a line starting with a
     * space is an RFC 5545 continuation, so an indented heredoc would fold the whole event into one
     * line.
     */
    private fun parse(vararg properties: String) = parseICSFile(
        (
            listOf("BEGIN:VCALENDAR", "VERSION:2.0", "BEGIN:VEVENT", "UID:test-1", "SUMMARY:Test") +
                properties +
                listOf("END:VEVENT", "END:VCALENDAR")
            ).joinToString("\r\n").byteInputStream()
    )

    @Test
    fun repeatDatesOnOneLineAreImported() {
        val events = parse(
            "DTSTART;VALUE=DATE:20250704",
            "DTEND;VALUE=DATE:20250705",
            "RDATE;VALUE=DATE:20250708,20250712",
        )
        assertEquals(1, events.size)
        assertEquals(listOf(LocalDate(2025, 7, 8), LocalDate(2025, 7, 12)), events[0].rdate)
    }

    @Test
    fun repeatDatesSpreadOverSeveralLinesAllSurvive() {
        // The property map is last-wins, so before RDATE accumulated these lines overwrote each
        // other and every date but the last was silently dropped.
        val events = parse(
            "DTSTART;VALUE=DATE:20250704",
            "DTEND;VALUE=DATE:20250705",
            "RDATE;VALUE=DATE:20250708",
            "RDATE;VALUE=DATE:20250712",
            "RDATE;VALUE=DATE:20250715",
        )
        assertEquals(
            listOf(LocalDate(2025, 7, 8), LocalDate(2025, 7, 12), LocalDate(2025, 7, 15)),
            events[0].rdate,
        )
    }

    @Test
    fun excludedDatesAreImported() {
        val events = parse(
            "DTSTART;VALUE=DATE:20250704",
            "DTEND;VALUE=DATE:20250705",
            "RRULE:FREQ=DAILY;INTERVAL=1",
            "EXDATE;VALUE=DATE:20250706",
            "EXDATE;VALUE=DATE:20250707",
        )
        assertEquals(listOf(LocalDate(2025, 7, 6), LocalDate(2025, 7, 7)), events[0].exdate)
    }

    @Test
    fun theStartDateIsNotRepeatedAsAnExtraDate() {
        // DTSTART is already an occurrence; keeping it in rdate too would double it up.
        val events = parse(
            "DTSTART;VALUE=DATE:20250704",
            "DTEND;VALUE=DATE:20250705",
            "RDATE;VALUE=DATE:20250704,20250708",
        )
        assertEquals(listOf(LocalDate(2025, 7, 8)), events[0].rdate)
    }

    @Test
    fun aLateEveningRepeatKeepsItsLocalDay() {
        // 22:00 New York is 02:00 the next day in UTC. Taking the day off the raw value would push
        // every occurrence forward by one.
        val events = parse(
            "DTSTART;TZID=America/New_York:20250704T220000",
            "DTEND;TZID=America/New_York:20250704T230000",
            "RDATE;TZID=America/New_York:20250708T220000",
        )
        assertEquals("America/New_York", events[0].timezone)
        assertEquals(listOf(LocalDate(2025, 7, 8)), events[0].rdate)
    }

    @Test
    fun aUtcRepeatIsResolvedInTheEventZone() {
        val events = parse(
            "DTSTART;TZID=America/New_York:20250704T220000",
            "DTEND;TZID=America/New_York:20250704T230000",
            "RDATE:20250709T020000Z",
        )
        assertEquals(listOf(LocalDate(2025, 7, 8)), events[0].rdate)
    }

    @Test
    fun anEventWithNoRepeatHasNoDates() {
        val events = parse(
            "DTSTART;VALUE=DATE:20250704",
            "DTEND;VALUE=DATE:20250705",
        )
        assertTrue(events[0].rdate.isEmpty())
        assertTrue(events[0].exdate.isEmpty())
        assertTrue(!events[0].isRecurring)
    }

    private fun parseAll(vararg blocks: List<String>) = parseICSFile(
        (
            listOf("BEGIN:VCALENDAR", "VERSION:2.0") +
                blocks.flatMap { listOf("BEGIN:VEVENT") + it + listOf("END:VEVENT") } +
                listOf("END:VCALENDAR")
            ).joinToString("\r\n").byteInputStream()
    )

    @Test
    fun aDeletedOccurrenceArrivesAsAnExdate() {
        val events = parseAll(
            listOf(
                "UID:series-1",
                "SUMMARY:Standup",
                "DTSTART:20250707T090000Z",
                "DTEND:20250707T093000Z",
                "RRULE:FREQ=DAILY;INTERVAL=1",
            ),
            listOf(
                "UID:series-1",
                "SUMMARY:Standup",
                "DTSTART:20250708T090000Z",
                "DTEND:20250708T093000Z",
                "RECURRENCE-ID:20250708T090000Z",
                "STATUS:CANCELLED",
            ),
        )
        assertEquals(1, events.size)
        assertEquals(listOf(LocalDate(2025, 7, 8)), events[0].exdate)
    }

    @Test
    fun aChangedOccurrenceIsCutOutAndReinserted() {
        val events = parseAll(
            listOf(
                "UID:series-2",
                "SUMMARY:Standup",
                "DTSTART:20250707T090000Z",
                "DTEND:20250707T093000Z",
                "RRULE:FREQ=DAILY;INTERVAL=1",
            ),
            listOf(
                "UID:series-2",
                "SUMMARY:Standup moved",
                "DTSTART:20250708T100000Z",
                "DTEND:20250708T103000Z",
                "RECURRENCE-ID:20250708T090000Z",
            ),
        )
        assertEquals(2, events.size)
        val series = events.first { it.isRecurring }
        val moved = events.first { !it.isRecurring }
        assertEquals(listOf(LocalDate(2025, 7, 8)), series.exdate)
        assertEquals("Standup moved", moved.title)
    }

    @Test
    fun aDeletedRdateIsDroppedFromTheDateList() {
        val events = parseAll(
            listOf(
                "UID:series-3",
                "SUMMARY:Custom repeats",
                "DTSTART;VALUE=DATE:20250704",
                "DTEND;VALUE=DATE:20250705",
                "RDATE;VALUE=DATE:20250708,20250712",
            ),
            listOf(
                "UID:series-3",
                "SUMMARY:Custom repeats",
                "DTSTART;VALUE=DATE:20250708",
                "RECURRENCE-ID;VALUE=DATE:20250708",
                "STATUS:CANCELLED",
            ),
        )
        assertEquals(1, events.size)
        assertEquals(listOf(LocalDate(2025, 7, 12)), events[0].rdate)
        assertTrue(events[0].exdate.isEmpty())
    }

    @Test
    fun anOverrideWithoutCancelStatusIsKeptStandalone() {
        // No STATUS:CANCELLED: the override is kept as its own event (it may carry updated
        // metadata), while the date is still cut from the series so it does not show twice.
        val events = parseAll(
            listOf(
                "UID:series-3b",
                "SUMMARY:Custom repeats",
                "DTSTART;VALUE=DATE:20250704",
                "DTEND;VALUE=DATE:20250705",
                "RDATE;VALUE=DATE:20250708,20250712",
            ),
            listOf(
                "UID:series-3b",
                "SUMMARY:Custom repeats renamed",
                "DTSTART;VALUE=DATE:20250708",
                "RECURRENCE-ID;VALUE=DATE:20250708",
            ),
        )
        assertEquals(2, events.size)
        val series = events.first { it.isRecurring }
        assertEquals(listOf(LocalDate(2025, 7, 12)), series.rdate)
        assertEquals("Custom repeats renamed", events.first { !it.isRecurring }.title)
    }

    @Test
    fun thisAndFutureTruncatesTheSeries() {
        val events = parseAll(
            listOf(
                "UID:series-4",
                "SUMMARY:Standup",
                "DTSTART:20250707T090000Z",
                "DTEND:20250707T093000Z",
                "RRULE:FREQ=DAILY;INTERVAL=1",
            ),
            listOf(
                "UID:series-4",
                "SUMMARY:Standup moved",
                "DTSTART:20250710T100000Z",
                "DTEND:20250710T103000Z",
                "RECURRENCE-ID;RANGE=THISANDFUTURE:20250710T090000Z",
            ),
        )
        assertEquals(2, events.size)
        val truncated = events.first { it.isRecurring }
        val tail = events.first { !it.isRecurring }
        val end = truncated.rrule?.endCondition as? RRule.EndCondition.Until
        assertEquals(LocalDate(2025, 7, 9), end?.date)
        assertEquals("Standup moved", tail.title)
    }
}
