package com.vayunmathur.games.hub.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.games.hub.R
import com.vayunmathur.games.hub.data.entities.HubGameEntity
import com.vayunmathur.games.hub.ui.components.GameCard
import com.vayunmathur.games.hub.ui.components.LevelBadge
import com.vayunmathur.games.hub.ui.components.StatCard
import com.vayunmathur.games.hub.ui.components.XpProgressBar
import com.vayunmathur.games.hub.ui.screens.DashboardScreen
import com.vayunmathur.games.hub.ui.screens.GamesListScreen
import com.vayunmathur.games.hub.util.DashboardActions
import com.vayunmathur.games.hub.util.DashboardUiState
import com.vayunmathur.games.hub.util.GamesListActions
import com.vayunmathur.games.hub.util.GamesListUiState
import com.vayunmathur.games.hub.viewmodel.CrossGameStats
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Dashboard, games list and shared hub components. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class HubScreensTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private fun str(id: Int): String = context.getString(id)

    private fun game(id: String, name: String) = HubGameEntity(
        gameId = id, packageName = "com.example.$id", displayName = name,
        totalPlaytimeMs = 3_600_000, totalSessions = 3,
    )

    @Test
    fun dashboardShowsPlayerLevelStatsAndRecentGame() {
        var profileOpened = false
        val actions = object : DashboardActions {
            override fun openProfile() { profileOpened = true }
        }
        composeRule.setContent {
            DashboardScreen(
                DashboardUiState(
                    playerName = "Ada", level = 3, title = "Casual Gamer", totalXp = 450,
                    stats = CrossGameStats(
                        totalPlaytimeMs = 5_400_000, totalGames = 4,
                        totalAchievementsUnlocked = 7, totalAchievements = 20,
                        currentStreak = 3, longestStreak = 9,
                    ),
                    recentlyPlayed = listOf(game("chess", "Chess")),
                ),
                actions,
            )
        }
        composeRule.onNodeWithText("Ada").assertIsDisplayed()
        composeRule.onNodeWithText("Casual Gamer").assertIsDisplayed()
        composeRule.onNodeWithText("1h 30m").assertIsDisplayed()
        composeRule.onNodeWithText("7/20").assertIsDisplayed()
        composeRule.onNodeWithText("Ada").performClick()
        assertTrue(profileOpened)
    }

    @Test
    fun dashboardAnonymousPlayerFallback() {
        composeRule.setContent {
            DashboardScreen(DashboardUiState(), DashboardActions.Noop)
        }
        composeRule.onNodeWithText(str(R.string.player)).assertIsDisplayed()
        composeRule.onNodeWithText("Beginner").assertIsDisplayed()
    }

    @Test
    fun gamesListShowsGamesAndOpensOnClick() {
        var opened: String? = null
        val actions = object : GamesListActions {
            override fun openGame(gameId: String) { opened = gameId }
        }
        composeRule.setContent {
            GamesListScreen(
                GamesListUiState(
                    games = listOf(game("chess", "Chess"), game("sudoku", "Sudoku")),
                    installedGameIds = setOf("chess"),
                ),
                actions,
            )
        }
        composeRule.onNodeWithText("Chess").assertIsDisplayed()
        composeRule.onNodeWithText("Chess").performClick()
        assertEquals("chess", opened)
    }

    @Test
    fun gameCardStatCardAndXpBarRender() {
        var clicked = false
        composeRule.setContent {
            GameCard(game("chess", "Chess"), isInstalled = true, onClick = { clicked = true }, onPlay = {})
            StatCard(label = "Playtime", value = "2h 0m")
            XpProgressBar(totalXp = 250)
            LevelBadge(level = 4)
        }
        composeRule.onNodeWithText("Chess").assertIsDisplayed()
        composeRule.onNodeWithText("Playtime").assertIsDisplayed()
        composeRule.onNodeWithText("2h 0m").assertIsDisplayed()
        composeRule.onNodeWithText("Chess").performClick()
        assertTrue(clicked)
    }
}
