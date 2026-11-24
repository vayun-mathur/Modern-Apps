package com.vayunmathur.camera.domain

import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Log-scale position->ratio mapping and scrub routing for the ZoomBar. */
class ZoomScrubTest {

    @Test
    fun endpointsAreMinAndMax() {
        assertEquals(0.5f, zoomRatioForFraction(0f, 0.5f, 10f), 0.0001f)
        assertEquals(10f, zoomRatioForFraction(1f, 0.5f, 10f), 0.0001f)
        assertEquals(0.5f, zoomRatioForPosition(0f, 200f, 0.5f, 10f), 0.0001f)
        assertEquals(10f, zoomRatioForPosition(200f, 200f, 0.5f, 10f), 0.0001f)
    }

    @Test
    fun midpointIsGeometricMeanNotArithmeticMean() {
        // sqrt(0.5 * 8) = 2, whereas the arithmetic midpoint would be 4.25.
        assertEquals(2f, zoomRatioForFraction(0.5f, 0.5f, 8f), 0.001f)
        assertEquals(sqrt(0.5f * 10f), zoomRatioForPosition(100f, 200f, 0.5f, 10f), 0.001f)
    }

    @Test
    fun mappingIsMonotonicAcrossBar() {
        val ratios = (0..10).map { zoomRatioForFraction(it / 10f, 0.5f, 10f) }
        assertEquals(ratios.sorted(), ratios)
        assertTrue(ratios.zipWithNext().all { (a, b) -> a < b })
    }

    @Test
    fun outOfRangeInputsClampToRange() {
        assertEquals(0.5f, zoomRatioForFraction(-1f, 0.5f, 10f), 0.0001f)
        assertEquals(10f, zoomRatioForFraction(2f, 0.5f, 10f), 0.0001f)
        assertEquals(0.5f, zoomRatioForPosition(-50f, 200f, 0.5f, 10f), 0.0001f)
        assertEquals(10f, zoomRatioForPosition(999f, 200f, 0.5f, 10f), 0.0001f)
        assertEquals(0.5f, zoomRatioForFraction(Float.NaN, 0.5f, 10f), 0.0001f)
    }

    @Test
    fun degenerateAndInvalidRangesCollapse() {
        // min == max: every position is that ratio.
        assertEquals(2f, zoomRatioForFraction(0f, 2f, 2f), 0.0001f)
        assertEquals(2f, zoomRatioForFraction(0.7f, 2f, 2f), 0.0001f)
        assertEquals(2f, zoomRatioForPosition(123f, 200f, 2f, 2f), 0.0001f)
        // Non-finite / inverted / non-positive ranges collapse to 1x,
        // matching PreviewGestureRouter.updateZoomRange.
        assertEquals(1f, zoomRatioForFraction(0.5f, Float.NaN, 10f), 0.0001f)
        assertEquals(1f, zoomRatioForFraction(0.5f, 10f, 0.5f), 0.0001f)
        assertEquals(1f, zoomRatioForFraction(0.5f, 0f, 10f), 0.0001f)
        assertEquals(1f, zoomRatioForPosition(10f, 0f, 1f, 1f), 0.0001f)
    }

    @Test
    fun fractionInverseRoundTrips() {
        for (ratio in listOf(0.5f, 1f, 2f, 5f, 10f)) {
            val fraction = fractionForZoomRatio(ratio, 0.5f, 10f)
            assertEquals(ratio, zoomRatioForFraction(fraction, 0.5f, 10f), 0.001f)
        }
        assertEquals(0f, fractionForZoomRatio(0.1f, 0.5f, 10f), 0.0001f)
        assertEquals(1f, fractionForZoomRatio(99f, 0.5f, 10f), 0.0001f)
    }

    @Test
    fun dragDeltaTraversesWholeRangeOverOneBarWidth() {
        // One full width from the minimum reaches the maximum.
        assertEquals(8f, zoomRatioForDragDelta(0.5f, 200f, 200f, 0.5f, 8f), 0.001f)
        // Half a width from 1x (quarter-way in log space) reaches 3/4-way: 4x.
        assertEquals(4f, zoomRatioForDragDelta(1f, 100f, 200f, 0.5f, 8f), 0.001f)
        // Overshoot clamps; a degenerate width leaves the clamped start.
        assertEquals(8f, zoomRatioForDragDelta(1f, 10_000f, 200f, 0.5f, 8f), 0.001f)
        assertEquals(0.5f, zoomRatioForDragDelta(1f, -10_000f, 200f, 0.5f, 8f), 0.001f)
        assertEquals(2f, zoomRatioForDragDelta(2f, 100f, 0f, 0.5f, 8f), 0.001f)
    }

    @Test
    fun routerEmitsOnlyAfterSlopAndReportsScrub() {
        val emitted = mutableListOf<Float>()
        val router = ZoomScrubRouter(onZoom = { emitted.add(it) })
        router.updateZoomRange(0.5f, 8f)
        router.dragStart(100f, 200f)
        router.dragTo(105f, touchSlopPx = 10f)
        assertTrue(emitted.isEmpty(), "movement within slop must not zoom")
        assertFalse(router.isScrubbing)
        router.dragTo(150f, touchSlopPx = 10f)
        router.dragTo(200f, touchSlopPx = 10f)
        assertTrue(router.isScrubbing)
        assertEquals(2, emitted.size)
        // 150/200 -> fraction .75, 200/200 -> max, both in range and rising.
        assertTrue(emitted[0] in 0.5f..8f && emitted[1] == 8f && emitted[0] < emitted[1])
        assertTrue(router.dragEnd(), "a scrubbed gesture must not read as a tap")
    }

    @Test
    fun routerTapAndCancelEmitNothing() {
        val emitted = mutableListOf<Float>()
        val router = ZoomScrubRouter(onZoom = { emitted.add(it) })
        router.updateZoomRange(0.5f, 8f)
        router.dragStart(100f, 200f)
        router.dragTo(102f, touchSlopPx = 10f)
        assertFalse(router.dragEnd(), "a plain press-release is a tap")
        assertTrue(emitted.isEmpty())

        router.dragStart(100f, 200f)
        router.dragTo(190f, touchSlopPx = 10f)
        router.dragCancel()
        assertFalse(router.isScrubbing)
        // The pre-cancel live emission stands (it was applied live), but
        // the cancel itself adds nothing and leaves the router reusable.
        assertEquals(1, emitted.size)
        router.dragStart(100f, 200f)
        assertFalse(router.dragEnd())
    }

    @Test
    fun routerFreezesWidthCapturedAtDragStart() {
        val emitted = mutableListOf<Float>()
        val router = ZoomScrubRouter(onZoom = { emitted.add(it) })
        router.updateZoomRange(1f, 4f)
        router.dragStart(50f, 100f)
        // Same x always yields the same ratio within one gesture, even
        // though the live-chip insertion would widen the real bar.
        router.dragTo(100f, touchSlopPx = 1f)
        router.dragTo(100f, touchSlopPx = 1f)
        assertEquals(listOf(4f, 4f), emitted)
    }

    @Test
    fun routerDegenerateRangeEmitsLockedRatio() {
        val emitted = mutableListOf<Float>()
        val router = ZoomScrubRouter(onZoom = { emitted.add(it) })
        router.updateZoomRange(Float.NaN, -1f)
        router.dragStart(0f, 100f)
        router.dragTo(100f, touchSlopPx = 1f)
        assertEquals(listOf(1f), emitted)
    }
}
