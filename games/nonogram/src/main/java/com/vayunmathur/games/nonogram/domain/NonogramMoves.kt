package com.vayunmathur.games.nonogram.domain

import com.vayunmathur.games.nonogram.data.NonogramGameState

/**
 * What a single move does to a board, as pure state transitions.
 *
 * The ViewModel applies these to the board it holds in memory and only then persists the result, so
 * a tap is judged against the state the player is looking at rather than whatever DataStore last
 * echoed back. Every function returns the board unchanged when the move does not apply (game over, or
 * a cell the game has already settled), which is what lets callers fire them freely.
 */
object NonogramMoves {

    /**
     * A fill attempt on [index].
     *
     * A cell that belongs to the picture is filled; one that does not is revealed as blank and costs a
     * heart. Either way any note on the cell is dropped, since the game has now settled it.
     */
    fun guess(game: NonogramGameState, index: Int): NonogramGameState {
        if (game.isOver || game.isLocked(index)) return game
        return if (game.belongsToPicture(index)) {
            game.copy(filled = game.filled + index, crossed = game.crossed - index)
        } else {
            game.copy(
                revealedBlanks = game.revealedBlanks + index,
                crossed = game.crossed - index,
                hearts = (game.hearts - 1).coerceAtLeast(0),
            )
        }
    }

    /** Places ([crossed] true) or removes the player's own "looks empty" note. Never costs a heart. */
    fun setNote(game: NonogramGameState, index: Int, crossed: Boolean): NonogramGameState {
        if (game.isOver || game.isLocked(index)) return game
        val notes = if (crossed) game.crossed + index else game.crossed - index
        return if (notes == game.crossed) game else game.copy(crossed = notes)
    }

    /**
     * Takes [current] back to the marks it had at [before], without giving anything back.
     *
     * Fills and notes return to how they were, but every blank a mistake revealed stays revealed and
     * every heart spent stays spent — so undo is for a slip of the finger, never a way to un-make a
     * wrong guess and try the cell again. A note that sat on a cell the undone move revealed is not
     * restored, since that cell is settled now.
     */
    fun undo(current: NonogramGameState, before: NonogramGameState): NonogramGameState =
        current.copy(
            filled = before.filled - current.revealedBlanks,
            crossed = before.crossed - current.revealedBlanks,
        )

    /** Flips the note on [index]: the single-tap behaviour of cross mode. */
    fun toggleNote(game: NonogramGameState, index: Int): NonogramGameState =
        setNote(game, index, crossed = index !in game.crossed)
}
