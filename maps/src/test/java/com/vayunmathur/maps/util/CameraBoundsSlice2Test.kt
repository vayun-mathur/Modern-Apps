package com.vayunmathur.maps.util

import com.vayunmathur.library.map.GeoBounds
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
}
