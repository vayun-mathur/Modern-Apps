package com.vayunmathur.games.minesweeper.ui

import android.content.Context
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.games.minesweeper.R
import com.vayunmathur.games.minesweeper.data.BoardSize
import com.vayunmathur.games.minesweeper.data.Difficulty
import com.vayunmathur.games.minesweeper.data.GameConfig
import com.vayunmathur.games.minesweeper.data.GameOutcome
import com.vayunmathur.games.minesweeper.data.MinesweeperGameState
import com.vayunmathur.games.minesweeper.data.TapMode
import com.vayunmathur.games.minesweeper.platform.MinesweeperActions
import com.vayunmathur.games.minesweeper.platform.MinesweeperUiState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Board screen end states, confirmation dialogs and the mode toggle. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class GameBoardScreenOutcomeTest {

    @get:Rule val rule = createComposeRule()
    private val ctx: Context get() = ApplicationProvider.getApplicationContext()
    private fun str(id: Int, vararg a: Any) = ctx.getString(id, *a)

    private class RecordingActions : MinesweeperActions {
        val tapped = mutableListOf<Int>()
        var mode: TapMode? = null
        var restarts = 0
        var giveUps = 0
        override fun tapCell(index: Int) { tapped += index }
        override fun flagCell(index: Int): Boolean = false
        override fun setTapMode(m: TapMode) { mode = m }
        override fun restart() { restarts++ }
        override fun giveUp() { giveUps++ }
    }

    /** 3x3, mine at centre (4). */
    private fun field(
        revealed: List<Boolean> = List(9) { false },
        flagged: List<Boolean> = List(9) { false },
        outcome: GameOutcome = GameOutcome.PLAYING,
        started: Boolean = true,
        explodedAt: Int = -1,
    ): MinesweeperGameState {
        val mines = List(9) { it == 4 }
        val probe = MinesweeperGameState.of(3, 3, 1)
        val counts = List(9) { i -> if (mines[i]) 0 else probe.neighbours(i).count { mines[it] } }
        return MinesweeperGameState(
            cols = 3, rows = 3, mineCount = 1, mines = mines, neighbourCounts = counts,
            revealed = revealed, flagged = flagged, outcome = outcome,
            explodedAt = explodedAt, started = started,
        )
    }

    private fun state(game: MinesweeperGameState?) = MinesweeperUiState(
        config = GameConfig(BoardSize.SMALL, Difficulty.EASY), game = game,
    )

    @Test
    fun wonFieldShowsClearedOverlayWithTimeAndPlayAgainRestarts() {
        val actions = RecordingActions()
        var exited = false
        val won = field(
            revealed = List(9) { it != 4 }, flagged = List(9) { it == 4 },
            outcome = GameOutcome.WON,
        )
        rule.setContent {
            GameBoardScreen(state(won), actions, onExit = { exited = true }, elapsedSeconds = { 65 })
        }
        rule.onNodeWithText(str(R.string.cleared)).assertExists()
        rule.onNodeWithText("${str(com.vayunmathur.library.ui.R.string.game_time)}: 01:05")
            .assertExists()
        assertFalse(exited)
        rule.onNodeWithText(str(com.vayunmathur.library.ui.R.string.game_play_again)).performClick()
        assertEquals(1, actions.restarts)
    }

    @Test
    fun lostFieldShowsBoomWithoutTimeAndBackExits() {
        val actions = RecordingActions()
        var exited = false
        val lost = field(
            revealed = List(9) { it == 4 }, outcome = GameOutcome.LOST, explodedAt = 4,
        )
        rule.setContent {
            GameBoardScreen(state(lost), actions, onExit = { exited = true }, elapsedSeconds = { 65 })
        }
        rule.onNodeWithText(str(R.string.boom)).assertExists()
        rule.onNodeWithText("${str(com.vayunmathur.library.ui.R.string.game_time)}: 01:05")
            .assertDoesNotExist()
        rule.onNodeWithText(str(com.vayunmathur.library.ui.R.string.game_back)).performClick()
        assertTrue(exited)
        assertEquals(0, actions.restarts)
    }

    @Test
    fun newFieldWithProgressAsksFirstAndCancelKeepsTheField() {
        val actions = RecordingActions()
        var dialogShown: Boolean? = null
        rule.setContent {
            GameBoardScreen(
                state(field(revealed = List(9) { it == 0 })), actions,
                onExit = {}, onDialogShownChange = { dialogShown = it },
            )
        }
        rule.onNodeWithText(str(R.string.new_field)).performClick()
        rule.onNodeWithText(str(R.string.confirm_new_field_title)).assertExists()
        rule.onNodeWithText(str(R.string.confirm_new_field_message)).assertExists()
        assertEquals(true, dialogShown)
        assertEquals(0, actions.restarts)

        rule.onNodeWithText(str(com.vayunmathur.library.ui.R.string.cancel)).performClick()
        rule.onNodeWithText(str(R.string.confirm_new_field_title)).assertDoesNotExist()
        assertEquals(false, dialogShown)
        assertEquals(0, actions.restarts)

        rule.onNodeWithText(str(R.string.new_field)).performClick()
        rule.onNodeWithText(str(R.string.confirm_new_field_title)).assertExists()
        // Confirm via the dialog's confirm button (last "New Field" node).
        val nodes = rule.onAllNodesWithText(str(R.string.new_field))
        nodes[nodes.fetchSemanticsNodes().lastIndex].performClick()
        assertEquals(1, actions.restarts)
    }

    @Test
    fun giveUpWithProgressAsksFirstAndConfirmEndsTheField() {
        val actions = RecordingActions()
        var exited = false
        rule.setContent {
            GameBoardScreen(
                state(field(revealed = List(9) { it == 0 })), actions,
                onExit = { exited = true },
            )
        }
        rule.onNodeWithText(str(R.string.give_up)).performClick()
        rule.onNodeWithText(str(R.string.confirm_give_up_title)).assertExists()
        rule.onNodeWithText(str(R.string.confirm_give_up_message)).assertExists()
        assertEquals(0, actions.giveUps)
        assertFalse(exited)

        val nodes = rule.onAllNodesWithText(str(R.string.give_up))
        nodes[nodes.fetchSemanticsNodes().lastIndex].performClick()
        assertEquals(1, actions.giveUps)
        assertTrue(exited)
    }

    @Test
    fun giveUpWithoutProgressExitsImmediatelyAndNewFieldRestartsSilently() {
        val actions = RecordingActions()
        var exited = false
        rule.setContent {
            GameBoardScreen(state(field(started = false)), actions, onExit = { exited = true })
        }
        rule.onNodeWithText(str(R.string.new_field)).performClick()
        assertEquals(1, actions.restarts, "an untouched field restarts without asking")
        rule.onNodeWithText(str(R.string.confirm_new_field_title)).assertDoesNotExist()

        rule.onNodeWithText(str(R.string.give_up)).performClick()
        assertEquals(1, actions.giveUps)
        assertTrue(exited, "giving up an untouched field leaves at once")
        rule.onNodeWithText(str(R.string.confirm_give_up_title)).assertDoesNotExist()
    }

    @Test
    fun modeToggleReportsDigAndFlagSelections() {
        val actions = RecordingActions()
        rule.setContent { GameBoardScreen(state(field()), actions, onExit = {}) }
        rule.onNodeWithText(str(R.string.mode_flag)).performClick()
        assertEquals(TapMode.FLAG, actions.mode)
        rule.onNodeWithText(str(R.string.mode_dig)).performClick()
        assertEquals(TapMode.DIG, actions.mode)
    }

    @Test
    fun overFlaggedCounterReadsNegative() {
        val game = field(flagged = List(9) { it == 0 || it == 1 })
        assertEquals(-1, game.minesRemaining)
        rule.setContent { GameBoardScreen(state(game), RecordingActions(), onExit = {}) }
        rule.onNodeWithText("-1", substring = true).assertExists()
    }

    @Test
    @Config(qualifiers = "w800dp-h360dp-land")
    fun landscapeBoardStillOffersControlsAndCells() {
        val game = MinesweeperGameState.empty(GameConfig(BoardSize.LARGE, Difficulty.MEDIUM))
        rule.setContent {
            GameBoardScreen(
                MinesweeperUiState(config = GameConfig(BoardSize.LARGE, Difficulty.MEDIUM), game = game),
                RecordingActions(), onExit = {},
            )
        }
        rule.onNodeWithText(str(R.string.new_field)).assertExists()
        rule.onNodeWithText(str(R.string.give_up)).assertExists()
        rule.onNodeWithContentDescription("Row 18, column 12,", substring = true).assertExists()
    }
}
