package com.vayunmathur.pdf.data

import android.net.Uri
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect

/** Immutable value describing quadrilateral. */
data class Quadrilateral(
    val topLeft: Offset,
    val topRight: Offset,
    val bottomRight: Offset,
    val bottomLeft: Offset
) {
    /** Shared constants and helpers for Quadrilateral. */
    companion object {
        fun default() = Quadrilateral(
                topLeft = Offset(0f, 0f),
                topRight = Offset(1f, 0f),
                bottomRight = Offset(1f, 1f),
                bottomLeft = Offset(0f, 1f)
            )
    }
    
    fun toBoundingRect(): Rect {
        val left = minOf(topLeft.x, topRight.x, bottomRight.x, bottomLeft.x)
        val top = minOf(topLeft.y, topRight.y, bottomRight.y, bottomLeft.y)
        val right = maxOf(topLeft.x, topRight.x, bottomRight.x, bottomLeft.x)
        val bottom = maxOf(topLeft.y, topRight.y, bottomRight.y, bottomLeft.y)
        return Rect(left, top, right, bottom)
    }

    fun corners(): List<Offset> = listOf(topLeft, topRight, bottomRight, bottomLeft)

    fun corner(index: Int): Offset = when (index) {
        0 -> topLeft; 1 -> topRight; 2 -> bottomRight; else -> bottomLeft
    }

    fun withCorner(index: Int, offset: Offset): Quadrilateral = when (index) {
        0 -> copy(topLeft = offset); 1 -> copy(topRight = offset)
        2 -> copy(bottomRight = offset); else -> copy(bottomLeft = offset)
    }

    fun toSrcPoints(width: Int, height: Int): FloatArray {
        val w = width.toFloat(); val h = height.toFloat()
        return floatArrayOf(
            topLeft.x * w, topLeft.y * h, topRight.x * w, topRight.y * h,
            bottomRight.x * w, bottomRight.y * h, bottomLeft.x * w, bottomLeft.y * h
        )
    }
}

/** Immutable value describing captured image. */
data class CapturedImage(
    val uri: Uri,
    val cropRect: Rect? = null,
    val quadrilateral: Quadrilateral? = null
)
