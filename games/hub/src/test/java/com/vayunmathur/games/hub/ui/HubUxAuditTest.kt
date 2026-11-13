package com.vayunmathur.games.hub.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import com.vayunmathur.games.hub.ui.screens.GamesListScreen
import com.vayunmathur.games.hub.util.GamesListActions
import com.vayunmathur.games.hub.util.GamesListUiState
import com.vayunmathur.library.ui.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.vayunmathur.games.hub.data.dao.AchievementWithProgress
import com.vayunmathur.games.hub.data.entities.ActivityEventEntity
import com.vayunmathur.games.hub.data.entities.HubGameEntity
import com.vayunmathur.games.hub.ui.components.AchievementRow
import com.vayunmathur.games.hub.ui.components.ActivityItemCard
import com.vayunmathur.games.hub.ui.components.GameCard
import com.vayunmathur.games.hub.ui.components.StatCard
import com.vayunmathur.games.hub.ui.components.activityTypeLabel
import com.vayunmathur.games.hub.ui.components.profileTabAvatarSymbol
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.games.hub.R
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Regression tests for the games UX audit fixes (#ux-audit). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class HubUxAuditTest {

    @get:Rule
    val rule = createComposeRule()

    @Test
    fun statCardCentresValueAndLabelInTheCard() {
        rule.setContent {
            Box(Modifier.width(300.dp)) {
                StatCard(label = "Playtime", value = "12h 5m", modifier = Modifier.width(300.dp).testTag("card"))
            }
        }
        val card = rule.onNodeWithTag("card").getBoundsInRoot()
        val cardCentre = (card.left + card.right) / 2
        for (text in listOf("Playtime", "12h 5m")) {
            val b = rule.onNodeWithText(text).getBoundsInRoot()
            val centre = (b.left + b.right) / 2
            assertTrue(abs((centre - cardCentre).value) < 1f, "$text centre $centre != card centre $cardCentre")
        }
    }

    private fun game(id: String, name: String) = HubGameEntity(
        gameId = id, packageName = "com.example.$id", displayName = name,
    )

    @Test
    fun gameCardShowsAchievementCountAsTextWithoutASpinnerLikeRing() {
        rule.setContent {
            GameCard(game("chess", "Chess"), isInstalled = true, achievementProgress = 3 to 10, onClick = {}, onPlay = {})
        }
        rule.onNodeWithText("Achievements: 3/10").assertIsDisplayed()
        rule.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.ProgressBarRangeInfo))
            .assertCountEquals(0)
    }

    @Test
    fun profileTabNeverReusesASiblingTabIcon() {
        // Default profile and ordinary avatars: unchanged (person / the avatar itself).
        assertNull(profileTabAvatarSymbol(null))
        assertEquals("star", profileTabAvatarSymbol("star"))
        assertEquals("rocket", profileTabAvatarSymbol("rocket"))
        // Avatars that render the Games, Achievements or Yuu tab glyph fall back to the person icon.
        for (symbol in listOf("stadia_controller", "sports_esports", "emoji_events", "psychology")) {
            assertNull(profileTabAvatarSymbol(symbol), symbol)
        }
    }

    @Test
    fun activityAndAchievementRowsShowGameNamesNotIds() {
        // A legacy row stored before names were resolved: its title carried the id too.
        val event = ActivityEventEntity(id = 1, type = ActivityEventEntity.TYPE_SESSION_COMPLETED, gameId = "unblockjam", title = "Played unblockjam")
        val ach = AchievementWithProgress(
            gameId = "logicgate", achievementId = "a1", name = "Wired", description = "Build a gate",
            xpReward = 25, targetProgress = 1, isSecret = false, tier = "BRONZE", iconResName = null,
            progress = 0, isUnlocked = false, unlockedAt = null,
        )
        rule.setContent {
            Column {
                // No registered rows: the catalog name is used.
                ActivityItemCard(event = event)
                // A registered row's own name wins.
                AchievementRow(ach, gameNames = mapOf("logicgate" to "Logic Gates Deluxe"))
            }
        }
        rule.onNodeWithText("Unblock Jam").assertIsDisplayed()
        rule.onNodeWithText("Played Unblock Jam").assertIsDisplayed()
        rule.onNodeWithText("Logic Gates Deluxe").assertIsDisplayed()
        rule.onAllNodesWithText("unblockjam", substring = true).assertCountEquals(0)
        rule.onAllNodesWithText("logicgate").assertCountEquals(0)
    }

    private fun achievement(id: String, unlocked: Boolean) = AchievementWithProgress(
        gameId = "chess", achievementId = id, name = "Ach $id", description = "Do $id",
        xpReward = 25, targetProgress = 1, isSecret = false, tier = "BRONZE", iconResName = null,
        progress = if (unlocked) 1 else 0, isUnlocked = unlocked, unlockedAt = if (unlocked) 1L else null,
    )

    @Test
    fun activityTypesHaveSpokenLabelsNotConstants() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val types = listOf(
            ActivityEventEntity.TYPE_ACHIEVEMENT_UNLOCKED, ActivityEventEntity.TYPE_SESSION_COMPLETED,
            ActivityEventEntity.TYPE_LEVEL_UP, ActivityEventEntity.TYPE_GAME_REGISTERED,
            ActivityEventEntity.TYPE_FIRST_PLAY, ActivityEventEntity.TYPE_GAME_LAUNCHED,
        )
        val labels = types.map { context.getString(activityTypeLabel(it)) }
        assertEquals(types.size, labels.toSet().size, "each known type gets its own label: $labels")
        labels.forEach { assertTrue(it.none { c -> c == '_' }, "label looks like a constant: $it") }
        assertEquals(context.getString(R.string.activity_type_other), context.getString(activityTypeLabel("SOMETHING_NEW")))

        val event = ActivityEventEntity(id = 1, type = ActivityEventEntity.TYPE_ACHIEVEMENT_UNLOCKED, gameId = "chess", title = "First Win")
        rule.setContent { ActivityItemCard(event = event) }
        rule.onNodeWithContentDescription("Achievement unlocked", useUnmergedTree = true).assertExists()
        rule.onAllNodesWithContentDescription(ActivityEventEntity.TYPE_ACHIEVEMENT_UNLOCKED, useUnmergedTree = true)
            .assertCountEquals(0)
    }

    @Test
    fun achievementRowStateIsNotColourOnly() {
        rule.setContent {
            Column {
                AchievementRow(achievement("u", unlocked = true))
                AchievementRow(achievement("l", unlocked = false))
            }
        }
        // Unlocked: the star icon speaks its state; no "Locked" anywhere on that row.
        rule.onNodeWithContentDescription("Unlocked", useUnmergedTree = true).assertExists()
        // Locked: a visible "Locked" label (once), and the lock icon does not repeat it to TalkBack.
        rule.onAllNodesWithText("Locked").assertCountEquals(1)
        rule.onNodeWithText("Locked").assertIsDisplayed()
        // Merged tree = what TalkBack sees (clearAndSetSemantics hides the icon there).
        rule.onAllNodesWithContentDescription("Locked").assertCountEquals(0)
        rule.onAllNodesWithContentDescription("Lock").assertCountEquals(0)
    }

    @Test
    fun gamesListSortSurvivesSwipingAwayAndBack() {
        // Last played puts Sudoku first; Name puts Chess first.
        val games = listOf(
            game("chess", "Chess").copy(lastPlayedAt = 100L),
            game("sudoku", "Sudoku").copy(lastPlayedAt = 200L),
        )
        var page by mutableIntStateOf(0)
        rule.setContent {
            val pager = rememberPagerState(pageCount = { 3 })
            LaunchedEffect(page) { pager.scrollToPage(page) }
            HorizontalPager(pager) { p ->
                if (p == 0) GamesListScreen(GamesListUiState(games = games), GamesListActions.Noop) else Text("Tab $p")
            }
        }
        fun chessAboveSudoku() =
            rule.onNodeWithText("Chess").getBoundsInRoot().top < rule.onNodeWithText("Sudoku").getBoundsInRoot().top
        assertTrue(!chessAboveSudoku(), "default sort is last played")
        rule.onNodeWithContentDescription("More").performClick()
        rule.onNodeWithText("Name").performClick()
        rule.waitForIdle()
        assertTrue(chessAboveSudoku(), "sorted by name")

        page = 2 // two tabs away: the pager disposes the games list page
        rule.waitForIdle()
        rule.onNodeWithText("Tab 2").assertIsDisplayed()
        rule.onAllNodesWithText("Chess").assertCountEquals(0)

        page = 0
        rule.waitForIdle()
        assertTrue(chessAboveSudoku(), "sort order survived the swipe")
    }
}
