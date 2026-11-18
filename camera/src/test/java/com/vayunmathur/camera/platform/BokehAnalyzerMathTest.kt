package com.vayunmathur.camera.platform

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import androidx.camera.core.ImageInfo
import androidx.camera.core.ImageProxy
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.library.ml.SegmentationMask
import java.lang.reflect.Proxy
import java.nio.ByteBuffer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** BokehAnalyzer: close/throttle lifecycle, temporal smoothing, frame prep helpers. */
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BokehAnalyzerMathTest {

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()

    private fun proxy(width: Int = 64, height: Int = 48, closed: BooleanArray = BooleanArray(1)): ImageProxy {
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
            when (m.name) { "getRotationDegrees" -> 0; "getTimestamp" -> 7L; else -> null }
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

    @Test fun closedAnalyzerIgnoresFramesAndCloseIsIdempotent() {
        var emitted = 0
        val analyzer = BokehAnalyzer(context, isFrontFacing = false) { emitted++ }
        analyzer.close(); analyzer.close()
        val closed = BooleanArray(1)
        analyzer.analyze(proxy(closed = closed))
        assertTrue(closed[0], "proxy always closed")
        assertEquals(0, emitted)
    }

    @Test fun liveAnalyzeNeverThrowsAndAlwaysCloses() {
        var emitted = 0
        val analyzer = BokehAnalyzer(context, isFrontFacing = true) { emitted++ }
        val closed = BooleanArray(1)
        analyzer.analyze(proxy(closed = closed))
        assertTrue(closed[0])
        // Native segmentation is unavailable in this harness: no mask, no crash.
        assertEquals(0, emitted)
        analyzer.close()
    }

    @Test fun smoothAndEmitBlendsTemporallyAndEmitsBitmap() {
        var last: Bitmap? = null
        val analyzer = BokehAnalyzer(context) { last = it }
        val m = BokehAnalyzer::class.java.getDeclaredMethod(
            "smoothAndEmit", SegmentationMask::class.java,
        ).apply { isAccessible = true }
        m.invoke(analyzer, SegmentationMask(4, 3, FloatArray(12) { 1f }))
        assertNotNull(last)
        assertEquals(4, last!!.width); assertEquals(3, last!!.height)
        assertEquals(255, Color.alpha(last!!.getPixel(0, 0)))
        // Next frame all-background: smoothed = 0*0.65 + 1*0.35 = 0.35 -> alpha 89.
        m.invoke(analyzer, SegmentationMask(4, 3, FloatArray(12) { 0f }))
        assertEquals(89, Color.alpha(last!!.getPixel(1, 1)))
        analyzer.close()
        // After close, smoothing is a no-op (emitted bitmap unchanged).
        val before = last
        m.invoke(analyzer, SegmentationMask(4, 3, FloatArray(12) { 1f }))
        assertTrue(last === before)
    }

    @Test fun framePrepHelpersDownscaleAndOrient() {
        val analyzer = BokehAnalyzer(context, isFrontFacing = true) { }
        val cls = BokehAnalyzer::class.java
        val down = cls.getDeclaredMethod("downscaleIfNeeded", Bitmap::class.java, Int::class.javaPrimitiveType)
            .apply { isAccessible = true }
        val big = Bitmap.createBitmap(100, 50, Bitmap.Config.ARGB_8888)
        val small = down.invoke(analyzer, big, 50) as Bitmap
        assertEquals(50, small.width); assertEquals(25, small.height)
        assertTrue(down.invoke(analyzer, small, 512) === small)
        val orient = cls.getDeclaredMethod("orientToDisplay", Bitmap::class.java, Int::class.javaPrimitiveType)
            .apply { isAccessible = true }
        val rotated = orient.invoke(analyzer, small, 90) as Bitmap
        assertEquals(25, rotated.width); assertEquals(50, rotated.height)
        val back = BokehAnalyzer(context, isFrontFacing = false) { }
        assertTrue(orient.invoke(back, small, 0) === small)
        analyzer.close(); back.close()
    }
}
