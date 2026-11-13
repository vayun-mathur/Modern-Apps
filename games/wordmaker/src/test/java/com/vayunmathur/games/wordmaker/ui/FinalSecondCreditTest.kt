package com.vayunmathur.games.wordmaker.ui

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
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
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/**
 * A competitive word entered with under a second left must count: it is credited when it is
 * submitted, not when its 800 ms letter flight lands.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h891dp")
class FinalSecondCreditTest {
    @get:Rule val rule = createComposeRule()

    @Test fun competitiveWordIsCreditedOnSubmitNotWhenItsFlightLands() {
        val credited = mutableListOf<String>()
        var timedOut = false
        val actions = object : WordGameActions {
            override fun addFoundWord(word: String) { credited += word }
            override fun onCompetitiveTimeout() { timedOut = true }
        }
        val state = WordGameUiState(
            crosswordData = CrosswordData.fromString("CAT\n C \n T ")!!,
            tapToSpell = true,
            gameMode = GameMode.COMPETITIVE,
            competitiveLevelNumber = 1,
            // A running timer, far enough out that a slow test machine cannot hit it: what
            // matters is that the credit lands within frames of the submit, well inside the
            // 800 ms the flight takes (the window in which the timer used to win).
            competitiveDeadline = System.currentTimeMillis() + 60_000,
        )
        rule.setContent {
            DynamicTheme { WordGameScreen(state = state, actions = actions, onOpenGameCenter = {}, onOpenSettings = {}) }
        }
        rule.onNodeWithText("C").performClick()
        rule.onNodeWithText("A").performClick()
        rule.onNodeWithText("T").performClick()

        rule.mainClock.autoAdvance = false
        rule.onNodeWithContentDescription("Submit").performClick()
        // A few frames - far less than the 800 ms flight.
        repeat(5) { rule.mainClock.advanceTimeByFrame() }
        assertEquals(listOf("CAT"), credited)
        assertFalse(timedOut)
    }
}
