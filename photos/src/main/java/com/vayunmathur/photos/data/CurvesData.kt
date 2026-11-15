package com.vayunmathur.photos.data

import android.graphics.Bitmap
import android.graphics.ColorMatrix

private const val ALPHA_SHIFT = 24
private const val RED_SHIFT = 16
private const val GREEN_SHIFT = 8
private const val CHANNEL_MAX = 255
private const val CHANNEL_MAX_F = 255f

/** Immutable value describing curve control point; carries x, y. */
data class CurveControlPoint(val x: Float, val y: Float)

/** Kinds of curve channel. */
enum class CurveChannel { Combined, Red, Green, Blue }

/** Immutable value describing curves adjustment. */
data class CurvesAdjustment(
    val combined: List<CurveControlPoint> = listOf(CurveControlPoint(0f, 0f), CurveControlPoint(1f, 1f)),
    val red: List<CurveControlPoint> = listOf(CurveControlPoint(0f, 0f), CurveControlPoint(1f, 1f)),
    val green: List<CurveControlPoint> = listOf(CurveControlPoint(0f, 0f), CurveControlPoint(1f, 1f)),
    val blue: List<CurveControlPoint> = listOf(CurveControlPoint(0f, 0f), CurveControlPoint(1f, 1f)),
) {
    private val defaultPoints = listOf(CurveControlPoint(0f, 0f), CurveControlPoint(1f, 1f))

    fun isIdentity(): Boolean =
        listOf(combined, red, green, blue).all { it == defaultPoints }
}

fun interpolateSpline(points: List<CurveControlPoint>, steps: Int = 256): FloatArray {
    val sorted = points.sortedBy { it.x }
    val lut = FloatArray(steps)
    if (sorted.size < 2) {
        for (i in 0 until steps) lut[i] = i.toFloat() / (steps - 1)
        return lut
    }
    for (i in 0 until steps) {
        val t = i.toFloat() / (steps - 1)
        val idx = sorted.indexOfLast { it.x <= t }.coerceAtLeast(0)
        val p0 = sorted[idx]
        val p1 = sorted[(idx + 1).coerceAtMost(sorted.lastIndex)]
        val frac = if (p1.x == p0.x) 0f else ((t - p0.x) / (p1.x - p0.x)).coerceIn(0f, 1f)
        val smoothFrac = frac * frac * (3f - 2f * frac)
        lut[i] = (p0.y + (p1.y - p0.y) * smoothFrac).coerceIn(0f, 1f)
    }
    return lut
}

fun CurvesAdjustment.generateLuts(): Array<FloatArray> = arrayOf(
    interpolateSpline(combined),
    interpolateSpline(red),
    interpolateSpline(green),
    interpolateSpline(blue),
)

fun CurvesAdjustment.toColorMatrix(): ColorMatrix {
    val mid = interpolateSpline(combined, 3)
    val rMid = interpolateSpline(red, 3)
    val gMid = interpolateSpline(green, 3)
    val bMid = interpolateSpline(blue, 3)
    val combinedScale = mid[2] - mid[0]
    val combinedOff = mid[0]
    val rChScale = rMid[2] - rMid[0]
    val gChScale = gMid[2] - gMid[0]
    val bChScale = bMid[2] - bMid[0]
    val rScale = combinedScale * rChScale
    val gScale = combinedScale * gChScale
    val bScale = combinedScale * bChScale
    val rOffset = (rChScale * combinedOff + rMid[0]) * 255f
    val gOffset = (gChScale * combinedOff + gMid[0]) * 255f
    val bOffset = (bChScale * combinedOff + bMid[0]) * 255f
    return ColorMatrix(floatArrayOf(
        rScale, 0f, 0f, 0f, rOffset,
        0f, gScale, 0f, 0f, gOffset,
        0f, 0f, bScale, 0f, bOffset,
        0f, 0f, 0f, 1f, 0f,
    ))
}

fun CurvesAdjustment.applyLutToBitmap(bitmap: Bitmap): Bitmap {
    val luts = generateLuts()
    val combinedLut = luts[0]
    val rLut = luts[1]
    val gLut = luts[2]
    val bLut = luts[3]
    return bitmap.copy(Bitmap.Config.ARGB_8888, true).apply {
        mapPixels { p ->
            val a = (p shr 24) and 0xFF
            var r = (p shr 16) and 0xFF
            var g = (p shr 8) and 0xFF
            var b = p and 0xFF
            r = (combinedLut[r] * CHANNEL_MAX_F).toInt().coerceIn(0, CHANNEL_MAX)
            g = (combinedLut[g] * CHANNEL_MAX_F).toInt().coerceIn(0, CHANNEL_MAX)
            b = (combinedLut[b] * CHANNEL_MAX_F).toInt().coerceIn(0, CHANNEL_MAX)
            r = (rLut[r] * CHANNEL_MAX_F).toInt().coerceIn(0, CHANNEL_MAX)
            g = (gLut[g] * CHANNEL_MAX_F).toInt().coerceIn(0, CHANNEL_MAX)
            b = (bLut[b] * CHANNEL_MAX_F).toInt().coerceIn(0, CHANNEL_MAX)
            (a shl ALPHA_SHIFT) or (r shl RED_SHIFT) or (g shl GREEN_SHIFT) or b
        }
    }
}
