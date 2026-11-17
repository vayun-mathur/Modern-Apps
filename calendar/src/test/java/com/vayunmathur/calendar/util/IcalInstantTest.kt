package com.vayunmathur.calendar.util
import com.vayunmathur.calendar.domain.icalLocalDateTime
import com.vayunmathur.calendar.domain.icalUtcDateTime

import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Millis-based ICS formatting cross-checked against java.time: the UTC instant form,
 * the zone wall-time form, and the winter/summer offset split across a DST boundary.
 *
 * The LocalDate-based writers live in DateFormatsTest; only the millis overloads are
 * covered here.
 */
class IcalInstantTest {

    private val utcFormat = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'")
        .withZone(ZoneId.of("UTC"))

    private fun millis(year: Int, month: Int, day: Int, hour: Int, minute: Int): Long {
        val zone = TimeZone.of("America/New_York")
        return LocalDateTime(year, month, day, hour, minute).toInstant(zone).toEpochMilliseconds()
    }

    @Test
    fun `the epoch formats as the ICS origin`() {
        assertEquals("19700101T000000Z", icalUtcDateTime(0))
    }

    @Test
    fun `a UTC instant matches java time`() {
        val at = millis(2026, 1, 15, 9, 0)
        val expected = utcFormat.format(java.time.Instant.ofEpochMilli(at))
        assertEquals(expected, icalUtcDateTime(at))
    }

    @Test
    fun `the wall form keeps the zone-local clock time`() {
        val at = millis(2026, 7, 15, 9, 0)
        val wall = icalLocalDateTime(at, TimeZone.of("America/New_York"))
        assertEquals("20260715T090000", wall)
    }

    @Test
    fun `summer and winter share a wall time but not an instant`() {
        assertEquals("20260715T130000Z", icalUtcDateTime(millis(2026, 7, 15, 9, 0)))
        assertEquals("20260115T140000Z", icalUtcDateTime(millis(2026, 1, 15, 9, 0)))
    }

    @Test
    fun `a late evening maps onto the next UTC day`() {
        val at = millis(2026, 7, 4, 22, 0)
        assertEquals("20260705T020000Z", icalUtcDateTime(at))
        val wall = icalLocalDateTime(at, TimeZone.of("America/New_York"))
        assertEquals("20260704T220000", wall)
    }
}
