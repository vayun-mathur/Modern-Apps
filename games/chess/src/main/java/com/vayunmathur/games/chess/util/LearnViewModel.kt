package com.vayunmathur.games.chess.util

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.vayunmathur.games.chess.data.Board
import com.vayunmathur.games.chess.data.LearnLevel
import com.vayunmathur.games.chess.data.LearnRepository
import com.vayunmathur.games.chess.data.LearnShape
import com.vayunmathur.games.chess.data.LearnStage
import com.vayunmathur.games.chess.data.PieceColor
import com.vayunmathur.games.chess.data.PieceType
import com.vayunmathur.games.chess.data.Position
import com.vayunmathur.games.chess.data.opposite
import com.vayunmathur.games.chess.data.parseUci
import com.vayunmathur.games.chess.data.square
import com.vayunmathur.library.util.DataStoreUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class LearnStatus { Playing, Completed, Failed }

data class LearnUiState(
    val stage: LearnStage? = null,
    val levelIndex: Int = 0,
    val level: LearnLevel? = null,
    val board: Board = Board.initialState,
    val selectedPiece: Position? = null,
    val isFlipped: Boolean = false,
    val playerColor: PieceColor = PieceColor.WHITE,
    val apples: Set<Position> = emptySet(),
    val shapes: List<LearnShape> = emptyList(),
    val status: LearnStatus = LearnStatus.Playing,
    val nbMoves: Int = 0,
    val starsEarned: Int = 0,
    val stageScore: Int = 0,
    // Best stars per level for the loaded stage (levelIndex -> stars), for the UI.
    val stageStars: List<Int> = emptyList(),
    val stageFinished: Boolean = false
)

/**
 * Drives the Lichess-style "Learn" lessons. Each stage is a list of levels with a
 * goal type (collect stars, capture all, give check/mate, castle, escape check,
 * or follow a scripted scenario). Correctness is validated against the bundled
 * lesson data using the app's own Board rules engine — no external engine needed.
 */
class LearnViewModel(application: Application) : AndroidViewModel(application) {

    private val _uiState = MutableStateFlow(LearnUiState())
    val uiState: StateFlow<LearnUiState> = _uiState.asStateFlow()

    private var categoryKey: String = ""
    private var sideToMove: PieceColor = PieceColor.WHITE
    private var scenarioIndex: Int = 0
    // Guards async opponent-scenario callbacks against level changes.
    private var token: Int = 0

    private val opponentDelayMs = 700L

    // The board before the pawn stepped onto the last rank, while its promotion is pending.
    private var promotionUndo: Board? = null

    // Lichess-style scoring (see LearnRules.kt): apple/capture/scenario points,
    // completion bonus by move count, and piece values for value levels.
    private val applePoints = SCORE_APPLE
    private val capturePoints = SCORE_CAPTURE
    private val scenarioPoints = SCORE_SCENARIO
    private var stageScore = 0

    fun loadStage(categoryKey: String, stageKey: String) {
        this.categoryKey = categoryKey
        stageScore = 0
        viewModelScope.launch {
            val stage = withContext(Dispatchers.IO) {
                LearnRepository.ensureLoaded(getApplication())
                LearnRepository.stage(categoryKey, stageKey)
            } ?: return@launch
            // Resume at the first level that hasn't been solved yet.
            val firstIncomplete = loadStageStars(stage).indexOfFirst { it == 0 }
            startLevel(stage, if (firstIncomplete >= 0) firstIncomplete else 0)
        }
    }

    fun retryLevel() {
        val s = _uiState.value
        val stage = s.stage ?: return
        startLevel(stage, s.levelIndex)
    }

    /** Jump directly to a level within the current stage (via the level stepper). */
    fun goToLevel(index: Int) {
        val s = _uiState.value
        val stage = s.stage ?: return
        if (index in stage.levels.indices) startLevel(stage, index)
    }

    fun nextLevel() {
        val s = _uiState.value
        val stage = s.stage ?: return
        if (s.levelIndex + 1 >= stage.levels.size) {
            _uiState.update { it.copy(stageFinished = true) }
        } else {
            startLevel(stage, s.levelIndex + 1)
        }
    }

    private fun startLevel(stage: LearnStage, index: Int) {
        token++
        val level = stage.levels[index]
        val board = LearnRepository.buildBoard(level)
        sideToMove = fenSide(level.fen)
        scenarioIndex = 0

        _uiState.value = LearnUiState(
            stage = stage,
            levelIndex = index,
            level = level,
            board = board,
            playerColor = level.playerColor,
            isFlipped = level.playerColor == PieceColor.BLACK,
            apples = level.appleSquares.toSet(),
            shapes = level.shapes,
            status = LearnStatus.Playing,
            stageScore = stageScore,
            stageStars = loadStageStars(stage)
        )

        // Scenario levels where the opponent moves first (e.g. en passant setup).
        if (level.goalType == "scenario" && sideToMove != level.playerColor) {
            val myToken = token
            viewModelScope.launch {
                delay(opponentDelayMs)
                if (token == myToken) playOpponentScenario()
            }
        }
    }

