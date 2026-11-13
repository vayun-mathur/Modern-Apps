package com.vayunmathur.games.nonogram.ui.components

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.AwaitPointerEventScope
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.unit.IntSize
import com.vayunmathur.games.nonogram.platform.NonogramGameActions
import kotlin.math.floor

/**
 * Pure grid geometry for touch input.
 *
 * Kept free of Compose so the hit-testing can be unit tested directly, including the right-to-left
 * case: the grid's columns are laid out with `Row`, which mirrors under RTL, so physical x = 0 is
 * the *last* column there.
 */
internal object BoardGeometry {

    /**
     * The cell under ([x], [y]), in pixels from the top-left of a [gridPx]-square grid of [n] x [n].
     *
     * Points outside the grid clamp onto its nearest edge cell, so a drag that overshoots the board
     * keeps extending along the edge instead of dropping out.
     */
    fun cellAt(x: Float, y: Float, gridPx: Float, n: Int, rtl: Boolean): Int {
        val cellPx = gridPx / n
        val physicalCol = floor(x / cellPx).toInt().coerceIn(0, n - 1)
        val row = floor(y / cellPx).toInt().coerceIn(0, n - 1)
        val col = if (rtl) n - 1 - physicalCol else physicalCol
        return row * n + col
    }
}

/** How the opening moments of a touch resolved, before it became a stroke. */
private sealed interface Press {
    /** Lifted within the long-press timeout without leaving touch slop. */
    data object Tap : Press

    /** Left touch slop before the long-press timeout: a drag in the mode's own mark. */
    data class Drag(val position: Offset) : Press

    /** The pointer went away without lifting (the gesture was taken from us). */
    data object Cancelled : Press
}

/**
 * One gesture handler for the whole grid, turning touches into strokes on [actions].
 *
 *  - Tap: a one-cell stroke in the current mode.
 *  - Drag: begins once the finger leaves touch slop; the stroke follows the finger cell by cell.
 *  - Long press: [onLongPress] (haptics), then a stroke in the *other* mode, which keeps following
 *    the finger — so long-press-and-drag crosses a run in fill mode, or fills one in cross mode.
 *    This keeps long press meaning exactly what it did before ("the other mark") while extending it
 *    to runs, so the mode toggle stays the single answer to "what does a plain touch do".
 *
 * [n] and [rtl] are keys: the handler restarts if the grid size or layout direction changes.
 * Every change is consumed so an enclosing scroll or double-tap detector never sees grid touches.
 */
internal fun Modifier.strokeGestures(
    n: Int,
    rtl: Boolean,
    actions: () -> NonogramGameActions,
    onLongPress: () -> Unit,
): Modifier = pointerInput(n, rtl) {
    fun cellAt(position: Offset, size: IntSize) =
        BoardGeometry.cellAt(position.x, position.y, size.width.toFloat(), n, rtl)

    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        down.consume()
        val start = cellAt(down.position, size)
        val press = withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
            awaitTapOrDrag(down.id, down.position, viewConfiguration.touchSlop)
        }
        when (press) {
            Press.Tap -> {
                actions().beginStroke(start, alternate = false)
                actions().endStroke()
            }
            is Press.Drag -> {
                actions().beginStroke(start, alternate = false)
                actions().extendStroke(cellAt(press.position, size))
                followStroke(down.id, actions) { cellAt(it, size) }
            }
            null -> {
                onLongPress()
                actions().beginStroke(start, alternate = true)
                followStroke(down.id, actions) { cellAt(it, size) }
            }
            Press.Cancelled -> Unit
        }
    }
}

private suspend fun AwaitPointerEventScope.awaitTapOrDrag(id: PointerId, origin: Offset, slop: Float): Press {
    while (true) {
        val change = awaitPointerEvent().changes.firstOrNull { it.id == id } ?: return Press.Cancelled
        change.consume()
        if (!change.pressed) return Press.Tap
        if ((change.position - origin).getDistance() > slop) return Press.Drag(change.position)
    }
}

/** Feeds the finger's cell to the stroke until it lifts; always ends the stroke, even if cancelled. */
private suspend fun AwaitPointerEventScope.followStroke(
    id: PointerId,
    actions: () -> NonogramGameActions,
    cellAt: (Offset) -> Int,
) {
    try {
        while (true) {
            val change = awaitPointerEvent().changes.firstOrNull { it.id == id } ?: break
            // positionChanged() reads as false once consumed, so check before consuming.
            val moved = change.positionChanged()
            change.consume()
            if (!change.pressed) break
            if (moved) actions().extendStroke(cellAt(change.position))
        }
    } finally {
        actions().endStroke()
    }
}
