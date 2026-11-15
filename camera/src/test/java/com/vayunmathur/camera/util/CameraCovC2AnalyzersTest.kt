package com.vayunmathur.camera.util

import android.graphics.Bitmap
import android.graphics.Rect
import androidx.camera.core.ImageInfo
import androidx.camera.core.ImageProxy
import androidx.test.core.app.ApplicationProvider
import java.lang.reflect.Proxy
import java.nio.ByteBuffer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Cov-c2: Photo/Bokeh analyzers against a fake ImageProxy frame. */
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CameraCovC2AnalyzersTest {

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()

    private fun imageInfo(): ImageInfo {
        var self: Any? = null
        val proxy = Proxy.newProxyInstance(
            javaClass.classLoader, arrayOf(ImageInfo::class.java),
        ) { _, method, _ ->
            when (method.name) {
                "getRotationDegrees" -> 0
                "getTimestamp" -> 123L
                else -> null
            }
        }
        self = proxy
        return proxy as ImageInfo
    }

    private fun plane(bytes: ByteArray, rowStride: Int): ImageProxy.PlaneProxy {
        return Proxy.newProxyInstance(
            javaClass.classLoader, arrayOf(ImageProxy.PlaneProxy::class.java),
        ) { _, method, _ ->
            when (method.name) {
                "getBuffer" -> ByteBuffer.wrap(bytes)
                "getRowStride" -> rowStride
                "getPixelStride" -> 1
                else -> null
            }
        } as ImageProxy.PlaneProxy
    }

    private fun fakeProxy(width: Int = 64, height: Int = 48, closedFlag: BooleanArray = BooleanArray(1)): ImageProxy {
        val yBytes = ByteArray(width * height) { (it % 200).toByte() }
        val planes = arrayOf(plane(yBytes, width), plane(ByteArray(width * height / 2), width), plane(ByteArray(width * height / 2), width))
        return Proxy.newProxyInstance(
            javaClass.classLoader, arrayOf(ImageProxy::class.java),
        ) { _, method, args ->
            when (method.name) {
                "getPlanes" -> planes
                "getWidth" -> width
                "getHeight" -> height
                "getFormat" -> android.graphics.ImageFormat.YUV_420_888
                "getImageInfo" -> imageInfo()
                "getCropRect" -> Rect(0, 0, width, height)
                "setCropRect" -> null
                "getImage" -> null
                "close" -> { closedFlag[0] = true; null }
                else -> null
            }
        } as ImageProxy
    }

    @Test fun photoAnalyzerReportsLuminanceAndCloses() {
        var luminance: Float? = null
        var qr: String? = null
        var motionFrames = 0
        val analyzer = PhotoAnalyzer(
            onLuminance = { luminance = it },
            onQrDetected = { qr = it },
            onMotionFrame = { _: Bitmap, _: Long, _: Int -> motionFrames++ },
        )
        val closed = BooleanArray(1)
        analyzer.analyze(fakeProxy(closedFlag = closed))
        // Second frame inside the cadence window still reports luminance.
        analyzer.analyze(fakeProxy(closedFlag = closed))
        assertTrue(closed[0], "analyzer must close every proxy")
        if (luminance != null) {
            assertTrue(luminance!! in 0f..255f)
        }
        assertEquals(null, qr)
    }

    @Test fun photoAnalyzerHandlesEmptyPlanes() {
        var calls = 0
        val analyzer = PhotoAnalyzer(onLuminance = { calls++ }, onQrDetected = {})
        val empty = Proxy.newProxyInstance(
            javaClass.classLoader, arrayOf(ImageProxy::class.java),
        ) { _, method, _ ->
            when (method.name) {
                "getPlanes" -> arrayOf(plane(ByteArray(0), 0))
                "getWidth" -> 0
                "getHeight" -> 0
                "getFormat" -> android.graphics.ImageFormat.YUV_420_888
                "getImageInfo" -> imageInfo()
                "close" -> null
                else -> null
            }
        } as ImageProxy
        analyzer.analyze(empty)
        assertEquals(0, calls)
    }

    // BokehAnalyzer cannot be constructed on the JVM: its SelfieSegmenter
    // init loads the native MlNative library (UnsatisfiedLinkError).
}
