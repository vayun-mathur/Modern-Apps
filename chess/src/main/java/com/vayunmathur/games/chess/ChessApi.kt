package com.vayunmathur.games.chess

class ChessApi {
    suspend fun getBestMove(board: Board): Move {
        StockfishEngine.nextMove(board)
        while (true) {
            val lineSplit =
                (StockfishEngine.outputChannel.tryReceive().getOrNull() ?: continue).split(" ")
            if (lineSplit[0] == "bestmove") {
                val moveString = lineSplit[1]
                val startPosition = Position(8-(moveString[1] - '0'), moveString[0] - 'a', )
                val endPosition = Position(8-(moveString[3] - '0'),moveString[2] - 'a', )
                println(startPosition)
                println(endPosition)
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