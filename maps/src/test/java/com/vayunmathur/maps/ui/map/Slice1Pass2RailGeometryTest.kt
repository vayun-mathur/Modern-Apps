package com.vayunmathur.maps.ui.map

import com.vayunmathur.library.map.GeoPoint
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Slice 1/3 second pass (maps): the rail-corridor geometry decisions —
 * lane counts, lane offsets, polyline fanning and the settled-camera
 * refresh decision — beyond the first pass's math smoke tests.
 */
class Slice1Pass2RailGeometryTest {

    @Test
    fun `lane count follows the tile style ramp`() {
        assertEquals(1, railLaneCount(-2.0))
        assertEquals(1, railLaneCount(0.0))
        assertEquals(2, railLaneCount(9.0))
        assertEquals(2, railLaneCount(10.0))
        assertEquals(3, railLaneCount(11.0))
        assertEquals(3, railLaneCount(12.0))
        assertEquals(4, railLaneCount(13.0))
        assertEquals(4, railLaneCount(18.0))
    }

    @Test
    fun `lane offset is zero for single lane corridors and scales with taper`() {
        assertEquals(0.0, railLaneOffsetDp(13.0, ordinal = 0, count = 1, taper = 255), 1e-9)
        assertEquals(0.0, railLaneOffsetDp(13.0, ordinal = 0, count = 4, taper = 0), 1e-9)
        // first and last ordinals sit on opposite sides of the centreline
        val first = railLaneOffsetDp(13.0, ordinal = 0, count = 4, taper = 255)
        val last = railLaneOffsetDp(13.0, ordinal = 3, count = 4, taper = 255)
        assertTrue(first < 0.0 && last > 0.0)
        assertEquals(-last, first, 1e-9)
        // half taper halves the offset
        assertEquals(
            first / 2,
            railLaneOffsetDp(13.0, ordinal = 0, count = 4, taper = 127),
            0.05,
        )
        // fewer lanes than colours squash ordinals onto shared lanes from the middle
        val squashed = railLaneOffsetDp(9.0, ordinal = 1, count = 4, taper = 255)
        assertTrue(kotlin.math.abs(squashed) <= kotlin.math.abs(first))
    }

    @Test
    fun `meters per dp shrinks with zoom and latitude`() {
        val equatorZoom10 = metersPerDp(0.0, 10.0)
        assertTrue(metersPerDp(0.0, 11.0) < equatorZoom10)
        assertTrue(metersPerDp(60.0, 10.0) < equatorZoom10)
        assertEquals(equatorZoom10 / 2, metersPerDp(0.0, 11.0), equatorZoom10 * 0.01)
    }

    @Test
    fun `offset polyline shifts right of travel and glues bends`() {
        val east = listOf(GeoPoint(0.0, 0.0), GeoPoint(0.01, 0.0), GeoPoint(0.02, 0.0))
        val shifted = offsetPolyline(east, 100.0)
        // travelling east, right of travel is south: latitude drops, longitude kept
        assertTrue(shifted.all { it.latitude < 0.0 })
        assertEquals(east.map { it.longitude }, shifted.map { it.longitude })
        // a northbound leg shifts east instead
        val north = listOf(GeoPoint(0.0, 0.0), GeoPoint(0.0, 0.01))
        val shiftedNorth = offsetPolyline(north, 100.0)
        assertTrue(shiftedNorth.all { it.longitude > 0.0 })
        // negative offset goes the other way
        assertTrue(offsetPolyline(east, -100.0).all { it.latitude > 0.0 })
    }

    @Test
    fun `offset polyline degenerate inputs are returned unchanged`() {
        assertEquals(emptyList(), offsetPolyline(emptyList(), 100.0))
        val single = listOf(GeoPoint(1.0, 2.0))
        assertEquals(single, offsetPolyline(single, 100.0))
        val two = listOf(GeoPoint(0.0, 0.0), GeoPoint(0.01, 0.0))
        assertEquals(two, offsetPolyline(two, 0.0))
        // a duplicated point makes a zero-length segment; the polyline still shifts
        val withDupe = listOf(GeoPoint(0.0, 0.0), GeoPoint(0.0, 0.0), GeoPoint(0.01, 0.0))
        val shifted = offsetPolyline(withDupe, 50.0)
        assertEquals(3, shifted.size)
        // a bend averages its normals rather than tearing at the vertex
        val bend = listOf(GeoPoint(0.0, 0.0), GeoPoint(0.01, 0.0), GeoPoint(0.01, 0.01))
        val bentShifted = offsetPolyline(bend, 100.0)
        assertTrue(bentShifted[1].latitude < 0.0 && bentShifted[1].longitude > 0.01)
    }

    @Test
    fun `refresh decision matrix`() {
        val centre = 37.0 to -122.0
        // below the zoom floor always clears, even with a fresh fetch cached
        assertEquals(
            RailRefresh.CLEAR,
            railRefreshDecision(8.0, centre, centre, 12.0, 12.0, overlayNull = false, spansEmpty = false),
        )
        // never fetched: refetch
        assertEquals(
            RailRefresh.REFETCH,
            railRefreshDecision(12.0, centre, null, Double.NaN, Double.NaN, overlayNull = true, spansEmpty = true),
        )
        // drifted beyond the recenter radius: refetch
        assertEquals(
            RailRefresh.REFETCH,
            railRefreshDecision(12.0, 37.03 to -122.0, centre, 12.0, 12.0, overlayNull = false, spansEmpty = false),
        )
        // zoom stepped a full level since the fetch: refetch
        assertEquals(
            RailRefresh.REFETCH,
            railRefreshDecision(13.0, centre, centre, 12.0, 12.0, overlayNull = false, spansEmpty = false),
        )
        // inside the footprint with no spans fetched: reuse (nothing to fan)
        assertEquals(
            RailRefresh.REUSE,
            railRefreshDecision(12.0, centre, centre, 12.0, 12.0, overlayNull = true, spansEmpty = true),
        )
        // overlay cleared by the zoom gate but spans cached: refan
        assertEquals(
            RailRefresh.REFAN,
            railRefreshDecision(12.0, centre, centre, 12.0, Double.NaN, overlayNull = true, spansEmpty = false),
        )
        // lane-count band changed since the fan: refan
        assertEquals(
            RailRefresh.REFAN,
            railRefreshDecision(13.0, centre, centre, 12.5, 12.0, overlayNull = false, spansEmpty = false),
        )
        // same band, overlay live: reuse
        assertEquals(
            RailRefresh.REUSE,
            railRefreshDecision(12.0, centre, centre, 12.0, 12.0, overlayNull = false, spansEmpty = false),
        )
    }
}
