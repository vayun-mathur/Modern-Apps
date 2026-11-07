package com.vayunmathur.games.chess.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Pass 3: Board.promotePawn, SAN notation (Move.toString), en passant. */
class BoardPromotionPass3Test {

    private fun pos(s: String) = Position(8 - (s[1] - '0'), s[0] - 'a')

    @Test fun promotePawnToQueenGivesCheckAndRecordsPromotion() {
        // White pawn b7 promotes on b8, checking the black king on a8 along the rank.
        var board = Board.fromFen("k7/1P6/8/8/8/8/8/4K2k w - - 0 1")
        board = board.movePiece(pos("b7"), pos("b8"))
        assertEquals(pos("b8"), board.promotionPosition)
        val promoted = board.promotePawn(pos("b8"), PieceType.QUEEN)
        assertNull(promoted.promotionPosition)
        assertEquals(PieceType.QUEEN, promoted.pieces[0][1]?.type)
        assertEquals(PieceColor.WHITE, promoted.pieces[0][1]?.color)
        assertEquals(PieceType.QUEEN, promoted.lastMove?.promotedTo)
        assertTrue(promoted.lastMove?.isCheck == true, "queen on b8 checks the a8 king")
        assertEquals("b8=Q+", promoted.lastMove.toString())
    }

    @Test fun promotePawnUnderpromotions() {
        var board = Board.fromFen("k7/1P6/8/8/8/8/8/4K2k w - - 0 1")
        board = board.movePiece(pos("b7"), pos("b8"))
        val knight = board.promotePawn(pos("b8"), PieceType.KNIGHT)
        assertEquals(PieceType.KNIGHT, knight.pieces[0][1]?.type)
        assertFalse(knight.lastMove?.isCheck == true, "knight on b8 does not check a8")
        assertEquals("b8=N", knight.lastMove.toString())

        var black = Board.fromFen("4k2K/8/8/8/8/8/1p6/8 b - - 0 1")
        black = black.movePiece(pos("b2"), pos("b1"))
        val rook = black.promotePawn(pos("b1"), PieceType.ROOK)
        assertEquals(PieceType.ROOK, rook.pieces[7][1]?.type)
        assertEquals(PieceColor.BLACK, rook.pieces[7][1]?.color)
    }

    @Test fun promotePawnWithNoMovesRecorded() {
        val grid = Board.fromFen("k7/1P6/8/8/8/8/8/4K2k w - - 0 1").pieces
        val board = Board(pieces = grid, promotionPosition = pos("b7"))
        val promoted = board.promotePawn(pos("b7"), PieceType.QUEEN)
        assertEquals(PieceType.QUEEN, promoted.pieces[1][1]?.type)
        assertTrue(promoted.moves.isEmpty())
        assertNull(promoted.promotionPosition)
    }

    @Test fun enPassantCaptureRemovesPawnAndNotates() {
        var board = Board.initialState
        fun mv(f: String, t: String) {
            board = board.movePiece(pos(f), pos(t))
        }
        mv("e2", "e4"); mv("a7", "a6"); mv("e4", "e5"); mv("d7", "d5")
        // White pawn e5 captures en passant on d6.
        assertTrue(board.isValidMove(pos("e5"), pos("d6")))
        board = board.movePiece(pos("e5"), pos("d6"))
        assertEquals(PieceType.PAWN, board.pieces[2][3]?.type)
        assertEquals(PieceColor.WHITE, board.pieces[2][3]?.color)
        assertNull(board.pieces[3][3], "the captured pawn on d5 is removed")
        assertEquals(1, board.capturedByWhite.size)
        assertEquals("exd6", board.lastMove.toString())
    }

    @Test fun sanCastlingAndDisambiguation() {
        var board = Board.initialState
        fun mv(f: String, t: String) {
            board = board.movePiece(pos(f), pos(t))
        }
        mv("e2", "e4"); mv("e7", "e5"); mv("g1", "f3"); mv("b8", "c6")
        mv("f1", "c4"); mv("f8", "c5"); mv("e1", "g1")
        assertEquals("O-O", board.lastMove.toString())

        // Two white rooks can both reach d1: the moving rook's file disambiguates.
        val rooks = Board.fromFen("4k3/8/8/8/8/8/8/R4R1K w - - 0 1")
        val moved = rooks.movePiece(pos("a1"), pos("d1"))
        assertEquals("Rad1", moved.lastMove.toString())
        val movedOther = rooks.movePiece(pos("f1"), pos("d1"))
        assertEquals("Rfd1", movedOther.lastMove.toString())
    }

    @Test fun sanCaptureAndCheckSuffixes() {
        var board = Board.initialState
        fun mv(f: String, t: String) {
            board = board.movePiece(pos(f), pos(t))
        }
        mv("e2", "e4"); mv("d7", "d5")
        board = board.movePiece(pos("e4"), pos("d5"))
        assertEquals("exd5", board.lastMove.toString())

        // Scholar's mate final move is a capture with mate: Qxf7#
        var mate = Board.initialState
        fun mm(f: String, t: String) {
            mate = mate.movePiece(pos(f), pos(t))
        }
        mm("e2", "e4"); mm("e7", "e5"); mm("f1", "c4"); mm("b8", "c6")
        mm("d1", "h5"); mm("g8", "f6"); mm("h5", "f7")
        assertEquals("Qxf7#", mate.lastMove.toString())
        assertTrue(mate.lastMove?.isCheckmate == true)
    }
}
