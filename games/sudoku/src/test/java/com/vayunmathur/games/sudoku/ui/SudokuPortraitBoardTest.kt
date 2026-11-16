package com.vayunmathur.games.sudoku.ui

import android.app.Application
import android.content.Context
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.games.sudoku.Navigation
import com.vayunmathur.games.sudoku.R
import com.vayunmathur.games.sudoku.Route
import com.vayunmathur.games.sudoku.data.BoardSize
import com.vayunmathur.games.sudoku.data.Difficulty
import com.vayunmathur.games.sudoku.data.GameConfig
import com.vayunmathur.games.sudoku.data.SudokuGameState
import com.vayunmathur.games.sudoku.domain.SudokuGenerator
import com.vayunmathur.games.sudoku.platform.RejectedDigit
import com.vayunmathur.games.sudoku.platform.SudokuActions
import com.vayunmathur.games.sudoku.platform.SudokuUiState
import com.vayunmathur.games.sudoku.platform.SudokuViewModel
import com.vayunmathur.games.sudoku.ui.components.NumberPad
import com.vayunmathur.library.util.NavBackStack
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The portrait (stacked) board layout: tall, narrow windows put the grid above
 * the pad instead of beside it, and that whole branch is invisible to the
 * landscape/default-size suites.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w360dp-h800dp-port")
class SudokuPortraitBoardTest {

