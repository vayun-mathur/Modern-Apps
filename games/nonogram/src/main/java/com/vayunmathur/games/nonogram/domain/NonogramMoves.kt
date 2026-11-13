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

    /** Flips the note on [index]: the single-tap behaviour of cross mode. */
    fun toggleNote(game: NonogramGameState, index: Int): NonogramGameState =
        setNote(game, index, crossed = index !in game.crossed)
}
