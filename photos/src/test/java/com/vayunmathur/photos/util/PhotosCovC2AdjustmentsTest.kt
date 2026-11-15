package com.vayunmathur.photos.util

import android.graphics.Bitmap
import android.graphics.Color
import com.vayunmathur.photos.data.BlackAndWhiteAdjustment
import com.vayunmathur.photos.data.ChannelMixerAdjustment
import com.vayunmathur.photos.data.ColorBalanceAdjustment
import com.vayunmathur.photos.data.CurveControlPoint
import com.vayunmathur.photos.data.CurvesAdjustment
import com.vayunmathur.photos.data.HslAdjustments
import com.vayunmathur.photos.data.HslChannelAdjustment
import com.vayunmathur.photos.data.HslColorRange
import com.vayunmathur.photos.data.LevelsAdjustment
import com.vayunmathur.photos.data.MaskType
import com.vayunmathur.photos.data.SelectiveEdits
import com.vayunmathur.photos.data.SelectiveMask
import com.vayunmathur.photos.data.applyHslToBitmap
import com.vayunmathur.photos.data.applyLutToBitmap
import com.vayunmathur.photos.data.applySelectiveEdits
import com.vayunmathur.photos.data.applyToBitmap
import com.vayunmathur.photos.data.generateLuts
import com.vayunmathur.photos.data.hslToRgb
import com.vayunmathur.photos.data.interpolateSpline
import com.vayunmathur.photos.data.rgbToHsl
import com.vayunmathur.photos.data.toColorMatrix
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Cov-c2: pixel maths for curves/levels/HSL/selective/B&W/mixer/balance. */
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PhotosCovC2AdjustmentsTest {

    private fun bitmap(): Bitmap {
        val bmp = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888)
        for (x in 0 until 16) for (y in 0 until 16) {
            bmp.setPixel(x, y, Color.rgb(x * 16, y * 16, (x + y) * 8))
        }
        return bmp
    }

    @Test fun curvesSplineLutsAndBitmap() {
        val identity = interpolateSpline(listOf(CurveControlPoint(0f, 0f), CurveControlPoint(1f, 1f)))
        assertEquals(256, identity.size)
        assertEquals(0f, identity[0], 0.01f)
        assertEquals(1f, identity[255], 0.01f)
        val steep = CurvesAdjustment(
            combined = listOf(CurveControlPoint(0f, 0f), CurveControlPoint(0.5f, 0.9f), CurveControlPoint(1f, 1f)),
        )
        assertFalse(steep.isIdentity())
        assertTrue(CurvesAdjustment().isIdentity())
        val luts = steep.generateLuts()
        assertEquals(4, luts.size)
        assertTrue(luts.all { it.size == 256 })
        assertTrue(steep.toColorMatrix().array.size == 20)
        val out = steep.applyLutToBitmap(bitmap())
        assertEquals(16, out.width)
    }

    @Test fun levelsAndHslConversions() {
        assertTrue(LevelsAdjustment().isIdentity())
        val levels = LevelsAdjustment(inBlack = 20f, inWhite = 230f, gamma = 1.4f, outBlack = 5f, outWhite = 250f)
        assertFalse(levels.isIdentity())
        assertEquals(16, levels.applyToBitmap(bitmap()).width)

        val hsl = rgbToHsl(255, 0, 0)
        assertEquals(0f, hsl[0], 1f)
        assertEquals(1f, hsl[1], 0.01f)
        assertEquals(0.5f, hsl[2], 0.01f)
        val gray = rgbToHsl(128, 128, 128)
        assertEquals(0f, gray[1], 0.01f)
        val back = hslToRgb(120f, 1f, 0.5f)
        assertEquals(0, back[0])
        assertEquals(255, back[1])
        assertEquals(0, back[2])
        val adjusted = HslAdjustments(
            channels = HslColorRange.entries.associateWith {
                HslChannelAdjustment(hue = 10f, saturation = 20f, luminance = -5f)
            },
        )
        assertFalse(adjusted.isIdentity())
        assertEquals(16, adjusted.applyHslToBitmap(bitmap()).width)
        assertTrue(HslAdjustments().isIdentity())
    }

    @Test fun selectiveMasksAndMonoMixerBalance() {
        assertTrue(SelectiveEdits().isIdentity())
        for (type in MaskType.entries) {
            val edits = SelectiveEdits(
                masks = listOf(
                    SelectiveMask(
                        type = type,
                        brushPoints = listOf(0.5f to 0.5f, 0.6f to 0.6f),
                        adjustments = com.vayunmathur.photos.data.ImageAdjustments(brightness = 40f),
                    ),
                ),
            )
            assertFalse(edits.isIdentity())
            assertEquals(16, edits.applySelectiveEdits(bitmap()).width)
        }
        assertTrue(BlackAndWhiteAdjustment().isIdentity())
        val mono = BlackAndWhiteAdjustment(enabled = true, reds = 80f, tint = Color.rgb(10, 5, 0))
        assertFalse(mono.isIdentity())
        val monoOut = mono.applyToBitmap(bitmap())
        val px = monoOut.getPixel(8, 8)
        assertTrue(Color.red(px) == Color.green(px) || Color.red(px) >= Color.green(px))

        val mixer = ChannelMixerAdjustment(rFromR = 0f, rFromG = 1f, gFromG = 0f, gFromB = 1f)
        assertEquals(16, mixer.applyToBitmap(bitmap()).width)
        val balance = ColorBalanceAdjustment(shadowsRedCyan = 20f, midGreenMagenta = -10f, highBlueYellow = 30f)
        assertEquals(16, balance.applyToBitmap(bitmap()).width)
    }
}
