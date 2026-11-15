package com.vayunmathur.games.chess.data

import com.vayunmathur.games.chess.util.captures
import com.vayunmathur.games.chess.util.countColor
import com.vayunmathur.games.chess.util.detectCaptureMove
import com.vayunmathur.games.chess.util.isFailingMove
import com.vayunmathur.games.chess.util.isSuccess
import com.vayunmathur.games.chess.util.positionsOf
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Learn goal evaluation branches and the binary puzzle record decoder. */
class LearnAndPuzzleDataTest {
    private fun sq(file: Char, rank: Int) = Position(8 - rank, file - 'a')
    private fun board(vararg placements: Pair<Position, Piece>): Board {
        val grid = MutableList(8) { MutableList<Piece?>(8) { null } }
        for ((pos, piece) in placements) grid[pos.row][pos.col] = piece
        return Board(grid.map { it.toList() })
    }
    private fun level(
        goalType: String,
        detectCapture: String? = null,
        n: Int? = null,
        nbMoves: Int = 1,
        failOn: List<String> = emptyList(),
        offPath: List<String> = emptyList(),
    ) = LearnLevel(
        goal = "goal", fen = "8/8/8/8/8/8/8/8 w - - 0 1", goalType = goalType,
        nbMoves = nbMoves, detectCapture = detectCapture, n = n,
        failIfWhitePawnOn = failOn, failIfPieceOffPath = offPath,
    )

    @Test fun `learn level derived properties parse squares and colour`() {
        val black = LearnLevel(goal = "g", fen = "8/8/8/8/8/8/8/8 b - - 0 1", color = "black",
            goalType = "apples", apples = "e7 g5  ")
        assertEquals(PieceColor.BLACK, black.playerColor)
        assertEquals(listOf(sq('e', 7), sq('g', 5)), black.appleSquares)
        val bare = LearnLevel(goal = "g", fen = "8/8/8/8/8/8/8/8 w - - 0 1", goalType = "apples")
        assertEquals(PieceColor.WHITE, bare.playerColor)
        assertEquals(emptyList(), bare.appleSquares)
        assertEquals(sq('e', 4), square("e4"))
        assertEquals(Triple(sq('e', 2), sq('e', 4), null), parseUci("e2e4"))
        assertEquals(PieceType.ROOK, parseUci("a7a8r").third)
        assertNull(parseUci("a7a8x").third, "unknown promotion letter decodes to no promotion")
    }

    @Test fun `buildBoard plants apples as enemy pawns unless empty or occupied`() {
        val rookLevel = LearnLevel(goal = "g", fen = "8/8/8/8/8/8/4R3/8 w - - 0 1",
            goalType = "apples", apples = "e7")
        val built = LearnRepository.buildBoard(rookLevel)
        val apple = built.pieces[sq('e', 7).row][sq('e', 7).col]
        assertEquals(PieceType.PAWN, apple?.type)
        assertEquals(PieceColor.BLACK, apple?.color, "white player collects black apple pawns")
        val empty = LearnRepository.buildBoard(rookLevel.copy(emptyApples = true))
        assertNull(empty.pieces[sq('e', 7).row][sq('e', 7).col])
        val blackPlayer = LearnRepository.buildBoard(rookLevel.copy(color = "black"))
        assertEquals(PieceColor.WHITE, blackPlayer.pieces[sq('e', 7).row][sq('e', 7).col]?.color)
    }

