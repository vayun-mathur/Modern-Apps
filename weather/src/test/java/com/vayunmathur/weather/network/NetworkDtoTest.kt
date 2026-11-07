package com.vayunmathur.weather.network

import com.vayunmathur.weather.domain.map.OmMapMetadata
import com.vayunmathur.weather.domain.WeatherMetric
import com.vayunmathur.weather.domain.map.omFileUrl
import com.vayunmathur.weather.domain.map.DwdIconGlobal
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class NetworkDtoTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test fun forecastJsonRoundTrip() {
        val raw = """
            {"latitude":52.5,"longitude":13.4,"timezone":"Europe/Berlin","utc_offset_seconds":7200,
             "current":{"time":"2026-07-15T14:30","temperature_2m":25.0,"apparent_temperature":26.0,
               "relative_humidity_2m":50,"weather_code":2,"wind_speed_10m":10.0,"wind_direction_10m":90,"is_day":1},
             "hourly":{"time":["2026-07-15T14:00"],"temperature_2m":[25.0],"uv_index":[null]},
             "daily":{"time":["2026-07-15"],"moonrise":[null]}}
        """.trimIndent()
        val f = json.decodeFromString<ForecastResponse>(raw)
        assertEquals("Europe/Berlin", f.timezone)
        assertEquals(25.0, f.current!!.temperature)
        assertEquals(listOf(null), f.hourly!!.uvIndex)
        assertEquals(listOf(null), f.daily!!.moonrise)
        val re = json.decodeFromString<ForecastResponse>(json.encodeToString(f))
        assertEquals(f, re)
    }

    @Test fun wxBundleDefaultsAndNullSections() {
        val b = json.decodeFromString<WxBundle>("""{"lat":1.0,"lon":2.0}""")
        assertEquals("UTC", b.tz)
        assertNull(b.current)
        val f = b.toForecastResponse()
        assertNull(f.current); assertNull(f.hourly); assertNull(f.daily); assertNull(f.minutely15)
        assertNull(b.toAirQuality())
        assertEquals(RegionTimezone("UTC", "UTC", 0), b.toRegionTimezone())
    }

    @Test fun bundleWithBadDailyStartDropsDaily() {
        val b = WxBundle(
            lat = 0.0, lon = 0.0,
            daily = WxDaily(start = "not-a-date", count = 2, tmax = listOf(1.0, 2.0)),
            hourly = WxHourly(start = 0, count = 1, temp = listOf(3.0)),
        )
        val f = b.toForecastResponse()
        assertNull(f.daily)
        assertEquals(listOf(3.0), assertNotNull(f.hourly).temperature)
    }

    @Test fun geocodingAndAirQualitySerialization() {
        val g = json.decodeFromString<GeocodingResponse>(
            """{"results":[{"id":1,"name":"Berlin","latitude":52.5,"longitude":13.4,"country_code":"DE"}]}""",
        )
        assertEquals("Berlin", g.results[0].name)
        assertEquals("DE", g.results[0].countryCode)
        assertEquals(emptyList(), json.decodeFromString<GeocodingResponse>("{}").results)
        val aq = json.decodeFromString<AirQualityResponse>(
            """{"current":{"time":"2026-07-15T14:00","us_aqi":42,"grass_pollen":1.5}}""",
        )
        assertEquals(42, aq.current!!.usAqi)
        assertEquals(1.5, aq.current!!.grassPollen)
    }

    @Test fun omMetadataSupports() {
        val meta = OmMapMetadata(
            referenceTime = "2026-07-01T18:00:00Z",
            validTimes = listOf("2026-07-01T18:00Z"),
            variables = listOf("temperature_2m", "wind_u_component_10m"),
        )
        assertTrue(meta.supports(WeatherMetric.Temperature))
        assertFalse(meta.supports(WeatherMetric.WindSpeed)) // needs both u and v
        assertFalse(meta.supports(WeatherMetric.Humidity))
        val withWind = meta.copy(variables = meta.variables + "wind_v_component_10m")
        assertTrue(withWind.supports(WeatherMetric.WindSpeed))
        assertEquals("dwd_icon", DwdIconGlobal.model)
        assertEquals(2879, DwdIconGlobal.nx)
    }

    @Test fun omFileUrl() {
        assertEquals(
            "https://api.maproj.org/data_spatial/dwd_icon/2026/07/01/1800Z/2026-07-01T1800.om",
            omFileUrl(DwdIconGlobal, "2026-07-01T18:00:00Z", "2026-07-01T18:00Z"),
        )
    }

    @Test fun geocodeBlankIsEmpty() = runTest {
        assertTrue(WeatherApi.geocode("").results.isEmpty())
        assertTrue(WeatherApi.geocode("   ").results.isEmpty())
        assertTrue(WeatherApi.currentTemperatures(emptyList()).isEmpty())
    }

    @Test fun currentTempResponseDefaults() {
        val c = json.decodeFromString<CurrentTemperatureResponse>("""{"current":{"temperature_2m":21.5}}""")
        assertEquals(21.5, c.current!!.temperature)
        assertNull(json.decodeFromString<CurrentTemperatureResponse>("{}").current)
    }
}
