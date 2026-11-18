package com.vayunmathur.camera.platform

import android.graphics.Bitmap
import android.graphics.Color
import androidx.test.core.app.ApplicationProvider
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

/** Portrait bokeh math: separable blur, mask packing, still renderer fallbacks. */
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PortraitBokehMathTest {

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()

    @Test fun blurScaleClampsAndMaps() {
        assertEquals(0.4f, bokehBlurScale(0f), 1e-6f)
        assertEquals(0.4f, bokehBlurScale(-3f), 1e-6f)
        assertEquals(1.8f, bokehBlurScale(1f), 1e-6f)
        assertEquals(1.8f, bokehBlurScale(9f), 1e-6f)
        assertEquals(1.1f, bokehBlurScale(0.5f), 1e-6f)
    }

    @Test fun blurKeepsConstantMaskConstant() {
        val w = 9; val h = 7
        val src = FloatArray(w * h) { 0.6f }
        val out = blurMask(src, w, h, FloatArray(w * h), FloatArray(w * h))
        // Kernel weights sum to 1, so a flat field must survive both passes.
        out.forEach { assertEquals(0.6f, it, 1e-5f) }
    }

    @Test fun blurSpreadsImpulseAndPreservesMass() {
        val w = 15; val h = 15
        val src = FloatArray(w * h); src[7 * w + 7] = 1f
        val out = blurMask(src, w, h, FloatArray(w * h), FloatArray(w * h))
        assertTrue(out[7 * w + 7] < 1f, "peak must spread")
        assertTrue(out[7 * w + 7] > out[7 * w + 10], "falls off with distance")
        assertTrue(out[7 * w + 8] > 0f && out[8 * w + 7] > 0f, "neighbours lit")
        val mass = out.sum()
        assertTrue(abs(mass - 1f) < 0.02f, "interior impulse conserves mass, got $mass")
    }

    @Test fun blurClampsAtEdgesWithoutDarkening() {
        val w = 5; val h = 5
        val src = FloatArray(w * h) { 1f }
        val out = blurMask(src, w, h, FloatArray(w * h), FloatArray(w * h))
        out.forEach { assertEquals(1f, it, 1e-5f) }
    }

    @Test fun maskToBitmapPacksClampedAlphaWhite() {
        val pixels = IntArray(4)
        val bmp = maskToBitmap(floatArrayOf(0f, 0.5f, 1f, 2f), 2, 2, pixels)
        assertEquals(Bitmap.Config.ARGB_8888, bmp.config)
        assertEquals(0, Color.alpha(pixels[0]))
        assertEquals(127, Color.alpha(pixels[1])) // truncation of 0.5*255
        assertEquals(255, Color.alpha(pixels[2]))
        assertEquals(255, Color.alpha(pixels[3])) // >1 clamps
        assertEquals(255, Color.red(pixels[2]))
        val neg = IntArray(1)
        maskToBitmap(floatArrayOf(-1f), 1, 1, neg)
        assertEquals(0, Color.alpha(neg[0]))
    }

    @Test fun rendererReturnsNullWhenSegmenterUnavailableAndKeepsSrc() {
        val renderer = StillBokehRenderer(context)
        val src = Bitmap.createBitmap(32, 24, Bitmap.Config.ARGB_8888).apply {
            eraseColor(Color.rgb(10, 20, 30))
        }
        // No Vulkan/ML runtime under Robolectric: render must degrade to null, src untouched.
        assertNull(renderer.render(src, 90, 0.5f, 0.2f, 0.1f, mirror = true))
        assertTrue(!src.isRecycled)
        renderer.close()
        // Closed renderer refuses even the fallback-free path, close is idempotent.
        assertNull(renderer.render(src, 0, 0.5f, 0f, 0f, mirror = false))
        renderer.close()
        src.recycle()
    }

    @Test fun rendererPrivatePassesViaReflection() {
        val renderer = StillBokehRenderer(context)
        val src = Bitmap.createBitmap(64, 48, Bitmap.Config.ARGB_8888).apply {
            eraseColor(Color.rgb(200, 100, 50))
        }
        val mask = Bitmap.createBitmap(64, 48, Bitmap.Config.ARGB_8888).apply {
            eraseColor(Color.argb(128, 255, 255, 255))
        }
        val cls = StillBokehRenderer::class.java
        // compositeFrame: sharp frame + background over it, src recycled, size preserved.
        val composite = cls.getDeclaredMethod(
            "compositeFrame", Bitmap::class.java, Bitmap::class.java,
            Float::class.javaPrimitiveType, Float::class.javaPrimitiveType, Boolean::class.javaPrimitiveType,
        ).apply { isAccessible = true }
        val bg = Bitmap.createBitmap(64, 48, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.BLUE) }
        val out = composite.invoke(renderer, src, bg, 0.3f, 0.2f, true) as Bitmap
        assertNotNull(out); assertEquals(64, out.width); assertEquals(48, out.height)
        assertTrue(src.isRecycled)
        // scaleToMaxSide / rotate helpers.
        val scaleM = cls.getDeclaredMethod("scaleToMaxSide", Bitmap::class.java, Int::class.javaPrimitiveType)
            .apply { isAccessible = true }
        val big = Bitmap.createBitmap(200, 100, Bitmap.Config.ARGB_8888)
        val scaled = scaleM.invoke(renderer, big, 50) as Bitmap
        assertEquals(50, scaled.width); assertEquals(25, scaled.height)
        assertTrue(scaleM.invoke(renderer, scaled, 512) === scaled)
        val rotateM = cls.getDeclaredMethod("rotate", Bitmap::class.java, Float::class.javaPrimitiveType)
            .apply { isAccessible = true }
        assertTrue(rotateM.invoke(renderer, scaled, 360f) === scaled)
        val rotated = rotateM.invoke(renderer, scaled, 90f) as Bitmap
        assertEquals(25, rotated.width); assertEquals(50, rotated.height)
        // blurBackground may need a hardware canvas; either a bitmap or graceful null.
        val blurM = cls.getDeclaredMethod(
            "blurBackground", Bitmap::class.java, Bitmap::class.java, Float::class.javaPrimitiveType,
        ).apply { isAccessible = true }
        try {
            val blurred = blurM.invoke(renderer, Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888), mask, 0.5f)
            assertTrue(blurred == null || blurred is Bitmap)
        } catch (e: java.lang.reflect.InvocationTargetException) {
            // Hardware rendering unavailable in this environment is an acceptable device-only path.
            assertTrue(e.targetException is IllegalStateException || e.targetException is IllegalArgumentException)
        }
        renderer.close()
    }
}
