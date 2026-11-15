package com.vayunmathur.games.chess.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Castling, en passant, pins, notation ambiguity, promotion expansion and FEN edges. */
class ChessLogicCoverageTest {
    private fun sq(file: Char, rank: Int) = Position(8 - rank, file - 'a')
    private fun board(vararg placements: Pair<Position, Piece>): Board {
        val grid = MutableList(8) { MutableList<Piece?>(8) { null } }
        for ((pos, piece) in placements) grid[pos.row][pos.col] = piece
        return Board(grid.map { it.toList() })
    }
    private fun piece(type: PieceType, color: PieceColor, moved: Boolean = false) =
        Piece(type, color, hasMoved = moved)

    private fun castleBoard() = board(
        sq('e', 1) to piece(PieceType.KING, PieceColor.WHITE),
        sq('a', 1) to piece(PieceType.ROOK, PieceColor.WHITE),
        sq('h', 1) to piece(PieceType.ROOK, PieceColor.WHITE),
        sq('e', 8) to piece(PieceType.KING, PieceColor.BLACK),
        sq('a', 8) to piece(PieceType.ROOK, PieceColor.BLACK),
        sq('h', 8) to piece(PieceType.ROOK, PieceColor.BLACK),
    )

    @Test fun `castling both sides legal and movePiece relocates the rook`() {
        val b = castleBoard()
        assertTrue(b.isValidMove(sq('e', 1), sq('g', 1)))
        assertTrue(b.isValidMove(sq('e', 1), sq('c', 1)))
        val kingside = b.movePiece(sq('e', 1), sq('g', 1))
        assertEquals(PieceType.KING, kingside.pieces[sq('g', 1).row][sq('g', 1).col]?.type)
        assertEquals(PieceType.ROOK, kingside.pieces[sq('f', 1).row][sq('f', 1).col]?.type)
        assertNull(kingside.pieces[sq('h', 1).row][sq('h', 1).col])
        assertEquals("O-O", kingside.lastMove.toString())
        val queenside = b.movePiece(sq('e', 1), sq('c', 1))
        assertEquals(PieceType.ROOK, queenside.pieces[sq('d', 1).row][sq('d', 1).col]?.type)
        assertEquals("O-O-O", queenside.lastMove.toString())
    }

    @Test fun `castling blocked by pieces between king and rook`() {
        val b = board(
            sq('e', 1) to piece(PieceType.KING, PieceColor.WHITE),
            sq('f', 1) to piece(PieceType.KNIGHT, PieceColor.WHITE),
            sq('b', 1) to piece(PieceType.KNIGHT, PieceColor.WHITE),
            sq('a', 1) to piece(PieceType.ROOK, PieceColor.WHITE),
            sq('h', 1) to piece(PieceType.ROOK, PieceColor.WHITE),
            sq('e', 8) to piece(PieceType.KING, PieceColor.BLACK),
        )
        assertFalse(b.isValidMove(sq('e', 1), sq('g', 1)), "knight on f1 blocks kingside")
        assertFalse(b.isValidMove(sq('e', 1), sq('c', 1)), "knight on b1 blocks queenside")
    }

    @Test fun `castling through check and out of check is illegal but queenside may stay legal`() {
        val throughCheck = board(
            sq('e', 1) to piece(PieceType.KING, PieceColor.WHITE),
            sq('a', 1) to piece(PieceType.ROOK, PieceColor.WHITE),
            sq('h', 1) to piece(PieceType.ROOK, PieceColor.WHITE),
            sq('e', 8) to piece(PieceType.KING, PieceColor.BLACK),
            sq('f', 8) to piece(PieceType.ROOK, PieceColor.BLACK),
        )
        assertFalse(throughCheck.isKingInCheck(PieceColor.WHITE))
        assertFalse(throughCheck.isValidMove(sq('e', 1), sq('g', 1)), "f1 is attacked by the f8 rook")
        assertTrue(throughCheck.isValidMove(sq('e', 1), sq('c', 1)))
        val inCheck = board(
            sq('e', 1) to piece(PieceType.KING, PieceColor.WHITE),
            sq('a', 1) to piece(PieceType.ROOK, PieceColor.WHITE),
            sq('h', 1) to piece(PieceType.ROOK, PieceColor.WHITE),
            sq('e', 8) to piece(PieceType.KING, PieceColor.BLACK),
            sq('e', 7) to piece(PieceType.ROOK, PieceColor.BLACK),
        )
        assertTrue(inCheck.isKingInCheck(PieceColor.WHITE))
        assertFalse(inCheck.isValidMove(sq('e', 1), sq('g', 1)))
        assertFalse(inCheck.isValidMove(sq('e', 1), sq('c', 1)))
    }

