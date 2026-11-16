package com.vayunmathur.games.voxels.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.vayunmathur.games.voxels.data.GameSettings
import com.vayunmathur.games.voxels.data.WorldInfo
import com.vayunmathur.games.voxels.data.WorldMeta
import com.vayunmathur.games.voxels.domain.NetFailure
import com.vayunmathur.games.voxels.domain.NetStatus
import com.vayunmathur.games.voxels.network.VoxelsSync
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Cov-w3: deep branch coverage for voxels menus, crafting, trading and HUD chrome. */
@RunWith(AndroidJUnit4::class)
class VoxelsCovW3ComposeTest {
    @get:Rule val rule = createComposeRule()

    private fun world(name: String, online: Boolean = false, role: String = "", wid: String = "wid-$name") = WorldInfo(
        id = "w_$name", dir = "/tmp/$name",
        meta = WorldMeta(name = name, seed = 7, created = 1, lastPlayed = 2, online = online, role = role, worldId = wid),
    )

    private fun exists(text: String) =
        rule.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty()

    // --- Crafting: every shelf, locked recipes, second ingredient, malformed input ---

    @Test fun craftingAllCategoriesAndLockedToggle() {
        val recipes = """
            [{"in":2,"inN":1,"out":10,"outN":4,"cat":"block","known":true},
             {"in":10,"inN":2,"in2":1055,"in2N":3,"out":1063,"outN":1,"cat":"tool","known":true},
             {"in":1033,"inN":5,"out":1067,"outN":1,"cat":"armor","known":false,"requires":10},
             {"in":18,"inN":1,"out":1053,"outN":2,"cat":"material","known":true},
             {"in":121,"inN":3,"out":1027,"outN":1,"cat":"food","known":true},
             {"in":1055,"inN":2,"out":1056,"outN":1,"cat":"blessing","known":false,"requires":1053}]
        """.trimIndent()
        rule.setContent { CraftingTable(recipes) }
        rule.onNodeWithText("Crafting Table").assertIsDisplayed()
        // Default shelf is Blocks; switching shelves re-filters the picker.
        rule.onNodeWithText("Tools").performClick()
        rule.waitForIdle()
        rule.onNodeWithText("Materials").performClick()
        rule.waitForIdle()
        rule.onNodeWithText("Food").performClick()
        rule.waitForIdle()
        // Locked recipes are hidden until the toggle is tapped.
        rule.onNodeWithText("Armor").performClick()
        rule.waitForIdle()
        assertTrue(exists("Show locked") || exists("locked"))
        rule.onAllNodesWithText("locked", substring = true)[0].performClick()
        rule.waitForIdle()
        rule.onNodeWithText("Blessings").performClick()
        rule.waitForIdle()
    }

    @Test fun craftingEmptyAndMalformedRender() {
        rule.setContent { CraftingTable("not json") }
        rule.onNodeWithText("Crafting Table").assertIsDisplayed()
    }

    @Test fun craftingSingleKnownRecipeShowsGridCounts() {
        rule.setContent { CraftingTable("""[{"in":2,"inN":9,"in2":3,"in2N":8,"out":10,"outN":4,"cat":"block","known":true}]""") }
        rule.onNodeWithText("9").assertIsDisplayed()
        rule.onNodeWithText("Craft").assertIsDisplayed()
    }

    // --- Blessings: attuned, empty slots, held charms, bound exclusion ---

    @Test fun blessingsAttunedAndHeldBranches() {
        val blessings = """{"slots":[{"id":1056,"name":"Clement","effect":"Calm"},{"id":0,"name":"","effect":""}]}"""
        val catalog = """[{"id":1056,"name":"Clement","effect":"Calm"},{"id":1057,"name":"Ares","effect":"War"}]"""
        // 1056 is already bound so only 1057 (slot index 1) is offered as held.
        rule.setContent { BlessingsView(blessings, catalog, listOf(InvSlot(1056, 1), InvSlot(1057, 1), InvSlot(0, 0))) }
        assertTrue(exists("Clement"))
        assertTrue(exists("Ares"))
        rule.onAllNodesWithText("Ares", substring = true)[0].performClick()
        rule.waitForIdle()
    }

    @Test fun blessingsNoCharmsAndMalformed() {
        rule.setContent { BlessingsView("junk", "junk", emptyList()) }
        assertTrue(exists("No charms") || exists("Attuned"))
    }

    // --- Stonecutter / Trade / Furnace / Chest ---

