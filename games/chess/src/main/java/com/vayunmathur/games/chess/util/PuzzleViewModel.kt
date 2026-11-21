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
import com.vayunmathur.games.chess.data.toFen
import com.vayunmathur.games.chess.data.toUci
import com.vayunmathur.library.util.DataStoreUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/** Difficulty bands, mapped to Lichess rating ranges within the bundled set. */
private val EASY_RANGE = 400..1199
private val MEDIUM_RANGE = 1200..1799
private val HARD_RANGE = 1800..2799

/** Kinds of puzzle difficulty. */
enum class PuzzleDifficulty(val range: IntRange) {
    EASY(EASY_RANGE),
    MEDIUM(MEDIUM_RANGE),
    HARD(HARD_RANGE);
}

/** Kinds of puzzle status. */
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

/** Immutable value describing puzzle ui state. */
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

/** A puzzle left mid-solve, rebuilt by replaying its solution up to [solutionIndex]. */
data class SavedPuzzleGame(
    val puzzle: Puzzle,
    val board: Board,
    val solutionIndex: Int,
    val playerColor: PieceColor,
    val difficulty: PuzzleDifficulty,
)

/**
 * FEN + solution-list codec for a puzzle in progress. The puzzle's starting FEN and
 * its UCI solution are stored; the current board is replayed from them and dropped
 * unless the replay is legal and the index is in range.
 */
object PuzzleProgressCodec {
    private const val VERSION = 1

    fun encode(state: PuzzleUiState): String {
        val puzzle = requireNotNull(state.puzzle)
        return buildJsonObject {
            put("v", VERSION)
            put("fen", puzzle.board.toFen())
            put("fenNow", state.board.toFen())
            put("rating", puzzle.rating)
            put("difficulty", state.difficulty.name)
            put("index", state.solutionIndex)
            put(
                "solution",
                buildJsonArray { for (move in puzzle.solution) add(move.toUci()) },
            )
        }.toString()
    }

    fun decode(json: String): SavedPuzzleGame? = runCatching {
        val root = Json.parseToJsonElement(json).jsonObject
        if (root["v"]?.jsonPrimitive?.int != VERSION) return null
        val startBoard = Board.fromFen(root.getValue("fen").jsonPrimitive.content)
        val solution = root.getValue("solution").jsonArray.map { element ->
            val (from, to, promotion) =
                com.vayunmathur.games.chess.data.parseUciMove(element.jsonPrimitive.content)
                    ?: return null
            PuzzleMove(from, to, promotion)
        }
        if (solution.isEmpty()) return null
        val index = root.getValue("index").jsonPrimitive.int
        if (index !in 1 until solution.size) return null
        var board = startBoard
        for (move in solution.subList(0, index)) {
            if (!board.isValidMove(move.from, move.to)) return null
            board = board.movePiece(move.from, move.to, move.promotion)
        }
        if (board.toFen() != root.getValue("fenNow").jsonPrimitive.content) return null
        if (board.promotionPosition != null) return null
        val puzzle = Puzzle(
            rating = root.getValue("rating").jsonPrimitive.int,
            board = startBoard,
            solution = solution,
        )
        val opponent = startBoard.pieces[solution[0].from.row][solution[0].from.col]?.color
            ?: return null
        SavedPuzzleGame(
            puzzle = puzzle,
            board = board,
            solutionIndex = index,
            playerColor = opponent.opposite,
            difficulty = PuzzleDifficulty.valueOf(
                root.getValue("difficulty").jsonPrimitive.content,
            ),
        )
    }.getOrNull()
}

/**
 * Drives the Lichess puzzle solve flow. A puzzle's stored FEN is the position
 * *before* the opponent's setup move; `solution[0]` is auto-played by the
 * opponent, then the player must find `solution[1]`, the opponent replies with
 * `solution[2]`, and so on. Correctness is validated purely against the stored
 * move list — no engine is needed.
 */
class PuzzleViewModel(application: Application) : AndroidViewModel(application), PuzzleActions {

    private val _uiState = MutableStateFlow(PuzzleUiState())
    val uiState: StateFlow<PuzzleUiState> = _uiState.asStateFlow()

    // Delay before the opponent's auto-played moves, so they read as a response.
    private val setupDelayMs = 500L
    private val replyDelayMs = 400L

    /** Shared constants and helpers for PuzzleViewModel. */
    companion object {
        private const val REPLAY_STEP_DELAY_MS = 600L
        private const val SAVE_DEBOUNCE_MS = 300L
        internal const val IN_PROGRESS_KEY = "chess_puzzle_in_progress"
    }

