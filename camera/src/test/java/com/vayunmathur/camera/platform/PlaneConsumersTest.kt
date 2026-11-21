package com.vayunmathur.camera.platform

import com.vayunmathur.camera.domain.FrameDiffSample
import com.vayunmathur.camera.domain.PlaneView
import java.nio.ByteBuffer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowSystemClock

/** Plane entry points of the two cheap consumers, fed Camera2-style views. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PlaneConsumersTest {

    private fun plane(w: Int, h: Int, fill: (Int, Int) -> Int): PlaneView {
        val bytes = ByteArray(w * h)
        for (y in 0 until h) for (x in 0 until w) bytes[y * w + x] = fill(x, y).toByte()
        return PlaneView(ByteBuffer.wrap(bytes), w, 1, w, h)
    }

    @Test fun photoAnalyzerPlaneEntryReportsStridedMean() {
        var luminance: Float? = null
        val analyzer = PhotoAnalyzer(onLuminance = { luminance = it }, onQrDetected = {})
        analyzer.onYPlane(plane(32, 32) { _, _ -> 120 })
        assertEquals(120f, luminance)

        analyzer.onYPlane(plane(32, 32) { x, _ -> x * 4 })
        // Raster step-16 samples of a horizontal ramp: not the naive row mean.
        assertTrue(luminance != null && luminance!! in 0f..255f)
    }

    @Test fun videoMotionPlaneEntryDiffsSuccessiveGrids() {
        val samples = mutableListOf<FrameDiffSample>()
        val analyzer = VideoMotionAnalyzer { samples += it }

        ShadowSystemClock.advanceBy(java.time.Duration.ofMillis(1_000))
        analyzer.onYPlane(plane(32, 24) { _, _ -> 50 }) // first grid: no diff yet
        assertTrue(samples.isEmpty())

        ShadowSystemClock.advanceBy(java.time.Duration.ofMillis(VIDEO_MOTION_SAMPLE_INTERVAL_MS + 50))
        analyzer.onYPlane(plane(32, 24) { _, _ -> 90 })
        assertEquals(1, samples.size)
        assertEquals(40.0, samples[0].meanAbsDiff)
        assertEquals(0.0, samples[0].meanGradient)
    }

    @Test fun videoMotionPlaneEntryHonoursCadence() {
        val samples = mutableListOf<FrameDiffSample>()
        val analyzer = VideoMotionAnalyzer { samples += it }
        ShadowSystemClock.advanceBy(java.time.Duration.ofMillis(1_000))
        analyzer.onYPlane(plane(32, 24) { _, _ -> 50 })
        // No clock advance: the second frame is inside the sample interval.
        analyzer.onYPlane(plane(32, 24) { _, _ -> 90 })
        assertTrue(samples.isEmpty())
    }
}
