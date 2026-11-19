package com.vayunmathur.camera.ui

import kotlin.test.Test
import kotlin.test.assertEquals

/** Tap-to-focus metering conversion: clamping and centre fallback. */
class TapFocusMeteringTest {

    @Test
    fun centreOfSurfaceNormalisesToHalf() {
        val (x, y) = normalizedMeteringCoordinates(960f, 540f, 1920f, 1080f)
        assertEquals(0.5f, x, 0.0001f)
        assertEquals(0.5f, y, 0.0001f)
    }

    @Test
    fun cropEdgeTapClampsIntoRangeInsteadOfBeingRejected() {
        // ContentScale.Crop can map an edge tap just outside the buffer;
        // CameraX rejects >1, so the point must clamp to the edge.
        val (x, y) = normalizedMeteringCoordinates(2100f, -20f, 1920f, 1080f)
        assertEquals(1f, x, 0.0001f)
        assertEquals(0f, y, 0.0001f)
    }

    @Test
    fun unpopulatedTransformFallsBackToCentre() {
        val (x, y) = normalizedMeteringCoordinates(Float.NaN, Float.POSITIVE_INFINITY, 1920f, 1080f)
        assertEquals(0.5f, x, 0.0001f)
        assertEquals(0.5f, y, 0.0001f)
    }

    @Test
    fun degenerateSurfaceFallsBackToCentre() {
        val (x, y) = normalizedMeteringCoordinates(10f, 10f, 0f, 0f)
        assertEquals(0.5f, x, 0.0001f)
        assertEquals(0.5f, y, 0.0001f)
    }
}
