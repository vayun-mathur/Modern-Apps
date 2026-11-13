package com.vayunmathur.games.unblockjam.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.compose.ui.unit.LayoutDirection
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
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Under an RTL locale the board must still be a picture of physical space: blocks drawn where the
 * level says, and a drag moving a block the way the finger went.
 */
@RunWith(RobolectricTestRunner::class)
class GameBoardRtlTest {
    @get:Rule val rule = createComposeRule()

    private fun level(vararg blocks: Block) = LevelData(
        id = "rtl", dimension = Dimension(6, 6), exit = Coord(6, 2),
        blocks = blocks.toList(), optimalMoves = 1,
    )

    private fun setRtlBoard(level: LevelData, onChanged: (LevelData) -> Unit, onWon: () -> Unit = {}) {
        rule.setContent {
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                Box(Modifier.size(360.dp)) {
                    GameBoard(level, onChanged, onWon, isLevelWon = false, modifier = Modifier.testTag("board"))
                }
            }
        }
    }

    @Test
    fun leftwardDragMovesBlockLeftInRtl() {
        var changed: LevelData? = null
        setRtlBoard(
            level(Block(Coord(0, 2), Dimension(2, 1), false), Block(Coord(3, 5), Dimension(2, 1), false)),
            { changed = it },
        )
        // The spare block sits at x 3..4 of row 5 in physical coordinates; drag it two cells left.
        rule.onNodeWithTag("board").performTouchInput {
            swipe(Offset(width * 4f / 6f, height * 5.5f / 6f), Offset(width * 2f / 6f, height * 5.5f / 6f), 300)
        }
        val result = assertNotNull(changed, "the block under the finger should move")
        assertEquals(Coord(1, 5), result.blocks[1].position)
    }

    @Test
    fun rightwardDragOfTargetToExitWinsInRtl() {
        var won = 0
        setRtlBoard(
            level(Block(Coord(0, 2), Dimension(2, 1), false), Block(Coord(0, 5), Dimension(2, 1), false)),
            {},
            { won++ },
        )
        rule.onNodeWithTag("board").performTouchInput {
            swipe(Offset(width / 6f, height * 2.5f / 6f), Offset(width * 0.97f, height * 2.5f / 6f), 400)
        }
        assertTrue(won >= 1, "dragging the target block right, toward the exit, should win")
    }
}
