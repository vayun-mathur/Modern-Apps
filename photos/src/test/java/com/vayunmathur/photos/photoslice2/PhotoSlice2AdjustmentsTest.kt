package com.vayunmathur.photos.photoslice2

import android.graphics.Bitmap
import android.graphics.ColorMatrix
import androidx.core.graphics.createBitmap
import com.vayunmathur.photos.data.ImageAdjustments
import com.vayunmathur.photos.data.applyPixelEffects
import com.vayunmathur.photos.data.applyToBitmap
import com.vayunmathur.photos.data.hasPixelEffects
import com.vayunmathur.photos.data.mapPixels
import com.vayunmathur.photos.data.toColorMatrix
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Slice-2 tests for [ImageAdjustments]: color-matrix composition per slider
 * (asserted on the matrix arrays — pure Java), bitmap application for the
 * paths Robolectric executes faithfully (identity fast-path, pixel effects
 * via getPixels/setPixels), and the [mapPixels] helper.
 *
 * NOTE: `Canvas.drawBitmap` with a `ColorMatrixColorFilter` is a native
 * pipeline Robolectric shadows as a no-op (verified: rendered pixels come
 * back unfiltered), so matrix-driven rendering is asserted on
 * [toColorMatrix] arrays instead. The matrix math IS the behavior under
 * test; the Canvas path is covered on the emulator.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PhotoSlice2AdjustmentsTest {

    private fun solidBitmap(color: Int, w: Int = 8, h: Int = 8): Bitmap {
        val bmp = createBitmap(w, h)
        bmp.eraseColor(color)
        return bmp
    }

    private fun channelOf(px: Int, shift: Int): Int = (px shr shift) and 0xFF

    private fun identityArray(): List<Float> = ColorMatrix().array.toList()

    @Test
    fun identityAdjustmentsAreNoOps() {
        val adj = ImageAdjustments()
        assertFalse(adj.hasPixelEffects())
        assertEquals(identityArray(), adj.toColorMatrix().array.toList())
        // Bitmap unchanged via the identity fast path (no Canvas involved).
        val bmp = solidBitmap(0xFF445566.toInt())
        val out = adj.applyToBitmap(bmp)
        assertEquals(bmp.getPixel(0, 0), out.getPixel(0, 0))
    }

    @Test
    fun brightnessMatrixOffsetsChannels() {
        // brightness=100 -> v = 128 added to R/G/B rows.
        val m = ImageAdjustments(brightness = 100f).toColorMatrix().array
        assertEquals(128f, m[4], "R offset")
        assertEquals(128f, m[9], "G offset")
        assertEquals(128f, m[14], "B offset")
        assertEquals(1f, m[0])
        // Zero brightness is the identity.
        assertEquals(identityArray(), ImageAdjustments(brightness = 0f).toColorMatrix().array.toList())
    }

    @Test
    fun contrastMatrixScalesAroundMid() {
        // contrast=100 -> s=2, t=(-0.5*2+0.5)*255 = -127.5.
        val m = ImageAdjustments(contrast = 100f).toColorMatrix().array
        assertEquals(2f, m[0])
        assertEquals(-127.5f, m[4])
        assertEquals(identityArray(), ImageAdjustments(contrast = 0f).toColorMatrix().array.toList())
    }

    @Test
    fun saturationMatrixDesaturatesToGray() {
        // saturation=-100 -> setSaturation(0): all rows equal the luminance weights.
        val m = ImageAdjustments(saturation = -100f).toColorMatrix().array
        // R row ~= (0.213, 0.715, 0.072) — Android's setSaturation(0) weights.
        assertTrue(abs(m[0] - 0.213f) < 0.01f, "R weight ${m[0]}")
        assertTrue(abs(m[1] - 0.715f) < 0.01f, "G weight ${m[1]}")
        assertTrue(abs(m[2] - 0.072f) < 0.01f, "B weight ${m[2]}")
    }

    @Test
    fun warmthMatrixPushesRedPullsBlue() {
        // warmth=100 -> w=30: R+30, B-30.
        val m = ImageAdjustments(warmth = 100f).toColorMatrix().array
        assertEquals(30f, m[4])
        assertEquals(-30f, m[14])
        assertEquals(0f, m[9])
    }

    @Test
    fun exposureMatrixScalesGain() {
        // exposure=100 -> 2^1 = 2x diagonal.
        val m = ImageAdjustments(exposure = 100f).toColorMatrix().array
        assertEquals(2f, m[0])
        assertEquals(2f, m[6])
        assertEquals(2f, m[12])
        assertEquals(1f, m[18])
    }

    @Test
    fun combinedMatricesCompose() {
        val both = ImageAdjustments(brightness = 20f, contrast = 20f).toColorMatrix()
        val justBright = ImageAdjustments(brightness = 20f).toColorMatrix()
        assertFalse(both.array.toList() == justBright.array.toList())
        // Highlights/shadows/fade/tint each contribute.
        for (adj in listOf(
            ImageAdjustments(highlights = 30f),
            ImageAdjustments(shadows = 30f),
            ImageAdjustments(fade = 30f),
            ImageAdjustments(tint = 30f),
        )) {
            assertFalse(
                adj.toColorMatrix().array.toList() == identityArray(),
                "matrix for $adj",
            )
        }
    }

    @Test
    fun highlightsShadowsFadeTintValues() {
        // highlights=100: h=0.3, offset=12.
        val h = ImageAdjustments(highlights = 100f).toColorMatrix().array
        assertEquals(1.3f, h[0])
        assertEquals(12f, h[4])
        // shadows=100: +40 offset.
        val s = ImageAdjustments(shadows = 100f).toColorMatrix().array
        assertEquals(40f, s[4])
        // fade=100: scale 0.6, offset 51.2.
        val f = ImageAdjustments(fade = 100f).toColorMatrix().array
        assertTrue(abs(f[0] - 0.6f) < 0.001f)
        assertTrue(abs(f[4] - 51.2f) < 0.01f)
        // tint=100: t=25, R/B -12.5, G +25.
        val t = ImageAdjustments(tint = 100f).toColorMatrix().array
        assertEquals(-12.5f, t[4])
        assertEquals(25f, t[9])
        assertEquals(-12.5f, t[14])
    }

    @Test
    fun pixelEffectsFlags() {
        assertFalse(ImageAdjustments().hasPixelEffects())
        assertTrue(ImageAdjustments(sharpness = 50f).hasPixelEffects())
        assertTrue(ImageAdjustments(vignette = 50f).hasPixelEffects())
        assertTrue(ImageAdjustments(grain = 50f).hasPixelEffects())
        // Negative sharpness still flags (guard path in applyPixelEffects).
        assertTrue(ImageAdjustments(sharpness = -10f).hasPixelEffects())
    }

    @Test
    fun grainIsDeterministicAndBounded() {
        val bmp = solidBitmap(0xFF808080.toInt(), 16, 16)
        val a = ImageAdjustments(grain = 100f).applyPixelEffects(bmp.copy(Bitmap.Config.ARGB_8888, true))
        val b = ImageAdjustments(grain = 100f).applyPixelEffects(bmp.copy(Bitmap.Config.ARGB_8888, true))
        // Seeded RNG (42): identical outputs.
        var same = 0
        for (y in 0 until 16) for (x in 0 until 16) {
            if (a.getPixel(x, y) == b.getPixel(x, y)) same++
        }
        assertEquals(256, same)
        // Grain actually perturbs most pixels.
        var changed = 0
        for (y in 0 until 16) for (x in 0 until 16) {
            if (a.getPixel(x, y) != bmp.getPixel(x, y)) changed++
        }
        assertTrue(changed > 200, "grain changed=$changed")
    }

    @Test
    fun vignetteDarkensCorners() {
        val bmp = solidBitmap(0xFFFFFFFF.toInt(), 32, 32)
        val out = ImageAdjustments(vignette = 100f).applyPixelEffects(bmp.copy(Bitmap.Config.ARGB_8888, true))
        val center = channelOf(out.getPixel(16, 16), 16)
        val corner = channelOf(out.getPixel(0, 0), 16)
        assertTrue(corner < center, "corner=$corner center=$center")
    }

    @Test
    fun sharpenUniformImageIsStable() {
        // Uniform image: sharpen kernel sums to ~1, output ~= input.
        val bmp = solidBitmap(0xFF808080.toInt(), 8, 8)
        val out = ImageAdjustments(sharpness = 100f).applyPixelEffects(bmp.copy(Bitmap.Config.ARGB_8888, true))
        val r = channelOf(out.getPixel(4, 4), 16)
        assertTrue(abs(r - 0x80) <= 4, "sharpened uniform=$r")
    }

    @Test
    fun mapPixelsTransformsInPlace() {
        val bmp = solidBitmap(0xFF010203.toInt(), 2, 2)
        bmp.mapPixels { it or 0x00F00000 }
        assertEquals(0xFFF10203.toInt(), bmp.getPixel(0, 0))
        assertEquals(0xFFF10203.toInt(), bmp.getPixel(1, 1))
    }

    @Test
    fun applyToBitmapWithPixelEffects() {
        // Combined path: matrix (Canvas no-op under Robolectric) + grain (real).
        val bmp = solidBitmap(0xFF707070.toInt(), 8, 8)
        val out = ImageAdjustments(brightness = 20f, grain = 40f).applyToBitmap(bmp)
        assertEquals(8, out.width)
        assertEquals(8, out.height)
    }
}
