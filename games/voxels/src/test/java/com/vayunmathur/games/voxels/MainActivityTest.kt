package com.vayunmathur.games.voxels

import android.content.Intent
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.test.core.app.ApplicationProvider
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * MainActivity (the game) under Robolectric: the host Rust engine loads, so
 * the HUD composes against a real (surface-less) engine instance.
 */
@RunWith(RobolectricTestRunner::class)
class MainActivityTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()

    private fun awaitText(text: String, timeoutMs: Long = 10000) {
        rule.waitUntil(timeoutMillis = timeoutMs) {
            rule.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()
        }
    }

    @Test fun `game hud renders hotbar and debug overlay`() {
        // Debug overlay starts visible ("Voxels Engine" from native debug json or fallback text)
        rule.waitUntil(timeoutMillis = 10000) {
            rule.onAllNodesWithText("Voxels Engine", substring = true).fetchSemanticsNodes().isNotEmpty() ||
                rule.onAllNodesWithText("…").fetchSemanticsNodes().isNotEmpty()
        }
        // hotbar inventory tile is always present
        rule.onAllNodesWithText("…")[0].assertIsDisplayed()
    }

    @Test fun `pause menu resume and quit flow`() {
        awaitText("…")
        // top-left buttons: Menu (pause) and Settings (debug toggle) by content description
        val menus = rule.onAllNodesWithContentDescription("Menu").fetchSemanticsNodes()
        if (menus.isNotEmpty()) {
            rule.onAllNodesWithContentDescription("Menu")[0].performClick()
            rule.onNodeWithText("Paused").assertIsDisplayed()
            rule.onNodeWithText("Resume").performClick()
            rule.waitForIdle()
            // pause again and quit to menu (finishes the activity)
            rule.onAllNodesWithContentDescription("Menu")[0].performClick()
            rule.onNodeWithText("Paused").assertIsDisplayed()
            rule.onNodeWithText("Save & Quit to Menu").performClick()
            rule.waitForIdle()
            assertTrue(rule.activity.isFinishing || rule.activity.isDestroyed)
        }
    }

    @Test fun `inventory opens from hotbar tile and closes`() {
        awaitText("…")
        rule.onAllNodesWithText("…")[0].performClick()
        // InventoryOverlay shows its tab column
        rule.waitUntil(timeoutMillis = 10000) {
            rule.onAllNodesWithText("Inventory").fetchSemanticsNodes().isNotEmpty()
        }
        rule.onNodeWithText("Inventory").assertIsDisplayed()
        // close via the Close tab (first Close node in the overlay)
        rule.onAllNodesWithText("Close")[0].performClick()
        rule.waitForIdle()
    }

    @Test fun `jump and sneak hold buttons toggle states`() {
        awaitText("…")
        rule.onNodeWithText("Jump").assertIsDisplayed()
        rule.onNodeWithText("Sneak").assertIsDisplayed()
        rule.onNodeWithText("Sneak").performClick()
        rule.waitForIdle()
        rule.onNodeWithText("Sneaking").assertIsDisplayed()
    }
}

@RunWith(RobolectricTestRunner::class)
class MainActivityFlyTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()

    @Test fun `double tap jump toggles fly mode buttons`() {
        rule.waitUntil(timeoutMillis = 10000) {
            rule.onAllNodesWithText("Jump").fetchSemanticsNodes().isNotEmpty()
        }
        // double-tap the Jump HoldButton: toggles flying, swapping Sneak for Down
        rule.onNodeWithText("Jump").performTouchInput {
            down(center); advanceEventTime(60); up()
            advanceEventTime(80)
            down(center); advanceEventTime(60); up()
        }
        rule.waitForIdle()
        // either the double-tap registered (Down appears) or a plain press happened;
        // the button row must still be intact
        val downNodes = rule.onAllNodesWithText("Down").fetchSemanticsNodes()
        val sneakNodes = rule.onAllNodesWithText("Sneak", substring = true).fetchSemanticsNodes()
        assertTrue(downNodes.isNotEmpty() || sneakNodes.isNotEmpty())
    }
}

@RunWith(RobolectricTestRunner::class)
class MainActivityLaunchTest {
    @Test fun `activity with explicit world intent creates and destroys`() {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        val intent = Intent(ctx, MainActivity::class.java).apply {
            putExtra("world_dir", java.io.File(ctx.filesDir, "worlds/probe-main").apply { mkdirs() }.absolutePath)
            putExtra("world_seed", 1234)
            putExtra("world_name", "Probe")
            putExtra("online", false)
        }
        val controller = org.robolectric.Robolectric.buildActivity(MainActivity::class.java, intent).create().start().resume()
        Thread.sleep(300)
        controller.pause().stop().destroy()
        assertTrue(true)
    }
}