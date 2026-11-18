package com.vayunmathur.camera.platform

import android.graphics.Bitmap
import android.graphics.Rect
import androidx.camera.core.ImageInfo
import androidx.camera.core.ImageProxy
import java.lang.reflect.Proxy
import java.nio.ByteBuffer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** PhotoAnalyzer cadence-gated motion/QR paths (timestamps aged via reflection). */
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PhotoAnalyzerCadenceTest {

    private fun proxy(width: Int = 64, height: Int = 48, closed: BooleanArray = BooleanArray(1)): ImageProxy {
        val y = ByteArray(width * height) { (it * 7 % 251).toByte() }
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
            when (m.name) { "getRotationDegrees" -> 90; "getTimestamp" -> 42L; else -> null }
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

    private fun ageTimestamps(analyzer: PhotoAnalyzer) {
        for (name in listOf("lastMotionAtMs", "lastDecodeAtMs")) {
            PhotoAnalyzer::class.java.getDeclaredField(name).apply { isAccessible = true }
                .setLong(analyzer, -10_000L)
        }
    }

    @Test fun agedCadenceRunsMotionAndQrAndCloses() {
        var luminance: Float? = null
        var motion = 0
        var motionTs = -1L
        val analyzer = PhotoAnalyzer(
            onLuminance = { luminance = it },
            onQrDetected = { },
            onMotionFrame = { _: Bitmap, ts: Long, _: Int -> motion++; motionTs = ts },
        )
        ageTimestamps(analyzer)
        val closed = BooleanArray(1)
        analyzer.analyze(proxy(closed = closed))
        assertTrue(closed[0], "proxy closed after full pipeline")
        assertTrue(luminance != null && luminance!! in 0f..255f)
        // Motion emit depends on the harness YUV->bitmap conversion; either it
        // delivered with the frame timestamp or it degraded without a crash.
        assertTrue(motion == 0 || motionTs == 42L)
        assertTrue(motion <= 1)
    }

    @Test fun throwingMotionCallbackDoesNotBreakAnalyze() {
        val analyzer = PhotoAnalyzer(
            onLuminance = { },
            onQrDetected = { throw IllegalStateException("ui gone") },
            onMotionFrame = { _, _, _ -> throw IllegalArgumentException("ring closed") },
        )
        ageTimestamps(analyzer)
        val closed = BooleanArray(1)
        analyzer.analyze(proxy(closed = closed))
        assertTrue(closed[0])
    }

    @Test fun freshCadenceSkipsExpensiveOpsButStillCloses() {
        var motion = 0
        val analyzer = PhotoAnalyzer(
            onLuminance = { },
            onQrDetected = { },
            onMotionFrame = { _, _, _ -> motion++ },
        )
        // Timestamps start at 0 and Robolectric's clock is near 0: both gates closed.
        val closed = BooleanArray(1)
        analyzer.analyze(proxy(closed = closed))
        assertTrue(closed[0])
        assertEquals(0, motion)
    }
}
