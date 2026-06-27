package com.vayunmathur.games.chess.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChessRulesTest {

    // file 'a'..'h', rank 1..8  ->  internal Position(row, col) where row 0 = rank 8, col 0 = file a
    private fun sq(file: Char, rank: Int) = Position(8 - rank, file - 'a')

    private fun board(vararg placements: Pair<Position, Piece>): Board {
        val grid = MutableList(8) { MutableList<Piece?>(8) { null } }
        for ((pos, piece) in placements) grid[pos.row][pos.col] = piece
        return Board(grid.map { it.toList() })
    }

    private fun king(color: PieceColor) = Piece(PieceType.KING, color)
    private fun queen(color: PieceColor) = Piece(PieceType.QUEEN, color)
    private fun rook(color: PieceColor) = Piece(PieceType.ROOK, color)
    private fun bishop(color: PieceColor) = Piece(PieceType.BISHOP, color)
    private fun knight(color: PieceColor) = Piece(PieceType.KNIGHT, color)

    @Test
    fun initialPosition_hasLegalMoves_andIsNotTerminal() {
        val b = Board.initialState
        assertTrue(b.hasLegalMoves(PieceColor.WHITE))
        assertFalse(b.isCheckmate(PieceColor.WHITE))
        assertFalse(b.isStalemate(PieceColor.WHITE))
        assertFalse(b.isInsufficientMaterial())
    }

    @Test
    fun initialPosition_fenIsStandardStartpos() {
        assertEquals(
            "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1",
            Board.initialState.toFen()
        )
    }

    @Test
    fun stalemate_isDetected_andIsNotCheckmate() {
        // Black king a8 boxed in by White queen b6; Black to move, not in check, no legal move.
        val b = board(
            sq('a', 8) to king(PieceColor.BLACK),
            sq('b', 6) to queen(PieceColor.WHITE),
            sq('h', 1) to king(PieceColor.WHITE),
        )
        assertFalse(b.hasLegalMoves(PieceColor.BLACK))
        assertTrue(b.isStalemate(PieceColor.BLACK))
        assertFalse(b.isCheckmate(PieceColor.BLACK))
    }

    @Test
    fun checkmate_isDetected_andIsNotStalemate() {
        // Black king a8 mated by White queen b7 supported by White king c6.
        val b = board(
            sq('a', 8) to king(PieceColor.BLACK),
            sq('b', 7) to queen(PieceColor.WHITE),
            sq('c', 6) to king(PieceColor.WHITE),
        )
        assertTrue(b.isCheckmate(PieceColor.BLACK))
        assertFalse(b.isStalemate(PieceColor.BLACK))
        assertFalse(b.hasLegalMoves(PieceColor.BLACK))
    }

    @Test
    fun insufficientMaterial_kingVsKing() {
        val b = board(
            sq('e', 1) to king(PieceColor.WHITE),
            sq('e', 8) to king(PieceColor.BLACK),
        )
        assertTrue(b.isInsufficientMaterial())
    }

    @Test
    fun insufficientMaterial_kingAndMinorVsKing() {
        val withBishop = board(
            sq('e', 1) to king(PieceColor.WHITE),
            sq('c', 1) to bishop(PieceColor.WHITE),
            sq('e', 8) to king(PieceColor.BLACK),
        )
        val withKnight = board(
            sq('e', 1) to king(PieceColor.WHITE),
            sq('b', 1) to knight(PieceColor.WHITE),
            sq('e', 8) to king(PieceColor.BLACK),
        )
        assertTrue(withBishop.isInsufficientMaterial())
        assertTrue(withKnight.isInsufficientMaterial())
    }

    @Test
    fun insufficientMaterial_oppositeBishops_sameColorIsDraw_differentColorIsNot() {
        // c1 is a dark square; f8 is also dark -> same colour bishops -> draw.
        val sameColor = board(
            sq('e', 1) to king(PieceColor.WHITE),
            sq('c', 1) to bishop(PieceColor.WHITE),
            sq('e', 8) to king(PieceColor.BLACK),
            sq('f', 8) to bishop(PieceColor.BLACK),
        )
        // c1 dark vs c8 light -> different colours -> mate still possible -> not auto-draw.
        val diffColor = board(
            sq('e', 1) to king(PieceColor.WHITE),
            sq('c', 1) to bishop(PieceColor.WHITE),
            sq('e', 8) to king(PieceColor.BLACK),
            sq('c', 8) to bishop(PieceColor.BLACK),
        )
        assertTrue(sameColor.isInsufficientMaterial())
        assertFalse(diffColor.isInsufficientMaterial())
    }

    @Test
    fun sufficientMaterial_rookOrTwoKnights_isNotDraw() {
        val withRook = board(
            sq('e', 1) to king(PieceColor.WHITE),
            sq('a', 1) to rook(PieceColor.WHITE),
            sq('e', 8) to king(PieceColor.BLACK),
        )
        val twoKnights = board(
            sq('e', 1) to king(PieceColor.WHITE),
            sq('b', 1) to knight(PieceColor.WHITE),
            sq('g', 1) to knight(PieceColor.WHITE),
            sq('e', 8) to king(PieceColor.BLACK),
        )
        assertFalse(withRook.isInsufficientMaterial())
        assertFalse(twoKnights.isInsufficientMaterial())
    }
}
