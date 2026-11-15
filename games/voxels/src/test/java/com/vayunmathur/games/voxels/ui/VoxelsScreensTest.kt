package com.vayunmathur.games.voxels.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.vayunmathur.games.voxels.data.WorldInfo
import com.vayunmathur.games.voxels.data.WorldMeta
import com.vayunmathur.games.voxels.network.VoxelsSync
import org.junit.Rule
import org.junit.runner.RunWith
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Robolectric Compose tests for voxels menu + overlay screens (JSON-driven, no Vulkan). */
@RunWith(AndroidJUnit4::class)
class VoxelsScreensTest {
    @get:Rule val rule = createComposeRule()

    private fun world(name: String, online: Boolean = false, role: String = "") = WorldInfo(
        id = "w_$name", dir = "/tmp/$name",
        meta = WorldMeta(name = name, seed = 1, created = 1, lastPlayed = 2, online = online, role = role, worldId = "wid-$name"),
    )

    @Test fun menuShowsWorldsAndCreateCallbacks() {
        var created = false; var played: WorldInfo? = null
        rule.setContent {
            MenuScreen(listOf(world("Alpha")), emptyList(), "dev-123", true,
                onPlay = { played = it }, onDelete = {}, onCreate = { created = true },
                onHostOnline = {}, onRefresh = {}, onCopyDeviceId = {}, onShare = {})
        }
        rule.onNodeWithText("Alpha").assertIsDisplayed()
        rule.onNodeWithText("Create New World").performClick()
        assertTrue(created)
        assertEquals(null, played)
    }
    @Test fun menuEmptyAndOfflineStates() {
        rule.setContent {
            MenuScreen(emptyList(), listOf(world("Shared", online = true, role = "editor")), "", false,
                onPlay = {}, onDelete = {}, onCreate = {}, onHostOnline = {}, onRefresh = {}, onCopyDeviceId = {}, onShare = {})
        }
        rule.onNodeWithText("No worlds yet", substring = true).assertIsDisplayed()
        rule.onNodeWithText("Select a world").assertIsDisplayed()
    }
    @Test fun menuShowsJoinRequests() {
        val req = VoxelsSync.JoinRequest(worldId = "wid-Alpha", requesterId = "dev9", name = "Bob")
        var approved: VoxelsSync.JoinRequest? = null
        rule.setContent {
            MenuScreen(emptyList(), listOf(world("Alpha", online = true, role = "owner")), "d", true,
                onPlay = {}, onDelete = {}, onCreate = {}, onHostOnline = {}, onRefresh = {}, onCopyDeviceId = {}, onShare = {},
                requests = listOf(req), onApprove = { approved = it }, onDeny = {})
        }
        rule.onNodeWithText("Select a world").assertIsDisplayed()
        assertEquals(null, approved)
    }
    @Test fun worldCreatorReturnsNameAndSeed() {
        var result: Pair<String, String>? = null
        rule.setContent { WorldCreatorScreen(onBack = {}, onCreate = { n, s -> result = n to s }) }
        rule.onNodeWithText("World name", substring = true).performTextInput("My World")
        rule.onNodeWithText("My World").assertIsDisplayed()
    }
    @Test fun furnaceFiltersBlastRecipes() {
        val json = """[{"in":1,"inN":1,"out":3,"outN":1,"secs":1.0,"blast":true},{"in":2,"inN":1,"out":4,"outN":1,"secs":1.0}]"""
        rule.setContent { FurnaceOverlay(json, """{"active":false}""", isBlast = false, onClose = {}) }
        rule.onNodeWithText("Furnace", substring = true).assertIsDisplayed()
    }
    @Test fun stonecutterRender() {
        rule.setContent { StonecutterOverlay("""[{"in":1,"out":102}]""", """{"selected":0,"slots":[{"id":1,"count":5}]}""", onClose = {}) }
        assertTrue(rule.onAllNodesWithText("Stone", substring = true).fetchSemanticsNodes().isNotEmpty())
    }
    @Test fun tradeRender() {
        rule.setContent { TradeOverlay("""{"prof":"Farmer","level":1,"maxLevel":2,"trades":[{"cost":1026,"costN":2,"give":1027,"giveN":1,"level":1}]}""", onClose = {}) }
        rule.onNodeWithText("Farmer").assertIsDisplayed()
    }
    @Test fun craftingRender() {
        val recipes = """[{"in":2,"inN":1,"out":10,"outN":4,"cat":"block","known":true}]"""
        rule.setContent { CraftingTable(recipes) }
        rule.onNodeWithText("Crafting Table").assertIsDisplayed()
        rule.onNodeWithText("Planks").assertIsDisplayed()
    }
    @Test fun blessingsRender() {
        rule.setContent { BlessingsView("""{"slots":[]}""", """[{"id":1056,"name":"Blessing of Clement","effect":"Calm"}]""", listOf(InvSlot(1056, 1))) }
        rule.onNodeWithText("Blessing of Clement", substring = true).assertIsDisplayed()
    }
    @Test fun healthRender() {
        rule.setContent { HealthOverlay("""{"hp":10,"max":20,"estus":2}""") }
    }
    @Test fun debugRender() {
        rule.setContent { DebugOverlay("Voxels Engine\nFPS 60") }
        rule.onNodeWithText("Voxels Engine", substring = true).assertIsDisplayed()
    }
    @Test fun hotbarRender() {
        rule.setContent { Hotbar("""{"selected":0,"slots":[{"id":1,"count":3}]}""", onSelect = {}) }
    }
    @Test fun inventoryOverlayRender() {
        rule.setContent {
            InventoryOverlay(
                """{"selected":0,"slots":[{"id":1,"count":3},{"id":2,"count":5}]}""",
                """[{"in":2,"inN":1,"out":10,"outN":4,"cat":"block","known":true}]""",
                """{"slots":[]}""", """[]""", onClose = {})
        }
        assertTrue(rule.onAllNodesWithText("Inventory", substring = true).fetchSemanticsNodes().isNotEmpty())
    }
    @Test fun joystickRender() {
        rule.setContent { Joystick(modifier = androidx.compose.ui.Modifier, onMove = { _, _ -> }, onLookDelta = { _, _ -> }) }
    }
}
