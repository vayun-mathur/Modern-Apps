package com.vayunmathur.games.nonogram.ui

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performSemanticsAction
import androidx.test.core.app.ApplicationProvider
import android.content.Context
import com.vayunmathur.games.nonogram.R
import com.vayunmathur.games.nonogram.data.GameMode
import com.vayunmathur.games.nonogram.data.MarkMode
import com.vayunmathur.games.nonogram.data.NonogramGameState
import com.vayunmathur.games.nonogram.data.NonogramPuzzle
import com.vayunmathur.games.nonogram.data.STARTING_HEARTS
import com.vayunmathur.games.nonogram.platform.NonogramUiState
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.Test
import kotlin.test.assertEquals

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w1280dp-h900dp")
class NonogramExpandedScreenTest {
    @get:Rule val rule = createComposeRule()
    private val ctx get() = ApplicationProvider.getApplicationContext<Context>()
    private fun str(id: Int, vararg args: Any) = if (args.isEmpty()) ctx.getString(id) else ctx.getString(id, *args)

    @Test fun expandedLayoutShowsSidePanelControls() {
        val a = NonogramScreensTest.Recording()
        val puzzle = NonogramPuzzle.from(3, listOf(false,true,false, true,true,true, false,true,false))
        val game = NonogramGameState(puzzle, emptySet(), emptySet(), emptySet(), STARTING_HEARTS, GameMode.CASUAL, 2)
        rule.setContent {
            NonogramGameScreen(NonogramUiState(game = game, level = 2, markMode = MarkMode.FILL), a, {}, {})
        }
        rule.onNodeWithText(str(R.string.how_to_play)).assertExists()
        rule.onNodeWithText(str(R.string.mode_cross)).performSemanticsAction(SemanticsActions.OnClick)
        assertEquals(listOf(MarkMode.CROSS), a.modes)
    }

    @Test fun expandedDailyInProgressShowsControls() {
        val a = NonogramScreensTest.Recording()
        val puzzle = NonogramPuzzle.from(3, listOf(true,false,true, false,true,false, true,false,true))
        val game = NonogramGameState(puzzle, setOf(0), emptySet(), emptySet(), 2, GameMode.DAILY, 1)
        rule.setContent {
            NonogramGameScreen(NonogramUiState(game = game, mode = GameMode.DAILY, dailyStreak = 4), a, {}, {})
        }
        rule.onNodeWithText(str(R.string.mode_daily)).assertExists()
    }
}
