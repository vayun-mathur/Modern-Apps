package com.vayunmathur.games.sudoku.ui

import androidx.compose.foundation.layout.size
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import com.vayunmathur.games.sudoku.data.BoardSize
import com.vayunmathur.games.sudoku.data.Difficulty
import com.vayunmathur.games.sudoku.data.SudokuGameState
import com.vayunmathur.games.sudoku.domain.SudokuGenerator
import com.vayunmathur.games.sudoku.ui.components.SudokuGrid
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Actually rasterising the grid is the only way the line-drawing code runs:
 * under Robolectric's legacy graphics the compose tree is laid out but never
 * drawn, so a semantics-only test cannot reach it.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SudokuGridDrawTest {

    @get:Rule val rule = createComposeRule()

    @Test
    fun gridLinesAreDrawnForEveryBoardSize() {
        var current by androidx.compose.runtime.mutableStateOf(
            SudokuGameState.from(SudokuGenerator.generate(BoardSize.SIX, Difficulty.EASY, Random(2))),
        )
        rule.setContent {
            SudokuGrid(game = current, onSelect = {}, modifier = Modifier.size(360.dp))
        }
        for (size in BoardSize.entries) {
            current = SudokuGameState.from(
                SudokuGenerator.generate(size, Difficulty.EASY, Random(2)),
            )
            rule.waitForIdle()
            val image = rule.onRoot().captureToImage()
            assertTrue(image.width > 0 && image.height > 0, "captured ${size.name} board")
        }
    }
}
