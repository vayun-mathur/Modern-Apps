package com.vayunmathur.astronomy.ui

import com.vayunmathur.astronomy.domain.engine.TimeEngine
import kotlinx.datetime.TimeZone
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

/** Rise/set times must be shown as local wall-clock, not raw UTC instants. */
@OptIn(ExperimentalTime::class)
class JdLocalFormatTest {

    @Test
    fun `formats in utc as iso date and time`() {
        // 2025-06-21T12:30:00Z
        val jd = TimeEngine.instantToJulianDate(Instant.parse("2025-06-21T12:30:00Z"))
        assertEquals("2025-06-21 12:30", formatJdInZone(jd, TimeZone.UTC))
    }

    @Test
    fun `shifts by the zone offset`() {
        val jd = TimeEngine.instantToJulianDate(Instant.parse("2025-06-21T12:30:00Z"))
        // Denver is on MDT (UTC-6) in June; Sydney is on AEST (UTC+10).
        assertEquals("2025-06-21 06:30", formatJdInZone(jd, TimeZone.of("America/Denver")))
        assertEquals("2025-06-21 22:30", formatJdInZone(jd, TimeZone.of("Australia/Sydney")))
    }
}
