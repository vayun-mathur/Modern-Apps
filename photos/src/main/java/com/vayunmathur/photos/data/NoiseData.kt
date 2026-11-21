package com.vayunmathur.photos.data

import android.graphics.Bitmap
import androidx.core.graphics.createBitmap

private const val ALPHA_SHIFT = 24
private const val RED_SHIFT = 16
private const val GREEN_SHIFT = 8
private const val CHANNEL_MAX = 255
private const val NOISE_SCALE = 80f
private const val PERCENT_DIVISOR = 100f
private const val NOISE_SEED = 42

/** Immutable value describing noise params. */
data class NoiseParams(
    val amount: Float = 0f,
    val monochrome: Boolean = true,
) {
    fun isIdentity(): Boolean = amount == 0f
}

/** Zero-copy entry point: applies noise in place to [pixels] and returns it. */
fun NoiseParams.applyToPixels(pixels: IntArray, width: Int, height: Int): IntArray {
    val intensity = (amount / PERCENT_DIVISOR * NOISE_SCALE).toInt()
    val random = kotlin.random.Random(NOISE_SEED)

    for (i in pixels.indices) {
        val p = pixels[i]
        val a = (p ushr 24) and 0xFF
        val origR = (p ushr 16) and 0xFF
        val origG = (p ushr 8) and 0xFF
        val origB = p and 0xFF

        val r: Int
        val g: Int
        val b: Int
        if (monochrome) {
            val n = random.nextInt(-intensity, intensity + 1)
            r = (origR + n).coerceIn(0, CHANNEL_MAX)
            g = (origG + n).coerceIn(0, CHANNEL_MAX)
            b = (origB + n).coerceIn(0, CHANNEL_MAX)
        } else {
            r = (origR + random.nextInt(-intensity, intensity + 1)).coerceIn(0, CHANNEL_MAX)
            g = (origG + random.nextInt(-intensity, intensity + 1)).coerceIn(0, CHANNEL_MAX)
            b = (origB + random.nextInt(-intensity, intensity + 1)).coerceIn(0, CHANNEL_MAX)
        }

        pixels[i] = (a shl ALPHA_SHIFT) or (r shl RED_SHIFT) or (g shl GREEN_SHIFT) or b
    }
    return pixels
}

fun NoiseParams.applyToBitmap(bitmap: Bitmap): Bitmap {
    val w = bitmap.width
    val h = bitmap.height
    val pixels = IntArray(w * h)
    bitmap.getPixels(pixels, 0, w, 0, 0, w, h)
    applyToPixels(pixels, w, h)
    val result = createBitmap(w, h)
    result.setPixels(pixels, 0, w, 0, 0, w, h)
    return result
}