    @Test fun `success evaluation covers check mate escape castle and captureAll`() {
        val mate = board(
            sq('a', 8) to Piece(PieceType.KING, PieceColor.BLACK),
            sq('b', 7) to Piece(PieceType.QUEEN, PieceColor.WHITE),
            sq('c', 6) to Piece(PieceType.KING, PieceColor.WHITE),
        )
        assertTrue(isSuccess(level("mate"), mate, emptySet(), PieceColor.WHITE))
        assertTrue(isSuccess(level("check"), mate, emptySet(), PieceColor.WHITE))
        assertTrue(isSuccess(level("checkIn"), mate, emptySet(), PieceColor.WHITE))
        assertFalse(isSuccess(level("escapeCheck"), mate, emptySet(), PieceColor.BLACK),
            "the mated king is still in check")
        assertTrue(isSuccess(level("escapeCheck"), mate, emptySet(), PieceColor.WHITE))
        assertTrue(isSuccess(level("protection"), mate, emptySet(), PieceColor.WHITE))
        assertFalse(isSuccess(level("captureAll"), mate, emptySet(), PieceColor.WHITE))
        val noBlack = board(sq('e', 1) to Piece(PieceType.KING, PieceColor.WHITE))
        assertTrue(isSuccess(level("captureAll"), noBlack, emptySet(), PieceColor.WHITE))
        val castled = Board.fromFen("r3k2r/8/8/8/8/8/8/R3K2R w KQkq - 0 1")
            .movePiece(sq('e', 1), sq('g', 1))
        assertTrue(isSuccess(level("castle"), castled, emptySet(), PieceColor.WHITE))
        assertFalse(isSuccess(level("castle"), mate, emptySet(), PieceColor.WHITE))
        assertFalse(isSuccess(level("unknownGoal"), mate, setOf(sq('a', 1)), PieceColor.WHITE),
            "unknown goals fall back to collecting every apple")
    }

    @Test fun `detectCapture distinguishes any capture from an unprotected one`() {
        val hanging = board(
            sq('e', 1) to Piece(PieceType.KING, PieceColor.WHITE),
            sq('e', 8) to Piece(PieceType.KING, PieceColor.BLACK),
            sq('d', 4) to Piece(PieceType.QUEEN, PieceColor.WHITE),
            sq('d', 8) to Piece(PieceType.QUEEN, PieceColor.BLACK),
        )
        assertEquals(sq('d', 8) to sq('d', 4),
            detectCaptureMove(level("captureAll", detectCapture = "all"), hanging, PieceColor.WHITE))
        assertEquals(sq('d', 8) to sq('d', 4),
            detectCaptureMove(level("captureAll", detectCapture = "unprotected"), hanging, PieceColor.WHITE),
            "nothing defends the queen on d4")
        val defended = board(
            sq('e', 1) to Piece(PieceType.KING, PieceColor.WHITE),
            sq('e', 8) to Piece(PieceType.KING, PieceColor.BLACK),
            sq('d', 4) to Piece(PieceType.QUEEN, PieceColor.WHITE),
            sq('c', 3) to Piece(PieceType.PAWN, PieceColor.WHITE),
            sq('d', 8) to Piece(PieceType.QUEEN, PieceColor.BLACK),
        )
        assertNull(detectCaptureMove(level("captureAll", detectCapture = "unprotected"), defended, PieceColor.WHITE),
            "the c3 pawn recaptures on d4, so the queen is protected")
        assertNotNull(detectCaptureMove(level("captureAll", detectCapture = "all"), defended, PieceColor.WHITE))
        assertNull(detectCaptureMove(level("captureAll", detectCapture = "none"), hanging, PieceColor.WHITE))
        assertEquals(1, captures(hanging, PieceColor.BLACK).size)
        assertEquals(listOf(sq('d', 4), sq('e', 1)), positionsOf(hanging, PieceColor.WHITE))
        assertEquals(2, countColor(hanging, PieceColor.BLACK))
    }

    @Test fun `failing move covers path constraints and timed check goals`() {
        val pawnAstray = board(
            sq('e', 1) to Piece(PieceType.KING, PieceColor.WHITE),
            sq('e', 8) to Piece(PieceType.KING, PieceColor.BLACK),
            sq('e', 4) to Piece(PieceType.PAWN, PieceColor.WHITE),
        )
        assertTrue(isFailingMove(level("apples", failOn = listOf("e4")), pawnAstray, PieceColor.WHITE, 1))
        assertFalse(isFailingMove(level("apples", failOn = listOf("e5")), pawnAstray, PieceColor.WHITE, 1))
        assertTrue(isFailingMove(level("apples", offPath = listOf("e1", "e8")), pawnAstray, PieceColor.WHITE, 1),
            "the pawn left the allowed path squares")
        assertFalse(isFailingMove(
            level("apples", offPath = listOf("e1", "e8", "e4")), pawnAstray, PieceColor.WHITE, 1))
        val kings = board(
            sq('e', 1) to Piece(PieceType.KING, PieceColor.WHITE),
            sq('e', 8) to Piece(PieceType.KING, PieceColor.BLACK),
        )
        assertFalse(isFailingMove(level("checkIn", n = 3), kings, PieceColor.WHITE, 1),
            "checkIn still has moves left")
        assertTrue(isFailingMove(level("checkIn", n = 3), kings, PieceColor.WHITE, 3),
            "checkIn out of moves without check fails")
        assertTrue(isFailingMove(level("escapeCheck"), board(
            sq('e', 1) to Piece(PieceType.KING, PieceColor.WHITE),
            sq('e', 8) to Piece(PieceType.KING, PieceColor.BLACK),
            sq('e', 7) to Piece(PieceType.ROOK, PieceColor.BLACK),
        ), PieceColor.WHITE, 1), "still in check after the move")
    }

