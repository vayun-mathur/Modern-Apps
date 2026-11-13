package com.vayunmathur.games.unblockjam

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.compose.ui.unit.dp
import com.vayunmathur.games.unblockjam.data.Block
import com.vayunmathur.games.unblockjam.data.Coord
import com.vayunmathur.games.unblockjam.data.Dimension
import com.vayunmathur.games.unblockjam.data.LevelData
import com.vayunmathur.games.unblockjam.ui.components.GameBoard
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
class BoardDragTest {
    @get:Rule val rule = createComposeRule()

    private fun level(blocks: List<Block>) = LevelData(
        id = "drag", dimension = Dimension(6, 6), exit = Coord(6, 2),
        blocks = blocks, optimalMoves = 1,
    )

    private fun openLevel() = level(
        listOf(
            Block(Coord(0, 2), Dimension(2, 1), false), // main
            Block(Coord(2, 1), Dimension(1, 3), false), // vertical blocker
            Block(Coord(4, 4), Dimension(2, 1), true), // fixed wall
            Block(Coord(0, 5), Dimension(2, 1), false), // spare horizontal
        )
    )

    private fun setBoard(level: LevelData, won: Boolean, onChanged: (LevelData) -> Unit, onWon: () -> Unit) {
        rule.setContent {
            Box(Modifier.size(360.dp)) {
                GameBoard(levelData = level, onLevelChanged = onChanged, onLevelWon = onWon, isLevelWon = won, modifier = Modifier.testTag("board"))
            }
        }
    }

    @Test
    fun draggingVerticalBlockDownReportsMovedLevel() {
        var changed: LevelData? = null
        var won = 0
        setBoard(openLevel(), false, { changed = it }, { won++ })
        // vertical blocker occupies x=2, y=1..3: centre (2.5/6, 2.5/6); drag down one cell
        rule.onNodeWithTag("board").performTouchInput {
            swipe(Offset(width * 2.5f / 6f, height * 2.5f / 6f), Offset(width * 2.5f / 6f, height * 3.5f / 6f), 300)
        }
        val result = assertNotNull(changed, "vertical drag should report a moved level")
        assertEquals(Coord(2, 2), result.blocks[1].position)
        assertEquals(1, result.lastMovedBlockIndex)
        assertEquals(0, won)
    }

    @Test
    fun draggingMainBlockToExitWins() {
        var changed: LevelData? = null
        var won = 0
        val free = level(
            listOf(
                Block(Coord(0, 2), Dimension(2, 1), false),
                Block(Coord(0, 5), Dimension(2, 1), false),
            )
        )
        setBoard(free, false, { changed = it }, { won++ })
        // main centre (1/6, 2.5/6); drag far right toward the exit
        rule.onNodeWithTag("board").performTouchInput {
            swipe(Offset(width / 6f, height * 2.5f / 6f), Offset(width * 0.97f, height * 2.5f / 6f), 400)
        }
        assertTrue(won >= 1, "dragging main block out should win, won=$won changed=$changed")
    }

    @Test
    fun smallDragThatStaysInCellResetsWithoutReporting() {
        var changed: LevelData? = null
        var won = 0
        setBoard(openLevel(), false, { changed = it }, { won++ })
        // tiny drag on the spare horizontal block: snaps back to its own cell
        rule.onNodeWithTag("board").performTouchInput {
            swipe(Offset(width / 6f, height * 5.5f / 6f), Offset(width / 6f + 5f, height * 5.5f / 6f), 200)
        }
        assertNull(changed)
        assertEquals(0, won)
    }

    @Test
    fun draggingBlockedMainBlockDoesNotReportOrWin() {
        var changed: LevelData? = null
        var won = 0
        setBoard(openLevel(), false, { changed = it }, { won++ })
        // main is blocked by the vertical blocker at x=2: rightward drag goes nowhere
        rule.onNodeWithTag("board").performTouchInput {
            swipe(Offset(width / 6f, height * 2.5f / 6f), Offset(width * 3f / 6f, height * 2.5f / 6f), 300)
        }
        assertNull(changed)
        assertEquals(0, won)
    }

    @Test
    fun dragsIgnoredWhenLevelAlreadyWon() {
        var changed: LevelData? = null
        var won = 0
        setBoard(openLevel(), true, { changed = it }, { won++ })
        rule.onNodeWithTag("board").performTouchInput {
            swipe(Offset(width * 2.5f / 6f, height * 2.5f / 6f), Offset(width * 2.5f / 6f, height * 3.5f / 6f), 300)
        }
        assertNull(changed)
        assertEquals(0, won)
    }

    @Test
    fun draggingHorizontalBlockLeftReportsMove() {
        var changed: LevelData? = null
        val lvl = level(
            listOf(
                Block(Coord(0, 2), Dimension(2, 1), false),
                Block(Coord(3, 5), Dimension(2, 1), false),
            )
        )
        setBoard(lvl, false, { changed = it }, {})
        // spare block centre (4/6, 5.5/6); drag left two cells
        rule.onNodeWithTag("board").performTouchInput {
            swipe(Offset(width * 4f / 6f, height * 5.5f / 6f), Offset(width * 2f / 6f, height * 5.5f / 6f), 300)
        }
        val result = assertNotNull(changed)
        assertEquals(Coord(1, 5), result.blocks[1].position)
        assertEquals(1, result.lastMovedBlockIndex)
    }

    @Test
    fun blockFollowsTheFingerMidDrag() {
        val lvl = level(
            listOf(
                Block(Coord(0, 2), Dimension(2, 1), false),
                Block(Coord(3, 5), Dimension(2, 1), false),
            )
        )
        setBoard(lvl, false, {}, {})
        val before = rule.onNodeWithTag("block_1").getBoundsInRoot().left
        // Finger still down: the position is applied in layout, not by recomposition, and must
        // still track the drag frame by frame.
        rule.onNodeWithTag("board").performTouchInput {
            down(Offset(width * 4f / 6f, height * 5.5f / 6f))
            moveBy(Offset(-width * 1.5f / 6f, 0f))
            moveBy(Offset(-width * 0.2f / 6f, 0f))
        }
        val mid = rule.onNodeWithTag("block_1").getBoundsInRoot().left
        // 1.7 of 6 cells of a 360 dp board is 102 dp; touch slop eats a little of the first move.
        val moved = (before - mid).value
        assertTrue(moved in 80f..103f, "block should follow the finger, moved=$moved")
        rule.onNodeWithTag("board").performTouchInput { up() }
    }
}
