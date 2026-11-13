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
    data class VsAI(val playerColor: PieceColor, val difficulty: Difficulty) : GameMode()
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

class ChessViewModel(application: Application) : AndroidViewModel(application), ChessActions {

    private val _uiState = MutableStateFlow(ChessUiState())
    val uiState: StateFlow<ChessUiState> = _uiState.asStateFlow()
    private val chessApi = ChessApi(application)

    /**
     * Whether the "play against the AI" option is offered.
     *
     * False on a device that cannot run the model — no `libmodelrunner.so` for its ABI, or no
     * Vulkan device with fp16 compute. Offering a mode that can never move would be worse than
     * not offering it, so the picker hides it. Mirrors `translationAvailable`.
     */
    private val _aiAvailable = MutableStateFlow(false)
    val aiAvailable: StateFlow<Boolean> = _aiAvailable.asStateFlow()

    // Guards one-time scoring of a finished game. The win-counting effect lives in composition and
    // re-runs whenever the Game screen re-enters composition (e.g. returning from the achievements
    // screen), which must not tick the counter again (#668). Reset on every new game.
    private var winScored = false

    // Incremented by every new game. An AI reply computed for an older game
    // (the engine call suspends) must never be applied to the current one.
    private var gameGeneration = 0

    // The state before a pawn stepped onto the last rank, while its promotion choice is pending.
    private var promotionUndo: ChessUiState? = null

    companion object {
        private const val AI_REPLY_DELAY_MS = 500L
    }

    init {
        viewModelScope.launch { _aiAvailable.value = chessApi.isAvailable() }
    }

    /** Returns true only the first time it is called for the current game; false thereafter. */
    fun claimWinScoring(): Boolean {
        if (winScored) return false
        winScored = true
        return true
    }

    fun onNewGame(gameMode: GameMode) {
        winScored = false
        promotionUndo = null
        gameGeneration++
        val isFlipped = gameMode is GameMode.VsAI && gameMode.playerColor == PieceColor.BLACK
        _uiState.value = ChessUiState(gameMode = gameMode, isBoardFlipped = isFlipped)
        if (gameMode is GameMode.VsAI && gameMode.playerColor == PieceColor.BLACK) {
            makeAiMove(gameGeneration)
        }
    }

    override fun onSquareClick(position: Position) {
        val state = _uiState.value
        if (state.gameResult != null) return
        val mode = state.gameMode
        if (mode is GameMode.VsAI && state.turn != mode.playerColor) return

        val pieceAtPosition = state.board.pieces[position.row][position.col]
        val selectedPiece = state.selectedPiece
        val ownPiece = pieceAtPosition != null && pieceAtPosition.color == state.turn
        when {
            // Selecting a piece, or tapping another of the player's own pieces to reselect it,
            // matching PuzzleViewModel and LearnViewModel.
            ownPiece && position != selectedPiece -> _uiState.update { it.copy(selectedPiece = position) }
            selectedPiece == null -> Unit
            state.board.isValidMove(selectedPiece, position) -> commitPlayerMove(state, selectedPiece, position)
            else -> _uiState.update { it.copy(selectedPiece = null) }
        }
    }

    private fun commitPlayerMove(state: ChessUiState, from: Position, to: Position) {
        val newBoard = state.board.movePiece(from, to)
        val promoting = newBoard.promotionPosition != null
        // Kept so a cancelled promotion can put the pawn back exactly as it was.
        promotionUndo = if (promoting) state else null
        val nextTurn = if (promoting) state.turn else state.turn.opposite
        // While a promotion choice is pending the move is not complete: the pawn sits
        // unpromoted on the last rank, so a check/checkmate verdict computed now can be wrong
        // for the piece the player is about to pick (and would fire the win achievements +
        // one-time win counter early). onPromote computes the real result once the piece is
        // chosen.
        val (result, status) = if (promoting) null to null else statusFor(newBoard, nextTurn)
        _uiState.update {
            it.copy(
                board = newBoard,
                selectedPiece = null,
                turn = nextTurn,
                gameStatus = status,
                gameResult = result
            )
        }
        if (state.gameMode is GameMode.VsAI && result == null && !promoting) scheduleAiReply()
    }

    private fun scheduleAiReply() {
        val generation = gameGeneration
        viewModelScope.launch {
            delay(AI_REPLY_DELAY_MS)
            if (generation == gameGeneration) makeAiMove(generation)
        }
    }

    override fun onSquareDrop(from: Position, to: Position) {
        if (from == to) {
            onSquareClick(from)
            return
        }
        // A drop is the tap flow in one gesture: the press selects the piece (unless it
        // already is), and the release taps the target square. The tap path owns every
        // guard and the AI reply, so a legal drop commits exactly what two taps would and
        // anything else lands as a tap on [from]. Releasing over the start square is a
        // single tap, never select-then-deselect.
        if (_uiState.value.selectedPiece != from) onSquareClick(from)
        if (_uiState.value.selectedPiece == from) onSquareClick(to)
    }

    override fun onPromote(pieceType: PieceType) {
        val board = _uiState.value.board
        val promotionPosition = board.promotionPosition ?: return
        promotionUndo = null
        val newBoard = board.promotePawn(promotionPosition, pieceType)
        val nextTurn = _uiState.value.turn.opposite
        val (result, status) = statusFor(newBoard, nextTurn)
        _uiState.update {
            it.copy(board = newBoard, turn = nextTurn, gameStatus = status, gameResult = result)
        }
        if (_uiState.value.gameMode is GameMode.VsAI && result == null) scheduleAiReply()
    }

    /**
     * The promotion picker was dismissed: take the pawn's step back. The whole pre-move state
     * is restored (board, turn, status line), minus the selection, so the player can choose a
     * different move.
     */
    override fun onCancelPromotion() {
        val undo = promotionUndo ?: return
        if (_uiState.value.board.promotionPosition == null) return
        promotionUndo = null
        _uiState.value = undo.copy(selectedPiece = null)
    }

    private fun makeAiMove(generation: Int) {
        val mode = _uiState.value.gameMode as? GameMode.VsAI ?: return
        viewModelScope.launch {
            if (generation != gameGeneration) return@launch
            val state = _uiState.value
            // Only ever move for the AI side of the current, unfinished game.
            if (state.gameMode != mode || state.gameResult != null) return@launch
            if (state.turn == mode.playerColor) return@launch
            // The side to move comes from the UI's own state rather than from the board: the
            // rules model infers a turn from its last move, which the puzzle path fakes.
            // Returns null when the engine has no move to make (mate or stalemate against the
            // AI, or an unavailable model).
            val bestMove = chessApi.getBestMove(state.board, state.turn, mode.difficulty)
                ?: return@launch
            // The engine call suspends: a new game may have started meanwhile.
            // Never apply a move computed for a stale position.
            val current = _uiState.value
            if (generation != gameGeneration) return@launch
            if (current.board != state.board || current.turn != state.turn) return@launch
            if (current.gameResult != null) return@launch
            val newBoard = state.board.movePiece(bestMove.start, bestMove.end, bestMove.promotedTo)
            val nextTurn = state.turn.opposite
            val (result, status) = statusFor(newBoard, nextTurn)
            _uiState.update {
                it.copy(board = newBoard, turn = nextTurn, gameStatus = status, gameResult = result)
            }
        }
    }

    override fun onCleared() {
        chessApi.close()
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
