package com.vayunmathur.weather.platform

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.vayunmathur.weather.data.SavedLocation
import com.vayunmathur.weather.domain.TemperatureUnit
import com.vayunmathur.weather.network.Current
import com.vayunmathur.weather.network.Daily
import com.vayunmathur.weather.network.ForecastResponse
import com.vayunmathur.weather.network.Minutely15
import org.junit.runner.RunWith
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@RunWith(AndroidJUnit4::class)
class PlatformHelpersTest {

    private val ctx: Context get() = ApplicationProvider.getApplicationContext()

    private fun forecast(precipProb: Int = 0, wind: Double = 5.0) = ForecastResponse(
        latitude = 52.0, longitude = 13.0,
        current = Current(
            time = "2026-07-15T14:30", temperature = 25.0, apparentTemperature = 26.0,
            relativeHumidity = 50, weatherCode = 0, windSpeed = wind, windDirection = 90,
        ),
        daily = Daily(
            time = listOf("2026-07-15"),
            temperatureMax = listOf(27.0), temperatureMin = listOf(17.0),
            precipitationProbabilityMax = listOf(precipProb),
        ),
    )

    @Test fun summaryDryCalm() {
        val s = computeDaySummary(ctx, forecast(), TemperatureUnit.Celsius)
        assertTrue(s.contains("27°"), s)
        assertTrue(s.contains("17°"), s)
        assertFalse(s.lowercase().contains("rain"), s)
    }

    @Test fun summaryRainAndWind() {
        val s = computeDaySummary(ctx, forecast(precipProb = 80, wind = 40.0), TemperatureUnit.Fahrenheit)
        assertTrue(s.lowercase().contains("rain") || s.lowercase().contains("likely"), s)
    }

    @Test fun summaryShowersAndSlight() {
        assertTrue(computeDaySummary(ctx, forecast(precipProb = 50), TemperatureUnit.Celsius).isNotBlank())
        assertTrue(computeDaySummary(ctx, forecast(precipProb = 25), TemperatureUnit.Celsius).isNotBlank())
        // No daily/current at all still produces a string
        assertTrue(computeDaySummary(ctx, ForecastResponse(0.0, 0.0), TemperatureUnit.Celsius).isNotBlank())
    }

    private fun naiveUtc(epochSec: Long): String {
        val ldt = java.time.Instant.ofEpochSecond(epochSec).atZone(java.time.ZoneOffset.UTC)
        return "%04d-%02d-%02dT%02d:%02d".format(ldt.year, ldt.monthValue, ldt.dayOfMonth, ldt.hour, ldt.minute)
    }

    @Test fun nowcastNullAndEmpty() {
        assertNull(precipitationNowcast(ctx, null, 0))
        assertNull(precipitationNowcast(ctx, Minutely15(), 0))
        assertNull(precipitationNowcast(ctx, Minutely15(time = listOf("bogus"), precipitation = listOf(1.0)), 0))
    }

    @Test fun nowcastNoRain() {
        val now = System.currentTimeMillis() / 1000
        val series = Minutely15(
            time = listOf(naiveUtc(now + 900), naiveUtc(now + 1800)),
            precipitation = listOf(0.0, 0.0),
        )
        assertNotNull(precipitationNowcast(ctx, series, 0))
    }

    @Test fun nowcastRainSoonAndNow() {
        val now = System.currentTimeMillis() / 1000
        val soon = Minutely15(
            time = listOf(naiveUtc(now - 3600), naiveUtc(now + 1800)),
            precipitation = listOf(0.0, 1.0),
        )
        assertNotNull(precipitationNowcast(ctx, soon, 0))
        val raining = Minutely15(
            time = listOf(naiveUtc(now - 300)),
            precipitation = listOf(0.5),
        )
        assertNotNull(precipitationNowcast(ctx, raining, 0))
    }

    @Test fun systemTempUnitAndLocationPermission() {
        // Just exercises the branch; value depends on locale prefs.
        assertTrue(systemTempUnit() in TemperatureUnit.entries)
        assertFalse(LocationProvider.hasPermission(ctx))
    }

    @Test fun contractDefaults() {
        val units = DisplayUnits()
        assertEquals(TemperatureUnit.Celsius, units.temperature)
        val loc = SavedLocation(name = "Berlin", latitude = 52.0, longitude = 13.0)
        val state = LocationUiState(location = loc)
        assertNull(state.forecast); assertEquals(false, state.refreshing)
        val rows = LocationsUiState()
        assertTrue(rows.rows.isEmpty()); assertNull(rows.activeLocationId)
        // Noop actions must not throw
        WeatherActions.Noop.refreshAll()
        WeatherActions.Noop.toggleTime("2026-07-15T14:00")
        WeatherActions.Noop.toggleDay("2026-07-15")
        WeatherActions.Noop.clearSelection()
        WeatherActions.Noop.deleteLocation(loc)
        WeatherActions.Noop.reorderLocations(listOf(loc))
        assertEquals(loc, LocationRow(loc, "desc").location)
    }

    @Test fun formatAgoRanges() {
        val res = ctx.resources
        val now = 1_000_000_000_000L
        assertTrue(formatAgoInternal(res, now, now).isNotBlank())
        assertTrue(formatAgoInternal(res, now - 5 * 60_000L, now).isNotBlank())
        assertTrue(formatAgoInternal(res, now - 3 * 3600_000L, now).isNotBlank())
        assertTrue(formatAgoInternal(res, now - 2 * 86400_000L, now).isNotBlank())
    }

    private fun formatAgoInternal(res: android.content.res.Resources, epoch: Long, now: Long) =
        com.vayunmathur.weather.ui.formatAgo(res, epoch, now)
}
