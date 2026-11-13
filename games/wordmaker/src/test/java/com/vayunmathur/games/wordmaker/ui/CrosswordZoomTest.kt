package com.vayunmathur.games.wordmaker.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.doubleClick
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.pinch
import androidx.compose.ui.unit.dp
import com.vayunmathur.games.wordmaker.data.CrosswordData
import com.vayunmathur.games.wordmaker.ui.components.CrosswordBoard
import com.vayunmathur.library.ui.DynamicTheme
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.Test
import kotlin.test.assertEquals

/** Pinch-zoom of the crossword board: clamped, resettable, and refitted on size change. */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h891dp")
class CrosswordZoomTest {
    @get:Rule val rule = createComposeRule()

    // 8 columns x 6 rows: 296 x 222 dp at natural size.
    private val data = CrosswordData.fromString("...FIVE.\n.F.I.E..\nRIFE.R.R\n.R.R.IVY\nVERY.F.E\n.....Y..")!!
    private var width by mutableStateOf(148.dp)
    private var lastScale = -1f

    private fun setBoard() {
        rule.setContent {
            DynamicTheme {
                Box(Modifier.size(width, 400.dp).testTag("board")) {
                    CrosswordBoard(
                        foundWords = emptySet(), revealedHints = emptySet(), crosswordData = data, hiddenWords = emptySet(),
                        onCellPositioned = { _, _ -> }, onCellClicked = { _, _ -> }, scaleUpdated = { lastScale = it },
                    )
                }
            }
        }
        rule.waitForIdle()
    }

    private fun pinchBy(factor: Float) {
        // Fingers stay inside the 148 dp board (10 px apart from the centre, up to 60).
        rule.onNodeWithTag("board").performTouchInput {
            val c = center
            val start = 10f
            val end = start * factor
            pinch(c - Offset(start, 0f), c - Offset(end, 0f), c + Offset(start, 0f), c + Offset(end, 0f))
        }
        settle()
    }

    /**
     * The zoom is applied in the draw layer and reported through snapshotFlow, neither of which
     * needs a recomposition; run a frame so the layer and the collector catch up.
     */
    private fun settle() {
        rule.mainClock.advanceTimeByFrame()
        rule.waitForIdle()
    }

    /**
     * On-screen width of an (empty) grid cell's text: it scales with the board's layer. The
     * widest one, since cells at the edge of a zoomed board are clipped by the viewport.
     */
    private fun cellWidth(): Float =
        rule.onAllNodesWithText(" ", useUnmergedTree = true).fetchSemanticsNodes().maxOf { it.boundsInRoot.width }

    @Test fun zoomIsClampedAndDoubleTapResetsIt() {
        setBoard()
        val fit = lastScale
        assertEquals(0.5f, fit, 0.01f) // 148 dp box / 296 dp board
        val widthAtFit = cellWidth()

        pinchBy(0.2f)
        assertEquals(fit, lastScale, 0.01f, "cannot zoom out past the fit")
        assertEquals(1f, cellWidth() / widthAtFit, 0.05f, "drawn board did not shrink past the fit")

        pinchBy(6f); pinchBy(6f); pinchBy(6f)
        // Max is 4x the fit here (enough to reach 2x natural size).
        assertEquals(4f, cellWidth() / widthAtFit, 0.2f, "drawn zoom is capped at 4x the fit (reported $lastScale)")
        assertEquals(2f, lastScale, 0.01f, "reported zoom is capped")

        rule.onNodeWithTag("board").performTouchInput { doubleClick(center + Offset(1f, 180f)) }
        settle()
        assertEquals(fit, lastScale, 0.01f, "double-tap resets to fit")
        assertEquals(1f, cellWidth() / widthAtFit, 0.05f, "drawn board is back at the fit")
    }

    @Test fun fitIsRecomputedWhenTheBoxChangesSize() {
        setBoard()
        assertEquals(0.5f, lastScale, 0.01f)
        width = 222.dp // e.g. rotation: the box changes size
        rule.waitForIdle()
        assertEquals(0.75f, lastScale, 0.01f)
    }
}
