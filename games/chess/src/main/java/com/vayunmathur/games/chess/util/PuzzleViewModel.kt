package com.vayunmathur.games.chess.util

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.vayunmathur.games.chess.data.Board
import com.vayunmathur.games.chess.data.PieceColor
import com.vayunmathur.games.chess.data.PieceType
import com.vayunmathur.games.chess.data.Position
import com.vayunmathur.games.chess.data.Puzzle
import com.vayunmathur.games.chess.data.PuzzleMove
import com.vayunmathur.games.chess.data.PuzzleRepository
import com.vayunmathur.games.chess.data.opposite
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Difficulty bands, mapped to Lichess rating ranges within the bundled set. */
enum class PuzzleDifficulty(val range: IntRange) {
    EASY(400..1199),
    MEDIUM(1200..1799),
    HARD(1800..2799)
}

enum class PuzzleStatus {
    /** Loading a puzzle / auto-playing the opponent's setup move. */
    Loading,
    /** The player's turn — waiting for the next solution move. */
    Solving,
    /** The full solution has been played. */
    Solved,
    /** The player played a legal but incorrect move. */
    Failed,
    /** The solution is being replayed for the player to watch. */
    ShowingSolution
}

data class PuzzleUiState(
    val puzzle: Puzzle? = null,
    val board: Board = Board.initialState,
    val selectedPiece: Position? = null,
    val isBoardFlipped: Boolean = false,
    val playerColor: PieceColor = PieceColor.WHITE,
    val rating: Int = 0,
    // Index of the next solution move to be played (by either side).
    val solutionIndex: Int = 0,
    val status: PuzzleStatus = PuzzleStatus.Loading,
    val difficulty: PuzzleDifficulty = PuzzleDifficulty.EASY
)

/**
 * Drives the Lichess puzzle solve flow. A puzzle's stored FEN is the position
 * *before* the opponent's setup move; `solution[0]` is auto-played by the
 * opponent, then the player must find `solution[1]`, the opponent replies with
 * `solution[2]`, and so on. Correctness is validated purely against the stored
 * move list — no engine is needed.
 */
class PuzzleViewModel(application: Application) : AndroidViewModel(application) {

    private val _uiState = MutableStateFlow(PuzzleUiState())
    val uiState: StateFlow<PuzzleUiState> = _uiState.asStateFlow()

    // Delay before the opponent's auto-played moves, so they read as a response.
    private val setupDelayMs = 500L
    private val replyDelayMs = 400L

    /** Loads a fresh random puzzle from the given [difficulty] band. */
    fun loadRandom(difficulty: PuzzleDifficulty = _uiState.value.difficulty) {
        _uiState.value = PuzzleUiState(status = PuzzleStatus.Loading, difficulty = difficulty)
        viewModelScope.launch {
            val puzzle = withContext(Dispatchers.IO) {
                PuzzleRepository.ensureLoaded(getApplication())
                PuzzleRepository.random(difficulty.range)
            } ?: return@launch
            startPuzzle(puzzle, difficulty)
        }
    }

    /** Restarts the current puzzle from the beginning. */
    fun retry() {
        val puzzle = _uiState.value.puzzle ?: return
        val difficulty = _uiState.value.difficulty
        viewModelScope.launch { startPuzzle(puzzle, difficulty) }
    }

    private suspend fun startPuzzle(puzzle: Puzzle, difficulty: PuzzleDifficulty) {
        val setup = puzzle.solution.firstOrNull() ?: return
        // The side that plays the setup move (the board's side to move) is the
        // opponent; the player is the other color.
        val opponentColor = puzzle.board.pieces[setup.from.row][setup.from.col]?.color
            ?: PieceColor.WHITE
        val playerColor = opponentColor.opposite

        _uiState.value = PuzzleUiState(
            puzzle = puzzle,
            board = puzzle.board,
            playerColor = playerColor,
            isBoardFlipped = playerColor == PieceColor.BLACK,
            rating = puzzle.rating,
            solutionIndex = 0,
            status = PuzzleStatus.Loading,
            difficulty = difficulty
        )

        delay(setupDelayMs)
        if (_uiState.value.puzzle !== puzzle) return // a newer puzzle superseded this one
        val afterSetup = puzzle.board.movePiece(setup.from, setup.to, setup.promotion)
        _uiState.update {
            it.copy(
                board = afterSetup,
                solutionIndex = 1,
                status = if (1 >= puzzle.solution.size) PuzzleStatus.Solved else PuzzleStatus.Solving
            )
        }
    }

