package com.vayunmathur.camera.platform

import android.graphics.ImageFormat
import android.util.Range
import android.util.Size
import android.view.Surface
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.camera.platform.camera2.Camera2Driver
import com.vayunmathur.camera.platform.camera2.Camera2Executor
import com.vayunmathur.camera.platform.camera2.Camera2Session
import com.vayunmathur.camera.platform.camera2.CaptureSessionHandle
import com.vayunmathur.camera.platform.camera2.CharacteristicsFacade
import com.vayunmathur.camera.platform.camera2.DeviceCallback
import com.vayunmathur.camera.platform.camera2.DeviceHandle
import com.vayunmathur.camera.platform.camera2.CaptureSessionCallback
import com.vayunmathur.camera.platform.camera2.FrameSize
import com.vayunmathur.camera.platform.camera2.LensCharacteristicsSnapshot
import com.vayunmathur.camera.platform.camera2.ResolvedRequestParams
import com.vayunmathur.camera.platform.camera2.StillOutput
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Camera2 contract: controls routed to a fake Camera2Session. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CameraCovW3MockSessionsTest {

    private fun vm() = CameraViewModel(ApplicationProvider.getApplicationContext())

    private class FakeHandle : CaptureSessionHandle {
        val repeating = mutableListOf<ResolvedRequestParams>()
        override fun setRepeatingPreview(params: ResolvedRequestParams, target: Surface) { repeating += params }
        override fun captureStill(params: com.vayunmathur.camera.platform.camera2.ResolvedStillParams, stillTarget: Surface, callback: com.vayunmathur.camera.platform.camera2.StillResultCallback) { callback.onCompleted(com.vayunmathur.camera.platform.camera2.RawStillMetadata()) }
        override fun close() {}
    }
    private class FakeDevice : DeviceHandle {
        override val cameraId: String = "0"
        override fun createCaptureSession(surfaces: List<Surface>, callback: CaptureSessionCallback) { callback.onConfigured(FakeHandle()) }
        override fun close() {}
    }
    private class FakeDriver : Camera2Driver {
        override fun openCamera(cameraId: String, callback: DeviceCallback) { callback.onOpened(FakeDevice()) }
        override fun createStillOutput(size: FrameSize): StillOutput? = null
    }
    internal fun snapshot(
        zoom: Range<Float>? = Range(0.5f, 8f),
        flash: Boolean = true,
        sensitivity: Range<Int>? = Range(50, 6400),
        ev: Range<Float>? = Range(-4f, 4f),
    ): LensCharacteristicsSnapshot = CharacteristicsFacade.fromFakeMap(
        cameraId = "0", zoomRatioRange = zoom, hasFlashUnit = flash,
        sensitivityRange = sensitivity, exposureCompensationEvRange = ev,
        exposureTimeRangeNs = Range(1_000_000L, 1_000_000_000L),
        sizesByFormat = mapOf(ImageFormat.PRIVATE to listOf(Size(1920, 1080))),
        activeArrayWidth = 4000, activeArrayHeight = 3000,
        exposureCompensationStepEv = 1f / 6f, maxAfRegions = 1, maxAeRegions = 1,
    ).snapshot

    internal fun fakeSession(executor: Camera2Executor, snap: LensCharacteristicsSnapshot = snapshot()): Camera2Session =
        Camera2Session(FakeDriver(), executor, snap)

    internal fun Camera2Executor.drain() {
        val latch = CountDownLatch(1); execute { latch.countDown() }
        assertTrue(latch.await(5, TimeUnit.SECONDS))
    }
    internal fun dirtyTeardownState(v: CameraViewModel) {
        v.isRecordingMutable.value = true; v.recordingPausedMutable.value = true
        v.recordingDurationSecMutable.value = 42; v.timerCountdownMutable.value = 3
        v.isCapturingMutable.value = true; v.photoSessionActiveMutable.value = true
        v.analysisStreamActiveMutable.value = true; v.nightPreviewActiveMutable.value = true
        v.highSpeedActiveMutable.value = true; v.videoSessionActiveMutable.value = true
        v.videoSnapshotSupportedMutable.value = true; v.focusLockedMutable.value = true
    }
    internal fun assertTeardownCleared(v: CameraViewModel) {
        assertFalse(v.isRecording.value); assertFalse(v.recordingPaused.value)
        assertEquals(0L, v.recordingDurationSec.value); assertEquals(0, v.timerCountdown.value)
        assertFalse(v.isCapturing.value); assertFalse(v.photoSessionActive.value)
        assertFalse(v.analysisStreamActive.value); assertFalse(v.nightPreviewActive.value)
        assertFalse(v.highSpeedActive.value); assertFalse(v.videoSessionActive.value)
        assertFalse(v.videoSnapshotSupported.value); assertFalse(v.focusLocked.value)
    }

    @Test fun controlsRouteToFakeSession() {
        val ex = Camera2Executor("mock-controls"); val s = fakeSession(ex); val v = vm(); v.activeCamera2Session = s
        try { v.setZoomRatio(2f); v.enableTorch(true); ex.drain()
            assertEquals(2f, s.controlState.zoomRatio); assertTrue(s.controlState.torchOn)
            v.enableTorch(false); ex.drain(); assertFalse(s.controlState.torchOn)
        } finally { ex.shutdown() }
    }
    @Test fun noSessionControlsAreNoOps() { val v = vm(); v.enableTorch(true); v.setZoomRatio(3f); assertEquals(3f, v.zoomRatio.value) }

}
