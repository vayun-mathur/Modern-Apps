package com.vayunmathur.games.logicgate.ui.canvas

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size

/** Edge auto-pan while a wire or gate is carried towards something off screen. */
object AutoPan {

    /**
     * How fast to pan the camera, in pixels per millisecond, for a finger at [pos] in a
     * [viewport] (both canvas pixels). Zero unless the finger is inside the viewport and within
     * [edge] pixels of a side; it then grows linearly to [maxSpeed] at that side. The sign moves
     * the board content away from that side, revealing what lies beyond it.
     */
    fun velocity(pos: Offset, viewport: Size, edge: Float, maxSpeed: Float): Offset {
        if (viewport.width <= 0f || viewport.height <= 0f) return Offset.Zero
        if (pos.x !in 0f..viewport.width || pos.y !in 0f..viewport.height) return Offset.Zero
        return Offset(axis(pos.x, viewport.width, edge, maxSpeed), axis(pos.y, viewport.height, edge, maxSpeed))
    }

    private fun axis(p: Float, length: Float, edge: Float, maxSpeed: Float): Float {
        // A tiny viewport must still keep a dead middle, or every touch would pan.
        val zone = minOf(edge, length / MIN_ZONE_DIVISOR)
        if (zone <= 0f) return 0f
        return when {
            p < zone -> (zone - p) / zone * maxSpeed
            p > length - zone -> -(p - (length - zone)) / zone * maxSpeed
            else -> 0f
        }
    }

    private const val MIN_ZONE_DIVISOR = 4f
}
