package com.vayunmathur.maps.util
import com.vayunmathur.maps.platform.headingDelta

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The compass emit gate behind the user puck's bearing cone.
 *
 * The magnetometer fires at ~60 ms whether or not the phone turned, and every
 * emission used to push `userPosition`/`userBearing` through the page (and wake
 * the renderer) — so a stationary phone recomposed at sensor rate. The gate
 * compares the smoothed heading against the last *emitted* one and drops
 * sub-degree jitter, while a real turn accumulates past it.
 */
class HeadingGateTest {

    @Test
    fun `the first reading always emits`() {
        assertEquals(Float.MAX_VALUE, headingDelta(null, 213f))
    }

    @Test
    fun `sub-degree jitter does not emit`() {
        // Settled sensor hovering around north: a tenth of a degree moves no pixel.
        assertTrue(headingDelta(90f, 90.4f) < 1.0f)
        assertTrue(headingDelta(90f, 89.6f) < 1.0f)
    }

    @Test
    fun `a real turn emits`() {
        assertTrue(headingDelta(90f, 92f) >= 1.0f)
        assertTrue(headingDelta(90f, 88f) >= 1.0f)
    }

    @Test
    fun `crossing north takes the short way round`() {
        // 359 to 1 is +2 degrees, not -358 — the gate must see a turn, not jitter.
        assertEquals(2f, headingDelta(359f, 1f), absoluteTolerance = 1e-3f)
        assertEquals(2f, headingDelta(1f, 359f), absoluteTolerance = 1e-3f)
        // And 359.9 to 0.1 is jitter, not a turn.
        assertTrue(headingDelta(359.9f, 0.1f) < 1.0f)
    }
}
