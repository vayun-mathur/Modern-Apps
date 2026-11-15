package com.vayunmathur.photos.data

import android.graphics.Bitmap
import kotlin.math.max
import kotlin.math.sqrt

private const val ALPHA_SHIFT = 24
private const val RED_SHIFT = 16
private const val GREEN_SHIFT = 8

/** Immutable value describing smudge stroke. */
data class SmudgeStroke(
    val points: List<Pair<Float, Float>>,
    val strength: Float = 0.5f,
    val brushSize: Float = 0.05f,
)

/** Immutable value describing smudge strokes. */
data class SmudgeStrokes(
    val strokes: List<SmudgeStroke> = emptyList(),
) {
    fun isIdentity(): Boolean = strokes.isEmpty()
}

fun SmudgeStrokes.applyToBitmap(bitmap: Bitmap): Bitmap {
    val result = bitmap.copy(Bitmap.Config.ARGB_8888, true)
    val w = result.width
    val h = result.height
    val pixels = IntArray(w * h)
    result.getPixels(pixels, 0, w, 0, 0, w, h)
    for (stroke in strokes) {
        if (stroke.points.size < 2) continue
        val brushPx = (stroke.brushSize * max(w, h)).coerceAtLeast(1f)
        val brushR = brushPx.toInt().coerceAtLeast(1)
        for (i in 1 until stroke.points.size) {
            val prev = stroke.points[i - 1]
            val cur = stroke.points[i]
            smudgeSegment(pixels, w, h, stroke, brushPx, brushR, prev, cur)
        }
    }
    result.setPixels(pixels, 0, w, 0, 0, w, h)
    return result
}

private fun smudgeSegment(
    pixels: IntArray,
    w: Int,
    h: Int,
    stroke: SmudgeStroke,
    brushPx: Float,
    brushR: Int,
    prev: Pair<Float, Float>,
    cur: Pair<Float, Float>,
) {
    val curX = (cur.first * w).toInt()
    val curY = (cur.second * h).toInt()
    val prevX = (prev.first * w).toInt()
    val prevY = (prev.second * h).toInt()
    for (dy in -brushR..brushR) {
        for (dx in -brushR..brushR) {
            val dist = sqrt((dx * dx + dy * dy).toFloat())
            if (dist > brushPx) continue
            val feather = (1f - dist / brushPx).coerceIn(0f, 1f)
            val tx = (curX + dx).coerceIn(0, w - 1)
            val ty = (curY + dy).coerceIn(0, h - 1)
            val sx = (prevX + dx).coerceIn(0, w - 1)
            val sy = (prevY + dy).coerceIn(0, h - 1)
            val amount = (stroke.strength * feather).coerceIn(0f, 1f)
            pixels[ty * w + tx] = blendSmudgePixel(pixels[ty * w + tx], pixels[sy * w + sx], amount)
        }
    }
}

private fun blendSmudgePixel(dstPx: Int, srcPx: Int, amount: Float): Int {
    val dA = (dstPx shr 24) and 0xFF
    val dR = (dstPx shr 16) and 0xFF
    val dG = (dstPx shr 8) and 0xFF
    val dB = dstPx and 0xFF
    val sA = (srcPx shr 24) and 0xFF
    val sR = (srcPx shr 16) and 0xFF
    val sG = (srcPx shr 8) and 0xFF
    val sB = srcPx and 0xFF
    val a = (dA + (sA - dA) * amount).toInt().coerceIn(0, 255)
    val r = (dR + (sR - dR) * amount).toInt().coerceIn(0, 255)
    val g = (dG + (sG - dG) * amount).toInt().coerceIn(0, 255)
    val b = (dB + (sB - dB) * amount).toInt().coerceIn(0, 255)
    return (a shl ALPHA_SHIFT) or (r shl RED_SHIFT) or (g shl GREEN_SHIFT) or b
}
