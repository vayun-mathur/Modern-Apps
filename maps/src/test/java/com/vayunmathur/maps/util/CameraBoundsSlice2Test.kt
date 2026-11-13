package com.vayunmathur.maps.util

import com.vayunmathur.library.map.GeoBounds
import com.vayunmathur.library.map.GeoPoint
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The visible-bounds fallback for a camera that has not measured yet.
 * Slice-2 coverage for maps (basename C, outside the slice-1 dirs).
 *
 * A fresh camera has no projection (the viewport is unmeasured), so every
 * caller gets the whole world rather than nothing.
 */
class CameraBoundsSlice2Test {

    @Test
    fun `an unmeasured camera falls back to the world`() {
        val bounds = com.vayunmathur.library.map.CameraState().visibleBoundsOrWorld()
        assertEquals(
            GeoBounds(west = -180.0, south = -85.0, east = 180.0, north = 85.0),
            bounds,
        )
    }

    @Test
    fun `the fallback is stable across cameras`() {
        val a = com.vayunmathur.library.map.CameraState().visibleBoundsOrWorld()
        val b = com.vayunmathur.library.map.CameraState().visibleBoundsOrWorld()
        assertEquals(a, b)
    }

    private val sf = GeoBounds(west = -122.45, south = 37.76, east = -122.38, north = 37.81)

    @Test
    fun `search centres on the user when they are on screen`() {
        val user = GeoPoint(-122.4075, 37.7879)
        assertEquals(user, searchBiasPoint(sf, user))
    }

    @Test
    fun `search centres on the screen when the user is elsewhere or has no fix`() {
        val centre = GeoPoint((-122.45 + -122.38) / 2.0, (37.76 + 37.81) / 2.0)
        assertEquals(centre, searchBiasPoint(sf, GeoPoint(2.3376, 48.8606)))
        assertEquals(centre, searchBiasPoint(sf, GeoPoint(0.0, 0.0)))
    }

    @Test
    fun `a view across the antimeridian still sees the user`() {
        val fiji = GeoBounds(west = 177.0, south = -19.0, east = -178.0, north = -16.0)
        val user = GeoPoint(179.5, -17.5)
        assertEquals(user, searchBiasPoint(fiji, user))
    }
}
