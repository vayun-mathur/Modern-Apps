package com.vayunmathur.games.arrows.ui

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.width
import androidx.compose.ui.unit.height
import com.vayunmathur.games.arrows.ui.components.BOARD_TEST_TAG
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.games.arrows.R
import com.vayunmathur.games.arrows.data.ArrowPiece
import com.vayunmathur.games.arrows.data.ArrowsGameState
import com.vayunmathur.games.arrows.data.ArrowsPuzzle
import com.vayunmathur.games.arrows.data.Direction
import com.vayunmathur.games.arrows.data.GameMode
import com.vayunmathur.games.arrows.data.STARTING_HEARTS
import com.vayunmathur.games.arrows.platform.ArrowsGameActions
import com.vayunmathur.games.arrows.platform.ArrowsUiState
import com.vayunmathur.games.arrows.platform.SettingsActions
import com.vayunmathur.games.arrows.platform.SettingsUiState
import com.vayunmathur.library.ui.R as UiR
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
class ArrowsScreensTest {
    @get:Rule val rule = createComposeRule()
    private val ctx get() = ApplicationProvider.getApplicationContext<Context>()
    private fun str(id: Int) = ctx.getString(id)

    private class RecordingActions : ArrowsGameActions {
        val tapped = mutableListOf<Int>()
        var commits = 0
        var nexts = 0
        var restarts = 0
        var retries = 0
        val modes = mutableListOf<GameMode>()
        override fun tapArrow(pieceId: Int) { tapped += pieceId }
        override fun finishMove(moveId: Long) { commits++ }
        override fun nextLevel() { nexts++ }
        override fun restartLevel() { restarts++ }
        override fun retryGeneration() { retries++ }
        override fun setGameMode(mode: GameMode) { modes += mode }
        var hintDismissals = 0
        override fun dismissRedirectorHint() { hintDismissals++ }
    }

    private class RecordingSettings : SettingsActions {
        val routes = mutableListOf<Boolean>()
        val reminders = mutableListOf<Boolean>()
        val times = mutableListOf<Pair<Int, Int>>()
        override fun setShowRoutes(enabled: Boolean) { routes += enabled }
        override fun setReminderEnabled(enabled: Boolean) { reminders += enabled }
        override fun setReminderTime(hour: Int, minute: Int) { times += hour to minute }
    }

    private fun at(r: Int, c: Int, cols: Int = 5) = r * cols + c

    private fun game(
        removed: Set<Int> = emptySet(),
        hearts: Int = STARTING_HEARTS,
        mode: GameMode = GameMode.CASUAL,
        blockedId: Int = -1,
    ): ArrowsGameState {
        val pieces = listOf(
            ArrowPiece(0, listOf(at(2, 1), at(2, 2)), Direction.RIGHT),
            ArrowPiece(1, listOf(at(0, 4), at(1, 4)), Direction.DOWN),
        )
        return ArrowsGameState(ArrowsPuzzle(5, 5, pieces), removed, hearts, 3, mode, blockedId)
    }

    @Test
    fun loadingStateShowsBoardAreaWithoutGameButtons() {
        rule.setContent {
            ArrowsGameScreen(ArrowsUiState(generating = true), RecordingActions(), {}, {})
        }
        // Mode chooser title still present (casual label folds in the level)
        rule.onNodeWithText("Level 1", substring = true).assertExists()
        rule.onNodeWithText(str(R.string.how_to_play)).assertDoesNotExist()
    }

    @Test
    fun generationFailedShowsErrorAndRetry() {
        val actions = RecordingActions()
        rule.setContent {
            ArrowsGameScreen(ArrowsUiState(generationFailed = true), actions, {}, {})
        }
        rule.onNodeWithText(str(R.string.generation_failed)).assertIsDisplayed()
        rule.onNodeWithText(str(R.string.try_again)).performClick()
        // Retrying generation, not restarting: a restart only resets progress and
        // could never bring back a board that failed to generate.
        assertEquals(1, actions.retries)
        assertEquals(0, actions.restarts)
    }

    @Test
    fun freshBoardShowsHowToPlayAndRestartDirectly() {
        val actions = RecordingActions()
        rule.setContent {
            ArrowsGameScreen(ArrowsUiState(game = game(), level = 3), actions, {}, {})
        }
        rule.onNodeWithText(str(R.string.how_to_play)).assertIsDisplayed()
        // Restart label from library ui (same text "Restart")
        rule.onNodeWithText("Restart").performClick()
        assertEquals(1, actions.restarts)
    }