    fun onSquareClick(position: Position) {
        val state = _uiState.value
        state.puzzle ?: return
        if (state.status != PuzzleStatus.Solving) return

        val board = state.board
        val pieceAt = board.pieces[position.row][position.col]
        val selected = state.selectedPiece

        if (selected == null) {
            if (pieceAt != null && pieceAt.color == state.playerColor) {
                _uiState.update { it.copy(selectedPiece = position) }
            }
            return
        }

        if (position == selected) {
            _uiState.update { it.copy(selectedPiece = null) }
            return
        }
        // Clicking another of the player's own pieces reselects it.
        if (pieceAt != null && pieceAt.color == state.playerColor) {
            _uiState.update { it.copy(selectedPiece = position) }
            return
        }

        attemptPlayerMove(selected, position)
    }

    private fun attemptPlayerMove(from: Position, to: Position) {
        val state = _uiState.value
        val puzzle = state.puzzle ?: return
        val board = state.board
        val expected = puzzle.solution.getOrNull(state.solutionIndex) ?: return
        val movingPiece = board.pieces[from.row][from.col]

        // An illegal click just deselects — only a legal-but-wrong move fails.
        if (movingPiece == null || !board.isValidMove(from, to)) {
            _uiState.update { it.copy(selectedPiece = null) }
            return
        }

        if (from != expected.from || to != expected.to) {
            _uiState.update { it.copy(selectedPiece = null, status = PuzzleStatus.Failed) }
            return
        }

        val isPromotion = movingPiece.type == PieceType.PAWN &&
            ((movingPiece.color == PieceColor.WHITE && to.row == 0) ||
                (movingPiece.color == PieceColor.BLACK && to.row == 7))

        if (isPromotion && expected.promotion != null) {
            // Apply the move so the board exposes a promotionPosition; the UI shows
            // the promotion dialog and onPromote finalizes it.
            val newBoard = board.movePiece(from, to)
            _uiState.update { it.copy(board = newBoard, selectedPiece = null) }
            return
        }

        val newBoard = board.movePiece(from, to, expected.promotion)
        advanceAfterPlayerMove(newBoard)
    }

    fun onPromote(pieceType: PieceType) {
        val state = _uiState.value
        val puzzle = state.puzzle ?: return
        val promoPos = state.board.promotionPosition ?: return
        val expected = puzzle.solution.getOrNull(state.solutionIndex) ?: return

        val newBoard = state.board.promotePawn(promoPos, pieceType)
        if (pieceType != expected.promotion) {
            _uiState.update { it.copy(board = newBoard, status = PuzzleStatus.Failed) }
            return
        }
        advanceAfterPlayerMove(newBoard)
    }

    /** After a correct player move, advance the index and auto-play any reply. */
    private fun advanceAfterPlayerMove(newBoard: Board) {
        val puzzle = _uiState.value.puzzle ?: return
        val nextIndex = _uiState.value.solutionIndex + 1

        if (nextIndex >= puzzle.solution.size) {
            _uiState.update {
                it.copy(
                    board = newBoard,
                    selectedPiece = null,
                    solutionIndex = nextIndex,
                    status = PuzzleStatus.Solved
                )
            }
            return
        }

        _uiState.update {
            it.copy(
                board = newBoard,
                selectedPiece = null,
                solutionIndex = nextIndex,
                status = PuzzleStatus.Solving
            )
        }
        viewModelScope.launch {
            delay(replyDelayMs)
            val cur = _uiState.value
            if (cur.puzzle !== puzzle || cur.solutionIndex != nextIndex) return@launch
            val reply = puzzle.solution[nextIndex]
            val replyBoard = cur.board.movePiece(reply.from, reply.to, reply.promotion)
            val afterReply = nextIndex + 1
            _uiState.update {
                it.copy(
                    board = replyBoard,
                    solutionIndex = afterReply,
                    status = if (afterReply >= puzzle.solution.size) PuzzleStatus.Solved
                    else PuzzleStatus.Solving
                )
            }
        }
    }

    /** Replays the full solution from the start for the player to watch. */
    fun showSolution() {
        val puzzle = _uiState.value.puzzle ?: return
        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    board = puzzle.board,
                    selectedPiece = null,
                    solutionIndex = 0,
                    status = PuzzleStatus.ShowingSolution
                )
            }
            var board = puzzle.board
            for ((i, move) in puzzle.solution.withIndex()) {
                delay(replyDelayMs + 200L)
                if (_uiState.value.puzzle !== puzzle) return@launch
                board = board.movePiece(move.from, move.to, move.promotion)
                _uiState.update { it.copy(board = board, solutionIndex = i + 1) }
            }
            _uiState.update { it.copy(status = PuzzleStatus.Solved) }
        }
    }
}
