package com.vayunmathur.games.nonogram.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.longClick
import androidx.test.core.app.ApplicationProvider
import android.content.Context
import com.vayunmathur.games.nonogram.R
import com.vayunmathur.games.nonogram.data.GameMode
import com.vayunmathur.games.nonogram.data.MarkMode
import com.vayunmathur.games.nonogram.data.NonogramGameState
import com.vayunmathur.games.nonogram.data.NonogramPuzzle
import com.vayunmathur.games.nonogram.data.STARTING_HEARTS
import com.vayunmathur.games.nonogram.platform.NonogramGameActions
import com.vayunmathur.games.nonogram.platform.NonogramUiState
import com.vayunmathur.games.nonogram.platform.SettingsActions
import com.vayunmathur.games.nonogram.platform.SettingsUiState
import com.vayunmathur.games.nonogram.ui.components.NonogramBoard
import com.vayunmathur.library.ui.R as UiR
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
class NonogramScreensTest {
    @get:Rule val rule = createComposeRule()
    private val ctx get() = ApplicationProvider.getApplicationContext<Context>()
    private fun str(id: Int, vararg args: Any) = if (args.isEmpty()) ctx.getString(id) else ctx.getString(id, *args)

    class Recording : NonogramGameActions {
        val taps = mutableListOf<Int>(); val crosses = mutableListOf<Int>()
        val modes = mutableListOf<MarkMode>(); val gameModes = mutableListOf<GameMode>()
        var next = 0; var restarts = 0; var retries = 0
        override fun tapCell(index: Int) { taps += index }
        override fun crossCell(index: Int) { crosses += index }
        override fun setMarkMode(mode: MarkMode) { modes += mode }
        override fun nextLevel() { next++ }
        override fun restartLevel() { restarts++ }
        override fun retryGeneration() { retries++ }
        override fun setGameMode(mode: GameMode) { gameModes += mode }
    }

    private fun puzzle(): NonogramPuzzle {
        // 3x3 cross
        return NonogramPuzzle.from(3, listOf(false,true,false, true,true,true, false,true,false))
    }
    private fun game(filled: Set<Int> = emptySet(), crossed: Set<Int> = emptySet(), revealed: Set<Int> = emptySet(), hearts: Int = STARTING_HEARTS, mode: GameMode = GameMode.CASUAL) =
        NonogramGameState(puzzle(), filled, crossed, revealed, hearts, mode, level = 2)
    private fun answer() = puzzle().solution.indices.filter { puzzle().solution[it] }.toSet()

    @Test fun loadingStateShowsBoardChrome() {
        val a = Recording()
        rule.setContent { NonogramGameScreen(NonogramUiState(generating = true), a, {}, {}) }
        rule.onNodeWithText(str(R.string.level_title, 1)).assertExists()
    }

    @Test fun generationFailedOffersRetry() {
        val a = Recording()
        rule.setContent { NonogramGameScreen(NonogramUiState(generationFailed = true), a, {}, {}) }
        rule.onNodeWithText(str(R.string.generation_failed)).assertIsDisplayed()
        rule.onNodeWithText(str(R.string.try_again)).performClick()
        assertEquals(1, a.retries, "try-again must widen the seed search, not just clear marks")
        assertEquals(0, a.restarts)
    }

    @Test fun inProgressShowsHintToggleAndRestartWithoutConfirm() {
        val a = Recording()
        rule.setContent { NonogramGameScreen(NonogramUiState(game = game(), mode = GameMode.CASUAL, markMode = MarkMode.FILL, level = 2), a, {}, {}) }
        rule.onNodeWithText(str(R.string.how_to_play)).assertExists()
        rule.onNodeWithText(str(R.string.mode_cross)).performSemanticsAction(SemanticsActions.OnClick)
        assertEquals(listOf(MarkMode.CROSS), a.modes)
        rule.onNodeWithText(str(UiR.string.restart)).performSemanticsAction(SemanticsActions.OnClick)
        assertEquals(1, a.restarts, "untouched board restarts without confirmation")
    }

    @Test fun restartWithProgressAsksConfirmation() {
        val a = Recording()
        rule.setContent { NonogramGameScreen(NonogramUiState(game = game(filled = setOf(1)), level = 2), a, {}, {}) }
        rule.onNodeWithText(str(UiR.string.restart)).performSemanticsAction(SemanticsActions.OnClick)
        rule.onNodeWithText(str(R.string.confirm_restart_title)).assertExists()
        rule.onNodeWithText(str(UiR.string.cancel)).performClick()
        assertEquals(0, a.restarts)
        rule.onNodeWithText(str(UiR.string.restart)).performSemanticsAction(SemanticsActions.OnClick)
        rule.onNodeWithText(str(R.string.confirm_restart_title)).assertExists()
        // Two Restart nodes now exist (screen + dialog); the dialog's is last.
        val nodes = rule.onAllNodes(androidx.compose.ui.test.hasText(str(UiR.string.restart)))
        nodes.fetchSemanticsNodes().size.let { assertTrue(it >= 2) }
        nodes[1].performClick()
        assertEquals(1, a.restarts)
    }

    @Test fun wonCasualShowsNextLevel() {
        val a = Recording()
        rule.setContent { NonogramGameScreen(NonogramUiState(game = game(filled = answer()), level = 2), a, {}, {}) }
        rule.onNodeWithText(str(R.string.solved)).assertExists()
        rule.onNodeWithText(str(UiR.string.next_level)).performClick()
        assertEquals(1, a.next)
    }

