package com.vayunmathur.games.arrows.ui.components

import android.content.Context
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertLeftPositionInRootIsEqualTo
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.games.arrows.R
import com.vayunmathur.games.arrows.data.ArrowPiece
import com.vayunmathur.games.arrows.data.ArrowsGameState
import com.vayunmathur.games.arrows.data.ArrowsPuzzle
import com.vayunmathur.games.arrows.data.Direction
import com.vayunmathur.games.arrows.data.GameMode
import com.vayunmathur.games.arrows.data.STARTING_HEARTS
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The board paints in absolute coordinates, so under an RTL locale the per-arrow tap targets
 * must not mirror: a mirrored target sits over a different arrow and launches the wrong one.
 */
@RunWith(RobolectricTestRunner::class)
class ArrowsBoardRtlTest {
    @get:Rule val rule = createComposeRule()
    private val ctx get() = ApplicationProvider.getApplicationContext<Context>()
    private fun str(id: Int, vararg args: Any) = ctx.getString(id, *args)

    private fun at(r: Int, c: Int) = r * 5 + c

    // A's head is at column 0 and B's head at column 4 of the same row: exactly each other's
    // mirror image, so a mirrored tap target for one lands on the other.
    private val game = ArrowsGameState(
        ArrowsPuzzle(
            5, 5,
            listOf(
                ArrowPiece(0, listOf(at(1, 0), at(0, 0)), Direction.UP),
                ArrowPiece(1, listOf(at(0, 3), at(0, 4)), Direction.RIGHT),
            ),
        ),
        emptySet(), STARTING_HEARTS, 1, GameMode.CASUAL,
    )

    private fun setRtlBoard(onTap: (Int) -> Unit) {
        rule.setContent {
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                Box(Modifier.size(250.dp).testTag("board")) {
                    ArrowsBoard(game, showRoutes = true, move = null, onTapArrow = onTap, onMoveFinished = {})
                }
            }
        }
    }

    @Test
    fun tapOnRightHandArrowLaunchesThatArrowInRtl() {
        val tapped = mutableListOf<Int>()
        setRtlBoard { tapped += it }
        // B's head cell, row 0 column 4, in absolute (unmirrored) coordinates.
        rule.onNodeWithTag("board").performTouchInput {
            click(Offset(width * 4.5f / 5f, height * 0.5f / 5f))
        }
        assertEquals(listOf(1), tapped)
    }

    @Test
    fun tapOnLeftHandArrowLaunchesThatArrowInRtl() {
        val tapped = mutableListOf<Int>()
        setRtlBoard { tapped += it }
        rule.onNodeWithTag("board").performTouchInput {
            click(Offset(width * 0.5f / 5f, height * 0.5f / 5f))
        }
        assertEquals(listOf(0), tapped)
    }

    @Test
    fun accessibilityTargetsSitOverTheirOwnHeadsInRtl() {
        setRtlBoard {}
        val descA = str(R.string.cd_arrow, str(R.string.cd_up), 1, 1)
        val descB = str(R.string.cd_arrow, str(R.string.cd_right), 1, 5)
        rule.onNodeWithContentDescription(descA).assertLeftPositionInRootIsEqualTo(0.dp)
        rule.onNodeWithContentDescription(descB).assertLeftPositionInRootIsEqualTo(200.dp)
    }
}
