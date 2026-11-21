package com.vayunmathur.camera.platform

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageInfo
import androidx.camera.core.ImageProxy
import androidx.test.core.app.ApplicationProvider
import java.lang.reflect.Proxy
import java.nio.ByteBuffer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.runner.RunWith
import org.mockito.Mockito
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Night burst frame conversion with undecodable frames + bokeh segment lock. */
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BurstConvertExtraTest {

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private fun vm() = CameraViewModel(context.applicationContext as android.app.Application)

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
            when (m.name) { "getRotationDegrees" -> 0; "getTimestamp" -> 9L; else -> null }
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

    @Test fun burstWithUndecodableFramesStillFinishes() {
        val viewModel = vm()
        val capture = Mockito.mock(ImageCapture::class.java)
        val callbacks = mutableListOf<ImageCapture.OnImageCapturedCallback>()
        Mockito.doAnswer { inv ->
            callbacks.add(inv.getArgument(1))
            null
        }.`when`(capture).takePicture(
            Mockito.any(java.util.concurrent.Executor::class.java),
            Mockito.any(ImageCapture.OnImageCapturedCallback::class.java),
        )
        viewModel.imageCapture = capture
        var done: List<Bitmap>? = null
        val cls = Class.forName("com.vayunmathur.camera.platform.NightBurstDriver")
        val ctor = cls.getDeclaredConstructor(
            CameraViewModel::class.java,
            androidx.camera.camera2.interop.Camera2CameraControl::class.java,
            Boolean::class.javaPrimitiveType,
            NightExposure::class.java,
            Function1::class.java,
            Function1::class.java,
        ).apply { isAccessible = true }
        val onFrameCaptured: (Int) -> Unit = { }
        val onDone: (List<Bitmap>) -> Unit = { frames -> done = frames }
        val driver = ctor.newInstance(viewModel, null, false, NightExposure(1_000L, null), onFrameCaptured, onDone)
        cls.getDeclaredMethod("start").apply { isAccessible = true }.invoke(driver)
        var guard = 0
        while (done == null && guard < 20) {
            val closed = BooleanArray(1)
            callbacks.last().onCaptureSuccess(proxy(closed))
            assertTrue(closed[0], "frame closed even when undecodable")
            guard++
        }
        // Every frame undecodable: burst ends empty instead of crashing/looping.
        assertEquals(emptyList(), done)
    }

    @Test fun segmentLockedWithoutSegmenterRecyclesFrame() {
        var emitted = 0
        val analyzer = BokehAnalyzer(context) { emitted++ }
        val frame = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.GREEN) }
        val m = BokehAnalyzer::class.java.getDeclaredMethod("segmentLocked", Bitmap::class.java)
            .apply { isAccessible = true }
        val result = m.invoke(analyzer, frame)
        assertEquals(null, result)
        assertTrue(frame.isRecycled)
        assertEquals(0, emitted)
        analyzer.close()
    }
}
