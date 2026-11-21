package com.vayunmathur.photos.data

import android.graphics.Bitmap
import com.vayunmathur.photos.platform.LayerCompositor
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Zero-copy census #3: every [LayerAdjustment] must produce pixel-identical
 * output through the new IntArray entry point ([LayerAdjustment.applyToPixels],
 * used by LayerCompositor on its own buffers) and through the legacy bitmap
 * entry point ([LayerAdjustment.applyToBitmap], kept for other callers):
 * same ints in -> same ints out, adjustment by adjustment.
 *
 * Runs in the native quarantine task (see photos/build.gradle.kts): the
 * Blur/Unsharp/Stylize/FilterBlur cases execute the PhotosNative JNI bridge.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AdjustmentPixelsEqualityTest {

    /**
     * Deterministic opaque ARGB pattern at [w] x [h]. Opaque only: a Bitmap
     * stores premultiplied pixels, so the legacy path destroys RGB under
     * alpha < 255 before any math runs (setPixels/getPixels round-trip) and
     * no array conversion can reproduce that loss bit-exactly — equality is
     * therefore asserted on the opaque pixels photo backdrops consist of.
     */
    private fun pattern(w: Int, h: Int): IntArray {
        var seed = 0x12345678
        fun next(): Int {
            seed = (seed * 1103515245 + 12345) and 0x7fffffff
            return (seed shr 8) and 0xFF
        }
        return IntArray(w * h) {
            (0xFF shl 24) or (next() shl 16) or (next() shl 8) or next()
        }
    }

    private fun bitmapOf(pixels: IntArray, w: Int, h: Int): Bitmap {
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        bmp.setPixels(pixels, 0, w, 0, 0, w, h)
        return bmp
    }

    private fun intsOf(bitmap: Bitmap): IntArray {
        val out = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(out, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        return out
    }

    private fun assertPixelIdentical(label: String, adj: LayerAdjustment, w: Int, h: Int) {
        val input = pattern(w, h)
        val expected = intsOf(adj.applyToBitmap(bitmapOf(input.copyOf(), w, h)))
        val working = input.copyOf()
        val result = adj.applyToPixels(working, w, h)
        assertNotNull(result, "$label: applyToPixels returned null")
        assertEquals(w * h, result.size, "$label: result size")
        assertTrue(
            expected.contentEquals(result),
            "$label: pixel path differs from bitmap path at ${w}x$h " +
                "(first diff index ${expected.indices.firstOrNull { expected[it] != result[it] }})",
        )
    }

    private fun assertAllSizes(label: String, adj: LayerAdjustment) {
        assertPixelIdentical(label, adj, 16, 16)
        assertPixelIdentical(label, adj, 24, 18)
        assertPixelIdentical(label, adj, 13, 11)
    }

    /**
     * Like [assertPixelIdentical], but compares after one identical Bitmap
     * storage round-trip on the pixel-path result. Needed for the native
     * gaussian-family blurs: their kernel lets alpha drift (255 -> 254), and
     * the legacy path's `setPixels` then premultiplies RGB by that alpha,
     * losing up to 1 level on a few pixels — the raw native ints are the
     * exact ones, the bitmap-stored form (the only form bitmap callers ever
     * see, and the form the compositor finally produces) is bit-identical.
     */
    private fun assertPixelIdenticalViaBitmapStorage(label: String, adj: LayerAdjustment, w: Int, h: Int) {
        val input = pattern(w, h)
        val expected = intsOf(adj.applyToBitmap(bitmapOf(input.copyOf(), w, h)))
        val working = input.copyOf()
        val result = adj.applyToPixels(working, w, h)
        assertNotNull(result, "$label: applyToPixels returned null")
        val stored = intsOf(bitmapOf(result.copyOf(), w, h))
        assertTrue(
            expected.contentEquals(stored),
            "$label: bitmap-stored pixel path differs from bitmap path at ${w}x$h",
        )
    }

    @Test fun everyAdjustmentIsPixelIdentical() {
        assertAllSizes("basic-matrix", BasicAdjustment(ImageAdjustments(
            brightness = 20f, contrast = 15f, saturation = 30f, warmth = 10f,
            exposure = 12f, highlights = 18f, shadows = -8f, fade = 15f, tint = -12f,
        )))
        assertAllSizes("basic-sharpen", BasicAdjustment(ImageAdjustments(sharpness = 60f)))
        assertAllSizes("basic-grain", BasicAdjustment(ImageAdjustments(grain = 55f)))
        assertAllSizes("basic-matrix-sharpen-grain", BasicAdjustment(ImageAdjustments(
            contrast = 20f, saturation = -25f, sharpness = 45f, grain = 30f,
        )))
        assertAllSizes("curves", CurvesAdj(CurvesAdjustment(
            combined = listOf(
                CurveControlPoint(0f, 0f), CurveControlPoint(0.5f, 0.8f), CurveControlPoint(1f, 1f),
            ),
            red = listOf(
                CurveControlPoint(0f, 0.1f), CurveControlPoint(1f, 0.9f),
            ),
        )))
        assertAllSizes("hsl", HslAdj(HslAdjustments(channels = HslColorRange.entries.associateWith {
            HslChannelAdjustment(hue = 20f, saturation = 0.5f, luminance = 0.1f)
        })))
        assertAllSizes("levels", LevelsAdj(LevelsAdjustment(inBlack = 20f, inWhite = 230f, gamma = 1.4f)))
        assertAllSizes("color-balance", ColorBalanceAdj(ColorBalanceAdjustment(
            midRedCyan = 0.4f, midGreenMagenta = -0.2f, midBlueYellow = 0.3f, shadowsRedCyan = 0.2f,
        )))
        assertAllSizes("channel-mixer", ChannelMixerAdj(ChannelMixerAdjustment(
            rFromR = 0.5f, rFromG = 0.5f, gFromG = 0.6f, gFromB = 0.4f,
        )))
        assertAllSizes("channel-mixer-mono", ChannelMixerAdj(ChannelMixerAdjustment(
            rFromR = 0.3f, rFromG = 0.6f, rFromB = 0.1f, monochrome = true,
        )))
        assertAllSizes("gradient-map", GradientMapAdj(GradientMapAdjustment(stops = listOf(
            GradientStop(0f, 0xFF102040.toInt()), GradientStop(0.5f, 0xFF808080.toInt()),
            GradientStop(1f, 0xFFFFE0B0.toInt()),
        ))))
        assertAllSizes("black-and-white", BlackAndWhiteAdj(BlackAndWhiteAdjustment(enabled = true)))
        assertAllSizes("black-and-white-tint", BlackAndWhiteAdj(BlackAndWhiteAdjustment(
            enabled = true, tint = 0xFFD0A060.toInt(),
        )))
        // Native gaussian-family blurs: alpha drift makes the legacy bitmap
        // path lossy at raw-int level; equality holds at bitmap storage.
        for ((w, h) in listOf(16 to 16, 24 to 18, 13 to 11)) {
            assertPixelIdenticalViaBitmapStorage("blur", BlurAdj(BlurParams(radius = 0.5f, intensity = 20f)), w, h)
        }
        assertAllSizes("selective", SelectiveAdj(SelectiveEdits(masks = listOf(
            SelectiveMask(
                type = MaskType.RadialGradient, centerX = 0.5f, centerY = 0.5f, radius = 0.4f,
                adjustments = ImageAdjustments(brightness = 25f, saturation = 20f),
            ),
            SelectiveMask(
                type = MaskType.Brush,
                brushPoints = listOf(0.25f to 0.25f, 0.75f to 0.6f),
                adjustments = ImageAdjustments(contrast = 30f, sharpness = 40f),
            ),
        ))))
        assertAllSizes("invert", InvertAdj())
        assertAllSizes("posterize", PosterizeAdj(levels = 5))
        assertAllSizes("threshold", ThresholdAdj(level = 100))
        assertAllSizes("vibrance", VibranceAdj(amount = 70f))
        assertAllSizes("photo-filter", PhotoFilterAdj(density = 0.4f))
        assertAllSizes("selective-color", SelectiveColorAdj(
            range = SelectiveColorRange.Reds, cyan = 50f, magenta = -30f, yellow = 20f,
        ))
        assertAllSizes("selective-color-neutrals", SelectiveColorAdj(
            range = SelectiveColorRange.Neutrals, cyan = -40f, yellow = 35f,
        ))
        for ((w, h) in listOf(16 to 16, 24 to 18, 13 to 11)) {
            assertPixelIdenticalViaBitmapStorage("filter-blur", FilterBlurAdj(FilterBlur(amount = 3f)), w, h)
        }
        assertAllSizes("unsharp", UnsharpAdj(UnsharpMask(amount = 0.8f, radius = 2f, threshold = 5)))
        assertAllSizes("noise-mono", NoiseAdj(NoiseParams(amount = 60f, monochrome = true)))
        assertAllSizes("noise-color", NoiseAdj(NoiseParams(amount = 60f, monochrome = false)))
        assertAllSizes("stylize-edges", StylizeAdj(StylizeParams(mode = StylizeMode.FindEdges)))
        assertAllSizes("stylize-emboss", StylizeAdj(StylizeParams(mode = StylizeMode.Emboss)))
    }

    @Test fun pixelPathReusesCallerBuffer() {
        // Per-pixel ops mutate and return the caller's own array (no new
        // IntArray, no bitmap round-trip); native ops write back into it too.
        val input = pattern(16, 16)
        val hsl = HslAdj(HslAdjustments(channels = HslColorRange.entries.associateWith {
            HslChannelAdjustment(hue = 15f)
        }))
        assertSame(input, hsl.applyToPixels(input, 16, 16))
        val native = pattern(16, 16)
        val stylize = StylizeAdj(StylizeParams(mode = StylizeMode.Emboss))
        assertSame(native, stylize.applyToPixels(native, 16, 16))
    }

    @Test fun vignetteHasNoPixelPathAndCompositorFallsBack() {
        // Vignette draws a RadialGradient through Skia; per-pixel float math
        // is off by one on ~30-55% of pixels, so it must refuse the array
        // path (null) instead of silently changing output.
        assertNull(
            BasicAdjustment(ImageAdjustments(vignette = 50f)).applyToPixels(pattern(16, 16), 16, 16),
        )
        assertNull(
            SelectiveAdj(SelectiveEdits(masks = listOf(
                SelectiveMask(adjustments = ImageAdjustments(vignette = 40f)),
            ))).applyToPixels(pattern(16, 16), 16, 16),
        )
        // Through the compositor the fallback still renders pixel-identically
        // to the plain bitmap path (opaque base, Normal blend at opacity 1).
        val w = 16
        val h = 16
        val base = pattern(w, h).map { it or (0xFF shl 24) }.toIntArray()
        val adj = BasicAdjustment(ImageAdjustments(vignette = 50f, brightness = 10f))
        val expected = intsOf(adj.applyToBitmap(bitmapOf(base.copyOf(), w, h)))
        val doc = EditDocument(canvasWidth = w, canvasHeight = h)
            .addLayer(PixelLayer(bitmapRef = BitmapReference(bitmapOf(base.copyOf(), w, h)), name = "base"))
            .addLayer(AdjustmentLayer(adjustment = adj, name = "vignette"))
        val out = intsOf(LayerCompositor().composite(doc))
        assertTrue(expected.contentEquals(out), "compositor vignette fallback differs from bitmap path")
    }

    @Test fun compositorAdjustmentMatchesBitmapPath() {
        // An adjustment layer composited by LayerCompositor (array path) over
        // an opaque base at Normal/opacity 1 equals applying the same
        // adjustment to the base bitmap directly.
        val w = 24
        val h = 18
        val base = pattern(w, h).map { it or (0xFF shl 24) }.toIntArray()
        val adjustments = listOf(
            BasicAdjustment(ImageAdjustments(brightness = 18f, contrast = 12f, saturation = 22f)),
            HslAdj(HslAdjustments(channels = HslColorRange.entries.associateWith {
                HslChannelAdjustment(hue = -25f, saturation = 0.3f)
            })),
            LevelsAdj(LevelsAdjustment(inBlack = 15f, gamma = 1.3f)),
            StylizeAdj(StylizeParams(mode = StylizeMode.FindEdges)),
        )
        for (adj in adjustments) {
            val expected = intsOf(adj.applyToBitmap(bitmapOf(base.copyOf(), w, h)))
            val doc = EditDocument(canvasWidth = w, canvasHeight = h)
                .addLayer(PixelLayer(bitmapRef = BitmapReference(bitmapOf(base.copyOf(), w, h)), name = "base"))
                .addLayer(AdjustmentLayer(adjustment = adj, name = adj.label))
            val out = intsOf(LayerCompositor().composite(doc))
            assertTrue(expected.contentEquals(out), "${adj.label}: compositor differs from bitmap path")
            val preview = intsOf(LayerCompositor().compositePreview(doc, 1024))
            assertTrue(expected.contentEquals(preview), "${adj.label}: preview differs from bitmap path")
        }
    }
}
