package com.vayunmathur.games.minesweeper.ui

import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.games.minesweeper.R
import com.vayunmathur.games.minesweeper.data.BoardSize
import com.vayunmathur.games.minesweeper.data.Difficulty
import com.vayunmathur.games.minesweeper.data.GameConfig
import com.vayunmathur.games.minesweeper.data.MinesweeperGameState
import com.vayunmathur.games.minesweeper.platform.MinesweeperActions
import com.vayunmathur.games.minesweeper.platform.MinesweeperUiState
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.Test
import kotlin.test.assertTrue

/** The whole field fits the window in every orientation, with square cells and no scrolling. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MinesweeperLayoutTest {

    @get:Rule val rule = createComposeRule()
    private val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()

    private fun showLarge(): GameConfig {
        val config = GameConfig(BoardSize.LARGE, Difficulty.MEDIUM)
        val state = MinesweeperUiState(config = config, game = MinesweeperGameState.empty(config))
        rule.setContent { GameBoardScreen(state, MinesweeperActions.Noop, onExit = {}) }
        return config
    }

    private fun assertFieldFits(config: GameConfig) {
        val root = rule.onRoot().getUnclippedBoundsInRoot()
        val first = rule.onNodeWithContentDescription("Row 1, column 1,", substring = true)
            .getUnclippedBoundsInRoot()
        val last = rule.onNodeWithContentDescription(
            "Row ${config.size.rows}, column ${config.size.cols},", substring = true,
        ).getUnclippedBoundsInRoot()
        assertTrue(first.top >= root.top && last.bottom <= root.bottom, "field fits vertically: $first .. $last in $root")
        assertTrue(first.left >= root.left && last.right <= root.right, "field fits horizontally")
        val w = last.right - last.left
        val h = last.bottom - last.top
        assertTrue((w - h).value in -0.5f..0.5f, "cells are square ($w x $h)")
    }

    @Test
    @Config(qualifiers = "w800dp-h360dp-land")
    fun landscapeFitsTheLargeFieldBesideTheControls() {
        val config = showLarge()
        assertFieldFits(config)
        // Controls sit beside the field, not below it.
        val field = rule.onNodeWithContentDescription("Row 1, column 1,", substring = true)
            .getUnclippedBoundsInRoot()
        val newField = rule.onNodeWithText(ctx.getString(R.string.new_field)).getUnclippedBoundsInRoot()
        assertTrue(newField.right <= field.left, "controls are on the start side of the field")
    }

    @Test
    @Config(qualifiers = "w360dp-h640dp-port")
    fun shortPortraitPhoneFitsTheLargeFieldAboveTheControls() {
        val config = showLarge()
        assertFieldFits(config)
        val last = rule.onNodeWithContentDescription(
            "Row ${config.size.rows}, column ${config.size.cols},", substring = true,
        ).getUnclippedBoundsInRoot()
        val newField = rule.onNodeWithText(ctx.getString(R.string.new_field)).getUnclippedBoundsInRoot()
        assertTrue(newField.top >= last.bottom, "controls are below the field")
        assertTrue(newField.bottom <= rule.onRoot().getUnclippedBoundsInRoot().bottom, "and still on screen")
    }
}
