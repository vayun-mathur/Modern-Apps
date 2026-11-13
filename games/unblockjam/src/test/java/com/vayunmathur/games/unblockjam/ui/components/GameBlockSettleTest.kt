package com.vayunmathur.games.unblockjam.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import com.vayunmathur.games.unblockjam.data.Block
import com.vayunmathur.games.unblockjam.data.Coord
import com.vayunmathur.games.unblockjam.data.Dimension
import com.vayunmathur.games.unblockjam.data.LevelData
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Letting go glides the block into its cell instead of teleporting it there. */
@RunWith(RobolectricTestRunner::class)
class GameBlockSettleTest {
    @get:Rule val rule = createComposeRule()

    private var level by mutableStateOf(
        LevelData(
            id = "settle", dimension = Dimension(6, 6), exit = Coord(6, 2),
            blocks = listOf(
                Block(Coord(0, 2), Dimension(2, 1), false),
                Block(Coord(3, 5), Dimension(2, 1), false),
            ),
            optimalMoves = 1,
        )
    )

    @Test
    fun releaseSettlesIntoTheCommittedCellOverAFewFrames() {
        rule.setContent {
            Box(Modifier.size(360.dp).testTag("frame")) {
                // Feeds each committed move back, as the ViewModel does.
                GameBoard(level, { level = it }, {}, isLevelWon = false, modifier = Modifier.testTag("board"))
            }
        }
        // Cells are square and the board keeps a one-cell exit lane on its right, so a cell is a
        // sixth of the board's height.
        val board = rule.onNodeWithTag("board").getBoundsInRoot()
        val cell = (board.height / 6).value
        rule.mainClock.autoAdvance = false
        // Drag the spare block about 1.6 cells left of x = 3 and let go between cells.
        rule.onNodeWithTag("board").performTouchInput {
            down(Offset(height * 4f / 6f, height * 5.5f / 6f))
            moveBy(Offset(-height * 0.8f / 6f, 0f))
            moveBy(Offset(-height * 0.8f / 6f, 0f))
            up()
        }
        rule.mainClock.advanceTimeByFrame()
        rule.mainClock.advanceTimeByFrame()
        assertEquals(Coord(1, 5), level.blocks[1].position, "the move commits on release")
        val mid = (rule.onNodeWithTag("block_1").getBoundsInRoot().left - board.left).value
        rule.mainClock.advanceTimeBy(1_000)
        val rest = (rule.onNodeWithTag("block_1").getBoundsInRoot().left - board.left).value
        assertEquals(cell, rest, 1f) // x = 1
        assertTrue(mid > rest + 1f, "just after release the block is still on its way: mid=$mid rest=$rest")
    }
}
