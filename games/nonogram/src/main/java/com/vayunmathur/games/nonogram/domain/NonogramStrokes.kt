package com.vayunmathur.games.nonogram.domain

import com.vayunmathur.games.nonogram.data.CellMark
import com.vayunmathur.games.nonogram.data.MarkMode
import com.vayunmathur.games.nonogram.data.NonogramGameState
import kotlin.math.abs
import kotlin.math.sign

/** What every cell of one stroke gets. Decided by the first cell that takes input. */
enum class StrokeAction {
    /** Fill attempt: picture cells fill, the first wrong one is revealed, costs a heart, and ends it. */
    FILL,

    /** Puts the player's X on blank cells. */
    CROSS,

    /** Takes the player's X off. */
    CLEAR,
}

/** The line a stroke is locked to once it leaves its first cell. */
enum class StrokeAxis { ROW, COLUMN }

/**
 * One finger-down-to-finger-up gesture over the grid. A tap is a stroke of one cell.
 *
 * @property mode the mark mode this stroke paints with: the toggle's mode, or the other one for a
 *   stroke that began with a long press.
 * @property anchor the cell the finger went down on.
 * @property action set by the first cell that takes input, then fixed for the stroke.
 * @property axis set once the finger reaches a second cell, then fixed for the stroke.
 * @property visited every cell the stroke has passed, applied or skipped; each is handled once.
 * @property head the cell the stroke last walked to along its line.
 * @property stopped set by a wrong fill or the end of the game; the rest of the stroke does nothing.
 */
data class Stroke(
    val mode: MarkMode,
    val anchor: Int,
    val action: StrokeAction? = null,
    val axis: StrokeAxis? = null,
    val visited: Set<Int> = emptySet(),
    val head: Int = anchor,
    val stopped: Boolean = false,
)

/** A board and the stroke that produced it. */
data class StrokeStep(val game: NonogramGameState, val stroke: Stroke)

/**
 * Drag-to-fill, as pure functions over a [NonogramGameState].
 *
 * Rules:
 *  - The first cell that takes input decides the [StrokeAction]: fill in fill mode; in cross mode,
 *    clear when that cell already carries an X and cross otherwise. A stroke that starts on a settled
 *    cell waits for the first unsettled one.
 *  - Once the finger reaches another cell the stroke locks to the first cell's row or column, picking
 *    whichever the finger has moved further along, so a wobbly horizontal drag stays in its row.
 *  - Every cell between the last position and the new one is walked, so a fast swipe that skips
 *    cells between two pointer events still marks them, and each cell is handled at most once.
 *  - Fill strokes only paint blank cells after the first: an X the player placed protects its cell
 *    from a sweep (tapping an X in fill mode still attempts it, as before).
 *  - A wrong fill reveals that cell, costs one heart and stops the stroke, so one stroke can never
 *    cost more than one heart.
 */
object NonogramStrokes {

    /** The action a stroke would take if it started on [index], or null if that cell takes no input. */
    fun actionFor(game: NonogramGameState, index: Int, mode: MarkMode): StrokeAction? = when {
        game.isLocked(index) -> null
        mode == MarkMode.FILL -> StrokeAction.FILL
        index in game.crossed -> StrokeAction.CLEAR
        else -> StrokeAction.CROSS
    }

    /** Starts a stroke on [index] and applies it to that cell. */
    fun begin(game: NonogramGameState, index: Int, mode: MarkMode): StrokeStep =
        visit(game, Stroke(mode = mode, anchor = index), index)

    /** Moves the stroke on to the cell under the finger, [index], marking every cell on the way. */
    fun extend(game: NonogramGameState, stroke: Stroke, index: Int): StrokeStep {
        if (stroke.stopped || index == stroke.anchor && stroke.axis == null) return StrokeStep(game, stroke)
        val n = game.size
        val axis = stroke.axis ?: axisTowards(stroke.anchor, index, n)
        val target = when (axis) {
            StrokeAxis.ROW -> stroke.anchor / n * n + index % n
            StrokeAxis.COLUMN -> index / n * n + stroke.anchor % n
        }
        val step = (if (axis == StrokeAxis.ROW) 1 else n) * (target - stroke.head).sign
        var current = StrokeStep(game, stroke.copy(axis = axis))
        var cursor = stroke.head
        while (cursor != target && !current.stroke.stopped) {
            cursor += step
            current = visit(current.game, current.stroke, cursor)
        }
        return current.copy(stroke = current.stroke.copy(head = cursor))
    }

    private fun axisTowards(anchor: Int, index: Int, n: Int): StrokeAxis {
        val dRow = abs(index / n - anchor / n)
        val dCol = abs(index % n - anchor % n)
        return if (dCol >= dRow) StrokeAxis.ROW else StrokeAxis.COLUMN
    }

    private fun visit(game: NonogramGameState, stroke: Stroke, index: Int): StrokeStep {
        if (stroke.stopped || index in stroke.visited) return StrokeStep(game, stroke)
        val seen = stroke.copy(visited = stroke.visited + index)
        val decided = seen.action
        val action = decided ?: actionFor(game, index, seen.mode) ?: return StrokeStep(game, seen)
        val next = apply(game, action, index, deciding = decided == null)
        val wrongFill = next.hearts < game.hearts
        return StrokeStep(next, seen.copy(action = action, stopped = wrongFill || next.isOver))
    }

    private fun apply(game: NonogramGameState, action: StrokeAction, index: Int, deciding: Boolean) =
        when (action) {
            StrokeAction.FILL ->
                if (deciding || game.markAt(index) == CellMark.BLANK) NonogramMoves.guess(game, index) else game
            StrokeAction.CROSS ->
                if (game.markAt(index) == CellMark.BLANK) NonogramMoves.setNote(game, index, true) else game
            StrokeAction.CLEAR ->
                if (game.markAt(index) == CellMark.CROSSED) NonogramMoves.setNote(game, index, false) else game
        }
}
