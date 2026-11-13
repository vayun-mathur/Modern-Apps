package com.vayunmathur.games.chess

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.test.TouchInjectionScope
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.LayoutDirection
import com.vayunmathur.games.chess.data.Board
import com.vayunmathur.games.chess.data.PieceColor
import com.vayunmathur.games.chess.data.Position
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * BoardGrid's drag gesture and layout, driven through real touch input. Kept apart from
 * ChessDropTest's view-model tests: a compose rule in the same Robolectric class broke the
 * engine probe those view models run on construction.
 */
@RunWith(RobolectricTestRunner::class)
class BoardDragGestureTest {
    @get:Rule val rule = createComposeRule()

    private fun sq(file: Char, rank: Int) = Position(8 - rank, file - 'a')

    @Test fun boardKeepsFilesLeftToRightUnderRtl() {
        rule.setContent {
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                BoardGrid(
                    board = Board.initialState, selectedPiece = null, isFlipped = false,
                    turn = PieceColor.WHITE, onSquareClick = {},
                )
            }
        }
        val squares = rule.onAllNodes(hasClickAction()).fetchSemanticsNodes()
        // Layout order: squares[0] is a8 (row 0, col 0), squares[7] is h8.
        assertTrue(
            squares[0].boundsInRoot.left < squares[7].boundsInRoot.left,
            "a8 must stay on the left in an RTL locale, or the drag maths would hit the mirrored file"
        )
    }
}