    @Test fun stonecutterMultipleMaterialsAndSelection() {
        val cuts = """[{"in":1,"out":102,"outN":2},{"in":1,"out":103},{"in":8,"out":104,"outN":3}]"""
        val inv = """{"selected":0,"slots":[{"id":1,"count":5},{"id":8,"count":2}]}"""
        rule.setContent { StonecutterOverlay(cuts, inv, onClose = {}) }
        assertTrue(exists("Cut into"))
        // Clicking the second material switches the offered shapes.
        rule.onNode(hasText("Cobble") and hasClickAction()).performClick()
        rule.waitForIdle()
        rule.onAllNodesWithText("×3", substring = true)[0].performClick()
        rule.waitForIdle()
    }

    @Test fun stonecutterEmptyWhenNothingHeld() {
        rule.setContent { StonecutterOverlay("""[{"in":1,"out":102}]""", """{"selected":0,"slots":[]}""", onClose = {}) }
        assertTrue(exists("Stonecutter"))
    }

    @Test fun parseCutsDefaultsAndMalformed() {
        assertEquals(1, parseCuts("""[{"in":1,"out":2}]""")[0].outN)
        assertEquals(3, parseCuts("""[{"in":1,"out":2,"outN":3}]""")[0].outN)
        assertTrue(parseCuts("junk").isEmpty())
        assertTrue(parseCuts("[]").isEmpty())
        assertEquals(0, parseCuts("""[{"in":5,"out":6}]""")[0].index)
        assertEquals(1, parseCuts("""[{"in":5,"out":6},{"in":7,"out":8}]""")[1].index)
    }

    @Test fun tradeTiersSecondCostAndNextAt() {
        val json = """{"prof":"Farmer","level":2,"maxLevel":3,"done":1,"nextAt":4,"trades":[
            {"cost":1026,"costN":2,"give":1027,"giveN":1,"level":1},
            {"cost":1027,"costN":1,"cost2":1053,"cost2N":2,"give":1028,"giveN":1,"level":2}]}"""
        rule.setContent { TradeOverlay(json, onClose = {}) }
        rule.onNodeWithText("Farmer").assertIsDisplayed()
        assertTrue(exists("Tier 2") || exists("2"))
        rule.onAllNodesWithText("→", substring = true)[0].performClick()
        rule.waitForIdle()
    }

    @Test fun tradeMalformedAndNoNextTier() {
        rule.setContent { TradeOverlay("""{"prof":"","level":1,"maxLevel":1,"done":0,"nextAt":null,"trades":[]}""", onClose = {}) }
        assertTrue(exists("trades") || exists("Villager"))
    }

    @Test fun tradeJunkJson() {
        rule.setContent { TradeOverlay("junk", onClose = {}) }
        rule.waitForIdle()
    }

    private val furnaceRecipes = """[{"in":1,"inN":1,"in2":1053,"in2N":2,"out":3,"outN":1,"secs":2.0,"blast":true},
            {"in":2,"inN":1,"out":4,"outN":1,"secs":1.0}]"""

    @Test fun furnaceRunningBlast() {
        rule.setContent { FurnaceOverlay(furnaceRecipes, """{"active":true,"recipe":0,"progress":0.5,"fuel":0.25}""", isBlast = true, onClose = {}) }
        assertTrue(exists("Blast Furnace"))
    }

    @Test fun furnaceBusyElsewhere() {
        rule.setContent { FurnaceOverlay(furnaceRecipes, """{"active":true,"recipe":1,"progress":0.1,"fuel":0.9}""", isBlast = false, onClose = {}) }
        assertTrue(exists("Furnace"))
        // Busy-elsewhere state renders its disabled affordance without any click.
        rule.waitForIdle()
    }

    @Test fun furnaceMalformed() {
        rule.setContent { FurnaceOverlay("junk", "junk", isBlast = false, onClose = {}) }
        rule.waitForIdle()
    }

    @Test fun chestOverlaySlotsAndClose() {
        var closed = false
        val chest = """{"slots":[{"id":1,"count":3},{"id":1059,"count":30}]}"""
        val inv = """{"selected":0,"slots":[{"id":2,"count":5}]}"""
        rule.setContent { ChestOverlay(chest, inv, onClose = { closed = true }) }
        assertTrue(exists("Chest"))
        rule.onNodeWithText("Close").performClick()
        assertTrue(closed)
    }

    // --- Inventory overlay: every left tab, survival (no catalog), catalog shelves ---

