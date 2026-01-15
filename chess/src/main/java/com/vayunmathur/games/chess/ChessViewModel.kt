package com.vayunmathur.games.chess

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

sealed class GameMode {
    object TwoPlayer : GameMode()
    data class VsAI(val playerColor: PieceColor, val difficulty: StockfishEngine.Difficulty) : GameMode()
}

data class ChessUiState(
    val board: Board = Board.initialState,
    val selectedPiece: Position? = null,
    val gameMode: GameMode = GameMode.TwoPlayer,
    val turn: PieceColor = PieceColor.WHITE,
    val gameStatus: String? = null,
    val isBoardFlipped: Boolean = false
)

class ChessViewModel : ViewModel() {

    private val _uiState = MutableStateFlow(ChessUiState())
    val uiState: StateFlow<ChessUiState> = _uiState.asStateFlow()
    private val chessApi = ChessApi()

    fun onNewGame(gameMode: GameMode) {
        val isFlipped = gameMode is GameMode.VsAI && gameMode.playerColor == PieceColor.BLACK
        _uiState.value = ChessUiState(gameMode = gameMode, isBoardFlipped = isFlipped)
        if(gameMode is GameMode.VsAI) {
            StockfishEngine.difficulty = gameMode.difficulty
        }
        if (gameMode is GameMode.VsAI && gameMode.playerColor == PieceColor.BLACK) {
            makeAiMove()
        }
    }

    fun onSquareClick(position: Position) {
        val selectedPiece = _uiState.value.selectedPiece
        val board = _uiState.value.board
        val pieceAtPosition = board.pieces[position.row][position.col]

        if(isGameOver(board)) {
            return
        }

        if (_uiState.value.gameMode is GameMode.VsAI) {
            val playerColor = (_uiState.value.gameMode as GameMode.VsAI).playerColor
            if (_uiState.value.turn != playerColor) {
                return // Not player's turn
            }
        }

        if (selectedPiece == null) {
            if (pieceAtPosition != null && pieceAtPosition.color == _uiState.value.turn) {
                _uiState.update { it.copy(selectedPiece = position) }
            }
        } else {
            if (board.isValidMove(selectedPiece, position)) {
                val newBoard = board.movePiece(selectedPiece, position)
                _uiState.update {
                    it.copy(
                        board = newBoard,
                        selectedPiece = null,
                        turn = if (it.turn == PieceColor.WHITE) PieceColor.BLACK else PieceColor.WHITE,
                        gameStatus = getGameStatus(newBoard)
                    )
                }
                if (_uiState.value.gameMode is GameMode.VsAI) {
                    if (!isGameOver(newBoard) && newBoard.promotionPosition == null) {
                        viewModelScope.launch {
                            delay(500)
                            makeAiMove()
                        }
                    }
                }
            } else {
                _uiState.update { it.copy(selectedPiece = null) }
            }
        }
    }

    fun onPromote(pieceType: PieceType) {
        val board = _uiState.value.board
        val promotionPosition = board.promotionPosition ?: return
        val newBoard = board.promotePawn(promotionPosition, pieceType)
        _uiState.update {
            it.copy(
                board = newBoard,
                turn = if (it.turn == PieceColor.WHITE) PieceColor.BLACK else PieceColor.WHITE,
                gameStatus = getGameStatus(newBoard)
            )
        }
        if (_uiState.value.gameMode is GameMode.VsAI) {
            if (!isGameOver(newBoard)) {
                makeAiMove()
            }
        }
    }

    private fun makeAiMove() {
        viewModelScope.launch {
            val board = _uiState.value.board
            val bestMove= chessApi.getBestMove(board)

            val newBoard = board.movePiece(bestMove.start, bestMove.end, bestMove.promotedTo)
            _uiState.update {
                it.copy(
                    board = newBoard,
                    turn = if (it.turn == PieceColor.WHITE) PieceColor.BLACK else PieceColor.WHITE,
                    gameStatus = getGameStatus(newBoard)
                )
            }
        }
    }

    fun isGameOver(board: Board): Boolean {
        return board.isCheckmate(PieceColor.WHITE) || board.isCheckmate(PieceColor.BLACK)
    }

    private fun getGameStatus(board: Board): String? {
        val turn = if (_uiState.value.turn == PieceColor.WHITE) PieceColor.BLACK else PieceColor.WHITE
        return when {
            board.isCheckmate(turn) -> "Checkmate! ${if (turn == PieceColor.WHITE) "Black" else "White"} wins."
            board.isKingInCheck(turn) -> "Check!"
            else -> null
        }
    }
}