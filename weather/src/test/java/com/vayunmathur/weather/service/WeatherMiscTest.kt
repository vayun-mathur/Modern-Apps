package com.vayunmathur.weather.service

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.vayunmathur.library.map.GeoBounds
import com.vayunmathur.weather.data.weatherJson
import com.vayunmathur.weather.domain.map.OmMapMetadata
import com.vayunmathur.weather.domain.map.colorizeToBitmap
import com.vayunmathur.weather.domain.ColorStop
import com.vayunmathur.weather.domain.WeatherMetric
import com.vayunmathur.weather.intents.errorWeatherData
import com.vayunmathur.weather.intents.toWeatherData
import com.vayunmathur.weather.network.Current
import com.vayunmathur.weather.network.Daily
import com.vayunmathur.weather.network.ForecastResponse
import com.vayunmathur.weather.service.car.WeatherCarAppService
import com.vayunmathur.weather.service.car.WeatherCarState
import com.vayunmathur.weather.service.car.cacheKeyFor
import com.vayunmathur.weather.service.car.decodeCarForecast
import com.vayunmathur.weather.ui.cacheKey
import com.vayunmathur.weather.ui.indexForIso
import com.vayunmathur.weather.ui.rasterSize
import com.vayunmathur.weather.ui.WeatherMapState
import kotlinx.coroutines.test.runTest
import org.junit.runner.RunWith
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.toArgb
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@RunWith(AndroidJUnit4::class)
class WeatherMiscTest {

    private val ctx: Context get() = ApplicationProvider.getApplicationContext()

    private fun forecast() = ForecastResponse(
        latitude = 52.0, longitude = 13.0, utcOffsetSeconds = 0,
        current = Current(
            time = "2026-07-15T14:30", temperature = 25.0, apparentTemperature = 26.0,
            relativeHumidity = 50, weatherCode = 2, windSpeed = 10.0, windDirection = 90,
        ),
        daily = Daily(
            time = listOf("2026-07-15"), temperatureMax = listOf(27.0),
            temperatureMin = listOf(17.0), precipitationProbabilityMax = listOf(33),
            uvIndexMax = listOf(5.0), sunrise = listOf("2026-07-15T05:00"),
            sunset = listOf("2026-07-15T21:00"),
        ),
    )

    @Test fun toWeatherData() {
        val data = forecast().toWeatherData(ctx, "Berlin, Germany")
        assertEquals("Berlin, Germany", data.locationName)
        assertEquals(25.0, data.temperatureCelsius)
        assertEquals(27.0, data.highCelsius)
        assertEquals(33, data.precipitationChancePercent)
        assertEquals("E", data.windDirection)
        assertEquals(5.0, data.uvIndex)
        assertNotNull(data.sunriseEpochSec)
    }

    @Test fun toWeatherDataNoCurrent() {
        val data = ForecastResponse(0.0, 0.0).toWeatherData(ctx, null)
        assertEquals("No current observations available", data.error)
        val err = errorWeatherData("X", "boom")
        assertEquals("boom", err.error); assertEquals("X", err.locationName)
        assertEquals(0.0, err.temperatureCelsius)
    }

    @Test fun intentsReturnErrorWithoutNetwork() = runTest {
        val a = com.vayunmathur.weather.intents.GetWeatherIntent()
            .performCalculation(com.vayunmathur.weather.intents.LatLonInput(52.0, 13.0))
        assertNotNull(a.error)
        val b = com.vayunmathur.weather.intents.GetWeatherByNameIntent()
            .performCalculation(com.vayunmathur.weather.intents.LocationQueryInput(""))
        assertNotNull(b.error)
    }

    @Test fun carStateObserversAndDecode() {
        val state = WeatherCarState()
        var calls = 0
        state.observe { calls++ }
        state.observe { throw RuntimeException("bad observer") }
        state.notifyChanged()
        assertEquals(1, calls)
        assertNull(state.forecastFor(1L))
        val json = weatherJson.encodeToString(forecast())
        val car = assertNotNull(decodeCarForecast(json, null, 123L))
        assertEquals(123L, car.fetchedAtEpochMs)
        assertEquals(25.0, car.forecast.current!!.temperature)
        state.putForecast(1L, car)
        assertEquals(car, state.forecastFor(1L))
        assertNull(decodeCarForecast("not json", null, 0L))
        val withAq = decodeCarForecast(json, """{"current":null}""", 5L)
        assertNotNull(withAq); assertNull(withAq.airQuality!!.current)
        assertEquals(52.5201 to 13.405, cacheKeyFor(52.52006, 13.40495))
    }

    @Test fun carServiceCreatesSessionAndValidator() {
        val svc = WeatherCarAppService()
        assertNotNull(svc.createHostValidator())
        assertNotNull(svc.onCreateSession())
    }

    @Test fun mapHelpers() {
        val bounds = GeoBounds(west = 0.0, south = 0.0, east = 10.0, north = 5.0)
        val (w, h) = rasterSize(bounds)
        assertEquals(512, w); assertEquals(256, h)
        val (w2, h2) = rasterSize(GeoBounds(west = 0.0, south = 0.0, east = 5.0, north = 10.0))
        assertEquals(512, h2); assertEquals(256, w2)
        assertTrue(cacheKey(bounds, "temperature_2m", "2026-07-01T18:00Z").contains("temperature_2m"))
        assertEquals(0, indexForIso(emptyList(), "x"))
        assertEquals(1, indexForIso(listOf("2026-07-01T17:00Z", "2026-07-01T18:00Z"), "2026-07-01T18:00Z"))
        assertEquals(1, indexForIso(listOf("2026-07-01T17:00Z", "2026-07-01T18:30Z"), "2026-07-01T18:00"))
        assertEquals(0, indexForIso(listOf("2026-07-02T00:00Z"), "2026-07-01T18:00"))
        assertEquals(0, indexForIso(listOf("2026-07-01T00:00Z"), null))
    }

    @Test fun mapStateDefaultsAndFallback() {
        val s = WeatherMapState("Temperature")
        assertEquals(WeatherMetric.Temperature, s.selectedMetric)
        assertEquals(WeatherMetric.Temperature, WeatherMapState("bogus").selectedMetric)
        // Precipitation is not a map metric -> falls back
        assertEquals(WeatherMetric.Temperature, WeatherMapState("Precipitation").selectedMetric)
        assertTrue(s.supportedMetrics.isNotEmpty())
        s.metadata = OmMapMetadata(
            referenceTime = "2026-07-01T18:00:00Z",
            validTimes = listOf("t1"),
            variables = listOf("temperature_2m"),
        )
        assertEquals(listOf(WeatherMetric.Temperature), s.supportedMetrics)
        assertEquals(listOf("t1"), s.times)
    }

    @Test fun colorizeBitmap() {
        val ramp = listOf(ColorStop(0.0, Color.Red), ColorStop(10.0, Color.Blue))
        val bmp = colorizeToBitmap(floatArrayOf(0f, 5f, 10f, Float.NaN), 2, 2, ramp)
        assertEquals(2, bmp.width); assertEquals(2, bmp.height)
        assertEquals(Color.Red.toArgb(), bmp.asAndroidBitmap().getPixel(0, 0))
        assertEquals(Color.Blue.toArgb(), bmp.asAndroidBitmap().getPixel(0, 1))
        assertEquals(0, bmp.asAndroidBitmap().getPixel(1, 1))
        // empty ramp -> transparent
        val empty = colorizeToBitmap(floatArrayOf(1f), 1, 1, emptyList())
        assertEquals(0, empty.asAndroidBitmap().getPixel(0, 0))
    }
}
