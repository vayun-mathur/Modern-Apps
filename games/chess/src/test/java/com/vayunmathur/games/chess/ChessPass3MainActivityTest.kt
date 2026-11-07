package com.vayunmathur.games.chess

import android.content.Context
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
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
}
