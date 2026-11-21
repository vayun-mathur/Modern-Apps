package com.vayunmathur.games.nonogram.ui

import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.games.nonogram.R
import com.vayunmathur.games.nonogram.data.GameMode
import com.vayunmathur.games.nonogram.data.NonogramGameState
import com.vayunmathur.games.nonogram.data.STARTING_HEARTS
import com.vayunmathur.games.nonogram.domain.NonogramGenerator
import com.vayunmathur.games.nonogram.platform.NonogramUiState
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * A short portrait window (portrait split-screen size): compact height, but
 * taller than wide. The side-by-side split must not engage here — it would
 * halve an already narrow width and squeeze the board into a sliver — so the
 * stacked portrait layout stays, exactly as in a full-height portrait window.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w320dp-h470dp-port")
class NonogramShortPortraitScreenTest {
    @get:Rule val rule = createComposeRule()
    private val ctx get() = ApplicationProvider.getApplicationContext<Context>()

    @Test fun shortPortraitKeepsStackedLayout() {
        val p = requireNotNull(NonogramGenerator.generateSeeded(15, 25L))
        val g = NonogramGameState(p, emptySet(), emptySet(), emptySet(), STARTING_HEARTS, GameMode.CASUAL, 25)
        rule.setContent {
            NonogramGameScreen(NonogramUiState(game = g, level = 25), NonogramScreensTest.Recording(), {}, {})
        }
        val lastCell = rule.onNodeWithContentDescription(ctx.getString(R.string.cd_cell_position, 15, 15))
            .fetchSemanticsNode().boundsInRoot
        val toggle = rule.onNodeWithText(ctx.getString(R.string.mode_fill))
            .fetchSemanticsNode().boundsInRoot
        // Stacked: the controls sit under the board, not beside it.
        assertTrue(
            toggle.top >= lastCell.bottom - 0.5f,
            "mode toggle should be under the board in short portrait: toggle=$toggle lastCell=$lastCell",
        )
        // The weighted board slot leaves the controls on screen without scrolling.
        rule.onNodeWithText(ctx.getString(R.string.mode_fill)).assertIsDisplayed()
    }
}