    @Test
    fun boardWithProgressRestartAsksForConfirmation() {
        val actions = RecordingActions()
        rule.setContent {
            ArrowsGameScreen(
                ArrowsUiState(game = game(removed = setOf(0)), level = 3),
                actions, {}, {},
            )
        }
        rule.onNodeWithText(str(R.string.how_to_play)).assertDoesNotExist()
        rule.onNodeWithText("Restart").performClick()
        // Confirm dialog appears, no restart yet
        assertEquals(0, actions.restarts)
        rule.onNodeWithText(str(R.string.confirm_restart_title)).assertIsDisplayed()
        rule.onNodeWithText(str(R.string.confirm_restart_message)).assertIsDisplayed()
        // Cancel dismisses
        rule.onNodeWithText("Cancel").performClick()
        rule.onNodeWithText(str(R.string.confirm_restart_title)).assertDoesNotExist()
        assertEquals(0, actions.restarts)
        // Again and confirm
        rule.onNodeWithText("Restart").performClick()
        // The dialog confirm button is also "Restart"; clicking dialog's confirm routes restart.
        // There are now dialog buttons; perform click on the last Restart node via onAllNodes
        val nodes = rule.onAllNodes(androidx.compose.ui.test.hasText("Restart"))
        nodes[nodes.fetchSemanticsNodes().size - 1].performClick()
        assertEquals(1, actions.restarts)
    }

    @Test
    fun wonCasualShowsClearedAndNextLevel() {
        val actions = RecordingActions()
        rule.setContent {
            ArrowsGameScreen(
                ArrowsUiState(game = game(removed = setOf(0, 1)), level = 3),
                actions, {}, {},
            )
        }
        rule.onNodeWithText(str(R.string.board_cleared)).assertIsDisplayed()
        rule.onNodeWithText("Next Level").performClick()
        assertEquals(1, actions.nexts)
    }

    @Test
    fun wonDailyShowsComeBackTomorrowAndNoNextLevel() {
        val actions = RecordingActions()
        rule.setContent {
            ArrowsGameScreen(
                ArrowsUiState(
                    game = game(removed = setOf(0, 1), mode = GameMode.DAILY),
                    mode = GameMode.DAILY, dailyStreak = 4, dailyDone = true,
                ),
                actions, {}, {},
            )
        }
        rule.onNodeWithText(str(R.string.board_cleared)).assertIsDisplayed()
        rule.onNodeWithText(str(R.string.daily_come_back_tomorrow)).assertExists()
        rule.onNodeWithText("Next Level").assertDoesNotExist()
        assertEquals(0, actions.nexts)
    }

    @Test
    fun failedBoardShowsOutOfHeartsAndRetry() {
        val actions = RecordingActions()
        rule.setContent {
            ArrowsGameScreen(
                ArrowsUiState(game = game(hearts = 0), level = 3),
                actions, {}, {},
            )
        }
        rule.onNodeWithText(str(R.string.out_of_hearts)).assertIsDisplayed()
        rule.onNodeWithText(str(R.string.try_again)).performClick()
        assertEquals(1, actions.restarts)
    }

    @Test
    fun settingsScreenTogglesShowRoutes() {
        val actions = RecordingSettings()
        var back = 0
        rule.setContent {
            SettingsScreen(SettingsUiState(showRoutes = true), actions, onBack = { back++ })
        }
        rule.onNodeWithText(str(R.string.show_routes)).assertIsDisplayed()
        rule.onNodeWithText(str(R.string.show_routes_description)).assertIsDisplayed()
        rule.onNodeWithText(str(R.string.show_routes)).performClick()
        assertEquals(listOf(false), actions.routes)
        assertEquals(0, back)
    }

    @Test
    fun settingsScreenWithRoutesOffToggleTurnsOn() {
        val actions = RecordingSettings()
        rule.setContent {
            SettingsScreen(
                SettingsUiState(showRoutes = false, reminderEnabled = true, reminderHour = 9, reminderMinute = 15),
                actions, onBack = {},
            )
        }
        rule.onNodeWithText(str(R.string.show_routes)).performClick()
        assertEquals(listOf(true), actions.routes)
    }

