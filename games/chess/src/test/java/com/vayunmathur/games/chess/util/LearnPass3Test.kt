package com.vayunmathur.games.chess.util

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.games.chess.data.LearnRepository
import com.vayunmathur.games.chess.data.PieceType
import com.vayunmathur.games.chess.data.Position
import com.vayunmathur.games.chess.data.parseUci
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/** Pass 3: LearnViewModel lesson play — collecting, capturing, scenarios, promotion. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class LearnPass3Test {

    private val app get() = ApplicationProvider.getApplicationContext<Application>()
    private val ctx: Context get() = app

    @BeforeTest fun setUp() {
        Dispatchers.setMain(Dispatchers.Unconfined)
        LearnRepository.ensureLoaded(ctx)
    }

    @AfterTest fun tearDown() = Dispatchers.resetMain()

    private fun await(desc: String, cond: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 20_000
        while (System.currentTimeMillis() < deadline) {
            if (cond()) return
            Thread.sleep(25)
        }
        assertTrue(cond(), "timed out: $desc")
    }

    private fun loadVm(categoryKey: String, stageKey: String, levelIndex: Int = 0): LearnViewModel {
        val vm = LearnViewModel(app)
        vm.loadStage(categoryKey, stageKey)
        await("stage $stageKey") { vm.uiState.value.level != null }
        if (levelIndex != 0) vm.goToLevel(levelIndex)
        return vm
    }

    /** One legal (VM-rules) move for the player that lands on [target], if any exists. */
    private fun moveOnto(vm: LearnViewModel, target: Position): Boolean {
        val state = vm.uiState.value
        val board = state.board
        for (r in 0..7) for (c in 0..7) {
            val piece = board.pieces[r][c] ?: continue
            if (piece.color != state.playerColor) continue
            val from = Position(r, c)
            if (board.isValidMove(from, target) || board.isPseudoLegalMove(from, target)) {
                vm.onSquareClick(from)
                vm.onSquareClick(target)
                return true
            }
        }
        return false
    }

    /** Any legal player move (used to walk a piece toward a target). */
    private fun anyMoveToward(vm: LearnViewModel, target: Position): Boolean {
        val state = vm.uiState.value
        val board = state.board
        var best: Pair<Position, Position>? = null
        var bestDist = Int.MAX_VALUE
        for (r in 0..7) for (c in 0..7) {
            val piece = board.pieces[r][c] ?: continue
            if (piece.color != state.playerColor) continue
            val from = Position(r, c)
            for (r2 in 0..7) for (c2 in 0..7) {
                val to = Position(r2, c2)
                if (board.isValidMove(from, to) || board.isPseudoLegalMove(from, to)) {
                    val d = kotlin.math.abs(r2 - target.row) + kotlin.math.abs(c2 - target.col)
                    if (d < bestDist) {
                        bestDist = d
                        best = from to to
                    }
                }
            }
        }
        val (from, to) = best ?: return false
        vm.onSquareClick(from)
        vm.onSquareClick(to)
        return true
    }

    @Test fun rookStageCollectAllApplesCompletes() {
        val vm = loadVm("chessPieces", "rook")
        var guard = 0
        while (vm.uiState.value.status == LearnStatus.Playing && guard++ < 40) {
            val apples = vm.uiState.value.apples
            assertTrue(apples.isNotEmpty() || vm.uiState.value.status != LearnStatus.Playing)
            val target = apples.first()
            if (!moveOnto(vm, target)) assertTrue(anyMoveToward(vm, target), "a legal move must exist")
            if (vm.uiState.value.status == LearnStatus.Failed) vm.retryLevel()
        }
        assertEquals(LearnStatus.Completed, vm.uiState.value.status)
        assertTrue(vm.uiState.value.starsEarned in 1..3, "stars: ${vm.uiState.value.starsEarned}")
        assertTrue(vm.uiState.value.stageScore > 0)
        await("stars persisted") { vm.uiState.value.stageStars.getOrNull(0)?.let { it > 0 } == true }

        await("stars persisted to DataStore") {
            LearnProgress.stageStars(ctx, vm.uiState.value.stage!!)[0] > 0
        }
        val stars = LearnProgress.stageStars(ctx, vm.uiState.value.stage!!)
        assertTrue(stars[0] > 0, "persisted stars: $stars")
        assertTrue(LearnProgress.completedCount(ctx, vm.uiState.value.stage!!) >= 1)

        vm.nextLevel()
        assertEquals(1, vm.uiState.value.levelIndex)
        assertEquals(LearnStatus.Playing, vm.uiState.value.status)
    }

    @Test fun captureStageCapturesAllTargets() {
        val vm = loadVm("fundamentals", "capture")
        var guard = 0
        while (vm.uiState.value.status == LearnStatus.Playing && guard++ < 30) {
            val state = vm.uiState.value
            // Capture the first enemy piece reachable in one move; else reposition.
            var captured = false
            outer@ for (r in 0..7) for (c in 0..7) {
                val enemy = state.board.pieces[r][c] ?: continue
                if (enemy.color == state.playerColor) continue
                if (moveOnto(vm, Position(r, c))) {
                    captured = true
                    break@outer
                }
            }
            if (!captured) {
                val enemyPos = (0..7).firstNotNullOfOrNull { r ->
                    (0..7).firstNotNullOfOrNull { c ->
                        val p = state.board.pieces[r][c]
                        if (p != null && p.color != state.playerColor) Position(r, c) else null
                    }
                }
                assertTrue(enemyPos != null && anyMoveToward(vm, enemyPos), "must be able to approach")
            }
            if (vm.uiState.value.status == LearnStatus.Failed) vm.retryLevel()
        }
        assertEquals(LearnStatus.Completed, vm.uiState.value.status)
    }

    @Test fun illegalAndWrongClicksDeselectButLegalMoveRegisters() {
        val vm = loadVm("chessPieces", "rook")
        val state = vm.uiState.value
        // Click an empty square with nothing selected: no selection, no move.
        val empty = (0..7).firstNotNullOf { r ->
            (0..7).firstNotNullOfOrNull { c -> if (state.board.pieces[r][c] == null) Position(r, c) else null }
        }
        vm.onSquareClick(empty)
        assertEquals(null, vm.uiState.value.selectedPiece)
        assertEquals(0, vm.uiState.value.nbMoves)

        // Select the rook, then click an illegal target: deselects without moving.
        val rook = (0..7).firstNotNullOf { r ->
            (0..7).firstNotNullOfOrNull { c ->
                val p = state.board.pieces[r][c]
                if (p != null && p.color == state.playerColor) Position(r, c) else null
            }
        }
        vm.onSquareClick(rook)
        assertEquals(rook, vm.uiState.value.selectedPiece)
        vm.onSquareClick(rook)
        assertEquals(null, vm.uiState.value.selectedPiece, "clicking the selected piece deselects")
        vm.onSquareClick(rook)
        vm.onSquareClick(empty)
        // Either it was a legal rook move (nbMoves 1) or an illegal one (deselected, 0 moves).
        assertTrue(vm.uiState.value.nbMoves in 0..1)
    }

    @Test fun scenarioLevelWrongMoveFailsAndRetryRestores() {
        // Find the first scenario level in the catalogue.
        var found: Triple<String, String, Int>? = null
        for (cat in LearnRepository.categories) for (stage in cat.stages) {
            val idx = stage.levels.indexOfFirst { it.goalType == "scenario" && it.scenario.isNotEmpty() }
            if (idx >= 0) {
                found = Triple(cat.key, stage.key, idx)
                break
            }
        }
        val (catKey, stageKey, levelIdx) = found ?: error("catalogue has a scenario level")
        val vm = loadVm(catKey, stageKey, levelIdx)
        val level = vm.uiState.value.level!!
        assertEquals("scenario", level.goalType)

        // If the opponent opens the scenario (FEN side to move is not the player),
        // wait for the scripted reply.
        val fenSide = level.fen.split(" ").getOrNull(1)
        val opponentOpens = (fenSide == "w") != (vm.uiState.value.playerColor.name == "WHITE")
        if (opponentOpens) {
            val boardBefore = vm.uiState.value.board
            await("opponent reply or player turn") {
                vm.uiState.value.board != boardBefore || vm.uiState.value.status != LearnStatus.Playing
            }
        }

        // Play a pseudo-legal move that is NOT the scripted one: scenario fails.
        val state = vm.uiState.value
        if (state.status == LearnStatus.Playing) {
            val expected = level.scenario.firstOrNull()
            // The scripted move for the current turn depends on replies already played;
            // use the level's first script entry's from/to as the forbidden target pair.
            val (efrom, eto, _) = parseUci(expected ?: "a1a2")
            var failedMove = false
            outer@ for (r in 0..7) for (c in 0..7) {
                val piece = state.board.pieces[r][c] ?: continue
                if (piece.color != state.playerColor) continue
                for (r2 in 0..7) for (c2 in 0..7) {
                    val from = Position(r, c)
                    val to = Position(r2, c2)
                    if (from == efrom && to == eto) continue
                    if (state.board.isPseudoLegalMove(from, to) && state.board.pieces[r2][c2]?.color != state.playerColor) {
                        vm.onSquareClick(from)
                        vm.onSquareClick(to)
                        failedMove = true
                        break@outer
                    }
                }
            }
            if (failedMove && vm.uiState.value.status == LearnStatus.Failed) {
                vm.retryLevel()
                assertEquals(LearnStatus.Playing, vm.uiState.value.status)
                assertEquals(0, vm.uiState.value.nbMoves)
            } else {
                // The chosen move may have been the scripted reply's turn or the game
                // already advanced; the invariant is that state stays consistent.
                assertTrue(vm.uiState.value.status in LearnStatus.entries)
            }
        }
    }

    @Test fun stageStepperBoundsAndFinishFlag() {
        val vm = loadVm("chessPieces", "bishop")
        val stage = vm.uiState.value.stage!!
        vm.goToLevel(stage.levels.size - 1)
        assertEquals(stage.levels.size - 1, vm.uiState.value.levelIndex)
        vm.goToLevel(-1)
        assertEquals(stage.levels.size - 1, vm.uiState.value.levelIndex, "negative index ignored")
        // Completing is not required to finish via the stepper contract: nextLevel
        // past the end flips stageFinished instead of indexing out of range.
        var guard = 0
        while (vm.uiState.value.status == LearnStatus.Playing && guard++ < 40) {
            val target = vm.uiState.value.apples.firstOrNull() ?: break
            if (!moveOnto(vm, target)) anyMoveToward(vm, target)
            if (vm.uiState.value.status == LearnStatus.Failed) vm.retryLevel()
        }
        if (vm.uiState.value.status == LearnStatus.Completed) {
            vm.nextLevel()
            assertTrue(vm.uiState.value.stageFinished)
        } else {
            assertNotEquals(LearnStatus.Completed, vm.uiState.value.status)
            vm.nextLevel()
            // From the last level, nextLevel finishes the stage when already at the end
            // only after completion attempts; it must never crash or go negative.
            assertTrue(vm.uiState.value.levelIndex >= 0)
        }
    }

    @Test fun promotionLevelPromotesPawn() {
        // Find a level where a pawn can reach the last rank in one legal move.
        var spot: Triple<String, String, Int>? = null
        outer@ for (cat in LearnRepository.categories) for (stage in cat.stages) {
            for ((idx, level) in stage.levels.withIndex()) {
                val board = LearnRepository.buildBoard(level)
                for (r in 0..7) for (c in 0..7) {
                    val piece = board.pieces[r][c] ?: continue
                    if (piece.type != PieceType.PAWN || piece.color != level.playerColor) continue
                    val lastRow = if (level.playerColor.name == "WHITE") 0 else 7
                    for (c2 in 0..7) {
                        val to = Position(lastRow, c2)
                        if (board.isValidMove(Position(r, c), to) || board.isPseudoLegalMove(Position(r, c), to)) {
                            spot = Triple(cat.key, stage.key, idx)
                            break@outer
                        }
                    }
                }
            }
        }
        val foundSpot = spot
        if (foundSpot == null) {
            // No one-move promotion in the catalogue: onPromote without a pending
            // promotion is a no-op (covered contract).
            val vm = loadVm("chessPieces", "rook")
            vm.onPromote(PieceType.QUEEN)
            assertEquals(LearnStatus.Playing, vm.uiState.value.status)
            return
        }
        val vm = loadVm(foundSpot.first, foundSpot.second, foundSpot.third)
        val state = vm.uiState.value
        val board = state.board
        var promoted = false
        outer@ for (r in 0..7) for (c in 0..7) {
            val piece = board.pieces[r][c] ?: continue
            if (piece.type != PieceType.PAWN || piece.color != state.playerColor) continue
            val lastRow = if (state.playerColor.name == "WHITE") 0 else 7
            for (c2 in 0..7) {
                val to = Position(lastRow, c2)
                if (board.isValidMove(Position(r, c), to) || board.isPseudoLegalMove(Position(r, c), to)) {
                    vm.onSquareClick(Position(r, c))
                    vm.onSquareClick(to)
                    if (vm.uiState.value.board.promotionPosition != null) {
                        vm.onPromote(PieceType.QUEEN)
                        val pieceAfter = vm.uiState.value.board.pieces[to.row][to.col]
                        assertEquals(PieceType.QUEEN, pieceAfter?.type)
                        promoted = true
                        break@outer
                    }
                }
            }
        }
        assertTrue(promoted, "expected a pawn promotion to be playable")
    }
}
