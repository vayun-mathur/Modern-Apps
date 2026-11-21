package com.vayunmathur.photos.data

import android.graphics.Bitmap
import androidx.core.graphics.createBitmap
import com.vayunmathur.photos.platform.PhotosNative

/** Kinds of stylize mode. */
enum class StylizeMode { None, FindEdges, Emboss }

/** Immutable value describing stylize params. */
data class StylizeParams(
    val mode: StylizeMode = StylizeMode.None,
) {
    fun isIdentity(): Boolean = mode == StylizeMode.None
}

/** Zero-copy entry point: runs native stylize in place on [pixels] and returns it. */
fun StylizeParams.applyToPixels(pixels: IntArray, width: Int, height: Int): IntArray {
    PhotosNative.stylizeInPlace(pixels, width, height, mode.ordinal)
    return pixels
}

fun StylizeParams.applyToBitmap(bitmap: Bitmap): Bitmap {
    val w = bitmap.width
    val h = bitmap.height
    val pixels = IntArray(w * h)
    bitmap.getPixels(pixels, 0, w, 0, 0, w, h)
    val out = PhotosNative.stylize(pixels, w, h, mode.ordinal)
    return createBitmap(w, h).apply { setPixels(out, 0, w, 0, 0, w, h) }
}
