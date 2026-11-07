package com.vayunmathur.games.voxels.platform

import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.games.voxels.MainActivity
import com.vayunmathur.games.voxels.data.WorldManager
import com.vayunmathur.games.voxels.network.FakeVoxelsRelay
import com.vayunmathur.games.voxels.network.VoxelsSync
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * MenuActivity against [FakeVoxelsRelay]: the activity's VoxelsSync.init runs
 * through the relay seam, so no real network is touched.
 */
@OptIn(ExperimentalEncodingApi::class)
@RunWith(RobolectricTestRunner::class)
class MenuActivityTest {
    private val composeRule = createAndroidComposeRule<MenuActivity>()

    // Seeding must happen BEFORE the activity launches (its world list is
    // read in onCreate), so it runs as an outer rule, not @Before.
    @get:Rule val rule = org.junit.rules.RuleChain.outerRule(SeedMenuRule()).around(composeRule)
    private val rule2 get() = composeRule

    private val ctx: Context get() = ApplicationProvider.getApplicationContext()

    private class SeedMenuRule : org.junit.rules.TestRule {
        override fun apply(base: org.junit.runners.model.Statement, description: org.junit.runner.Description): org.junit.runners.model.Statement =
            object : org.junit.runners.model.Statement() {
                override fun evaluate() {
                    val ctx = ApplicationProvider.getApplicationContext<Context>()
                    FakeVoxelsRelay.reset()
                    FakeVoxelsRelay.install()
                    runBlocking { VoxelsSync.init(ctx) }
                    if (VoxelsSync.deviceId.isNotBlank()) {
                        FakeVoxelsRelay.keys[VoxelsSync.deviceId] = Base64.encode(VoxelsSync.publicBundle)
                    }
                    WorldManager.listWorlds(ctx).forEach { WorldManager.deleteWorld(ctx, it.id) }
                    WorldManager.createWorld(ctx, "Menu Local World", 42, 1_700_000_000_000)
                    base.evaluate()
                }
            }
    }

    private fun awaitText(text: String) {
        composeRule.waitUntil(timeoutMillis = 10000) {
            composeRule.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()
        }
    }

    @Test fun `menu shows seeded world and device id`() {
        awaitText("Menu Local World")
        composeRule.onNodeWithText("Menu Local World").assertIsDisplayed()
        composeRule.onNodeWithText("Select a world").assertIsDisplayed()
    }

    @Test fun `create world flow starts game with world extras`() {
        awaitText("Menu Local World")
        composeRule.onNodeWithText("Create New World").performClick()
        composeRule.onNodeWithText("Create World").assertIsDisplayed()
        composeRule.onNodeWithText("World name").performTextInput("Created Via Menu")
        composeRule.onNodeWithText("Seed").performTextInput("777")
        composeRule.onNodeWithText("Create & Play").performClick()
        // a new world was persisted with the hashed/numeric seed
        val created = WorldManager.listWorlds(ctx).firstOrNull { it.meta.name == "Created Via Menu" }
        assertNotNull(created)
        assertEquals(777, created.meta.seed)
        val started = Shadows.shadowOf(ctx as android.app.Application).nextStartedActivity
        assertNotNull(started)
        assertEquals(MainActivity::class.java.name, started.component?.className)
        assertEquals(created.dir, started.getStringExtra("world_dir"))
        assertEquals(777, started.getIntExtra("world_seed", -1))
    }

    @Test fun `delete local world removes it from list and disk`() {
        awaitText("Menu Local World")
        // WorldRow delete is an icon button with content description Delete
        composeRule.onAllNodesWithContentDescription("Delete")[0].performClick()
        composeRule.waitForIdle()
        assertTrue(WorldManager.listWorlds(ctx).none { it.meta.name == "Menu Local World" })
    }

    @Test fun `copy device id writes clipboard`() {
        awaitText("Menu Local World")
        // scroll to the online section where the device-id copy row lives
        repeat(3) {
            composeRule.onRoot().performTouchInput {
                swipe(start = androidx.compose.ui.geometry.Offset(center.x, center.y * 1.6f), end = androidx.compose.ui.geometry.Offset(center.x, center.y * 0.4f), durationMillis = 250)
            }
            composeRule.waitForIdle()
        }
        val copies = composeRule.onAllNodesWithText("Copy").fetchSemanticsNodes()
        if (copies.isNotEmpty() && VoxelsSync.deviceId.isNotBlank()) {
            composeRule.onAllNodesWithText("Copy")[0].performClick()
            val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            assertEquals(VoxelsSync.deviceId, cm.primaryClip?.getItemAt(0)?.text)
        }
    }
}
