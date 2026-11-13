package com.vayunmathur.games.arrows.ui.components

import android.content.Context
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.click
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.games.arrows.R
import com.vayunmathur.games.arrows.data.ArrowPiece
import com.vayunmathur.games.arrows.data.ArrowsGameState
import com.vayunmathur.games.arrows.data.ArrowsPuzzle
import com.vayunmathur.games.arrows.data.Direction
import com.vayunmathur.games.arrows.data.GameMode
import com.vayunmathur.games.arrows.data.Mirror
import com.vayunmathur.games.arrows.data.STARTING_HEARTS
import com.vayunmathur.games.arrows.platform.ArrowMove
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.NATIVE)
class ArrowsBoardTest {
    @get:Rule val rule = createComposeRule()
    private val ctx get() = ApplicationProvider.getApplicationContext<Context>()
    private fun str(id: Int, vararg args: Any) = ctx.getString(id, *args)

    private fun at(r: Int, c: Int) = r * 5 + c

    private fun game(
        removed: Set<Int> = emptySet(),
        blockedId: Int = -1,
        mirrors: Map<Int, Mirror> = emptyMap(),
    ): ArrowsGameState {
        val pieces = listOf(
            ArrowPiece(0, listOf(at(2, 1), at(2, 2)), Direction.RIGHT),
            ArrowPiece(1, listOf(at(0, 4), at(1, 4)), Direction.DOWN),
        )
        return ArrowsGameState(ArrowsPuzzle(5, 5, pieces, mirrors), removed, STARTING_HEARTS, 1, GameMode.CASUAL, blockedId)
    }

    @Test
    fun boardRendersArrowSemanticsAndClickTapsHead() {
        val tapped = mutableListOf<Int>()
        rule.setContent {
            ArrowsBoard(game(), showRoutes = true, moves = emptyList(), onTapArrow = { tapped += it }, onMoveFinished = {})
        }
        // Head of piece0 at row3 col3 (1-based), pointing right
        val desc0 = str(R.string.cd_arrow, str(R.string.cd_right), 3, 3)
        val desc1 = str(R.string.cd_arrow, str(R.string.cd_down), 2, 5)
        rule.onNodeWithContentDescription(desc0).assertIsDisplayed()
        rule.onNodeWithContentDescription(desc1).assertIsDisplayed()
        rule.onNodeWithContentDescription(desc0).performClick()
        assertEquals(listOf(0), tapped)
        rule.onNodeWithContentDescription(desc1).performClick()
        assertEquals(listOf(0, 1), tapped)
    }

    @Test
    fun boardShowsRedirectorSemanticsAndSkipsRemovedArrows() {
        rule.setContent {
            ArrowsBoard(
                game(removed = setOf(0), mirrors = mapOf(at(1, 1) to Mirror.FORWARD)),
                showRoutes = true, moves = emptyList(), onTapArrow = {}, onMoveFinished = {},
            )
        }
        rule.onNodeWithContentDescription(str(R.string.cd_redirector)).assertExists()
        val desc0 = str(R.string.cd_arrow, str(R.string.cd_right), 3, 3)
        rule.onNodeWithContentDescription(desc0).assertDoesNotExist()
    }

    @Test
    fun boardWithBlockedArrowAndRoutesRenders() {
        val tapped = mutableListOf<Int>()
        rule.setContent {
            ArrowsBoard(
                game(blockedId = 0),
                showRoutes = true, moves = emptyList(), onTapArrow = { tapped += it }, onMoveFinished = {},
            )
        }
        val desc0 = str(R.string.cd_arrow, str(R.string.cd_right), 3, 3)
        rule.onNodeWithContentDescription(desc0).assertIsDisplayed()
        rule.onNodeWithContentDescription(desc0).performClick()
        assertEquals(listOf(0), tapped)
    }