    @Test fun `castling requires unmoved king and rook`() {
        val kingMoved = board(
            sq('e', 1) to piece(PieceType.KING, PieceColor.WHITE, moved = true),
            sq('h', 1) to piece(PieceType.ROOK, PieceColor.WHITE),
            sq('e', 8) to piece(PieceType.KING, PieceColor.BLACK),
        )
        assertFalse(kingMoved.isValidMove(sq('e', 1), sq('g', 1)))
        assertTrue(kingMoved.isValidMove(sq('e', 1), sq('f', 1)), "ordinary king step still legal")
        val rookMoved = board(
            sq('e', 1) to piece(PieceType.KING, PieceColor.WHITE),
            sq('h', 1) to piece(PieceType.ROOK, PieceColor.WHITE, moved = true),
            sq('e', 8) to piece(PieceType.KING, PieceColor.BLACK),
        )
        assertFalse(rookMoved.isValidMove(sq('e', 1), sq('g', 1)))
    }

    @Test fun `en passant capture for white removes the double-stepped pawn`() {
        var b = Board.initialState
        b = b.movePiece(sq('e', 2), sq('e', 4))
        b = b.movePiece(sq('a', 7), sq('a', 6))
        b = b.movePiece(sq('e', 4), sq('e', 5))
        b = b.movePiece(sq('d', 7), sq('d', 5))
        assertTrue(b.isValidMove(sq('e', 5), sq('d', 6)))
        val after = b.movePiece(sq('e', 5), sq('d', 6))
        assertNull(after.pieces[sq('d', 5).row][sq('d', 5).col], "captured pawn leaves d5")
        assertEquals(PieceType.PAWN, after.pieces[sq('d', 6).row][sq('d', 6).col]?.type)
        assertEquals(PieceColor.WHITE, after.pieces[sq('d', 6).row][sq('d', 6).col]?.color)
        assertTrue(after.capturedByWhite.any { it.type == PieceType.PAWN && it.color == PieceColor.BLACK })
        assertEquals("exd6", after.lastMove.toString())
    }

    @Test fun `en passant expires and black can capture en passant`() {
        var b = Board.initialState
        b = b.movePiece(sq('e', 2), sq('e', 4))
        b = b.movePiece(sq('a', 7), sq('a', 6))
        b = b.movePiece(sq('e', 4), sq('e', 5))
        b = b.movePiece(sq('d', 7), sq('d', 5))
        b = b.movePiece(sq('g', 1), sq('f', 3)) // white spends a move: the chance is gone
        b = b.movePiece(sq('a', 6), sq('a', 5))
        assertFalse(b.isValidMove(sq('e', 5), sq('d', 6)))

        var black = Board.initialState
        black = black.movePiece(sq('a', 2), sq('a', 3))
        black = black.movePiece(sq('e', 7), sq('e', 5))
        black = black.movePiece(sq('a', 3), sq('a', 4))
        black = black.movePiece(sq('e', 5), sq('e', 4))
        black = black.movePiece(sq('d', 2), sq('d', 4))
        assertTrue(black.isValidMove(sq('e', 4), sq('d', 3)))
        val after = black.movePiece(sq('e', 4), sq('d', 3))
        assertNull(after.pieces[sq('d', 4).row][sq('d', 4).col])
        assertTrue(after.capturedByBlack.any { it.type == PieceType.PAWN })
    }

    @Test fun `pawn double step needs a clear path and captures need a victim`() {
        val blocked = board(
            sq('e', 1) to piece(PieceType.KING, PieceColor.WHITE),
            sq('e', 8) to piece(PieceType.KING, PieceColor.BLACK),
            sq('e', 2) to piece(PieceType.PAWN, PieceColor.WHITE),
            sq('e', 3) to piece(PieceType.PAWN, PieceColor.BLACK),
        )
        assertFalse(blocked.isValidMove(sq('e', 2), sq('e', 3)), "cannot step onto a piece")
        assertFalse(blocked.isValidMove(sq('e', 2), sq('e', 4)), "cannot jump over a piece")
        assertFalse(blocked.isValidMove(sq('e', 2), sq('d', 3)), "no diagonal without a capture")
        val open = board(
            sq('e', 1) to piece(PieceType.KING, PieceColor.WHITE),
            sq('e', 8) to piece(PieceType.KING, PieceColor.BLACK),
            sq('e', 2) to piece(PieceType.PAWN, PieceColor.WHITE),
            sq('d', 3) to piece(PieceType.PAWN, PieceColor.BLACK),
        )
        assertTrue(open.isValidMove(sq('e', 2), sq('e', 4)))
        assertTrue(open.isValidMove(sq('e', 2), sq('d', 3)))
    }

