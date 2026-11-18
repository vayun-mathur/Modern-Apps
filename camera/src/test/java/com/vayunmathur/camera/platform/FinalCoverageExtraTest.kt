package com.vayunmathur.camera.platform

import android.graphics.Bitmap
import android.graphics.Rect
import android.net.Uri
import androidx.camera.core.Camera
import androidx.camera.core.CameraInfo
import androidx.camera.core.ExposureState
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageInfo
import androidx.camera.core.ImageProxy
import androidx.camera.core.ZoomState
import androidx.lifecycle.MutableLiveData
import androidx.test.core.app.ApplicationProvider
import com.google.common.util.concurrent.ListenableFuture
import java.lang.reflect.Proxy
import java.nio.ByteBuffer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.junit.runner.RunWith
import org.mockito.Mockito
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Manual controls through a Camera2 adapter unwrap + capture-for-result. */
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class FinalCoverageExtraTest {

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private fun vm() = CameraViewModel(context.applicationContext as android.app.Application)

    private fun cam2Control(): androidx.camera.camera2.interop.Camera2CameraControl {
        val control = Mockito.mock(androidx.camera.camera2.interop.Camera2CameraControl::class.java)
        @Suppress("UNCHECKED_CAST")
        val future = Mockito.mock(ListenableFuture::class.java) as ListenableFuture<Void?>
        Mockito.`when`(
            control.setCaptureRequestOptions(
                Mockito.any(androidx.camera.camera2.interop.CaptureRequestOptions::class.java)
                    ?: androidx.camera.camera2.interop.CaptureRequestOptions.Builder().build(),
            ),
        ).thenReturn(future)
        return control
    }

    /** A Camera whose CameraControl unwraps (via CameraControlAdapter) to [cam2]. */
    private fun cameraWrapping(cam2: androidx.camera.camera2.interop.Camera2CameraControl): Camera {
        val adapter = Mockito.mock(androidx.camera.camera2.adapter.CameraControlAdapter::class.java)
        Mockito.`when`(adapter.camera2cameraControl).thenReturn(cam2)
        val control = Mockito.mock(
            androidx.camera.core.CameraControl::class.java,
            Mockito.withSettings().extraInterfaces(androidx.camera.core.impl.CameraControlInternal::class.java),
        )
        val internal = control as androidx.camera.core.impl.CameraControlInternal
        Mockito.`when`(internal.implementation).thenReturn(adapter)
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
        Mockito.`when`(camera.cameraControl).thenReturn(control)
        return camera
    }

    @Test fun manualControlsReachCamera2Control() {
        val viewModel = vm()
        val cam2 = cam2Control()
        viewModel.boundCamera = cameraWrapping(cam2)
        viewModel.isoStopsMutable.value = listOf(100, 200, 400, 800)
        // Auto first (empty options), then fully manual, then toggles off.
        viewModel.applyManualControls()
        viewModel.setExposureTimeIndex(2)
        viewModel.setManualIsoIndex(3)
        viewModel.noiseReductionEnabledMutable.value = false
        viewModel.edgeSharpeningEnabledMutable.value = false
        viewModel.applyManualControls()
        Mockito.verify(cam2, Mockito.atLeast(2)).setCaptureRequestOptions(
            Mockito.any(androidx.camera.camera2.interop.CaptureRequestOptions::class.java)
                ?: androidx.camera.camera2.interop.CaptureRequestOptions.Builder().build(),
        )
        assertFalse(viewModel.isExposureAuto())
        assertEquals(400, viewModel.manualIso())
    }

    private fun proxy(closed: BooleanArray = BooleanArray(1)): ImageProxy {
        val width = 32; val height = 24
        val y = ByteArray(width * height) { (it % 251).toByte() }
        fun plane(bytes: ByteArray, stride: Int) = Proxy.newProxyInstance(
            javaClass.classLoader, arrayOf(ImageProxy.PlaneProxy::class.java),
        ) { _, m, _ ->
            when (m.name) {
                "getBuffer" -> ByteBuffer.wrap(bytes)
                "getRowStride" -> stride
                "getPixelStride" -> 1
                else -> null
            }
        } as ImageProxy.PlaneProxy
        val planes = arrayOf(plane(y, width), plane(ByteArray(width * height / 2), width), plane(ByteArray(width * height / 2), width))
        val info = Proxy.newProxyInstance(javaClass.classLoader, arrayOf(ImageInfo::class.java)) { _, m, _ ->
            when (m.name) { "getRotationDegrees" -> 0; "getTimestamp" -> 3L; else -> null }
        } as ImageInfo
        return Proxy.newProxyInstance(javaClass.classLoader, arrayOf(ImageProxy::class.java)) { _, m, _ ->
            when (m.name) {
                "getPlanes" -> planes
                "getWidth" -> width
                "getHeight" -> height
                "getFormat" -> android.graphics.ImageFormat.YUV_420_888
                "getImageInfo" -> info
                "getCropRect" -> Rect(0, 0, width, height)
                "close" -> { closed[0] = true; null }
                else -> null
            }
        } as ImageProxy
    }

    @Test fun captureForResultWithoutCaptureErrors() {
        val viewModel = vm()
        var errors = 0
        viewModel.capturePhotoForResult(onSaved = { }, onError = { errors++ })
        assertEquals(1, errors)
    }

    @Test fun captureForResultThumbnailDecodeFailureRoutesToError() {
        val viewModel = vm()
        val capture = Mockito.mock(ImageCapture::class.java)
        var callback: ImageCapture.OnImageCapturedCallback? = null
        Mockito.doAnswer { inv ->
            callback = inv.getArgument(1)
            null
        }.`when`(capture).takePicture(
            Mockito.any(java.util.concurrent.Executor::class.java),
            Mockito.any(ImageCapture.OnImageCapturedCallback::class.java),
        )
        viewModel.imageCapture = capture
        var saved: Bitmap? = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
        var errors = 0
        viewModel.capturePhotoForResult(onSaved = { saved = it }, onError = { errors++ })
        assertTrue(viewModel.isCapturingMutable.value)
        val closed = BooleanArray(1)
        callback!!.onCaptureSuccess(proxy(closed))
        // Undecodable harness frame: onError (not a crash, not a fake save).
        assertEquals(1, errors)
        assertFalse(viewModel.isCapturingMutable.value)
        assertTrue(closed[0])
        // The error callback path also clears the in-flight flag.
        var errors2 = 0
        val viewModel2 = vm()
        val capture2 = Mockito.mock(ImageCapture::class.java)
        var callback2: ImageCapture.OnImageCapturedCallback? = null
        Mockito.doAnswer { inv ->
            callback2 = inv.getArgument(1)
            null
        }.`when`(capture2).takePicture(
            Mockito.any(java.util.concurrent.Executor::class.java),
            Mockito.any(ImageCapture.OnImageCapturedCallback::class.java),
        )
        viewModel2.imageCapture = capture2
        viewModel2.capturePhotoForResult(onSaved = { }, onError = { errors2++ })
        callback2!!.onError(ImageCaptureException(ImageCapture.ERROR_UNKNOWN, "nope", null))
        assertEquals(1, errors2)
        assertFalse(viewModel2.isCapturingMutable.value)
    }

    @Test fun captureForResultExtraOutputWithoutStreamErrors() {
        val viewModel = vm()
        val capture = Mockito.mock(ImageCapture::class.java)
        viewModel.imageCapture = capture
        viewModel.enableCaptureForResult(Uri.parse("content://caller/output.jpg"))
        var errors = 0
        viewModel.capturePhotoForResult(onSaved = { }, onError = { errors++ })
        // Robolectric's resolver cannot open the caller's stream: clean error.
        assertEquals(1, errors)
        assertFalse(viewModel.isCapturingMutable.value)
        assertNotNull(viewModel)
    }
}
