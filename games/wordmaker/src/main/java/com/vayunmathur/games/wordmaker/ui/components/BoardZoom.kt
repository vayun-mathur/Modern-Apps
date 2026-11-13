package com.vayunmathur.games.wordmaker.ui.components

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import kotlin.math.max
import kotlin.math.min

/**
 * Pinch-zoom state of the crossword board: a zoom relative to the fit-to-screen scale plus a
 * pan of the board's centre from the box's centre, both in pixels.
 *
 * Keeping the zoom relative to the fit means the fit can be recomputed whenever the box
 * changes size (rotation, split screen) without leaving a scale meant for the old size.
 */
internal data class BoardZoom(val zoom: Float = 1f, val pan: Offset = Offset.Zero) {

    /** Absolute layer scale for a board whose fit-to-box scale is [fit]. */
    fun scale(fit: Float): Float = fit * zoom

    /**
     * One pinch/drag step: [zoomChange] around [centroid] (relative to the box centre) plus
     * [panChange], clamped so the board can neither shrink below the fit, grow past
     * [maxZoom], nor be pushed off-screen.
     */
    fun transformed(
        fit: Float,
        board: Size,
        box: Size,
        centroid: Offset,
        zoomChange: Float,
        panChange: Offset,
    ): BoardZoom {
        val newZoom = (zoom * zoomChange).coerceIn(1f, maxZoom(fit))
        val applied = newZoom / zoom
        // Keep the board point under the centroid fixed while zooming, then apply the drag.
        val newPan = centroid - (centroid - pan) * applied + panChange
        return BoardZoom(newZoom, clampPan(newPan, board, box, fit * newZoom))
    }

    /** This zoom with its pan re-clamped for the current sizes (after a size change). */
    fun clampedTo(fit: Float, board: Size, box: Size): BoardZoom {
        val z = zoom.coerceIn(1f, maxZoom(fit))
        return BoardZoom(z, clampPan(pan, board, box, fit * z))
    }

    companion object {
        /** Zooming in goes to at least this many times the fit... */
        private const val MIN_MAX_ZOOM = 3f

        /** ...and always far enough for cells to reach this scale (natural size is 1). */
        private const val MAX_ABSOLUTE_SCALE = 2f

        /** Scale at which a [board] fits inside [box]; never above natural size. */
        fun fitScale(board: Size, box: Size): Float {
            val sx = if (board.width > 0f) box.width / board.width else 1f
            val sy = if (board.height > 0f) box.height / board.height else 1f
            return min(min(sx, sy), 1f).coerceAtLeast(MIN_FIT)
        }

        /** Largest zoom (relative to [fit]) a pinch may reach. */
        fun maxZoom(fit: Float): Float = max(MIN_MAX_ZOOM, MAX_ABSOLUTE_SCALE / fit)

        /**
         * Pan limits: a board larger than the box may move until its edge meets the box edge;
         * a board that fits stays centred on that axis.
         */
        fun clampPan(pan: Offset, board: Size, box: Size, scale: Float): Offset {
            val maxX = max(0f, (board.width * scale - box.width) / 2)
            val maxY = max(0f, (board.height * scale - box.height) / 2)
            return Offset(pan.x.coerceIn(-maxX, maxX), pan.y.coerceIn(-maxY, maxY))
        }

        /** Guards against a zero-size box (before the first layout) collapsing the scale. */
        private const val MIN_FIT = 0.05f
    }
}
