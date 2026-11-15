package com.vayunmathur.photos.data

import android.graphics.Bitmap

private const val ALPHA_SHIFT = 24
private const val RED_SHIFT = 16
private const val GREEN_SHIFT = 8
private const val CHANNEL_MAX = 255
private const val CHANNEL_MAX_F = 255f
private const val GRADIENT_LUT_SIZE = 256

/** Immutable value describing gradient stop; carries position, color. */
data class GradientStop(val position: Float, val color: Int)

/** Immutable value describing gradient map adjustment; carries stops. */
data class GradientMapAdjustment(val stops: List<GradientStop> = emptyList()) {
    fun isIdentity(): Boolean = stops.size < 2
}

fun GradientMapAdjustment.applyToBitmap(bitmap: Bitmap): Bitmap {
    val sorted = stops.sortedBy { it.position }
    val lut = IntArray(GRADIENT_LUT_SIZE)
    for (v in 0..CHANNEL_MAX) {
        val t = v / CHANNEL_MAX_F
        lut[v] = colorAt(sorted, t)
    }

    return bitmap.copy(Bitmap.Config.ARGB_8888, true).apply {
        mapPixels { p ->
            val a = (p ushr 24) and 0xFF
            val r = (p ushr 16) and 0xFF
            val g = (p ushr 8) and 0xFF
            val b = p and 0xFF

            val lum = (0.299f * r + 0.587f * g + 0.114f * b).toInt().coerceIn(0, 255)
            val c = lut[lum]
            val nr = (c ushr 16) and 0xFF
            val ng = (c ushr 8) and 0xFF
            val nb = c and 0xFF
            (a shl ALPHA_SHIFT) or (nr shl RED_SHIFT) or (ng shl GREEN_SHIFT) or nb
        }
    }
}

private fun colorAt(sorted: List<GradientStop>, t: Float): Int {
    if (sorted.isEmpty()) return 0
    if (t <= sorted.first().position) return sorted.first().color
    if (t >= sorted.last().position) return sorted.last().color

    for (i in 0 until sorted.size - 1) {
        val lo = sorted[i]
        val hi = sorted[i + 1]
        if (t >= lo.position && t <= hi.position) {
            val span = (hi.position - lo.position)
            val f = if (span <= 0f) 0f else (t - lo.position) / span
            return lerpColor(lo.color, hi.color, f)
        }
    }
    return sorted.last().color
}

private fun lerpColor(c0: Int, c1: Int, f: Float): Int {
    val a0 = (c0 ushr 24) and 0xFF
    val r0 = (c0 ushr 16) and 0xFF
    val g0 = (c0 ushr 8) and 0xFF
    val b0 = c0 and 0xFF
    val a1 = (c1 ushr 24) and 0xFF
    val r1 = (c1 ushr 16) and 0xFF
    val g1 = (c1 ushr 8) and 0xFF
    val b1 = c1 and 0xFF
    val a = (a0 + (a1 - a0) * f).toInt().coerceIn(0, 255)
    val r = (r0 + (r1 - r0) * f).toInt().coerceIn(0, 255)
    val g = (g0 + (g1 - g0) * f).toInt().coerceIn(0, 255)
    val b = (b0 + (b1 - b0) * f).toInt().coerceIn(0, 255)
    return (a shl ALPHA_SHIFT) or (r shl RED_SHIFT) or (g shl GREEN_SHIFT) or b
}
