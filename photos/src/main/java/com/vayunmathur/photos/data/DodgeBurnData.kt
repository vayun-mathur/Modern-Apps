package com.vayunmathur.photos.data

import android.graphics.Bitmap
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt

private const val ALPHA_SHIFT = 24
private const val RED_SHIFT = 16
private const val GREEN_SHIFT = 8
private const val CHANNEL_MAX = 255

/** Kinds of dodge burn mode. */
enum class DodgeBurnMode { Dodge, Burn }

/** Kinds of tonal range. */
enum class TonalRange { Shadows, Midtones, Highlights }

/** Immutable value describing dodge burn stroke. */
data class DodgeBurnStroke(
    val points: List<Pair<Float, Float>>,
    val mode: DodgeBurnMode,
    val range: TonalRange = TonalRange.Midtones,
    val exposure: Float = 0.5f,
    val brushSize: Float = 0.05f,
)

/** Immutable value describing dodge burn strokes. */
data class DodgeBurnStrokes(
    val strokes: List<DodgeBurnStroke> = emptyList(),
) {
    fun isIdentity(): Boolean = strokes.isEmpty()
}

fun DodgeBurnStrokes.applyToBitmap(bitmap: Bitmap): Bitmap {
    val result = bitmap.copy(Bitmap.Config.ARGB_8888, true)
    val w = result.width
    val h = result.height
    val pixels = IntArray(w * h)
    result.getPixels(pixels, 0, w, 0, 0, w, h)
    for (stroke in strokes) {
        if (stroke.points.isEmpty()) continue
        val brushPx = (stroke.brushSize * max(w, h)).coerceAtLeast(1f)
        val brushR = brushPx.toInt().coerceAtLeast(1)
        val sign = if (stroke.mode == DodgeBurnMode.Dodge) 1f else -1f
        for ((px, py) in stroke.points) {
            applyDodgeBurnDab(pixels, w, h, stroke, sign, brushPx, brushR, px, py)
        }
    }
    result.setPixels(pixels, 0, w, 0, 0, w, h)
    return result
}

private fun applyDodgeBurnDab(
    pixels: IntArray,
    w: Int,
    h: Int,
    stroke: DodgeBurnStroke,
    sign: Float,
    brushPx: Float,
    brushR: Int,
    px: Float,
    py: Float,
) {
    val cx = (px * w).toInt()
    val cy = (py * h).toInt()
    for (dy in -brushR..brushR) {
        for (dx in -brushR..brushR) {
            val dist = sqrt((dx * dx + dy * dy).toFloat())
            if (dist > brushPx) continue
            val feather = (1f - dist / brushPx).coerceIn(0f, 1f)
            val tx = (cx + dx).coerceIn(0, w - 1)
            val ty = (cy + dy).coerceIn(0, h - 1)
            val idx = ty * w + tx
            pixels[idx] = dodgeBurnPixel(pixels[idx], stroke, sign, feather)
        }
    }
}

private fun dodgeBurnPixel(pixel: Int, stroke: DodgeBurnStroke, sign: Float, feather: Float): Int {
    val a = (pixel shr 24) and 0xFF
    val r = (pixel shr 16) and 0xFF
    val g = (pixel shr 8) and 0xFF
    val b = pixel and 0xFF
    val l = (0.299f * r + 0.587f * g + 0.114f * b) / 255f
    val rangeWeight = when (stroke.range) {
        TonalRange.Shadows -> 1f - l
        TonalRange.Highlights -> l
        TonalRange.Midtones -> 1f - abs(2f * l - 1f)
    }
    val factor = 1f + sign * stroke.exposure * feather * rangeWeight
    val nr = (r * factor).toInt().coerceIn(0, 255)
    val ng = (g * factor).toInt().coerceIn(0, 255)
    val nb = (b * factor).toInt().coerceIn(0, 255)
    return (a shl ALPHA_SHIFT) or (nr shl RED_SHIFT) or (ng shl GREEN_SHIFT) or nb
}
