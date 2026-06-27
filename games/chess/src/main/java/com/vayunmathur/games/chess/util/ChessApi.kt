package com.vayunmathur.games.chess.util
import com.vayunmathur.games.chess.data.Board
import com.vayunmathur.games.chess.data.Move
import com.vayunmathur.games.chess.data.Position
import com.vayunmathur.games.chess.data.PieceType

class ChessApi {
    /**
     * Returns the engine's best move, or null when the engine reports it has no move to play
     * ("bestmove (none)" / "0000" — i.e. the AI is checkmated or stalemated). Callers must treat
     * null as "no move available" rather than parsing it as a board coordinate.
     */
    suspend fun getBestMove(board: Board): Move? {
        StockfishEngine.nextMove(board)
        while (true) {
            val lineSplit = StockfishEngine.outputChannel.receive().split(" ")
            if (lineSplit[0] == "bestmove") {
                val moveString = lineSplit.getOrNull(1) ?: return null
                if (moveString == "(none)" || moveString == "0000" || moveString.length < 4) return null
                val startPosition = Position(8 - (moveString[1] - '0'), moveString[0] - 'a')
                val endPosition = Position(8 - (moveString[3] - '0'), moveString[2] - 'a')
                val promotedTo = when (moveString.getOrNull(4)) {
                    'q' -> PieceType.QUEEN
                    'r' -> PieceType.ROOK
                    'b' -> PieceType.BISHOP
                    'n' -> PieceType.KNIGHT
                    else -> null
                }
                return Move(startPosition, endPosition, board.pieces[startPosition.row][startPosition.col]!!, promotedTo = promotedTo)
            }
        }
    }
}
