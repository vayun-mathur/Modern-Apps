package com.vayunmathur.camera.platform

import android.graphics.Bitmap
import android.graphics.Color
import android.hardware.camera2.CameraMetadata
import androidx.camera.core.ImageCapture
import androidx.test.core.app.ApplicationProvider
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.runner.RunWith
import org.mockito.Mockito
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Capture routing: AE gate, still-route selection, burst and motion ring. */
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CaptureRoutingExtraTest {

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private fun vm() = CameraViewModel(context.applicationContext as android.app.Application)

    @Test fun aeStateGate() {
        assertTrue(aeStateReadyForCapture(null))
        assertTrue(aeStateReadyForCapture(CameraMetadata.CONTROL_AE_STATE_CONVERGED))
        assertTrue(aeStateReadyForCapture(CameraMetadata.CONTROL_AE_STATE_LOCKED))
        assertFalse(aeStateReadyForCapture(CameraMetadata.CONTROL_AE_STATE_SEARCHING))
        assertFalse(aeStateReadyForCapture(CameraMetadata.CONTROL_AE_STATE_PRECAPTURE))
        assertFalse(aeStateReadyForCapture(CameraMetadata.CONTROL_AE_STATE_FLASH_REQUIRED))
        assertFalse(aeStateReadyForCapture(CameraMetadata.CONTROL_AE_STATE_INACTIVE))
        val viewModel = vm()
        viewModel.lastAeState = CameraMetadata.CONTROL_AE_STATE_SEARCHING
        assertFalse(viewModel.isAeConvergedForCapture())
        viewModel.lastAeState = null
        assertTrue(viewModel.isAeConvergedForCapture())
    }

    @Test fun routeDefaultsToMotionPhotoWhenEligible() {
        val viewModel = vm()
        // Fresh VM: PHOTO mode, 4:3, no bake, not for-result, queue has slots.
        assertEquals(StillRoute.MOTION_PHOTO, viewModel.routeStillCapture())
    }

    @Test fun routeAvifSelectsSingleNotMotionOnCameraX() {
        // Regression: an AVIF-selected plain shot served by CameraX (Camera2
        // fallback) used to divert to Motion Photo and land as .jpg. Only
        // meaningful when the native AVIF encoder is present; without it
        // AVIF honestly falls back to JPEG and Motion stays eligible.
        if (!StitchNative.isAvailable) return
        val viewModel = vm()
        viewModel.photoFormatMutable.value = PhotoFormat.AVIF
        assertEquals(StillRoute.SINGLE, viewModel.routeStillCapture())
        viewModel.photoFormatMutable.value = PhotoFormat.JPEG
        assertEquals(StillRoute.MOTION_PHOTO, viewModel.routeStillCapture())
    }

    @Test fun routeNightPreviewIsSingle() {
        val viewModel = vm()
        viewModel.nightPreviewActiveMutable.value = true
        assertEquals(StillRoute.SINGLE, viewModel.routeStillCapture())
    }

    @Test fun routeNightWhenDarkAutoAndFlashOff() {
        val viewModel = vm()
        viewModel.lowLightDetectedMutable.value = true
        // nightModeActive is a combined StateFlow: let the main looper fold it.
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
        assertTrue(viewModel.nightCaptureApplies())
        assertEquals(StillRoute.NIGHT, viewModel.routeStillCapture())
        // Flash on defeats the night path (instant flash photo instead).
        viewModel.flashModeMutable.value = FlashMode.ON
        assertFalse(viewModel.nightCaptureApplies())
        assertEquals(StillRoute.MOTION_PHOTO, viewModel.routeStillCapture())
    }

    @Test fun routeSingleWhenBakeOrWrongRatio() {
        val warm = vm()
        warm.warmthMutable.value = 0.4f
        assertEquals(StillRoute.SINGLE, warm.routeStillCapture())
        val wide = vm()
        wide.aspectRatioMutable.value = AspectRatioOption.RATIO_16_9
        assertEquals(StillRoute.SINGLE, wide.routeStillCapture())
        val video = vm()
        video.cameraModeMutable.value = CameraMode.VIDEO
        assertEquals(StillRoute.SINGLE, video.routeStillCapture())
    }

    @Test fun takePhotoGuardsAndTimerCancel() {
        val viewModel = vm()
        viewModel.takePhoto() // no ImageCapture: returns without claiming
        assertFalse(viewModel.isCapturingMutable.value)
        viewModel.timerCountdownMutable.value = 5
        viewModel.takePhoto() // second tap cancels the armed timer
        assertEquals(0, viewModel.timerCountdownMutable.value)
        viewModel.isCapturingMutable.value = true
        viewModel.takePhoto() // already capturing: ignored
        assertTrue(viewModel.isCapturingMutable.value)
    }

    @Test fun capturePhotoClaimsAndRoutesWithMockCapture() {
        val viewModel = vm()
        val capture = Mockito.mock(ImageCapture::class.java)
        Mockito.`when`(capture.captureMode).thenReturn(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
        viewModel.imageCapture = capture
        viewModel.warmthMutable.value = 0.2f // force the single (bake) route
        viewModel.capturePhoto()
        assertTrue(viewModel.isCapturingMutable.value)
        // A second press while in flight is a no-op.
        viewModel.capturePhoto()
    }

    @Test fun burstLifecycleWithMockCapture() {
        val viewModel = vm()
        val capture = Mockito.mock(ImageCapture::class.java)
        Mockito.`when`(capture.captureMode).thenReturn(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
        viewModel.imageCapture = capture
        viewModel.startBurst()
        assertTrue(viewModel.burstActiveMutable.value)
        assertTrue(viewModel.isCapturingMutable.value)
        viewModel.startBurst() // re-entry ignored while active
        viewModel.stopBurst()
        assertFalse(viewModel.burstActiveMutable.value)
    }

    @Test fun motionRingTrimsAndDrains() {
        val viewModel = vm()
        fun frame(ts: Long): Bitmap {
            val b = Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888)
            b.eraseColor(Color.RED)
            viewModel.addMotionFrame(b, ts, 90)
            return b
        }
        val first = frame(0L)
        frame(500_000_000L)
        frame(1_000_000_000L)
        // Default duration is >1s, so nothing aged out yet; drain hands ownership over.
        val drained = viewModel.drainMotionFrames()
        assertEquals(3, drained.size)
        assertEquals(90, drained[0].rotationDegrees)
        assertTrue(viewModel.drainMotionFrames().isEmpty())
        drained.forEach { it.bitmap.recycle() }
        // Frames older than the window are recycled on append.
        val old = frame(0L)
        frame(60_000_000_000L)
        assertTrue(old.isRecycled)
        viewModel.clearMotionFrames()
        assertTrue(viewModel.drainMotionFrames().isEmpty())
    }
}