    /** Builds one binary puzzle record at offset 0 (see PuzzleRepository format). */
    private fun record(flags: Int, ep: Int, rating: Int, moves: List<Int>, nibbles: Map<Int, Int>): ByteArray {
        val bytes = ByteArray(37 + 2 * moves.size)
        for ((square, nibble) in nibbles) {
            val b = square / 2
            bytes[b] = if (square % 2 == 0) {
                (bytes[b].toInt() or nibble).toByte()
            } else {
                (bytes[b].toInt() or (nibble shl 4)).toByte()
            }
        }
        bytes[32] = flags.toByte()
        bytes[33] = ep.toByte()
        val bb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        bb.putShort(34, rating.toShort())
        bytes[36] = moves.size.toByte()
        moves.forEachIndexed { i, m -> bb.putShort(37 + 2 * i, m.toShort()) }
        return bytes
    }

    private fun moveBits(from: Int, to: Int, promo: Int = 0) = from or (to shl 6) or (promo shl 12)

    @Test fun `puzzle record decoder reads pieces side castling ep rating and moves`() {
        // WK e1 (square 60, king=1 white), BK e8 (square 4, king=1 black=9), WP a7 (square 8, pawn=6)
        val bytes = record(
            flags = 0x01 or 0x02 or 0x10, // black to move, white kingside + black queenside rights
            ep = 19, // d6
            rating = 1500,
            moves = listOf(moveBits(52, 36), moveBits(8, 0, promo = 1), moveBits(6, 22, promo = 4)),
            nibbles = mapOf(60 to 1, 4 to 9, 8 to 6),
        )
        val puzzle = PuzzleRepository.decodeRecord(bytes, 0)
        assertEquals(1500, puzzle.rating)
        assertEquals(3, puzzle.solution.size)
        assertEquals(Position(6, 4), puzzle.solution[0].from) // e2
        assertEquals(Position(4, 4), puzzle.solution[0].to) // e4
        assertNull(puzzle.solution[0].promotion)
        assertEquals(PieceType.QUEEN, puzzle.solution[1].promotion)
        assertEquals(PieceType.KNIGHT, puzzle.solution[2].promotion)
        val king = puzzle.board.pieces[7][4]
        assertEquals(PieceType.KING, king?.type)
        assertFalse(king!!.hasMoved, "a retained castling right keeps the king unmoved")
        assertEquals(PieceType.PAWN, puzzle.board.pieces[1][0]?.type) // a7
        assertNotNull(puzzle.board.lastMove, "ep target seeds a synthetic last move")
    }

    @Test fun `puzzle record without ep or promotion decodes plainly`() {
        val bytes = record(
            flags = 0, ep = 0xFF, rating = 400,
            moves = listOf(moveBits(62, 45, promo = 2), moveBits(57, 42, promo = 3)),
            nibbles = mapOf(62 to 1, 6 to 9),
        )
        val puzzle = PuzzleRepository.decodeRecord(bytes, 0)
        assertEquals(400, puzzle.rating)
        assertEquals(PieceType.ROOK, puzzle.solution[0].promotion)
        assertEquals(PieceType.BISHOP, puzzle.solution[1].promotion)
        assertTrue(puzzle.board.pieces[7][6]?.hasMoved == true, "no castling rights: king counts as moved")
    }
}
