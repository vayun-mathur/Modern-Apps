package com.vayunmathur.games.pipes.ui

import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.vayunmathur.games.pipes.data.CellPos
import com.vayunmathur.games.pipes.data.EndpointPair
import com.vayunmathur.games.pipes.data.LevelData
import com.vayunmathur.games.pipes.data.computeAdjacency
import com.vayunmathur.games.pipes.platform.PipesGameState
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
class GameBoardGestureTest {
    @get:Rule val rule = createComposeRule()

    private val cells = buildSet { for (r in 0 until 5) for (c in 0 until 5) add(CellPos(r, c)) }
    private val level = LevelData(
        "t", 5, 5, cells, computeAdjacency(cells), null,
        listOf(EndpointPair(0, listOf(CellPos(0, 0), CellPos(4, 4)))), emptySet(), 1,
    )

    private fun tapAt(fx: Float, fy: Float) {
        rule.onNodeWithTag(GAME_BOARD_TAG).performTouchInput {
            down(Offset(width * fx, height * fy))
            up()
        }
        rule.waitForIdle()
    }

    @Test fun touchesUseTheCurrentGeometryAfterAResize() {
        val starts = mutableListOf<CellPos>()
        var side by mutableStateOf(300.dp)
        rule.setContent {
            GameBoard(
                level, PipesGameState(), null, emptyList(),
                onStartDraw = { starts += it }, onExtendPath = {}, onCommitDraw = {},
                isLevelWon = false, modifier = Modifier.size(side),
            )
        }
        tapAt(0.9f, 0.1f)
        assertEquals(CellPos(0, 4), starts.last())
        // Shrink the board (rotation / fold / split screen). The same relative spot must
        // still be the top-right cell; stale 300dp geometry would resolve it to column 2.
        side = 150.dp
        rule.waitForIdle()
        tapAt(0.9f, 0.1f)
        assertEquals(CellPos(0, 4), starts.last())
        side = 400.dp
        rule.waitForIdle()
        tapAt(0.1f, 0.9f)
        assertEquals(CellPos(4, 0), starts.last())
    }

    @Test fun rtlDoesNotMirrorHitTesting() {
        val starts = mutableListOf<CellPos>()
        rule.setContent {
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                GameBoard(
                    level, PipesGameState(), null, emptyList(),
                    onStartDraw = { starts += it }, onExtendPath = {}, onCommitDraw = {},
                    isLevelWon = false, modifier = Modifier.size(300.dp),
                )
            }
        }
        // The canvas draws in absolute coordinates, so the top-left cell stays top-left.
        tapAt(0.1f, 0.1f)
        assertEquals(CellPos(0, 0), starts.last())
    }

    @Test fun aSecondFingerNeitherRestartsNorSteersTheStroke() {
        val starts = mutableListOf<CellPos>()
        val extends = mutableListOf<CellPos>()
        var commits = 0
        rule.setContent {
            GameBoard(
                level, PipesGameState(), null, emptyList(),
                onStartDraw = { starts += it }, onExtendPath = { extends += it },
                onCommitDraw = { commits++ }, isLevelWon = false, modifier = Modifier.size(300.dp),
            )
        }
        rule.onNodeWithTag(GAME_BOARD_TAG).performTouchInput {
            down(0, Offset(width * 0.1f, height * 0.1f))
            moveTo(0, Offset(width * 0.3f, height * 0.1f))
            down(1, Offset(width * 0.9f, height * 0.9f))
            moveTo(1, Offset(width * 0.7f, height * 0.9f))
            up(1)
            moveTo(0, Offset(width * 0.5f, height * 0.1f))
            up(0)
        }
        rule.waitForIdle()
        assertEquals(listOf(CellPos(0, 0)), starts)
        assertTrue(extends.all { it.row == 0 }, "extends=$extends")
        assertTrue(CellPos(0, 2) in extends, "extends=$extends")
        assertEquals(1, commits)
    }

    @Test fun boardOptsOutOfSystemBackGestures() {
        var view: android.view.View? = null
        rule.setContent {
            view = androidx.compose.ui.platform.LocalView.current
            GameBoard(
                level, PipesGameState(), null, emptyList(),
                onStartDraw = {}, onExtendPath = {}, onCommitDraw = {},
                isLevelWon = false, modifier = Modifier.size(300.dp),
            )
        }
        rule.waitForIdle()
        val rects = view!!.systemGestureExclusionRects
        assertTrue(rects.isNotEmpty(), "no gesture exclusion rects")
        assertTrue(rects.any { it.width() > 0 && it.height() > 0 })
    }

    @Test fun talkBackSeesABoardSummaryAndEveryCell() {
        val paths = mapOf(0 to listOf(CellPos(0, 0), CellPos(0, 1)))
        val state = PipesGameState(paths, com.vayunmathur.games.pipes.domain.BoardEdits.owners(level, paths))
        rule.setContent {
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                GameBoard(
                    level, state, null, emptyList(),
                    onStartDraw = {}, onExtendPath = {}, onCommitDraw = {},
                    isLevelWon = false, modifier = Modifier.size(300.dp),
                )
            }
        }
        rule.onNodeWithContentDescription("Board, 5 by 5. Pairs connected: 0 of 1. Cells filled: 2 of 25.").assertExists()
        rule.onNodeWithContentDescription("Row 1, column 1, dot A").assertExists()
        rule.onNodeWithContentDescription("Row 1, column 2, pipe A").assertExists()
        rule.onNodeWithContentDescription("Row 5, column 5, dot A").assertExists()
        rule.onAllNodes(hasContentDescription("empty", substring = true)).assertCountEquals(22)
        // Cell nodes sit over their cells even in RTL (the canvas never mirrors).
        val board = rule.onNodeWithTag(GAME_BOARD_TAG).fetchSemanticsNode().boundsInRoot
        val first = rule.onNodeWithContentDescription("Row 1, column 1, dot A").fetchSemanticsNode().boundsInRoot
        assertEquals(board.left, first.left, 1f)
        assertEquals(board.top, first.top, 1f)
    }
}
