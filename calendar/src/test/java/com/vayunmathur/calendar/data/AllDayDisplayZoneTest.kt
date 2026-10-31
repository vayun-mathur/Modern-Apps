package com.vayunmathur.calendar.data

import kotlinx.datetime.DatePeriod
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.plus
import kotlinx.datetime.toInstant
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Pins the all-day render contract behind issue #859: every wall-time
 * derived from an all-day event or instance must resolve in UTC, never in
 * the row's stored zone. Legacy birthday rows were synced at local midnight
 * with a named zone; converting their instants in that zone slides the wall
 * date across midnight while DST is in effect (April-October birthdays
 * showing a day early in US/EU zones).
 */
class AllDayDisplayZoneTest {

    private fun utcMidnight(date: LocalDate): Long =
        date.atStartOfDayIn(TimeZone.UTC).toEpochMilliseconds()

    private fun localMidnightInstance(date: LocalDate, zoneId: String): Instance {
        // A legacy row: wall date anchored at local midnight (plus an optional
        // DST-slide of the begin instant, as the provider expansion produced).
        val zone = TimeZone.of(zoneId)
        val begin = date.atStartOfDayIn(zone).toEpochMilliseconds()
        return Instance(
            id = 1L,
            eventID = 1L,
            begin = begin,
            end = begin + 86_400_000L,
            timezone = zoneId,
            allDay = true,
            eventTitle = "Birthday",
            color = 0,
            rrule = null,
        )
    }

    private fun allDayEvent(date: LocalDate, zoneId: String) = Event(
        id = 1,
        calendarID = 1,
        title = "Birthday",
        description = "",
        location = "",
        color = null,
        start = utcMidnight(date),
        end = utcMidnight(date + DatePeriod(days = 1)),
        timezone = zoneId,
        allDay = true,
        rrule = null,
    )

    private fun timedEvent(date: LocalDate, zoneId: String) = Event(
        id = 2,
        calendarID = 1,
        title = "Meeting",
        description = "",
        location = "",
        color = null,
        start = LocalDateTime(date, LocalTime(9, 0))
            .toInstant(TimeZone.of(zoneId)).toEpochMilliseconds(),
        end = LocalDateTime(date, LocalTime(10, 0))
            .toInstant(TimeZone.of(zoneId)).toEpochMilliseconds(),
        timezone = zoneId,
        allDay = false,
        rrule = null,
    )

    @Test
    fun utcRowDisplaysOnItsWallDateInDstZones() {
        val date = LocalDate(2025, 10, 23)
        for (zoneId in listOf("America/New_York", "Europe/Berlin", "Australia/Sydney")) {
            val instance = localMidnightInstance(date, "UTC")
                .copy(begin = utcMidnight(date), end = utcMidnight(date) + 86_400_000L)
            assertEquals(date, instance.startDateTimeDisplay.date, zoneId)
            assertEquals(listOf(date), instance.spanDays, zoneId)
        }
    }

    @Test
    fun legacyLocalMidnightRowStartStaysOnItsWallDate() {
        // The wall date a legacy row starts on must survive UTC resolution:
        // 23 Oct 00:00 in New York (EDT) is 04:00 UTC the same day, and in
        // Berlin (CEST) 22:00 UTC on the 22nd — the start wall date is what
        // the agenda labels, and UTC resolution keeps New York's on the 23rd
        // instead of following the stored zone across a DST-shifted boundary.
        // (Legacy rows whose local-midnight window straddles two UTC days are
        // exactly what the UTC-midnight writer plus re-sync migration remove.)
        val date = LocalDate(2025, 10, 23)
        assertEquals(date, localMidnightInstance(date, "America/New_York").startDateTime.date)
        assertEquals(date, localMidnightInstance(date, "America/New_York").startDateTimeDisplay.date)
    }

    @Test
    fun eventDisplayDatesResolveInUtcForAllDay() {
        val date = LocalDate(2025, 10, 23)
        for (zoneId in listOf("America/New_York", "Europe/Berlin", "Australia/Sydney")) {
            val event = allDayEvent(date, zoneId)
            assertEquals(date, event.startDateTimeDisplay.date, zoneId)
            assertEquals(date + DatePeriod(days = 1), event.endDateTimeDisplay.date, zoneId)
        }
    }

    @Test
    fun timedEventsStillUseTheirOwnZone() {
        val date = LocalDate(2025, 7, 4)
        val event = timedEvent(date, "America/New_York")
        assertEquals(date, event.startDateTimeDisplay.date)
        assertEquals(LocalTime(9, 0), event.startDateTimeDisplay.time)
    }

    @Test
    fun leapDayAllDaySpansOneDay() {
        val date = LocalDate(2024, 2, 29)
        val instance = localMidnightInstance(date, "UTC")
            .copy(begin = utcMidnight(date), end = utcMidnight(date) + 86_400_000L)
        assertEquals(listOf(date), instance.spanDays)
    }
}
