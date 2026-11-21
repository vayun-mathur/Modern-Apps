package com.vayunmathur.camera.platform

import android.graphics.Bitmap
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.test.core.app.ApplicationProvider
import com.google.common.util.concurrent.ListenableFuture
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.runner.RunWith
import org.mockito.Mockito
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Night hysteresis, exposure derivation, burst driver sequencing. */
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class NightLogicExtraTest {

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private fun vm() = CameraViewModel(context.applicationContext as android.app.Application)

    @Test fun luminanceHysteresisEngagesAndDisengages() {
        val viewModel = vm()
        val frames = CameraViewModel.NIGHT_DEBOUNCE_FRAMES
        // Dark frames below the engage threshold, debounced.
        repeat(frames - 1) { viewModel.onLuminance(10f) }
        assertFalse(viewModel.lowLightDetectedMutable.value)
        viewModel.onLuminance(10f)
        assertTrue(viewModel.lowLightDetectedMutable.value)
        // A bright-ish frame below disengage does not flap the state.
        viewModel.onLuminance(50f)
        assertTrue(viewModel.lowLightDetectedMutable.value)
        // Sustained bright frames disengage and clear the override.
        repeat(frames) { viewModel.onLuminance(120f) }
        assertFalse(viewModel.lowLightDetectedMutable.value)
        assertFalse(viewModel.nightModeOverriddenOffMutable.value)
        // Mid-range frames in the bright arm reset the dark counter.
        repeat(frames - 1) { viewModel.onLuminance(10f) }
        viewModel.onLuminance(45f)
        repeat(frames - 1) { viewModel.onLuminance(10f) }
        assertFalse(viewModel.lowLightDetectedMutable.value)
    }

    @Test fun nightExposureFallsBackWithoutCamera() {
        val viewModel = vm()
        val exposure = viewModel.computeNightExposure(123_456L)
        assertEquals(123_456L, exposure.nanos)
        assertEquals(null, exposure.iso)
        val defaulted = viewModel.computeNightExposure()
        assertEquals(CameraViewModel.NIGHT_TARGET_EXPOSURE_NANOS, defaulted.nanos)
    }

    @Test fun lockNightExposureFallsThroughOnFailure() {
        val viewModel = vm()
        val control = Mockito.mock(androidx.camera.camera2.interop.Camera2CameraControl::class.java)
        Mockito.`when`(
            control.setCaptureRequestOptions(
                Mockito.any(androidx.camera.camera2.interop.CaptureRequestOptions::class.java)
                    ?: androidx.camera.camera2.interop.CaptureRequestOptions.Builder().build(),
            ),
        ).thenThrow(IllegalStateException("session gone"))
        var locked = 0
        viewModel.lockNightExposure(control, NightExposure(1_000_000L, 800)) { locked++ }
        assertEquals(1, locked)
        // With ISO null the ISO option is skipped; failure still falls through.
        viewModel.lockNightExposure(control, NightExposure(2_000_000L, null)) { locked++ }
        assertEquals(2, locked)
    }

    @Test fun lockNightExposureSuccessRegistersListener() {
        val viewModel = vm()
        val control = Mockito.mock(androidx.camera.camera2.interop.Camera2CameraControl::class.java)
        @Suppress("UNCHECKED_CAST")
        val future = Mockito.mock(ListenableFuture::class.java) as ListenableFuture<Void?>
        Mockito.`when`(
            control.setCaptureRequestOptions(
                Mockito.any(androidx.camera.camera2.interop.CaptureRequestOptions::class.java)
                    ?: androidx.camera.camera2.interop.CaptureRequestOptions.Builder().build(),
            ),
        ).thenReturn(future)
        viewModel.lockNightExposure(control, NightExposure(1_000_000L, null)) { }
        Mockito.verify(future).addListener(Mockito.any(Runnable::class.java) ?: Runnable { }, Mockito.any(java.util.concurrent.Executor::class.java) ?: java.util.concurrent.Executor { it.run() })
    }

    @Test fun burstDriverFinishesImmediatelyWithoutCapture() {
        val viewModel = vm()
        var done: List<Bitmap>? = null
        val cls = Class.forName("com.vayunmathur.camera.platform.NightBurstDriver")
        val ctor = cls.getDeclaredConstructor(
            CameraViewModel::class.java,
            androidx.camera.camera2.interop.Camera2CameraControl::class.java,
            Boolean::class.javaPrimitiveType,
            NightExposure::class.java,
            Function1::class.java,
            Function1::class.java,
        ).apply { isAccessible = true }
        val onFrameCaptured: (Int) -> Unit = { }
        val onDone: (List<Bitmap>) -> Unit = { frames -> done = frames }
        val driver = ctor.newInstance(viewModel, null, false, NightExposure(1_000L, null), onFrameCaptured, onDone)
        cls.getDeclaredMethod("start").apply { isAccessible = true }.invoke(driver)
        // No ImageCapture bound: finishes at once with an empty burst, once only.
        assertEquals(emptyList(), done)
    }

    @Test fun burstDriverRetriesOnErrorUntilDone() {
        val viewModel = vm()
        val capture = Mockito.mock(ImageCapture::class.java)
        val callbacks = mutableListOf<ImageCapture.OnImageCapturedCallback>()
        Mockito.doAnswer { inv ->
            callbacks.add(inv.getArgument(1))
            null
        }.`when`(capture).takePicture(
            Mockito.any(java.util.concurrent.Executor::class.java),
            Mockito.any(ImageCapture.OnImageCapturedCallback::class.java),
        )
        viewModel.imageCapture = capture
        var done: List<Bitmap>? = null
        val cls = Class.forName("com.vayunmathur.camera.platform.NightBurstDriver")
        val ctor = cls.getDeclaredConstructor(
            CameraViewModel::class.java,
            androidx.camera.camera2.interop.Camera2CameraControl::class.java,
            Boolean::class.javaPrimitiveType,
            NightExposure::class.java,
            Function1::class.java,
            Function1::class.java,
        ).apply { isAccessible = true }
        val onFrameCaptured: (Int) -> Unit = { }
        val onDone: (List<Bitmap>) -> Unit = { frames -> done = frames }
        val driver = ctor.newInstance(viewModel, null, true, NightExposure(1_000L, 400), onFrameCaptured, onDone)
        cls.getDeclaredMethod("start").apply { isAccessible = true }.invoke(driver)
        // Every error re-queues the next frame; the driver never gives up early
        // and finishes (empty) after the burst count is exhausted by errors.
        var guard = 0
        while (done == null && guard < 20) {
            callbacks.last().onError(ImageCaptureException(ImageCapture.ERROR_UNKNOWN, "fail", null))
            guard++
        }
        assertEquals(emptyList(), done)
        assertTrue(callbacks.size >= 2)
    }

    @Test fun exposureRemainingFormatting() {
        val m = Class.forName("com.vayunmathur.camera.platform.CameraNightCaptureKt")
            .getDeclaredMethod("formatExposureRemaining", Long::class.javaPrimitiveType)
            .apply { isAccessible = true }
        fun f(ms: Long) = m.invoke(null, ms) as String
        assertEquals("0.0s", f(0L))
        assertEquals("1.5s", f(1500L))
        assertEquals("12.3s", f(12349L))
        val viewModel = vm()
        viewModel.stopLongExposureCountdown()
        assertEquals(0f, viewModel.longExposureProgressMutable.value)
        assertEquals("", viewModel.longExposureRemainingMutable.value)
    }
}