    fun onSquareClick(position: Position) {
        val state = _uiState.value
        val level = state.level ?: return
        if (state.status != LearnStatus.Playing) return
        // During a scenario it's not the player's turn until the opponent has replied.
        if (level.goalType == "scenario" && sideToMove != state.playerColor) return

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
        if (pieceAt != null && pieceAt.color == state.playerColor) {
            _uiState.update { it.copy(selectedPiece = position) }
            return
        }
        attemptMove(selected, position)
    }

    private fun attemptMove(from: Position, to: Position) {
        val state = _uiState.value
        val level = state.level ?: return
        val board = state.board

        if (level.goalType == "scenario") {
            handleScenarioMove(from, to)
            return
        }

        // Kingless boards (and "offer illegal move" levels) use pseudo-legal moves,
        // matching Lichess's antichess-style lesson boards.
        val pseudo = level.offerIllegalMove || board.kingCount() < 2
        val allowed = if (pseudo) board.isPseudoLegalMove(from, to) else board.isValidMove(from, to)
        if (!allowed) {
            _uiState.update { it.copy(selectedPiece = null) }
            return
        }

        val moving = board.pieces[from.row][from.col] ?: return

        // Pawn reaching the last rank: let the player pick the promotion piece
        // (some lessons need underpromotion). The dialog then calls onPromote.
        if (moving.type == PieceType.PAWN && isLastRank(to)) {
            val promoBoard = board.movePiece(from, to)
            promotionUndo = board
            _uiState.update { it.copy(board = promoBoard, selectedPiece = null) }
            return
        }

        val newBoard = board.movePiece(from, to)
        val newMoves = state.nbMoves + 1
        val newApples = state.apples - to
        addMovePoints(level, state.apples, to, board, state.playerColor)
        finishPlayerMove(level, newBoard, newApples, newMoves, to, state.playerColor)
    }

    /** Finalizes a pending pawn promotion chosen by the player, then evaluates the move. */
    fun onPromote(pieceType: PieceType) {
        val state = _uiState.value
        val level = state.level ?: return
        val promoPos = state.board.promotionPosition ?: return
        promotionUndo = null
        val newBoard = state.board.promotePawn(promoPos, pieceType)
        val newMoves = state.nbMoves + 1
        val newApples = state.apples - promoPos
        addMovePoints(level, state.apples, promoPos, state.board, state.playerColor)
        finishPlayerMove(level, newBoard, newApples, newMoves, promoPos, state.playerColor)
    }

    /**
     * The promotion picker was dismissed: put the pawn back. The step was never counted (moves,
     * apples and points are only scored in [onPromote]), so the level continues as before it.
     */
    fun onCancelPromotion() {
        val undo = promotionUndo ?: return
        if (_uiState.value.board.promotionPosition == null) return
        promotionUndo = null
        _uiState.update { it.copy(board = undo, selectedPiece = null) }
    }

    /**
     * Drag-and-drop: the press started on [from] and lifted on [to]. Same
     * two-taps-in-one-gesture arrangement as the game board — the press selects, the
     * release taps the target, so a legal drop reaches attemptMove exactly as two taps
     * would. Anything else lands as a tap on [from].
     */
    fun onSquareDrop(from: Position, to: Position) {
        if (from == to) {
            onSquareClick(from)
            return
        }
        if (_uiState.value.selectedPiece != from) onSquareClick(from)
        if (_uiState.value.selectedPiece == from) onSquareClick(to)
    }

    private fun addMovePoints(
        level: LearnLevel,
        apples: Set<Position>,
        to: Position,
        preMove: Board,
        player: PieceColor,
    ) {
        if (to in apples) {
            stageScore += applePoints
        } else if (level.pointsForCapture) {
            val captured = preMove.pieces[to.row][to.col]
            if (captured != null && captured.color != player) {
                stageScore += if (level.showPieceValues) pieceValue(captured.type) else capturePoints
            }
        }
    }

    private fun finishPlayerMove(
        level: LearnLevel,
        newBoard: Board,
        apples: Set<Position>,
        moves: Int,
        to: Position,
        player: PieceColor,
    ) {
        // A move that hangs a piece (per detectCapture) fails — and the opponent is
        // shown grabbing the hanging piece, like Lichess.
        val capture = detectCaptureMove(level, newBoard, player)
        if (capture != null) {
            punishAndFail(newBoard, apples, moves, capture)
            return
        }

        val failed = isFailingMove(level, newBoard, player, moves)
        val outcome = when {
            failed -> LearnStatus.Failed
            isSuccess(level, newBoard, apples, player) -> LearnStatus.Completed
            else -> LearnStatus.Playing
        }

        // On multi-move levels keep the moved piece selected so it's quick to move again.
        applyOutcome(newBoard, apples, moves, outcome, keepSelected = to)
    }

