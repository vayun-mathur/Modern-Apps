package com.vayunmathur.pdf.model

import android.net.Uri
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect

/**
 * Represents a quadrilateral with 4 independent corner points.
 * Points are in normalized coordinates (0f..1f) relative to the image dimensions.
 * Order: top-left, top-right, bottom-right, bottom-left (clockwise)
 */
data class Quadrilateral(
    val topLeft: Offset,
    val topRight: Offset,
    val bottomRight: Offset,
    val bottomLeft: Offset
) {
    companion object {
        /** Creates a quadrilateral from a Rect (for backward compatibility) */
        fun fromRect(rect: Rect): Quadrilateral {
            return Quadrilateral(
                topLeft = Offset(rect.left, rect.top),
                topRight = Offset(rect.right, rect.top),
                bottomRight = Offset(rect.right, rect.bottom),
                bottomLeft = Offset(rect.left, rect.bottom)
            )
        }
        
        /** Creates a default quadrilateral covering the full image */
        fun default(): Quadrilateral {
            return Quadrilateral(
                topLeft = Offset(0f, 0f),
                topRight = Offset(1f, 0f),
                bottomRight = Offset(1f, 1f),
                bottomLeft = Offset(0f, 1f)
            )
        }
    }
    
    /** Converts to Rect by computing the bounding box (for backward compatibility) */
    fun toBoundingRect(): Rect {
        val left = minOf(topLeft.x, topRight.x, bottomRight.x, bottomLeft.x)
        val top = minOf(topLeft.y, topRight.y, bottomRight.y, bottomLeft.y)
        val right = maxOf(topLeft.x, topRight.x, bottomRight.x, bottomLeft.x)
        val bottom = maxOf(topLeft.y, topRight.y, bottomRight.y, bottomLeft.y)
        return Rect(left, top, right, bottom)
    }
}

data class CapturedImage(
    val uri: Uri,
    val cropRect: Rect? = null,
    val quadrilateral: Quadrilateral? = null
)
