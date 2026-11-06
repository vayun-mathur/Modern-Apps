package com.vayunmathur.camera.util

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.vayunmathur.camera.domain.LensSelectionLogic
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Robolectric ViewModel tests: night-luminance detection and settings
 * toggles. No CameraX binding — only state flows and the hysteresis filter.
 */
@RunWith(AndroidJUnit4::class)
class CameraViewModelLogicTest {

    private fun vm() = CameraViewModel(ApplicationProvider.getApplicationContext())

    // ---- Night luminance detection ----

    @Test fun darkFramesEngageLowLight() {
        val v = vm()
        repeat(3) { v.onLuminance(10f) }
        assertFalse(v.lowLightDetected.value)
        v.onLuminance(10f)
        assertTrue(v.lowLightDetected.value)
    }

    @Test fun brightFramesResetDarkCounter() {
        val v = vm()
        repeat(3) { v.onLuminance(10f) }
        v.onLuminance(200f)
        // Counter reset: needs 4 fresh dark frames again.
        repeat(3) { v.onLuminance(10f) }
        assertFalse(v.lowLightDetected.value)
        v.onLuminance(10f)
        assertTrue(v.lowLightDetected.value)
    }

    @Test fun brightFramesDisengage() {
        val v = vm()
        repeat(4) { v.onLuminance(10f) }
        assertTrue(v.lowLightDetected.value)
        repeat(3) { v.onLuminance(200f) }
        assertTrue(v.lowLightDetected.value)
        v.onLuminance(200f)
        assertFalse(v.lowLightDetected.value)
        // Override flag resets on disengage.
        assertFalse(v.nightModeOverriddenOff.value)
    }

    @Test fun indicatorSupportedSkipsHeuristic() {
        val v = vm().apply { nightIndicatorSupported = true }
        repeat(8) { v.onLuminance(5f) }
        assertFalse(v.lowLightDetected.value)
    }

    @Test fun overrideToggleAndReset() {
        val v = vm()
        assertFalse(v.nightModeOverriddenOff.value)
        v.toggleNightModeOverride()
        assertTrue(v.nightModeOverriddenOff.value)
        v.toggleNightModeOverride()
        assertFalse(v.nightModeOverriddenOff.value)
        repeat(4) { v.onLuminance(5f) }
        assertTrue(v.lowLightDetected.value)
        v.resetNightModeDetection()
        assertFalse(v.lowLightDetected.value)
        assertFalse(v.nightModeOverriddenOff.value)
    }

    @Test fun nightFailureFreshness() {
        assertFalse(LensSelectionLogic.nightFailureFresh(null, 1000L, 100L))
        assertTrue(LensSelectionLogic.nightFailureFresh(950L, 1000L, 100L))
        assertFalse(LensSelectionLogic.nightFailureFresh(800L, 1000L, 100L))
    }

    // ---- Settings toggles ----

    @Test fun torchAndFlash() {
        val v = vm()
        assertFalse(v.torchEnabled.value)
        v.toggleTorch()
        assertTrue(v.torchEnabled.value)
        v.toggleTorch()
        assertFalse(v.torchEnabled.value)
        v.setFlashMode(FlashMode.AUTO)
        assertEquals(FlashMode.AUTO, v.flashMode.value)
    }

    @Test fun aspectRatioCycle() {
        val v = vm()
        assertEquals(AspectRatioOption.RATIO_4_3, v.aspectRatio.value)
        v.cycleAspectRatio()
        assertEquals(AspectRatioOption.RATIO_3_2, v.aspectRatio.value)
        v.cycleAspectRatio()
        assertEquals(AspectRatioOption.RATIO_16_9, v.aspectRatio.value)
        v.cycleAspectRatio()
        assertEquals(AspectRatioOption.RATIO_1_1, v.aspectRatio.value)
        v.cycleAspectRatio()
        assertEquals(AspectRatioOption.RATIO_4_3, v.aspectRatio.value)
    }

    @Test fun gridAndTimer() {
        val v = vm()
        assertFalse(v.gridEnabled.value)
        v.toggleGrid()
        assertTrue(v.gridEnabled.value)
        v.setTimerDuration(TimerDuration.FIVE)
        assertEquals(TimerDuration.FIVE, v.timerDuration.value)
    }

    @Test fun blurStrengthClamped() {
        val v = vm()
        v.setBlurStrength(2f)
        assertEquals(1f, v.blurStrength.value)
        v.setBlurStrength(-1f)
        assertEquals(0f, v.blurStrength.value)
        v.setBlurStrength(0.5f)
        assertEquals(0.5f, v.blurStrength.value)
    }

    @Test fun exposureAndGradingSetters() {
        val v = vm()
        v.setExposureCompensation(-2f)
        assertEquals(-2f, v.exposureCompensation.value)
        v.setWarmth(0.3f)
        assertEquals(0.3f, v.warmth.value)
        v.setShadows(-0.5f)
        assertEquals(-0.5f, v.shadows.value)
    }

    @Test fun videoCodecAndAudioSource() {
        val v = vm()
        v.setVideoCodec(VideoCodec.HEVC)
        assertEquals(VideoCodec.HEVC, v.videoCodec.value)
        v.setMotionPhotoDuration(MotionPhotoDuration.THREE)
        assertEquals(MotionPhotoDuration.THREE, v.motionPhotoDuration.value)
    }
}
