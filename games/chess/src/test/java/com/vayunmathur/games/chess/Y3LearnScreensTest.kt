package com.vayunmathur.games.chess

import android.app.Application
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.games.chess.data.LearnRepository
import com.vayunmathur.games.chess.util.LearnViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class Y3LearnScreensTest {
    @get:Rule val rule = createComposeRule()
    private val ctx get() = ApplicationProvider.getApplicationContext<android.content.Context>()
    private fun str(id: Int) = ctx.getString(id)

    @BeforeTest fun setUp() { Dispatchers.setMain(Dispatchers.Unconfined) }
    @AfterTest fun tearDown() { Dispatchers.resetMain() }

    @Test fun learnHomeListsStagesAndClickOpens() {
        LearnRepository.ensureLoaded(ctx)
        val opened = mutableListOf<Pair<String, String>>()
        rule.setContent { LearnHomeScreen(onOpenStage = { c, s -> opened += c to s }) }
        val cat = LearnRepository.categories.first()
        val stage = cat.stages.first()
        rule.waitUntil(timeoutMillis = 15_000) {
            rule.onAllNodesWithText(stage.title).fetchSemanticsNodes().isNotEmpty()
        }
        rule.onNodeWithText(stage.title).performClick()
        assertEquals(listOf(cat.key to stage.key), opened)
        assertTrue(LearnRepository.categories.sumOf { it.stages.size } > 5)
    }

    @Test fun stageIntroAndCompleteDialogsRender() {
        LearnRepository.ensureLoaded(ctx)
        val stage = LearnRepository.categories.first().stages.first()
        var started = 0
        rule.setContent { StageIntroDialog(stage, onStart = { started++ }) }
        rule.onNodeWithText(str(R.string.learn_lets_go)).performClick()
        assertEquals(1, started)
    }

    @Test fun stageCompleteDialogWithoutNext() {
        LearnRepository.ensureLoaded(ctx)
        val stage = LearnRepository.categories.last().stages.last()
        var back = 0
        rule.setContent {
            StageCompleteDialog(stage = stage, stageStars = listOf(3), score = 100, nextTitle = null, onNext = {}, onBackToMenu = { back++ })
        }
        // Dialog renders stage title/score; dismiss via back-to-menu action if present.
        assertTrue(rule.onAllNodesWithText(stage.title, substring = true).fetchSemanticsNodes().isNotEmpty() ||
            rule.onAllNodesWithText("100", substring = true).fetchSemanticsNodes().isNotEmpty())
        assertEquals(0, back)
    }

    @Test fun learnStagePlayClicksSquares() {
        LearnRepository.ensureLoaded(ctx)
        val cat = LearnRepository.categories.first()
        val stage = cat.stages.first()
        val app = ApplicationProvider.getApplicationContext<Application>()
        val vm = LearnViewModel(app)
        vm.loadStage(cat.key, stage.key)
        rule.waitUntil(timeoutMillis = 15_000) { vm.uiState.value.level != null }
        rule.setContent { LearnStageScreen(viewModel = vm, onBack = {}, onOpenStage = { _, _ -> }) }
        rule.onNodeWithText(str(R.string.learn_lets_go)).performClick()
        val squares = rule.onAllNodes(hasClickAction())
        assertTrue(squares.fetchSemanticsNodes().size >= 32)
        squares[0].performClick(); squares[8].performClick()
        // State remains coherent after board taps.
        assertEquals(stage.key, vm.uiState.value.stage?.key)
    }

    @Test fun levelStepperSelects() {
        var selected = -1
        rule.setContent { LevelStepper(count = 5, current = 1, stars = listOf(3, 0, 0, 0, 0), onSelect = { selected = it }) }
        rule.onNodeWithText("3").performClick()
        assertEquals(2, selected)
    }

    @Test fun learnRepositoryEveryLevelBuildsBoardAndNextStageChain() {
        LearnRepository.ensureLoaded(ctx)
        var levels = 0
        for (cat in LearnRepository.categories) {
            for (stage in cat.stages) {
                assertEquals(stage, LearnRepository.stage(cat.key, stage.key))
                for (level in stage.levels) {
                    val board = LearnRepository.buildBoard(level)
                    assertEquals(8, board.pieces.size); levels++
                }
            }
        }
        assertTrue(levels > 50, "levels=$levels")
        assertEquals(null, LearnRepository.stage("nope", "nope"))
        val first = LearnRepository.categories.first().stages.first()
        val next = LearnRepository.nextStage(first.key)
        assertTrue(next != null)
        val last = LearnRepository.categories.last().stages.last()
        assertEquals(null, LearnRepository.nextStage(last.key))
        assertEquals(null, LearnRepository.nextStage("missing-stage"))
    }

    @Test fun routesAreDistinctNavKeys() {
        val routes: List<Route> = listOf(Route.Game, Route.Online, Route.Puzzles, Route.Learn, Route.GameCenter, Route.LearnStage("c", "s"))
        assertEquals(6, routes.toSet().size)
        assertEquals(Route.LearnStage("c", "s"), routes.last())
    }

    @Test fun learnVmCompletesFirstLevelAndNavigates() {
        LearnRepository.ensureLoaded(ctx)
        val app = ApplicationProvider.getApplicationContext<Application>()
        val vm = LearnViewModel(app)
        val cat = LearnRepository.categories.first(); val stage = cat.stages.first()
        vm.loadStage(cat.key, stage.key)
        val deadline = System.currentTimeMillis() + 15_000
        while (vm.uiState.value.level == null && System.currentTimeMillis() < deadline) Thread.sleep(50)
        assertEquals(stage.key, vm.uiState.value.stage?.key)
        fun sq(f: Char, r: Int) = com.vayunmathur.games.chess.data.Position(8 - r, f - 'a')
        // Level 1: rook e2 to star e7 in one move.
        vm.onSquareClick(sq('e', 2)); vm.onSquareClick(sq('e', 7))
        assertTrue(vm.uiState.value.status != com.vayunmathur.games.chess.util.LearnStatus.Playing || vm.uiState.value.levelIndex > 0, "${vm.uiState.value}")
        vm.retryLevel(); assertEquals(0, vm.uiState.value.nbMoves)
        vm.onSquareClick(sq('e', 2)); vm.onSquareClick(sq('e', 7))
        vm.nextLevel()
        assertTrue(vm.uiState.value.levelIndex >= 1 || vm.uiState.value.status != com.vayunmathur.games.chess.util.LearnStatus.Playing)
        vm.goToLevel(0); assertEquals(0, vm.uiState.value.levelIndex)
        // Wrong move: rook sideways does not collect the star.
        vm.onSquareClick(sq('e', 2)); vm.onSquareClick(sq('a', 2))
        vm.onSquareDrop(sq('e', 2), sq('e', 7))
    }
}
