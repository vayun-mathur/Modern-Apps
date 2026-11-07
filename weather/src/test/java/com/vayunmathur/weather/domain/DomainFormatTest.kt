package com.vayunmathur.weather.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class DomainFormatTest {

    @Test fun temperatureConversions() {
        assertEquals("20°C", formatTemperature(20.0, TemperatureUnit.Celsius))
        assertEquals("68°F", formatTemperature(20.0, TemperatureUnit.Fahrenheit))
        assertEquals("-40°F", formatTemperature(-40.0, TemperatureUnit.Fahrenheit))
        assertEquals("21°", formatTemperatureCompact(20.6, TemperatureUnit.Celsius))
        assertEquals(32.0, 0.0.celsiusTo(TemperatureUnit.Fahrenheit))
        assertEquals(0.0, 0.0.celsiusTo(TemperatureUnit.Celsius))
    }

    @Test fun pressureWindAndHelpers() {
        assertEquals("1013 hPa", formatPressure(1013.0, PressureUnit.Hpa))
        assertEquals("29.91 inHg", formatPressure(1013.0, PressureUnit.InHg))
        assertEquals("10 km/h", formatWind(10.0, WindUnit.KmH))
        assertEquals("6 mph", formatWind(10.0, WindUnit.Mph))
        assertEquals(1.0, mmToInches(25.4), 1e-9)
        assertEquals(1.0, metersToMiles(1609.34), 1e-9)
        assertEquals(52.5201, roundCoord(52.52006), 1e-9)
        assertEquals(52.52, roundCoord(52.52004), 1e-9)
    }

    @Test fun compass() {
        assertEquals("N", compassDirection(0))
        assertEquals("N", compassDirection(360))
        assertEquals("N", compassDirection(-10))
        assertEquals("NE", compassDirection(45))
        assertEquals("E", compassDirection(90))
        assertEquals("S", compassDirection(180))
        assertEquals("W", compassDirection(270))
        assertEquals("NNW", compassDirection(340))
        assertEquals("NNE", compassDirection(20))
    }

    @Test fun wmoCodes() {
        assertEquals(WeatherCondition.Clear, weatherConditionForCode(0))
        assertEquals(WeatherCondition.MostlyClear, weatherConditionForCode(1))
        assertEquals(WeatherCondition.PartlyCloudy, weatherConditionForCode(2))
        assertEquals(WeatherCondition.Cloudy, weatherConditionForCode(3))
        assertEquals(WeatherCondition.Fog, weatherConditionForCode(45))
        assertEquals(WeatherCondition.Fog, weatherConditionForCode(48))
        assertEquals(WeatherCondition.Drizzle, weatherConditionForCode(53))
        assertEquals(WeatherCondition.Rain, weatherConditionForCode(61))
        assertEquals(WeatherCondition.Rain, weatherConditionForCode(82))
        assertEquals(WeatherCondition.Snow, weatherConditionForCode(71))
        assertEquals(WeatherCondition.Snow, weatherConditionForCode(86))
        assertEquals(WeatherCondition.Thunderstorm, weatherConditionForCode(95))
        assertEquals(WeatherCondition.Thunderstorm, weatherConditionForCode(99))
        assertEquals(WeatherCondition.Unknown, weatherConditionForCode(4))
        assertEquals(WeatherCondition.Unknown, weatherConditionForCode(999))
    }

    @Test fun dateTimeLabels() {
        assertEquals("Thu 25 Jun", formatDayMonthLabel("2026-06-25"))
        assertEquals("not-a-date", formatDayMonthLabel("not-a-date"))
        assertEquals("3 PM · Thu", formatSelectedHourLabel("2026-06-25T15:00", false))
        assertEquals("15:00 · Thu", formatSelectedHourLabel("2026-06-25T15:00", true))
        assertEquals("bogus", formatSelectedHourLabel("bogus", false))
    }

    @Test fun instantInZoneAndParse() {
        assertEquals(
            "6 PM · Wed",
            formatInstantInZone("2026-07-01T18:00Z", kotlinx.datetime.TimeZone.UTC, false),
        )
        assertEquals(
            "18:00 · Wed",
            formatInstantInZone("2026-07-01T18:00Z", kotlinx.datetime.TimeZone.UTC, true),
        )
        assertEquals("bogus", formatInstantInZone("bogus", kotlinx.datetime.TimeZone.UTC, false))
        assertEquals(1767225600L, parseLocalIsoToEpochSec("2026-01-01T00:00", 0))
        assertEquals(1767222000L, parseLocalIsoToEpochSec("2026-01-01T00:00", 3600))
        assertEquals(null, parseLocalIsoToEpochSec("nope", 0))
    }

    @Test fun epochLabelsUtc() {
        java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone("UTC"))
        val epoch = java.time.LocalDateTime.of(2026, 1, 1, 15, 5).toEpochSecond(java.time.ZoneOffset.UTC)
        assertEquals("3 PM", formatStripHour(epoch, false))
        assertEquals("15", formatStripHour(epoch, true))
        assertTrue(formatClockTime(epoch, false).contains("3:05"))
        assertEquals("15:05", formatClockTime(epoch, true))
        assertTrue(formatHourAxisLabel(epoch, true).isNotBlank())
    }

    @Test fun metricMetadata() {
        assertEquals("temperature_2m", WeatherMetric.Temperature.bundleHourlyVar)
        assertEquals("uv_index", WeatherMetric.UvIndex.bundleHourlyVar)
        assertEquals("pressure_msl", WeatherMetric.Pressure.bundleHourlyVar)
        assertTrue(WeatherMetric.Precipitation !in mapMetrics)
        assertTrue(WeatherMetric.WindGusts !in mapMetrics)
        assertTrue(WeatherMetric.Temperature in mapMetrics)
        for (m in WeatherMetric.entries) {
            val ramp = m.colorRamp
            assertTrue(ramp.size >= 3, m.name)
            assertTrue(ramp.zipWithNext().all { (a, b) -> a.value < b.value }, m.name)
        }
        val fmt = metricValueFormatter(WeatherMetric.Humidity, TemperatureUnit.Celsius, WindUnit.KmH, PressureUnit.Hpa)
        assertEquals("42%", fmt(41.6))
        assertEquals("3", metricValueFormatter(WeatherMetric.UvIndex, TemperatureUnit.Celsius, WindUnit.KmH, PressureUnit.Hpa)(3.4))
        assertEquals("20°", metricValueFormatter(WeatherMetric.Temperature, TemperatureUnit.Celsius, WindUnit.KmH, PressureUnit.Hpa)(20.0))
        assertEquals("10 km/h", metricValueFormatter(WeatherMetric.WindSpeed, TemperatureUnit.Celsius, WindUnit.KmH, PressureUnit.Hpa)(10.0))
        assertEquals("1013 hPa", metricValueFormatter(WeatherMetric.Pressure, TemperatureUnit.Celsius, WindUnit.KmH, PressureUnit.Hpa)(1013.0))
        assertEquals("8 km", metricValueFormatter(WeatherMetric.Visibility, TemperatureUnit.Celsius, WindUnit.KmH, PressureUnit.Hpa)(8000.0))
        assertEquals("5 mi", metricValueFormatter(WeatherMetric.Visibility, TemperatureUnit.Celsius, WindUnit.Mph, PressureUnit.Hpa)(8000.0))
        assertTrue(metricValueFormatter(WeatherMetric.Precipitation, TemperatureUnit.Celsius, WindUnit.KmH, PressureUnit.Hpa)(1.0).contains("mm"))
    }
}
