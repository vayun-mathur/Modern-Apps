package com.vayunmathur.health.ui

import androidx.test.core.app.ApplicationProvider
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.datetime.LocalDate
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Tests for the medical date and duration formatters.
 *
 * These strings land in long-lived records, where an ambiguous date is a
 * real defect rather than a cosmetic one.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class HealthFormatTest {

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()

    // The formatters render in the device zone/locale by design, so the
    // expected wall-clock strings below are only meaningful under a pinned
    // zone and locale: 2026-03-04T09:30:00Z is 09:30 in UTC, 04:30 in
    // New York and 01:30 in Los Angeles, and the suite must not depend on
    // the host it happens to run on.
    private lateinit var defaultTimeZone: java.util.TimeZone
    private lateinit var defaultLocale: java.util.Locale

    @kotlin.test.BeforeTest
    fun pinZoneAndLocale() {
        defaultTimeZone = java.util.TimeZone.getDefault()
        defaultLocale = java.util.Locale.getDefault()
        java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone("UTC"))
        java.util.Locale.setDefault(java.util.Locale.US)
    }

    @kotlin.test.AfterTest
    fun restoreZoneAndLocale() {
        java.util.TimeZone.setDefault(defaultTimeZone)
        java.util.Locale.setDefault(defaultLocale)
    }

    @Test
    fun `durations render as hours and minutes`() {
        assertEquals("1h 30m", hoursMinutesString(context, 90))
        assertEquals("25m", hoursMinutesString(context, 25))
        assertEquals("0m", hoursMinutesString(context, 0))
    }

    @Test
    fun `medical dates spell out the day`() {
        // Unambiguous long form: the year matters years after the fact.
        val text = medicalDateString(java.time.Instant.parse("2026-03-04T09:30:00Z"))
        assertTrue("2026" in text, "year missing from $text")
        assertTrue("4" in text, "day missing from $text")
    }

    @Test
    fun `medical datetimes carry the time of day`() {
        val text = medicalDateTimeString(java.time.Instant.parse("2026-03-04T09:30:00Z"), true)
        // Short date plus the time: this is the compact form for dose timestamps.
        // Zone and locale are pinned to UTC / US in setup, so the instant
        // renders as its UTC wall time.
        assertTrue("3/4/26" in text || "2026" in text, "date missing from $text")
        assertTrue("09:30" in text, "time missing from $text")
    }

    @Test
    fun `detail lines join present parts with a middle dot`() {
        assertEquals("a \u00B7 b", detailLine("a", "b"))
        assertEquals("a", detailLine("a", null, "", "  "))
        assertEquals("", detailLine(null, ""))
    }

    @Test
    fun `instants convert to their local date`() {
        val date: LocalDate = java.time.Instant.parse("2026-03-04T09:30:00Z").toLocalDate()
        assertEquals(2026, date.year)
        assertEquals(3, date.monthNumber)
    }

    @Test
    fun `local dates convert to midnight instants`() {
        val instant = LocalDate(2026, 3, 4).toInstant()
        assertEquals(LocalDate(2026, 3, 4), instant.toLocalDate())
    }
}
