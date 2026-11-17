package com.vayunmathur.photos.data

import android.graphics.Bitmap
import com.vayunmathur.photos.platform.PhotosNative

/**
 * Content-aware fill: removes the region marked by [holeMask] (normalized, at
 * [maskW] x [maskH]) via native exemplar-based synthesis, falling back internally
 * to Jacobi diffusion for very large holes so the op stays bounded.
 */
fun inpaintBitmap(
    bitmap: Bitmap,
    holeMask: FloatArray,
    maskW: Int,
    maskH: Int,
    passes: Int = 60,
): Bitmap {
    val w = bitmap.width
    val h = bitmap.height
    val out = bitmap.copy(Bitmap.Config.ARGB_8888, true)
    val px = IntArray(w * h)
    out.getPixels(px, 0, w, 0, 0, w, h)
    val filled = PhotosNative.inpaint(px, w, h, holeMask, maskW, maskH, passes)
    out.setPixels(filled, 0, w, 0, 0, w, h)
    return out
}
