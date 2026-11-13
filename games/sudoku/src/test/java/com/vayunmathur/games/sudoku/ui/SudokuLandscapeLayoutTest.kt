package com.vayunmathur.games.sudoku.ui

import android.content.Context
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.games.sudoku.R
import com.vayunmathur.games.sudoku.data.BoardSize
import com.vayunmathur.games.sudoku.data.Difficulty
import com.vayunmathur.games.sudoku.data.SudokuGameState
import com.vayunmathur.games.sudoku.data.sudokuSymbol
import com.vayunmathur.games.sudoku.domain.SudokuGenerator
import com.vayunmathur.games.sudoku.platform.SudokuActions
import com.vayunmathur.games.sudoku.platform.SudokuUiState
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertTrue

/** A landscape phone: the side-by-side layout must fit the whole board, bottom row included. */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w800dp-h360dp-land")
class SudokuLandscapeLayoutTest {

    @get:Rule val rule = createComposeRule()
    private val ctx: Context get() = ApplicationProvider.getApplicationContext()

    private fun cellDesc(game: SudokuGameState, index: Int): String {
        val side = game.size.side
        val value = game.valueAt(index)
        val spoken = if (value == 0) ctx.getString(R.string.cd_empty) else sudokuSymbol(value)
        return ctx.getString(R.string.cd_cell, index / side + 1, index % side + 1, spoken)
    }

    @Test
    fun nineByNineFitsWithSquareCells() = assertBoardFits(BoardSize.NINE)

    @Test
    fun twelveByTwelveFitsWithSquareCells() = assertBoardFits(BoardSize.TWELVE)

    private fun assertBoardFits(size: BoardSize) {
        val game = SudokuGameState.from(SudokuGenerator.generate(size, Difficulty.EASY, Random(3)))
        rule.setContent {
            GameBoardScreen(state = SudokuUiState(game = game), actions = SudokuActions.Noop, onExit = {})
        }
        val root = rule.onRoot().getUnclippedBoundsInRoot()
        val bottomCell = rule.onNodeWithContentDescription(cellDesc(game, size.cellCount - 1))
            .getUnclippedBoundsInRoot()
        val topCell = rule.onNodeWithContentDescription(cellDesc(game, 0)).getUnclippedBoundsInRoot()
        assertTrue(
            bottomCell.bottom <= root.bottom,
            "$size: bottom row ends at ${bottomCell.bottom}, window at ${root.bottom}",
        )
        val width = bottomCell.right - bottomCell.left
        val height = bottomCell.bottom - bottomCell.top
        assertTrue((width - height).value in -0.5f..0.5f, "$size: cells stay square ($width x $height)")
        // First row to last spans side cells, so no row was squeezed out.
        assertTrue(bottomCell.bottom - topCell.top >= height * size.side - 1.dp, "$size: full grid height")
    }
}
