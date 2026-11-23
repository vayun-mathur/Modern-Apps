package com.vayunmathur.camera.platform
import android.hardware.camera2.*
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.*
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito
import kotlin.test.*
/** AE gate: stillCaptureIsZsl now reads Camera2 session ring output. */
@RunWith(AndroidJUnit4::class)
class AeConvergenceGateTest {

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
    @Test fun convergedAndLockedAreReady() { assertTrue(aeStateReadyForCapture(CameraMetadata.CONTROL_AE_STATE_CONVERGED)); assertTrue(aeStateReadyForCapture(CameraMetadata.CONTROL_AE_STATE_LOCKED)) }
    @Test fun unsettledStatesAreNotReady() { assertFalse(aeStateReadyForCapture(CameraMetadata.CONTROL_AE_STATE_INACTIVE)); assertFalse(aeStateReadyForCapture(CameraMetadata.CONTROL_AE_STATE_SEARCHING)); assertFalse(aeStateReadyForCapture(CameraMetadata.CONTROL_AE_STATE_FLASH_REQUIRED)); assertFalse(aeStateReadyForCapture(CameraMetadata.CONTROL_AE_STATE_PRECAPTURE)) }
    @Test fun unreportedStateDoesNotGate() { assertTrue(aeStateReadyForCapture(null)) }
    @Test fun snapshotCallbackRecordsAeState() {
        val v=vm(); val result=Mockito.mock(TotalCaptureResult::class.java)
        Mockito.`when`(result.get(CaptureResult.CONTROL_AE_STATE)).thenReturn(CameraMetadata.CONTROL_AE_STATE_SEARCHING)
        Mockito.`when`(result.get(CaptureResult.SENSOR_SENSITIVITY)).thenReturn(800)
        Mockito.`when`(result.get(CaptureResult.SENSOR_EXPOSURE_TIME)).thenReturn(33_333_333L)
        v.aeSnapshotCallback.onCaptureCompleted(Mockito.mock(android.hardware.camera2.CameraCaptureSession::class.java), Mockito.mock(android.hardware.camera2.CaptureRequest::class.java), result)
        assertEquals(CameraMetadata.CONTROL_AE_STATE_SEARCHING, v.lastAeState); assertFalse(v.isAeConvergedForCapture())
    }
    @Test fun awaitReturnsImmediatelyWhenConvergedOrNoRing() = runBlocking {
        val ex=com.vayunmathur.camera.platform.camera2.Camera2Executor("AeGate1"); try {
            val v=vm(); v.registerCamera2Session(sessionWithRing(ex,true)); v.lastAeState=CameraMetadata.CONTROL_AE_STATE_CONVERGED; assertTrue(v.stillCaptureIsZsl()); assertTrue(v.awaitAeConvergence(5_000L))
            val v2=vm(); v2.lastAeState=CameraMetadata.CONTROL_AE_STATE_SEARCHING; assertFalse(v2.stillCaptureIsZsl()); assertTrue(v2.awaitAeConvergence(5_000L))
        } finally { ex.shutdown() }
    }
    @Test fun awaitWaitsAndTimesOutWithRing() = runBlocking {
        val ex=com.vayunmathur.camera.platform.camera2.Camera2Executor("AeGate2"); try {
            val v=vm(); v.registerCamera2Session(sessionWithRing(ex,true)); v.lastAeState=CameraMetadata.CONTROL_AE_STATE_SEARCHING
            val waiting=async { v.awaitAeConvergence(5_000L) }; delay(150L); assertFalse(waiting.isCompleted); v.lastAeState=CameraMetadata.CONTROL_AE_STATE_CONVERGED; assertTrue(waiting.await())
            v.lastAeState=CameraMetadata.CONTROL_AE_STATE_SEARCHING; val t=System.currentTimeMillis(); assertFalse(v.awaitAeConvergence(200L)); assertTrue(System.currentTimeMillis()-t>=200L)
        } finally { ex.shutdown() }
    }
}
