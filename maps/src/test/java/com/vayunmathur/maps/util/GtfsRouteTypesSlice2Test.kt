package com.vayunmathur.maps.util

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * GTFS `route_type` (base + extended ranges) to a coarse mode label.
 * Slice-2 coverage for maps (basename G, outside the slice-1 dirs).
 */
class GtfsRouteTypesSlice2Test {

    @Test
    fun `base codes map to their modes`() {
        assertEquals("TRAM", GtfsRouteTypes.mode(0))
        assertEquals("SUBWAY", GtfsRouteTypes.mode(1))
        assertEquals("RAIL", GtfsRouteTypes.mode(2))
        assertEquals("BUS", GtfsRouteTypes.mode(3))
        assertEquals("FERRY", GtfsRouteTypes.mode(4))
        assertEquals("TRAM", GtfsRouteTypes.mode(5))
        assertEquals("AERIAL", GtfsRouteTypes.mode(6))
        assertEquals("FUNICULAR", GtfsRouteTypes.mode(7))
        assertEquals("TROLLEYBUS", GtfsRouteTypes.mode(11))
        assertEquals("MONORAIL", GtfsRouteTypes.mode(12))
    }

    @Test
    fun `extended ranges map to their modes`() {
        assertEquals("TRAM", GtfsRouteTypes.mode(900))
        assertEquals("SUBWAY", GtfsRouteTypes.mode(400))
        assertEquals("SUBWAY", GtfsRouteTypes.mode(499))
        assertEquals("RAIL", GtfsRouteTypes.mode(100))
        assertEquals("RAIL", GtfsRouteTypes.mode(199))
        assertEquals("BUS", GtfsRouteTypes.mode(200))
        assertEquals("BUS", GtfsRouteTypes.mode(299))
        assertEquals("BUS", GtfsRouteTypes.mode(700))
        assertEquals("BUS", GtfsRouteTypes.mode(799))
        assertEquals("BUS", GtfsRouteTypes.mode(800))
        assertEquals("FERRY", GtfsRouteTypes.mode(1000))
        assertEquals("FERRY", GtfsRouteTypes.mode(1200))
        assertEquals("AERIAL", GtfsRouteTypes.mode(1300))
        assertEquals("FUNICULAR", GtfsRouteTypes.mode(1400))
    }

    @Test
    fun `range edges just outside fall through`() {
        // 99 is below the rail range, 500 above the subway range, 300/600/801
        // sit between the bus ranges — none of them is a known mode.
        assertEquals("TRANSIT", GtfsRouteTypes.mode(99))
        assertEquals("TRANSIT", GtfsRouteTypes.mode(500))
        assertEquals("TRANSIT", GtfsRouteTypes.mode(300))
        assertEquals("TRANSIT", GtfsRouteTypes.mode(600))
        assertEquals("TRANSIT", GtfsRouteTypes.mode(801))
    }

    @Test
    fun `unknown codes fall back to TRANSIT`() {
        assertEquals("TRANSIT", GtfsRouteTypes.mode(-1))
        assertEquals("TRANSIT", GtfsRouteTypes.mode(8))
        assertEquals("TRANSIT", GtfsRouteTypes.mode(9))
        assertEquals("TRANSIT", GtfsRouteTypes.mode(10))
        assertEquals("TRANSIT", GtfsRouteTypes.mode(13))
        assertEquals("TRANSIT", GtfsRouteTypes.mode(1500))
    }
}
