package com.vayunmathur.games.chess

import com.vayunmathur.games.chess.data.Board
import com.vayunmathur.games.chess.data.PieceColor
import com.vayunmathur.games.chess.data.Position
import com.vayunmathur.games.chess.data.findKing

// File-local copy of the board dimension, matching the other chess files' convention.
private const val BOARD_SQUARES = 8

// Pure per-position helpers behind BoardGrid, kept out of composition so the board computes
// each of them once per position (under `remember`) instead of once per square.

/**
 * The square of [turn]'s king when that king is in check, or null.
 *
 * `isKingInCheck` walks every enemy piece, so asking it from each of the 64 squares on every
 * recomposition (as the board used to) cost 64 full scans a frame; this is called once per
 * position.
 */
internal fun checkedKingSquare(board: Board, turn: PieceColor): Position? =
    if (board.isKingInCheck(turn)) board.findKing(turn) else null

/** The squares the piece on [selectedPiece] can legally move to (for move-hint dots). */
internal fun legalDestinations(board: Board, selectedPiece: Position?): Set<Position> {
    val sel = selectedPiece ?: return emptySet()
    return buildSet {
        for (i in 0 until BOARD_SQUARES) {
            for (j in 0 until BOARD_SQUARES) {
                if (board.isValidMove(sel, Position(i, j))) add(Position(i, j))
            }
        }
    }
}
