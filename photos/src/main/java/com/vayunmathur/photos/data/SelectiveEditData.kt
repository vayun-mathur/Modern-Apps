package com.vayunmathur.photos.data

import android.graphics.Bitmap
import kotlin.math.max
import kotlin.math.sqrt

enum class MaskType { Brush, RadialGradient, LinearGradient }

data class SelectiveMask(
    val type: MaskType = MaskType.Brush,
    val brushPoints: List<Pair<Float, Float>> = emptyList(),
    val brushSize: Float = 0.05f,
    val centerX: Float = 0.5f,
    val centerY: Float = 0.5f,
    val radius: Float = 0.3f,
    val angle: Float = 0f,
    val adjustments: ImageAdjustments = ImageAdjustments(),
)

data class SelectiveEdits(
    val masks: List<SelectiveMask> = emptyList(),
) {
    fun isIdentity(): Boolean = masks.isEmpty()
}

private fun generateSelectiveMask(mask: SelectiveMask, w: Int, h: Int): FloatArray {
    val alpha = FloatArray(w * h)
    when (mask.type) {
        MaskType.Brush -> {
            val brushPx = mask.brushSize * max(w, h)
            for ((bx, by) in mask.brushPoints) {
                val px = (bx * w).toInt()
                val py = (by * h).toInt()
                val r = brushPx.toInt()
                for (dy in -r..r) {
                    for (dx in -r..r) {
                        val x = px + dx
                        val y = py + dy
                        if (x in 0 until w && y in 0 until h) {
                            val dist = sqrt((dx * dx + dy * dy).toFloat())
                            if (dist <= brushPx) {
                                val strength = (1f - dist / brushPx).coerceIn(0f, 1f)
                                val idx = y * w + x
                                alpha[idx] = maxOf(alpha[idx], strength)
                            }
                        }
                    }
                }
            }
        }
        MaskType.RadialGradient -> {
            val cx = mask.centerX * w
            val cy = mask.centerY * h
            val r = mask.radius * max(w, h)
            for (y in 0 until h) {
                for (x in 0 until w) {
                    val dist = sqrt((x - cx) * (x - cx) + (y - cy) * (y - cy))
                    alpha[y * w + x] = (1f - dist / r.coerceAtLeast(1f)).coerceIn(0f, 1f)
                }
            }
        }
        MaskType.LinearGradient -> {
            val cx = mask.centerX * w
            val cy = mask.centerY * h
            val r = mask.radius * max(w, h)
            val rad = Math.toRadians(mask.angle.toDouble())
            val nx = -kotlin.math.sin(rad).toFloat()
            val ny = kotlin.math.cos(rad).toFloat()
            for (y in 0 until h) {
                for (x in 0 until w) {
                    val dist = kotlin.math.abs((x - cx) * nx + (y - cy) * ny)
                    alpha[y * w + x] = (1f - dist / r.coerceAtLeast(1f)).coerceIn(0f, 1f)
                }
            }
        }
    }
    return alpha
}

fun SelectiveEdits.applySelectiveEdits(bitmap: Bitmap): Bitmap {
    var result = bitmap.copy(Bitmap.Config.ARGB_8888, true)
    val w = result.width
    val h = result.height
    for (mask in masks) {
        if (mask.adjustments == ImageAdjustments()) continue
        val adjusted = mask.adjustments.applyToBitmap(result.copy(Bitmap.Config.ARGB_8888, true))
        val alpha = generateSelectiveMask(mask, w, h)
        val origPixels = IntArray(w * h)
        result.getPixels(origPixels, 0, w, 0, 0, w, h)
        val adjPixels = IntArray(w * h)
        adjusted.getPixels(adjPixels, 0, w, 0, 0, w, h)
        for (i in origPixels.indices) {
            val m = alpha[i]
            if (m <= 0f) continue
            val oA = (origPixels[i] shr 24) and 0xFF
            val oR = (origPixels[i] shr 16) and 0xFF
            val oG = (origPixels[i] shr 8) and 0xFF
            val oB = origPixels[i] and 0xFF
            val aR = (adjPixels[i] shr 16) and 0xFF
            val aG = (adjPixels[i] shr 8) and 0xFF
            val aB = adjPixels[i] and 0xFF
            val r = (oR + (aR - oR) * m).toInt().coerceIn(0, 255)
            val g = (oG + (aG - oG) * m).toInt().coerceIn(0, 255)
            val b = (oB + (aB - oB) * m).toInt().coerceIn(0, 255)
            origPixels[i] = (oA shl 24) or (r shl 16) or (g shl 8) or b
        }
        result.setPixels(origPixels, 0, w, 0, 0, w, h)
        adjusted.recycle()
    }
    return result
}
