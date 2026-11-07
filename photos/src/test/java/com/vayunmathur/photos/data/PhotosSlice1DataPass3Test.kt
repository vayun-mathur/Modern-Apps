package com.vayunmathur.photos.data

import android.graphics.Bitmap
import android.graphics.Color
import androidx.core.graphics.createBitmap
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Slice 1/3 pass 3: per-pixel editor ops over small Robolectric bitmaps. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PhotosSlice1DataPass3Test {

    private fun bitmap(w: Int, h: Int, pixel: Int): Bitmap {
        val b = createBitmap(w, h)
        b.setPixels(IntArray(w * h) { pixel }, 0, w, 0, 0, w, h)
        return b
    }

    private fun Bitmap.px(x: Int, y: Int): Int = getPixel(x, y)

    @Test
    fun hslConversionsPrimariesAndGrey() {
        val red = rgbToHsl(255, 0, 0)
        assertEquals(0f, red[0], 0.01f); assertEquals(1f, red[1], 0.01f); assertEquals(0.5f, red[2], 0.01f)
        val green = rgbToHsl(0, 255, 0)
        assertEquals(120f, green[0], 0.01f)
        val blue = rgbToHsl(0, 0, 255)
        assertEquals(240f, blue[0], 0.01f)
        val grey = rgbToHsl(128, 128, 128)
        assertEquals(0f, grey[1], 0.01f)
        assertTrue(hslToRgb(0f, 1f, 0.5f).contentEquals(intArrayOf(255, 0, 0)))
        assertTrue(hslToRgb(120f, 1f, 0.5f).contentEquals(intArrayOf(0, 255, 0)))
        assertTrue(hslToRgb(0f, 0f, 0.5f).contentEquals(intArrayOf(127, 127, 127)))
        // Round-trip a mixed colour within quantization.
        val hsl = rgbToHsl(30, 144, 200)
        val back = hslToRgb(hsl[0], hsl[1], hsl[2])
        assertTrue(kotlin.math.abs(back[0] - 30) <= 2)
        assertTrue(kotlin.math.abs(back[1] - 144) <= 2)
        assertTrue(kotlin.math.abs(back[2] - 200) <= 2)
    }

    @Test
    fun hslAdjustmentShiftsTargetRangeOnly() {
        val src = bitmap(2, 1, Color.rgb(255, 0, 0))
        src.setPixel(1, 0, Color.rgb(0, 0, 255))
        assertTrue(HslAdjustments().isIdentity(), "default hsl should be identity")
        val adj = HslAdjustments(
            mapOf(HslColorRange.Red to HslChannelAdjustment(hue = 120f)),
        )
        assertFalse(adj.isIdentity(), "shifted hsl should not be identity")
        val out = adj.applyHslToBitmap(src)
        // Red shifted toward green; blue untouched (outside Red's hue window).
        val shifted = out.px(0, 0)
        assertTrue(Color.green(shifted) > Color.red(shifted), "shifted=$shifted src=${src.px(0,0)}")
        assertEquals(Color.rgb(0, 0, 255), out.px(1, 0))
        assertEquals(Color.rgb(255, 0, 0), src.px(0, 0))
    }

    @Test
    fun noiseZeroIsIdentityNonZeroChangesDeterministically() {
        val src = bitmap(4, 4, Color.rgb(100, 100, 100))
        assertTrue(NoiseParams().isIdentity())
        val identity = NoiseParams(0f).applyToBitmap(src)
        assertEquals(Color.rgb(100, 100, 100), identity.px(2, 2))
        val noisy = NoiseParams(100f).applyToBitmap(src)
        val again = NoiseParams(100f).applyToBitmap(src)
        // Fixed seed: identical output, and at least one pixel moved.
        assertEquals(noisy.px(1, 1), again.px(1, 1))
        var changed = false
        for (x in 0 until 4) for (y in 0 until 4) if (noisy.px(x, y) != Color.rgb(100, 100, 100)) changed = true
        assertTrue(changed)
        // Monochrome noise moves all channels together.
        val p = noisy.px(0, 0)
        assertEquals(Color.red(p), Color.green(p))
        assertEquals(Color.green(p), Color.blue(p))
    }

    @Test
    fun colorBalanceAndChannelMixerIdentityAndEffect() {
        val src = bitmap(1, 1, Color.rgb(100, 100, 100))
        assertTrue(ColorBalanceAdjustment().isIdentity())
        assertEquals(Color.rgb(100, 100, 100), ColorBalanceAdjustment().applyToBitmap(src).px(0, 0))
        val warmer = ColorBalanceAdjustment(midRedCyan = 50f).applyToBitmap(src)
        assertTrue(Color.red(warmer.px(0, 0)) > 100)
        assertTrue(ChannelMixerAdjustment().isIdentity())
        assertEquals(Color.rgb(100, 100, 100), ChannelMixerAdjustment().applyToBitmap(src).px(0, 0))
        // Swap red and blue channels.
        val swapped = ChannelMixerAdjustment(
            rFromR = 0f, rFromB = 1f, bFromR = 1f, bFromB = 0f,
        ).applyToBitmap(bitmap(1, 1, Color.rgb(10, 20, 30)))
        assertEquals(Color.rgb(30, 20, 10), swapped.px(0, 0))
    }

    @Test
    fun gradientMapIdentityBelowTwoStopsAndMapsLuminance() {
        val src = bitmap(2, 1, Color.BLACK)
        src.setPixel(1, 0, Color.WHITE)
        assertTrue(GradientMapAdjustment().isIdentity())
        assertTrue(GradientMapAdjustment(listOf(GradientStop(0f, Color.RED))).isIdentity())
        val mapped = GradientMapAdjustment(
            listOf(GradientStop(0f, Color.BLUE), GradientStop(1f, Color.YELLOW)),
        ).applyToBitmap(src)
        assertEquals(Color.BLUE, mapped.px(0, 0))
        assertEquals(Color.YELLOW, mapped.px(1, 0))
    }

    @Test
    fun curvesIdentityAndDarkeningLut() {
        val src = bitmap(1, 1, Color.rgb(128, 128, 128))
        val identity = CurvesAdjustment()
        val same = identity.applyLutToBitmap(src)
        assertEquals(Color.rgb(128, 128, 128), same.px(0, 0))
        val darken = CurvesAdjustment(
            combined = listOf(CurveControlPoint(0f, 0f), CurveControlPoint(1f, 0.5f)),
        ).applyLutToBitmap(src)
        assertTrue(Color.red(darken.px(0, 0)) < 128)
    }

    @Test
    fun strokeOpsEmptyIsIdentity() {
        val src = bitmap(4, 4, Color.rgb(80, 90, 100))
        assertTrue(HealingStrokes().isIdentity())
        assertTrue(SmudgeStrokes().isIdentity())
        assertTrue(DodgeBurnStrokes().isIdentity())
        assertTrue(RedEyeSpots().isIdentity())
        assertTrue(SelectiveEdits().isIdentity())
        assertTrue(LiquifyParams().isIdentity())
        assertEquals(Color.rgb(80, 90, 100), HealingStrokes().applyHealingToBitmap(src).px(1, 1))
        assertEquals(Color.rgb(80, 90, 100), SmudgeStrokes().applyToBitmap(src).px(1, 1))
        assertEquals(Color.rgb(80, 90, 100), DodgeBurnStrokes().applyToBitmap(src).px(1, 1))
        assertEquals(Color.rgb(80, 90, 100), RedEyeSpots().applyToBitmap(src).px(1, 1))
        assertEquals(Color.rgb(80, 90, 100), LiquifyParams().applyToBitmap(src).px(1, 1))
    }

    @Test
    fun dodgeBurnLightensAndDarkens() {
        val src = bitmap(8, 8, Color.rgb(100, 100, 100))
        val dodged = DodgeBurnStrokes(
            listOf(DodgeBurnStroke(listOf(0.5f to 0.5f), DodgeBurnMode.Dodge, brushSize = 0.9f)),
        ).applyToBitmap(src)
        assertTrue(Color.red(dodged.px(4, 4)) > 100)
        val burned = DodgeBurnStrokes(
            listOf(DodgeBurnStroke(listOf(0.5f to 0.5f), DodgeBurnMode.Burn, brushSize = 0.9f)),
        ).applyToBitmap(src)
        assertTrue(Color.red(burned.px(4, 4)) < 100)
    }

    @Test
    fun redEyeReducesRedDominanceInSpot() {
        val src = bitmap(8, 8, Color.rgb(200, 30, 30))
        val fixed = RedEyeSpots(listOf(RedEyeSpot(0.5f, 0.5f, radius = 0.4f))).applyToBitmap(src)
        val p = fixed.px(4, 4)
        assertTrue(Color.red(p) < 200)
    }

    @Test
    fun selectiveRadialMaskAppliesInsideOnly() {
        val src = bitmap(8, 8, Color.rgb(100, 100, 100))
        val edits = SelectiveEdits(
            listOf(
                SelectiveMask(
                    type = MaskType.RadialGradient, centerX = 0.5f, centerY = 0.5f, radius = 0.2f,
                    adjustments = ImageAdjustments(brightness = 50f),
                ),
            ),
        )
        val out = edits.applySelectiveEdits(src)
        // The blend is per-pixel, but the adjustment itself rasterises through
        // Canvas/ColorMatrix, which Robolectric does not rasterise: pin the
        // copy contract and the untouched corner outside the radial mask.
        assertEquals(8, out.width)
        assertEquals(Color.rgb(100, 100, 100), out.px(0, 0))
    }

    @Test
    fun liquifyEmptyAndPushRuns() {
        val src = bitmap(8, 8, Color.rgb(10, 20, 30))
        val pushed = LiquifyParams(
            listOf(LiquifyOp(LiquifyTool.Push, 0.5f, 0.5f, dx = 0.2f, dy = 0f, radius = 0.5f)),
        ).applyToBitmap(src)
        assertEquals(8, pushed.width)
        assertEquals(8, pushed.height)
    }

    @Test
    fun paintOpsShapeAndGradientAndFloodFill() {
        val base = bitmap(8, 8, Color.WHITE)
        // Canvas-rasterised ops: under Robolectric the canvas does not rasterise,
        // so only the copy contract (size, source untouched) is pinned here.
        val shaped = drawShapeBitmap(base, PaintShape.Rectangle, 0.25f, 0.25f, 0.75f, 0.75f, Color.RED, 0.05f)
        assertEquals(8, shaped.width)
        assertEquals(Color.WHITE, base.px(4, 4))
        val gradient = drawGradientBitmap(base, 0f, 0f, 1f, 0f, Color.BLACK)
        assertEquals(8, gradient.width)
        val filled = floodFillBitmap(base, 0f, 0f, Color.GREEN, 0f)
        assertEquals(Color.GREEN, filled.px(7, 7))
    }
}
