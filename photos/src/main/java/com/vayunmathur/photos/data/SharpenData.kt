package com.vayunmathur.photos.data

import android.graphics.Bitmap
import androidx.core.graphics.createBitmap
import com.vayunmathur.photos.util.PhotosNative

/** Immutable value describing unsharp mask. */
data class UnsharpMask(
    val amount: Float = 0f,
    val radius: Float = 2f,
    val threshold: Int = 0,
) {
    fun isIdentity(): Boolean = amount == 0f
}

fun UnsharpMask.applyToBitmap(bitmap: Bitmap): Bitmap {
    val w = bitmap.width
    val h = bitmap.height
    val pixels = IntArray(w * h)
    bitmap.getPixels(pixels, 0, w, 0, 0, w, h)
    val out = PhotosNative.unsharp(pixels, w, h, amount, radius, threshold)
    return createBitmap(w, h).apply { setPixels(out, 0, w, 0, 0, w, h) }
}
