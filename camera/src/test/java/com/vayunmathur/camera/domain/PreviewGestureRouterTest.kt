package com.vayunmathur.camera.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Pinch/tap/long-press routing for the preview's single gesture handler. */
class PreviewGestureRouterTest {

    private class Recorder {
        val zooms = mutableListOf<Float>()
        val taps = mutableListOf<Pair<Float, Float>>()
        val longPresses = mutableListOf<Pair<Float, Float>>()
        val router = PreviewGestureRouter(
            onZoom = { zooms.add(it) },
            onTap = { x, y -> taps.add(x to y) },
            onLongPress = { x, y -> longPresses.add(x to y) },
        )

        fun pinchGesture(startZoom: Float = 1f, vararg scaleSteps: Float) {
            router.updateZoomRange(0.5f, 10f)
            router.pointerDown(1, 100f, 100f, startZoom)
            router.pointerDown(2, 150f, 100f, startZoom)
            scaleSteps.forEach { router.pinch(it) }
            router.gestureEnd()
        }
    }

    @Test
    fun pinchEmitsAccumulatedZoomAndNoFocusEvents() {
        val r = Recorder()
        r.pinchGesture(startZoom = 1f, 1.5f, 1.5f)
        // 1 x 1.5 x 1.5, applied live per step.
        assertEquals(listOf(1.5f, 2.25f), r.zooms)
        assertTrue(r.taps.isEmpty(), "pinch must not fire tap-to-focus")
        assertTrue(r.longPresses.isEmpty(), "pinch must not fire focus lock")
    }

    @Test
    fun pinchStartsFromZoomAtGestureStartNotOne() {
        val r = Recorder()
        r.pinchGesture(startZoom = 2f, 1.5f)
        assertEquals(listOf(3f), r.zooms)
    }

    @Test
    fun pinchClampsAtMaxZoom() {
        val r = Recorder()
        r.pinchGesture(startZoom = 1f, 4f, 4f, 4f)
        assertEquals(listOf(4f, 10f, 10f), r.zooms)
    }

    @Test
    fun pinchClampsAtMinZoom() {
        val r = Recorder()
        r.pinchGesture(startZoom = 2f, 0.5f, 0.1f)
        assertEquals(listOf(1f, 0.5f), r.zooms)
    }

    @Test
    fun pinchAfterSecondFingerSuppressesEarlierTapIntent() {
        val r = Recorder()
        r.router.updateZoomRange(0.5f, 10f)
        r.router.pointerDown(1, 10f, 10f, 1f)
        // Second finger lands before any up: the whole gesture is a pinch.
        r.router.pointerDown(2, 60f, 10f, 1f)
        r.router.pinch(2f)
        r.router.gestureEnd()
        assertEquals(listOf(2f), r.zooms)
        assertTrue(r.taps.isEmpty())
    }

    @Test
    fun singleTapEmitsExactlyOneFocusEventAndNoZoom() {
        val r = Recorder()
        r.router.pointerDown(1, 40f, 80f, 1f)
        r.router.gestureEnd()
        assertEquals(listOf(40f to 80f), r.taps)
        assertTrue(r.zooms.isEmpty())
        assertTrue(r.longPresses.isEmpty())
    }

    @Test
    fun longPressEmitsLockAndSuppressesTapOnRelease() {
        val r = Recorder()
        r.router.pointerDown(1, 30f, 30f, 1f)
        r.router.longPressTimeout()
        r.router.gestureEnd()
        assertEquals(listOf(30f to 30f), r.longPresses)
        assertTrue(r.taps.isEmpty())
        assertTrue(r.zooms.isEmpty())
    }

    @Test
    fun longPressDoesNotFireDuringPinch() {
        val r = Recorder()
        r.router.updateZoomRange(0.5f, 10f)
        r.router.pointerDown(1, 10f, 10f, 1f)
        r.router.pointerDown(2, 50f, 10f, 1f)
        r.router.longPressTimeout()
        assertTrue(r.longPresses.isEmpty())
    }

    @Test
    fun movementBeyondSlopSuppressesTapAndLongPress() {
        val r = Recorder()
        r.router.pointerDown(1, 0f, 0f, 1f)
        r.router.pointerMoved(100f, 0f, touchSlopPx = 10f)
        r.router.longPressTimeout()
        r.router.gestureEnd()
        assertTrue(r.taps.isEmpty())
        assertTrue(r.longPresses.isEmpty())
    }

    @Test
    fun cancelledGestureEmitsNothing() {
        val r = Recorder()
        r.router.pointerDown(1, 5f, 5f, 1f)
        r.router.gestureCancel()
        assertTrue(r.taps.isEmpty())
        assertTrue(r.zooms.isEmpty())
    }

    @Test
    fun invalidPinchFactorsAreDropped() {
        val r = Recorder()
        r.router.updateZoomRange(0.5f, 10f)
        r.router.pointerDown(1, 0f, 0f, 1f)
        r.router.pointerDown(2, 10f, 0f, 1f)
        r.router.pinch(Float.NaN)
        r.router.pinch(0f)
        r.router.pinch(-1f)
        r.router.pinch(2f)
        assertEquals(listOf(2f), r.zooms)
    }

    @Test
    fun degenerateZoomRangeCollapsesToOne() {
        val r = Recorder()
        r.router.updateZoomRange(Float.NaN, -1f)
        r.router.pointerDown(1, 0f, 0f, 1f)
        r.router.pointerDown(2, 10f, 0f, 1f)
        r.router.pinch(3f)
        assertEquals(listOf(1f), r.zooms)
    }
}
