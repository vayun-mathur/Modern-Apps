package com.vayunmathur.games.unblockjam.ui.components

import android.content.Context
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performCustomAccessibilityActionWithLabel
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.games.unblockjam.R
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

/** Blocks can be played with TalkBack actions and with arrow keys, not only by dragging. */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
class BlockAccessibilityTest {
    @get:Rule val rule = createComposeRule()
    private val ctx get() = ApplicationProvider.getApplicationContext<Context>()

    private val level = LevelData(
        id = "a11y", dimension = Dimension(6, 6), exit = Coord(6, 2),
        blocks = listOf(
            Block(Coord(4, 2), Dimension(2, 1), false), // target, at the exit's edge
            Block(Coord(3, 5), Dimension(2, 1), false),
            Block(Coord(0, 0), Dimension(1, 1), true),
        ),
        optimalMoves = 1,
    )

    private fun describe(kind: Int, block: Block) = ctx.getString(
        R.string.cd_block_at, ctx.getString(kind),
        block.dimension.width, block.dimension.height, block.position.y + 1, block.position.x + 1,
    )

    private fun setBoard(onChanged: (LevelData) -> Unit, onWon: () -> Unit = {}) {
        rule.setContent {
            Box(Modifier.size(360.dp)) { GameBoard(level, onChanged, onWon, isLevelWon = false) }
        }
    }

    @Test
    fun talkBackMoveActionSlidesTheBlock() {
        var changed: LevelData? = null
        setBoard({ changed = it })
        rule.onNodeWithContentDescription(describe(R.string.cd_block, level.blocks[1]))
            .performCustomAccessibilityActionWithLabel(ctx.getString(R.string.action_move_left))
        assertEquals(Coord(2, 5), assertNotNull(changed).blocks[1].position)
    }

    @Test
    fun talkBackMoveRightOnTheTargetAtTheExitWins() {
        var won = 0
        setBoard({}, { won++ })
        rule.onNodeWithContentDescription(describe(R.string.cd_target_block, level.blocks[0]))
            .performCustomAccessibilityActionWithLabel(ctx.getString(R.string.action_move_right))
        assertEquals(1, won)
    }

    @Test
    fun arrowKeysMoveTheFocusedBlock() {
        var changed: LevelData? = null
        setBoard({ changed = it })
        val node = rule.onNodeWithContentDescription(describe(R.string.cd_block, level.blocks[1]))
        node.requestFocus()
        node.performKeyInput { pressKey(Key.DirectionLeft) }
        assertEquals(Coord(2, 5), assertNotNull(changed).blocks[1].position)
    }

    @Test
    fun wallsAreDescribedButOfferNoMoves() {
        setBoard({})
        rule.onNodeWithContentDescription(describe(R.string.cd_wall, level.blocks[2])).assertExists()
    }
}