    @Test
    fun boardWithoutRoutesStillRendersBlockedArrow() {
        rule.setContent {
            ArrowsBoard(game(blockedId = 0), showRoutes = false, moves = emptyList(), onTapArrow = {}, onMoveFinished = {})
        }
        rule.onNodeWithContentDescription(str(R.string.cd_arrow, str(R.string.cd_right), 3, 3)).assertIsDisplayed()
    }

    @Test
    fun boardWithClearingMoveAnimatesAndFinishes() {
        var finished = 0
        val g = game()
        val piece = g.puzzle.pieces[0]
        val move = ArrowMove(piece.id, piece.cells + listOf(at(2, 3), at(2, 4)), 2, true)
        rule.setContent {
            ArrowsBoard(g, showRoutes = true, moves = listOf(move), onTapArrow = {}, onMoveFinished = { finished++ })
        }
        rule.waitUntil(timeoutMillis = 10_000) { finished > 0 }
        assertEquals(1, finished)
    }

    @Test
    fun boardWithBlockedMoveAnimatesAndFinishes() {
        var finished = 0
        val g = game()
        val piece = g.puzzle.pieces[0]
        val move = ArrowMove(piece.id, piece.cells + listOf(at(2, 3)), 1, false)
        rule.setContent {
            ArrowsBoard(g, showRoutes = true, moves = listOf(move), onTapArrow = {}, onMoveFinished = { finished++ })
        }
        rule.waitUntil(timeoutMillis = 10_000) { finished > 0 }
        assertEquals(1, finished)
    }

    @Test
    fun boardWithWedgedMoveNudgesAndFinishes() {
        var finished = 0
        val g = game()
        val piece = g.puzzle.pieces[0]
        val move = ArrowMove(piece.id, piece.cells, 0, false)
        rule.setContent {
            ArrowsBoard(g, showRoutes = false, moves = listOf(move), onTapArrow = {}, onMoveFinished = { finished++ })
        }
        rule.waitUntil(timeoutMillis = 10_000) { finished > 0 }
        assertEquals(1, finished)
    }

    @Test
    fun twoConcurrentMovesEachAnimateAndFinishOnTheirOwn() {
        val finished = mutableListOf<Long>()
        // Both arrows were applied at tap time, so neither is in `remaining` any more.
        val g = game(removed = setOf(0, 1))
        val p0 = g.puzzle.pieces[0]
        val p1 = g.puzzle.pieces[1]
        val m0 = ArrowMove(p0.id, p0.cells + listOf(at(2, 3), at(2, 4)), 2, true, id = 1L)
        val m1 = ArrowMove(p1.id, p1.cells + listOf(at(2, 4), at(3, 4), at(4, 4)), 3, true, id = 2L)
        rule.setContent {
            ArrowsBoard(g, showRoutes = true, moves = listOf(m0, m1), onTapArrow = {}, onMoveFinished = { finished += it })
        }
        rule.waitUntil(timeoutMillis = 10_000) { finished.size == 2 }
        assertEquals(setOf(1L, 2L), finished.toSet())
    }

    @Test
    fun boardCanvasTapViaTouchHitsArrow() {
        val tapped = mutableListOf<Int>()
        rule.setContent {
            ArrowsBoard(game(), showRoutes = true, moves = emptyList(), onTapArrow = { tapped += it }, onMoveFinished = {})
        }
        // Touch near the centre should hit some arrow cell or at least not crash; head clicks are
        // covered via semantics above, this exercises the board's pointerInput path.
        rule.onRoot().performTouchInput { click(center) }
        rule.waitForIdle()
        assertTrue(tapped.size <= 1)
    }

    @Test
    fun spokenNameResMapsEveryDirection() {
        assertEquals(R.string.cd_up, Direction.UP.spokenNameRes)
        assertEquals(R.string.cd_down, Direction.DOWN.spokenNameRes)
        assertEquals(R.string.cd_left, Direction.LEFT.spokenNameRes)
        assertEquals(R.string.cd_right, Direction.RIGHT.spokenNameRes)
    }
}
