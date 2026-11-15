package com.vayunmathur.photos.data

import android.graphics.Bitmap
import kotlin.math.max
import kotlin.math.sqrt

private const val ALPHA_SHIFT = 24
private const val RED_SHIFT = 16
private const val GREEN_SHIFT = 8
private const val CHANNEL_MAX = 255
private const val RED_DOMINANCE_FACTOR = 1.5f
private const val DESATURATE_FACTOR = 0.8f

/** Immutable value describing red eye spot. */
data class RedEyeSpot(
    val x: Float,
    val y: Float,
    val radius: Float = 0.03f,
)

/** Immutable value describing red eye spots. */
data class RedEyeSpots(
    val spots: List<RedEyeSpot> = emptyList(),
) {
    fun isIdentity(): Boolean = spots.isEmpty()
}

fun RedEyeSpots.applyToBitmap(bitmap: Bitmap): Bitmap {
    val result = bitmap.copy(Bitmap.Config.ARGB_8888, true)
    val w = result.width
    val h = result.height
    val pixels = IntArray(w * h)
    result.getPixels(pixels, 0, w, 0, 0, w, h)
    for (spot in spots) {
        val cx = (spot.x * w).toInt()
        val cy = (spot.y * h).toInt()
        val radiusPx = (spot.radius * max(w, h)).coerceAtLeast(1f)
        val r0 = radiusPx.toInt().coerceAtLeast(1)
        for (dy in -r0..r0) {
            for (dx in -r0..r0) {
                val dist = sqrt((dx * dx + dy * dy).toFloat())
                if (dist > radiusPx) continue
                val feather = (1f - dist / radiusPx).coerceIn(0f, 1f)
                val tx = (cx + dx).coerceIn(0, w - 1)
                val ty = (cy + dy).coerceIn(0, h - 1)
                val idx = ty * w + tx
                val pixel = pixels[idx]
                val a = (pixel shr 24) and 0xFF
                val r = (pixel shr 16) and 0xFF
                val g = (pixel shr 8) and 0xFF
                val b = pixel and 0xFF
                if (r > RED_DOMINANCE_FACTOR * g && r > RED_DOMINANCE_FACTOR * b) {
                    val targetR = ((g + b) / 2f) * DESATURATE_FACTOR
                    val targetG = g * DESATURATE_FACTOR
                    val targetB = b * DESATURATE_FACTOR
                    val nr = (r + (targetR - r) * feather).toInt().coerceIn(0, 255)
                    val ng = (g + (targetG - g) * feather).toInt().coerceIn(0, 255)
                    val nb = (b + (targetB - b) * feather).toInt().coerceIn(0, 255)
                    pixels[idx] = (a shl ALPHA_SHIFT) or (nr shl RED_SHIFT) or (ng shl GREEN_SHIFT) or nb
                }
            }
        }
    }
    result.setPixels(pixels, 0, w, 0, 0, w, h)
    return result
}
