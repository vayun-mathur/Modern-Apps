package com.vayunmathur.maps.ui.map

import com.vayunmathur.library.map.GeoPoint
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Slice 1/3 (maps): the rest of the rail-corridor fan math — ground scale,
 * polyline offsets and the settle decision — beyond what [RailFanTest] pins.
 */
class RailMathSlice1Test {

    @Test
    fun `metres per dp halves every zoom and shrinks toward the poles`() {
        val z10 = metersPerDp(37.7, 10.0)
        val z11 = metersPerDp(37.7, 11.0)
        assertTrue(abs(z10 / z11 - 2.0) < 1e-9, "$z10 vs $z11")
        assertTrue(metersPerDp(60.0, 10.0) < metersPerDp(0.0, 10.0))
        assertTrue(z10 > 0)
    }

    @Test
    fun `a degenerate polyline offsets to itself`() {
        assertEquals(emptyList(), offsetPolyline(emptyList(), 10.0))
        val solo = listOf(GeoPoint(-122.4, 37.7))
        assertEquals(solo, offsetPolyline(solo, 10.0))
    }

    @Test
    fun `an east-west line offsets north-south`() {
        // Due-east line: right of travel is south.
        val line = listOf(GeoPoint(-122.4, 37.7), GeoPoint(-122.39, 37.7))
        val shifted = offsetPolyline(line, 10.0)
        assertEquals(2, shifted.size)
        assertTrue(shifted[0].latitude < 37.7, "south of the line")
        assertTrue(abs(shifted[0].longitude + 122.4) < 1e-9, "longitude unchanged")
    }

    @Test
    fun `a zero-length segment does not move its endpoints`() {
        val line = listOf(GeoPoint(-122.4, 37.7), GeoPoint(-122.4, 37.7), GeoPoint(-122.4, 37.71))
        val shifted = offsetPolyline(line, 10.0)
        assertEquals(3, shifted.size)
        // The doubled point has no direction: it holds (or averages) rather
        // than flying off.
        assertTrue(shifted.all { it.longitude.isFinite() && it.latitude.isFinite() })
    }

    @Test
    fun `a zoom step of a full level refetches`() {
        val centre = 37.7 to -122.4
        assertEquals(
            RailRefresh.REFETCH,
            railRefreshDecision(11.0, centre, centre, 10.0, 10.0, false, false),
        )
        // Half a level reuses: the lane count did not step.
        assertEquals(
            RailRefresh.REUSE,
            railRefreshDecision(10.4, centre, centre, 10.0, 10.4, false, false),
        )
    }

    @Test
    fun `a cleared overlay with cached spans refans inside the footprint`() {
        val centre = 37.7 to -122.4
        assertEquals(
            RailRefresh.REFAN,
            railRefreshDecision(10.0, centre, centre, 10.0, 9.0, true, false),
        )
    }
}
