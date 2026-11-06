package com.vayunmathur.maps.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Slice 3/3 (root package files M-Z): [parseMaxspeed] turns OSM `maxspeed`
 * tag values into [PostedLimit]s for the driving speed badge.
 *
 * Pure Kotlin with no Android dependency, so this pins the parsing contract
 * directly: unit-tagged values, bare numbers, implicit country schemes, and
 * every "no concrete limit" form returning null rather than a guessed badge.
 */
class OsmMaxspeedSlice3Test {

    @Test
    fun `null and blank carry no limit`() {
        assertNull(parseMaxspeed(null))
        assertNull(parseMaxspeed(""))
        assertNull(parseMaxspeed("   "))
    }

    @Test
    fun `relative and unlimited values carry no limit`() {
        assertNull(parseMaxspeed("none"))
        assertNull(parseMaxspeed("signals"))
        assertNull(parseMaxspeed("variable"))
        assertNull(parseMaxspeed("walk"))
        assertNull(parseMaxspeed("unposted"))
        assertNull(parseMaxspeed("NONE"))
    }

    @Test
    fun `bare number is kmh and displays metric`() {
        val limit = parseMaxspeed("50")
        assertEquals(PostedLimit(kmh = 50, mph = 31, displayIsMph = false), limit)
    }

    @Test
    fun `explicit kmh forms parse the same as bare`() {
        assertEquals(parseMaxspeed("50"), parseMaxspeed("50 km/h"))
        assertEquals(parseMaxspeed("50"), parseMaxspeed("50kmh"))
        assertEquals(parseMaxspeed("50"), parseMaxspeed("  50  "))
    }

    @Test
    fun `mph value converts to kmh and displays imperial`() {
        val limit = parseMaxspeed("30 mph")
        assertTrue(limit != null)
        assertEquals(30, limit.mph)
        // 30 * 1.609344 = 48.28 -> 48.
        assertEquals(48, limit.kmh)
        assertTrue(limit.displayIsMph)
    }

    @Test
    fun `mph without space still parses`() {
        val limit = parseMaxspeed("70mph")
        assertTrue(limit != null)
        assertEquals(70, limit.mph)
        assertTrue(limit.displayIsMph)
    }

    @Test
    fun `zero and fractional-zero limits are rejected`() {
        assertNull(parseMaxspeed("0"))
        assertNull(parseMaxspeed("0 mph"))
    }

    @Test
    fun `implicit country schemes resolve to their legal defaults`() {
        assertEquals(50, parseMaxspeed("DE:urban")?.kmh)
        assertEquals(100, parseMaxspeed("de:rural")?.kmh)
        assertEquals(130, parseMaxspeed("DE:motorway")?.kmh)
        // 60 mph national single-carriageway.
        assertEquals(97, parseMaxspeed("gb:nsl_single")?.kmh)
        assertEquals(50, parseMaxspeed("FR:URBAN")?.kmh)
    }

    @Test
    fun `unknown country scheme returns null rather than guessing`() {
        assertNull(parseMaxspeed("XX:urban"))
        assertNull(parseMaxspeed("de:spaceship"))
    }

    @Test
    fun `unparseable text returns null`() {
        assertNull(parseMaxspeed("fast"))
        assertNull(parseMaxspeed("mph"))
    }

    @Test
    fun `metric display flag is false for kmh sources`() {
        assertFalse(parseMaxspeed("DE:urban")!!.displayIsMph)
        assertFalse(parseMaxspeed("90")!!.displayIsMph)
    }
}
