package com.vayunmathur.camera.platform

import android.graphics.ImageFormat
import android.util.Range
import android.util.Size
import android.view.Surface
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.camera.platform.camera2.Camera2Driver
import com.vayunmathur.camera.platform.camera2.Camera2Executor
import com.vayunmathur.camera.platform.camera2.Camera2Session
import com.vayunmathur.camera.platform.camera2.CaptureSessionCallback
import com.vayunmathur.camera.platform.camera2.CaptureSessionHandle
import com.vayunmathur.camera.platform.camera2.CharacteristicsFacade
import com.vayunmathur.camera.platform.camera2.DeviceCallback
import com.vayunmathur.camera.platform.camera2.DeviceHandle
import com.vayunmathur.camera.platform.camera2.FrameSize
import com.vayunmathur.camera.platform.camera2.LensCharacteristicsSnapshot
import com.vayunmathur.camera.platform.camera2.ResolvedRequestParams
import com.vayunmathur.camera.platform.camera2.StillOutput
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Panorama/Photosphere zoom lock: the session must sit at 1x no matter
 * what zoom the user had stored from Photo/other modes, and the stored
 * ratio must survive the mode so leaving restores it untouched.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PanoramaZoomLockTest {

    private fun vm() = CameraViewModel(ApplicationProvider.getApplicationContext())

    private class FakeHandle : CaptureSessionHandle {
        override fun setRepeatingPreview(params: ResolvedRequestParams, target: Surface) {}
        override fun captureStill(
            params: com.vayunmathur.camera.platform.camera2.ResolvedStillParams,
            stillTarget: Surface,
            callback: com.vayunmathur.camera.platform.camera2.StillResultCallback,
        ) {
            callback.onCompleted(com.vayunmathur.camera.platform.camera2.RawStillMetadata())
        }
        override fun close() {}
    }

    private class FakeDevice : DeviceHandle {
        override val cameraId: String = "0"
        override fun createCaptureSession(surfaces: List<Surface>, callback: CaptureSessionCallback) {
            callback.onConfigured(FakeHandle())
        }
        override fun close() {}
    }

    private class FakeDriver : Camera2Driver {
        override fun openCamera(cameraId: String, callback: DeviceCallback) {
            callback.onOpened(FakeDevice())
        }
        override fun createStillOutput(size: FrameSize): StillOutput? = null
    }

    private fun snapshot(): LensCharacteristicsSnapshot = CharacteristicsFacade.fromFakeMap(
        cameraId = "0", zoomRatioRange = Range(0.5f, 8f), hasFlashUnit = true,
        sensitivityRange = Range(50, 6400), exposureCompensationEvRange = Range(-4f, 4f),
        exposureTimeRangeNs = Range(1_000_000L, 1_000_000_000L),
        sizesByFormat = mapOf(ImageFormat.PRIVATE to listOf(Size(1920, 1080))),
        activeArrayWidth = 4000, activeArrayHeight = 3000,
        exposureCompensationStepEv = 1f / 6f, maxAfRegions = 1, maxAeRegions = 1,
    ).snapshot

    private fun Camera2Executor.drain() {
        val latch = CountDownLatch(1)
        execute { latch.countDown() }
        assertTrue(latch.await(5, TimeUnit.SECONDS))
    }

    @Test
    fun lockedModesAreExactlyPanoramaAndPhotosphere() {
        assertTrue(CameraMode.PANORAMA.isZoomLockedTo1x())
        assertTrue(CameraMode.PHOTOSPHERE.isZoomLockedTo1x())
        for (mode in CameraMode.entries - CameraMode.PANORAMA - CameraMode.PHOTOSPHERE) {
            assertFalse(mode.isZoomLockedTo1x(), "mode $mode must not be zoom-locked")
            assertEquals(3f, effectiveZoomRatioForMode(mode, 3f))
        }
        assertEquals(1f, effectiveZoomRatioForMode(CameraMode.PANORAMA, 3f))
        assertEquals(1f, effectiveZoomRatioForMode(CameraMode.PHOTOSPHERE, 0.5f))
    }

    @Test
    fun bindApplies1xDespiteStoredZoomAndKeepsStoredZoom() {
        for (mode in listOf(CameraMode.PANORAMA, CameraMode.PHOTOSPHERE)) {
            val executor = Camera2Executor("pano-lock-$mode")
            val session = Camera2Session(FakeDriver(), executor, snapshot())
            val viewModel = vm()
            try {
                viewModel.cameraModeMutable.value = mode
                viewModel.zoomRatioMutable.value = 3f
                // Session left over from Photo at the stored zoom.
                session.setZoomRatio(3f)
                executor.drain()
                assertEquals(3f, session.controlState.zoomRatio)

                viewModel.registerCamera2Session(session)
                executor.drain()
                assertEquals(1f, session.controlState.zoomRatio, "bind in $mode")
                assertEquals(3f, viewModel.zoomRatio.value, "stored zoom must survive $mode")

                // Same guarantee through the capability-refresh bind path.
                session.setZoomRatio(3f)
                executor.drain()
                viewModel.restoreZoom(0.5f, 8f)
                executor.drain()
                assertEquals(1f, session.controlState.zoomRatio, "restoreZoom in $mode")
                assertEquals(3f, viewModel.zoomRatio.value)
            } finally {
                executor.shutdown()
            }
        }
    }

    @Test
    fun zoomChangesWhileLockedAreIgnoredAndStoredZoomSurvives() {
        val executor = Camera2Executor("pano-lock-ignore")
        val session = Camera2Session(FakeDriver(), executor, snapshot())
        val viewModel = vm()
        try {
            viewModel.activeCamera2Session = session
            viewModel.cameraModeMutable.value = CameraMode.PANORAMA
            viewModel.zoomRatioMutable.value = 2.5f

            viewModel.setZoomRatio(5f)
            viewModel.onViewfinderZoomRatio(5f)
            executor.drain()

            assertEquals(1f, session.controlState.zoomRatio)
            assertEquals(2.5f, viewModel.zoomRatio.value)
        } finally {
            executor.shutdown()
        }
    }

    @Test
    fun enteringLockedModeApplies1xAndLeavingRestoresStoredZoom() {
        val executor = Camera2Executor("pano-lock-switch")
        val session = Camera2Session(FakeDriver(), executor, snapshot())
        val viewModel = vm()
        try {
            viewModel.activeCamera2Session = session
            viewModel.zoomRatioMutable.value = 2.5f
            session.setZoomRatio(2.5f)
            executor.drain()

            viewModel.switchCameraMode(CameraMode.PHOTOSPHERE)
            executor.drain()
            assertEquals(1f, session.controlState.zoomRatio)
            assertEquals(2.5f, viewModel.zoomRatio.value)

            viewModel.switchCameraMode(CameraMode.PHOTO)
            executor.drain()
            assertEquals(2.5f, session.controlState.zoomRatio)
            assertEquals(2.5f, viewModel.zoomRatio.value)
        } finally {
            executor.shutdown()
        }
    }

    @Test
    fun otherModesStillApplyStoredZoomAtBind() {
        val executor = Camera2Executor("zoom-unlocked")
        val session = Camera2Session(FakeDriver(), executor, snapshot())
        val viewModel = vm()
        try {
            viewModel.activeCamera2Session = session
            viewModel.cameraModeMutable.value = CameraMode.PHOTO
            viewModel.zoomRatioMutable.value = 3f

            viewModel.restoreZoom(0.5f, 8f)
            executor.drain()
            assertEquals(3f, session.controlState.zoomRatio)

            viewModel.setZoomRatio(99f)
            executor.drain()
            assertEquals(8f, session.controlState.zoomRatio)
            assertEquals(8f, viewModel.zoomRatio.value)
        } finally {
            executor.shutdown()
        }
    }
}
