package com.vayunmathur.photos.data

import android.graphics.Bitmap
import androidx.annotation.StringRes
import androidx.core.graphics.createBitmap
import com.vayunmathur.photos.R
import kotlin.math.abs

private const val ALPHA_SHIFT = 24
private const val RED_SHIFT = 16
private const val GREEN_SHIFT = 8
private const val CHANNEL_MAX = 255
private const val CHANNEL_MAX_F = 255f
private const val FULL_CIRCLE_DEGREES = 360f
private const val HUE_RANGE_HALF_WIDTH = 30f
private const val HUE_SECTOR_DEGREES = 60f
private const val PERCENT_DIVISOR = 100f
private const val HUE_SIXTH = 1f / 6f
private const val HUE_HALF = 1f / 2f
private const val HUE_THIRD = 1f / 3f
private const val HUE_TWO_THIRDS = 2f / 3f
private const val HUE_BLEND_SCALE = 6f

/** Kinds of hsl color range. */
enum class HslColorRange(@StringRes val labelRes: Int, val hueCenter: Float) {
    Red(R.string.color_red, hueCenter = 0f),
    Orange(R.string.color_orange, hueCenter = 30f),
    Yellow(R.string.yellow, hueCenter = 60f),
    Green(R.string.color_green, hueCenter = 120f),
    Cyan(R.string.cyan, hueCenter = 180f),
    Blue(R.string.color_blue, hueCenter = 240f),
    Purple(R.string.color_purple, hueCenter = 270f),
    Magenta(R.string.magenta, hueCenter = 300f),
}

/** Immutable value describing hsl channel adjustment. */
data class HslChannelAdjustment(
    val hue: Float = 0f,
    val saturation: Float = 0f,
    val luminance: Float = 0f,
)

/** Immutable value describing hsl adjustments. */
data class HslAdjustments(
    val channels: Map<HslColorRange, HslChannelAdjustment> =
        HslColorRange.entries.associateWith { HslChannelAdjustment() },
) {
    fun isIdentity(): Boolean = channels.values.all { it.hue == 0f && it.saturation == 0f && it.luminance == 0f }
}

fun rgbToHsl(r: Int, g: Int, b: Int): FloatArray {
    val rf = r / 255f
    val gf = g / 255f
    val bf = b / 255f
    val cMax = maxOf(rf, gf, bf)
    val cMin = minOf(rf, gf, bf)
    val delta = cMax - cMin
    val l = (cMax + cMin) / 2f
    if (delta == 0f) return floatArrayOf(0f, 0f, l)
    val s = if (l < 0.5f) delta / (cMax + cMin) else delta / (2f - cMax - cMin)
    val h = when (cMax) {
        rf -> ((gf - bf) / delta + (if (gf < bf) 6f else 0f)) * HUE_SECTOR_DEGREES
        gf -> ((bf - rf) / delta + 2f) * HUE_SECTOR_DEGREES
        else -> ((rf - gf) / delta + 4f) * HUE_SECTOR_DEGREES
    }
    return floatArrayOf(h, s, l)
}

fun hslToRgb(h: Float, s: Float, l: Float): IntArray {
    if (s == 0f) {
        val v = (l * 255f).toInt().coerceIn(0, 255)
        return intArrayOf(v, v, v)
    }
    val q = if (l < 0.5f) l * (1f + s) else l + s - l * s
    val p = 2f * l - q
    fun hue2rgb(t: Float): Float {
        var tt = t
        if (tt < 0f) tt += 1f
        if (tt > 1f) tt -= 1f
        return when {
            tt < HUE_SIXTH -> p + (q - p) * HUE_BLEND_SCALE * tt
            tt < HUE_HALF -> q
            tt < HUE_TWO_THIRDS -> p + (q - p) * (HUE_TWO_THIRDS - tt) * HUE_BLEND_SCALE
            else -> p
        }
    }
    val hNorm = h / FULL_CIRCLE_DEGREES
    return intArrayOf(
        (hue2rgb(hNorm + HUE_THIRD) * CHANNEL_MAX_F).toInt().coerceIn(0, CHANNEL_MAX),
        (hue2rgb(hNorm) * CHANNEL_MAX_F).toInt().coerceIn(0, CHANNEL_MAX),
        (hue2rgb(hNorm - HUE_THIRD) * CHANNEL_MAX_F).toInt().coerceIn(0, CHANNEL_MAX),
    )
}

private fun hueWeight(pixelHue: Float, centerHue: Float): Float {
    val diff = abs(((pixelHue - centerHue + 180f + FULL_CIRCLE_DEGREES) % FULL_CIRCLE_DEGREES) - 180f)
    return (1f - (diff / HUE_RANGE_HALF_WIDTH)).coerceIn(0f, 1f)
}

fun HslAdjustments.applyHslToBitmap(bitmap: Bitmap): Bitmap {
    val w = bitmap.width
    val h = bitmap.height
    val pixels = IntArray(w * h)
    bitmap.getPixels(pixels, 0, w, 0, 0, w, h)
    for (i in pixels.indices) {
        val a = (pixels[i] shr 24) and 0xFF
        val r = (pixels[i] shr 16) and 0xFF
        val g = (pixels[i] shr 8) and 0xFF
        val b = pixels[i] and 0xFF
        val hsl = rgbToHsl(r, g, b)
        var hue = hsl[0]
        var sat = hsl[1]
        var lum = hsl[2]
        for ((range, adj) in channels) {
            val weight = hueWeight(hue, range.hueCenter)
            if (weight > 0f) {
                hue = (hue + adj.hue * weight) % FULL_CIRCLE_DEGREES
                if (hue < 0f) hue += FULL_CIRCLE_DEGREES
                sat = (sat + adj.saturation / PERCENT_DIVISOR * weight).coerceIn(0f, 1f)
                lum = (lum + adj.luminance / PERCENT_DIVISOR * weight).coerceIn(0f, 1f)
            }
        }
        val rgb = hslToRgb(hue, sat, lum)
        pixels[i] = (a shl ALPHA_SHIFT) or (rgb[0] shl RED_SHIFT) or (rgb[1] shl GREEN_SHIFT) or rgb[2]
    }
    val result = createBitmap(w, h)
    result.setPixels(pixels, 0, w, 0, 0, w, h)
    return result
}