    @Test fun `a pinned piece is pseudo-legal but not legal`() {
        val b = board(
            sq('e', 1) to piece(PieceType.KING, PieceColor.WHITE),
            sq('e', 2) to piece(PieceType.ROOK, PieceColor.WHITE),
            sq('e', 8) to piece(PieceType.ROOK, PieceColor.BLACK),
            sq('a', 8) to piece(PieceType.KING, PieceColor.BLACK),
        )
        assertFalse(b.isKingInCheck(PieceColor.WHITE), "the rook currently shields the king")
        assertTrue(b.isPseudoLegalMove(sq('e', 2), sq('d', 2)))
        assertFalse(b.isValidMove(sq('e', 2), sq('d', 2)), "sidestep exposes the king")
        assertTrue(b.isValidMove(sq('e', 2), sq('e', 8)), "capturing the attacker resolves it")
    }

    @Test fun `legalMoves expands promotions four ways including captures`() {
        val b = board(
            sq('e', 1) to piece(PieceType.KING, PieceColor.WHITE),
            sq('e', 8) to piece(PieceType.KING, PieceColor.BLACK),
            sq('a', 7) to piece(PieceType.PAWN, PieceColor.WHITE),
            sq('b', 8) to piece(PieceType.ROOK, PieceColor.BLACK),
        )
        val promos = b.legalMoves(PieceColor.WHITE)
            .filter { it.start == sq('a', 7) }
        assertEquals(8, promos.size, "push and capture, each with four promotion pieces")
        assertEquals(Board.PROMOTION_CHOICES.toSet(), promos.mapNotNull { it.promotedTo }.toSet())
        assertTrue(promos.filter { it.end == sq('b', 8) }.all { it.capturedPiece?.type == PieceType.ROOK })
        assertEquals(
            listOf(PieceType.QUEEN, PieceType.ROOK, PieceType.BISHOP, PieceType.KNIGHT),
            Board.PROMOTION_CHOICES,
        )
    }

    @Test fun `promotePawn rewrites the last move and clears the pending square`() {
        val b = board(
            sq('e', 1) to piece(PieceType.KING, PieceColor.WHITE),
            sq('e', 8) to piece(PieceType.KING, PieceColor.BLACK),
            sq('a', 7) to piece(PieceType.PAWN, PieceColor.WHITE),
        )
        val stepped = b.movePiece(sq('a', 7), sq('a', 8))
        assertEquals(sq('a', 8), stepped.promotionPosition)
        val promoted = stepped.promotePawn(sq('a', 8), PieceType.KNIGHT)
        assertEquals(PieceType.KNIGHT, promoted.pieces[sq('a', 8).row][sq('a', 8).col]?.type)
        assertNull(promoted.promotionPosition)
        assertEquals(PieceType.KNIGHT, promoted.lastMove?.promotedTo)
        assertEquals("a8=N", promoted.lastMove.toString())
    }

    @Test fun `notation disambiguates by file rank or both`() {
        val byFile = board(
            sq('b', 1) to piece(PieceType.KNIGHT, PieceColor.WHITE),
            sq('f', 3) to piece(PieceType.KNIGHT, PieceColor.WHITE),
        )
        assertEquals("Nbd2", byFile.movePiece(sq('b', 1), sq('d', 2)).lastMove.toString())
        val byRank = board(
            sq('b', 1) to piece(PieceType.KNIGHT, PieceColor.WHITE),
            sq('b', 3) to piece(PieceType.KNIGHT, PieceColor.WHITE),
        )
        assertEquals("N1d2", byRank.movePiece(sq('b', 1), sq('d', 2)).lastMove.toString())
        val byBoth = board(
            sq('b', 1) to piece(PieceType.KNIGHT, PieceColor.WHITE),
            sq('b', 3) to piece(PieceType.KNIGHT, PieceColor.WHITE),
            sq('f', 1) to piece(PieceType.KNIGHT, PieceColor.WHITE),
        )
        assertEquals("Nb1d2", byBoth.movePiece(sq('b', 1), sq('d', 2)).lastMove.toString())
        val plain = Move(sq('e', 2), sq('e', 4), Piece(PieceType.PAWN, PieceColor.WHITE))
        assertEquals("e4", plain.toString())
    }

