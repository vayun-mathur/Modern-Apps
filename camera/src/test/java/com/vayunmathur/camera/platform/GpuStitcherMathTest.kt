package com.vayunmathur.camera.platform

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** GpuStitcher binary estimate parsing, spherical forward map, content rect. */
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class GpuStitcherMathTest {

    private fun blob(canvasW: Int = 100, canvasH: Int = 50, count: Int = 1, truncate: Int = 0): ByteArray {
        val size = 8 + 24 + 4 + count * (4 + 24 + 72 + 4)
        val buf = ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN)
        buf.putInt(canvasW); buf.putInt(canvasH)
        buf.putDouble(10.0); buf.putDouble(20.0); buf.putDouble(300.0)
        buf.putInt(count)
        repeat(count) { i ->
            buf.putInt(i + 3); buf.putDouble(500.0 + i); buf.putDouble(40.0); buf.putDouble(30.0)
            repeat(9) { buf.putDouble(if (it % 4 == 0) 1.0 else 0.0) }
            buf.putFloat(1.5f)
        }
        val bytes = ByteArray(buf.position()); buf.flip(); buf.get(bytes)
        return if (truncate > 0) bytes.copyOf(bytes.size - truncate) else bytes
    }

    @Test fun parseEstimateRoundTrips() {
        val est = GpuStitcher.parseEstimate(blob(count = 2))
        assertNotNull(est)
        assertEquals(100, est.canvasW); assertEquals(50, est.canvasH)
        assertEquals(10.0, est.u0); assertEquals(300.0, est.scale)
        assertEquals(2, est.cams.size)
        assertEquals(4, est.cams[1].originalIndex)
        assertEquals(501.0, est.cams[1].focal)
        assertEquals(1.5f, est.cams[0].gain)
        assertEquals(1.0, est.cams[0].r[0]); assertEquals(0.0, est.cams[0].r[1])
    }

    @Test fun parseEstimateRejectsBadHeadersAndTruncation() {
        assertNull(GpuStitcher.parseEstimate(blob(count = 0)))
        assertNull(GpuStitcher.parseEstimate(blob(canvasW = 0)))
        assertNull(GpuStitcher.parseEstimate(blob(canvasH = -1)))
        assertNull(GpuStitcher.parseEstimate(blob(truncate = 10)))
        assertNull(GpuStitcher.parseEstimate(ByteArray(3)))
        assertNull(GpuStitcher.parseEstimate(ByteArray(0)))
    }

    @Test fun compositeWithoutGlReturnsNull() {
        val est = GpuStitcher.parseEstimate(blob())!!
        // No EGL/GLES under Robolectric: graceful null, never a throw.
        assertNull(GpuStitcher.composite(est, listOf(ByteArray(8))))
        assertNull(GpuStitcher.composite(est, emptyList()))
    }

    @Test fun forwardMapsPrincipalPointToEquatorCentre() {
        val cam = GpuStitcher.FrameCam(
            0, 500.0, 40.0, 30.0,
            doubleArrayOf(1.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 1.0), 1f,
        )
        val m = GpuStitcher::class.java.getDeclaredMethod(
            "forward", GpuStitcher.FrameCam::class.java,
            Double::class.javaPrimitiveType, Double::class.javaPrimitiveType, Double::class.javaPrimitiveType,
        ).apply { isAccessible = true }
        val uv = m.invoke(GpuStitcher, cam, 300.0, 40.0, 30.0) as DoubleArray
        assertEquals(0.0, uv[0], 1e-9) // atan2(0,1)
        assertEquals(300.0 * Math.PI / 2, uv[1], 1e-6) // py=0 -> pi - acos(0) = pi/2
        // Point above centre (smaller y) tilts the ray up: under this
        // convention v = scale*(pi - acos(py/|p|)) shrinks below pi/2*scale.
        val up = m.invoke(GpuStitcher, cam, 300.0, 40.0, 0.0) as DoubleArray
        assertTrue(up[1] < uv[1])
        assertTrue(abs(up[0]) < 1e-9)
    }

    @Test fun contentRectFindsLargestOpaqueRectangle() {
        // 6x4 canvas, opaque block x=2..4, y=1..3 (w=3,h=3) plus a stray pixel.
        val w = 6; val h = 4
        val px = ByteBuffer.allocate(w * h * 4)
        fun set(x: Int, y: Int) { px.put((y * w + x) * 4 + 3, 0xFF.toByte()) }
        for (x in 2..4) for (y in 1..3) set(x, y)
        set(0, 0)
        val m = GpuStitcher::class.java.getDeclaredMethod(
            "contentRect", ByteBuffer::class.java, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType,
        ).apply { isAccessible = true }
        val rect = m.invoke(GpuStitcher, px, w, h)
        assertNotNull(rect)
        // Private data class Rect(x, y, w, h): read the fields reflectively.
        val cls = rect.javaClass
        fun field(n: String) = cls.getDeclaredField(n).apply { isAccessible = true }.getInt(rect)
        assertEquals(2, field("x")); assertEquals(1, field("y"))
        assertEquals(3, field("w")); assertEquals(3, field("h"))
    }

    @Test fun contentRectEmptyWhenNothingOpaque() {
        val m = GpuStitcher::class.java.getDeclaredMethod(
            "contentRect", ByteBuffer::class.java, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType,
        ).apply { isAccessible = true }
        assertNull(m.invoke(GpuStitcher, ByteBuffer.allocate(4 * 4 * 4), 4, 4))
    }
}
