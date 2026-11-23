package com.vayunmathur.camera.platform
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import kotlin.test.*
/** ZSL gating: zslCompatible flow unchanged; stillCaptureIsZsl reads Camera2 ring. */
@RunWith(AndroidJUnit4::class)
class ZslGatingTest {

    private class FakeStillOutput : com.vayunmathur.camera.platform.camera2.StillOutput {
        override val surface: android.view.Surface = org.mockito.Mockito.mock(android.view.Surface::class.java)
        var jpegListener: com.vayunmathur.camera.platform.camera2.JpegImageListener? = null
        override fun setListener(l: com.vayunmathur.camera.platform.camera2.JpegImageListener?) { jpegListener = l }
        override fun close() {}
    }
    private class FakeRingOutput : com.vayunmathur.camera.platform.camera2.RingOutput {
        override val surface: android.view.Surface = org.mockito.Mockito.mock(android.view.Surface::class.java)
        var ringListener: com.vayunmathur.camera.platform.camera2.RingFrameListener? = null
        override fun setListener(l: com.vayunmathur.camera.platform.camera2.RingFrameListener?) { ringListener = l }
        override fun close() {}
    }
    private class FakeHandle : com.vayunmathur.camera.platform.camera2.CaptureSessionHandle {
        val stillRequests = mutableListOf<com.vayunmathur.camera.platform.camera2.ResolvedStillParams>()
        var stillCallback: com.vayunmathur.camera.platform.camera2.StillResultCallback? = null
        override fun setRepeatingPreview(p: com.vayunmathur.camera.platform.camera2.ResolvedRequestParams, t: android.view.Surface) {}
        override fun setRepeatingStreaming(p: com.vayunmathur.camera.platform.camera2.ResolvedRequestParams, ts: List<android.view.Surface>, cb: com.vayunmathur.camera.platform.camera2.PreviewResultCallback?) {}
        override fun captureStill(p: com.vayunmathur.camera.platform.camera2.ResolvedStillParams, t: android.view.Surface, cb: com.vayunmathur.camera.platform.camera2.StillResultCallback) { stillRequests += p; stillCallback = cb }
        override fun close() {}
    }
    private class FakeDevice(val handle: FakeHandle) : com.vayunmathur.camera.platform.camera2.DeviceHandle {
        override val cameraId = "0"
        override fun createCaptureSession(s: List<android.view.Surface>, cb: com.vayunmathur.camera.platform.camera2.CaptureSessionCallback) = cb.onConfigured(handle)
        override fun close() {}
    }
    private class FakeDriver(val device: FakeDevice, val still: FakeStillOutput?, val ring: FakeRingOutput? = null) : com.vayunmathur.camera.platform.camera2.Camera2Driver {
        override fun openCamera(id: String, cb: com.vayunmathur.camera.platform.camera2.DeviceCallback) = cb.onOpened(device)
        override fun createStillOutput(size: com.vayunmathur.camera.platform.camera2.FrameSize) = still
        override fun createRingOutput(size: com.vayunmathur.camera.platform.camera2.FrameSize) = ring
    }
    private fun snapshot(ring: Boolean) = com.vayunmathur.camera.platform.camera2.CharacteristicsFacade.fromFakeMap(
        cameraId = "0", lensFacingRaw = android.hardware.camera2.CameraMetadata.LENS_FACING_BACK,
        zoomRatioRange = android.util.Range(1f, 10f), hasFlashUnit = true,
        exposureCompensationEvRange = android.util.Range(-4f, 4f),
        sizesByFormat = buildMap {
            put(android.graphics.ImageFormat.PRIVATE, listOf(android.util.Size(1280, 720)))
            put(android.graphics.ImageFormat.JPEG, listOf(android.util.Size(4000, 3000)))
            if (ring) put(android.graphics.ImageFormat.YUV_420_888, listOf(android.util.Size(1920, 1080)))
        }, activeArrayWidth = 4000, activeArrayHeight = 3000, sensorOrientationDegrees = 90,
        exposureCompensationStepEv = 1f / 6f, maxAfRegions = 1, maxAeRegions = 1,
    ).snapshot
    private fun sessionWithRing(executor: com.vayunmathur.camera.platform.camera2.Camera2Executor, ring: Boolean): com.vayunmathur.camera.platform.camera2.Camera2Session {
        val handle = FakeHandle(); val ringOut = if (ring) FakeRingOutput() else null
        val s = com.vayunmathur.camera.platform.camera2.Camera2Session(
            FakeDriver(FakeDevice(handle), FakeStillOutput(), ringOut), executor, snapshot(ring),
            ringConfig = if (ring) com.vayunmathur.camera.platform.camera2.ReprocessRingConfig(enabled = true) else com.vayunmathur.camera.platform.camera2.ReprocessRingConfig.DISABLED,
        )
        if (ring) s.ringOutput = ringOut
        return s
    }
    private fun com.vayunmathur.camera.platform.camera2.Camera2Executor.drain() {
        val latch = java.util.concurrent.CountDownLatch(1); execute { latch.countDown() }
        kotlin.test.assertTrue(latch.await(5, java.util.concurrent.TimeUnit.SECONDS))
    }

    private fun vm() = CameraViewModel(ApplicationProvider.getApplicationContext())
    private fun idle() = shadowOf(Looper.getMainLooper()).idle()
    private fun CameraViewModel.engageNight() { repeat(CameraViewModel.NIGHT_DEBOUNCE_FRAMES) { onLuminance(5f) }; idle() }
    @Test fun manualExposureAndNightTurnZslOff() {
        val v=vm(); idle(); assertTrue(v.zslCompatible.value)
        v.exposureTimeIndexMutable.value=3; idle(); assertFalse(v.zslCompatible.value)
        v.exposureTimeIndexMutable.value=0; v.manualIsoIndexMutable.value=2; idle(); assertFalse(v.zslCompatible.value)
        v.manualIsoIndexMutable.value=0; idle(); assertTrue(v.zslCompatible.value); v.engageNight(); assertFalse(v.zslCompatible.value)
    }
    @Test fun valueHeldWhileCapturing() {
        val v=vm(); idle(); v.isCapturingMutable.value=true; idle(); v.engageNight(); assertTrue(v.zslCompatible.value)
        v.isCapturingMutable.value=false; idle(); assertFalse(v.zslCompatible.value)
    }
    @Test fun ringSessionNeverTakesNightPath() {
        val ex=com.vayunmathur.camera.platform.camera2.Camera2Executor("ZslGate"); try {
            val v=vm(); v.engageNight(); assertTrue(v.nightCaptureApplies())
            v.registerCamera2Session(sessionWithRing(ex,true)); assertTrue(v.stillCaptureIsZsl()); assertFalse(v.nightCaptureApplies()); assertTrue(v.routeStillCapture()!=StillRoute.NIGHT)
            val v2=vm(); v2.registerCamera2Session(sessionWithRing(ex,false)); assertFalse(v2.stillCaptureIsZsl())
        } finally { ex.shutdown() }
    }
}
