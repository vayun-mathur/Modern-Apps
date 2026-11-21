package com.vayunmathur.photos.data

import android.graphics.Bitmap
import androidx.core.graphics.createBitmap
import com.vayunmathur.photos.platform.PhotosNative

/** Kinds of blur mode. */
enum class BlurMode { Radial, Linear, Lens }

/** Immutable value describing blur params. */
data class BlurParams(
    val mode: BlurMode = BlurMode.Radial,
    val centerX: Float = 0.5f,
    val centerY: Float = 0.5f,
    val radius: Float = 0.3f,
    val intensity: Float = 10f,
    val feather: Float = 0.3f,
    val angle: Float = 0f,
) {
    fun isIdentity(): Boolean = intensity == 0f
}

/** Zero-copy entry point: runs the native blur in place on [pixels] and returns it. */
fun BlurParams.applyToPixels(pixels: IntArray, width: Int, height: Int): IntArray {
    PhotosNative.blurParamsInPlace(pixels, width, height, mode.ordinal, centerX, centerY, radius, intensity, feather, angle)
    return pixels
}

fun BlurParams.applyBlurToBitmap(bitmap: Bitmap): Bitmap {
    val w = bitmap.width
    val h = bitmap.height
    val pixels = IntArray(w * h)
    bitmap.getPixels(pixels, 0, w, 0, 0, w, h)
    val out = PhotosNative.blurParams(pixels, w, h, mode.ordinal, centerX, centerY, radius, intensity, feather, angle)
    return createBitmap(w, h).apply { setPixels(out, 0, w, 0, 0, w, h) }
}

// --- Full-image filter blurs ----------------------------------------------------

/** Kinds of filter blur mode. */
enum class FilterBlurMode { Gaussian, Motion, Radial, Spin }

/** Full-image blur filter (destructive), distinct from the masked [BlurParams] lens blur. */
data class FilterBlur(
    val mode: FilterBlurMode = FilterBlurMode.Gaussian,
    val amount: Float = 0f,
    val angle: Float = 0f,
    val centerX: Float = 0.5f,
    val centerY: Float = 0.5f,
) {
    fun isIdentity(): Boolean = amount == 0f
}

/** Zero-copy entry point: runs the native filter blur in place on [pixels] and returns it. */
fun FilterBlur.applyToPixels(pixels: IntArray, width: Int, height: Int): IntArray {
    if (!isIdentity()) {
        PhotosNative.filterBlurInPlace(pixels, width, height, mode.ordinal, amount, angle, centerX, centerY)
    }
    return pixels
}

fun FilterBlur.applyToBitmap(bitmap: Bitmap): Bitmap {
    if (isIdentity()) return bitmap.copy(Bitmap.Config.ARGB_8888, true)
    val w = bitmap.width
    val h = bitmap.height
    val src = IntArray(w * h)
    bitmap.getPixels(src, 0, w, 0, 0, w, h)
    val out = PhotosNative.filterBlur(src, w, h, mode.ordinal, amount, angle, centerX, centerY)
    return createBitmap(w, h).apply { setPixels(out, 0, w, 0, 0, w, h) }
}
