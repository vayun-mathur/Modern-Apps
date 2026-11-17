package com.vayunmathur.photos.platform

import android.graphics.Bitmap
import com.vayunmathur.library.image.Size
import com.vayunmathur.library.image.Transformation

/**
 * Transformation that crops a decoded bitmap down to a single face, using a
 * bounding box in normalised (0..1) coordinates. Used to show a person-cluster's
 * representative face as a thumbnail without storing a separate cropped image.
 *
 * A `data class` so two transformations with the same box compare equal — this
 * keeps a remembered [com.vayunmathur.library.image.ImageRequest] stable across recompositions
 * (no needless re-decode / thumbnail flashing).
 */
data class FaceCropTransformation(
    private val left: Float,
    private val top: Float,
    private val right: Float,
    private val bottom: Float,
) : Transformation {

    override val cacheKey: String = "face_crop_${left}_${top}_${right}_${bottom}"

    override suspend fun transform(input: Bitmap, size: Size): Bitmap {
        val l = (left * input.width).toInt().coerceIn(0, input.width - 1)
        val t = (top * input.height).toInt().coerceIn(0, input.height - 1)
        val r = (right * input.width).toInt().coerceIn(l + 1, input.width)
        val b = (bottom * input.height).toInt().coerceIn(t + 1, input.height)
        return Bitmap.createBitmap(input, l, t, r - l, b - t)
    }
}