    @Test fun nextLevelIsDisabledWhileTheNextPuzzleGenerates() {
        val a = Recording()
        rule.setContent { NonogramGameScreen(NonogramUiState(game = game(filled = answer()), level = 3, generating = true), a, {}, {}) }
        rule.onNodeWithText(str(UiR.string.next_level)).assertIsNotEnabled().performClick()
        assertEquals(0, a.next, "a second tap while generating must not advance again")
    }

    @Test fun wonDailyShowsComeBack() {
        val a = Recording()
        rule.setContent { NonogramGameScreen(NonogramUiState(game = game(filled = answer(), mode = GameMode.DAILY), mode = GameMode.DAILY, dailyStreak = 3), a, {}, {}) }
        rule.onNodeWithText(str(R.string.daily_come_back_tomorrow)).assertExists()
    }

    @Test fun failedShowsTryAgain() {
        val a = Recording()
        rule.setContent { NonogramGameScreen(NonogramUiState(game = game(hearts = 0), level = 2), a, {}, {}) }
        rule.onNodeWithText(str(R.string.out_of_hearts)).assertExists()
        rule.onNodeWithText(str(R.string.try_again)).performClick()
        assertEquals(1, a.restarts)
    }

    @Test fun boardCellsReportDescriptionsAndTaps() {
        val taps = mutableListOf<Int>()
        rule.setContent { NonogramBoard(game = game(filled = setOf(1), crossed = setOf(0)), onTap = { taps += it }, onLongPress = {}) }
        rule.onNodeWithContentDescription(str(R.string.cd_cell, 1, 2, str(R.string.cd_filled))).assertExists()
        rule.onNodeWithContentDescription(str(R.string.cd_cell, 1, 1, str(R.string.cd_crossed))).assertExists()
        rule.onNodeWithContentDescription(str(R.string.cd_cell, 1, 3, str(R.string.cd_blank))).performClick()
        assertEquals(listOf(2), taps)
    }

    @Test fun boardTellsARevealedBlankApartFromANote() {
        rule.setContent { NonogramBoard(game = game(crossed = setOf(0), revealed = setOf(2)), onTap = {}, onLongPress = {}) }
        rule.onNodeWithContentDescription(str(R.string.cd_cell, 1, 1, str(R.string.cd_crossed))).assertExists()
        rule.onNodeWithContentDescription(str(R.string.cd_cell, 1, 3, str(R.string.cd_revealed))).assertExists()
    }

    @Test fun boardLongPressFiresCross() {
        val crosses = mutableListOf<Int>()
        rule.setContent { NonogramBoard(game = game(), onTap = {}, onLongPress = { crosses += it }) }
        rule.onNodeWithContentDescription(str(R.string.cd_cell, 2, 2, str(R.string.cd_blank)))
            .performTouchInput { longClick() }
        assertEquals(listOf(4), crosses)
    }

    @Test fun boardWithEmptyLinesRendersZeroClues() {
        val p = com.vayunmathur.games.nonogram.data.NonogramPuzzle.from(4, listOf(
            true,true,false,false, false,false,false,false, false,false,true,false, false,false,false,true))
        val g = NonogramGameState(p, emptySet(), emptySet(), emptySet(), STARTING_HEARTS, GameMode.CASUAL, 1)
        rule.setContent { NonogramBoard(game = g, onTap = {}, onLongPress = {}) }
        rule.onNodeWithContentDescription(str(R.string.cd_cell, 2, 1, str(R.string.cd_blank))).assertExists()
        rule.onNodeWithText("0").assertExists()
    }

    @Test fun boardLargeGeneratedPuzzleRenders() {
        val p = requireNotNull(com.vayunmathur.games.nonogram.domain.NonogramGenerator.generateSeeded(15, 99L))
        val g = NonogramGameState(p, setOf(0), setOf(1), emptySet(), 2, GameMode.CASUAL, 25)
        rule.setContent { NonogramBoard(game = g, onTap = {}, onLongPress = {}) }
        rule.onNodeWithContentDescription(str(R.string.cd_cell, 15, 15, str(R.string.cd_blank))).assertExists()
    }

    @Test fun settingsPageWithRealViewModelRenders() {
        val vm = com.vayunmathur.games.nonogram.platform.NonogramViewModel(
            androidx.test.core.app.ApplicationProvider.getApplicationContext<android.app.Application>())
        var backed = false
        rule.setContent { SettingsPage(vm, onBack = { backed = true }) }
        rule.onNodeWithText(str(UiR.string.settings)).assertExists()
        assertTrue(!backed)
    }

    @Test fun settingsScreenRendersAndToggles() {
        var enabled: Boolean? = null; var time: Pair<Int, Int>? = null
        val actions = object : SettingsActions {
            override fun setReminderEnabled(e: Boolean) { enabled = e }
            override fun setReminderTime(hour: Int, minute: Int) { time = hour to minute }
        }
        rule.setContent { SettingsScreen(SettingsUiState(false, 20, 0), actions, {}) }
        rule.onNodeWithText(str(UiR.string.settings)).assertExists()
        assertTrue(enabled == null && time == null)
    }
}