    private fun handleScenarioMove(from: Position, to: Position) {
        val state = _uiState.value
        val level = state.level ?: return
        val board = state.board
        val expected = level.scenario.getOrNull(scenarioIndex) ?: return
        val (efrom, eto, epromo) = parseUci(expected)

        if (from != efrom || to != eto) {
            // A legal-but-wrong choice fails the scenario; an impossible click is ignored.
            if (board.isPseudoLegalMove(from, to)) {
                _uiState.update { it.copy(selectedPiece = null, status = LearnStatus.Failed) }
            } else {
                _uiState.update { it.copy(selectedPiece = null) }
            }
            return
        }

        val moving = board.pieces[from.row][from.col] ?: return
        val promo = epromo ?: promotionFor(moving, to)
        val newBoard = board.movePiece(from, to, promo)
        scenarioIndex++
        sideToMove = sideToMove.opposite
        stageScore += scenarioPoints
        val newMoves = state.nbMoves + 1

        if (scenarioIndex >= level.scenario.size) {
            applyOutcome(newBoard, state.apples, newMoves, LearnStatus.Completed)
            return
        }
        _uiState.update {
            it.copy(
                board = newBoard, selectedPiece = null, shapes = emptyList(),
                nbMoves = newMoves, stageScore = stageScore
            )
        }
        val myToken = token
        viewModelScope.launch {
            delay(opponentDelayMs)
            if (token == myToken) playOpponentScenario()
        }
    }

    private fun playOpponentScenario() {
        val state = _uiState.value
        val level = state.level ?: return
        val move = level.scenario.getOrNull(scenarioIndex) ?: return
        val (from, to, promo) = parseUci(move)
        val newBoard = state.board.movePiece(from, to, promo)
        scenarioIndex++
        sideToMove = sideToMove.opposite
        val done = scenarioIndex >= level.scenario.size
        if (done) {
            applyOutcome(newBoard, state.apples, state.nbMoves, LearnStatus.Completed)
        } else {
            _uiState.update { it.copy(board = newBoard, shapes = emptyList(), stageScore = stageScore) }
        }
    }

    private fun applyOutcome(
        board: Board,
        apples: Set<Position>,
        moves: Int,
        outcome: LearnStatus,
        keepSelected: Position? = null
    ) {
        var stars = 0
        var stageStars = _uiState.value.stageStars
        if (outcome == LearnStatus.Completed) {
            val level = _uiState.value.level
            stars = if (level != null) starsFor(level, moves) else MAX_STARS
            stageScore += levelBonus(stars)
            stageStars = persistStars(_uiState.value.stage, _uiState.value.levelIndex, stars, stageStars)
        }
        _uiState.update {
            it.copy(
                board = board,
                selectedPiece = if (outcome == LearnStatus.Playing) keepSelected else null,
                apples = apples,
                nbMoves = moves,
                shapes = if (outcome == LearnStatus.Playing) it.shapes else emptyList(),
                status = outcome,
                starsEarned = if (outcome == LearnStatus.Completed) stars else it.starsEarned,
                stageScore = stageScore,
                stageStars = stageStars
            )
        }
    }

    /** Shows the player's move, then the opponent capturing the hanging piece, then fails. */
    private fun punishAndFail(board: Board, apples: Set<Position>, moves: Int, capture: Pair<Position, Position>) {
        _uiState.update {
            it.copy(
                board = board, selectedPiece = null, apples = apples,
                nbMoves = moves, shapes = emptyList(), stageScore = stageScore
            )
        }
        val myToken = token
        viewModelScope.launch {
            delay(opponentDelayMs)
            if (token != myToken) return@launch
            val punished = board.movePiece(capture.first, capture.second)
            _uiState.update { it.copy(board = punished, status = LearnStatus.Failed) }
        }
    }

    // ---- Progress persistence ----

    private fun starKey(stageKey: String, index: Int) = "learn_${stageKey}_${index}_stars"

    private fun loadStageStars(stage: LearnStage): List<Int> {
        val ds = DataStoreUtils.getInstance(getApplication())
        return stage.levels.indices.map { ds.getLong(starKey(stage.key, it))?.toInt() ?: 0 }
    }

    private fun persistStars(stage: LearnStage?, index: Int, stars: Int, current: List<Int>): List<Int> {
        if (stage == null) return current
        viewModelScope.launch(Dispatchers.IO) {
            DataStoreUtils.getInstance(getApplication()).setLongIfGreater(starKey(stage.key, index), stars.toLong())
        }
        return current.toMutableList().also {
            if (index in it.indices && stars > it[index]) it[index] = stars
        }
    }
}

/** Reads persisted Learn stars for the stage-list UI. */
object LearnProgress {
    private fun starKey(stageKey: String, index: Int) = "learn_${stageKey}_${index}_stars"

    fun stageStars(context: android.content.Context, stage: LearnStage): List<Int> {
        val ds = DataStoreUtils.getInstance(context)
        return stage.levels.indices.map { ds.getLong(starKey(stage.key, it))?.toInt() ?: 0 }
    }

    /** Number of levels in [stage] completed with at least one star. */
    fun completedCount(context: android.content.Context, stage: LearnStage): Int =
        stageStars(context, stage).count { it > 0 }
}
