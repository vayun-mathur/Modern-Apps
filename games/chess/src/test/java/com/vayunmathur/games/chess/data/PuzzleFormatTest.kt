package com.vayunmathur.games.chess.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Verifies the `puzzles.dat` binary format round-trips: a record encoded exactly
 * as `scripts/chess/generate_puzzles.py` writes it decodes back to the same
 * position (board + solution). Also covers [Board.fromFen] reconstruction of
 * castling rights and en-passant, which the decoder relies on.
 */
class PuzzleFormatTest {

    // ---- Test-side encoder mirroring generate_puzzles.py (kept in sync with it). ----

    private fun encodeRecord(fen: String, moves: String, rating: Int): ByteArray {
        val parts = fen.split(" ")
        val placement = parts[0]
        val side = parts[1]
        val castling = parts[2]
        val ep = parts[3]

        val board = IntArray(64)
        var row = 0
        var col = 0
        for (ch in placement) {
            when {
                ch == '/' -> { row++; col = 0 }
                ch.isDigit() -> col += ch - '0'
                else -> {
                    val type = when (ch.lowercaseChar()) {
                        'k' -> 1; 'q' -> 2; 'r' -> 3; 'b' -> 4; 'n' -> 5; 'p' -> 6; else -> 0
                    }
                    val color = if (ch.isUpperCase()) 0 else 1
                    board[row * 8 + col] = type or (color shl 3)
                    col++
                }
            }
        }

        val out = ByteArrayOutputStream()
        for (b in 0 until 32) {
            out.write(board[2 * b] or (board[2 * b + 1] shl 4))
        }
        var cbits = 0
        if (castling.contains('K')) cbits = cbits or 1
        if (castling.contains('Q')) cbits = cbits or 2
        if (castling.contains('k')) cbits = cbits or 4
        if (castling.contains('q')) cbits = cbits or 8
        val stm = if (side == "w") 0 else 1
        out.write(stm or (cbits shl 1))

        val epSq = if (ep == "-") 0xFF else (8 - (ep[1] - '0')) * 8 + (ep[0] - 'a')
        out.write(epSq)

        writeShortLE(out, rating)
        val moveList = if (moves.isBlank()) emptyList() else moves.split(" ")
        out.write(moveList.size)
        for (uci in moveList) {
            val fromSq = (8 - (uci[1] - '0')) * 8 + (uci[0] - 'a')
            val toSq = (8 - (uci[3] - '0')) * 8 + (uci[2] - 'a')
            val promo = if (uci.length >= 5) when (uci[4]) {
                'q' -> 1; 'r' -> 2; 'b' -> 3; 'n' -> 4; else -> 0
            } else 0
            writeShortLE(out, fromSq or (toSq shl 6) or (promo shl 12))
        }
        return out.toByteArray()
    }

    private class ByteArrayOutputStream {
        private val buf = ArrayList<Byte>()
        fun write(v: Int) { buf.add((v and 0xFF).toByte()) }
        fun toByteArray() = buf.toByteArray()
    }

    private fun writeShortLE(out: ByteArrayOutputStream, v: Int) {
        out.write(v and 0xFF)
        out.write((v shr 8) and 0xFF)
    }

    private fun fen4(fen: String): String = fen.split(" ").take(4).joinToString(" ")

    private fun sq(file: Char, rank: Int) = Position(8 - rank, file - 'a')

    // ---- Tests ----

    @Test
    fun decodeRecord_reconstructsBoardAndSolution() {
        val fen = "r6k/pp2r2p/4Rp1Q/3p4/8/1N1P2R1/PqP2bPP/7K b - - 0 24"
        val moves = "f2g3 e6e7 b2b1 b3c1 b1c1 h6c1"
        val rec = encodeRecord(fen, moves, 1784)

        val puzzle = PuzzleRepository.decodeRecord(rec, 0)

        assertEquals(1784, puzzle.rating)
        assertEquals(fen4(fen), fen4(puzzle.board.toFen()))
        assertEquals(6, puzzle.solution.size)
        assertEquals(sq('f', 2), puzzle.solution[0].from)
        assertEquals(sq('g', 3), puzzle.solution[0].to)
        assertNull(puzzle.solution[0].promotion)
        assertEquals(sq('h', 6), puzzle.solution[5].from)
        assertEquals(sq('c', 1), puzzle.solution[5].to)
    }

    @Test
    fun decodeRecord_castlingRightsRoundTrip() {
        val fen = "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1"
        val rec = encodeRecord(fen, "e2e4 e7e5", 900)
        val puzzle = PuzzleRepository.decodeRecord(rec, 0)
        assertEquals(fen4(fen), fen4(puzzle.board.toFen()))
    }

    @Test
    fun decodeRecord_promotionMoveDecodes() {
        val fen = "8/P6k/8/8/8/8/7K/8 w - - 0 1"
        val rec = encodeRecord(fen, "a7a8q", 1500)
        val puzzle = PuzzleRepository.decodeRecord(rec, 0)
        assertEquals(sq('a', 7), puzzle.solution[0].from)
        assertEquals(sq('a', 8), puzzle.solution[0].to)
        assertEquals(PieceType.QUEEN, puzzle.solution[0].promotion)
    }

    @Test
    fun fromFen_startPosition_roundTrips() {
        val fen = "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1"
        assertEquals(fen4(fen), fen4(Board.fromFen(fen).toFen()))
    }

    @Test
    fun fromFen_partialCastling_roundTrips() {
        // White kingside + black queenside only.
        val fen = "r3k2r/8/8/8/8/8/8/R3K2R w Kq - 0 1"
        assertEquals(fen4(fen), fen4(Board.fromFen(fen).toFen()))
    }

    @Test
    fun fromFen_enPassant_whiteToMove_roundTrips() {
        val fen = "rnbqkbnr/ppp1pppp/8/3pP3/8/8/PPPP1PPP/RNBQKBNR w KQkq d6 0 3"
        assertEquals(fen4(fen), fen4(Board.fromFen(fen).toFen()))
    }

    @Test
    fun fromFen_enPassant_blackToMove_roundTrips() {
        val fen = "rnbqkbnr/pppp1ppp/8/8/3Pp3/8/PPP1PPPP/RNBQKBNR b KQkq d3 0 3"
        assertEquals(fen4(fen), fen4(Board.fromFen(fen).toFen()))
    }
}
