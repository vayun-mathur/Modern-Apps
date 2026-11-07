package com.vayunmathur.everysync.domain.format

import com.vayunmathur.everysync.data.RemoteEvent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** ICalendar branches the first pass left: timezones, folding, defaults, escaping, malformed input. */
class Formats2Test {

    @Test fun parseZonedRecurringAndFolded() {
        val text = buildString {
            append("BEGIN:VCALENDAR\r\nVERSION:2.0\r\n")
            append("BEGIN:VEVENT\r\n")
            append("UID:evt-1\r\n")
            append("DTSTART;TZID=America/New_York:20240115T090000\r\n")
            append("DTEND;TZID=America/New_York:20240115T100000\r\n")
            append("SUMMARY:Long title that was\r\n folded across lines\r\n")
            append("DESCRIPTION:Line1\\nLine2\\, with\\; escapes\\\\end\r\n")
            append("LOCATION:Room 5\r\n")
            append("RRULE:FREQ=WEEKLY;BYDAY=MO\r\n")
            append("RECURRENCE-ID:20240122T140000Z\r\n")
            append("END:VEVENT\r\n")
            append("BEGIN:VEVENT\r\n")
            append("DTSTART;VALUE=DATE:20240229\r\n")
            append("SUMMARY:Leap day all-day\r\n")
            append("END:VEVENT\r\n")
            append("END:VCALENDAR\r\n")
        }
        val events = ICalendar.parse(text, "cal-9")
        assertEquals(2, events.size)
        val zoned = events[0]
        assertEquals("evt-1", zoned.uid)
        assertEquals("America/New_York", zoned.timezone)
        assertEquals(3_600_000L, zoned.endMillis - zoned.startMillis)
        assertEquals("Long title that wasfolded across lines", zoned.summary)
        assertEquals("Line1\nLine2, with; escapes\\end", zoned.description)
        assertEquals("Room 5", zoned.location)
        assertEquals("FREQ=WEEKLY;BYDAY=MO", zoned.rrule)
        assertEquals("20240122T140000Z", zoned.recurrenceId)
        assertEquals(false, zoned.allDay)
        val allDay = events[1]
        assertEquals(true, allDay.allDay)
        // No DTEND on an all-day event defaults to a full day
        assertEquals(86_400_000L, allDay.endMillis - allDay.startMillis)
        assertEquals("cal-9", allDay.calendarId)
        // Missing UID falls back to the supplied fallback
        assertTrue(allDay.uid.isNotBlank())
    }

    @Test fun parseFallbackUidAndMalformed() {
        val noUid = "BEGIN:VEVENT\nDTSTART:20240101T000000Z\nSUMMARY:X\nEND:VEVENT\n"
        assertEquals("fallback-1", ICalendar.parse(noUid, "c", fallbackUid = "fallback-1").single().uid)
        // Garbage dates fall back to epoch + one hour rather than throwing
        val bad = "BEGIN:VEVENT\nUID:bad\nDTSTART:not-a-date\nEND:VEVENT\n"
        val ev = ICalendar.parse(bad, "c").single()
        assertEquals(0L, ev.startMillis); assertEquals(3_600_000L, ev.endMillis)
        // Lines outside events and properties without colons are ignored
        val messy = "BEGIN:VCALENDAR\nJUNKLINE\nBEGIN:VEVENT\nUID:ok\nDTSTART:20240101T120000Z\nEND:VEVENT\nEND:VCALENDAR\n"
        assertEquals(1, ICalendar.parse(messy, "c").size)
        assertTrue(ICalendar.parse("", "c").isEmpty())
        assertTrue(ICalendar.parse("no events here", "c").isEmpty())
        // Tab-folded continuation also unfolds
        val tabbed = "BEGIN:VEVENT\nUID:t\nSUMMARY:ab\n\tcd\nDTSTART:20240101T000000Z\nEND:VEVENT\n"
        assertEquals("abcd", ICalendar.parse(tabbed, "c").single().summary)
    }

    @Test fun serializeRoundTripsTimedAllDayAndRecurring() {
        val timed = RemoteEvent(
            uid = "rt-1", calendarId = "c", summary = "Meet; greet, then\nleave",
            description = "Bring \\ stuff", location = "Office",
            startMillis = 1_705_314_000_000L, endMillis = 1_705_317_600_000L,
            rrule = "RRULE:FREQ=DAILY", recurrenceId = "20240116T090000Z",
        )
        val parsed = ICalendar.parse(ICalendar.serialize(timed), "c").single()
        assertEquals(timed.uid, parsed.uid)
        assertEquals(timed.summary, parsed.summary)
        assertEquals(timed.description, parsed.description)
        assertEquals(timed.location, parsed.location)
        assertEquals(timed.startMillis, parsed.startMillis)
        assertEquals(timed.endMillis, parsed.endMillis)
        assertEquals("FREQ=DAILY", parsed.rrule)
        assertEquals(timed.recurrenceId, parsed.recurrenceId)
        assertTrue(ICalendar.serialize(timed).contains("RRULE:FREQ=DAILY"))

        val allDay = RemoteEvent(uid = "rt-2", calendarId = "c", summary = "Holiday", startMillis = 1_709_164_800_000L, endMillis = 1_709_251_200_000L, allDay = true)
        val parsedDay = ICalendar.parse(ICalendar.serialize(allDay), "c").single()
        assertEquals(true, parsedDay.allDay)
        assertEquals(allDay.startMillis, parsedDay.startMillis)
        assertEquals(allDay.endMillis, parsedDay.endMillis)
        // Blank optional fields are omitted from the wire form
        val bare = ICalendar.serialize(RemoteEvent(uid = "rt-3", calendarId = "c", startMillis = 0, endMillis = 1000))
        assertTrue(!bare.contains("SUMMARY")); assertTrue(!bare.contains("RRULE"))
    }

    @Test fun vCardExtraBranches() {
        // Escapes and structured name fields survive a round trip through VCard helpers
        val parsed = VCard.parse("BEGIN:VCARD\r\nVERSION:3.0\r\nFN:Doe\\, John\r\nN:Doe;John;;Dr.;Jr.\r\nORG:Acme Inc\r\nTITLE:Dev\r\nEND:VCARD\r\n")
        assertEquals("Doe, John", parsed.displayName)
        assertEquals("Doe", parsed.lastName); assertEquals("John", parsed.firstName)
        assertEquals("Dr.", parsed.prefix); assertEquals("Jr.", parsed.suffix)
        assertEquals("Acme Inc", parsed.organization)
        assertTrue(VCard.serialize(parsed).contains("FN:"))
        assertNull(VCard.parse("BEGIN:VCARD\nEND:VCARD\n").displayName.ifBlank { null })
    }
}