    @get:Rule val rule = createComposeRule()
    private val ctx: Context get() = ApplicationProvider.getApplicationContext()
    private val app: Application get() = ApplicationProvider.getApplicationContext()
    private fun str(id: Int) = ctx.getString(id)

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(Dispatchers.Unconfined)
        app.getSharedPreferences("sudoku_stats", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    private class RecordingActions : SudokuActions {
        val digits = mutableListOf<Int>()
        val cells = mutableListOf<Int>()
        var notesToggles = 0
        var clears = 0
        var hints = 0
        var undos = 0
        var restarts = 0
        var playAgains = 0
        var giveUps = 0
        override fun selectCell(index: Int) { cells += index }
        override fun enterDigit(digit: Int) { digits += digit }
        override fun clearCell() { clears++ }
        override fun toggleNotesMode() { notesToggles++ }
        override fun hint() { hints++ }
        override fun undo() { undos++ }
        override fun restart() { restarts++ }
        override fun playAgain() { playAgains++ }
        override fun giveUp() { giveUps++ }
    }

    private fun game(
        size: BoardSize = BoardSize.SIX,
        answerMode: Boolean = false,
    ): SudokuGameState =
        SudokuGameState.from(SudokuGenerator.generate(size, Difficulty.EASY, Random(1)), answerMode)

    @Test
    fun portraitBoardStacksAndEveryControlReachesActions() {
        val actions = RecordingActions()
        val game = game()
        var exits = 0
        rule.setContent {
            GameBoardScreen(
                state = SudokuUiState(game = game, canUndo = true),
                actions = actions,
                onExit = { exits++ },
                elapsedSeconds = { 125 },
            )
        }
        rule.onNodeWithText("6x6 · Easy").assertExists()
        rule.onAllNodesWithText("2:05", substring = true).assertCountEquals(1)

        val side = game.size.side
        rule.onNodeWithContentDescription(ctx.getString(R.string.cd_digit_key, "1")).performClick()
        rule.onNodeWithText(str(R.string.notes)).performClick()
        rule.onNodeWithText(str(R.string.erase)).performClick()
        rule.onNodeWithText(str(com.vayunmathur.library.ui.R.string.undo)).performClick()
        rule.onNodeWithText(str(R.string.hint)).performClick()
        assertEquals(listOf(1), actions.digits)
        assertEquals(1, actions.notesToggles)
        assertEquals(1, actions.clears)
        assertEquals(1, actions.undos)
        assertEquals(1, actions.hints)
        assertEquals(0, exits)
        assertTrue(side > 0)
    }

    @Test
    fun portraitWonAndGameOverBoardsShowTheirOverlays() {
        val actions = RecordingActions()
        val base = game()
        val entries = List(base.size.cellCount) { if (base.isGiven(it)) 0 else base.solution[it] }
        val won = base.copy(entries = entries, isWon = true, hintsUsed = 1)
        rule.setContent {
            GameBoardScreen(
                state = SudokuUiState(game = won),
                actions = actions,
                onExit = {},
                elapsedSeconds = { 42 },
            )
        }
        rule.onNodeWithText(str(R.string.congratulations)).assertExists()
        rule.onNodeWithText("Play Again").performClick()
        assertEquals(1, actions.playAgains)
    }

    @Test
    fun portraitGameOverBoardShowsOutOfLivesOverlay() {
        val actions = RecordingActions()
        val over = game(answerMode = true).copy(livesRemaining = 0)
        rule.setContent {
            GameBoardScreen(state = SudokuUiState(game = over), actions = actions, onExit = {})
        }
        rule.onNodeWithText(str(R.string.out_of_lives)).assertExists()
        rule.onNodeWithText(str(R.string.try_again)).performClick()
        assertEquals(1, actions.restarts)
    }

    @Test
    fun portraitTwelveBoardRendersLetterPadAndNotes() {
        val actions = RecordingActions()
        val base = game(BoardSize.TWELVE)
        val blank = base.givens.indices.first { base.givens[it] == 0 }
        val styled = base.copy(
            notes = base.notes.toMutableList().also { it[blank] = (1 shl 9) or (1 shl 11) },
            selected = blank,
            notesMode = true,
        )
        rule.setContent {
            GameBoardScreen(state = SudokuUiState(game = styled), actions = actions, onExit = {})
        }
        rule.onNodeWithContentDescription(ctx.getString(R.string.cd_note_key, "A")).performClick()
        assertEquals(listOf(10), actions.digits)
        rule.onNodeWithText(str(R.string.hint)).assertExists()
    }

    @Test
    fun rejectedKeyFlashSettlesAfterItsDelay() {
        val game = game(answerMode = true)
        val tapped = mutableListOf<Int>()
        rule.mainClock.autoAdvance = false
        rule.setContent {
            NumberPad(game = game, onDigit = { tapped += it }, rejected = RejectedDigit(2, 1L))
        }
        rule.waitForIdle()
        rule.mainClock.advanceTimeBy(600)
        rule.waitForIdle()
        // After the flash settles the key is an ordinary key again.
        rule.onNodeWithContentDescription(ctx.getString(R.string.cd_digit_key, "2")).performClick()
        assertEquals(listOf(2), tapped)
    }

    private fun awaitValue(desc: String, cond: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 20_000
        while (System.currentTimeMillis() < deadline) {
            if (cond()) return
            Thread.sleep(25)
        }
        assertTrue(cond(), "timed out: $desc")
    }

    @Test
    fun homeSwitchesToggleTheViewModelSettings() {
        val vm = SudokuViewModel(app)
        val stack = NavBackStack(arrayOf<Route>(Route.Home))
        rule.setContent { HomeScreen(stack, vm) }
        rule.onNodeWithText(str(R.string.answer_mode)).performScrollTo().performClick()
        awaitValue("answer mode on") { vm.answerMode.value }
        rule.onNodeWithText(str(R.string.answer_mode)).performClick()
        awaitValue("answer mode off") { !vm.answerMode.value }

        rule.onNodeWithText(str(R.string.highlight_mistakes)).performScrollTo().performClick()
        awaitValue("highlight off") { !vm.highlightMistakes.value }
    }

    @Test
    fun navigationGameCenterBackReturnsHome() {
        val vm = SudokuViewModel(app)
        rule.setContent { Navigation(vm) }
        rule.onNodeWithContentDescription("Badges").performClick()
        rule.waitForIdle()
        rule.onNodeWithText(str(com.vayunmathur.library.ui.R.string.title_game_center)).assertExists()
        rule.onNodeWithContentDescription("Back").performClick()
        rule.waitForIdle()
        rule.onNodeWithText(str(com.vayunmathur.library.ui.R.string.new_game)).assertExists()
    }

    @Test
    fun gameScreenBackPopsTheStack() {
        val vm = SudokuViewModel(app)
        val config = GameConfig(BoardSize.SIX, Difficulty.EASY)
        val stack = NavBackStack(arrayOf<Route>(Route.Home, Route.Game(config)))
        rule.setContent { GameScreen(stack, vm, config) }
        val deadline = System.currentTimeMillis() + 20_000
        while (vm.uiState.value.game == null && System.currentTimeMillis() < deadline) {
            Thread.sleep(25)
        }
        assertTrue(vm.uiState.value.game != null, "GameScreen deals its game")
        rule.onNodeWithContentDescription("Back").performClick()
        assertEquals(1, stack.backStack.size, "the board's back arrow pops the game route")
    }
}
