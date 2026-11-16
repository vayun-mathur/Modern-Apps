package com.vayunmathur.weather.network

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.vayunmathur.weather.data.GeoDatabase
import kotlinx.coroutines.test.runTest
import org.junit.runner.RunWith
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** WeatherApi behaviour that does not need the live network: blank/short-circuit paths and the dead-server fallback. */
@RunWith(AndroidJUnit4::class)
class WeatherApiBehaviourTest {

    private val ctx: Context get() = ApplicationProvider.getApplicationContext()

    @BeforeTest fun setUp() {
        GeoDatabase.init(ctx)
        WeatherApi.bundleBaseOverride = "https://127.0.0.1:9/wx/v1"
    }

    @AfterTest fun tearDown() {
        WeatherApi.bundleBaseOverride = null
    }

    @Test fun geocodeBlankShortCircuits() = runTest {
        assertTrue(WeatherApi.geocode("").results.isEmpty())
        assertTrue(WeatherApi.geocode("   ").results.isEmpty())
    }

    @Test fun geocodeUnknownPlaceIsEmptyNotFailure() = runTest {
        // Catalogue may not be unpacked under Robolectric; either way an
        // unknown nonsense query must return empty, never throw.
        val r = WeatherApi.geocode("zzzzqqqq-no-such-place-12345", limit = 5)
        assertTrue(r.results.isEmpty())
    }

    @Test fun emptyBatchTemperaturesNeedsNoNetwork() = runTest {
        assertEquals(emptyMap(), WeatherApi.currentTemperatures(emptyList()))
    }

    @Test fun bundleDerivedCallsFailAgainstDeadServer() = runTest {
        assertFailsWith<Exception> { WeatherApi.forecast(52.0, 13.0) }
        assertFailsWith<Exception> { WeatherApi.airQuality(52.0, 13.0) }
        assertFailsWith<Exception> { WeatherApi.timezoneAt(52.0, 13.0) }
        assertFailsWith<Exception> { WeatherApi.currentTemperature(52.0, 13.0) }
        assertFailsWith<Exception> { WeatherApi.currentTemperatures(listOf(52.0 to 13.0)) }
    }

    @Test fun dtoDefaultsAndJsonRoundTrip() {
        val bundle = WxBundle(lat = 1.5, lon = 2.5)
        assertEquals("UTC", bundle.tz)
        assertEquals(null, bundle.current)
        assertEquals(null, bundle.aq)
        val aq = WxAq(time = 100L, usAqi = 42)
        val mapped = WxBundle(lat = 0.0, lon = 0.0, utcOffset = 3600, aq = aq).toAirQuality()
        assertEquals(42, mapped!!.current!!.usAqi)
        // 100s after epoch + 1h offset = 01:01 local.
        assertEquals("1970-01-01T01:01", mapped.current!!.time)
        assertEquals(null, WxBundle(lat = 0.0, lon = 0.0).toAirQuality())
        val tz = WxBundle(lat = 0.0, lon = 0.0, tz = "Europe/Berlin", tzAbbr = "CEST", utcOffset = 7200).toRegionTimezone()
        assertEquals("Europe/Berlin", tz.timezone)
        assertEquals(7200, tz.utcOffsetSeconds)
        // Forecast mapping with no sections: envelope only.
        val f = WxBundle(lat = 3.0, lon = 4.0).toForecastResponse()
        assertEquals(3.0, f.latitude); assertEquals(null, f.current); assertEquals(null, f.hourly); assertEquals(null, f.daily)
    }

    @Test fun forecastMappingExpandsSeries() {
        val bundle = WxBundle(
            lat = 52.0, lon = 13.0, utcOffset = 0,
            current = WxCurrent(time = 3600, temp = 20.0, feels = 21.0, rh = 50, dew = 9.0, code = 1,
                windSpd = 10.0, windDir = 90, pres = 1013.0, vis = 10000.0, cloud = 20, gust = 15.0, day = 1),
            hourly = WxHourly(start = 0, step = 3600, count = 2, temp = listOf(10.0, 11.0),
                feels = listOf(10.0, 11.0), rh = listOf(40, 41), dew = listOf(1.0, 2.0), code = listOf(0, 1),
                pprob = listOf(0, 10), precip = listOf(0.0, 0.1), windSpd = listOf(5.0, 6.0),
                windDir = listOf(0, 90), pres = listOf(1000.0, 1001.0), vis = listOf(9000.0, 9500.0),
                uv = listOf(1.0, null), cloud = listOf(0, 50), gust = listOf(7.0, 8.0), day = listOf(0, 1)),
            daily = WxDaily(start = "2026-07-15", count = 2, code = listOf(1, 61),
                tmax = listOf(25.0, 20.0), tmin = listOf(15.0, 11.0),
                feelsMax = listOf(26.0, 20.0), feelsMin = listOf(15.0, 11.0),
                sunrise = listOf(18000, 104400), sunset = listOf(72000, 158400),
                daylight = listOf(54000, 54000), sunshine = listOf(3600, 0),
                uvMax = listOf(5.0, null), pprobMax = listOf(10, 80), precipSum = listOf(0.0, 2.5),
                moonPhase = listOf(0.1, 0.2), moonrise = listOf(1000L, null), moonset = listOf(2000L, 3000L)),
            min15 = WxMin15(start = 0, step = 900, count = 2, precip = listOf(0.0, 0.4)),
        )
        val f = bundle.toForecastResponse()
        assertEquals("1970-01-01T01:00", f.current!!.time)
        assertEquals(listOf("1970-01-01T00:00", "1970-01-01T01:00"), f.hourly!!.time)
        assertEquals(listOf("2026-07-15", "2026-07-16"), f.daily!!.time)
        assertEquals(80, f.daily!!.precipitationProbabilityMax[1])
        assertEquals(null, f.daily!!.moonrise[1])
        assertEquals("1970-01-01T00:15", f.minutely15!!.time[1])
    }
}
