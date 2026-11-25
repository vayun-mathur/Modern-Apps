package com.vayunmathur.weather.network

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Wire-format round trips for the bundle DTOs: serial names, defaults, nullability. */
class WxDtoWireTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun min15AndCurrentSerialNames() {
        val min15 = json.decodeFromString<WxMin15>("""{"start":1700000000,"precip":[0.0,0.2,1.4]}""")
        assertEquals(900L, min15.step); assertEquals(0, min15.count); assertEquals(listOf(0.0, 0.2, 1.4), min15.precip)
        val encodedMin = json.encodeToString(WxMin15.serializer(), WxMin15(start = 5, step = 900, count = 2, precip = listOf(0.5)))
        assertTrue(encodedMin.contains("\"start\":5")); assertTrue(encodedMin.contains("\"precip\":[0.5]"))
        val current = json.decodeFromString<WxCurrent>(
            """{"time":1700000000,"temp":4.0,"feels":1.5,"rh":81,"dew":1.0,"code":45,"wind_spd":12.5,"wind_dir":270,"pres":1013.0,"vis":4000.0,"cloud":90,"gust":30.0,"day":1}""",
        )
        assertEquals(12.5, current.windSpd); assertEquals(270, current.windDir); assertEquals(45, current.code)
        assertTrue(json.encodeToString(WxCurrent.serializer(), current).contains("\"wind_spd\":12.5"))
    }

    @Test
    fun regionTimezoneDefaults() {
        val tz = json.decodeFromString<RegionTimezone>("""{"timezone":"Europe/London","timezone_abbreviation":"GMT","utc_offset_seconds":0}""")
        assertEquals("Europe/London", tz.timezone); assertEquals("GMT", tz.abbreviation); assertEquals(0, tz.utcOffsetSeconds)
        val empty = json.decodeFromString<RegionTimezone>("""{}""")
        assertNull(empty.timezone); assertNull(empty.abbreviation)
    }

    @Test
    fun bundleDefaultsAndNullableSections() {
        val bundle = json.decodeFromString<WxBundle>("""{"lat":1.0,"lon":2.0}""")
        assertEquals("UTC", bundle.tz); assertNull(bundle.current); assertNull(bundle.min15); assertNull(bundle.aq)
        val aq = json.decodeFromString<WxAq>("""{"time":9,"us_aqi":42,"grass":1.5}""")
        assertEquals(42, aq.usAqi); assertEquals(1.5, aq.grass); assertNull(aq.birch)
        assertTrue(json.encodeToString(WxAq.serializer(), aq).contains("\"us_aqi\":42"))
    }
}
