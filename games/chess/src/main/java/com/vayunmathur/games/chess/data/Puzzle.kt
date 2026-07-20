package com.vayunmathur.games.chess.data

import android.content.Context
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** One move of a puzzle solution: just the coordinates and (optional) promotion. */
data class PuzzleMove(val from: Position, val to: Position, val promotion: PieceType?)

/**
 * A single Lichess puzzle: the starting [board] (the position *before* the
 * opponent's setup move) and the full [solution] as a UCI-style move list.
 * By Lichess convention `solution[0]` is played automatically by the opponent,
 * then the player must find `solution[1]`, the opponent replies `solution[2]`, etc.
 */
data class Puzzle(val rating: Int, val board: Board, val solution: List<PuzzleMove>)

/**
 * Loads and decodes the bundled `puzzles.dat` asset produced by
 * `scripts/chess/generate_puzzles.py`. The blob is read once into memory; a
 * lightweight index (record offsets + ratings) is built so puzzles can be picked
 * by difficulty and decoded one at a time, without materializing ~50k Boards.
 *
 * Binary format is documented in the generator; it is little-endian, records are
 * sorted by rating ascending, and each record is:
 *   32B board nibbles, 1B flags, 1B enPassant, 2B rating, 1B moveCount, 2B*M moves.
 */
object PuzzleRepository {
    private const val ASSET_NAME = "puzzles.dat"
    private const val HEADER_SIZE = 8
    private const val EP_NONE = 0xFF

    private var data: ByteArray? = null
    private var offsets: IntArray = IntArray(0)
    private var ratings: IntArray = IntArray(0)

    val count: Int get() = offsets.size

    /** Reads and indexes the asset if not already loaded. Safe to call repeatedly. */
    @Synchronized
    fun ensureLoaded(context: Context) {
        if (data != null) return

        val bytes = context.assets.open(ASSET_NAME).use { it.readBytes() }
        require(bytes.size >= HEADER_SIZE) { "puzzles.dat too small" }
        require(
            bytes[0] == 'C'.code.toByte() && bytes[1] == 'P'.code.toByte() &&
                bytes[2] == 'Z'.code.toByte() && bytes[3] == '1'.code.toByte()
        ) { "puzzles.dat: bad magic" }

        val bb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val n = bb.getInt(4)
        val offs = IntArray(n)
        val rts = IntArray(n)

        var off = HEADER_SIZE
        for (i in 0 until n) {
            offs[i] = off
            rts[i] = bb.getShort(off + 34).toInt() and 0xFFFF
            val moveCount = bytes[off + 36].toInt() and 0xFF
            off += 37 + 2 * moveCount
        }

        data = bytes
        offsets = offs
        ratings = rts
    }

    /**
     * Picks a random puzzle, optionally restricted to a rating [band] (inclusive).
     * Returns null only if the repository is empty or the band matches nothing.
     */
    fun random(band: IntRange? = null): Puzzle? {
        if (count == 0) return null
        val index = if (band == null) {
            (0 until count).random()
        } else {
            val start = lowerBound(band.first)
            val end = upperBound(band.last)
            if (end <= start) return null
            (start until end).random()
        }
        return decode(index)
    }

    /** Decodes the record at [index] into a [Puzzle]. */
    fun decode(index: Int): Puzzle {
        val bytes = data ?: error("PuzzleRepository not loaded")
        return decodeRecord(bytes, offsets[index])
    }

    /** Decodes a single record starting at [off] in [bytes]. Exposed for tests. */
    internal fun decodeRecord(bytes: ByteArray, off: Int): Puzzle {
        val bb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)

        val grid = MutableList(8) { MutableList<Piece?>(8) { null } }
        for (b in 0 until 32) {
            val byte = bytes[off + b].toInt() and 0xFF
            placeNibble(grid, 2 * b, byte and 0x0F)
            placeNibble(grid, 2 * b + 1, (byte shr 4) and 0x0F)
        }

        val flags = bytes[off + 32].toInt() and 0xFF
        val sideToMove = if (flags and 0x01 == 0) PieceColor.WHITE else PieceColor.BLACK
        val castling = CastlingRights(
            whiteKing = flags and 0x02 != 0,
            whiteQueen = flags and 0x04 != 0,
            blackKing = flags and 0x08 != 0,
            blackQueen = flags and 0x10 != 0
        )

        val epByte = bytes[off + 33].toInt() and 0xFF
        val epSquare = if (epByte == EP_NONE) null else Position(epByte / 8, epByte % 8)
        val rating = bb.getShort(off + 34).toInt() and 0xFFFF

        val moveCount = bytes[off + 36].toInt() and 0xFF
        val solution = ArrayList<PuzzleMove>(moveCount)
        for (i in 0 until moveCount) {
            val m = bb.getShort(off + 37 + 2 * i).toInt() and 0xFFFF
            val fromSq = m and 0x3F
            val toSq = (m shr 6) and 0x3F
            val promo = (m shr 12) and 0x07
            solution.add(
                PuzzleMove(
                    from = Position(fromSq / 8, fromSq % 8),
                    to = Position(toSq / 8, toSq % 8),
                    promotion = promoType(promo)
                )
            )
        }

        val board = Board.fromPuzzle(grid.map { it.toList() }, sideToMove, castling, epSquare)
        return Puzzle(rating, board, solution)
    }

    private fun placeNibble(grid: MutableList<MutableList<Piece?>>, square: Int, nibble: Int) {
        if (nibble == 0) return
        val type = when (nibble and 0x07) {
            1 -> PieceType.KING; 2 -> PieceType.QUEEN; 3 -> PieceType.ROOK
            4 -> PieceType.BISHOP; 5 -> PieceType.KNIGHT; 6 -> PieceType.PAWN
            else -> return
        }
        val color = if ((nibble shr 3) and 0x01 == 0) PieceColor.WHITE else PieceColor.BLACK
        grid[square / 8][square % 8] = Piece(type, color)
    }

    private fun promoType(code: Int): PieceType? = when (code) {
        1 -> PieceType.QUEEN; 2 -> PieceType.ROOK; 3 -> PieceType.BISHOP; 4 -> PieceType.KNIGHT
        else -> null
    }

    /** First index whose rating >= [value] (ratings are sorted ascending). */
    private fun lowerBound(value: Int): Int {
        var lo = 0
        var hi = ratings.size
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (ratings[mid] < value) lo = mid + 1 else hi = mid
        }
        return lo
    }

    /** First index whose rating > [value] (ratings are sorted ascending). */
    private fun upperBound(value: Int): Int {
        var lo = 0
        var hi = ratings.size
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (ratings[mid] <= value) lo = mid + 1 else hi = mid
        }
        return lo
    }
}
