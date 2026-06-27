package com.vayunmathur.games.chess.util
import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.vayunmathur.games.chess.R
import com.vayunmathur.games.chess.data.Board
import com.vayunmathur.games.chess.data.PieceColor
import com.vayunmathur.games.chess.data.PieceType
import com.vayunmathur.games.chess.data.Position
import com.vayunmathur.games.chess.data.opposite
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

sealed class GameMode {
    data object TwoPlayer : GameMode()
    data class VsAI(val playerColor: PieceColor, val difficulty: StockfishEngine.Difficulty) : GameMode()
}

/** Terminal outcome of a game, or null while it is still in progress. */
sealed class GameResult {
    data class Checkmate(val winner: PieceColor) : GameResult()
    data object Stalemate : GameResult()
    data object InsufficientMaterial : GameResult()

    val isGameOver: Boolean get() = true
}

data class ChessUiState(
    val board: Board = Board.initialState,
    val selectedPiece: Position? = null,
    val gameMode: GameMode = GameMode.TwoPlayer,
    val turn: PieceColor = PieceColor.WHITE,
    val gameStatus: String? = null,
    val gameResult: GameResult? = null,
    val isBoardFlipped: Boolean = false
)

class ChessViewModel(application: Application) : AndroidViewModel(application) {

    private val _uiState = MutableStateFlow(ChessUiState())
    val uiState: StateFlow<ChessUiState> = _uiState.asStateFlow()
    private val chessApi = ChessApi()

    fun onNewGame(gameMode: GameMode) {
        val isFlipped = gameMode is GameMode.VsAI && gameMode.playerColor == PieceColor.BLACK
        _uiState.value = ChessUiState(gameMode = gameMode, isBoardFlipped = isFlipped)
        if (gameMode is GameMode.VsAI) {
            StockfishEngine.difficulty = gameMode.difficulty
            if (gameMode.playerColor == PieceColor.BLACK) makeAiMove()
        }
    }

    fun onSquareClick(position: Position) {
        val state = _uiState.value
        val board = state.board
        val pieceAtPosition = board.pieces[position.row][position.col]

        if (state.gameResult != null) return

        val mode = state.gameMode
        if (mode is GameMode.VsAI && state.turn != mode.playerColor) return

        val selectedPiece = state.selectedPiece
        if (selectedPiece == null) {
            if (pieceAtPosition != null && pieceAtPosition.color == state.turn) {
                _uiState.update { it.copy(selectedPiece = position) }
            }
        } else {
            if (board.isValidMove(selectedPiece, position)) {
                val newBoard = board.movePiece(selectedPiece, position)
                val nextTurn = if (newBoard.promotionPosition != null) state.turn else state.turn.opposite
                val (result, status) = statusFor(newBoard, nextTurn)
                _uiState.update {
                    it.copy(
                        board = newBoard,
                        selectedPiece = null,
                        turn = nextTurn,
                        gameStatus = status,
                        gameResult = result
                    )
                }
                if (mode is GameMode.VsAI && result == null && newBoard.promotionPosition == null) {
                    viewModelScope.launch {
                        delay(500)
                        makeAiMove()
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
        val nextTurn = _uiState.value.turn.opposite
        val (result, status) = statusFor(newBoard, nextTurn)
        _uiState.update {
            it.copy(board = newBoard, turn = nextTurn, gameStatus = status, gameResult = result)
        }
        if (_uiState.value.gameMode is GameMode.VsAI && result == null) {
            makeAiMove()
        }
    }

    private fun makeAiMove() {
        viewModelScope.launch {
            val board = _uiState.value.board
            // Returns null when the engine has no move to make (mate/stalemate against the AI);
            // without this guard the "bestmove (none)" reply would be parsed as a coordinate and crash.
            val bestMove = chessApi.getBestMove(board) ?: return@launch
            val newBoard = board.movePiece(bestMove.start, bestMove.end, bestMove.promotedTo)
            val nextTurn = _uiState.value.turn.opposite
            val (result, status) = statusFor(newBoard, nextTurn)
            _uiState.update {
                it.copy(board = newBoard, turn = nextTurn, gameStatus = status, gameResult = result)
            }
        }
    }

    /** Terminal result for [board] when it is [turn]'s move, or null if play continues. */
    private fun resultFor(board: Board, turn: PieceColor): GameResult? = when {
        board.isCheckmate(turn) -> GameResult.Checkmate(turn.opposite)
        board.isInsufficientMaterial() -> GameResult.InsufficientMaterial
        board.isStalemate(turn) -> GameResult.Stalemate
        else -> null
    }

    /** Computes the result plus the localized status line (terminal result, or "check", or none). */
    private fun statusFor(board: Board, turn: PieceColor): Pair<GameResult?, String?> {
        val result = resultFor(board, turn)
        val ctx = getApplication<Application>()
        val text = when (result) {
            is GameResult.Checkmate ->
                if (result.winner == PieceColor.WHITE) ctx.getString(R.string.white_wins)
                else ctx.getString(R.string.black_wins)
            GameResult.Stalemate -> ctx.getString(R.string.stalemate)
            GameResult.InsufficientMaterial -> ctx.getString(R.string.draw_insufficient_material)
            null -> if (board.isKingInCheck(turn)) ctx.getString(R.string.check) else null
        }
        return result to text
    }
}
