package com.vayunmathur.games.chess.util

import com.vayunmathur.games.chess.data.Board
import com.vayunmathur.games.chess.data.LearnLevel
import com.vayunmathur.games.chess.data.Piece
import com.vayunmathur.games.chess.data.PieceColor
import com.vayunmathur.games.chess.data.PieceType
import com.vayunmathur.games.chess.data.Position
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LearnRulesTest {
    private fun sq(file: Char, rank: Int) = Position(8 - rank, file - 'a')
    private fun board(vararg placements: Pair<Position, Piece>): Board {
        val grid = MutableList(8) { MutableList<Piece?>(8) { null } }
        for ((pos, piece) in placements) grid[pos.row][pos.col] = piece
        return Board(grid.map { it.toList() })
    }
    private fun level(goalType: String, nbMoves: Int = 1, detectCapture: String? = null, n: Int? = null) =
        LearnLevel(goal = "goal", fen = "8/8/8/8/8/8/8/8 w - - 0 1", goalType = goalType, nbMoves = nbMoves, detectCapture = detectCapture, n = n)

    @Test fun `stars and bonus follow lichess par bands`() {
        val level = level("apples", nbMoves = 4)
        assertEquals(3, starsFor(level, 4))
        assertEquals(2, starsFor(level, 5)) // par + max(1, 4/4)=5
        assertEquals(1, starsFor(level, 6))
        assertEquals(500, levelBonus(3)); assertEquals(300, levelBonus(2)); assertEquals(100, levelBonus(1)); assertEquals(100, levelBonus(0))
    }

    @Test fun `piece values and promotion helper`() {
        assertEquals(90, pieceValue(PieceType.QUEEN)); assertEquals(50, pieceValue(PieceType.ROOK))
        assertEquals(30, pieceValue(PieceType.BISHOP)); assertEquals(30, pieceValue(PieceType.KNIGHT))
        assertEquals(10, pieceValue(PieceType.PAWN)); assertEquals(0, pieceValue(PieceType.KING))
        val pawn = Piece(PieceType.PAWN, PieceColor.WHITE)
        assertEquals(PieceType.QUEEN, promotionFor(pawn, sq('a', 8)))
        assertNull(promotionFor(pawn, sq('a', 7)))
        assertNull(promotionFor(Piece(PieceType.ROOK, PieceColor.WHITE), sq('a', 8)))
        assertTrue(isLastRank(sq('a', 8))); assertTrue(isLastRank(sq('a', 1))); assertFalse(isLastRank(sq('a', 5)))
    }

    @Test fun `success goals evaluate board state`() {
        val kings = board(sq('e', 1) to Piece(PieceType.KING, PieceColor.WHITE), sq('e', 8) to Piece(PieceType.KING, PieceColor.BLACK))
        assertTrue(isSuccess(level("info"), kings, emptySet(), PieceColor.WHITE))
        assertTrue(isSuccess(level("apples"), kings, emptySet(), PieceColor.WHITE))
        assertFalse(isSuccess(level("apples"), kings, setOf(sq('a', 1)), PieceColor.WHITE))
        assertFalse(isSuccess(level("captureAll"), kings, emptySet(), PieceColor.WHITE)) // black king still on board
        assertEquals(PieceColor.BLACK, fenSide("8/8/8/8/8/8/8/8 b - - 0 1"))
        assertEquals(PieceColor.WHITE, fenSide("8/8/8/8/8/8/8/8 w - - 0 1"))
        assertEquals(PieceColor.WHITE, fenSide("garbage"))
    }

    @Test fun `captures list only legal captures`() {
        val b = board(
            sq('e', 1) to Piece(PieceType.KING, PieceColor.WHITE),
            sq('e', 8) to Piece(PieceType.KING, PieceColor.BLACK),
            sq('d', 1) to Piece(PieceType.QUEEN, PieceColor.WHITE),
            sq('d', 8) to Piece(PieceType.QUEEN, PieceColor.BLACK),
        )
        val caps = captures(b, PieceColor.WHITE)
        assertTrue(caps.contains(sq('d', 1) to sq('d', 8)))
        assertEquals(2, countColor(b, PieceColor.WHITE))
        assertEquals(2, positionsOf(b, PieceColor.WHITE).size)
        assertEquals(2, positionsOf(b, PieceColor.BLACK).size)
    }

    @Test fun `failing move checks single move goals`() {
        val kings = board(sq('e', 1) to Piece(PieceType.KING, PieceColor.WHITE), sq('e', 8) to Piece(PieceType.KING, PieceColor.BLACK))
        assertTrue(isFailingMove(level("check"), kings, PieceColor.WHITE, 1))
        assertTrue(isFailingMove(level("mate"), kings, PieceColor.WHITE, 1))
        assertFalse(isFailingMove(level("escapeCheck"), kings, PieceColor.WHITE, 1))
        assertFalse(isFailingMove(level("apples"), kings, PieceColor.WHITE, 1))
        assertNull(detectCaptureMove(level("info"), kings, PieceColor.WHITE))
        assertNull(detectCaptureMove(level("apples"), kings, PieceColor.WHITE)) // detectCapture null
    }
}
