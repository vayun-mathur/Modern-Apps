package com.vayunmathur.weather.domain

import com.vayunmathur.weather.network.Current
import com.vayunmathur.weather.network.Daily
import com.vayunmathur.weather.network.ForecastResponse
import com.vayunmathur.weather.network.Hourly
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ResolveConditionsTest {

    private fun current() = Current(
        time = "2026-07-15T14:30", temperature = 25.0, apparentTemperature = 26.0,
        relativeHumidity = 50, dewPoint = 14.0, weatherCode = 2, windSpeed = 10.0,
        windDirection = 90, pressureMsl = 1013.0, visibility = 10000.0, cloudCover = 20,
        windGusts = 20.0, isDay = 1,
    )

    private fun forecast() = ForecastResponse(
        latitude = 52.0, longitude = 13.0, utcOffsetSeconds = 0,
        current = current(),
        hourly = Hourly(
            time = listOf("2026-07-15T13:00", "2026-07-15T14:00", "2026-07-16T10:00"),
            temperature = listOf(24.0, 25.5, 18.0),
            apparentTemperature = listOf(24.0, 26.5, 17.0),
            relativeHumidity = listOf(55, 48, 60),
            dewPoint = listOf(13.0, 13.5, 10.0),
            weatherCode = listOf(1, 3, 61),
            precipitation = listOf(0.0, 0.1, 2.0),
            windSpeed = listOf(8.0, 12.0, 5.0),
            windDirection = listOf(80, 100, 180),
            pressureMsl = listOf(1012.0, 1014.0, 1010.0),
            visibility = listOf(9000.0, 11000.0, 5000.0),
            cloudCover = listOf(10, 30, 80),
            windGusts = listOf(15.0, 22.0, 9.0),
            uvIndex = listOf(4.0, 5.0, 1.0),
            isDay = listOf(1, 1, 1),
        ),
        daily = Daily(
            time = listOf("2026-07-15", "2026-07-16"),
            weatherCode = listOf(2, 61),
            temperatureMax = listOf(27.0, 19.0),
            temperatureMin = listOf(17.0, 12.0),
            apparentTemperatureMax = listOf(28.0, 18.0),
            sunrise = listOf("2026-07-15T05:00", "2026-07-16T05:01"),
            sunset = listOf("2026-07-15T21:00", "2026-07-16T21:00"),
            daylightDuration = listOf(57600.0, 57540.0),
            uvIndexMax = listOf(6.0, 2.0),
            precipitationSum = listOf(0.1, 3.0),
            moonPhase = listOf(0.5, 0.52),
            moonrise = listOf("2026-07-15T20:00", null),
            moonset = listOf("2026-07-15T06:00", "2026-07-16T07:00"),
        ),
    )

    @Test fun noCurrentReturnsNull() {
        assertNull(resolveConditions(ForecastResponse(0.0, 0.0), null))
    }

    @Test fun currentResolution() {
        val r = assertNotNull(resolveConditions(forecast(), null))
        assertEquals(2, r.weatherCode)
        assertTrue(r.isDay)
        assertEquals(25.0, r.temperature)
        assertEquals(27.0, r.high)
        assertEquals(17.0, r.low)
        assertEquals(6.0, r.uvIndexMax)
        assertEquals("2026-07-15T05:00", r.sunriseIso)
        assertEquals(0.5, r.moonPhase)
        assertEquals(25.0, r.blockCurrent.temperature)
    }

    @Test fun hourResolution() {
        val r = assertNotNull(resolveConditions(forecast(), SelectedDateOrTime.Time("2026-07-15T14:00")))
        assertEquals(3, r.weatherCode)
        assertEquals(25.5, r.temperature)
        assertEquals(26.5, r.apparentTemperature)
        assertEquals(5.0, r.uvIndexMax)
        assertEquals(27.0, r.high)
        assertEquals(0.1, r.precipitationSum)
        assertEquals(48, r.blockCurrent.relativeHumidity)
        assertEquals(22.0, r.blockCurrent.windGusts)
    }

    @Test fun unknownHourFallsBackToCurrent() {
        val r = assertNotNull(resolveConditions(forecast(), SelectedDateOrTime.Time("2026-07-20T10:00")))
        assertEquals(25.0, r.temperature)
        assertEquals(2, r.weatherCode)
    }

    @Test fun dayResolutionAggregatesHourly() {
        val r = assertNotNull(resolveConditions(forecast(), SelectedDateOrTime.Day("2026-07-16")))
        assertEquals(61, r.weatherCode)
        assertEquals(19.0, r.temperature)
        assertEquals(18.0, r.apparentTemperature)
        assertEquals(3.0, r.precipitationSum)
        // Only one hourly row that day: aggregates equal that row.
        assertEquals(60, r.blockCurrent.relativeHumidity)
        assertEquals(9.0, r.blockCurrent.windGusts)
    }

    @Test fun unknownDayFallsBackToCurrent() {
        val r = assertNotNull(resolveConditions(forecast(), SelectedDateOrTime.Day("2026-08-01")))
        assertEquals(25.0, r.temperature)
    }

    @Test fun metricSeriesByDayAndSelection() {
        val f = forecast()
        val today = metricSeries(f, WeatherMetric.Temperature, null)
        assertEquals(listOf(24.0, 25.5), today.map { it.value })
        val day = metricSeries(f, WeatherMetric.Temperature, SelectedDateOrTime.Day("2026-07-16"))
        assertEquals(listOf(18.0), day.map { it.value })
        val byTime = metricSeries(f, WeatherMetric.Humidity, SelectedDateOrTime.Time("2026-07-15T14:00"))
        assertEquals(listOf(55.0, 48.0), byTime.map { it.value })
        assertTrue(metricSeries(ForecastResponse(0.0, 0.0), WeatherMetric.Temperature, null).isEmpty())
    }

    @Test fun metricSeriesSkipsNulls() {
        val f = forecast().copy(
            hourly = forecast().hourly!!.copy(uvIndex = listOf(null, 5.0, null)),
        )
        val pts = metricSeries(f, WeatherMetric.UvIndex, SelectedDateOrTime.Day("2026-07-15"))
        assertEquals(listOf(5.0), pts.map { it.value })
    }

    @Test fun todayFallbacks() {
        // currentTime null -> index based on size capped at 8
        assertEquals(1, todayIndex(Daily(time = listOf("2026-07-15", "2026-07-16")), null))
        assertEquals(0, nowIndex(Hourly(time = listOf("2026-07-15T14:00")), null))
        assertEquals(0, nowIndex(Hourly(time = listOf("2026-07-15T14:00")), "2026-07-15T15:30"))
        val f = ForecastResponse(0.0, 0.0, utcOffsetSeconds = 0, current = null)
        assertTrue(todayIsoDate(f).matches(Regex("\\d{4}-\\d{2}-\\d{2}")))
    }
}
