package com.vayunmathur.maps.ui.streetview

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Slice 1/3 (maps): the Street View projection math — bearing↔yaw round
 * trips and the 8-wind compass labels on the travel arrows.
 */
class StreetViewMathSlice1Test {

    @Test
    fun `a bearing round-trips through yaw`() {
        for (heading in listOf(0.0, 90.0, 180.0, 270.0)) {
            for (bearing in listOf(0.0, 45.0, 90.0, 180.0, 270.0, 359.0)) {
                val yaw = panoYawForBearing(heading, bearing)
                val back = panoBearingForYaw(heading, yaw)
                val delta = abs(back - bearing).let { minOf(it, 360.0 - it) }
                // Float yaw precision: exact at the poles, ~1e-5 elsewhere.
                assertTrue(delta < 1e-4, "heading=$heading bearing=$bearing -> $back")
            }
        }
    }

    @Test
    fun `looking along the capture heading faces the image centre`() {
        // bearing == heading sits at u == 0.5, i.e. yaw == -pi.
        assertTrue(abs(panoYawForBearing(90.0, 90.0) + Math.PI.toFloat()) < 1e-6)
    }

    @Test
    fun `compass points follow the truncated 8-wind`() {
        // Truncation, not rounding: each label covers the 45° after its
        // nominal direction. Pinned as-authored (a11y label only).
        assertEquals("N", compassPoint(0.0))
        assertEquals("N", compassPoint(22.0))
        assertEquals("N", compassPoint(44.9))
        assertEquals("NE", compassPoint(45.0))
        assertEquals("E", compassPoint(90.0))
        assertEquals("SE", compassPoint(135.0))
        assertEquals("S", compassPoint(180.0))
        assertEquals("SW", compassPoint(225.0))
        assertEquals("W", compassPoint(270.0))
        assertEquals("NW", compassPoint(315.0))
        assertEquals("NW", compassPoint(359.0))
        // Negative and wrapped bearings land on the same wind.
        assertEquals("W", compassPoint(-90.0))
        assertEquals("N", compassPoint(720.0))
    }
}
