package com.vayunmathur.camera.util

import android.graphics.Bitmap
import android.graphics.Color
import androidx.camera.core.ImageCapture
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.camera.domain.StillCaptureMode
import kotlinx.coroutines.runBlocking
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Cov-w3: pure/buildable parts of photo, portrait, night and video session setup.
 * CameraX provider binding is deliberately NOT exercised: awaitInstance() hangs
 * with no CameraX runtime in Robolectric (documented in CameraCovC2SessionsTest),
 * so builders and state logic that run before binding are covered instead.
 */
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CameraCovW3SessionsTest {
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private fun vm() = CameraViewModel(context.applicationContext as android.app.Application)

    @Test fun portraitAttemptLadders() {
        val ultra = portraitAttempts(true)
        assertEquals(6, ultra.size)
        assertTrue(ultra.first().capped && ultra.first().maxRes && ultra.first().ultra)
        assertEquals(PortraitAttempt(false, false, false), ultra.last())
        // Without Ultra HDR the ladder is shorter and never requests it.
        val plain = portraitAttempts(false)
        assertEquals(3, plain.size)
        assertTrue(plain.none { it.ultra })
        assertTrue(plain.first().maxRes)
        assertFalse(plain.last().maxRes)
    }

    @Test fun portraitBuildersProduceConfiguredUseCases() {
        val viewModel = vm()
        for (maxRes in listOf(true, false)) {
            for (ultra in listOf(true, false)) {
                val capture = viewModel.buildPortraitCapture(maxRes, ultra)
                assertNotNull(capture)
                assertEquals(capture, viewModel.imageCapture)
            }
        }
        // Ultra HDR capture must actually carry the JPEG_R output format.
        val ultraCapture = viewModel.buildPortraitCapture(maxResCapture = true, ultraHdr = true)
        assertEquals(ImageCapture.OUTPUT_FORMAT_JPEG_ULTRA_HDR, ultraCapture.outputFormat)
        val jpegCapture = viewModel.buildPortraitCapture(maxResCapture = false, ultraHdr = false)
        assertEquals(ImageCapture.OUTPUT_FORMAT_JPEG, jpegCapture.outputFormat)
        for (capped in listOf(true, false)) {
            val analysis = viewModel.buildPortraitAnalysis(capped, maxResCapture = true)
            assertNotNull(analysis)
            assertEquals(analysis, viewModel.imageAnalysis)
        }
    }

    @Test fun photoBuildersAcrossModesAndAspectRatios() {
        val viewModel = vm()
        for (mode in StillCaptureMode.entries) {
            for (maxRes in listOf(true, false)) {
                val capture = viewModel.buildPhotoCapture(maxRes, ultraHdr = mode == StillCaptureMode.ZERO_SHUTTER_LAG, mode = mode)
                assertNotNull(capture)
            }
        }
        val ultra = viewModel.buildPhotoCapture(maxRes = true, ultraHdr = true)
        assertEquals(ImageCapture.OUTPUT_FORMAT_JPEG_ULTRA_HDR, ultra.outputFormat)
        // Every aspect ratio flows into the capture crop without throwing.
        for (ratio in AspectRatioOption.entries) {
            viewModel.setAspectRatio(ratio)
            assertNotNull(viewModel.buildPhotoCapture(maxRes = false, ultraHdr = false))
            assertNotNull(viewModel.currentCropAspectRatio())
        }
        assertNotNull(viewModel.buildPhotoAnalysis())
    }

    @Test fun nightExposureFallbackAndCountdownLifecycle() = runBlocking<Unit> {
        val viewModel = vm()
        // No bound camera: target is used verbatim with auto ISO.
        val fallback = viewModel.computeNightExposure(123_000_000L)
        assertEquals(123_000_000L, fallback.nanos)
        assertNull(fallback.iso)
        assertEquals(
            CameraViewModel.NIGHT_TARGET_EXPOSURE_NANOS,
            viewModel.computeNightExposure().nanos,
        )
        // Countdown sets progress/remaining, stop clears both.
        viewModel.startLongExposureCountdown(2_000_000_000L)
        assertEquals(1f, viewModel.longExposureProgressMutable.value)
        assertTrue(viewModel.longExposureRemainingMutable.value.endsWith("s"))
        viewModel.stopLongExposureCountdown()
        assertEquals(0f, viewModel.longExposureProgressMutable.value)
        assertEquals("", viewModel.longExposureRemainingMutable.value)
        viewModel.startLongExposureCountdown(0L)
        viewModel.stopLongExposureCountdown()
    }

    @Test fun nightModeDetectionAndOverrideStateMachine() {
        val viewModel = vm()
        viewModel.resetNightModeDetection()
        // Bright frames never engage; sustained dark frames do (debounced).
        repeat(6) { viewModel.onLuminance(90f) }
        assertFalse(viewModel.nightModeActive.value)
        repeat(6) { viewModel.onLuminance(10f) }
        assertTrue(viewModel.lowLightDetected.value || viewModel.nightModeActive.value)
        viewModel.toggleNightModeOverride()
        viewModel.toggleNightModeOverride()
        viewModel.resetNightModeDetection()
        assertFalse(viewModel.nightExtFailedRecently())
        viewModel.recordNightExtensionFailure()
        viewModel.stopObservingNightModeIndicator()
        viewModel.stopObservingExtensionStrength()
        viewModel.stopObservingExtensionCameraState()
    }

    @Test fun sessionTeardownAndFlashModeMappingAllModes() {
        val viewModel = vm()
        for (mode in FlashMode.entries) {
            viewModel.setFlashMode(mode)
            val mapped = viewModel.getImageCaptureFlashMode()
            assertTrue(mapped in setOf(ImageCapture.FLASH_MODE_ON, ImageCapture.FLASH_MODE_OFF, ImageCapture.FLASH_MODE_AUTO))
        }
        viewModel.teardownSession()
        assertFalse(viewModel.photoSessionActiveMutable.value)
        // Capability probes degrade (not throw) on a bare CameraInfo proxy path:
        // with no bound camera they are simply not consulted; teardown is idempotent.
        viewModel.teardownSession()
    }

    @Test fun captureRoutingWithStateCombinations() {
        val viewModel = vm()
        // Route matrix: exercise routeStillCapture/night/ZSL gates across modes & settings.
        for (mode in CameraMode.entries) {
            viewModel.cameraModeMutable.value = mode
            viewModel.routeStillCapture()
            viewModel.nightCaptureApplies()
            viewModel.stillCaptureIsZsl()
        }
        viewModel.setExposureTimeIndex(3)
        viewModel.routeStillCapture()
        assertFalse(viewModel.stillCaptureIsZsl())
        viewModel.setExposureTimeIndex(0)
        viewModel.setManualIsoIndex(2)
        viewModel.routeStillCapture()
        // Burst lifecycle caps and clears.
        viewModel.startBurst()
        viewModel.stopBurst()
        assertFalse(viewModel.burstActive.value)
    }

    @Test fun videoRecordingEntryPointsWithoutBoundCapture() {
        val viewModel = vm()
        viewModel.toggleRecording()
        assertFalse(viewModel.isRecording.value)
        viewModel.toggleHighSpeedRecording()
        viewModel.togglePauseRecording()
        viewModel.toggleMicMuted()
        viewModel.captureVideoSnapshot()
        viewModel.startPanorama()
        viewModel.stopPanorama()
        viewModel.startPhotosphere()
        viewModel.stopPhotosphere()
        // Timer helpers for recording.
        viewModel.startRecordingTimer()
        viewModel.stopRecordingTimer()
        assertEquals(0L, viewModel.recordingDurationSecMutable.value)
    }

    @Test fun thumbnailAndSavePathsDegradeGracefully() = runBlocking<Unit> {
        val viewModel = vm()
        assertNull(viewModel.loadThumbnail(null))
        // A content:// URI is not exercised: Robolectric's ShadowContentResolver throws
        // UnsupportedOperationException for unregistered streams, which loadThumbnail's
        // IOException/SecurityException catches cannot intercept on the JVM.
        // Save routing under both targets.
        val bmp = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.RED) }
        viewModel.saveStillBitmap("cov-w3-bitmap.jpg", bmp)
        viewModel.saveStillBytes("cov-w3-bytes.jpg", byteArrayOf(9, 8, 7))
        val tree = android.net.Uri.parse("content://com.android.externalstorage.documents/tree/primary%3APictures%2FCovW3")
        viewModel.setSaveTreeUri(tree)
        viewModel.saveStillBitmap("cov-w3-saf.jpg", bmp)
        viewModel.prepareStillSave("cov-w3-saf2.jpg")?.closeStream()
        viewModel.clearSaveTreeUri()
        assertEquals(SaveTarget.MediaStoreDefault, viewModel.saveTargetMutable.value)
    }

    @Test fun zoomLabelsAndColorMatrixExactValues() {
        assertEquals(".5", formatZoomLabel(0.5f))
        assertEquals(".6", formatZoomLabel(0.6f))
        assertEquals("1x", formatZoomLabel(1f))
        assertEquals("2x", formatZoomLabel(2.04f))
        assertEquals("1.5x", formatZoomLabel(1.5f))
        assertEquals("10x", formatZoomLabel(10f))
        val neutral = buildColorAdjustmentMatrix(0f, 0f)
        val values = neutral.array
        assertEquals(1f, values[0]); assertEquals(1f, values[6]); assertEquals(1f, values[12])
        assertEquals(0f, values[4])
        val graded = buildColorAdjustmentMatrix(1f, 0.5f)
        // graded matrix values are read from its own array
        val values2 = graded.array
        assertTrue(values2[0] > 1f, "warmth raises red gain")
        assertTrue(values2[12] < 1f, "warmth lowers blue gain")
        assertEquals(20f, values2[4]) // 0.5 * 40 shadow lift
        // Exposure stops table is monotonic and Auto is the null stop.
        assertNull(CameraViewModel.EXPOSURE_TIME_STOPS.first().nanos)
        val nanos = CameraViewModel.EXPOSURE_TIME_STOPS.mapNotNull { it.nanos }
        assertEquals(nanos.sorted(), nanos)
    }

    @Test fun manualLifecycleOwnerTransitions() {
        val owner = ManualLifecycleOwner()
        owner.start()
        owner.destroy()
    }
}
