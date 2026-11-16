package com.vayunmathur.photos.data

import android.graphics.Bitmap
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Every [LayerAdjustment] subtype applied to a real bitmap: identity
 * checks hold at defaults, and non-identity parameters actually move
 * pixels (each result is a same-sized bitmap with changed content).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AdjustmentBitmapsRealTest {

    private fun colorful(): Bitmap {
        val bmp = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888)
        for (x in 0 until 16) for (y in 0 until 16) {
            bmp.setPixel(x, y, android.graphics.Color.rgb(x * 16, y * 16, (x + y) * 8))
        }
        // A hard edge so sharpen/blur-family adjustments have signal.
        for (x in 8 until 16) for (y in 0 until 16) {
            bmp.setPixel(x, y, android.graphics.Color.rgb(240, 240, 240))
        }
        return bmp
    }

    private fun pixels(bmp: Bitmap): List<Int> =
        (0 until 16).flatMap { y -> (0 until 16).map { x -> bmp.getPixel(x, y) } }

    private fun assertApplies(adj: LayerAdjustment) {
        val src = colorful()
        val before = pixels(src)
        val out = adj.applyToBitmap(src)
        assertEquals(16, out.width)
        assertEquals(16, out.height)
        assertTrue(pixels(out) != before, "${adj.label} should change pixels")
        assertFalse(adj.isIdentity())
    }

    @Test fun everyAdjustmentApplies() {
        assertApplies(BasicAdjustment(ImageAdjustments(brightness = 0.3f, contrast = 0.2f, saturation = 0.4f)))
        assertApplies(CurvesAdj(CurvesAdjustment(combined = listOf(
            CurveControlPoint(0f, 0f), CurveControlPoint(0.5f, 0.8f), CurveControlPoint(1f, 1f),
        ))))
        assertApplies(HslAdj(HslAdjustments(channels = HslColorRange.entries.associateWith {
            HslChannelAdjustment(hue = 20f, saturation = 0.5f, luminance = 0.1f)
        })))
        assertApplies(LevelsAdj(LevelsAdjustment(inBlack = 20f, inWhite = 230f, gamma = 1.4f)))
        assertApplies(ColorBalanceAdj(ColorBalanceAdjustment(midRedCyan = 0.4f, midGreenMagenta = -0.2f, midBlueYellow = 0.3f, shadowsRedCyan = 0.2f)))
        assertApplies(ChannelMixerAdj(ChannelMixerAdjustment(rFromR = 0.5f, rFromG = 0.5f, gFromG = 0.6f, gFromB = 0.4f)))
        assertApplies(GradientMapAdj(GradientMapAdjustment(stops = listOf(
            GradientStop(0f, 0xFF000000.toInt()), GradientStop(1f, 0xFFFFFFFF.toInt()),
        ))))
        assertApplies(BlurAdj(BlurParams(radius = 0.5f, intensity = 20f)))
        assertApplies(VibranceAdj(amount = 90f))
        // Selective color weights by HSV hue; Robolectric's Color.RGBToHSV
        // shadow reports zero saturation, so only the non-identity contract
        // is asserted here (pixel math runs on device).
        val selective = SelectiveColorAdj(range = SelectiveColorRange.Reds, cyan = 50f, magenta = -30f, yellow = 20f)
        assertFalse(selective.isIdentity())
        assertEquals(16, selective.applyToBitmap(colorful()).width)
        assertFalse(SelectiveColorAdj(range = SelectiveColorRange.Blues, cyan = -40f).isIdentity())
        assertApplies(FilterBlurAdj(FilterBlur(amount = 3f)))
        assertApplies(UnsharpAdj(UnsharpMask(amount = 0.8f, radius = 2f, threshold = 5)))
        assertApplies(NoiseAdj(NoiseParams(amount = 80f)))
        assertApplies(StylizeAdj(StylizeParams(mode = StylizeMode.FindEdges)))
        assertApplies(StylizeAdj(StylizeParams(mode = StylizeMode.Emboss)))
    }

    @Test fun identityAtDefaults() {
        val src = colorful()
        assertTrue(BasicAdjustment().isIdentity())
        assertTrue(VibranceAdj().isIdentity())
        assertTrue(SelectiveColorAdj().isIdentity())
        // Default basic adjustment leaves pixels untouched.
        assertEquals(pixels(src), pixels(BasicAdjustment().applyToBitmap(src)))
    }
}
