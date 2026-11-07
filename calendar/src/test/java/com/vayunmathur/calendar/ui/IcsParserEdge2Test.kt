package com.vayunmathur.calendar.ui

import com.vayunmathur.calendar.CalendarTestData as D
import com.vayunmathur.calendar.data.Event
import com.vayunmathur.calendar.util.RRule
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class IcsParserEdge2Test {

    private val utc = TimeZone.of("UTC")

    @Test
    fun `unescape handles newline comma semicolon backslash and plain text`() {
        assertEquals("plain", unescapeIcsText("plain"))
        assertEquals("a\nb", unescapeIcsText("a\\nb"))
        assertEquals("a\nb", unescapeIcsText("a\\Nb"))
        assertEquals("a,b", unescapeIcsText("a\\,b"))
        assertEquals("a;b", unescapeIcsText("a\\;b"))
        assertEquals("a\\", unescapeIcsText("a\\\\"))
        assertEquals("trailing\\", unescapeIcsText("trailing\\"))
    }

    @Test
    fun `parse ics dates handles date and datetime forms distinct sorted`() {
        val dates = parseIcsDates(
            listOf(
                "DTSTART;VALUE=DATE" to "20260310,20260309",
                "DTSTART;TZID=UTC" to "20260311T090000",
                "DTSTART" to "bad-value",
            ), utc,
        )
        assertEquals(listOf(LocalDate(2026, 3, 9), LocalDate(2026, 3, 10), LocalDate(2026, 3, 11)), dates)
        assertEquals(emptyList(), parseIcsDates(emptyList(), utc))
    }

    @Test
    fun `parse ics time covers all day utc offset floating and invalid`() {
        val (allDayMillis, allDay, allDayTz) = parseICSTime("DTSTART;VALUE=DATE", "20260309")
        assertNotNull(allDayMillis); assertTrue(allDay); assertEquals("UTC", allDayTz)

        val (utcMillis, utcAllDay, utcTz) = parseICSTime("DTSTART", "20260309T090000Z")
        assertNotNull(utcMillis); assertEquals(false, utcAllDay); assertEquals("UTC", utcTz)

        val (offsetMillis, _, _) = parseICSTime("DTSTART", "2026-03-09T09:00:00+02:00")
        assertNotNull(offsetMillis)

        val (floating, _, floatingTz) = parseICSTime("DTSTART;TZID=America/New_York", "20260309T090000")
        assertNotNull(floating); assertEquals("America/New_York", floatingTz)

        val (dashed, _, _) = parseICSTime("DTSTART", "2026-03-09T09:00:00")
        assertNotNull(dashed)

        val (shortFloating, _, _) = parseICSTime("DTSTART", "20260309T0900")
        assertNotNull(shortFloating)

        assertEquals(Triple<Long?, Boolean, String?>(null, false, null), parseICSTime("DTSTART", null))
        val (bad, _, _) = parseICSTime("DTSTART", "not-a-time-value!!")
        assertNull(bad)
    }

    @Test
    fun `tzid extraction from property left`() {
        assertEquals("Europe/Paris", extractTZID("DTSTART;TZID=Europe/Paris"))
        assertEquals("UTC", extractTZID("dtstart;tzid=UTC"))
        assertNull(extractTZID("DTSTART;VALUE=DATE"))
        assertNull(extractTZID("DTSTART"))
    }

    @Test
    fun `duration millis and explicit offset detection`() {
        assertEquals(1000L + 3_600_000L, tryParseDurationMillis("PT1H", 1000L))
        assertEquals(1000L, tryParseDurationMillis("PT0S", 1000L))
        assertNull(tryParseDurationMillis("garbage", 1000L))
        assertTrue(hasExplicitOffset("20260309T090000+02:00"))
        assertTrue(hasExplicitOffset("2026-03-09T09:00:00-05:00"))
        assertEquals(false, hasExplicitOffset("20260309T090000"))
        assertEquals(false, hasExplicitOffset("20260309"))
    }

    @Test
    fun `trigger minutes parsing covers negative zero positive and absolute`() {
        assertEquals(15, parseTriggerMinutesBefore("-PT15M"))
        assertEquals(0, parseTriggerMinutesBefore("PT0S"))
        assertEquals(90, parseTriggerMinutesBefore("-PT1H30M"))
        assertEquals(15, parseTriggerMinutesBefore("-PT15M;RELATED=START"))
        assertNull(parseTriggerMinutesBefore("PT15M")) // after start: not minutes-before
        assertNull(parseTriggerMinutesBefore("20260309T090000Z"))
        assertNull(parseTriggerMinutesBefore(""))
        assertNull(parseTriggerMinutesBefore("garbage"))
        assertNull(parseTriggerMinutesBefore("-Pgarbage"))
    }

    @Test
    fun `recurrence id date from map and raw forms`() {
        assertNull(recurrenceIdDate(emptyMap(), utc))
        assertEquals(
            LocalDate(2026, 3, 9),
            recurrenceIdDate(mapOf("RECURRENCE-ID" to "20260309"), utc),
        )
        assertEquals(
            LocalDate(2026, 3, 9),
            recurrenceIdDateFrom("20260309T090000Z", "RECURRENCE-ID", utc),
        )
        assertEquals(
            LocalDate(2026, 3, 10),
            recurrenceIdDateFrom("X:20260310", "", utc),
        )
        assertNull(recurrenceIdDateFrom("bad", "", utc))
    }

    private fun seriesEvent(): Event = D.event(
        id = 500, title = "SeriesEvent",
        rrule = RRule.EveryXDays(1, RRule.EndCondition.Count(10)),
    ).copy(id = 500)

    @Test
    fun `override without series is kept standalone stripped of recurrence`() {
        // applyRecurrenceOverride matches series by override.id (UID hash in real parsing);
        // give the override an id with no matching series.
        val override = D.event(id = 999, title = "StandaloneOverride")
        val events = mutableListOf<Event>()
        applyRecurrenceOverride(events, override, LocalDate(2026, 3, 10), emptyMap())
        assertEquals(1, events.size)
        assertEquals(null, events[0].rrule)
        assertEquals(emptyList(), events[0].rdate)
    }

    @Test
    fun `override cuts rrule occurrence via exdate and adds standalone`() {
        // The override must carry the same id as the series to be matched.
        val series = seriesEvent()
        val override = series.copy(title = "MovedOccurrence")
        val events = mutableListOf(series)
        applyRecurrenceOverride(events, override, LocalDate(2026, 3, 10), emptyMap())
        assertEquals(2, events.size)
        assertEquals(listOf(LocalDate(2026, 3, 10)), events[0].exdate)
        assertEquals("MovedOccurrence", events[1].title)
        assertEquals(null, events[1].rrule)
    }

    @Test
    fun `cancelled override cuts occurrence without adding standalone`() {
        val series = seriesEvent()
        val override = series.copy(title = "DeletedOccurrence")
        val events = mutableListOf(series)
        applyRecurrenceOverride(
            events, override, LocalDate(2026, 3, 11),
            mapOf("STATUS" to "CANCELLED"),
        )
        assertEquals(1, events.size)
        assertEquals(listOf(LocalDate(2026, 3, 11)), events[0].exdate)
    }

    @Test
    fun `this and future override truncates series and starts new one`() {
        val series = seriesEvent()
        val override = series.copy(title = "FutureSeries")
        val events = mutableListOf(series)
        applyRecurrenceOverride(
            events, override, LocalDate(2026, 3, 12),
            mapOf("RECURRENCE-ID_PROP" to "RECURRENCE-ID;RANGE=THISANDFUTURE"),
        )
        assertEquals(2, events.size)
        val truncated = events[0]
        assertTrue(truncated.rrule!!.endCondition is RRule.EndCondition.Until)
        assertEquals("FutureSeries", events[1].title)
    }

    @Test
    fun `override on rdate series drops the rdate instead of exdate`() {
        val series = D.event(
            id = 500, title = "RdateSeries",
            rdate = listOf(LocalDate(2026, 3, 10), LocalDate(2026, 3, 12)),
        )
        val override = series.copy(title = "RdateOverride")
        val events = mutableListOf(series)
        applyRecurrenceOverride(events, override, LocalDate(2026, 3, 10), emptyMap())
        assertEquals(listOf(LocalDate(2026, 3, 12)), events[0].rdate)
        assertEquals(emptyList(), events[0].exdate)
    }
}
