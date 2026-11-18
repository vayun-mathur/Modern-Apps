package com.vayunmathur.camera.platform

import android.graphics.Bitmap
import android.graphics.Rect
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageInfo
import androidx.camera.core.ImageProxy
import androidx.test.core.app.ApplicationProvider
import com.google.common.util.concurrent.ListenableFuture
import java.lang.reflect.Proxy
import java.nio.ByteBuffer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.junit.runner.RunWith
import org.mockito.Mockito
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Single-capture night exposure hand-off + thumbnail + burst frame conversion. */
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SingleCaptureNightTest {

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private fun vm() = CameraViewModel(context.applicationContext as android.app.Application)

    private fun planClass() = Class.forName("com.vayunmathur.camera.platform.SingleCapturePlan")

    private fun makePlan(
        viewModel: CameraViewModel,
        control: androidx.camera.camera2.interop.Camera2CameraControl?,
        withIso: Boolean,
    ): Any {
        val cls = planClass()
        val ctor = cls.getDeclaredConstructor(
            ImageCapture::class.java,
            Class.forName("com.vayunmathur.camera.data.PendingStill"),
            ImageCapture.OutputFileOptions::class.java,
            Long::class.javaObjectType,
            Int::class.javaObjectType,
            androidx.camera.camera2.interop.Camera2CameraControl::class.java,
            NightExposure::class.java,
            Float::class.javaPrimitiveType, Float::class.javaPrimitiveType,
            Boolean::class.javaPrimitiveType, Boolean::class.javaPrimitiveType,
            Float::class.javaPrimitiveType,
        ).apply { isAccessible = true }
        val pendingCls = Class.forName("com.vayunmathur.camera.data.PendingStill")
        val pendingCtor = pendingCls.getDeclaredConstructor(
            ImageCapture.OutputFileOptions::class.java, android.net.Uri::class.java,
            java.io.OutputStream::class.java, String::class.java,
        ).apply { isAccessible = true }
        val options = ImageCapture.OutputFileOptions.Builder(context.cacheDir).build()
        val pending = pendingCtor.newInstance(options, null, null, "IMG_test.jpg")
        return ctor.newInstance(
            Mockito.mock(ImageCapture::class.java), pending,
            ImageCapture.OutputFileOptions.Builder(context.cacheDir).build(),
            null, if (withIso) 800 else null, control,
            NightExposure(33_000_000L, if (withIso) 800 else null),
            0f, 0f, false, false, 0.5f,
        )
    }

    @Test fun nightExposureFailureFallsThroughToCapture() {
        val viewModel = vm()
        val control = Mockito.mock(androidx.camera.camera2.interop.Camera2CameraControl::class.java)
        Mockito.`when`(
            control.setCaptureRequestOptions(
                Mockito.any(androidx.camera.camera2.interop.CaptureRequestOptions::class.java)
                    ?: androidx.camera.camera2.interop.CaptureRequestOptions.Builder().build(),
            ),
        ).thenThrow(IllegalStateException("session gone"))
        var captured = 0
        val m = Class.forName("com.vayunmathur.camera.platform.CameraSingleCaptureKt")
            .getDeclaredMethod(
                "applyNightExposureThen", CameraViewModel::class.java, planClass(), Function1::class.java,
            ).apply { isAccessible = true }
        val doCapture: (Any) -> Unit = { captured++ }
        m.invoke(null, viewModel, makePlan(viewModel, control, withIso = true), doCapture)
        assertEquals(1, captured)
        m.invoke(null, viewModel, makePlan(viewModel, control, withIso = false), doCapture)
        assertEquals(2, captured)
    }

    @Test fun nightExposureSuccessWaitsForListener() {
        val viewModel = vm()
        val control = Mockito.mock(androidx.camera.camera2.interop.Camera2CameraControl::class.java)
        @Suppress("UNCHECKED_CAST")
        val future = Mockito.mock(ListenableFuture::class.java) as ListenableFuture<Void?>
        Mockito.`when`(
            control.setCaptureRequestOptions(
                Mockito.any(androidx.camera.camera2.interop.CaptureRequestOptions::class.java)
                    ?: androidx.camera.camera2.interop.CaptureRequestOptions.Builder().build(),
            ),
        ).thenReturn(future)
        var captured = 0
        val m = Class.forName("com.vayunmathur.camera.platform.CameraSingleCaptureKt")
            .getDeclaredMethod(
                "applyNightExposureThen", CameraViewModel::class.java, planClass(), Function1::class.java,
            ).apply { isAccessible = true }
        val doCapture: (Any) -> Unit = { captured++ }
        m.invoke(null, viewModel, makePlan(viewModel, control, withIso = false), doCapture)
        // Success path defers the capture to the future's listener.
        assertEquals(0, captured)
        Mockito.verify(future).addListener(
            Mockito.any(Runnable::class.java) ?: Runnable { },
            Mockito.any(java.util.concurrent.Executor::class.java) ?: java.util.concurrent.Executor { it.run() },
        )
    }

    @Test fun captureSinglePhotoWithoutCaptureIsNoOp() {
        val viewModel = vm()
        viewModel.captureSinglePhoto()
        assertEquals(false, viewModel.isCapturingMutable.value)
    }

    private fun proxy(width: Int = 64, height: Int = 48, closed: BooleanArray = BooleanArray(1)): ImageProxy {
        val y = ByteArray(width * height) { (it * 3 % 251).toByte() }
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
            when (m.name) { "getRotationDegrees" -> 90; "getTimestamp" -> 5L; else -> null }
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

    @Test fun downscaledThumbnailUndecodableFrameFailsCleanly() {
        val closed = BooleanArray(1)
        // The harness YUV converter yields no bitmap for this fake frame:
        // a clean IllegalArgumentException (routed to onError by the
        // caller), never an NPE crash.
        kotlin.test.assertFailsWith<IllegalArgumentException> {
            downscaledThumbnail(proxy(closed = closed), mirror = true)
        }
    }
}
