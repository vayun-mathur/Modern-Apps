package com.vayunmathur.games.voxels

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.click
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Cov-x4: in-game HUD interactions in the real MainActivity (engine absent on the JVM). */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class VoxelsCovX4MainUiTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()

    private fun exists(text: String) =
        rule.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty()

    private fun pressBack() {
        rule.runOnUiThread { rule.activity.onBackPressedDispatcher.onBackPressed() }
        rule.waitForIdle()
    }

    @Test fun hudHoldButtonsPressAndRelease() {
        rule.waitForIdle()
        assertTrue(exists("Use"))
        for (label in listOf("Use", "Jump", "Fly", "Sneak")) {
            if (exists(label)) {
                rule.onNodeWithText(label).performTouchInput { down(center); advanceEventTime(120); up() }
                rule.waitForIdle()
            }
        }
        // After Fly toggles, the vertical button relabels; sneak state resets with flight.
        if (exists("Land")) {
            rule.onNodeWithText("Land").performTouchInput { down(center); up() }
            rule.waitForIdle()
        }
        if (exists("Up")) {
            rule.onNodeWithText("Up").performTouchInput { down(center); up() }
            rule.onNodeWithText("Down").performTouchInput { down(center); up() }
            rule.waitForIdle()
        }
    }

    @Test fun backOpensPauseMenuAndResumeClosesIt() {
        rule.waitForIdle()
        assertFalse(exists("Resume"))
        pressBack()
        assertTrue(exists("Paused"))
        assertTrue(exists("Resume"))
        rule.onNodeWithText("Resume").performClick()
        rule.waitForIdle()
        assertFalse(exists("Resume"))
    }

    @Test fun backThenSaveAndQuitFinishesActivity() {
        rule.waitForIdle()
        pressBack()
        assertTrue(exists("Save & Quit to Menu"))
        rule.onNodeWithText("Save & Quit to Menu").performClick()
        rule.waitForIdle()
        assertTrue(rule.activity.isFinishing)
    }

    @Test fun topLeftIconButtonsArePresent() {
        rule.waitForIdle()
        // Pause, settings and the hotbar inventory button are the activity's click-action nodes.
        val clickables = rule.onAllNodes(hasClickAction()).fetchSemanticsNodes()
        assertEquals(3, clickables.size)
        assertTrue(clickables.any { it.boundsInRoot.top < 100f })
    }
}
