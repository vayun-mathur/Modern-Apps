package com.vayunmathur.games.unblockjam.ui.components

import android.content.Context
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
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
import kotlin.test.assertTrue

/** In portrait the width limits the board, which used to push the exit off the screen edge. */
@RunWith(RobolectricTestRunner::class)
class GameBoardExitTest {
    @get:Rule val rule = createComposeRule()
    private val ctx get() = ApplicationProvider.getApplicationContext<Context>()

    private val level = LevelData(
        id = "exit", dimension = Dimension(6, 6), exit = Coord(6, 2),
        blocks = listOf(Block(Coord(0, 2), Dimension(2, 1), false)),
        optimalMoves = 1,
    )

    @Test
    fun portraitBoardLeavesRoomForAVisibleExitLane() {
        rule.setContent {
            Box(Modifier.size(300.dp, 520.dp).testTag("frame")) {
                GameBoard(level, {}, {}, isLevelWon = false)
            }
        }
        val frame = rule.onNodeWithTag("frame").getBoundsInRoot()
        val lane = rule.onNodeWithTag(EXIT_LANE_TEST_TAG).getBoundsInRoot()
        assertTrue(lane.right <= frame.right + 0.5.dp, "exit lane must be on screen: $lane in $frame")
        // A full cell wide (plus the 1 dp seam), in the exit row.
        val cell = (300f * 6 / 7) / 6
        assertEquals(cell + 1f, lane.width.value, 1f)
        rule.onNodeWithContentDescription(ctx.getString(R.string.exit)).assertIsDisplayed()
    }
}
