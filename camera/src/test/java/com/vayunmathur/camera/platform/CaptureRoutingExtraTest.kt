package com.vayunmathur.camera.platform
import android.graphics.*
import android.hardware.camera2.CameraMetadata
import androidx.test.core.app.ApplicationProvider
import kotlin.test.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.*
/** Routing: AE gate, routeStillCapture, capturePhoto/burst guards (Camera2 session based). */
@GraphicsMode(GraphicsMode.Mode.NATIVE) @RunWith(RobolectricTestRunner::class) @Config(sdk=[34])
class CaptureRoutingExtraTest {

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

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private fun vm() = CameraViewModel(context.applicationContext as android.app.Application)
    @Test fun aeStateGate() {
        assertTrue(aeStateReadyForCapture(null)); assertTrue(aeStateReadyForCapture(CameraMetadata.CONTROL_AE_STATE_CONVERGED)); assertFalse(aeStateReadyForCapture(CameraMetadata.CONTROL_AE_STATE_SEARCHING))
        val v=vm(); v.lastAeState=CameraMetadata.CONTROL_AE_STATE_SEARCHING; assertFalse(v.isAeConvergedForCapture()); v.lastAeState=null; assertTrue(v.isAeConvergedForCapture())
    }
    @Test fun routeDefaultsToMotionPhotoWhenEligible() { assertEquals(StillRoute.MOTION_PHOTO, vm().routeStillCapture()) }
    @Test fun routeAvifSelectsSingle() { if(!StitchNative.isAvailable) return; val v=vm(); v.photoFormatMutable.value=PhotoFormat.AVIF; assertEquals(StillRoute.SINGLE,v.routeStillCapture()) }
    @Test fun routeNightPreviewIsSingle() { val v=vm(); v.nightPreviewActiveMutable.value=true; assertEquals(StillRoute.SINGLE,v.routeStillCapture()) }
    @Test fun routeNightWhenDark() {
        val v=vm(); v.lowLightDetectedMutable.value=true; org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
        assertTrue(v.nightCaptureApplies()); assertEquals(StillRoute.NIGHT,v.routeStillCapture())
        v.flashModeMutable.value=FlashMode.ON; assertFalse(v.nightCaptureApplies())
    }
    @Test fun routeSingleWhenBakeOrWrongRatio() {
        val w=vm(); w.warmthMutable.value=0.4f; assertEquals(StillRoute.SINGLE,w.routeStillCapture())
        val wide=vm(); wide.aspectRatioMutable.value=AspectRatioOption.RATIO_16_9; assertEquals(StillRoute.SINGLE,wide.routeStillCapture())
    }
    @Test fun takePhotoGuardsAndTimerCancel() {
        val v=vm(); v.takePhoto(); assertFalse(v.isCapturingMutable.value)
        v.timerCountdownMutable.value=5; v.takePhoto(); assertEquals(0,v.timerCountdownMutable.value)
        v.isCapturingMutable.value=true; v.takePhoto(); assertTrue(v.isCapturingMutable.value)
    }
    @Test fun capturePhotoWithoutSessionIsNoOpAndBurstGuard() {
        val v=vm(); v.capturePhoto(); assertFalse(v.isCapturingMutable.value); v.startBurst(); assertFalse(v.burstActiveMutable.value)
        val ex=com.vayunmathur.camera.platform.camera2.Camera2Executor("RouteBurst"); try {
            val v2=vm(); v2.registerCamera2Session(sessionWithRing(ex,false)); v2.startBurst(); assertTrue(v2.burstActiveMutable.value); v2.stopBurst(); assertFalse(v2.burstActiveMutable.value)
        } finally { ex.shutdown() }
    }
    @Test fun motionRingTrimsAndDrains() {
        val v=vm(); fun frame(ts:Long):Bitmap{val b=Bitmap.createBitmap(4,4,Bitmap.Config.ARGB_8888); b.eraseColor(Color.RED); v.addMotionFrame(b,ts,90); return b}
        frame(0L); frame(500_000_000L); frame(1_000_000_000L); val d=v.drainMotionFrames(); assertEquals(3,d.size); assertEquals(90,d[0].rotationDegrees); d.forEach{it.bitmap.recycle()}
        val old=frame(0L); frame(60_000_000_000L); assertTrue(old.isRecycled); v.clearMotionFrames()
    }
}