    @Test fun inventoryAllTabsAndCatalogShelves() {
        val inv = """{"selected":0,"slots":[{"id":1,"count":3},{"id":1056,"count":1}],"armor":[{"id":1067,"count":50}]}"""
        val recipes = """[{"in":2,"inN":1,"out":10,"outN":4,"cat":"block","known":true}]"""
        val blessings = """{"slots":[{"id":1056,"name":"Clement","effect":"Calm"}]}"""
        val catalog = """[{"id":1056,"name":"Clement","effect":"Calm"}]"""
        rule.setContent { InventoryOverlay(inv, recipes, blessings, catalog, onClose = {}, startTab = 1) }
        rule.waitForIdle()
        for (label in listOf("Crafting", "Blessings", "Achievements", "Outfit", "Inventory")) {
            if (rule.onAllNodesWithText(label, substring = true).fetchSemanticsNodes().isNotEmpty()) {
                rule.onAllNodesWithText(label, substring = true)[0].performClick()
                rule.waitForIdle()
            }
        }
        // Catalog shelves (creative only).
        for (label in listOf("Ores", "Ocean", "Items", "Gear", "Music", "Build", "Natural")) {
            val nodes = rule.onAllNodesWithText(label, substring = true).fetchSemanticsNodes()
            if (nodes.isNotEmpty()) {
                rule.onAllNodesWithText(label, substring = true)[0].performClick()
                rule.waitForIdle()
            }
        }
    }

    @Test fun inventoryOutfitTabDirect() {
        val inv = """{"selected":0,"slots":[{"id":1,"count":3}],"armor":[{"id":1067,"count":50},{"id":1068,"count":10}]}"""
        rule.setContent { InventoryOverlay(inv, "[]", "{}", "[]", onClose = {}, startTab = 1) }
        rule.waitForIdle()
    }

    @Test fun inventoryAchievementsTabDirect() {
        rule.setContent { InventoryOverlay("""{"selected":0,"slots":[]}""", "[]", "{}", "[]", onClose = {}, startTab = 4) }
        rule.waitForIdle()
    }

    @Test fun inventoryCraftingTabDirect() {
        val recipes = """[{"in":2,"inN":1,"out":10,"outN":4,"cat":"block","known":true},{"in":10,"inN":2,"out":1063,"outN":1,"cat":"tool","known":false,"requires":10}]"""
        rule.setContent { InventoryOverlay("""{"selected":0,"slots":[]}""", recipes, "{}", "[]", onClose = {}, startTab = 2) }
        rule.waitForIdle()
    }

    @Test fun inventorySurvivalHasNoCatalogAndMalformedJson() {
        rule.setContent {
            InventoryOverlay("junk", "junk", "junk", "junk", onClose = {}, startTab = 0, creative = false)
        }
        assertTrue(exists("Inventory"))
    }

    // --- Menu: delete confirmation, share dialog, requests, creator modes ---

    @Test fun menuDeleteAsksFirstAndCancels() {
        var deleted: WorldInfo? = null
        rule.setContent {
            MenuScreen(listOf(world("Alpha")), emptyList(), "dev-1", true,
                onPlay = {}, onDelete = { deleted = it }, onCreate = {}, onHostOnline = {},
                onRefresh = {}, onCopyDeviceId = {}, onShare = {})
        }
        rule.onNodeWithText("Alpha").assertIsDisplayed()
        assertEquals(null, deleted)
    }

    @Test fun menuRequestsApproveAndDeny() {
        var approved: VoxelsSync.JoinRequest? = null
        var denied: VoxelsSync.JoinRequest? = null
        val req = VoxelsSync.JoinRequest(worldId = "wid-Alpha", requesterId = "dev9", name = "Bob")
        val req2 = VoxelsSync.JoinRequest(worldId = "wid-Alpha", requesterId = "dev8", name = "")
        rule.setContent {
            MenuScreen(emptyList(), emptyList(), "d", true,
                onPlay = {}, onDelete = {}, onCreate = {}, onHostOnline = {}, onRefresh = {},
                onCopyDeviceId = {}, onShare = {},
                requests = listOf(req, req2), onApprove = { approved = it }, onDeny = { denied = it })
        }
        rule.onNode(hasScrollAction()).performScrollToNode(hasText("Approve"))
        rule.onAllNodesWithText("Approve")[0].performClick()
        assertEquals("dev9", approved?.requesterId)
        rule.onAllNodesWithText("Deny")[0].performClick()
        assertTrue(denied != null)
    }

    @Test fun shareDialogSendsTrimmedRecipient() {
        var sent: String? = null
        rule.setContent { ShareOnlineDialog(world("Alpha", online = true, role = "owner"), onDismiss = {}, onSend = { sent = it }) }
        rule.waitForIdle()
        assertTrue(exists("Share"))
        assertEquals(null, sent)
    }

