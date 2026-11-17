package com.vayunmathur.camera.platform

import android.util.Range
import androidx.camera.core.Camera
import androidx.camera.core.CameraInfo
import androidx.camera.core.ImageCapture
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.test.core.app.ApplicationProvider
import java.lang.reflect.Proxy
import kotlin.test.assertEquals
import kotlin.test.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Cov-w3 wave 6: Camera2 capability degradation and video builder options. */
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CameraCovW3Wave6Test {
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private fun vm() = CameraViewModel(context.applicationContext as android.app.Application)

    private lateinit var selfProxy: Any
    private fun emptyCameraInfo(): CameraInfo = Proxy.newProxyInstance(
        javaClass.classLoader,
        arrayOf(CameraInfo::class.java, androidx.camera.core.impl.CameraInfoInternal::class.java),
    ) { _, method, _ ->
        when {
            method.name == "getImplementation" -> selfProxy
            method.name == "getEncoderProfilesProvider" -> Proxy.newProxyInstance(
                javaClass.classLoader,
                arrayOf(androidx.camera.core.impl.EncoderProfilesProvider::class.java),
            ) { _, _, _ -> null }
            method.returnType == java.lang.Boolean.TYPE -> false
            method.returnType == java.lang.Integer.TYPE -> 0
            method.returnType == java.lang.Float.TYPE -> 0f
            java.util.Set::class.java.isAssignableFrom(method.returnType) -> emptySet<Any>()
            java.util.List::class.java.isAssignableFrom(method.returnType) -> emptyList<Any>()
            else -> null
        }
    }.also { selfProxy = it } as CameraInfo

    @Test fun capabilityQueriesAllDegrade() {
        val viewModel = vm()
        val info = emptyCameraInfo()
        // Every Camera2/Recorder capability probe must answer "unsupported".
        assertEquals(false, viewModel.av1SupportedByCamera(info))
        assertEquals(false, viewModel.hevcSupportedByCamera(info))
        assertEquals(false, viewModel.hlgSupportedByCamera(info))
        assertNull(viewModel.highestFpsRange(info))
        assertNull(viewModel.preferredStabilizationMode(info))
        // Cinematic mode takes the other fps-pick branch, same degradation.
        viewModel.cameraModeMutable.value = CameraMode.CINEMATIC
        assertNull(viewModel.highestFpsRange(info))
        viewModel.cameraModeMutable.value = CameraMode.VIDEO
        // Bound-camera manual ranges with an unanswerable info: no throw, empty stops.
        val camera = Mockito.mock(Camera::class.java)
        Mockito.`when`(camera.cameraInfo).thenReturn(info)
        viewModel.boundCamera = camera
        viewModel.readManualControlRanges()
        viewModel.boundCamera = null
    }

    @Test fun videoCaptureRequestOptionsOnBuilders() {
        val viewModel = vm()
        val previewBuilder = Preview.Builder()
        viewModel.applyVideoCaptureRequestOptions(previewBuilder, Range(30, 30), null)
        viewModel.applyVideoCaptureRequestOptions(
            previewBuilder, Range(60, 60),
            android.hardware.camera2.CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_ON,
        )
        viewModel.applyVideoCaptureRequestOptions(previewBuilder, null, null)
        val captureBuilder = ImageCapture.Builder()
        viewModel.applyVideoCaptureRequestOptions(captureBuilder, Range(24, 24), 1)
    }

    @Test fun unbindQuietlySwallowsAndSucceeds() {
        val provider = Mockito.mock(ProcessCameraProvider::class.java)
        unbindQuietly(provider)
        unbindQuietly(provider, "CovW3")
        Mockito.doThrow(IllegalStateException("unbind fail")).`when`(provider).unbindAll()
        unbindQuietly(provider)
        Mockito.doThrow(IllegalArgumentException("unbind fail")).`when`(provider).unbindAll()
        unbindQuietly(provider, "CovW3Throwing")
    }
}
