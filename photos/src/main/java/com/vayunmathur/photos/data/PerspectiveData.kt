package com.vayunmathur.photos.data

import android.graphics.Bitmap
import android.graphics.Matrix

private const val CORNER_POINT_COUNT = 4

/** Immutable value describing perspective corners. */
data class PerspectiveCorners(
    val topLeft: Pair<Float, Float> = 0f to 0f,
    val topRight: Pair<Float, Float> = 1f to 0f,
    val bottomLeft: Pair<Float, Float> = 0f to 1f,
    val bottomRight: Pair<Float, Float> = 1f to 1f,
) {
    fun isIdentity(): Boolean =
        topLeft == (0f to 0f) && topRight == (1f to 0f) &&
        bottomLeft == (0f to 1f) && bottomRight == (1f to 1f)

    fun toMatrix(width: Float, height: Float): Matrix {
        val src = floatArrayOf(
            0f, 0f,
            width, 0f,
            0f, height,
            width, height,
        )
        val dst = floatArrayOf(
            topLeft.first * width, topLeft.second * height,
            topRight.first * width, topRight.second * height,
            bottomLeft.first * width, bottomLeft.second * height,
            bottomRight.first * width, bottomRight.second * height,
        )
        val matrix = Matrix()
        matrix.setPolyToPoly(src, 0, dst, 0, CORNER_POINT_COUNT)
        return matrix
    }
}

fun PerspectiveCorners.applyPerspectiveToBitmap(bitmap: Bitmap): Bitmap {
    val w = bitmap.width.toFloat()
    val h = bitmap.height.toFloat()
    val matrix = toMatrix(w, h)
    return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
}