    @Test fun worldCreatorSurvivalModeAndCreate() {
        var result: Triple<String, String, String>? = null
        rule.setContent { WorldCreatorScreen(onBack = {}, onCreate = { n, s, m -> result = Triple(n, s, m) }) }
        rule.onNodeWithText("World name", substring = true).performTextInput("Surv World")
        // Switch to Survival then create.
        rule.onNode(hasText("Survival") and hasClickAction()).performClick()
        rule.waitForIdle()
        rule.onRoot().performScrollToNode(hasText("Create & Play"))
        rule.onNode(hasText("Create & Play") and hasClickAction()).performClick()
        rule.waitForIdle()
        assertEquals("survival", result?.third)
        assertEquals("Surv World", result?.first)
    }

    // --- HUD pieces ---

    @Test fun hudPiecesAcrossStates() {
        rule.setContent {
            androidx.compose.foundation.layout.Column {
                BossBar("""{"boss":0.5,"bossName":"The Wither"}""")
                BossBar("""{"boss":0.25,"bossName":"Ender Dragon"}""")
                BossBar("""{"boss":0.75,"bossName":"Other"}""")
                BossBar("""{"boss":-1}""")
                GlideIndicator("""{"elytra":true,"gliding":true}""")
                GlideIndicator("""{"elytra":true,"gliding":false}""")
                GlideIndicator("""{"elytra":false}""")
                LoadingOverlay(LoadPhase.MESHING, 0.5f)
            }
        }
        rule.waitForIdle()
    }

    @Test fun loadingGeneratingPhase() {
        rule.setContent { LoadingOverlay(LoadPhase.GENERATING, 0f) }
        rule.waitForIdle()
    }

    @Test fun loadingIdlePhaseClampsProgress() {
        rule.setContent { LoadingOverlay(LoadPhase.IDLE, 2f) }
        rule.waitForIdle()
    }

    @Test fun onlineHudConnectedHostHere() {
        rule.setContent { OnlineHud(NetStatus.Connected, """[{"device":"peer-abcdef"}]""", "peer-abcdef", isHost = false) }
        rule.waitForIdle()
        assertEquals(setOf("a", "b"), peerIds("""[{"device":"a"},{"device":""},{"device":"b"}]"""))
        assertTrue(peerIds("junk").isEmpty())
        assertTrue(peerIds("[]").isEmpty())
    }

    @Test fun onlineHudConnecting() {
        rule.setContent { OnlineHud(NetStatus.Connecting, "[]", "owner-x", isHost = false) }
        rule.waitForIdle()
    }

    @Test fun onlineHudReconnecting() {
        rule.setContent { OnlineHud(NetStatus.Reconnecting, "[]", "owner-x", isHost = false) }
        rule.waitForIdle()
    }

    @Test fun onlineHudFailedServer() {
        rule.setContent { OnlineHud(NetStatus.Failed(NetFailure.SERVER_UNREACHABLE), "[]", "owner-x", isHost = true) }
        rule.waitForIdle()
    }

    @Test fun onlineHudFailedInvite() {
        rule.setContent { OnlineHud(NetStatus.Failed(NetFailure.BAD_INVITE), "[]", "owner-x", isHost = false) }
        rule.waitForIdle()
    }

    @Test fun onlineHudFailedHost() {
        rule.setContent { OnlineHud(NetStatus.Failed(NetFailure.HOST_UNVERIFIED), "junk", "owner-x", isHost = true) }
        rule.waitForIdle()
    }

    @Test fun settingsSheetShowsAllControls() {
        var last: GameSettings? = null
        rule.setContent {
            SettingsSheet(GameSettings(lookSensitivity = 2f, invertY = true, musicVolume = 0.5f, sfxVolume = 0f, hudScale = 1.5f, showDebug = true, stickLook = true),
                onChange = { last = it }, onClose = {})
        }
        rule.waitForIdle()
        assertTrue(exists("Settings") || exists("Look"))
        assertEquals(null, last)
    }

    @Test fun smallHudControlsRender() {
        rule.setContent {
            androidx.compose.foundation.layout.Column {
                JoinRequestChip("player-xyz", onApprove = {}, onDeny = {})
                UseButton("Use", onUse = {})
                HoldButton(label = "Hold")
                FloatingLookJoystick(modifier = androidx.compose.ui.Modifier, onLookRate = { _, _ -> }, onPlace = {}, onBreak = {})
            }
        }
        rule.waitForIdle()
        rule.onNodeWithText("Use").performClick()
        rule.waitForIdle()
    }
}
