package com.vayunmathur.games.nonogram.ui

import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.games.nonogram.R
import com.vayunmathur.games.nonogram.data.GameMode
import com.vayunmathur.games.nonogram.data.NonogramGameState
import com.vayunmathur.games.nonogram.data.STARTING_HEARTS
import com.vayunmathur.games.nonogram.domain.NonogramGenerator
import com.vayunmathur.games.nonogram.platform.NonogramUiState
import com.vayunmathur.library.ui.R as UiR
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** A landscape phone: medium width, compact height — the case that used to overflow. */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w800dp-h360dp-land")
class NonogramLandscapeScreenTest {
    @get:Rule val rule = createComposeRule()
    private val ctx get() = ApplicationProvider.getApplicationContext<Context>()

    private fun solved15(): NonogramGameState {
        val p = requireNotNull(NonogramGenerator.generateSeeded(15, 25L))
        val answer = p.solution.indices.filter { p.solution[it] }.toSet()
        return NonogramGameState(p, answer, emptySet(), emptySet(), STARTING_HEARTS, GameMode.CASUAL, 25)
    }

    @Test fun solvedBoardKeepsNextLevelOnScreen() {
        val a = NonogramScreensTest.Recording()
        rule.setContent { NonogramGameScreen(NonogramUiState(game = solved15(), level = 25), a, {}, {}) }
        rule.onNodeWithText(ctx.getString(UiR.string.next_level)).assertIsDisplayed().performClick()
        assertEquals(1, a.next)
    }

    @Test fun boardFitsTheWindowHeight() {
        val g = solved15().copy(filled = emptySet())
        rule.setContent { NonogramGameScreen(NonogramUiState(game = g, level = 25), NonogramScreensTest.Recording(), {}, {}) }
        val root = rule.onRoot().fetchSemanticsNode().boundsInRoot
        val lastCell = rule.onNodeWithContentDescription(ctx.getString(R.string.cd_cell_position, 15, 15))
            .fetchSemanticsNode().boundsInRoot
        assertTrue(lastCell.bottom <= root.bottom + 0.5f, "bottom row at ${lastCell.bottom} overflows ${root.bottom}")
        assertTrue(lastCell.right <= root.right + 0.5f)
        // Side panel: the controls sit beside the board, not under it.
        val toggle = rule.onNodeWithText(ctx.getString(R.string.mode_fill)).fetchSemanticsNode().boundsInRoot
        assertTrue(toggle.left >= lastCell.right, "mode toggle should be in the side panel")
    }
}
