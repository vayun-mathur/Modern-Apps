package com.vayunmathur.games.sudoku.domain

import com.vayunmathur.games.sudoku.data.SudokuGameState

/**
 * The pure rules of answer mode: which keypad taps mean what, before anything is stored.
 *
 * Lives in `domain` rather than the ViewModel so the whole decision table is unit-testable without
 * an `Application`: [decide] reads the game and the tapped digit and says what should happen, and
 * the ViewModel carries it out (history, state flow, stats, keypad flash). Nothing here touches
 * Android, coroutines, or Compose.
 *
 * The table, in order:
 *
 * - no usable selection (nothing selected, a given cell, or an ended game) is ignored;
 * - notes mode toggles a pencil mark on an empty cell — possibilities are never judged and cost
 *   nothing — but a filled cell has nothing to pencil over, so the tap is ignored there instead
 *   of deleting the entry to make room for marks;
 * - re-tapping the digit already showing erases the cell, in either mode;
 * - in answer mode a digit that disagrees with the solution is rejected: never placed, one life;
 * - anything else is placed, exactly as classic mode always has.
 */
object AnswerMode {

    /**
     * What a tap on [digit] should do to [game].
     *
     * Guards collapse into one check so the table stays readable as written: an unusable selection
     * has a single cause from the caller's point of view (there is nowhere to write), whatever the
     * underlying reason.
     */
    fun decide(game: SudokuGameState, digit: Int): DigitAttempt {
        val index = game.selected
        val usable = index in 0 until game.size.cellCount &&
            !game.isGiven(index) &&
            !game.isWon &&
            !game.isGameOver
        if (!usable) return DigitAttempt.Ignore
        if (game.notesMode) {
            // Pencilling over an entered digit would have to delete it first (the ViewModel's
            // note write clears the entry), which no player means by tapping in notes mode.
            if (game.entries[index] != 0) return DigitAttempt.Ignore
            return DigitAttempt.Note(index, digit)
        }
        if (game.entries[index] == digit) return DigitAttempt.Erase(index)
        if (game.answerMode && digit != game.solution[index]) return DigitAttempt.Reject(digit)
        return DigitAttempt.Place(index, digit)
    }

    /**
     * Spends the life a rejection costs.
     *
     * The board is otherwise untouched — the digit was never placed, so there is nothing for undo
     * to restore and nothing for a hint to correct. Flooring at zero keeps a double-tap on the last
     * life from driving the count negative.
     */
    fun applyRejection(game: SudokuGameState): SudokuGameState =
        game.copy(livesRemaining = (game.livesRemaining - 1).coerceAtLeast(0))
}

/**
 * One keypad tap, classified.
 *
 * [Ignore] covers every "nowhere to write" case alike, because the UI does the same thing for all
 * of them: nothing. [Reject] carries the digit so the pad knows which key to flash.
 */
sealed interface DigitAttempt {
    /** Nothing selected, a given cell, or an ended game: there is nowhere to write. */
    data object Ignore : DigitAttempt

    /** Pencil-mark toggle; never judged and never costs a life. */
    data class Note(val index: Int, val digit: Int) : DigitAttempt

    /** Re-tapping the digit already showing, which clears the cell. */
    data class Erase(val index: Int) : DigitAttempt

    /** A digit that may be written; correct by construction in answer mode. */
    data class Place(val index: Int, val digit: Int) : DigitAttempt

    /** A wrong digit in answer mode: refused, never placed, one life. */
    data class Reject(val digit: Int) : DigitAttempt
}
