package com.vayunmathur.photos.data

import android.graphics.Bitmap
import kotlin.math.max
import kotlin.math.sqrt

private const val ALPHA_SHIFT = 24
private const val RED_SHIFT = 16
private const val GREEN_SHIFT = 8

/** Kinds of mask type. */
enum class MaskType { Brush, RadialGradient, LinearGradient }

/** Immutable value describing selective mask. */
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

/** Immutable value describing selective edits. */
data class SelectiveEdits(
    val masks: List<SelectiveMask> = emptyList(),
) {
    fun isIdentity(): Boolean = masks.isEmpty()
}

private fun generateSelectiveMask(mask: SelectiveMask, w: Int, h: Int): FloatArray {
    val alpha = FloatArray(w * h)
    when (mask.type) {
        MaskType.Brush -> paintBrushMask(mask, alpha, w, h)
        MaskType.RadialGradient -> paintRadialMask(mask, alpha, w, h)
        MaskType.LinearGradient -> paintLinearMask(mask, alpha, w, h)
    }
    return alpha
}

private fun paintBrushMask(mask: SelectiveMask, alpha: FloatArray, w: Int, h: Int) {
    val brushPx = mask.brushSize * max(w, h)
    for ((bx, by) in mask.brushPoints) {
        val px = (bx * w).toInt()
        val py = (by * h).toInt()
        paintBrushDab(alpha, w, h, brushPx, px, py)
    }
}

private fun paintBrushDab(alpha: FloatArray, w: Int, h: Int, brushPx: Float, px: Int, py: Int) {
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

private fun paintRadialMask(mask: SelectiveMask, alpha: FloatArray, w: Int, h: Int) {
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

private fun paintLinearMask(mask: SelectiveMask, alpha: FloatArray, w: Int, h: Int) {
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

/**
 * Zero-copy entry point: applies each masked adjustment in place to [pixels]
 * and returns it, or returns null if any mask's adjustment has no
 * pixel-identical array path (see [ImageAdjustments.applyToPixels]) and the
 * caller must fall back to [applySelectiveEdits].
 */
fun SelectiveEdits.applyToPixels(pixels: IntArray, width: Int, height: Int): IntArray? {
    val w = width
    val h = height
    for (mask in masks) {
        if (mask.adjustments == ImageAdjustments()) continue
        val adjPixels = mask.adjustments.applyToPixels(pixels.copyOf(), w, h) ?: return null
        val alpha = generateSelectiveMask(mask, w, h)
        for (i in pixels.indices) {
            val m = alpha[i]
            if (m <= 0f) continue
            val oA = (pixels[i] shr 24) and 0xFF
            val oR = (pixels[i] shr 16) and 0xFF
            val oG = (pixels[i] shr 8) and 0xFF
            val oB = pixels[i] and 0xFF
            val aR = (adjPixels[i] shr 16) and 0xFF
            val aG = (adjPixels[i] shr 8) and 0xFF
            val aB = adjPixels[i] and 0xFF
            val r = (oR + (aR - oR) * m).toInt().coerceIn(0, 255)
            val g = (oG + (aG - oG) * m).toInt().coerceIn(0, 255)
            val b = (oB + (aB - oB) * m).toInt().coerceIn(0, 255)
            pixels[i] = (oA shl ALPHA_SHIFT) or (r shl RED_SHIFT) or (g shl GREEN_SHIFT) or b
        }
    }
    return pixels
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
            origPixels[i] = (oA shl ALPHA_SHIFT) or (r shl RED_SHIFT) or (g shl GREEN_SHIFT) or b
        }
        result.setPixels(origPixels, 0, w, 0, 0, w, h)
        adjusted.recycle()
    }
    return result
}