    @Test fun `notation marks check and checkmate`() {
        val check = board(
            sq('e', 1) to piece(PieceType.KING, PieceColor.WHITE),
            sq('d', 1) to piece(PieceType.QUEEN, PieceColor.WHITE),
            sq('e', 8) to piece(PieceType.KING, PieceColor.BLACK),
        )
        assertEquals("Qd7+", check.movePiece(sq('d', 1), sq('d', 7)).lastMove.toString())
        var game = Board.initialState
        game = game.movePiece(sq('f', 2), sq('f', 3))
        game = game.movePiece(sq('e', 7), sq('e', 5))
        game = game.movePiece(sq('g', 2), sq('g', 4))
        game = game.movePiece(sq('d', 8), sq('h', 4))
        assertEquals("Qh4#", game.lastMove.toString())
        assertTrue(game.isCheckmate(PieceColor.WHITE))
    }

    @Test fun `insufficient material bishops must share a square colour`() {
        val twoWhiteBishops = board(
            sq('e', 1) to piece(PieceType.KING, PieceColor.WHITE),
            sq('c', 1) to piece(PieceType.BISHOP, PieceColor.WHITE),
            sq('e', 3) to piece(PieceType.BISHOP, PieceColor.WHITE),
            sq('e', 8) to piece(PieceType.KING, PieceColor.BLACK),
        )
        assertTrue(twoWhiteBishops.isInsufficientMaterial(), "same-colour bishops can never mate")
        val bishopAndKnight = board(
            sq('e', 1) to piece(PieceType.KING, PieceColor.WHITE),
            sq('c', 1) to piece(PieceType.BISHOP, PieceColor.WHITE),
            sq('e', 8) to piece(PieceType.KING, PieceColor.BLACK),
            sq('g', 8) to piece(PieceType.KNIGHT, PieceColor.BLACK),
        )
        assertFalse(bishopAndKnight.isInsufficientMaterial())
        assertEquals(2, Board.initialState.kingCount())
        assertEquals(2, twoWhiteBishops.kingCount())
    }

    @Test fun `fen round trips castling en passant and turn`() {
        assertEquals(
            "rnbqkbnr/pppppppp/8/8/4P3/8/PPPP1PPP/RNBQKBNR b KQkq e3 0 1",
            Board.initialState.movePiece(sq('e', 2), sq('e', 4)).toFen(),
        )
        val castle = Board.fromFen("r3k2r/8/8/8/8/8/8/R3K2R w KQkq - 0 1")
        assertTrue(castle.isValidMove(sq('e', 1), sq('g', 1)))
        assertTrue(castle.isValidMove(sq('e', 8), sq('c', 8)))
        assertTrue(castle.toFen().contains(" KQkq "))
        val noRights = Board.fromFen("r3k2r/8/8/8/8/8/8/R3K2R w - - 0 1")
        assertFalse(noRights.isValidMove(sq('e', 1), sq('g', 1)), "FEN '-' marks king and rook moved")
        val afterKingMove = castle.movePiece(sq('e', 1), sq('d', 1))
        assertTrue(afterKingMove.toFen().contains(" kq "), afterKingMove.toFen())
        val ep = Board.fromFen("8/8/8/3pP3/8/8/8/4K2k w - d6 0 1")
        assertTrue(ep.isValidMove(sq('e', 5), sq('d', 6)), "FEN en-passant target enables the capture")
        assertFailsWith<IllegalArgumentException> { Board.fromFen("8/8/8/8/8/8/8/4X3 w - - 0 1") }
        val blackToMove = Board.fromFen("8/8/8/8/8/8/8/4K2k b - - 0 1")
        assertNotNull(blackToMove.pieces[sq('e', 1).row][sq('e', 1).col])
    }

    @Test fun `movePiece on an empty square throws and empty-board queries are safe`() {
        val empty = board(sq('e', 1) to piece(PieceType.KING, PieceColor.WHITE))
        assertFailsWith<IllegalStateException> { empty.movePiece(sq('a', 1), sq('a', 2)) }
        assertFalse(empty.isValidMove(sq('a', 1), sq('a', 2)))
        assertFalse(empty.isKingInCheck(PieceColor.BLACK), "no black king: not in check")
        assertFalse(empty.hasLegalMoves(PieceColor.BLACK))
    }
}
