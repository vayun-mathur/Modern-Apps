package com.vayunmathur.games.minesweeper.ui.dialogs

import android.content.Context
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.games.minesweeper.R
import com.vayunmathur.games.minesweeper.data.BoardSize
import com.vayunmathur.games.minesweeper.data.Difficulty
import com.vayunmathur.games.minesweeper.data.GameConfig
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The size/density picker: live mine count, selection state, start and dismiss. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class GameConfigDialogTest {

    @get:Rule val rule = createComposeRule()
    private val ctx: Context get() = ApplicationProvider.getApplicationContext()
    private fun str(id: Int) = ctx.getString(id)

    @Test
    fun showsLabelsAndLiveMineCountForTheInitialConfig() {
        rule.setContent {
            GameConfigDialog(GameConfig(BoardSize.MEDIUM, Difficulty.MEDIUM), onStart = {}, onDismiss = {})
        }
        rule.onNodeWithText(str(R.string.new_field)).assertExists()
        rule.onNodeWithText(str(R.string.field_size)).assertExists()
        rule.onNodeWithText(str(R.string.difficulty)).assertExists()
        // MEDIUM/MEDIUM: 21 mines in 140 cells.
        rule.onNodeWithText("21 / 140").assertExists()
        rule.onNodeWithText(str(R.string.size_small)).assertExists()
        rule.onNodeWithText(str(R.string.size_large)).assertExists()
        rule.onNodeWithText(str(R.string.difficulty_expert)).assertExists()
    }

    @Test
    fun changingSizeAndDensityUpdatesTheCountAndStartReturnsTheChoice() {
        var started: GameConfig? = null
        rule.setContent {
            GameConfigDialog(
                GameConfig(BoardSize.MEDIUM, Difficulty.MEDIUM),
                onStart = { started = it },
                onDismiss = {},
            )
        }
        rule.onNodeWithText(str(R.string.size_large)).performClick()
        // LARGE with MEDIUM density: 32 mines in 216 cells.
        rule.onNodeWithText("32 / 216").assertExists()
        rule.onNodeWithText(str(R.string.difficulty_expert)).performClick()
        // LARGE/EXPERT: 50 mines in 216 cells.
        rule.onNodeWithText("50 / 216").assertExists()
        rule.onNodeWithText(str(com.vayunmathur.library.ui.R.string.easy)).performClick()
        rule.onNodeWithText("22 / 216").assertExists()
        rule.onNodeWithText(str(R.string.size_small)).performClick()
        // SMALL/EASY: 8 mines in 80 cells.
        rule.onNodeWithText("8 / 80").assertExists()

        rule.onNodeWithText(str(com.vayunmathur.library.ui.R.string.new_game)).performClick()
        assertEquals(GameConfig(BoardSize.SMALL, Difficulty.EASY), started)
    }

    @Test
    fun dismissReportsBackWithoutStarting() {
        var started: GameConfig? = null
        var dismissed = false
        rule.setContent {
            GameConfigDialog(
                GameConfig(BoardSize.LARGE, Difficulty.EXPERT),
                onStart = { started = it },
                onDismiss = { dismissed = true },
            )
        }
        rule.onNodeWithText("50 / 216").assertExists()
        rule.onNodeWithText(str(com.vayunmathur.library.ui.R.string.back)).performClick()
        assertNull(started)
        assertEquals(true, dismissed)
    }

    @Test
    fun everySizeAndDensitySegmentIsOffered() {
        rule.setContent {
            GameConfigDialog(GameConfig(), onStart = {}, onDismiss = {})
        }
        // Medium is both a size and a density label, so it appears twice.
        rule.onAllNodesWithText(str(com.vayunmathur.library.ui.R.string.medium))
            .fetchSemanticsNodes().let { assertEquals(2, it.size) }
        rule.onNodeWithText(str(com.vayunmathur.library.ui.R.string.hard)).assertExists()
        rule.onNodeWithText(str(com.vayunmathur.library.ui.R.string.easy)).assertExists()
    }
}
