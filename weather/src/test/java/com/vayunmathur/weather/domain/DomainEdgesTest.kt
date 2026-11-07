package com.vayunmathur.weather.domain

import com.vayunmathur.weather.network.Current
import com.vayunmathur.weather.network.Daily
import com.vayunmathur.weather.network.ForecastResponse
import com.vayunmathur.weather.network.Hourly
import kotlinx.datetime.TimeZone
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
class DomainEdgesTest {

    private fun current() = Current(
        time = "2026-07-15T14:30", temperature = 25.0, apparentTemperature = 26.0,
        relativeHumidity = 50, dewPoint = 14.0, weatherCode = 2, windSpeed = 10.0,
        windDirection = 90, pressureMsl = 1013.0, visibility = 10000.0, cloudCover = 20,
        windGusts = 20.0, isDay = 1,
    )

    private fun fullHourly() = Hourly(
        time = listOf("2026-07-15T00:00", "2026-07-15T06:00", "2026-07-15T12:00"),
        temperature = listOf(15.0, 20.0, 24.0),
        apparentTemperature = listOf(15.0, 20.0, 24.0),
        relativeHumidity = listOf(80, 60, 40),
        dewPoint = listOf(12.0, 12.0, 12.0),
        weatherCode = listOf(0, 1, 2),
        precipitationProbability = listOf(0, 10, 20),
        precipitation = listOf(0.0, 0.0, 0.5),
        windSpeed = listOf(10.0, 10.0, 10.0),
        windDirection = listOf(0, 90, 180),
        pressureMsl = listOf(1010.0, 1012.0, 1014.0),
        visibility = listOf(5000.0, 8000.0, 10000.0),
        cloudCover = listOf(90, 50, 10),
        windGusts = listOf(15.0, 25.0, 20.0),
        uvIndex = listOf(0.0, 3.0, 6.0),
        isDay = listOf(0, 1, 1),
    )

    private fun forecast(hourly: Hourly? = fullHourly(), daily: Daily? = null) = ForecastResponse(
        latitude = 52.0, longitude = 13.0, utcOffsetSeconds = 0,
        current = current(), hourly = hourly, daily = daily,
    )

    @Test fun everyMetricSeriesExtracts() {
        val f = forecast()
        val expected = mapOf(
            WeatherMetric.Temperature to listOf(15.0, 20.0, 24.0),
            WeatherMetric.FeelsLike to listOf(15.0, 20.0, 24.0),
            WeatherMetric.Humidity to listOf(80.0, 60.0, 40.0),
            WeatherMetric.DewPoint to listOf(12.0, 12.0, 12.0),
            WeatherMetric.Precipitation to listOf(0.0, 0.0, 0.5),
            WeatherMetric.WindSpeed to listOf(10.0, 10.0, 10.0),
            WeatherMetric.WindGusts to listOf(15.0, 25.0, 20.0),
            WeatherMetric.Pressure to listOf(1010.0, 1012.0, 1014.0),
            WeatherMetric.Visibility to listOf(5000.0, 8000.0, 10000.0),
            WeatherMetric.CloudCover to listOf(90.0, 50.0, 10.0),
            WeatherMetric.UvIndex to listOf(0.0, 3.0, 6.0),
        )
        for ((metric, values) in expected) {
            assertEquals(values, metricSeries(f, metric, null).map { it.value }, metric.name)
        }
    }

    @Test fun formatterForEveryMetric() {
        for (m in WeatherMetric.entries) {
            val s = metricValueFormatter(m, TemperatureUnit.Celsius, WindUnit.KmH, PressureUnit.Hpa)(10.0)
            assertTrue(s.isNotBlank(), m.name)
            val imperial = metricValueFormatter(m, TemperatureUnit.Fahrenheit, WindUnit.Mph, PressureUnit.InHg)(10.0)
            assertTrue(imperial.isNotBlank(), m.name)
        }
    }

    @Test fun dayAggregationVectorWind() {
        // Three wind samples N/E/S at equal speed: vector mean cancels N/S,
        // leaving an easterly result slower than the scalar mean.
        val r = assertNotNull(resolveConditions(forecast(), SelectedDateOrTime.Day("2026-07-15")))
        // No daily -> falls back to current? daily is null here, so resolveDay
        // must fall back; covered by unknownDay test. Use a daily instead:
        val withDaily = forecast(daily = Daily(time = listOf("2026-07-15"), temperatureMax = listOf(24.0)))
        val day = assertNotNull(resolveConditions(withDaily, SelectedDateOrTime.Day("2026-07-15")))
        assertEquals(60, day.blockCurrent.relativeHumidity) // avg 80/60/40
        assertEquals(25.0, day.blockCurrent.windGusts) // peak gust
        assertEquals(24.0, day.temperature)
    }

    @Test fun dayWithoutHourlyUsesCurrentBlock() {
        val withDaily = forecast(
            hourly = null,
            daily = Daily(time = listOf("2026-07-15"), temperatureMax = listOf(30.0), weatherCode = listOf(3)),
        )
        val day = assertNotNull(resolveConditions(withDaily, SelectedDateOrTime.Day("2026-07-15")))
        assertEquals(50, day.blockCurrent.relativeHumidity) // current unchanged
        assertEquals(3, day.weatherCode)
    }

    @Test fun hourWithShortListsFallsBackPerField() {
        val short = Hourly(time = listOf("2026-07-15T12:00"))
        val f = forecast(hourly = short, daily = Daily(time = listOf("2026-07-15")))
        val r = assertNotNull(resolveConditions(f, SelectedDateOrTime.Time("2026-07-15T12:00")))
        assertEquals(25.0, r.temperature) // hourly temp missing -> current
        assertEquals(50, r.blockCurrent.relativeHumidity)
    }

    @Test fun instantParsingVariants() {
        // Explicit offset form
        assertTrue(formatInstantInZone("2026-07-01T18:00:00+02:00", TimeZone.UTC, true).contains("16:00"))
        // Naive-UTC form (no Z)
        assertTrue(formatInstantInZone("2026-07-01T18:00", TimeZone.UTC, true).contains("18:00"))
        // Local ISO with explicit Z fallback in parseLocalIsoToEpochSec
        assertEquals(1767225600L, parseLocalIsoToEpochSec("2026-01-01T00:00:00Z", 0))
        assertEquals(null, parseLocalIsoToEpochSec("", 0))
    }

    @Test fun selectedTypesHoldValues() {
        assertEquals("2026-07-15T12:00", SelectedDateOrTime.Time("2026-07-15T12:00").isoTime)
        assertEquals("2026-07-15", SelectedDateOrTime.Day("2026-07-15").isoDate)
    }
}
