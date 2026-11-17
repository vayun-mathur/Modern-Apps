package com.vayunmathur.calendar.util
import com.vayunmathur.calendar.domain.writeIcs
import com.vayunmathur.calendar.domain.RRule

import com.vayunmathur.calendar.data.Event
import com.vayunmathur.calendar.ui.parseICSFile
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.toInstant
import java.io.StringWriter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Exercises [writeIcs] by reading its output back with [parseICSFile]: the file the app writes has
 * to be a file the app can read.
 */
class IcsExportTest {

    private val newYork = TimeZone.of("America/New_York")

    private fun timedEvent(
        title: String = "Standup",
        description: String = "",
        location: String = "",
        zone: TimeZone = newYork,
        start: LocalDateTime = LocalDateTime(2025, 7, 4, 9, 30),
        end: LocalDateTime = LocalDateTime(2025, 7, 4, 10, 0),
        rrule: RRule? = null,
        exdate: List<LocalDate> = emptyList(),
        rdate: List<LocalDate> = emptyList(),
        reminders: List<Int> = emptyList(),
    ) = Event(
        id = 42,
        calendarID = 1,
        title = title,
        description = description,
        location = location,
        color = null,
        start = start.toInstant(zone).toEpochMilliseconds(),
        end = end.toInstant(zone).toEpochMilliseconds(),
        timezone = zone.id,
        allDay = false,
        rrule = rrule,
        exdate = exdate,
        rdate = rdate,
        reminders = reminders,
    )

    private fun allDayEvent(
        start: LocalDate = LocalDate(2025, 7, 4),
        endExclusive: LocalDate = LocalDate(2025, 7, 6),
        rdate: List<LocalDate> = emptyList(),
    ) = Event(
        id = 7,
        calendarID = 1,
        title = "Holiday",
        description = "",
        location = "",
        color = null,
        start = start.atStartOfDayIn(TimeZone.UTC).toEpochMilliseconds(),
        end = endExclusive.atStartOfDayIn(TimeZone.UTC).toEpochMilliseconds(),
        timezone = "UTC",
        allDay = true,
        rrule = null,
        rdate = rdate,
    )

    private fun write(vararg events: Event): String {
        val out = StringWriter()
        writeIcs(events.toList(), out, now = 0L)
        return out.toString()
    }

    private fun roundTrip(vararg events: Event): List<Event> =
        parseICSFile(write(*events).byteInputStream())

    private fun lines(ics: String) = ics.split("\r\n").filter { it.isNotEmpty() }

    @Test
    fun theWrapperIsWellFormedAndCrlfTerminated() {
        val ics = write(timedEvent())
        assertTrue(ics.endsWith("END:VCALENDAR\r\n"), "every line ends with CRLF")
        assertTrue("\n" !in ics.replace("\r\n", ""), "no bare LF")
        val lines = lines(ics)
        assertEquals("BEGIN:VCALENDAR", lines.first())
        assertTrue("VERSION:2.0" in lines)
        assertTrue(lines.any { it.startsWith("PRODID:") })
        assertTrue("BEGIN:VEVENT" in lines)
        assertTrue(lines.any { it.startsWith("UID:42@") })
        assertTrue("DTSTAMP:19700101T000000Z" in lines)
    }

    @Test
    fun allDayEventsUseDateValuesWithAnExclusiveEnd() {
        val ics = write(allDayEvent())
        val lines = lines(ics)
        assertTrue("DTSTART;VALUE=DATE:20250704" in lines, ics)
        assertTrue("DTEND;VALUE=DATE:20250706" in lines, ics)

        val imported = roundTrip(allDayEvent()).single()
        assertTrue(imported.allDay)
        assertEquals(LocalDate(2025, 7, 4), imported.startDateTimeDisplay.date)
        assertEquals(LocalDate(2025, 7, 6), imported.endDateTimeDisplay.date)
    }

    @Test
    fun timedEventsKeepTheirTimeZone() {
        val ics = write(timedEvent())
        assertTrue("DTSTART;TZID=America/New_York:20250704T093000" in lines(ics), ics)

        val imported = roundTrip(timedEvent()).single()
        assertEquals("America/New_York", imported.timezone)
        assertEquals(LocalDateTime(2025, 7, 4, 9, 30), imported.startDateTimeDisplay)
        assertEquals(LocalDateTime(2025, 7, 4, 10, 0), imported.endDateTimeDisplay)
    }

    @Test
    fun aUtcEventNeedsNoTimeZoneParameter() {
        val event = timedEvent(zone = TimeZone.UTC)
        assertTrue("DTSTART:20250704T093000Z" in lines(write(event)), write(event))
        assertEquals(
            LocalDateTime(2025, 7, 4, 9, 30),
            roundTrip(event).single().startDateTimeDisplay,
        )
    }

