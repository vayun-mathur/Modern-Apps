package com.vayunmathur.games.sudoku.platform

import com.vayunmathur.games.sudoku.data.SudokuGameState

/**
 * The UI contract between [SudokuViewModel] and the board.
 *
 * The board takes this interface rather than the ViewModel itself so it can be rendered by a
 * `@Preview` — which is what the store listing images are generated from. It lives in `platform`
 * rather than next to the board so the dependency runs one way: `ui` depends on `platform`, and the
 * ViewModel implements this.
 *
 * Every member has a no-op default, so [Noop] is the whole implementation a preview needs.
 */
interface SudokuActions {
    fun selectCell(index: Int) {}
    fun enterDigit(digit: Int) {}
    fun clearCell() {}
    fun toggleNotesMode() {}
    fun hint() {}
    fun undo() {}
    /** Same puzzle again, work cleared: the out-of-lives "Try again". */
    fun restart() {}

    /** A fresh puzzle with the finished game's size, difficulty and answer mode. */
    fun playAgain() {}
    fun giveUp() {}

    companion object { val Noop: SudokuActions = object : SudokuActions {} }
}

/**
 * What the board needs to draw itself.
 *
 * [game] is null only before the first puzzle has been generated; the board shows a spinner then.
 * Generation happens on a background dispatcher because digging a 9x9 runs the solver once per
 * removed clue.
 *
 * [rejectedDigit] is the last keypad digit answer mode refused to place, or null if none has been
 * refused. It is write-once per attempt — every rejection carries a fresh sequence number, so two
 * wrong taps on the same key still animate twice — and it is never cleared: the keypad's flash runs
 * its course locally, and clearing here would cut a flash short if a correct digit landed meanwhile.
 */
data class SudokuUiState(
    val game: SudokuGameState? = null,
    val canUndo: Boolean = false,
    val generating: Boolean = false,
    val rejectedDigit: RejectedDigit? = null,
)

/**
 * A keypad digit answer mode rejected, with a sequence number telling repeats apart.
 *
 * The pad needs more than the digit: tapping a wrong 7 twice in a row must flash twice, and a
 * state that still says "7" would not recompose. Each rejection bumps [attempt], so every one is a
 * new value even for the same [digit].
 */
data class RejectedDigit(val digit: Int, val attempt: Long)
