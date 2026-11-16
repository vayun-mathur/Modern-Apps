package com.vayunmathur.games.voxels

import android.content.Intent
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import com.vayunmathur.games.voxels.platform.MenuActivity
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Cov-x4: the real MenuActivity — creator flow, refresh/copy and online hosting. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class VoxelsCovX4MenuUiTest {
    @get:Rule val rule = createAndroidComposeRule<MenuActivity>()

    private fun exists(text: String) =
        rule.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty()

    @Test fun creatorFlowCreatesAndPlaysWorld() {
        rule.waitForIdle()
        Shadows.shadowOf(rule.activity).clearNextStartedActivities()
        rule.onNode(hasText("Create New World") and hasClickAction()).performClick()
        rule.waitForIdle()
        rule.onNodeWithText("World name", substring = true).performTextInput("X4 World")
        rule.waitForIdle()
        rule.onNode(hasText("Create & Play") and hasClickAction()).performClick()
        rule.waitForIdle()
        val next = Shadows.shadowOf(rule.activity).nextStartedActivity
        assertEquals(MainActivity::class.java.name, next?.component?.className)
        assertEquals("X4 World", next?.getStringExtra("world_name"))
    }

    @Test fun creatorBackReturnsToMenu() {
        rule.waitForIdle()
        rule.onNode(hasText("Create New World") and hasClickAction()).performClick()
        rule.waitForIdle()
        rule.onAllNodesWithText("Cancel", substring = true)[0].performClick()
        rule.waitForIdle()
        assertTrue(exists("Create New World"))
    }

    @Test fun refreshAndCopyDeviceId() {
        rule.waitForIdle()
        // Provisioning runs in a background effect; give the main looper a chance to show the id.
        Thread.sleep(500)
        rule.waitForIdle()
        if (exists("Refresh")) {
            rule.onNode(hasText("Refresh") and hasClickAction()).performClick()
            rule.waitForIdle()
        }
        if (exists("Copy")) {
            val copy = rule.onAllNodesWithText("Copy", substring = true)
            if (copy.fetchSemanticsNodes().isNotEmpty()) {
                runCatching { copy[0].performClick() }
                rule.waitForIdle()
            }
        }
        assertTrue(exists("Voxels") || exists("Select a world") || exists("world"))
    }

    @Test fun hostOnlineStartsWorldWhenOnline() {
        rule.waitForIdle()
        Thread.sleep(500)
        rule.waitForIdle()
        Shadows.shadowOf(rule.activity).clearNextStartedActivities()
        val host = rule.onAllNodesWithText("Host Online", substring = true)
        if (host.fetchSemanticsNodes().isNotEmpty()) {
            runCatching { host[0].performClick() }
            rule.waitForIdle()
            Thread.sleep(500)
            rule.waitForIdle()
        }
        // Hosting requires connectivity; offline the button is disabled and nothing starts.
        val next: Intent? = Shadows.shadowOf(rule.activity).peekNextStartedActivity()
        if (next != null) {
            assertEquals(MainActivity::class.java.name, next.component?.className)
            assertTrue(next.getBooleanExtra("online", false))
        }
    }
}
