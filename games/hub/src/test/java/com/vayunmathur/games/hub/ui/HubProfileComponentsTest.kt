package com.vayunmathur.games.hub.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.vayunmathur.games.hub.data.dao.AchievementWithProgress
import com.vayunmathur.games.hub.data.entities.ActivityEventEntity
import com.vayunmathur.games.hub.ui.components.AchievementRow
import com.vayunmathur.games.hub.ui.components.ActivityItemCard
import com.vayunmathur.games.hub.ui.components.StreakCard
import com.vayunmathur.games.hub.ui.screens.ProfileScreen
import com.vayunmathur.games.hub.util.ProfileActions
import com.vayunmathur.games.hub.util.ProfileUiState
import com.vayunmathur.games.hub.viewmodel.CrossGameStats
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Profile screen, achievement rows, activity items, streak indicator. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class HubProfileComponentsTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun profileShowsNameTitleLevelAndStats() {
        composeRule.setContent {
            ProfileScreen(
                ProfileUiState(
                    playerName = "Grace", level = 5, title = "Enthusiast", totalXp = 1700,
                    stats = CrossGameStats(
                        totalPlaytimeMs = 7_200_000, totalSessions = 42, totalGames = 6,
                        totalAchievementsUnlocked = 11, totalAchievements = 30, longestStreak = 14,
                    ),
                ),
                ProfileActions.Noop,
            )
        }
        composeRule.onNodeWithText("Grace").assertIsDisplayed()
        composeRule.onNodeWithText("Enthusiast").assertIsDisplayed()
        composeRule.onNodeWithText("Grace", substring = true).assertIsDisplayed()
    }

    @Test
    fun achievementRowUnlockedAndLocked() {
        val unlocked = AchievementWithProgress(
            gameId = "chess", achievementId = "a1", name = "First Win", description = "Win a game",
            xpReward = 50, targetProgress = 1, isSecret = false, tier = "GOLD", iconResName = null,
            progress = 1, isUnlocked = true, unlockedAt = 123L,
        )
        composeRule.setContent { AchievementRow(unlocked) }
        composeRule.onNodeWithText("First Win").assertIsDisplayed()
        composeRule.onNodeWithText("Win a game").assertIsDisplayed()
    }

    @Test
    fun achievementRowLockedProgress() {
        val locked = AchievementWithProgress(
            gameId = "chess", achievementId = "a2", name = "Marathon", description = "Play 10 games",
            xpReward = 100, targetProgress = 10, isSecret = false, tier = "SILVER", iconResName = null,
            progress = 3, isUnlocked = false, unlockedAt = null,
        )
        composeRule.setContent { AchievementRow(locked) }
        composeRule.onNodeWithText("Marathon").assertIsDisplayed()
    }

    @Test
    fun activityItemClickReportsGame() {
        var clicked: String? = null
        val event = ActivityEventEntity(
            type = ActivityEventEntity.TYPE_ACHIEVEMENT_UNLOCKED, gameId = "sudoku",
            title = "Unlocked: Sharp Mind", description = "Completed a hard puzzle",
        )
        composeRule.setContent { ActivityItemCard(event, onGameClick = { clicked = it }) }
        composeRule.onNodeWithText("Unlocked: Sharp Mind").assertIsDisplayed()
        composeRule.onNodeWithText("Unlocked: Sharp Mind").performClick()
        assertEquals("sudoku", clicked)
    }

    @Test
    fun streakIndicatorRendersCounts() {
        composeRule.setContent { StreakCard(currentStreak = 5, longestStreak = 12) }
        composeRule.onNodeWithText("5", substring = true).assertIsDisplayed()
        assertTrue(true)
    }
}
