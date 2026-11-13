package com.vayunmathur.games.chess

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitLongPressOrCancellation
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.AwaitPointerEventScope
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.pointerInput
import com.vayunmathur.games.chess.data.Position

// File-local copy of the board dimension (each screen file keeps its own
// file-private BOARD_SQUARES = 8; this file follows the same convention).
private const val BOARD_SQUARES = 8

/**
 * Drag-to-move state for [BoardGrid]. The board is square, so one measured width maps
 * any touch offset to a square.
 *
 * The gesture modifier sits *outside* the board's 180-degree `graphicsLayer` rotation, so
 * it receives unrotated coordinates: on a flipped board the top-left corner the finger
 * touches is h1 (row 7, col 7), not a8. [squareAt] therefore mirrors both axes when
 * [flipped] is set. The board also forces LTR, so x always grows towards the h-file
 * (or the a-file when flipped) regardless of the locale's layout direction.
 */
internal class BoardDragState {
    /** Whether the board is drawn rotated 180 degrees (the player sits on Black). */
    var flipped: Boolean by mutableStateOf(false)

    /** Press square of the in-progress long-press drag; null when idle. Drives hint dots. */
    var source: Position? by mutableStateOf(null)

    /** Measured board width in px, set from `onSizeChanged`; mapping is dormant until set. */
    var boardPx: Float by mutableFloatStateOf(0f)

    fun squareAt(offset: Offset): Position? {
        val width = boardPx
        if (width <= 0f) return null
        if (offset.x < 0f || offset.y < 0f) return null
        val cell = width / BOARD_SQUARES
        val col = (offset.x / cell).toInt()
        val row = (offset.y / cell).toInt()
        if (row !in 0 until BOARD_SQUARES || col !in 0 until BOARD_SQUARES) return null
        val last = BOARD_SQUARES - 1
        return if (flipped) Position(last - row, last - col) else Position(row, col)
    }
}

/**
 * Long-press-drag to move, alongside the per-square tap handler (which this never
 * replaces). A quick tap never reaches the long-press, so it behaves exactly as before.
 * A long-press that lifts without moving stays silent so the square's own tap fires
 * exactly once. A press that moves consumes every move — killing the competing tap
 * and any enclosing scroll — and the lift commits [onSquareDrop], falling back to a
 * tap on the press square when the release lands off-board.
 */
internal fun Modifier.boardDragGesture(
    state: BoardDragState,
    onSquareDrop: (Position, Position) -> Unit,
): Modifier = pointerInput(onSquareDrop) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        val start = state.squareAt(down.position) ?: return@awaitEachGesture
        // Null: lifted before the long-press (a tap), or the gesture was cancelled.
        // Either way the tap path owns the press — nothing to do, no state to clear.
        awaitLongPressOrCancellation(down.id) ?: return@awaitEachGesture
        trackBoardDrag(state, down.id, start, onSquareDrop)
    }
}

private suspend fun AwaitPointerEventScope.trackBoardDrag(
    state: BoardDragState,
    pointerId: PointerId,
    start: Position,
    onSquareDrop: (Position, Position) -> Unit,
) {
    state.source = start
    var moved = false
    try {
        while (true) {
            val event = awaitPointerEvent()
            val change = event.changes.firstOrNull { it.id == pointerId } ?: break
            if (!change.pressed) {
                if (moved) {
                    val target = state.squareAt(change.position) ?: start
                    onSquareDrop(start, target)
                }
                break
            }
            if (change.position != change.previousPosition) {
                change.consume()
                moved = true
            }
        }
    } finally {
        state.source = null
    }
}
