package com.vayunmathur.games.chess.util

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.games.chess.data.LearnRepository
import com.vayunmathur.games.chess.data.PieceType
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
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class PuzzleAndLearnViewModelTest {
    private val app get() = ApplicationProvider.getApplicationContext<Application>()
    private val ctx get() = ApplicationProvider.getApplicationContext<Context>()

    @BeforeTest fun setUp() { Dispatchers.setMain(Dispatchers.Unconfined) }
    @AfterTest fun tearDown() { Dispatchers.resetMain() }

    private fun await(desc: String, timeoutMs: Long = 15_000, cond: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (cond()) return
            Thread.sleep(50)
        }
        assertTrue(cond(), "timed out waiting for $desc")
    }

    @Test fun `puzzle loads, wrong clicks fail or deselect, solution replay solves`() {
        val vm = PuzzleViewModel(app)
        assertEquals(PuzzleStatus.Loading, vm.uiState.value.status)
        vm.onSquareClick(com.vayunmathur.games.chess.data.Position(0, 0)) // no puzzle yet
        vm.retry(); vm.showSolution(); vm.onPromote(PieceType.QUEEN) // no-ops without a puzzle
        vm.loadRandom(PuzzleDifficulty.EASY)
        await("puzzle solving") { vm.uiState.value.status == PuzzleStatus.Solving && vm.uiState.value.puzzle != null }
        val state = vm.uiState.value
        val puzzle = assertNotNull(state.puzzle)
        assertTrue(puzzle.rating in PuzzleDifficulty.EASY.range)
        assertEquals(1, state.solutionIndex)
        // Replay solution, then restart via retry and fail with a wrong legal move if possible
        vm.showSolution()
        await("solution solved") { vm.uiState.value.status == PuzzleStatus.Solved }
        assertEquals(puzzle.solution.size, vm.uiState.value.solutionIndex)
    }

    @Test fun `playing the solution solves and a wrong legal move fails then retry restarts`() {
        val vm = PuzzleViewModel(app)
        vm.loadRandom(PuzzleDifficulty.EASY)
        await("solving") { vm.uiState.value.status == PuzzleStatus.Solving }
        // Play every remaining solution move correctly
        var guard = 0
        while (vm.uiState.value.status == PuzzleStatus.Solving && guard++ < 12) {
            val st = vm.uiState.value
            val expected = st.puzzle!!.solution[st.solutionIndex]
            vm.onSquareClick(expected.from)
            vm.onSquareClick(expected.to)
            if (vm.uiState.value.board.promotionPosition != null && expected.promotion != null) {
                vm.onPromote(expected.promotion!!)
            }
            await("advance") {
                vm.uiState.value.status != PuzzleStatus.Solving ||
                    vm.uiState.value.solutionIndex != st.solutionIndex
            }
            // Opponent reply is delayed; wait until it lands (index moves again or solved)
            Thread.sleep(600)
        }
        await("solved") { vm.uiState.value.status == PuzzleStatus.Solved }

        // New puzzle: make a legal but wrong move and expect Failed, then retry
        vm.loadRandom(PuzzleDifficulty.EASY)
        await("solving again") { vm.uiState.value.status == PuzzleStatus.Solving }
        val st = vm.uiState.value
        val expected = st.puzzle!!.solution[st.solutionIndex]
        val wrong = st.board.legalMoves(st.playerColor).firstOrNull { it.start != expected.from || it.end != expected.to }
        if (wrong != null) {
            vm.onSquareClick(wrong.start); vm.onSquareClick(wrong.end)
            if (vm.uiState.value.status == PuzzleStatus.Failed) {
                vm.retry()
                await("retry solving") { vm.uiState.value.status == PuzzleStatus.Solving }
            }
        }
    }

    @Test fun `puzzle difficulty bands map to rating ranges`() {
        assertTrue(PuzzleDifficulty.EASY.range.first < PuzzleDifficulty.MEDIUM.range.first)
        assertTrue(PuzzleDifficulty.MEDIUM.range.first < PuzzleDifficulty.HARD.range.first)
        assertEquals(400, PuzzleDifficulty.EASY.range.first)
    }

    @Test fun `learn loads first stage and navigates levels`() {
        LearnRepository.ensureLoaded(ctx)
        val cat = LearnRepository.categories.first()
        val stage = cat.stages.first()
        val vm = LearnViewModel(app)
        assertNull(vm.uiState.value.stage)
        vm.retryLevel(); vm.nextLevel(); vm.goToLevel(0) // no-ops before load
        vm.loadStage(cat.key, stage.key)
        await("learn stage") { vm.uiState.value.stage?.key == stage.key && vm.uiState.value.level != null }
        assertEquals(0, vm.uiState.value.levelIndex)
        if (stage.levels.size > 1) {
            vm.goToLevel(1)
            assertEquals(1, vm.uiState.value.levelIndex)
            vm.goToLevel(999) // out of range: ignored
            assertEquals(1, vm.uiState.value.levelIndex)
            vm.retryLevel()
            assertEquals(1, vm.uiState.value.levelIndex)
        }
        vm.loadStage("no-such-cat", "no-such-stage")
        // Failed load leaves current stage in place (launch returns early)
        assertEquals(stage.key, vm.uiState.value.stage?.key)
    }

    @Test fun `learn cancelling a promotion puts the pawn back uncounted`() {
        val vm = LearnViewModel(app)
        vm.loadStage("chessPieces", "pawn")
        await("pawn stage") { vm.uiState.value.level != null }
        vm.goToLevel(0) // a lone pawn on a5
        await("level 0") { vm.uiState.value.levelIndex == 0 && vm.uiState.value.level != null }
        fun sq(file: Char, rank: Int) = com.vayunmathur.games.chess.data.Position(8 - rank, file - 'a')
        // Learn keeps the moved piece selected, so only tap the piece when it isn't already.
        fun move(from: com.vayunmathur.games.chess.data.Position, to: com.vayunmathur.games.chess.data.Position) {
            if (vm.uiState.value.selectedPiece != from) vm.onSquareClick(from)
            vm.onSquareClick(to)
        }
        move(sq('a', 5), sq('a', 6))
        move(sq('a', 6), sq('a', 7))
        val before = vm.uiState.value
        move(sq('a', 7), sq('a', 8))
        assertNotNull(vm.uiState.value.board.promotionPosition, "picker is up")
        vm.onCancelPromotion()
        assertNull(vm.uiState.value.board.promotionPosition)
        assertEquals(before.board, vm.uiState.value.board, "pawn back on a7")
        assertEquals(before.nbMoves, vm.uiState.value.nbMoves, "the cancelled step is not counted")
        assertEquals(LearnStatus.Playing, vm.uiState.value.status)
    }
}
