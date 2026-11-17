package com.vayunmathur.maps.util
import com.vayunmathur.maps.platform.smoothHeading

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The compass low-pass filter behind the user puck's bearing cone.
 *
 * Pure arithmetic on an angle, so it tests without a sensor. The wrap case is the one that
 * matters: a naive lerp between 359 and 1 takes the 358-degree route and spins the cone
 * all the way round the puck, which is a very visible bug and an easy one to write.
 */
class SmoothHeadingTest {

    @Test
    fun `the first reading is taken whole`() {
        assertEquals(213f, smoothHeading(null, 213f))
    }

    @Test
    fun `crossing north takes the short way round`() {
        // 359 to 1 is +2 degrees, not -358. At alpha 0.5 that lands on 0, not 180.
        assertEquals(0f, smoothHeading(359f, 1f, alpha = 0.5f))
        // And the other way, 1 to 359, is -2.
        assertEquals(0f, smoothHeading(1f, 359f, alpha = 0.5f))
    }

    @Test
    fun `the result stays in zero to 360`() {
        // Stepping backwards past north must wrap rather than going negative: the shader
        // takes this as radians and a negative bearing would point the cone at its mirror.
        val stepped = smoothHeading(0.25f, 359f, alpha = 0.5f)
        assertTrue(stepped in 0f..360f, "$stepped is outside 0..360")
        assertEquals(359.625f, stepped, absoluteTolerance = 1e-3f)
    }

    @Test
    fun `a constant reading converges rather than creeping`() {
        // Every step moves a fraction of the remaining gap, so repeated identical readings
        // must settle on that reading and stay there — not orbit it, and not drift off.
        var heading = smoothHeading(null, 90f)
        repeat(200) { heading = smoothHeading(heading, 275f) }
        assertTrue(abs(heading - 275f) < 0.01f, "settled at $heading rather than 275")
        assertEquals(heading, smoothHeading(heading, 275f), absoluteTolerance = 1e-4f)
    }

    @Test
    fun `a step is a fraction of the gap, not the whole of it`() {
        // The point of the filter: a jittery magnetometer reading must not reach the puck
        // in full, or removing Compose's frame of latency makes the cone twitch.
        val stepped = smoothHeading(100f, 140f, alpha = 0.15f)
        assertEquals(106f, stepped, absoluteTolerance = 1e-3f)
    }
}
