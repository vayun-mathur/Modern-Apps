package com.vayunmathur.photos.photoslice2

import com.vayunmathur.photos.data.LayerBlendMode
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Slice-2 tests for [LayerBlendMode.blendPixel]: pure per-pixel math, no
 * Android framework needed. Covers identity/edge behavior plus each mode's
 * distinguishing arithmetic against hand-computed expectations.
 */
class PhotoSlice2BlendTest {

    private fun opaque(rgb: Int): Int = (0xFF shl 24) or rgb

    @Test
    fun transparentSourceReturnsBackdrop() {
        val dst = opaque(0x112233)
        val src = 0x00112233 // alpha 0
        for (mode in LayerBlendMode.entries) {
            assertEquals(dst, mode.blendPixel(dst, src, 1f), "mode $mode with alpha-0 src")
        }
    }

    @Test
    fun zeroExtraAlphaReturnsBackdrop() {
        val dst = opaque(0x112233)
        val src = opaque(0xAABBCC)
        for (mode in LayerBlendMode.entries) {
            assertEquals(dst, mode.blendPixel(dst, src, 0f), "mode $mode with alpha-0 extra")
        }
    }

    @Test
    fun normalOpaqueSourceReplacesBackdrop() {
        val src = opaque(0xDEAD42)
        val out = LayerBlendMode.Normal.blendPixel(opaque(0x112233), src, 1f)
        assertEquals(src, out)
    }

    @Test
    fun normalHalfOpacityAverages() {
        val dst = opaque(0x000000)
        val src = opaque(0xFFFFFF)
        val out = LayerBlendMode.Normal.blendPixel(dst, src, 0.5f)
        val r = (out shr 16) and 0xFF
        // 50% white over black ~= 127/128.
        assertTrue(abs(r - 127.5f) <= 1.5f, "half-blend red=$r")
        assertEquals(0xFF, (out ushr 24) and 0xFF)
    }

    @Test
    fun multiplyDarkens() {
        // 0.5 gray * 0.5 gray = 0.25 gray-ish.
        val g = opaque(0x808080)
        val out = LayerBlendMode.Multiply.blendPixel(g, g, 1f)
        val r = (out shr 16) and 0xFF
        assertTrue(r in 60..70, "multiply gray=$r")
    }

    @Test
    fun screenLightens() {
        val g = opaque(0x808080)
        val out = LayerBlendMode.Screen.blendPixel(g, g, 1f)
        val r = (out shr 16) and 0xFF
        // screen(0.5,0.5) = 0.75 -> ~191.
        assertTrue(r in 186..196, "screen gray=$r")
    }

    @Test
    fun darkenTakesMinLightenTakesMax() {
        val dark = opaque(0x102030)
        val light = opaque(0xA0B0C0)
        val d = LayerBlendMode.Darken.blendPixel(light, dark, 1f)
        assertEquals(dark, d)
        val l = LayerBlendMode.Lighten.blendPixel(dark, light, 1f)
        assertEquals(light, l)
    }

    @Test
    fun differenceOfEqualIsBlack() {
        val c = opaque(0x445566)
        val out = LayerBlendMode.Difference.blendPixel(c, c, 1f)
        assertEquals(opaque(0x000000), out)
    }

    @Test
    fun differenceIsSymmetric() {
        val a = opaque(0x102030)
        val b = opaque(0xA0B0C0)
        assertEquals(
            LayerBlendMode.Difference.blendPixel(a, b, 1f),
            LayerBlendMode.Difference.blendPixel(b, a, 1f),
        )
    }

    @Test
    fun exclusionOfEqualIsNotBlack() {
        // exclusion(c,c) = 2*c*(1-c) > 0 for mid tones.
        val c = opaque(0x808080)
        val out = LayerBlendMode.Exclusion.blendPixel(c, c, 1f)
        val r = (out shr 16) and 0xFF
        assertTrue(r in 120..136, "exclusion gray=$r")
    }

    @Test
    fun colorDodgeBrightensTowardWhite() {
        val dst = opaque(0x808080)
        val out = LayerBlendMode.ColorDodge.blendPixel(dst, opaque(0xFFFFFF), 1f)
        assertEquals(opaque(0xFFFFFF), out)
    }

    @Test
    fun colorBurnDarkensTowardBlack() {
        val dst = opaque(0x808080)
        val out = LayerBlendMode.ColorBurn.blendPixel(dst, opaque(0x000000), 1f)
        assertEquals(opaque(0x000000), out)
    }

    @Test
    fun overlayHardLightAgreeOnMid() {
        // Overlay(b,s) == HardLight(s,b) by construction.
        val b = opaque(0x336699)
        val s = opaque(0xCC6633)
        assertEquals(
            LayerBlendMode.Overlay.blendPixel(b, s, 1f),
            LayerBlendMode.HardLight.blendPixel(s, b, 1f),
        )
    }

    @Test
    fun softLightNeutralSourceIsNearIdentity() {
        // 50% gray source is the soft-light neutral: output ~= backdrop.
        val dst = opaque(0x336699)
        val out = LayerBlendMode.SoftLight.blendPixel(dst, opaque(0x808080), 1f)
        for (shift in listOf(16, 8, 0)) {
            val want = (dst shr shift) and 0xFF
            val got = (out shr shift) and 0xFF
            assertTrue(abs(got - want) <= 3, "channel shift=$shift want=$want got=$got")
        }
    }

    @Test
    fun nonSeparableModesPreserveLuminanceRelationships() {
        // Luminosity(src over dst): output luminance ~= src luminance.
        val dst = opaque(0xFF0000)
        val src = opaque(0x0000FF)
        val out = LayerBlendMode.Luminosity.blendPixel(dst, src, 1f)
        fun lum(px: Int): Float {
            val r = ((px shr 16) and 0xFF) / 255f
            val g = ((px shr 8) and 0xFF) / 255f
            val b = (px and 0xFF) / 255f
            return 0.3f * r + 0.59f * g + 0.11f * b
        }
        assertTrue(abs(lum(out) - lum(src)) < 0.08f, "lum out=${lum(out)} src=${lum(src)}")
    }

    @Test
    fun hueModeKeepsBackdropLuminance() {
        val dst = opaque(0x808080) // mid gray
        val src = opaque(0xFF0000) // red hue
        val out = LayerBlendMode.Hue.blendPixel(dst, src, 1f)
        fun lum(px: Int): Float {
            val r = ((px shr 16) and 0xFF) / 255f
            val g = ((px shr 8) and 0xFF) / 255f
            val b = (px and 0xFF) / 255f
            return 0.3f * r + 0.59f * g + 0.11f * b
        }
        assertTrue(abs(lum(out) - lum(dst)) < 0.08f, "lum out=${lum(out)} dst=${lum(dst)}")
    }

    @Test
    fun allModesReturnOpaqueForOpaqueInputs() {
        val dst = opaque(0x112233)
        val src = opaque(0xAABBCC)
        for (mode in LayerBlendMode.entries) {
            val out = mode.blendPixel(dst, src, 1f)
            assertEquals(0xFF, (out ushr 24) and 0xFF, "mode $mode alpha")
        }
    }

    @Test
    fun extraAlphaClamped() {
        val dst = opaque(0x000000)
        val src = opaque(0xFFFFFF)
        // Over-range alpha behaves like 1.
        assertEquals(
            LayerBlendMode.Normal.blendPixel(dst, src, 1f),
            LayerBlendMode.Normal.blendPixel(dst, src, 5f),
        )
        // Negative behaves like 0.
        assertEquals(dst, LayerBlendMode.Normal.blendPixel(dst, src, -2f))
    }
}
