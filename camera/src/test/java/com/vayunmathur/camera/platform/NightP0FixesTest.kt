package com.vayunmathur.camera.platform

import com.vayunmathur.camera.ui.nightAffordanceVisible
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Night P0: own-path affordance, honest progress, and merge downscale math. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class NightP0FixesTest {

    @Test fun affordanceVisibleWhenNightDetectedWithoutExtension() {
        // No extension parameter in the rule: detection alone surfaces the moon button
        // (custom burst path), and it stays visible when overridden off so it can be
        // toggled back on.
        assertTrue(nightAffordanceVisible(lowLightDetected = true, nightModeActive = true))
        assertTrue(nightAffordanceVisible(lowLightDetected = true, nightModeActive = false))
        assertTrue(nightAffordanceVisible(lowLightDetected = false, nightModeActive = true))
        assertFalse(nightAffordanceVisible(lowLightDetected = false, nightModeActive = false))
    }

    @Test fun frameCountProgressMapping() {
        assertEquals("Capturing 0/6", nightCaptureProgressLabel(0, 6))
        assertEquals("Capturing 3/6", nightCaptureProgressLabel(3, 6))
        assertEquals("Capturing 6/6", nightCaptureProgressLabel(6, 6))
        assertEquals("Capturing 6/6", nightCaptureProgressLabel(9, 6))
        assertEquals("Capturing", nightCaptureProgressLabel(0, 0))
        assertEquals(0f, nightCaptureProgressFraction(0, 6))
        assertEquals(0.5f, nightCaptureProgressFraction(3, 6))
        assertEquals(1f, nightCaptureProgressFraction(6, 6))
        assertEquals(1f, nightCaptureProgressFraction(9, 6))
        assertEquals(0f, nightCaptureProgressFraction(3, 0))
        assertEquals("Merging…", NIGHT_MERGING_LABEL)
    }

    @Test fun downscaleDimensionMath() {
        // Within / at the cap: unchanged.
        assertEquals(1920 to 1080, NightCaptureEngine.mergeScaledDimensions(1920, 1080))
        assertEquals(4000 to 2000, NightCaptureEngine.mergeScaledDimensions(4000, 2000))
        // 12 MP (4000x3000) is capped to <=8 MP, aspect preserved.
        val (w, h) = NightCaptureEngine.mergeScaledDimensions(4000, 3000)
        assertEquals(3265 to 2449, w to h)
        assertTrue(w.toLong() * h <= NightCaptureEngine.MAX_MERGE_PIXELS)
        // Large square and degenerate inputs.
        val (sw, sh) = NightCaptureEngine.mergeScaledDimensions(10000, 10000)
        assertEquals(2828 to 2828, sw to sh)
        assertTrue(sw.toLong() * sh <= NightCaptureEngine.MAX_MERGE_PIXELS)
        assertEquals(0 to 100, NightCaptureEngine.mergeScaledDimensions(0, 100))
        assertEquals(100 to -1, NightCaptureEngine.mergeScaledDimensions(100, -1))
    }
}
