package com.vayunmathur.games.nonogram.data

import kotlinx.serialization.Serializable

/**
 * Which puzzle the player is looking at.
 *
 * There is no level picker: [CASUAL] is a single "current level" counter that only ever moves
 * forward, and [DAILY] is one puzzle per calendar day shared by everyone on the same date.
 */
@Serializable
enum class GameMode { CASUAL, DAILY }

/**
 * What a cell shows.
 *
 * [CROSSED] is the player's own "definitely blank" note. It is bookkeeping only — the win check looks
 * at [FILLED] alone — but it is how a nonogram is actually solved, so it has to persist alongside the
 * fills. [REVEALED] is a blank the game exposed after a wrong guess; it is drawn differently from a
 * note so the player can tell their own working from the cells their mistakes paid for.
 */
enum class CellMark { BLANK, FILLED, CROSSED, REVEALED }

/**
 * Which mark a plain tap places.
 *
 * Long press always does the opposite, whatever the mode is, so the mode is a convenience for placing
 * a run of the same mark rather than the only way to reach either one.
 */
enum class MarkMode { FILL, CROSS }

/** Hearts a level starts with. Three wrong guesses and the board has to be retried. */
const val STARTING_HEARTS = 3

/**
 * A puzzle plus the player's work on it.
 *
 * The three cell sets are disjoint and mean different things:
 *  - [filled] is part of the picture. A fill only ever lands on a cell that belongs, so this is always
 *    a subset of the solution and there is no "wrong cell" state to draw.
 *  - [crossed] is the player's own note that a cell looks empty. Free to place and to take back, and
 *    never checked against the solution — being wrong in a note is not a guess worth punishing.
 *  - [revealedBlanks] is a cell the game has *told* the player is empty, after a wrong guess cost a
 *    heart. It draws as a distinct error-coloured cross and cannot be cleared, which is what stops
 *    the same cell being charged for twice.
 *
 * Storing them as sets rather than per-cell lists keeps the DataStore write small — only the cells
 * actually touched are persisted.
 */
data class NonogramGameState(
    val puzzle: NonogramPuzzle,
    val filled: Set<Int>,
    val crossed: Set<Int>,
    val revealedBlanks: Set<Int>,
    val hearts: Int,
    val mode: GameMode,
    val level: Int,
    val elapsedSeconds: Int = 0,
) {
    val size: Int get() = puzzle.size

    fun markAt(index: Int): CellMark = when {
        index in filled -> CellMark.FILLED
        index in revealedBlanks -> CellMark.REVEALED
        index in crossed -> CellMark.CROSSED
        else -> CellMark.BLANK
    }

    /**
     * Whether [index] is settled and no longer takes input.
     *
     * A correct fill and a revealed blank are both facts the game has confirmed, so re-tapping either
     * does nothing rather than undoing them.
     */
    fun isLocked(index: Int): Boolean = index in filled || index in revealedBlanks

    /**
     * Won when every cell of the picture has been filled in.
     *
     * The subset check is belt-and-braces: [filled] should never hold a cell outside the picture, but
     * a size comparison alone would silently accept a board restored from progress that predates that
     * guarantee.
     */
    val isWon: Boolean
        get() = filled.size == puzzle.filledCount && filled.all { puzzle.solution[it] }

    /** Failed once the hearts run out, unless the last guess also finished the picture. */
    val isFailed: Boolean get() = hearts <= 0 && !isWon

    val isOver: Boolean get() = isWon || isFailed

    /** Whether [index] belongs to the picture, which is what a tap on it is judged against. */
    fun belongsToPicture(index: Int): Boolean = puzzle.solution[index]
}

/**
 * How big level [level] is.
 *
 * The ramp exists so the first few levels teach the mechanic on a grid small enough to hold in your
 * head, and so a new player is not handed a 15x15 as their introduction.
 */
fun sizeForLevel(level: Int): Int = when {
    level <= SMALL_MAX_LEVEL -> SMALL_SIZE
    level <= MEDIUM_MAX_LEVEL -> MEDIUM_SIZE
    else -> LARGE_SIZE
}

private const val SMALL_MAX_LEVEL = 8
private const val MEDIUM_MAX_LEVEL = 20
private const val SMALL_SIZE = 5
private const val MEDIUM_SIZE = 10
private const val LARGE_SIZE = 15

/** Daily puzzles are a fixed size, so the challenge is comparable from one day to the next. */
const val DAILY_SIZE = 10
