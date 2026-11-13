package com.vayunmathur.games.chess

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitTouchSlopOrCancellation
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.AwaitPointerEventScope
import androidx.compose.ui.input.pointer.PointerId
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

    /** Square the in-progress drag picked its piece up from; null when idle. Drives hint dots. */
    var source: Position? by mutableStateOf(null)

    /**
     * Where the finger is during a drag, in the board's unrotated pixel space; null when idle.
     * The floating piece is drawn here, and the square under it is the drop target.
     */
    var pointer: Offset? by mutableStateOf(null)

    /** Measured board width in px, set from `onSizeChanged`; mapping is dormant until set. */
    var boardPx: Float by mutableFloatStateOf(0f)

    /**
     * Whether a press on a square may pick its piece up. Set by [BoardGrid] every composition
     * (the side to move's pieces only); anything else leaves the press to the square's tap and
     * to any enclosing scroll, so a swipe over empty squares still scrolls the Learn screen.
     */
    var canPickUp: (Position) -> Boolean = { false }

    /** Fired once when a drag takes the gesture (a haptic). Set by [BoardGrid]. */
    var onPickUp: () -> Unit = {}

    /** The last drop this board committed, so the board can skip its slide-in animation. */
    var lastDrop: Pair<Position, Position>? = null

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

    /** Top-left corner of [position]'s square in the unrotated pixel space [squareAt] reads. */
    fun squareOrigin(position: Position): Offset {
        val cell = boardPx / BOARD_SQUARES
        val last = BOARD_SQUARES - 1
        val row = if (flipped) last - position.row else position.row
        val col = if (flipped) last - position.col else position.col
        return Offset(col * cell, row * cell)
    }
}

/**
 * Press-and-drag to move, alongside the per-square tap handler (which this never replaces).
 *
 * A drag starts as soon as the finger passes the touch slop, so a quick flick moves the piece;
 * there is no long-press wait. A press that lifts before the slop is a tap and is left entirely
 * to the square's own clickable, so tap-tap moves behave exactly as before. Once dragging, every
 * move is consumed (cancelling the competing tap and any enclosing scroll), [BoardDragState.pointer]
 * follows the finger for the floating piece, and the lift commits [onSquareDrop] — falling back
 * to a tap on the press square when the release lands off-board.
 */
internal fun Modifier.boardDragGesture(
    state: BoardDragState,
    onSquareDrop: (Position, Position) -> Unit,
): Modifier = pointerInput(onSquareDrop) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        val start = state.squareAt(down.position)?.takeIf(state.canPickUp) ?: return@awaitEachGesture
        // Null: lifted before the slop (a tap), or the gesture was cancelled. Either way the
        // tap path owns the press — nothing to do, no state to clear.
        val slop = awaitTouchSlopOrCancellation(down.id) { change, _ -> change.consume() }
            ?: return@awaitEachGesture
        state.source = start
        state.pointer = slop.position
        state.onPickUp()
        try {
            val release = followDrag(state, slop.id)
            if (release != null) {
                val target = state.squareAt(release) ?: start
                state.lastDrop = start to target
                onSquareDrop(start, target)
            }
        } finally {
            state.source = null
            state.pointer = null
        }
    }
}

/** Tracks [pointerId] until it lifts; returns the release position, or null if it vanished. */
private suspend fun AwaitPointerEventScope.followDrag(state: BoardDragState, pointerId: PointerId): Offset? {
    var release: Offset? = null
    var tracking = true
    while (tracking) {
        val change = awaitPointerEvent().changes.firstOrNull { it.id == pointerId }
        change?.consume()
        when {
            change == null -> tracking = false
            !change.pressed -> {
                release = change.position
                tracking = false
            }
            else -> state.pointer = change.position
        }
    }
    return release
}
