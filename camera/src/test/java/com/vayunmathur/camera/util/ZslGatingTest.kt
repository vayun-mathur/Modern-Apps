package com.vayunmathur.camera.util

import android.os.Looper
import androidx.annotation.OptIn
import androidx.camera.core.ExperimentalZeroShutterLag
import androidx.camera.core.ImageCapture
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * ZSL gating (#914): the session-rebind signal flips with manual exposure and night mode but is
 * held while a shot is in flight, and a ZSL-bound ImageCapture never takes the night path.
 */
@RunWith(AndroidJUnit4::class)
class ZslGatingTest {

    private fun vm() = CameraViewModel(ApplicationProvider.getApplicationContext())

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    private fun CameraViewModel.engageNight() {
        repeat(CameraViewModel.NIGHT_DEBOUNCE_FRAMES) { onLuminance(5f) }
        idle()
    }

    @Test
    fun manualExposureAndNightTurnZslOff() {
        val v = vm()
        idle()
        assertTrue(v.zslCompatible.value)

        v.exposureTimeIndexMutable.value = 3
        idle()
        assertFalse(v.zslCompatible.value)
        v.exposureTimeIndexMutable.value = 0
        v.manualIsoIndexMutable.value = 2
        idle()
        assertFalse(v.zslCompatible.value)
        v.manualIsoIndexMutable.value = 0
        idle()
        assertTrue(v.zslCompatible.value)

        v.engageNight()
        assertTrue(v.nightModeActive.value)
        assertFalse(v.zslCompatible.value)
    }

    @Test
    fun valueHeldWhileCapturingThenApplied() {
        val v = vm()
        idle()
        v.isCapturingMutable.value = true
        idle()
        // Night engaging mid-shot must not rebind the session under the capture.
        v.engageNight()
        assertTrue(v.zslCompatible.value)

        v.isCapturingMutable.value = false
        idle()
        assertFalse(v.zslCompatible.value)
    }

    @OptIn(ExperimentalZeroShutterLag::class)
    @Test
    fun zslBoundCaptureNeverTakesTheNightPath() {
        val v = vm()
        v.engageNight()
        v.imageCapture = ImageCapture.Builder()
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
            .build()
        assertTrue(v.nightCaptureApplies())
        assertTrue(v.routeStillCapture() == StillRoute.NIGHT)

        v.imageCapture = ImageCapture.Builder()
            .setCaptureMode(ImageCapture.CAPTURE_MODE_ZERO_SHUTTER_LAG)
            .build()
        assertTrue(v.stillCaptureIsZsl())
        assertFalse(v.nightCaptureApplies())
        assertTrue(v.routeStillCapture() != StillRoute.NIGHT)
    }
}
