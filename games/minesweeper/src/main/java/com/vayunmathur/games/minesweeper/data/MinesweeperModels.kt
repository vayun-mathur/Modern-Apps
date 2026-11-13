package com.vayunmathur.games.minesweeper.data

import kotlinx.serialization.Serializable
import kotlin.math.roundToInt

/**
 * Field dimensions, chosen to stay playable in portrait on a phone.
 *
 * Columns are the binding constraint: a cell has to stay wide enough to hit with a thumb, so the
 * larger sizes grow mostly downward and scroll. The classic 30-column expert field is deliberately
 * absent — it is unusable at this width.
 */
@Serializable
enum class BoardSize(val cols: Int, val rows: Int) {
    SMALL(cols = 8, rows = 10),
    MEDIUM(cols = 10, rows = 14),
    LARGE(cols = 12, rows = 18);

    val cellCount: Int get() = cols * rows
}

/**
 * Mine density, as a fraction of the field.
 *
 * A fraction rather than a fixed count so a difficulty feels the same on every size. The classic
 * presets sit around 0.12 (beginner) to 0.21 (expert), which is roughly the range spanned here.
 */
@Serializable
enum class Difficulty(val mineFraction: Double) {
    EASY(mineFraction = 0.10),
    MEDIUM(mineFraction = 0.15),
    HARD(mineFraction = 0.19),
    EXPERT(mineFraction = 0.23);

    /**
     * Mines on a [size] field.
     *
     * Capped so the opening tap always has somewhere to go: the first reveal clears the tapped cell
     * and its eight neighbours, so the field needs at least that many safe squares.
     */
    fun mineCount(size: BoardSize): Int =
        (size.cellCount * mineFraction).roundToInt().coerceIn(1, size.cellCount - MIN_SAFE_CELLS)

    private companion object {
        /** A cell plus its eight neighbours, which the first tap always opens. */
        const val MIN_SAFE_CELLS = 9
    }
}

/** The options chosen on the home screen, carried into field generation. */
@Serializable
data class GameConfig(
    val size: BoardSize = BoardSize.MEDIUM,
    val difficulty: Difficulty = Difficulty.MEDIUM,
) {
    /** Stats key component, so records are tracked per field shape and density. */
    val variant: String get() = "${size.name}_${difficulty.name}"
}

/** How a game ended, or that it is still going. */
enum class GameOutcome { PLAYING, WON, LOST }

/**
 * Which action a plain tap performs.
 *
 * Long press always does the opposite, whatever the mode is, so the mode is a convenience for placing a
 * run of flags rather than the only way to reach either action.
 */
enum class TapMode { DIG, FLAG }

/**
 * Everything about a field in progress.
 *
 * Geometry is held as plain [cols] and [rows] rather than a [BoardSize], so the rules work on any
 * rectangle and can be tested against layouts far smaller than any the picker offers. The chosen
 * [BoardSize] and [Difficulty] belong to the session, not the field, and live on the UI state.
 *
 * [mines] is all false until the first tap: mines are laid *after* the opening reveal so it can never
 * be a loss. [neighbourCounts] is derived from [mines] once at generation and cached, because it is
 * read for every cell on every recomposition.
 *
 * All four per-cell lists are `cols * rows` long and indexed row-major.
 */
data class MinesweeperGameState(
    val cols: Int,
    val rows: Int,
    val mineCount: Int,
    val mines: List<Boolean>,
    val neighbourCounts: List<Int>,
    val revealed: List<Boolean>,
    val flagged: List<Boolean>,
    val outcome: GameOutcome = GameOutcome.PLAYING,
    /** The mine that ended it, so the board can single it out from the ones merely shown. */
    val explodedAt: Int = -1,
    // The clock is deliberately not here: it ticks every second, and every tick would hand the
    // field a new state and redraw all 80-216 cells. MinesweeperViewModel.elapsedSeconds owns it.
    /** False until the first reveal, which is when the mines are laid and the clock starts. */
    val started: Boolean = false,
) {
    val cellCount: Int get() = cols * rows
    val flagsPlaced: Int get() = flagged.count { it }

    /** Counts down as flags go in. Goes negative if the player over-flags, which is a useful signal. */
    val minesRemaining: Int get() = mineCount - flagsPlaced

    val isOver: Boolean get() = outcome != GameOutcome.PLAYING

    /** Won once every cell that is not a mine has been revealed; flags are not required. */
    val isCleared: Boolean get() = (0 until cellCount).all { mines[it] || revealed[it] }

    /** Indices of the up-to-eight cells touching [index], including diagonals. */
    fun neighbours(index: Int): List<Int> = neighbourIndices(cols, rows, index)

    companion object {
        /**
         * A field with no mines laid yet.
         *
         * The player sees a blank grid and the first tap decides where the mines go, so the opening
         * move is always safe and always opens an area.
         */
        fun empty(config: GameConfig): MinesweeperGameState = of(
            cols = config.size.cols,
            rows = config.size.rows,
            mineCount = config.difficulty.mineCount(config.size),
        )

        /** A blank field of arbitrary dimensions. */
        fun of(cols: Int, rows: Int, mineCount: Int): MinesweeperGameState {
            val cells = cols * rows
            return MinesweeperGameState(
                cols = cols,
                rows = rows,
                mineCount = mineCount,
                mines = List(cells) { false },
                neighbourCounts = List(cells) { 0 },
                revealed = List(cells) { false },
                flagged = List(cells) { false },
            )
        }
    }
}

/** Indices of the up-to-eight cells touching [index] on a [cols] x [rows] field, diagonals included. */
fun neighbourIndices(cols: Int, rows: Int, index: Int): List<Int> {
    val row = index / cols
    val col = index % cols
    val out = ArrayList<Int>(8)
    for (dr in -1..1) {
        for (dc in -1..1) {
            if (dr == 0 && dc == 0) continue
            val r = row + dr
            val c = col + dc
            if (r in 0 until rows && c in 0 until cols) out.add(r * cols + c)
        }
    }
    return out
}
