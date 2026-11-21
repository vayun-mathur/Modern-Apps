package com.vayunmathur.photos.data

import android.graphics.Bitmap

private const val ALPHA_SHIFT = 24
private const val RED_SHIFT = 16
private const val GREEN_SHIFT = 8
private const val CHANNEL_MAX = 255

/** Immutable value describing channel mixer adjustment. */
data class ChannelMixerAdjustment(
    val rFromR: Float = 1f,
    val rFromG: Float = 0f,
    val rFromB: Float = 0f,
    val gFromR: Float = 0f,
    val gFromG: Float = 1f,
    val gFromB: Float = 0f,
    val bFromR: Float = 0f,
    val bFromG: Float = 0f,
    val bFromB: Float = 1f,
    val monochrome: Boolean = false,
) {
    fun isIdentity(): Boolean =
        !monochrome &&
            rFromR == 1f && rFromG == 0f && rFromB == 0f &&
            gFromR == 0f && gFromG == 1f && gFromB == 0f &&
            bFromR == 0f && bFromG == 0f && bFromB == 1f
}

/** Zero-copy entry point: applies the channel mix in place to [pixels] and returns it. */
fun ChannelMixerAdjustment.applyToPixels(pixels: IntArray, width: Int, height: Int): IntArray {
    for (i in pixels.indices) {
        val p = pixels[i]
        val a = (p ushr 24) and 0xFF
        val r = (p ushr 16) and 0xFF
        val g = (p ushr 8) and 0xFF
        val b = p and 0xFF

        val nr: Int
        val ng: Int
        val nb: Int
        if (monochrome) {
            val gray = (rFromR * r + rFromG * g + rFromB * b).toInt().coerceIn(0, 255)
            nr = gray
            ng = gray
            nb = gray
        } else {
            nr = (rFromR * r + rFromG * g + rFromB * b).toInt().coerceIn(0, CHANNEL_MAX)
            ng = (gFromR * r + gFromG * g + gFromB * b).toInt().coerceIn(0, CHANNEL_MAX)
            nb = (bFromR * r + bFromG * g + bFromB * b).toInt().coerceIn(0, CHANNEL_MAX)
        }

        pixels[i] = (a shl ALPHA_SHIFT) or (nr shl RED_SHIFT) or (ng shl GREEN_SHIFT) or nb
    }
    return pixels
}

fun ChannelMixerAdjustment.applyToBitmap(bitmap: Bitmap): Bitmap {
    val result = bitmap.copy(Bitmap.Config.ARGB_8888, true)
    val pixels = IntArray(result.width * result.height)
    result.getPixels(pixels, 0, result.width, 0, 0, result.width, result.height)
    applyToPixels(pixels, result.width, result.height)
    result.setPixels(pixels, 0, result.width, 0, 0, result.width, result.height)
    return result
}
