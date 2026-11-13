package com.vayunmathur.library.ui.game

import android.view.View
import android.view.accessibility.AccessibilityManager
import androidx.activity.OnBackPressedDispatcher
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusManager
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.foundation.text.BasicText
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.library.ui.R as UiR
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

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

    @Test
    fun `TalkBack cannot reach the board while the overlay shows`() {
        val am = appContext.getSystemService(AccessibilityManager::class.java)
        shadowOf(am).setEnabled(true)
        shadowOf(am).setTouchExplorationEnabled(true)
        var showOverlay by mutableStateOf(false)
        lateinit var view: View
        rule.setContent {
            view = LocalView.current
            Box(Modifier.fillMaxSize()) {
                Box(Modifier.fillMaxSize().clickable { behindTaps++ }) { BasicText("Behind") }
                if (showOverlay) GameResultOverlay(title = "Solved", won = true, onBack = {})
            }
        }
        val behindId = rule.onNodeWithText("Behind").fetchSemanticsNode().id
        assertNotNull(accessibilityNode(view, behindId), "control: the board is reachable before the overlay")

        showOverlay = true
        rule.waitForIdle()
        val titleId = rule.onNodeWithText("Solved").fetchSemanticsNode().id
        assertNotNull(accessibilityNode(view, titleId), "the card itself stays reachable")
        assertNull(accessibilityNode(view, behindId), "the covered board is out of the accessibility tree")
    }

    @Test
    fun `keyboard focus moves onto the overlay and cannot leave it`() {
        var behindFocused = 0
        lateinit var focusManager: FocusManager
        rule.setContent {
            focusManager = LocalFocusManager.current
            Box(Modifier.fillMaxSize()) {
                Box(
                    Modifier
                        .fillMaxSize()
                        .onFocusChanged { if (it.isFocused) behindFocused++ }
                        .focusable(),
                )
                GameResultOverlay(title = "Solved", won = true, onBack = {}, onPlayAgain = {})
            }
        }
        rule.runOnIdle {
            listOf(FocusDirection.Next, FocusDirection.Previous, FocusDirection.Down, FocusDirection.Up)
                .forEach { direction -> repeat(4) { focusManager.moveFocus(direction) } }
        }
        rule.waitForIdle()
        assertEquals(0, behindFocused)
    }

    @Test
    fun `view board swaps the card for a bar and back`() {
        var again = 0
        setBoardWithOverlay {
            GameResultOverlay(title = "Solved", won = true, onBack = {}, onPlayAgain = { again++ })
        }
        rule.onNodeWithText("Solved").assertIsDisplayed()
        rule.onNodeWithText(str(UiR.string.game_view_board)).performClick()
        rule.waitForIdle()
        rule.onNodeWithText("Solved").assertDoesNotExist()
        rule.onNodeWithText(str(UiR.string.game_show_result)).assertIsDisplayed()

        // The board is on view, not live: a tap on it still goes nowhere.
        rule.onNodeWithTag(ROOT).performTouchInput { click(Offset(centerX, centerY)) }
        rule.waitForIdle()
        assertEquals(0, behindTaps)

        // The bar carries the primary action...
        rule.onNodeWithText(str(UiR.string.game_play_again)).performClick()
        assertEquals(1, again)
        // ...and brings the card back.
        rule.onNodeWithText(str(UiR.string.game_show_result)).performClick()
        rule.waitForIdle()
        rule.onNodeWithText("Solved").assertIsDisplayed()
        rule.onNodeWithText(str(UiR.string.game_show_result)).assertDoesNotExist()
    }

    @Test
    fun `back from the board view returns to the card`() {
        lateinit var dispatcher: OnBackPressedDispatcher
        setBoardWithOverlay {
            dispatcher = checkNotNull(LocalOnBackPressedDispatcherOwner.current).onBackPressedDispatcher
            GameResultOverlay(title = "Solved", won = true, onBack = {})
        }
        rule.onNodeWithText(str(UiR.string.game_view_board)).performClick()
        rule.waitForIdle()
        rule.runOnIdle { dispatcher.onBackPressed() }
        rule.waitForIdle()
        rule.onNodeWithText("Solved").assertIsDisplayed()
    }

    @Test
    fun `a daily board's bar offers the way out`() {
        var back = 0
        setBoardWithOverlay { GameResultOverlay(title = "Daily", won = true, onBack = { back++ }) }
        rule.onNodeWithText(str(UiR.string.game_view_board)).performClick()
        rule.waitForIdle()
        rule.onNodeWithText(str(UiR.string.game_back)).performClick()
        assertEquals(1, back)
    }

    @Test
    fun `callers can opt out of view board`() {
        setBoardWithOverlay {
            GameResultOverlay(title = "Solved", won = true, onBack = {}, allowViewBoard = false)
        }
        rule.onNodeWithText("Solved").assertIsDisplayed()
        rule.onNodeWithText(str(UiR.string.game_view_board)).assertDoesNotExist()
    }

    private fun str(id: Int): String = appContext.getString(id)

    /** What an accessibility service sees for one semantics node; null when it is left out of the tree. */
    private fun accessibilityNode(view: View, id: Int): Any? {
        var node: Any? = null
        rule.runOnIdle {
            val provider = checkNotNull(view.accessibilityNodeProvider) { "compose exposes a node provider" }
            node = provider.createAccessibilityNodeInfo(id)
        }
        return node
    }

    private companion object {
        const val ROOT = "root"
    }
}
