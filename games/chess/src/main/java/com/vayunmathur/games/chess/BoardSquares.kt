package com.vayunmathur.games.chess

import com.vayunmathur.games.chess.data.Board
import com.vayunmathur.games.chess.data.Move
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

/**
 * The move to mark as "last move", or null. The puzzle board seeds a synthetic zero-length
 * lastMove to encode the side to move, which is never shown.
 */
internal fun visibleLastMove(board: Board, showLastMove: Boolean): Move? =
    board.lastMove?.takeIf { showLastMove && it.start != it.end }

/**
 * Whether [lastMove]'s piece should slide in from its start square: every new move does (the
 * AI's reply, the opponent's move, a tap-tap move), except the move already on the board when
 * the screen opened ([initialMove]) and the one the player just dropped by drag ([lastDrop]),
 * whose piece is already where the finger left it.
 */
internal fun shouldSlideIn(lastMove: Move?, initialMove: Move?, lastDrop: Pair<Position, Position>?): Boolean =
    lastMove != null && lastMove != initialMove && lastDrop != (lastMove.start to lastMove.end)
