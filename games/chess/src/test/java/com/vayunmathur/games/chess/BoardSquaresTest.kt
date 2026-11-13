package com.vayunmathur.games.chess

import com.vayunmathur.games.chess.data.Board
import com.vayunmathur.games.chess.data.PieceColor
import com.vayunmathur.games.chess.data.Position
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Pure per-position board helpers used by BoardGrid. */
class BoardSquaresTest {
    private fun sq(file: Char, rank: Int) = Position(8 - rank, file - 'a')

    private fun play(vararg moves: Pair<Position, Position>): Board =
        moves.fold(Board.initialState) { b, (from, to) -> b.movePiece(from, to) }

    @Test fun `no check square in the starting position`() {
        assertNull(checkedKingSquare(Board.initialState, PieceColor.WHITE))
        assertNull(checkedKingSquare(Board.initialState, PieceColor.BLACK))
    }

    @Test fun `checked king square is the king of the side in check`() {
        // Fool's mate: 1. f3 e5 2. g4 Qh4# leaves White's king on e1 in check.
        val board = play(
            sq('f', 2) to sq('f', 3), sq('e', 7) to sq('e', 5),
            sq('g', 2) to sq('g', 4), sq('d', 8) to sq('h', 4),
        )
        assertEquals(sq('e', 1), checkedKingSquare(board, PieceColor.WHITE))
        assertNull(checkedKingSquare(board, PieceColor.BLACK), "Black is not in check")
    }

    @Test fun `legal destinations of the e2 pawn are e3 and e4`() {
        assertEquals(setOf(sq('e', 3), sq('e', 4)), legalDestinations(Board.initialState, sq('e', 2)))
        assertTrue(legalDestinations(Board.initialState, null).isEmpty())
    }

    @Test fun `last move is shown only when enabled and never the puzzle's zero-length seed`() {
        val afterE4 = play(sq('e', 2) to sq('e', 4))
        assertEquals(afterE4.lastMove, visibleLastMove(afterE4, showLastMove = true))
        assertNull(visibleLastMove(afterE4, showLastMove = false))
        val seeded = afterE4.copy(lastMove = afterE4.lastMove!!.copy(end = afterE4.lastMove!!.start))
        assertNull(visibleLastMove(seeded, showLastMove = true))
    }

    @Test fun `new moves slide in except the opening position's and a drag drop`() {
        val e4 = play(sq('e', 2) to sq('e', 4)).lastMove!!
        val e5 = play(sq('e', 2) to sq('e', 4), sq('e', 7) to sq('e', 5)).lastMove!!
        assertTrue(shouldSlideIn(e5, initialMove = e4, lastDrop = null), "the AI's reply slides in")
        assertTrue(shouldSlideIn(e5, initialMove = null, lastDrop = sq('e', 2) to sq('e', 4)))
        assertFalse(shouldSlideIn(e4, initialMove = e4, lastDrop = null), "already on screen at open")
        assertFalse(shouldSlideIn(e4, initialMove = null, lastDrop = sq('e', 2) to sq('e', 4)), "just dropped")
        assertFalse(shouldSlideIn(null, initialMove = null, lastDrop = null))
    }
}
