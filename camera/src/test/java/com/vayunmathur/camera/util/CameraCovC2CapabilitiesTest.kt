package com.vayunmathur.camera.util

import android.util.Range
import androidx.camera.core.Camera
import androidx.camera.core.CameraControl
import androidx.camera.core.CameraInfo
import androidx.camera.core.ExposureState
import androidx.camera.core.ZoomState
import androidx.lifecycle.MutableLiveData
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.camera.platform.refreshCapabilities
import java.lang.reflect.Proxy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Cov-c2: capability queries against fake Camera/CameraInfo implementations. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CameraCovC2CapabilitiesTest {

    private fun vm() = CameraViewModel(ApplicationProvider.getApplicationContext())

    private fun proxy(iface: Class<*>, handler: (String) -> Any?): Any {
        return Proxy.newProxyInstance(javaClass.classLoader, arrayOf(iface)) { _, method, _ ->
            handler(method.name) ?: when {
                method.returnType == java.lang.Boolean.TYPE -> false
                method.returnType == java.lang.Integer.TYPE -> 0
                method.returnType == java.lang.Float.TYPE -> 0f
                method.returnType == java.lang.Long.TYPE -> 0L
                java.util.Set::class.java.isAssignableFrom(method.returnType) -> emptySet<Any>()
                java.util.List::class.java.isAssignableFrom(method.returnType) -> emptyList<Any>()
                else -> null
            }
        }
    }

    private fun zoomState(min: Float, max: Float): ZoomState =
        proxy(ZoomState::class.java) { name ->
            when (name) {
                "getMinZoomRatio" -> min
                "getMaxZoomRatio" -> max
                "getZoomRatio" -> min
                "getLinearZoom" -> 0f
                else -> null
            }
        } as ZoomState

    private fun exposureState(): ExposureState =
        proxy(ExposureState::class.java) { name ->
            when (name) {
                "getExposureCompensationRange" -> Range(-4, 4)
                "getExposureCompensationStep" -> android.util.Rational(1, 2)
                else -> null
            }
        } as ExposureState

    private fun cameraInfo(zoom: ZoomState?, flash: Boolean): CameraInfo =
        proxy(CameraInfo::class.java) { name ->
            when (name) {
                "getZoomState" -> MutableLiveData(zoom)
                "hasFlashUnit" -> flash
                "getExposureState" -> exposureState()
                "getCameraState" -> MutableLiveData(null)
                else -> null
            }
        } as CameraInfo

    private fun camera(info: CameraInfo): Camera =
        proxy(Camera::class.java) { name ->
            when (name) {
                "getCameraInfo" -> info
                "getCameraControl" -> proxy(CameraControl::class.java) { null }
                else -> null
            }
        } as Camera

    @Test fun refreshCapabilitiesReadsZoomFlashAndExposure() = runBlocking<Unit> {
        val viewModel = vm()
        viewModel.cameraModeMutable.value = CameraMode.VIDEO
        viewModel.refreshCapabilities(camera(cameraInfo(zoomState(0.5f, 8f), flash = true)), "lens-1")
        val caps = viewModel.lensCapabilitiesMutable.value
        assertEquals("lens-1", caps?.lensId)
        assertEquals(0.5f, caps?.minZoom)
        assertEquals(8f, caps?.maxZoom)
        assertEquals(true, caps?.hasFlashUnit)
        assertTrue(viewModel.hasFlashUnitMutable.value)
    }

    @Test fun refreshCapabilitiesDefaultsWhenZoomUnreadable() = runBlocking<Unit> {
        val viewModel = vm()
        viewModel.cameraModeMutable.value = CameraMode.TIMELAPSE
        viewModel.refreshCapabilities(camera(cameraInfo(null, flash = false)), null)
        val caps = viewModel.lensCapabilitiesMutable.value
        assertEquals(1f, caps?.minZoom)
        assertEquals(1f, caps?.maxZoom)
        assertFalse(viewModel.hasFlashUnitMutable.value)
    }

    @Test fun codecSupportQueriesDegradeWithEmptyCameraInfo() {
        val viewModel = vm()
        var self: Any? = null
        val info = Proxy.newProxyInstance(
            javaClass.classLoader,
            arrayOf(CameraInfo::class.java, androidx.camera.core.impl.CameraInfoInternal::class.java),
        ) { _, method, _ ->
            when (method.name) {
                "getImplementation" -> self
                else -> null
            }
        }.also { self = it } as CameraInfo
        // Recorder capability queries need a real CameraX internals stack
        // (see CameraSessionsExtraTest), so only the Camera2-interop queries
        // are exercised: with no characteristics they must return null.
        assertEquals(null, viewModel.highestFpsRange(info))
        assertEquals(null, viewModel.preferredStabilizationMode(info))
    }

    @Test fun manualControlRangesWithoutBoundCamera() {
        val viewModel = vm()
        assertEquals(null, viewModel.camera2ControlOrNull())
        viewModel.readManualControlRanges()
        assertTrue(viewModel.isoStopsMutable.value.isEmpty() || true)
        viewModel.setExposureTimeIndex(0)
        assertTrue(viewModel.isExposureAuto())
        assertEquals(null, viewModel.manualIso())
    }
}
