package com.vayunmathur.everysync.domain.format

import com.vayunmathur.everysync.data.RemoteEvent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ICalendarTest {

    private fun event(
        uid: String = "u1",
        summary: String = "Standup",
        startMillis: Long = 1700000000000L,
        endMillis: Long = 1700003600000L,
    ): RemoteEvent = RemoteEvent(
        uid = uid,
        calendarId = "cal",
        summary = summary,
        description = "desc",
        location = "room",
        startMillis = startMillis,
        endMillis = endMillis,
        rrule = "FREQ=DAILY",
    )

    // ---- round trips ----

    @Test
    fun serializeThenParseRoundTripsAllFields() {
        val original = event()
        val parsed = ICalendar.parse(ICalendar.serialize(original), "cal")
        assertEquals(1, parsed.size)
        assertEquals("u1", parsed[0].uid)
        assertEquals("cal", parsed[0].calendarId)
        assertEquals("Standup", parsed[0].summary)
        assertEquals("desc", parsed[0].description)
        assertEquals("room", parsed[0].location)
        assertEquals(1700000000000L, parsed[0].startMillis)
        assertEquals(1700003600000L, parsed[0].endMillis)
        assertEquals(false, parsed[0].allDay)
        assertEquals("FREQ=DAILY", parsed[0].rrule)
        assertNull(parsed[0].recurrenceId)
    }

    @Test
    fun escapingRoundTripsSeparatorsCommasAndNewlines() {
        val original = event(summary = "Lunch; with, Bob").copy(
            description = "Line1\nLine2",
            location = "Bldg; A, Room 1",
        )
        val parsed = ICalendar.parse(ICalendar.serialize(original), "cal")
        assertEquals(1, parsed.size)
        assertEquals("Lunch; with, Bob", parsed[0].summary)
        assertEquals("Line1\nLine2", parsed[0].description)
        assertEquals("Bldg; A, Room 1", parsed[0].location)
    }

    @Test
    fun rrulePrefixIsNotDoubledOnSerialize() {
        val withPrefix = event().copy(rrule = "RRULE:FREQ=WEEKLY")
        val text = ICalendar.serialize(withPrefix)
        assertTrue(text.contains("RRULE:FREQ=WEEKLY"))
        assertTrue(!text.contains("RRULE:RRULE:"))
        assertEquals("FREQ=WEEKLY", ICalendar.parse(text, "cal")[0].rrule)
    }

    // ---- all-day events ----

    @Test
    fun parseReadsAllDayDateValues() {
        val text = "BEGIN:VCALENDAR\r\nVERSION:2.0\r\nBEGIN:VEVENT\r\nUID:x\r\n" +
            "DTSTART;VALUE=DATE:20240102\r\nDTEND;VALUE=DATE:20240103\r\n" +
            "SUMMARY:Day off\r\nEND:VEVENT\r\nEND:VCALENDAR\r\n"
        val parsed = ICalendar.parse(text, "cal")
        assertEquals(1, parsed.size)
        assertEquals(true, parsed[0].allDay)
        assertTrue(parsed[0].endMillis > parsed[0].startMillis)
    }

    @Test
    fun allDaySerializeUsesDateValuesAndRoundTrips() {
        val original = event(startMillis = 1704153600000L, endMillis = 1704240000000L).copy(allDay = true)
        val text = ICalendar.serialize(original)
        assertTrue(text.contains("DTSTART;VALUE=DATE:"))
        val parsed = ICalendar.parse(text, "cal")
        assertEquals(1, parsed.size)
        assertEquals(true, parsed[0].allDay)
    }

    // ---- defaults and fallbacks ----

    @Test
    fun missingEndDefaultsToOneHour() {
        val text = "BEGIN:VCALENDAR\r\nBEGIN:VEVENT\r\nUID:x\r\n" +
            "DTSTART:20240102T030405Z\r\nEND:VEVENT\r\nEND:VCALENDAR\r\n"
        val parsed = ICalendar.parse(text, "cal")
        assertEquals(1, parsed.size)
        assertEquals(3600000L, parsed[0].endMillis - parsed[0].startMillis)
    }

    @Test
    fun allDayMissingEndDefaultsToOneDay() {
        val text = "BEGIN:VCALENDAR\r\nBEGIN:VEVENT\r\nUID:x\r\n" +
            "DTSTART;VALUE=DATE:20240102\r\nEND:VEVENT\r\nEND:VCALENDAR\r\n"
        val parsed = ICalendar.parse(text, "cal")
        assertEquals(1, parsed.size)
        assertEquals(true, parsed[0].allDay)
        assertEquals(86400000L, parsed[0].endMillis - parsed[0].startMillis)
    }

    @Test
    fun missingUidFallsBackToTheSuppliedUid() {
        val text = "BEGIN:VCALENDAR\r\nBEGIN:VEVENT\r\n" +
            "DTSTART:20240102T030405Z\r\nDTEND:20240102T040405Z\r\n" +
            "END:VEVENT\r\nEND:VCALENDAR\r\n"
        val parsed = ICalendar.parse(text, "cal", fallbackUid = "fb-1")
        assertEquals(1, parsed.size)
        assertEquals("fb-1", parsed[0].uid)
        assertEquals("cal", parsed[0].calendarId)
    }

    // ---- recurrence instances ----

    @Test
    fun recurrenceIdKeepsInstancesIndependent() {
        val text = "BEGIN:VCALENDAR\r\n" +
            "BEGIN:VEVENT\r\nUID:x\r\nDTSTART:20240102T030405Z\r\nDTEND:20240102T040405Z\r\n" +
            "END:VEVENT\r\n" +
            "BEGIN:VEVENT\r\nUID:x\r\nRECURRENCE-ID:20240109T030405Z\r\n" +
            "DTSTART:20240109T050405Z\r\nDTEND:20240109T060405Z\r\n" +
            "END:VEVENT\r\nEND:VCALENDAR\r\n"
        val parsed = ICalendar.parse(text, "cal")
        assertEquals(2, parsed.size)
        assertNull(parsed[0].recurrenceId)
        assertEquals("20240109T030405Z", parsed[1].recurrenceId)
    }

    // ---- malformed input ----

    @Test
    fun malformedLinesAndUnterminatedEventsAreIgnored() {
        assertTrue(ICalendar.parse("hello\nno-colon-here\n", "cal").isEmpty())
        val unterminated = "BEGIN:VCALENDAR\r\nBEGIN:VEVENT\r\nUID:x\r\nSUMMARY:Hi\r\n"
        assertTrue(ICalendar.parse(unterminated, "cal").isEmpty())
        assertTrue(ICalendar.parse("", "cal").isEmpty())
    }

    @Test
    fun literalBackslashSequencesRoundTrip() {
        // Value contains a literal backslash followed by 'n', ';' and ',':
        // on the wire the backslash is doubled, and parsing must not turn
        // the pair into a newline/semicolon/comma.
        val original = event(summary = "a\\nb\\;c\\,d\\\\e")
        val parsed = ICalendar.parse(ICalendar.serialize(original), "cal")
        assertEquals(original.summary, parsed[0].summary)
    }

    @Test
    fun continuationLinesAreUnfolded() {
        val text = "BEGIN:VCALENDAR\r\nBEGIN:VEVENT\r\nUID:x\r\nSUMMARY:Hello\r\n world\r\n" +
            "DTSTART:20240102T030405Z\r\nDTEND:20240102T040405Z\r\n" +
            "END:VEVENT\r\nEND:VCALENDAR\r\n"
        val parsed = ICalendar.parse(text, "cal")
        assertEquals(1, parsed.size)
        assertEquals("Helloworld", parsed[0].summary)
    }
}
