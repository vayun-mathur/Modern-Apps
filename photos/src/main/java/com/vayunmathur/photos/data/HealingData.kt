package com.vayunmathur.photos.data

import android.graphics.Bitmap
import kotlin.math.max
import kotlin.math.sqrt

private const val ALPHA_SHIFT = 24
private const val RED_SHIFT = 16
private const val GREEN_SHIFT = 8

/** Kinds of heal mode. */
enum class HealMode { Heal, Clone, SpotHeal }

/** Immutable value describing healing stroke. */
data class HealingStroke(
    val sourceX: Float,
    val sourceY: Float,
    val points: List<Pair<Float, Float>>,
    val brushSize: Float = 0.02f,
    val mode: HealMode = HealMode.Heal,
)

/** Immutable value describing healing strokes. */
data class HealingStrokes(
    val strokes: List<HealingStroke> = emptyList(),
) {
    fun isIdentity(): Boolean = strokes.isEmpty()
}

fun HealingStrokes.applyHealingToBitmap(bitmap: Bitmap): Bitmap {
    val result = bitmap.copy(Bitmap.Config.ARGB_8888, true)
    val w = result.width
    val h = result.height
    val pixels = IntArray(w * h)
    result.getPixels(pixels, 0, w, 0, 0, w, h)
    val sourcePixels = IntArray(w * h)
    bitmap.getPixels(sourcePixels, 0, w, 0, 0, w, h)
    for (stroke in strokes) {
        if (stroke.points.isEmpty()) continue
        val firstPoint = stroke.points[0]
        val offsetX = stroke.sourceX - firstPoint.first
        val offsetY = stroke.sourceY - firstPoint.second
        val brushPx = (stroke.brushSize * max(w, h)).toInt().coerceAtLeast(1)
        for ((px, py) in stroke.points) {
            applyHealingDab(pixels, sourcePixels, w, h, offsetX, offsetY, brushPx, px, py)
        }
    }
    result.setPixels(pixels, 0, w, 0, 0, w, h)
    return result
}

private fun applyHealingDab(
    pixels: IntArray,
    sourcePixels: IntArray,
    w: Int,
    h: Int,
    offsetX: Float,
    offsetY: Float,
    brushPx: Int,
    px: Float,
    py: Float,
) {
    val destX = (px * w).toInt()
    val destY = (py * h).toInt()
    val srcX = ((px + offsetX) * w).toInt()
    val srcY = ((py + offsetY) * h).toInt()
    for (dy in -brushPx..brushPx) {
        for (dx in -brushPx..brushPx) {
            val dist = sqrt((dx * dx + dy * dy).toFloat())
            if (dist > brushPx) continue
            val feather = (1f - dist / brushPx).coerceIn(0f, 1f)
            val sx = (srcX + dx).coerceIn(0, w - 1)
            val sy = (srcY + dy).coerceIn(0, h - 1)
            val tx = (destX + dx).coerceIn(0, w - 1)
            val ty = (destY + dy).coerceIn(0, h - 1)
            pixels[ty * w + tx] = blendHealingPixel(sourcePixels[sy * w + sx], pixels[ty * w + tx], feather)
        }
    }
}

private fun blendHealingPixel(srcPx: Int, dstPx: Int, feather: Float): Int {
    val sA = (srcPx shr 24) and 0xFF; val dA = (dstPx shr 24) and 0xFF
    val sR = (srcPx shr 16) and 0xFF; val dR = (dstPx shr 16) and 0xFF
    val sG = (srcPx shr 8) and 0xFF; val dG = (dstPx shr 8) and 0xFF
    val sB = srcPx and 0xFF; val dB = dstPx and 0xFF
    val a = (dA + (sA - dA) * feather).toInt().coerceIn(0, 255)
    val r = (dR + (sR - dR) * feather).toInt().coerceIn(0, 255)
    val g = (dG + (sG - dG) * feather).toInt().coerceIn(0, 255)
    val b = (dB + (sB - dB) * feather).toInt().coerceIn(0, 255)
    return (a shl ALPHA_SHIFT) or (r shl RED_SHIFT) or (g shl GREEN_SHIFT) or b
}
