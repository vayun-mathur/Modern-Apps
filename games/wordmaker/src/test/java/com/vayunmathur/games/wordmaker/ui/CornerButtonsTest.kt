package com.vayunmathur.games.wordmaker.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import com.vayunmathur.games.wordmaker.data.CrosswordData
import com.vayunmathur.games.wordmaker.platform.WordGameActions
import com.vayunmathur.games.wordmaker.platform.WordGameUiState
import com.vayunmathur.library.ui.DynamicTheme
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertTrue

/** The bonus-words and hint buttons in the bottom corners of the game screen. */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h891dp")
class CornerButtonsTest {
    @get:Rule val rule = createComposeRule()

    private fun setScreen(hintCooldownEnd: Long = 0L) {
        val state = WordGameUiState(
            crosswordData = CrosswordData.fromString("CAT\n C \n T ")!!,
            bonusWords = setOf("ACT"),
            hintCooldownEnd = hintCooldownEnd,
        )
        rule.setContent {
            DynamicTheme { WordGameScreen(state = state, actions = WordGameActions.Noop, onOpenGameCenter = {}, onOpenSettings = {}) }
        }
    }

    @Test fun bonusAndHintButtonsSitOnTheSameBaseline() {
        setScreen()
        val bonus = rule.onNodeWithContentDescription("Bonus Words").fetchSemanticsNode().boundsInRoot
        val hint = rule.onNodeWithContentDescription("Hint").fetchSemanticsNode().boundsInRoot
        // The bonus button used to float 32 dp above the hint button.
        assertTrue(abs(bonus.bottom - hint.bottom) < 1f, "bonus bottom ${bonus.bottom} vs hint bottom ${hint.bottom}")
    }

    @Test fun hintIconStaysShownDuringTheCooldown() {
        setScreen(hintCooldownEnd = System.currentTimeMillis() + 20_000)
        rule.onNodeWithContentDescription("Hint").assertIsDisplayed()
        // Merged semantics: this node is the (disabled) button carrying the icon.
        rule.onNodeWithContentDescription("Hint").assertIsNotEnabled()
    }
}