    private val store = DataStoreUtils.getInstance(application)
    private var saveJob: Job? = null
    private var restoreAttempted = false

    init {
        viewModelScope.launch { _uiState.collect { scheduleSaveInProgress() } }
    }

    private fun scheduleSaveInProgress() {
        saveJob?.cancel()
        saveJob = viewModelScope.launch {
            delay(SAVE_DEBOUNCE_MS)
            writeInProgress()
        }
    }

    /** Flushes the in-progress puzzle now (on pause); a finished puzzle clears its slot. */
    fun saveInProgress() {
        saveJob?.cancel()
        viewModelScope.launch { writeInProgress() }
    }

    private suspend fun writeInProgress() {
        val state = _uiState.value
        when {
            state.puzzle == null || state.status != PuzzleStatus.Solving ||
                state.board.promotionPosition != null ->
                store.removeKeys(listOf(IN_PROGRESS_KEY))
            else -> store.setString(IN_PROGRESS_KEY, PuzzleProgressCodec.encode(state))
        }
    }

    /** Entry for the Puzzles route: restores a mid-solve puzzle, or loads a fresh one. */
    fun ensurePuzzle() {
        if (_uiState.value.puzzle != null) return
        if (restoreAttempted) {
            loadRandom(_uiState.value.difficulty)
            return
        }
        restoreAttempted = true
        viewModelScope.launch {
            val saved = store.getStringAwait(IN_PROGRESS_KEY)
                ?.let { PuzzleProgressCodec.decode(it) }
            if (_uiState.value.puzzle != null) return@launch
            if (saved == null) {
                loadRandom(_uiState.value.difficulty)
            } else {
                _uiState.value = PuzzleUiState(
                    puzzle = saved.puzzle,
                    board = saved.board,
                    isBoardFlipped = saved.playerColor == PieceColor.BLACK,
                    playerColor = saved.playerColor,
                    rating = saved.puzzle.rating,
                    solutionIndex = saved.solutionIndex,
                    status = PuzzleStatus.Solving,
                    difficulty = saved.difficulty,
                )
            }
        }
    }

    // The board before the pawn stepped onto the last rank, while its promotion is pending.
    private var promotionUndo: Board? = null

    /** Loads a fresh random puzzle from the given [difficulty] band. */
    override fun loadRandom(difficulty: PuzzleDifficulty) {
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
    override fun retry() {
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

    override fun onSquareClick(position: Position) {
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
            promotionUndo = board
            _uiState.update { it.copy(board = newBoard, selectedPiece = null) }
            return
        }

        val newBoard = board.movePiece(from, to, expected.promotion)
        advanceAfterPlayerMove(newBoard)
    }

    override fun onSquareDrop(from: Position, to: Position) {
        if (from == to) {
            onSquareClick(from)
            return
        }
        // Same two-taps-in-one-gesture arrangement as the game board: the press selects,
        // the release taps the target, so a legal drop reaches attemptPlayerMove exactly as
        // two taps would (correct answer, or Failed on a legal-but-wrong move). Anything
        // else lands as a tap on [from].
        if (_uiState.value.selectedPiece != from) onSquareClick(from)
        if (_uiState.value.selectedPiece == from) onSquareClick(to)
    }

    override fun onPromote(pieceType: PieceType) {
        val state = _uiState.value
        val puzzle = state.puzzle ?: return
        val promoPos = state.board.promotionPosition ?: return
        val expected = puzzle.solution.getOrNull(state.solutionIndex) ?: return

        promotionUndo = null
        val newBoard = state.board.promotePawn(promoPos, pieceType)
        if (pieceType != expected.promotion) {
            _uiState.update { it.copy(board = newBoard, status = PuzzleStatus.Failed) }
            return
        }
        advanceAfterPlayerMove(newBoard)
    }

    /**
     * The promotion picker was dismissed: put the pawn back. Nothing was judged yet (only the
     * chosen piece decides right or wrong), so the puzzle simply continues from before the step.
     */
    override fun onCancelPromotion() {
        val undo = promotionUndo ?: return
        if (_uiState.value.board.promotionPosition == null) return
        promotionUndo = null
        _uiState.update { it.copy(board = undo, selectedPiece = null) }
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
    override fun showSolution() {
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
                delay(REPLAY_STEP_DELAY_MS)
                if (_uiState.value.puzzle !== puzzle) return@launch
                board = board.movePiece(move.from, move.to, move.promotion)
                _uiState.update { it.copy(board = board, solutionIndex = i + 1) }
            }
            _uiState.update { it.copy(status = PuzzleStatus.Solved) }
        }
    }
}
