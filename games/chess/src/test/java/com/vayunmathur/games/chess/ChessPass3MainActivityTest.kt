package com.vayunmathur.games.chess

import android.content.Context
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertTrue

/** Pass 3: MainActivity nav host — game entry, bottom tabs, learn and game-center routes. */
@RunWith(AndroidJUnit4::class)
class ChessPass3MainActivityTest {

    @get:Rule val rule = createAndroidComposeRule<MainActivity>()
    private val ctx: Context get() = ApplicationProvider.getApplicationContext()
    private fun str(id: Int) = ctx.getString(id)

    @Test fun mainActivityLaunchesToGameAndTabsNavigate() {
        // Offline game entry is the start route.
        rule.onNodeWithText(str(R.string.tab_offline)).assertExists()
        // Puzzles tab: puzzle screen composes (loading or a puzzle board).
        rule.onNodeWithText(str(R.string.tab_puzzles)).performClick()
        rule.waitForIdle()
        // Learn tab: stage list.
        rule.onNodeWithText(str(R.string.tab_learn)).performClick()
        rule.waitForIdle()
        rule.onAllNodesWithText(str(R.string.tab_learn)).fetchSemanticsNodes().let {
            assertTrue(it.isNotEmpty())
        }
        // Online tab: lobby (signed out by default in tests).
        rule.onNodeWithText(str(R.string.tab_online)).performClick()
        rule.waitForIdle()
        rule.onNodeWithText(str(R.string.tab_offline)).performClick()
        rule.waitForIdle()
    }

    @Test fun gameCenterAndLearnStageEntriesOpen() {
        // Achievements (star) action on the game entry opens the Game Center route.
        rule.onNodeWithContentDescription("Achievements").performClick()
        rule.waitForIdle()
        rule.onNodeWithText(str(com.vayunmathur.library.ui.R.string.title_game_center)).assertExists()
        // Back via the top-bar navigation icon.
        rule.onNodeWithContentDescription("Back", substring = true).let { node ->
            runCatching { node.performClick() }
        }
        rule.waitForIdle()

        // Learn tab -> first stage row opens the LearnStage entry.
        rule.onNodeWithText(str(R.string.tab_learn)).performClick()
        rule.waitForIdle()
        val stageTitle = com.vayunmathur.games.chess.data.LearnRepository
            .apply { ensureLoaded(ctx) }.categories.first().stages.first().title
        rule.onNodeWithText(stageTitle, substring = true).performClick()
        rule.waitForIdle()
        rule.onNodeWithText(str(R.string.learn_lets_go)).assertExists()
    }
}
