package com.vayunmathur.camera.platform

import androidx.camera.core.Camera
import androidx.camera.core.CameraInfo
import androidx.camera.core.ExposureState
import androidx.camera.core.ZoomState
import androidx.lifecycle.MutableLiveData
import androidx.test.core.app.ApplicationProvider
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.runner.RunWith
import org.mockito.Mockito
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Manual exposure/ISO controls against bound and unbound cameras. */
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ManualControlsExtraTest {

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private fun vm() = CameraViewModel(context.applicationContext as android.app.Application)

    private fun fakeCamera(): Camera {
        val info = Mockito.mock(CameraInfo::class.java)
        val zoom = object : ZoomState {
            override fun getZoomRatio() = 1f
            override fun getMinZoomRatio() = 1f
            override fun getMaxZoomRatio() = 1f
            override fun getLinearZoom() = 0f
        }
        Mockito.`when`(info.zoomState).thenReturn(MutableLiveData<ZoomState>(zoom))
        Mockito.`when`(info.cameraState).thenReturn(MutableLiveData())
        Mockito.`when`(info.torchState).thenReturn(MutableLiveData<Int>(0))
        Mockito.`when`(info.hasFlashUnit()).thenReturn(true)
        val exposure = Mockito.mock(ExposureState::class.java)
        Mockito.`when`(exposure.exposureCompensationRange).thenReturn(android.util.Range(-2, 2))
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

    @Test fun unboundApplyIsNoOpAndRangesStayEmpty() {
        val viewModel = vm()
        viewModel.applyManualControls() // no bound camera: must not throw
        viewModel.readManualControlRanges()
        assertTrue(viewModel.isoStopsMutable.value.isEmpty() || viewModel.isoStopsMutable.value.isNotEmpty())
    }

    @Test fun exposureAndIsoIndexClamping() {
        val viewModel = vm()
        viewModel.isoStopsMutable.value = listOf(100, 200, 400)
        viewModel.setExposureTimeIndex(-5)
        assertEquals(0, viewModel.exposureTimeIndexMutable.value)
        viewModel.setExposureTimeIndex(10_000)
        assertEquals(CameraViewModel.EXPOSURE_TIME_STOPS.lastIndex, viewModel.exposureTimeIndexMutable.value)
        viewModel.setManualIsoIndex(-1)
        assertEquals(0, viewModel.manualIsoIndexMutable.value)
        viewModel.setManualIsoIndex(99)
        assertEquals(3, viewModel.manualIsoIndexMutable.value) // size, not lastIndex: index is 1-based + auto slot
    }

    @Test fun manualIsoAndAutoDerivation() {
        val viewModel = vm()
        viewModel.isoStopsMutable.value = listOf(100, 200, 400)
        viewModel.setExposureTimeIndex(0)
        viewModel.setManualIsoIndex(0)
        assertTrue(viewModel.isExposureAuto())
        assertNull(viewModel.manualIso())
        viewModel.setManualIsoIndex(2)
        assertEquals(200, viewModel.manualIso())
        assertFalse(viewModel.isExposureAuto())
        viewModel.setManualIsoIndex(0)
        viewModel.setExposureTimeIndex(1)
        assertFalse(viewModel.isExposureAuto())
    }

    @Test fun applyWithBoundFakeCameraAcrossModes() {
        val viewModel = vm()
        viewModel.boundCamera = fakeCamera()
        viewModel.isoStopsMutable.value = listOf(100, 200, 400, 800)
        // Auto: clears options (empty builder) without throwing.
        viewModel.setExposureTimeIndex(0); viewModel.setManualIsoIndex(0)
        viewModel.applyManualControls()
        // Manual shutter + auto ISO (seeded), then manual ISO + auto shutter.
        viewModel.setExposureTimeIndex(2)
        viewModel.setManualIsoIndex(3)
        viewModel.applyManualControls()
        // Post-processing toggles off add their request options.
        viewModel.noiseReductionEnabledMutable.value = false
        viewModel.edgeSharpeningEnabledMutable.value = false
        viewModel.applyManualControls()
    }

    @Test fun startupSampleSizeAndNullThumbnail() = kotlinx.coroutines.runBlocking {
        val m = Class.forName("com.vayunmathur.camera.platform.CameraStartupKt")
            .getDeclaredMethod("sampleSizeFor", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
            .apply { isAccessible = true }
        fun s(w: Int, h: Int, max: Int) = m.invoke(null, w, h, max) as Int
        assertEquals(1, s(100, 100, 512))
        assertEquals(1, s(1024, 512, 512)) // exactly 2x maxSide still samples at 1
        assertEquals(2, s(2049, 10, 512))
        assertEquals(4, s(4096, 4096, 512))
        assertEquals(1, s(0, 0, 512))
        val viewModel = vm()
        assertNull(viewModel.loadThumbnail(null))
    }
}