    @Test
    fun recurrenceWithAnUntilSurvives() {
        val rule = RRule.EveryXWeeks(
            weeks = 1,
            daysOfWeek = listOf(kotlinx.datetime.DayOfWeek.FRIDAY),
            endCondition = RRule.EndCondition.Until(LocalDate(2025, 12, 31)),
        )
        val ics = write(timedEvent(rrule = rule))
        assertTrue(lines(ics).any { it.startsWith("RRULE:FREQ=WEEKLY") }, ics)
        assertEquals(rule, roundTrip(timedEvent(rrule = rule)).single().rrule)
    }

    @Test
    fun recurrenceWithACountSurvives() {
        val rule = RRule.EveryXDays(days = 2, endCondition = RRule.EndCondition.Count(5))
        assertEquals(rule, roundTrip(timedEvent(rrule = rule)).single().rrule)
    }

    @Test
    fun excludedAndExtraDatesSurviveAsMultiValueProperties() {
        val rule = RRule.EveryXDays(days = 1, endCondition = RRule.EndCondition.Never)
        val exdate = listOf(LocalDate(2025, 7, 6), LocalDate(2025, 7, 7))
        val rdate = listOf(LocalDate(2025, 7, 10), LocalDate(2025, 7, 12))
        val event = timedEvent(rrule = rule, exdate = exdate, rdate = rdate)

        val lines = lines(write(event))
        assertEquals(1, lines.count { it.startsWith("EXDATE") }, "one multi-value EXDATE line")
        assertEquals(1, lines.count { it.startsWith("RDATE") }, "one multi-value RDATE line")

        val imported = roundTrip(event).single()
        assertEquals(exdate, imported.exdate)
        assertEquals(rdate, imported.rdate)
    }

    @Test
    fun allDayExtraDatesAreWrittenAsDates() {
        val rdate = listOf(LocalDate(2025, 7, 10))
        val ics = write(allDayEvent(rdate = rdate))
        assertTrue("RDATE;VALUE=DATE:20250710" in lines(ics), ics)
        assertEquals(rdate, roundTrip(allDayEvent(rdate = rdate)).single().rdate)
    }

    @Test
    fun textContainingSeparatorsAndNewlinesRoundTrips() {
        val event = timedEvent(
            title = "Lunch, then a walk; maybe",
            description = "First line\nSecond line\\ with a backslash",
            location = "Room 1, Building 2; floor 3",
        )
        val ics = write(event)
        assertTrue("SUMMARY:Lunch\\, then a walk\\; maybe" in lines(ics), ics)

        val imported = roundTrip(event).single()
        assertEquals("Lunch, then a walk; maybe", imported.title)
        assertEquals("First line\nSecond line\\ with a backslash", imported.description)
        assertEquals("Room 1, Building 2; floor 3", imported.location)
    }

    @Test
    fun longTextIsFoldedAndUnfoldsBackToItself() {
        // Spaces at the 75-octet boundaries are the case that breaks a naive unfolder.
        val title = (1..30).joinToString(" ") { "word$it" }
        val ics = write(timedEvent(title = title))
        val summaryLines = lines(ics).filter { it.startsWith("SUMMARY:") || it.startsWith(" ") }
        assertTrue(summaryLines.size > 1, "the summary should span several lines")
        assertTrue(lines(ics).all { it.toByteArray().size <= 75 }, "no line exceeds 75 octets")
        assertEquals(title, roundTrip(timedEvent(title = title)).single().title)
    }

    @Test
    fun nonAsciiTextFoldsOnOctetsAndRoundTrips() {
        val title = "é".repeat(60)
        val ics = write(timedEvent(title = title))
        assertTrue(lines(ics).all { it.toByteArray().size <= 75 }, "folded on octets, not chars")
        assertEquals(title, roundTrip(timedEvent(title = title)).single().title)
    }

    @Test
    fun remindersBecomeDisplayAlarms() {
        val lines = lines(write(timedEvent(reminders = listOf(0, 15))))
        assertEquals(2, lines.count { it == "BEGIN:VALARM" })
        assertTrue("TRIGGER:PT0S" in lines)
        assertTrue("TRIGGER:-PT15M" in lines)
    }

    @Test
    fun anAlarmDoesNotOverwriteTheEventDescriptionOnReimport() {
        // A DISPLAY alarm needs its own DESCRIPTION, and the parser's property map is flat, so an
        // unskipped VALARM would replace the event's description with the alarm's.
        val event = timedEvent(description = "Bring the agenda", reminders = listOf(10))
        val imported = roundTrip(event).single()
        assertEquals("Bring the agenda", imported.description)
    }

    @Test
    fun anOffsetStyleZoneIdFallsBackToUtc() {
        // "GMT+05:30" cannot be a bare TZID parameter, and quoting it would still break a parser
        // that splits on the first colon.
        val event = timedEvent(zone = TimeZone.of("GMT+05:30"))
        assertTrue("DTSTART:20250704T040000Z" in lines(write(event)), write(event))
        assertEquals(
            event.start,
            roundTrip(event).single().start,
        )
    }

    @Test
    fun everyEventInTheListIsWritten() {
        val imported = roundTrip(timedEvent(), allDayEvent())
        assertEquals(2, imported.size)
        assertEquals(listOf("Standup", "Holiday"), imported.map { it.title })
    }
}
