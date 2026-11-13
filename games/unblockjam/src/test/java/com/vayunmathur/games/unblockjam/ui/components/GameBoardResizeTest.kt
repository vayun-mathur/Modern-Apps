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
import androidx.compose.ui.test.swipe
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.height
import com.vayunmathur.games.unblockjam.data.Block
import com.vayunmathur.games.unblockjam.data.Coord
import com.vayunmathur.games.unblockjam.data.Dimension
import com.vayunmathur.games.unblockjam.data.LevelData
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** A rotation or window resize changes the cell size mid-level; positions and drags must follow. */
@RunWith(RobolectricTestRunner::class)
class GameBoardResizeTest {
    @get:Rule val rule = createComposeRule()

    private val level = LevelData(
        id = "resize", dimension = Dimension(6, 6), exit = Coord(6, 2),
        blocks = listOf(
            Block(Coord(0, 2), Dimension(2, 1), false),
            Block(Coord(3, 5), Dimension(2, 1), false),
        ),
        optimalMoves = 1,
    )

    private var side by mutableStateOf(360.dp)

    private fun setBoard(onChanged: (LevelData) -> Unit) {
        rule.setContent {
            Box(Modifier.size(side).testTag("frame")) {
                GameBoard(level, onChanged, {}, isLevelWon = false, modifier = Modifier.testTag("board"))
            }
        }
    }

    @Test
    fun blocksStayInTheirCellsAcrossAResize() {
        setBoard {}
        side = 240.dp
        rule.waitForIdle()
        val board = rule.onNodeWithTag("board").getBoundsInRoot()
        val cell = (board.height / 6).value
        assertTrue(cell < 40f, "the board did shrink: cell=$cell")
        val block = rule.onNodeWithTag("block_1").getBoundsInRoot()
        // (3, 5) in cells of the new size, whatever size the board started at.
        assertEquals(3 * cell, (block.left - board.left).value, 1f)
        assertEquals(5 * cell, (block.top - board.top).value, 1f)
    }

    @Test
    fun dragAfterAResizeSnapsByTheNewCellSize() {
        var changed: LevelData? = null
        setBoard { changed = it }
        side = 240.dp
        rule.waitForIdle()
        // One cell to the left at the new size (40 dp), from the block's centre.
        rule.onNodeWithTag("board").performTouchInput {
            swipe(Offset(height * 4f / 6f, height * 5.5f / 6f), Offset(height * 3f / 6f, height * 5.5f / 6f), 300)
        }
        val result = assertNotNull(changed)
        assertEquals(Coord(2, 5), result.blocks[1].position)
    }
}
