package com.vayunmathur.camera.domain

import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Log-scale drag-delta->ratio mapping and relative scrub routing for the ZoomBar. */
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
    fun dragDeltaTraversesWholeRangeOverTwoBarWidths() {
        // Sensitivity: full log range over ZOOM_SCRUB_FULL_RANGE_WIDTHS (2)
        // bar widths. Two full widths from the minimum reaches the maximum.
        assertEquals(8f, zoomRatioForDragDelta(0.5f, 400f, 200f, 0.5f, 8f), 0.001f)
        // One width multiplies by sqrt(max/min) = 4: 0.5x -> 2x.
        assertEquals(2f, zoomRatioForDragDelta(0.5f, 200f, 200f, 0.5f, 8f), 0.001f)
        // Half a width from 1x multiplies by 16^0.25 = 2.
        assertEquals(2f, zoomRatioForDragDelta(1f, 100f, 200f, 0.5f, 8f), 0.001f)
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
        router.dragStart(100f, 200f, currentRatio = 1f)
        router.dragTo(105f, touchSlopPx = 10f)
        assertTrue(emitted.isEmpty(), "movement within slop must not zoom")
        assertFalse(router.isScrubbing)
        router.dragTo(150f, touchSlopPx = 10f)
        router.dragTo(200f, touchSlopPx = 10f)
        assertTrue(router.isScrubbing)
        assertEquals(2, emitted.size)
        // Relative from the 1x anchor: dx=50 -> 16^(50/400) = 1.414x,
        // dx=100 -> 16^(100/400) = 2x. Position on the bar is irrelevant.
        assertEquals(1.4142f, emitted[0], 0.001f)
        assertEquals(2f, emitted[1], 0.001f)
        assertTrue(router.dragEnd(), "a scrubbed gesture must not read as a tap")
    }

    @Test
    fun routerTouchdownAnywhereEmitsNothingUntilMovement() {
        // Touch-down (and sub-slop movement) at the left edge, middle and
        // right edge of the bar must all emit nothing: position is not zoom.
        for (downX in listOf(0f, 100f, 200f)) {
            val emitted = mutableListOf<Float>()
            val router = ZoomScrubRouter(onZoom = { emitted.add(it) })
            router.updateZoomRange(0.5f, 8f)
            router.dragStart(downX, 200f, currentRatio = 1f)
            assertTrue(emitted.isEmpty(), "touch-down at $downX must not zoom")
            router.dragTo(downX + 5f, touchSlopPx = 10f)
            assertTrue(emitted.isEmpty(), "sub-slop move at $downX must not zoom")
            assertFalse(router.dragEnd(), "no scrub past slop means tap at $downX")
        }
    }

    @Test
    fun routerEqualDeltasFromDifferentStartsProduceEqualMultiplicativeChanges() {
        fun scrub(downX: Float, startRatio: Float, dx: Float): Float {
            val emitted = mutableListOf<Float>()
            val router = ZoomScrubRouter(onZoom = { emitted.add(it) })
            router.updateZoomRange(0.5f, 8f)
            router.dragStart(downX, 200f, currentRatio = startRatio)
            router.dragTo(downX + dx, touchSlopPx = 1f)
            return emitted.single()
        }
        // Same delta + same anchor, different touchdown: identical result.
        assertEquals(scrub(10f, 1f, 40f), scrub(150f, 1f, 40f), 0.0001f)
        // Same delta, different anchors: equal multiplicative change.
        val from1x = scrub(20f, 1f, 40f)
        val from2x = scrub(180f, 2f, 40f)
        assertEquals(from1x / 1f, from2x / 2f, 0.001f)
    }

    @Test
    fun routerSmallDragFromOneXNeverJumpsTowardMax() {
        // Finger lands at the far (positional-max) end of the bar at 1x and
        // moves a little: the old absolute mapping emitted ~max; the
        // relative scrub must stay near 1x. dx=20/200 -> 16^(20/400) = 1.15x.
        val emitted = mutableListOf<Float>()
        val router = ZoomScrubRouter(onZoom = { emitted.add(it) })
        router.updateZoomRange(0.5f, 8f)
        router.dragStart(195f, 200f, currentRatio = 1f)
        router.dragTo(215f, touchSlopPx = 1f)
        assertEquals(1, emitted.size)
        assertEquals(1.1487f, emitted[0], 0.001f)
        assertTrue(emitted[0] < 1.3f, "small drag must stay near anchor, got $emitted")
    }

    @Test
    fun routerLongDragHitsClamp() {
        val emitted = mutableListOf<Float>()
        val router = ZoomScrubRouter(onZoom = { emitted.add(it) })
        router.updateZoomRange(0.5f, 8f)
        router.dragStart(100f, 200f, currentRatio = 2f)
        router.dragTo(100f + 10_000f, touchSlopPx = 1f)
        router.dragTo(100f - 10_000f, touchSlopPx = 1f)
        assertEquals(listOf(8f, 0.5f), emitted)
    }

    @Test
    fun routerTapAndCancelEmitNothing() {
        val emitted = mutableListOf<Float>()
        val router = ZoomScrubRouter(onZoom = { emitted.add(it) })
        router.updateZoomRange(0.5f, 8f)
        router.dragStart(100f, 200f, currentRatio = 1f)
        router.dragTo(102f, touchSlopPx = 10f)
        assertFalse(router.dragEnd(), "a plain press-release is a tap")
        assertTrue(emitted.isEmpty())

        router.dragStart(100f, 200f, currentRatio = 1f)
        router.dragTo(190f, touchSlopPx = 10f)
        router.dragCancel()
        assertFalse(router.isScrubbing)
        // The pre-cancel live emission stands (it was applied live), but
        // the cancel itself adds nothing and leaves the router reusable.
        assertEquals(1, emitted.size)
        router.dragStart(100f, 200f, currentRatio = 1f)
        assertFalse(router.dragEnd())
    }

    @Test
    fun routerFreezesWidthAndAnchorCapturedAtDragStart() {
        val emitted = mutableListOf<Float>()
        val router = ZoomScrubRouter(onZoom = { emitted.add(it) })
        router.updateZoomRange(1f, 4f)
        router.dragStart(50f, 100f, currentRatio = 2f)
        // Same delta always yields the same ratio within one gesture, even
        // though the live-chip insertion would widen the real bar and the
        // live ratio has since moved: dx=50 -> 2x * 4^(50/200) = 2.83x.
        router.dragTo(100f, touchSlopPx = 1f)
        router.dragTo(100f, touchSlopPx = 1f)
        assertEquals(2, emitted.size)
        assertEquals(2.8284f, emitted[0], 0.001f)
        assertEquals(emitted[0], emitted[1], 0.0001f)
    }

    @Test
    fun routerDegenerateRangeEmitsLockedRatio() {
        val emitted = mutableListOf<Float>()
        val router = ZoomScrubRouter(onZoom = { emitted.add(it) })
        router.updateZoomRange(Float.NaN, -1f)
        router.dragStart(0f, 100f, currentRatio = 1f)
        router.dragTo(100f, touchSlopPx = 1f)
        assertEquals(listOf(1f), emitted)
    }
}
