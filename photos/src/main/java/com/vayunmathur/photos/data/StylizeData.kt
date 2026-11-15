package com.vayunmathur.photos.data

import android.graphics.Bitmap
import androidx.core.graphics.createBitmap
import com.vayunmathur.photos.util.PhotosNative

/** Kinds of stylize mode. */
enum class StylizeMode { None, FindEdges, Emboss }

/** Immutable value describing stylize params. */
data class StylizeParams(
    val mode: StylizeMode = StylizeMode.None,
) {
    fun isIdentity(): Boolean = mode == StylizeMode.None
}

fun StylizeParams.applyToBitmap(bitmap: Bitmap): Bitmap {
    val w = bitmap.width
    val h = bitmap.height
    val pixels = IntArray(w * h)
    bitmap.getPixels(pixels, 0, w, 0, 0, w, h)
    val out = PhotosNative.stylize(pixels, w, h, mode.ordinal)
    return createBitmap(w, h).apply { setPixels(out, 0, w, 0, 0, w, h) }
}
