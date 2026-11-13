package com.vayunmathur.games.wordmaker.ui

import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import com.vayunmathur.games.wordmaker.data.CrosswordData
import com.vayunmathur.games.wordmaker.data.GameMode
import com.vayunmathur.games.wordmaker.platform.WordGameActions
import com.vayunmathur.games.wordmaker.platform.WordGameUiState
import com.vayunmathur.library.ui.DynamicTheme
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The hint cooldown and the competitive countdown tick inside their own small composables now
 * (not as screen state); they must still run down and fire on their own.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h891dp")
class LocalTickersTest {
    @get:Rule val rule = createComposeRule()
    private val board = CrosswordData.fromString("CAT\n C \n T ")!!

    /** Lets real (wall-clock) time pass [untilMillis], then runs the frame clock so tickers wake. */
    private fun passWallClock(untilMillis: Long) {
        val wait = untilMillis - System.currentTimeMillis()
        if (wait > 0) Thread.sleep(wait)
        rule.mainClock.advanceTimeBy(1_500)
        rule.waitForIdle()
    }

    @Test fun hintButtonReEnablesWhenItsCooldownEnds() {
        val end = System.currentTimeMillis() + 2_000
        val state = WordGameUiState(crosswordData = board, hintCooldownEnd = end)
        rule.setContent {
            DynamicTheme { WordGameScreen(state = state, actions = WordGameActions.Noop, onOpenGameCenter = {}, onOpenSettings = {}) }
        }
        if (System.currentTimeMillis() < end - 200) rule.onNodeWithContentDescription("Hint").assertIsNotEnabled()
        passWallClock(end + 100)
        rule.onNodeWithContentDescription("Hint").assertIsEnabled()
    }

    @Test fun competitiveCountdownRunsDownAndTimesOut() {
        var timedOut = false
        val actions = object : WordGameActions { override fun onCompetitiveTimeout() { timedOut = true } }
        val deadline = System.currentTimeMillis() + 2_500
        val state = WordGameUiState(
            crosswordData = board, gameMode = GameMode.COMPETITIVE,
            competitiveLevelNumber = 1, competitiveScore = 10, competitiveDeadline = deadline,
        )
        rule.setContent {
            DynamicTheme { WordGameScreen(state = state, actions = actions, onOpenGameCenter = {}, onOpenSettings = {}) }
        }
        passWallClock(deadline + 100)
        rule.onNodeWithText("00:00").assertExists()
        assertTrue(timedOut, "the screen's timer still fires at the deadline")
    }
}
