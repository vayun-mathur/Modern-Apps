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

/** Pass 3: LearnRules goal/scoring pure functions over hand-built FEN boards. */
class LearnRulesPass3Test {

    private fun level(
        goalType: String,
        fen: String = "8/8/8/8/8/8/8/8 w - - 0 1",
        nbMoves: Int = 1,
        detectCapture: String? = null,
        n: Int? = null,
        failWhite: List<String> = emptyList(),
        failOffPath: List<String> = emptyList(),
    ) = LearnLevel(
        goal = "g", fen = fen, goalType = goalType, nbMoves = nbMoves,
        detectCapture = detectCapture, n = n,
        failIfWhitePawnOn = failWhite, failIfPieceOffPath = failOffPath,
    )

    private fun board(fen: String) = Board.fromFen(fen)

    @Test fun isSuccessPerGoalType() {
        val empty = board("8/8/8/8/8/8/8/8 w - - 0 1")
        assertTrue(isSuccess(level("info"), empty, setOf(Position(0, 0)), PieceColor.WHITE))
        assertTrue(isSuccess(level("apples"), empty, emptySet(), PieceColor.WHITE))
        assertFalse(isSuccess(level("apples"), empty, setOf(Position(1, 1)), PieceColor.WHITE))
        assertTrue(isSuccess(level("unknown-goal"), empty, emptySet(), PieceColor.WHITE))
        assertFalse(isSuccess(level("unknown-goal"), empty, setOf(Position(2, 2)), PieceColor.WHITE))
        // captureAll: no black pieces left.
        val onlyWhite = board("8/8/8/8/8/8/4R3/4K3 w - - 0 1")
        assertTrue(isSuccess(level("captureAll"), onlyWhite, emptySet(), PieceColor.WHITE))
        val withBlack = board("4k3/8/8/8/8/8/4R3/4K3 w - - 0 1")
        assertFalse(isSuccess(level("captureAll"), withBlack, emptySet(), PieceColor.WHITE))
        assertTrue(isSuccess(level("protection"), withBlack, emptySet(), PieceColor.WHITE))
    }

    @Test fun isSuccessCheckEscapeMateCastle() {
        // White queen on h5 checks the black king on e8 along the diagonal? Use a
        // direct rook check instead: rook e1, black king e8.
        val check = board("4k3/8/8/8/8/8/8/4R2K w - - 0 1")
        assertTrue(isSuccess(level("check"), check, emptySet(), PieceColor.WHITE))
        assertTrue(isSuccess(level("checkIn"), check, emptySet(), PieceColor.WHITE))
        assertFalse(isSuccess(level("escapeCheck"), check, emptySet(), PieceColor.BLACK),
            "black king is in check, so black has not escaped")

        val foolsMate = board("rnb1kbnr/pppp1ppp/8/4p3/6Pq/5P2/PPPPP2P/RNBQKBNR w KQkq - 1 3")
        assertTrue(isSuccess(level("mate"), foolsMate, emptySet(), PieceColor.BLACK),
            "white is checkmated by the black queen")

        var b = Board.initialState
        fun mv(f: String, t: String) {
            b = b.movePiece(
                Position(8 - (f[1] - '0'), f[0] - 'a'),
                Position(8 - (t[1] - '0'), t[0] - 'a'),
            )
        }
        mv("e2", "e4"); mv("e7", "e5"); mv("g1", "f3"); mv("b8", "c6")
        mv("f1", "c4"); mv("f8", "c5"); mv("e1", "g1")
        assertTrue(isSuccess(level("castle"), b, emptySet(), PieceColor.WHITE))
    }

    @Test fun detectCaptureModes() {
        // Black queen d5 can take the white rook d1 (hanging, unprotected).
        // Flip the turn with a distant king move so capture legality is evaluated
        // for Black, as it is right after a real player move.
        val hanging = board("4k3/8/8/3q4/8/8/8/3R3K w - - 0 1")
            .movePiece(Position(7, 7), Position(6, 7))
        assertNull(detectCaptureMove(level("apples", detectCapture = null), hanging, PieceColor.WHITE))
        assertNull(detectCaptureMove(level("info", detectCapture = "all"), hanging, PieceColor.WHITE))
        val anyCapture = detectCaptureMove(level("apples", detectCapture = "all"), hanging, PieceColor.WHITE)
        assertEquals(Position(3, 3) to Position(7, 3), anyCapture)
        val unprotected = detectCaptureMove(level("apples", detectCapture = "unprotected"), hanging, PieceColor.WHITE)
        assertEquals(Position(3, 3) to Position(7, 3), unprotected)
        // Protected rook: white king steps e1->e2, from where it defends d1,
        // so 'unprotected' finds nothing (but 'all' still lists the capture).
        val protected = board("4k3/8/8/3q4/8/8/8/3RK3 w - - 0 1")
            .movePiece(Position(7, 4), Position(6, 4))
        assertNull(detectCaptureMove(level("apples", detectCapture = "unprotected"), protected, PieceColor.WHITE))
        assertNull(detectCaptureMove(level("apples", detectCapture = "bogus"), hanging, PieceColor.WHITE))
    }

