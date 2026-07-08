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

    // Lichess-style scoring (ui/learn/src/score.ts): apple/capture/scenario = 50,
    // completion bonus 500/300/100 by move count, and piece values for value levels.
    private val applePoints = 50
    private val capturePoints = 50
    private val scenarioPoints = 50
    private var stageScore = 0

    fun loadStage(categoryKey: String, stageKey: String) {
        this.categoryKey = categoryKey
        stageScore = 0
        viewModelScope.launch {
            val stage = withContext(Dispatchers.IO) {
                LearnRepository.ensureLoaded(getApplication())
                LearnRepository.stage(categoryKey, stageKey)
            } ?: return@launch
            startLevel(stage, 0)
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
        val myToken = token
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
            viewModelScope.launch {
                delay(opponentDelayMs)
                if (token == myToken) playOpponentScenario(myToken)
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
        val promo = promotionFor(moving, to)
        val newBoard = board.movePiece(from, to, promo)
        val newMoves = state.nbMoves + 1
        val newApples = state.apples - to

        if (to in state.apples) {
            stageScore += applePoints
        } else if (level.pointsForCapture) {
            val captured = board.pieces[to.row][to.col]
            if (captured != null && captured.color != state.playerColor) {
                stageScore += if (level.showPieceValues) pieceValue(captured.type) else capturePoints
            }
        }

        val failed = isFailingMove(level, newBoard, state.playerColor)
        val outcome = when {
            failed -> LearnStatus.Failed
            isSuccess(level, newBoard, newApples, newMoves, state.playerColor) -> LearnStatus.Completed
            else -> LearnStatus.Playing
        }

        applyOutcome(newBoard, newApples, newMoves, outcome)
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
            if (token == myToken) playOpponentScenario(myToken)
        }
    }

    private fun playOpponentScenario(myToken: Int) {
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

    private fun applyOutcome(board: Board, apples: Set<Position>, moves: Int, outcome: LearnStatus) {
        var stars = 0
        var stageStars = _uiState.value.stageStars
        if (outcome == LearnStatus.Completed) {
            val level = _uiState.value.level
            stars = if (level != null) starsFor(level, moves) else 3
            stageScore += levelBonus(stars)
            stageStars = persistStars(_uiState.value.stage, _uiState.value.levelIndex, stars, stageStars)
        }
        _uiState.update {
            it.copy(
                board = board,
                selectedPiece = null,
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

    // ---- Goal evaluation ----

    private fun isSuccess(level: LearnLevel, board: Board, apples: Set<Position>, moves: Int, player: PieceColor): Boolean {
        val opponent = player.opposite
        return when (level.goalType) {
            "info" -> true
            "apples" -> apples.isEmpty()
            "captureAll" -> countColor(board, opponent) == 0
            "protection" -> true // survived one move without a detectCapture failure
            "check" -> board.isKingInCheck(opponent)
            "checkIn" -> board.isKingInCheck(opponent)
            "escapeCheck" -> !board.isKingInCheck(player)
            "mate" -> board.isCheckmate(opponent)
            "castle" -> board.lastMove?.isCastling == true
            else -> apples.isEmpty()
        }
    }

    /** Levels that must succeed on this very move fail if the success condition isn't met. */
    private fun isFailingMove(level: LearnLevel, board: Board, player: PieceColor): Boolean {
        val opponent = player.opposite

        // A move that hangs a piece (per the level's detectCapture rule) fails.
        when (level.detectCapture) {
            "all" -> if (opponentHasCapture(board, opponent)) return true
            "unprotected" -> if (opponentHasUnprotectedCapture(board, opponent)) return true
        }

        // Pawn-stage path constraints.
        if (level.failIfWhitePawnOn.isNotEmpty()) {
            val bad = level.failIfWhitePawnOn.map { square(it) }
            if (bad.any { board.pieces[it.row][it.col]?.let { p -> p.type == PieceType.PAWN && p.color == PieceColor.WHITE } == true }) return true
        }
        if (level.failIfPieceOffPath.isNotEmpty()) {
            val allowed = level.failIfPieceOffPath.map { square(it) }.toSet()
            val strayed = board.pieces.flatMapIndexed { r, row ->
                row.mapIndexedNotNull { c, p -> if (p != null) Position(r, c) else null }
            }.any { it !in allowed }
            if (strayed) return true
        }

        // Single-move goals that weren't achieved this move.
        return when (level.goalType) {
            "check" -> !board.isKingInCheck(opponent)
            "checkIn" -> !board.isKingInCheck(opponent) && _uiState.value.nbMoves + 1 >= (level.n ?: level.nbMoves)
            "escapeCheck" -> board.isKingInCheck(player)
            "mate" -> !board.isCheckmate(opponent)
            else -> false
        }
    }

    private fun countColor(board: Board, color: PieceColor): Int =
        board.pieces.sumOf { row -> row.count { it?.color == color } }

    private fun positionsOf(board: Board, color: PieceColor): List<Position> =
        board.pieces.flatMapIndexed { r, row ->
            row.mapIndexedNotNull { c, p -> if (p?.color == color) Position(r, c) else null }
        }

    /** Legal captures available to [attacker] against the other side. */
    private fun captures(board: Board, attacker: PieceColor): List<Pair<Position, Position>> {
        val targets = positionsOf(board, attacker.opposite)
        return positionsOf(board, attacker).flatMap { from ->
            targets.filter { to -> board.isValidMove(from, to) }.map { from to it }
        }
    }

    private fun opponentHasCapture(board: Board, opponent: PieceColor): Boolean =
        captures(board, opponent).isNotEmpty()

    private fun opponentHasUnprotectedCapture(board: Board, opponent: PieceColor): Boolean {
        val player = opponent.opposite
        return captures(board, opponent).any { (from, to) ->
            val after = board.movePiece(from, to)
            positionsOf(after, player).none { after.isValidMove(it, to) }
        }
    }

    private fun promotionFor(piece: com.vayunmathur.games.chess.data.Piece, to: Position): PieceType? =
        if (piece.type == PieceType.PAWN && (to.row == 0 || to.row == 7)) PieceType.QUEEN else null

    private fun fenSide(fen: String): PieceColor =
        if (fen.split(" ").getOrNull(1) == "b") PieceColor.BLACK else PieceColor.WHITE

    private fun starsFor(level: LearnLevel, moves: Int): Int {
        val par = level.nbMoves
        return when {
            moves <= par -> 3
            moves <= par + maxOf(1, par / 4) -> 2
            else -> 1
        }
    }

    private fun levelBonus(stars: Int): Int = when (stars) {
        3 -> 500; 2 -> 300; else -> 100
    }

    private fun pieceValue(type: PieceType): Int = when (type) {
        PieceType.QUEEN -> 90; PieceType.ROOK -> 50; PieceType.BISHOP -> 30
        PieceType.KNIGHT -> 30; PieceType.PAWN -> 10; PieceType.KING -> 0
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
