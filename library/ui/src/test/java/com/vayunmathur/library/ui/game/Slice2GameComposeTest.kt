package com.vayunmathur.library.ui.game

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.library.ui.R as UiR
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Slice 2/3 (library/ui): the game chrome — hearts, streak, info boxes, mode
 * chooser, result overlay, challenge card and top-bar actions.
 */
@RunWith(RobolectricTestRunner::class)
class Slice2GameComposeTest {

    @get:Rule
    val rule = createComposeRule()

    private val appContext: android.content.Context
        get() = ApplicationProvider.getApplicationContext()

    // --- HeartsRow ----------------------------------------------------------

    @Test
    fun `hearts announce the remaining count as one description`() {
        rule.setContent { HeartsRow(remaining = 2, total = 3) }
        val desc = appContext.resources.getQuantityString(UiR.plurals.game_hearts_left, 2, 2)
        val nodes = rule.onAllNodes(hasContentDescription(desc)).fetchSemanticsNodes()
        assertEquals(1, nodes.size, "the row carries one merged description")
    }

    @Test
    fun `remaining clamps to the row`() {
        var remaining by mutableStateOf(99)
        rule.setContent { HeartsRow(remaining = remaining, total = 2) }
        val desc = appContext.resources.getQuantityString(UiR.plurals.game_hearts_left, 2, 2)
        assertTrue(
            rule.onAllNodes(hasContentDescription(desc)).fetchSemanticsNodes().isNotEmpty(),
        )
        remaining = -5
        rule.waitForIdle()
        val zero = appContext.resources.getQuantityString(UiR.plurals.game_hearts_left, 0, 0)
        assertTrue(
            rule.onAllNodes(hasContentDescription(zero)).fetchSemanticsNodes().isNotEmpty(),
        )
    }

    // --- DailyStreakText ------------------------------------------------------

    @Test
    fun `streak text pluralises`() {
        var streak by mutableStateOf(1L)
        rule.setContent { DailyStreakText(streak = streak) }
        val one = appContext.resources.getQuantityString(UiR.plurals.game_daily_streak, 1, 1)
        rule.onNodeWithText(one).assertIsDisplayed()
        streak = 5L
        rule.waitForIdle()
        val five = appContext.resources.getQuantityString(UiR.plurals.game_daily_streak, 5, 5)
        rule.onNodeWithText(five).assertIsDisplayed()
    }

    // --- GameInfoBox / LevelPickerBox / MovesBox -------------------------------

    @Test
    fun `info box shows its title and content`() {
        rule.setContent {
            GameInfoBox(title = "Slice2 Level") {
                androidx.compose.foundation.layout.Box {}
            }
        }
        rule.onNodeWithText("Slice2 Level").assertIsDisplayed()
    }

    @Test
    fun `level picker steps and gates its arrows`() {
        var level = 1
        rule.setContent {
            LevelPickerBox(levelIndex = level, maxLevelIndex = 2, isCompleted = true, onLevelChange = { level = it })
        }
        rule.onNodeWithText("2").assertIsDisplayed()
        rule.onNodeWithText(appContext.getString(UiR.string.game_completed)).assertIsDisplayed()
        assertEquals(1, level)
    }

    @Test
    fun `moves box dashes an unknown best`() {
        var bestScore: Int? by mutableStateOf<Int?>(null)
        var moves by mutableStateOf(12)
        rule.setContent { MovesBox(moves = moves, bestScore = bestScore, optimalMoves = 10) }
        rule.onNodeWithText("12").assertIsDisplayed()
        rule.onNodeWithText("Best – · Par 10").assertIsDisplayed()
        moves = 9
        bestScore = 8
        rule.waitForIdle()
        rule.onNodeWithText("Best 8 · Par 10").assertIsDisplayed()
    }

    @Test
    fun `moves box labels best and par for TalkBack too`() {
        var bestScore: Int? by mutableStateOf<Int?>(null)
        rule.setContent { MovesBox(moves = 3, bestScore = bestScore, optimalMoves = 10) }
        rule.onNode(hasContentDescription("No best yet, par 10")).assertExists()
        bestScore = 12
        rule.waitForIdle()
        rule.onNodeWithText("Best 12 · Par 10").assertIsDisplayed()
        rule.onNode(hasContentDescription("Best 12 moves, par 10")).assertExists()
        bestScore = 1
        rule.waitForIdle()
        rule.onNode(hasContentDescription("Best 1 move, par 10")).assertExists()
    }

    // --- GameModeChooser ---------------------------------------------------------

    @Test
    fun `chooser shows the selection and picks another`() {
        var selected = "Easy"
        rule.setContent {
            GameModeChooser(
                selected = selected,
                options = listOf("Easy", "Hard"),
                label = { "Mode: $it" },
                menuLabel = { it },
                onSelect = { selected = it },
            )
        }
        rule.onNodeWithText("Mode: Easy").assertIsDisplayed()
        rule.onNodeWithText("Mode: Easy").performClick()
        rule.onNodeWithText("Hard").assertIsDisplayed()
        rule.onNodeWithText("Hard").performClick()
        assertEquals("Hard", selected)
    }

    // --- GameResultOverlay -----------------------------------------------------------

    @Test
    fun `result overlay shows title stats and both actions`() {
        var back = 0
        var again = 0
        rule.setContent {
            GameResultOverlay(
                title = "Slice2 Solved",
                won = true,
                onBack = { back++ },
                onPlayAgain = { again++ },
                stats = { androidx.compose.material3.Text("12 moves") },
            )
        }
        rule.onNodeWithText("Slice2 Solved").assertIsDisplayed()
        rule.onNodeWithText("12 moves").assertIsDisplayed()
        rule.onNodeWithText(appContext.getString(UiR.string.game_play_again)).performClick()
        rule.onNodeWithText(appContext.getString(UiR.string.game_back)).performClick()
        assertEquals(1, again)
        assertEquals(1, back)
    }

    @Test
    fun `a daily board hides play-again`() {
        rule.setContent {
            GameResultOverlay(title = "Daily", won = false, onBack = {}, onPlayAgain = null)
        }
        rule.onNodeWithText("Daily").assertIsDisplayed()
        assertTrue(
            rule.onAllNodes(hasText(appContext.getString(UiR.string.game_play_again)))
                .fetchSemanticsNodes().isEmpty(),
        )
    }

    // --- DailyChallengeCard ---------------------------------------------------------------

    @Test
    fun `challenge card opens on tap`() {
        var opened = 0
        rule.setContent {
            DailyChallengeCard(day = 20000L, completed = 2, total = 5, streak = 3L, onOpen = { opened++ })
        }
        rule.onNodeWithText(appContext.getString(UiR.string.game_daily_challenge)).assertIsDisplayed()
        rule.onNodeWithText("2/5").assertIsDisplayed()
        rule.onNodeWithText("2/5").performClick()
        assertEquals(1, opened)
    }
}
