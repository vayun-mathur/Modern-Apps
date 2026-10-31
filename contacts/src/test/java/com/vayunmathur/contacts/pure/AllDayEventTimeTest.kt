package com.vayunmathur.contacts.pure

import com.vayunmathur.contacts.data.ALL_DAY_EVENT_TIMEZONE
import com.vayunmathur.contacts.data.allDayEventEndMillis
import com.vayunmathur.contacts.data.allDayEventStartMillis
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Instant

/**
 * Pins the all-day UTC-midnight contract for birthday/anniversary calendar
 * rows (see issue #859): DTSTART must be UTC midnight with EVENT_TIMEZONE
 * "UTC", independent of the device zone and of DST.
 *
 * The previous writer anchored the wall date at local midnight in the
 * device zone, baking the sync moment's UTC offset into DTSTART. Expanding
 * the FREQ=YEARLY recurrence under a different DST regime then slid the
 * instance across the UTC-midnight boundary, showing birthdays a day early
 * while DST was in effect (April-October in US/EU zones).
 */
class AllDayEventTimeTest {

    @Test
    fun eventTimezoneIsUtc() {
        assertEquals("UTC", ALL_DAY_EVENT_TIMEZONE)
    }

    @Test
    fun startIsUtcMidnightInDstZones() {
        // 23 Oct: DST is in effect in New York and Berlin, standard time in
        // Sydney. The stored instant must be 00:00 UTC on the wall date in
        // every case — never local midnight, which would be 04:00/05:00 UTC
        // before and 05:00/04:00 UTC after the transition.
        val start = LocalDate(2025, 10, 23).allDayEventStartMillis()
        assertEquals(Instant.parse("2025-10-23T00:00:00Z").toEpochMilliseconds(), start)
    }

    @Test
    fun startIsStableAcrossDstTransitions() {
        // Same wall date evaluated around each zone's spring-forward and
        // fall-back: the instant must not move, or yearly recurrences drift.
        val zones = listOf("America/New_York", "Europe/Berlin", "Australia/Sydney")
        val dates = listOf(
            LocalDate(2025, 3, 8),
            LocalDate(2025, 3, 10),
            LocalDate(2025, 10, 23),
            LocalDate(2025, 11, 3),
            LocalDate(2025, 1, 15),
            LocalDate(2025, 7, 15),
        )
        for (zoneId in zones) {
            TimeZone.of(zoneId)
            for (date in dates) {
                val start = date.allDayEventStartMillis()
                assertEquals(
                    date,
                    Instant.fromEpochMilliseconds(start).toLocalDateTime(TimeZone.UTC).date,
                    "wall date must round-trip through UTC in $zoneId for $date",
                )
            }
        }
    }

    @Test
    fun localMidnightWouldHaveShifted() {
        // Documents the old bug: local midnight of the same wall date is a
        // different instant per DST regime, which is what moved birthdays.
        // (Oct is EDT/UTC-4, Jan is EST/UTC-5, so Oct local midnight is one
        // hour earlier in the day than the same wall-clock span in January.)
        val oct = LocalDate(2025, 10, 23).atStartOfDayIn(TimeZone.of("America/New_York"))
        val jan = LocalDate(2025, 1, 23).atStartOfDayIn(TimeZone.of("America/New_York"))
        assertEquals(
            -3_600_000L,
            oct.toEpochMilliseconds() - jan.toEpochMilliseconds() - 273 * 86_400_000L,
            "Oct local midnight carries a different UTC offset than Jan",
        )
    }

    @Test
    fun endIsExclusiveNextUtcMidnight() {
        val start = LocalDate(2025, 10, 23).allDayEventStartMillis()
        assertEquals(
            Instant.parse("2025-10-24T00:00:00Z").toEpochMilliseconds(),
            allDayEventEndMillis(start),
        )
    }

    @Test
    fun leapDayAnchorsAtUtcMidnight() {
        val start = LocalDate(2024, 2, 29).allDayEventStartMillis()
        assertEquals(Instant.parse("2024-02-29T00:00:00Z").toEpochMilliseconds(), start)
        assertEquals(
            LocalDate(2024, 2, 29),
            Instant.fromEpochMilliseconds(start).toLocalDateTime(TimeZone.UTC).date,
        )
    }

    @Test
    fun noYearAnchorDateAnchorsAtUtcMidnight() {
        // No-year birthdays normalize to the leap anchor year 2000 so Feb 29
        // still recurs; the UTC-midnight rule applies to the anchor too.
        val start = LocalDate(2000, 2, 29).allDayEventStartMillis()
        assertEquals(Instant.parse("2000-02-29T00:00:00Z").toEpochMilliseconds(), start)
    }
}
