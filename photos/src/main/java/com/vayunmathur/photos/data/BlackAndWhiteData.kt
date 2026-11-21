package com.vayunmathur.photos.data

import android.graphics.Bitmap
import kotlin.math.max
import kotlin.math.min

private const val ALPHA_SHIFT = 24
private const val RED_SHIFT = 16
private const val GREEN_SHIFT = 8
private const val CHANNEL_MAX = 255
private const val CHANNEL_MAX_F = 255f
private const val FULL_CIRCLE_DEGREES = 360f

/** Immutable value describing black and white adjustment. */
data class BlackAndWhiteAdjustment(
    val enabled: Boolean = false,
    val reds: Float = 40f,
    val yellows: Float = 60f,
    val greens: Float = 40f,
    val cyans: Float = 60f,
    val blues: Float = 20f,
    val magentas: Float = 80f,
    val tint: Int = 0,
) {
    fun isIdentity(): Boolean = !enabled
}

/** Zero-copy entry point: applies the B&W conversion in place to [pixels] and returns it. */
fun BlackAndWhiteAdjustment.applyToPixels(pixels: IntArray, width: Int, height: Int): IntArray {
    // Band centers in degrees and associated weights (percent).
    val centers = floatArrayOf(0f, 60f, 120f, 180f, 240f, 300f)
    val weights = floatArrayOf(reds, yellows, greens, cyans, blues, magentas)

    val tintR = (tint ushr 16) and 0xFF
    val tintG = (tint ushr 8) and 0xFF
    val tintB = tint and 0xFF

    for (i in pixels.indices) {
        val p = pixels[i]
        val a = (p ushr 24) and 0xFF
        val r = (p ushr 16) and 0xFF
        val g = (p ushr 8) and 0xFF
        val b = p and 0xFF

        val hue = rgbToHue(r, g, b)
        val selectedWeight = interpolateWeight(hue, centers, weights)

        val luminance = 0.299f * r + 0.587f * g + 0.114f * b
        val gray = (luminance * (selectedWeight / 60f)).toInt().coerceIn(0, 255)

        val nr: Int
        val ng: Int
        val nb: Int
        if (tint != 0) {
            nr = (gray * (tintR / CHANNEL_MAX_F)).toInt().coerceIn(0, CHANNEL_MAX)
            ng = (gray * (tintG / CHANNEL_MAX_F)).toInt().coerceIn(0, CHANNEL_MAX)
            nb = (gray * (tintB / CHANNEL_MAX_F)).toInt().coerceIn(0, CHANNEL_MAX)
        } else {
            nr = gray
            ng = gray
            nb = gray
        }

        pixels[i] = (a shl ALPHA_SHIFT) or (nr shl RED_SHIFT) or (ng shl GREEN_SHIFT) or nb
    }
    return pixels
}

fun BlackAndWhiteAdjustment.applyToBitmap(bitmap: Bitmap): Bitmap {
    val result = bitmap.copy(Bitmap.Config.ARGB_8888, true)
    val pixels = IntArray(result.width * result.height)
    result.getPixels(pixels, 0, result.width, 0, 0, result.width, result.height)
    applyToPixels(pixels, result.width, result.height)
    result.setPixels(pixels, 0, result.width, 0, 0, result.width, result.height)
    return result
}

private fun rgbToHue(r: Int, g: Int, b: Int): Float {
    val rf = r / 255f
    val gf = g / 255f
    val bf = b / 255f
    val maxc = max(rf, max(gf, bf))
    val minc = min(rf, min(gf, bf))
    val delta = maxc - minc
    if (delta == 0f) return 0f
    var hue = when (maxc) {
        rf -> 60f * (((gf - bf) / delta) % 6f)
        gf -> 60f * (((bf - rf) / delta) + 2f)
        else -> 60f * (((rf - gf) / delta) + 4f)
    }
    if (hue < 0f) hue += FULL_CIRCLE_DEGREES
    return hue
}

private fun interpolateWeight(hue: Float, centers: FloatArray, weights: FloatArray): Float {
    val n = centers.size
    for (i in 0 until n) {
        val lo = centers[i]
        val hi = if (i + 1 < n) centers[i + 1] else 360f
        val wLo = weights[i]
        val wHi = weights[(i + 1) % n]
        if (hue >= lo && hue <= hi) {
            val span = hi - lo
            val f = if (span <= 0f) 0f else (hue - lo) / span
            return wLo + (wHi - wLo) * f
        }
    }
    return weights[0]
}
