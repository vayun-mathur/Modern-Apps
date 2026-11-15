package com.vayunmathur.photos.data

import android.graphics.Bitmap
import kotlin.math.pow

private const val ALPHA_SHIFT = 24
private const val RED_SHIFT = 16
private const val GREEN_SHIFT = 8
private const val CHANNEL_MAX = 255
private const val CHANNEL_MAX_F = 255f
private const val LUT_SIZE = 256
private const val MIN_GAMMA = 0.01f

/** Immutable value describing levels adjustment. */
data class LevelsAdjustment(
    val inBlack: Float = 0f,
    val inWhite: Float = 255f,
    val gamma: Float = 1f,
    val outBlack: Float = 0f,
    val outWhite: Float = 255f,
) {
    fun isIdentity(): Boolean =
        inBlack == 0f && inWhite == CHANNEL_MAX_F && gamma == 1f && outBlack == 0f && outWhite == CHANNEL_MAX_F
}

fun LevelsAdjustment.applyToBitmap(bitmap: Bitmap): Bitmap {
    val lut = IntArray(LUT_SIZE)
    val range = (inWhite - inBlack).coerceAtLeast(1f)
    val invGamma = 1f / gamma.coerceAtLeast(MIN_GAMMA)
    for (v in 0..CHANNEL_MAX) {
        var n = ((v - inBlack) / range).coerceIn(0f, 1f)
        n = n.pow(invGamma)
        lut[v] = (outBlack + n * (outWhite - outBlack)).toInt().coerceIn(0, CHANNEL_MAX)
    }

    return bitmap.copy(Bitmap.Config.ARGB_8888, true).apply {
        mapPixels { p ->
            val a = (p ushr 24) and 0xFF
            val r = (p ushr 16) and 0xFF
            val g = (p ushr 8) and 0xFF
            val b = p and 0xFF
            (a shl ALPHA_SHIFT) or (lut[r] shl RED_SHIFT) or (lut[g] shl GREEN_SHIFT) or lut[b]
        }
    }
}
