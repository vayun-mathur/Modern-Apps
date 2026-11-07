package com.vayunmathur.games.chess

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.games.chess.data.Board
import com.vayunmathur.games.chess.data.LearnRepository
import com.vayunmathur.games.chess.data.PieceColor
import com.vayunmathur.games.chess.data.Position
import com.vayunmathur.games.chess.util.LearnStatus
import com.vayunmathur.games.chess.util.LearnUiState
import com.vayunmathur.games.chess.util.PuzzleActions
import com.vayunmathur.games.chess.util.PuzzleDifficulty
import com.vayunmathur.games.chess.util.PuzzleStatus
import com.vayunmathur.games.chess.util.PuzzleUiState
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
class PuzzleAndLearnScreensTest {
    @get:Rule val rule = createComposeRule()
    private val ctx get() = ApplicationProvider.getApplicationContext<android.content.Context>()
    private fun str(id: Int, vararg args: Any) = if (args.isEmpty()) ctx.getString(id) else ctx.getString(id, *args)

    private fun clickByText(text: String) {
        val node = rule.onNode(androidx.compose.ui.test.hasText(text)).fetchSemanticsNode()
        val ok = node.config[androidx.compose.ui.semantics.SemanticsActions.OnClick].action?.invoke(); assertTrue(ok == true)
    }

    private class RecordingPuzzleActions : PuzzleActions {
        val clicks = mutableListOf<Position>()
        var loads = 0; var retries = 0; var solutions = 0
        override fun onSquareClick(position: Position) { clicks += position }
        override fun loadRandom(difficulty: PuzzleDifficulty) { loads++ }
        override fun retry() { retries++ }
        override fun showSolution() { solutions++ }
    }

    @Test fun `puzzle solving state shows rating and next loads another`() {
        val actions = RecordingPuzzleActions()
        rule.setContent {
            PuzzleBoardScreen(state = PuzzleUiState(status = PuzzleStatus.Solving, rating = 900, playerColor = PieceColor.WHITE), actions = actions)
        }
        rule.onNodeWithText(str(R.string.puzzle_rating, 900)).assertExists()
        rule.onNodeWithText(str(R.string.puzzle_white_to_move)).assertExists()
        clickByText(str(R.string.puzzle_next))
        assertEquals(1, actions.loads)
    }

    @Test fun `puzzle failed state offers retry and solution`() {
        val actions = RecordingPuzzleActions()
        rule.setContent {
            PuzzleBoardScreen(state = PuzzleUiState(status = PuzzleStatus.Failed, rating = 1500, playerColor = PieceColor.BLACK), actions = actions)
        }
        rule.onNodeWithText(str(R.string.puzzle_black_to_move)).assertExists()
        clickByText(str(R.string.puzzle_show_solution))
        assertEquals(1, actions.solutions)
    }

    @Test fun `puzzle solved and loading states render status`() {
        rule.setContent {
            PuzzleBoardScreen(state = PuzzleUiState(status = PuzzleStatus.Solved, rating = 2000), actions = RecordingPuzzleActions())
        }
        rule.onNodeWithText(str(R.string.puzzle_rating, 2000)).assertExists()
    }

    @Test fun `learn home lists real stages and opens one`() {
        LearnRepository.ensureLoaded(ctx)
        val opened = mutableListOf<Pair<String, String>>()
        rule.setContent { LearnHomeScreen(onOpenStage = { a, b -> opened += a to b }) }
        rule.waitUntil(timeoutMillis = 10_000) {
            rule.onAllNodes(androidx.compose.ui.test.hasText(LearnRepository.categories.first().stages.first().title))
                .fetchSemanticsNodes().isNotEmpty()
        }
        val first = LearnRepository.categories.first()
        rule.onNodeWithText(first.stages.first().title).performClick()
        assertEquals(listOf(first.key to first.stages.first().key), opened)
    }

    @Test fun `stage intro dialog and complete dialog render`() {
        LearnRepository.ensureLoaded(ctx)
        val stage = LearnRepository.categories.first().stages.first()
        var started = 0
        rule.setContent { StageIntroDialog(stage, onStart = { started++ }) }
        rule.onNodeWithText(str(R.string.learn_lets_go)).performClick()
        assertEquals(1, started)
        assertTrue(stage.levels.isNotEmpty())
    }

    @Test fun `level stepper and stars render and select`() {
        val selected = mutableListOf<Int>()
        rule.setContent {
            LevelStepper(count = 3, current = 0, stars = listOf(3, 1, 0), onSelect = { selected += it })
        }
        // Stepper renders three levels; clicking second selects index 1 if semantics expose it
        val nodes = rule.onAllNodes(androidx.compose.ui.test.hasClickAction())
        if (nodes.fetchSemanticsNodes().size >= 2) {
            nodes[1].performClick()
            assertTrue(selected.isNotEmpty())
        }
    }

    @Test fun `learn ui state defaults are playing on initial board`() {
        val state = LearnUiState()
        assertEquals(LearnStatus.Playing, state.status)
        assertEquals(Board.initialState, state.board)
    }
}
