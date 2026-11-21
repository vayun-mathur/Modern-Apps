package com.vayunmathur.camera.platform

import androidx.camera.core.Camera
import androidx.camera.core.CameraInfo
import androidx.camera.core.ExposureState
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.SurfaceOrientedMeteringPointFactory
import androidx.camera.core.ZoomState
import androidx.lifecycle.MutableLiveData
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

/** Camera controls (zoom/focus/torch/exposure) and recording toggles. */
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ControlsRecordingExtraTest {

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private fun vm() = CameraViewModel(context.applicationContext as android.app.Application)

    private fun fakeCamera(): Camera {
        val info = Mockito.mock(CameraInfo::class.java)
        val zoom = object : ZoomState {
            override fun getZoomRatio() = 1f
            override fun getMinZoomRatio() = 0.5f
            override fun getMaxZoomRatio() = 8f
            override fun getLinearZoom() = 0f
        }
        Mockito.`when`(info.zoomState).thenReturn(MutableLiveData<ZoomState>(zoom))
        Mockito.`when`(info.cameraState).thenReturn(MutableLiveData())
        Mockito.`when`(info.torchState).thenReturn(MutableLiveData<Int>(0))
        Mockito.`when`(info.hasFlashUnit()).thenReturn(true)
        val exposure = Mockito.mock(ExposureState::class.java)
        Mockito.`when`(exposure.exposureCompensationRange).thenReturn(android.util.Range(-3, 3))
        Mockito.`when`(exposure.exposureCompensationIndex).thenReturn(0)
        Mockito.`when`(exposure.isExposureCompensationSupported).thenReturn(true)
        Mockito.`when`(info.exposureState).thenReturn(exposure)
        val camera = Mockito.mock(Camera::class.java)
        Mockito.`when`(camera.cameraInfo).thenReturn(info)
        val control = Mockito.mock(
            androidx.camera.core.CameraControl::class.java,
            Mockito.withSettings().extraInterfaces(androidx.camera.core.impl.CameraControlInternal::class.java),
        )
        Mockito.`when`(camera.cameraControl).thenReturn(control)
        return camera
    }

    @Test fun zoomLevelsAndClamping() {
        val viewModel = vm()
        viewModel.updateZoomLevels(0.5f, 10f)
        assertEquals(
            listOf(".5" to 0.5f, "1x" to 1f, "2x" to 2f, "5x" to 5f),
            viewModel.availableZoomLevelsMutable.value,
        )
        viewModel.updateZoomLevels(1f, 1f)
        assertEquals(listOf("1x" to 1f), viewModel.availableZoomLevelsMutable.value)
        viewModel.boundCamera = fakeCamera()
        viewModel.setZoomRatio(99f)
        assertEquals(8f, viewModel.zoomRatioMutable.value) // clamped to lens max
        viewModel.setZoomRatio(0.1f)
        assertEquals(0.5f, viewModel.zoomRatioMutable.value)
        viewModel.onViewfinderZoomRatio(2f)
        assertEquals(2f, viewModel.zoomRatioMutable.value)
        viewModel.zoomRatioMutable.value = 20f
        viewModel.restoreZoom(0.5f, 8f)
        assertEquals(8f, viewModel.zoomRatioMutable.value)
    }

    @Test fun videoModeZoomAppliesToBoundCameraControl() {
        val viewModel = vm()
        viewModel.switchCameraMode(CameraMode.VIDEO)
        viewModel.boundCamera = fakeCamera()
        // setZoomRatio drives the bound camera's CameraControl, which CameraX
        // applies to every bound use case — the video Preview and the
        // VideoCapture alike — so it takes effect in preview and recording.
        viewModel.setZoomRatio(2f)
        assertEquals(2f, viewModel.zoomRatioMutable.value)
        Mockito.verify(viewModel.boundCamera!!.cameraControl).setZoomRatio(2f)
        // Exposure compensation likewise reaches the video session's control.
        viewModel.applyExposureCompensation(0.5f)
        Mockito.verify(viewModel.boundCamera!!.cameraControl)
            .setExposureCompensationIndex(2)
    }

    @Test fun qrTorchExposureAndFocusControls() {
        val viewModel = vm()
        viewModel.setQrResult("WIFI:T:WPA;S:x;;")
        assertEquals("WIFI:T:WPA;S:x;;", viewModel.qrResultMutable.value)
        viewModel.setQrResult(null)
        assertEquals(null, viewModel.qrResultMutable.value)

        viewModel.boundCamera = fakeCamera()
        viewModel.enableTorch(true)
        viewModel.applyExposureCompensation(0.7f)
        viewModel.applyImageCaptureFlashMode()
        val factory = SurfaceOrientedMeteringPointFactory(100f, 100f)
        val action = FocusMeteringAction.Builder(factory.createPoint(0.5f, 0.5f)).build()
        viewModel.startFocusAndMetering(action)
        assertFalse(viewModel.focusLockedMutable.value)
        viewModel.lockFocusAndMetering(action)
        viewModel.clearFocusLock()
        assertFalse(viewModel.focusLockedMutable.value)
        // Analyzer attachment setters are null-safe session plumbing.
        viewModel.setPhotoAnalyzer(null)
        viewModel.setBokehAnalyzer(null)
        viewModel.setImageAnalyzer(null)
        viewModel.attachDesiredAnalyzer()
        viewModel.onSessionBound()
    }

    @Test fun focusWithoutBoundCameraAndOnThrowFallsBack() {
        val viewModel = vm()
        val factory = SurfaceOrientedMeteringPointFactory(100f, 100f)
        val action = FocusMeteringAction.Builder(factory.createPoint(0.5f, 0.5f)).build()
        // No bound camera: must report failure, not silently no-op.
        assertFalse(viewModel.startFocusAndMetering(action))
        assertFalse(viewModel.lockFocusAndMetering(action))
        assertFalse(viewModel.focusLockedMutable.value)
        // Synchronous throw from the control: caught, lock cleared, reported.
        viewModel.boundCamera = fakeCamera()
        Mockito.`when`(viewModel.boundCamera!!.cameraControl.startFocusAndMetering(action))
            .thenThrow(IllegalStateException("camera closed"))
        assertFalse(viewModel.lockFocusAndMetering(action))
        assertFalse(viewModel.focusLockedMutable.value)
        viewModel.clearFocusLock()
    }

    @Test fun recordingTogglesWithoutRecording() {
        val viewModel = vm()
        // Countdown cancellation arm.
        viewModel.timerCountdownMutable.value = 3
        viewModel.toggleRecording()
        assertEquals(0, viewModel.timerCountdownMutable.value)
        viewModel.timerCountdownMutable.value = 2
        viewModel.toggleHighSpeedRecording()
        assertEquals(0, viewModel.timerCountdownMutable.value)
        // Not recording and no capture bound: plain no-ops.
        viewModel.toggleRecording()
        viewModel.toggleHighSpeedRecording()
        viewModel.startRecording()
        assertFalse(viewModel.isRecordingMutable.value)
        // Pause/mic toggles with nothing recording.
        viewModel.togglePauseRecording()
        val mutedBefore = viewModel.micMutedMutable.value
        viewModel.toggleMicMuted()
        assertEquals(!mutedBefore, viewModel.micMutedMutable.value)
        // Timer start/stop bookkeeping.
        viewModel.startRecordingTimer()
        viewModel.stopRecordingTimer()
        assertEquals(0L, viewModel.recordingDurationSecMutable.value)
    }
}