    @Test fun failingMovePathAndSingleMoveGoals() {
        val pawnBoard = board("8/8/8/8/8/8/4P3/4K2k w - - 0 1")
        assertTrue(
            isFailingMove(level("apples", failWhite = listOf("e2")), pawnBoard, PieceColor.WHITE, 0),
            "white pawn sitting on a forbidden square fails the path constraint",
        )
        assertFalse(isFailingMove(level("apples"), pawnBoard, PieceColor.WHITE, 0))
        assertTrue(
            isFailingMove(level("apples", failOffPath = listOf("e1")), pawnBoard, PieceColor.WHITE, 0),
            "pieces off the allowed path fail",
        )
        assertFalse(
            isFailingMove(level("apples", failOffPath = listOf("e1", "e2", "h1")), pawnBoard, PieceColor.WHITE, 0),
        )

        val quiet = board("4k3/8/8/8/8/8/8/4R2K w - - 0 1")
        // Rook not giving check from a1-style placement: move rook off the e-file.
        val noCheck = board("4k3/8/8/8/8/8/8/R6K w - - 0 1")
        assertTrue(isFailingMove(level("check"), noCheck, PieceColor.WHITE, 1))
        assertFalse(isFailingMove(level("check"), quiet, PieceColor.WHITE, 1))
        assertTrue(isFailingMove(level("checkIn", n = 2), noCheck, PieceColor.WHITE, 2))
        assertFalse(isFailingMove(level("checkIn", n = 2), noCheck, PieceColor.WHITE, 1),
            "checkIn tolerates misses before the move budget runs out")
        assertTrue(isFailingMove(level("mate"), noCheck, PieceColor.WHITE, 1))
        assertTrue(isFailingMove(level("escapeCheck"), quiet, PieceColor.BLACK, 1),
            "still in check means escape failed")
    }

    @Test fun helpersStarsBonusAndValues() {
        assertTrue(isLastRank(Position(0, 4)))
        assertTrue(isLastRank(Position(7, 4)))
        assertFalse(isLastRank(Position(3, 4)))
        val initial = Board.initialState
        assertEquals(16, countColor(initial, PieceColor.WHITE))
        assertEquals(16, countColor(initial, PieceColor.BLACK))
        assertEquals(16, positionsOf(initial, PieceColor.WHITE).size)
        assertTrue(captures(initial, PieceColor.WHITE).isEmpty(), "no captures in the start position")

        assertEquals(PieceType.QUEEN, promotionFor(Piece(PieceType.PAWN, PieceColor.WHITE), Position(0, 3)))
        assertEquals(PieceType.QUEEN, promotionFor(Piece(PieceType.PAWN, PieceColor.BLACK), Position(7, 3)))
        assertNull(promotionFor(Piece(PieceType.PAWN, PieceColor.WHITE), Position(3, 3)))
        assertNull(promotionFor(Piece(PieceType.ROOK, PieceColor.WHITE), Position(0, 3)))

        assertEquals(PieceColor.BLACK, fenSide("8/8/8/8/8/8/8/8 b - - 0 1"))
        assertEquals(PieceColor.WHITE, fenSide("8/8/8/8/8/8/8/8 w - - 0 1"))
        assertEquals(PieceColor.WHITE, fenSide("garbage"))

        val par4 = level("apples", nbMoves = 4)
        assertEquals(3, starsFor(par4, 4))
        assertEquals(2, starsFor(par4, 5))
        assertEquals(1, starsFor(par4, 9))
        assertEquals(500, levelBonus(3))
        assertEquals(300, levelBonus(2))
        assertEquals(100, levelBonus(1))
        assertEquals(100, levelBonus(0))

        assertEquals(90, pieceValue(PieceType.QUEEN))
        assertEquals(50, pieceValue(PieceType.ROOK))
        assertEquals(30, pieceValue(PieceType.BISHOP))
        assertEquals(30, pieceValue(PieceType.KNIGHT))
        assertEquals(10, pieceValue(PieceType.PAWN))
        assertEquals(0, pieceValue(PieceType.KING))
    }
}