    @Test
    fun nextLevelButtonIsDisabledWhileTheNextBoardLoads() {
        val won = game(removed = setOf(0, 1))
        var state by mutableStateOf(ArrowsUiState(game = won, level = 3))
        rule.setContent { ArrowsGameScreen(state, RecordingActions(), {}, {}) }
        rule.onNodeWithText(str(UiR.string.next_level)).assertIsEnabled()
        // Tapped: the level is requested and the cleared board stays up while the next generates.
        state = state.copy(level = 4, generating = true)
        rule.onNodeWithText(str(UiR.string.next_level)).assertIsNotEnabled()
    }

    @Test
    @Config(qualifiers = "w900dp-h420dp")
    fun tallBoardFitsALandscapePhoneWithoutScrolling() {
        // The largest ladder board, 9 x 12, with an arrow on the bottom row.
        val cols = 9
        val big = ArrowsGameState(
            ArrowsPuzzle(cols, 12, listOf(ArrowPiece(0, listOf(at(11, 0, cols), at(11, 1, cols)), Direction.RIGHT))),
            emptySet(), STARTING_HEARTS, 70, GameMode.CASUAL,
        )
        rule.setContent {
            Box(Modifier.requiredSize(800.dp, 360.dp).testTag("frame")) {
                ArrowsGameScreen(ArrowsUiState(game = big, level = 70), RecordingActions(), {}, {})
            }
        }
        // Measured against the frame, which Robolectric's smaller default screen may offset.
        val frame = rule.onNodeWithTag("frame").getBoundsInRoot()
        val board = rule.onNodeWithTag(BOARD_TEST_TAG).getBoundsInRoot()
        assertTrue(board.bottom <= frame.bottom, "board must fit the height: $board in $frame")
        // Square cells: the board keeps its 9:12 shape rather than being squashed to fit.
        assertEquals((board.width / cols).value, (board.height / 12).value, 0.5f)
        // The status sits beside the board, still on screen.
        rule.onNodeWithText(str(UiR.string.restart)).assertIsDisplayed()
        val restart = rule.onNodeWithText(str(UiR.string.restart)).getBoundsInRoot()
        assertTrue(restart.left >= board.right, "landscape status belongs beside the board")
    }

    @Test
    @Config(qualifiers = "w420dp-h900dp")
    fun portraitBoardStillStacksAboveTheStatus() {
        rule.setContent {
            Box(Modifier.requiredSize(400.dp, 800.dp)) {
                ArrowsGameScreen(ArrowsUiState(game = game(), level = 3), RecordingActions(), {}, {})
            }
        }
        val board = rule.onNodeWithTag(BOARD_TEST_TAG).getBoundsInRoot()
        val restart = rule.onNodeWithText(str(UiR.string.restart)).getBoundsInRoot()
        assertTrue(restart.top >= board.bottom)
    }

    @Test
    fun redirectorHintShowsOnAMirrorBoardUntilDismissed() {
        val actions = RecordingActions()
        val withMirror = game().let {
            it.copy(puzzle = it.puzzle.copy(mirrors = mapOf(at(4, 0) to com.vayunmathur.games.arrows.data.Mirror.BACK)))
        }
        var state by mutableStateOf(ArrowsUiState(game = withMirror, redirectorHintSeen = false))
        rule.setContent { ArrowsGameScreen(state, actions, {}, {}) }
        rule.onNodeWithText(str(R.string.redirector_hint)).assertIsDisplayed()
        rule.onNodeWithText(str(R.string.got_it)).performClick()
        assertEquals(1, actions.hintDismissals)
        state = state.copy(redirectorHintSeen = true)
        rule.onNodeWithText(str(R.string.redirector_hint)).assertDoesNotExist()
        // Never on a board without redirectors.
        state = state.copy(game = game(), redirectorHintSeen = false)
        rule.onNodeWithText(str(R.string.redirector_hint)).assertDoesNotExist()
    }

    @Test
    fun heartHintExplainsTheLostHeart() {
        rule.setContent {
            ArrowsGameScreen(ArrowsUiState(game = game(hearts = 2, blockedId = 0), showHeartHint = true), RecordingActions(), {}, {})
        }
        rule.onNodeWithText(ctx.getString(R.string.heart_hint, STARTING_HEARTS)).assertIsDisplayed()
    }
}
