package com.vayunmathur.camera.platform

import android.graphics.Bitmap
import android.graphics.Color
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** NightCaptureEngine: brightening curve + burst-selection fallbacks. */
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class NightCaptureEngineMathTest {

    private fun brighten(v: Float): Int {
        val m = NightCaptureEngine::class.java.getDeclaredMethod(
            "brightenChannel", Float::class.javaPrimitiveType,
        ).apply { isAccessible = true }
        return m.invoke(NightCaptureEngine, v) as Int
    }

    @Test fun brightenCurveEndpointsAndMidtone() {
        assertEquals(18, brighten(0f)) // pure shadow lift at black
        assertEquals(255, brighten(255f)) // highlight knee clamps, never wraps
        assertEquals(158, brighten(100f)) // tapered gain + weighted lift (hand-computed)
        // Monotonic and never below the input through the shadow/mid range.
        var prev = -1
        for (v in 0..250 step 10) {
            val out = brighten(v.toFloat())
            assertTrue(out >= prev, "monotonic at $v")
            assertTrue(out in 0..255)
            prev = out
        }
    }

    @Test fun mergeEmptyAndSingleFrame() = runBlocking {
        assertNull(NightCaptureEngine.merge(emptyList()))
        val src = Bitmap.createBitmap(4, 3, Bitmap.Config.ARGB_8888)
        val px = IntArray(12) { Color.argb(255, 100, 0, 255) }
        src.setPixels(px, 0, 4, 0, 0, 4, 3)
        val out = NightCaptureEngine.merge(listOf(src))
        assertNotNull(out)
        assertEquals(4, out.width); assertEquals(3, out.height)
        val p = out.getPixel(0, 0)
        assertEquals(255, Color.alpha(p))
        assertEquals(brighten(100f), Color.red(p))
        assertEquals(brighten(0f), Color.green(p))
        assertEquals(brighten(255f), Color.blue(p))
    }

    @Test fun mergeMismatchedSizesFallsBackToOneFrame() = runBlocking {
        val a = Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.BLACK) }
        val b = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.BLACK) }
        // Fewer than 2 same-sized frames: single-frame path, output matches first size group.
        val out = NightCaptureEngine.merge(listOf(a, b))
        assertNotNull(out)
        assertEquals(4, out.width)
    }

    @Test fun stitchableSizeGuard() {
        val m = NightCaptureEngine::class.java.getDeclaredMethod(
            "isStitchableSize", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType,
        ).apply { isAccessible = true }
        assertEquals(true, m.invoke(NightCaptureEngine, 100, 100))
        assertEquals(false, m.invoke(NightCaptureEngine, 0, 100))
        assertEquals(false, m.invoke(NightCaptureEngine, 100, -1))
        assertEquals(false, m.invoke(NightCaptureEngine, 20001, 10))
        assertEquals(true, m.invoke(NightCaptureEngine, 20000, 20000))
    }
}
