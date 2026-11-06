package com.vayunmathur.library.ui.game

import androidx.compose.foundation.layout.Row
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.library.ui.R as UiR
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Slice 2/3 (library/ui): the game chrome interactions — top-bar actions and
 * the level picker's stepping arrows.
 */
@RunWith(RobolectricTestRunner::class)
class Slice2GameChromeTest {

    @get:Rule
    val rule = createComposeRule()

    private val appContext: android.content.Context
        get() = ApplicationProvider.getApplicationContext()

    // --- GameTopBarActions ----------------------------------------------------

    @Test
    fun `achievements action is always present and fires`() {
        var opened = 0
        rule.setContent {
            Row { GameTopBarActions(onOpenGameCenter = { opened++ }) }
        }
        rule.onNodeWithContentDescription("Badges").assertIsDisplayed()
        rule.onNodeWithContentDescription("Badges").performClick()
        assertEquals(1, opened)
    }

    @Test
    fun `settings action shows only when its handler is given`() {
        var opened = 0
        rule.setContent {
            Row { GameTopBarActions(onOpenGameCenter = {}, onOpenSettings = { opened++ }) }
        }
        rule.onNodeWithContentDescription("Settings").assertIsDisplayed()
        rule.onNodeWithContentDescription("Settings").performClick()
        assertEquals(1, opened)
    }

    @Test
    fun `no settings action without its handler`() {
        rule.setContent {
            Row { GameTopBarActions(onOpenGameCenter = {}) }
        }
        rule.onNodeWithContentDescription("Badges").assertIsDisplayed()
        assertEquals(
            0,
            rule.onAllNodes(hasContentDescription("Settings")).fetchSemanticsNodes().size,
        )
    }

    // --- LevelPickerBox arrows --------------------------------------------------

    @Test
    fun `forward steps up and back steps down`() {
        val stepped = mutableListOf<Int>()
        rule.setContent {
            LevelPickerBox(levelIndex = 1, maxLevelIndex = 2, isCompleted = false, onLevelChange = stepped::add)
        }
        rule.onNodeWithContentDescription("Forward").assertIsEnabled()
        rule.onNodeWithContentDescription("Forward").performClick()
        rule.onNodeWithContentDescription("Back").performClick()
        assertEquals(listOf(2, 0), stepped)
    }

    @Test
    fun `arrows gate at the ends of the pack`() {
        val stepped = mutableListOf<Int>()
        rule.setContent {
            LevelPickerBox(levelIndex = 0, maxLevelIndex = 0, isCompleted = false, onLevelChange = stepped::add)
        }
        rule.onNodeWithContentDescription("Back").assertIsNotEnabled()
        rule.onNodeWithContentDescription("Forward").assertIsNotEnabled()
        assertEquals(emptyList(), stepped)
    }

    @Test
    fun `level number reads one higher than its index`() {
        rule.setContent {
            LevelPickerBox(levelIndex = 0, maxLevelIndex = 2, isCompleted = false, onLevelChange = {})
        }
        rule.onNodeWithText("1").assertIsDisplayed()
    }

    // --- DailyStreakText edge -----------------------------------------------------

    @Test
    fun `a negative streak clamps to zero`() {
        rule.setContent { DailyStreakText(streak = -5L) }
        val zero = appContext.resources.getQuantityString(UiR.plurals.game_daily_streak, 0, 0)
        rule.onNodeWithText(zero).assertIsDisplayed()
    }
}
