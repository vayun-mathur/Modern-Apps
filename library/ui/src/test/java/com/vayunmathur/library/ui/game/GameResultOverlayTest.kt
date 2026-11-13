package com.vayunmathur.library.ui.game

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.library.ui.R as UiR
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.Test
import kotlin.test.assertEquals

/** The result card over a finished board: what it lets through, and what it doesn't. */
@RunWith(RobolectricTestRunner::class)
class GameResultOverlayTest {

    @get:Rule
    val rule = createComposeRule()

    private val appContext: android.content.Context
        get() = ApplicationProvider.getApplicationContext()

    private var behindTaps = 0

    /** A board-sized clickable under the overlay, standing in for the cells and keys of a game. */
    private fun setBoardWithOverlay(overlay: @androidx.compose.runtime.Composable () -> Unit) {
        rule.setContent {
            Box(Modifier.fillMaxSize().testTag(ROOT)) {
                Box(Modifier.fillMaxSize().clickable { behindTaps++ })
                overlay()
            }
        }
    }

    @Test
    fun `a tap on the backdrop does not reach a clickable behind it`() {
        setBoardWithOverlay { GameResultOverlay(title = "Solved", won = true, onBack = {}) }
        // Corners and edges are all scrim: the card is centred with padding around it.
        rule.onNodeWithTag(ROOT).performTouchInput {
            click(Offset(2f, 2f))
            click(Offset(width - 2f, height - 2f))
            click(Offset(centerX, 2f))
        }
        rule.waitForIdle()
        assertEquals(0, behindTaps)
    }

    @Test
    fun `the card's own buttons still work over the barrier`() {
        var again = 0
        var back = 0
        setBoardWithOverlay {
            GameResultOverlay(title = "Solved", won = true, onBack = { back++ }, onPlayAgain = { again++ })
        }
        rule.onNodeWithText(appContext.getString(UiR.string.game_play_again)).performClick()
        rule.onNodeWithText(appContext.getString(UiR.string.game_back)).performClick()
        assertEquals(1, again)
        assertEquals(1, back)
        assertEquals(0, behindTaps)
    }

    private companion object {
        const val ROOT = "root"
    }
}
