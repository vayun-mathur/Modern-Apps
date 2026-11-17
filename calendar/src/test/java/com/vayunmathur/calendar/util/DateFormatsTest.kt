package com.vayunmathur.calendar.util
import com.vayunmathur.calendar.domain.toIcalUtcDateTime
import com.vayunmathur.calendar.domain.toIcalBasic
import com.vayunmathur.calendar.domain.parseIcalOccurrenceDate
import com.vayunmathur.calendar.domain.parseIcalBasicDate

import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class DateFormatsTest {

    @Test
    fun timedOccurrenceIsWrittenAsAUtcDateTime() {
        // 14:30 in New York on a summer date is 18:30 UTC.
        val value = LocalDate(2025, 7, 4)
            .toIcalUtcDateTime(LocalTime(14, 30), TimeZone.of("America/New_York"))
        assertEquals("20250704T183000Z", value)
    }

    @Test
    fun aUtcDateTimeCanRollOverIntoTheNextDay() {
        val value = LocalDate(2025, 7, 4)
            .toIcalUtcDateTime(LocalTime(22, 0), TimeZone.of("America/New_York"))
        assertEquals("20250705T020000Z", value)
    }

    @Test
    fun allDayOccurrenceIsADateOnly() {
        assertEquals("20250704", LocalDate(2025, 7, 4).toIcalBasic())
    }

    @Test
    fun writtenOccurrencesParseBackToTheirLocalDate() {
        // The provider round-trip the RDATE column relies on: what toIcalUtcDateTime writes has to
        // come back out as the same calendar day it was written for, including when the UTC value
        // lands on the following day.
        val zone = TimeZone.of("America/New_York")
        for (time in listOf(LocalTime(9, 0), LocalTime(22, 0), LocalTime(23, 59))) {
            val date = LocalDate(2025, 7, 4)
            val written = date.toIcalUtcDateTime(time, zone)
            assertEquals(date, parseIcalOccurrenceDate(written, zone), "round trip at $time")
        }
        val allDay = LocalDate(2025, 7, 4)
        assertEquals(allDay, parseIcalOccurrenceDate(allDay.toIcalBasic(), TimeZone.UTC))
    }

    @Test
    fun readingAUtcOccurrenceInTheWrongZoneWouldMoveIt() {
        // Guards the reason parseIcalOccurrenceDate takes a zone at all: the same value is July 4 in
        // New York and July 5 in UTC.
        val written = LocalDate(2025, 7, 4).toIcalUtcDateTime(LocalTime(22, 0), TimeZone.of("America/New_York"))
        assertEquals(LocalDate(2025, 7, 4), parseIcalOccurrenceDate(written, TimeZone.of("America/New_York")))
        assertEquals(LocalDate(2025, 7, 5), parseIcalOccurrenceDate(written, TimeZone.UTC))
    }

    @Test
    fun aFloatingDateTimeFallsBackToItsLeadingDate() {
        // No 'Z' and no offset, so there is nothing to resolve against.
        assertEquals(LocalDate(2025, 7, 4), parseIcalOccurrenceDate("20250704T090000", TimeZone.UTC))
    }

    @Test
    fun occurrencesWrittenByOtherClientsStillParse() {
        // Google Calendar and friends prefix RDATE values with parameters; Event.getAllEvents strips
        // everything up to the last colon before handing the value over.
        val zone = TimeZone.of("America/New_York")
        assertEquals(
            LocalDate(2025, 7, 4),
            parseIcalOccurrenceDate("TZID=America/New_York:20250704T090000".substringAfterLast(':'), zone),
        )
        assertEquals(
            LocalDate(2025, 7, 4),
            parseIcalOccurrenceDate("VALUE=DATE:20250704".substringAfterLast(':'), zone),
        )
    }

    @Test
    fun garbageIsNotADate() {
        assertNull(parseIcalOccurrenceDate("", TimeZone.UTC))
        assertNull(parseIcalOccurrenceDate("not-a-date", TimeZone.UTC))
        assertNull(parseIcalBasicDate(""))
    }
}
