package com.vayunmathur.games.nonogram.ui.components

import kotlin.math.floor
import kotlin.math.max

/**
 * Pinch-zoom and pan of the grid, in units of the grid's own side.
 *
 * A content point `c` (0..1 across the unzoomed grid) is drawn at `c * scale + offset`. Storing the
 * offset as a fraction of the side rather than in pixels is what lets the same zoom survive a size
 * change — rotation, a window resize, a fold — and still show the same cells.
 *
 * Clamped so the zoomed grid always covers its viewport: scale in `1..maxScale` and each offset in
 * `(1 - scale)..0`, which means no empty margin can ever be panned into view.
 */
data class BoardZoom(
    val scale: Float = 1f,
    val offsetX: Float = 0f,
    val offsetY: Float = 0f,
) {
    val isZoomed: Boolean get() = scale > 1f

    /**
     * Applies one [frame] of a two-finger gesture, keeping the scale within `1..maxScale`.
     *
     * The content point that was under the previous centroid ends up under the current one, scaled
     * about it — so the grid zooms where the fingers are and follows them as they move.
     */
    fun transformed(frame: PinchFrame, maxScale: Float): BoardZoom {
        val newScale = (scale * frame.factor).coerceIn(1f, max(1f, maxScale))
        val contentX = (frame.centroidX - frame.panX - offsetX) / scale
        val contentY = (frame.centroidY - frame.panY - offsetY) / scale
        return BoardZoom(
            scale = newScale,
            offsetX = frame.centroidX - contentX * newScale,
            offsetY = frame.centroidY - contentY * newScale,
        ).clamped(maxScale)
    }

    /** This zoom pulled back inside the limits for a grid whose largest scale is [maxScale]. */
    fun clamped(maxScale: Float): BoardZoom {
        val s = scale.coerceIn(1f, max(1f, maxScale))
        val least = 1f - s
        return BoardZoom(s, offsetX.coerceIn(least, 0f), offsetY.coerceIn(least, 0f))
    }

    companion object {
        val Identity = BoardZoom()
    }
}

/**
 * One frame of a two-finger gesture, as fractions of the grid's side.
 *
 * @property centroidX where the fingers' centre is now, across.
 * @property centroidY where the fingers' centre is now, down.
 * @property panX how far that centre moved across this frame.
 * @property panY how far that centre moved down this frame.
 * @property factor this frame's pinch ratio (1 = no zoom change).
 */
data class PinchFrame(
    val centroidX: Float,
    val centroidY: Float,
    val panX: Float = 0f,
    val panY: Float = 0f,
    val factor: Float = 1f,
)

/**
 * Pure geometry of the grid's on-screen square, for touch input and clue placement.
 *
 * Kept free of Compose so hit-testing can be unit tested directly, including the right-to-left case:
 * the grid's columns are laid out with `Row`, which mirrors under RTL, so physical x = 0 is the
 * *last* column there. [zoom] is a physical transform applied on top of that mirrored layout.
 *
 * @property sidePx the viewport's side in pixels (the grid is square).
 * @property n cells per side.
 * @property rtl whether the layout direction is right-to-left.
 * @property zoom the current pinch-zoom and pan.
 */
internal data class GridViewport(
    val sidePx: Float,
    val n: Int,
    val rtl: Boolean,
    val zoom: BoardZoom = BoardZoom.Identity,
) {
    private val cellPx: Float get() = sidePx / n

    /**
     * The cell under ([x], [y]), in pixels from the viewport's top-left.
     *
     * The point is clamped to the viewport first and then mapped into grid coordinates, so a drag that
     * overshoots the board — or the visible part of a zoomed board — keeps extending along the edge it
     * left by, and never edits a cell that is scrolled out of view.
     */
    fun cellAt(x: Float, y: Float): Int {
        val inside = sidePx - EDGE_EPSILON
        val contentX = (x.coerceIn(0f, inside) - zoom.offsetX * sidePx) / zoom.scale
        val contentY = (y.coerceIn(0f, inside) - zoom.offsetY * sidePx) / zoom.scale
        val physicalCol = floor(contentX / cellPx).toInt().coerceIn(0, n - 1)
        val row = floor(contentY / cellPx).toInt().coerceIn(0, n - 1)
        val col = if (rtl) n - 1 - physicalCol else physicalCol
        return row * n + col
    }

    /** Where the centre of column [col] is drawn, across: what its clue stack is centred on. */
    fun columnCenter(col: Int): Float {
        val content = if (rtl) sidePx - (col + HALF) * cellPx else (col + HALF) * cellPx
        return content * zoom.scale + zoom.offsetX * sidePx
    }

    /** Where the centre of row [row] is drawn, down: what its clue stack is centred on. */
    fun rowCenter(row: Int): Float = (row + HALF) * cellPx * zoom.scale + zoom.offsetY * sidePx

    /** Where grid line [i] (0..n, from the physical left or top) is drawn, along [offset]'s axis. */
    fun linePosition(i: Int, offset: Float): Float = i * cellPx * zoom.scale + offset * sidePx

    private companion object {
        /** Keeps a clamped point strictly inside the last cell rather than on its far edge. */
        const val EDGE_EPSILON = 0.01f
        const val HALF = 0.5f
    }
}
