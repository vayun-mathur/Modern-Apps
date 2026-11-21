package com.vayunmathur.camera.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PreviewExposurePolicyTest {

    private fun split(stillNanos: Long, stillIso: Int, isoMax: Int? = null) =
        PreviewExposurePolicy.split(stillNanos, stillIso, isoMax)

    @Test
    fun shortExposureIsNotSplit() {
        val split = split(16_666_667L, 400) // 1/60s
        assertEquals(16_666_667L, split.previewNanos)
        assertEquals(400, split.previewIso)
        assertEquals(16_666_667L, split.stillNanos)
        assertEquals(400, split.stillIso)
        assertFalse(split.stillOverrideRequired)
    }

    @Test
    fun capBoundaryIsNotSplit() {
        val split = split(PreviewExposurePolicy.PREVIEW_MAX_EXPOSURE_NANOS, 800)
        assertFalse(split.stillOverrideRequired)
        assertEquals(33_333_333L, split.previewNanos)
    }

    @Test
    fun oneSecondExposureCapsPreviewAtThirtyFpsAndBoostsIso() {
        val split = split(1_000_000_000L, 100)
        assertEquals(33_333_333L, split.previewNanos)
        // 1s / (1/30s) = 30x gain: ISO 100 -> 3000 for equal preview brightness.
        assertEquals(3000, split.previewIso)
        assertEquals(1_000_000_000L, split.stillNanos)
        assertEquals(100, split.stillIso)
        assertTrue(split.stillOverrideRequired)
        assertEquals(30.0, PreviewExposurePolicy.maxFps(split.previewNanos), 0.01)
        assertEquals(1.0, PreviewExposurePolicy.maxFps(split.stillNanos), 0.001)
    }

    @Test
    fun fourSecondExposureBoostClampsToSensorCeiling() {
        val split = split(4_000_000_000L, 400, isoMax = 12_800)
        assertEquals(33_333_333L, split.previewNanos)
        assertEquals(12_800, split.previewIso) // unclamped would be 48_000
        assertEquals(4_000_000_000L, split.stillNanos)
        assertEquals(400, split.stillIso)
        assertTrue(split.stillOverrideRequired)
    }

    @Test
    fun nightSingleExposureQuarterSecondSplits() {
        val split = split(250_000_000L, 1600)
        assertEquals(33_333_333L, split.previewNanos)
        assertEquals(12_000, split.previewIso) // 7.5x gain
        assertTrue(split.stillOverrideRequired)
    }

    @Test
    fun previewIsoNeverDropsBelowStillIso() {
        // Just over the cap: gain ~1.0x must not round the preview ISO down.
        val split = split(34_000_000L, 101)
        assertTrue(split.previewIso >= 101)
    }

    @Test
    fun maxFpsOfNonPositiveExposureIsUnbounded() {
        assertEquals(Double.POSITIVE_INFINITY, PreviewExposurePolicy.maxFps(0L))
    }
}
