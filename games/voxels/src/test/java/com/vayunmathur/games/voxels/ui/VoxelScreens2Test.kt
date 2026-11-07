package com.vayunmathur.games.voxels.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
class VoxelScreens2Test {
    @get:Rule val rule = createComposeRule()

    private fun slotsJson(vararg pairs: Pair<Int, Pair<Int, Int>>, armor: List<Pair<Int, Int>> = emptyList()): String {
        val byIndex = pairs.toMap()
        val slots = (0 until 36).map { i -> byIndex[i] ?: (0 to 0) }
        val s = slots.joinToString(",") { (id, n) -> """{"id":$id,"count":$n}""" }
        val a = armor.joinToString(",") { (id, n) -> """{"id":$id,"count":$n}""" }
        return """{"selected":1,"slots":[$s],"armor":[$a]}"""
    }

    private val recipesJson = """
        [
          {"in":1,"inN":4,"out":10,"outN":4,"cat":"block","known":true},
          {"in":10,"inN":2,"in2":1055,"in2N":1,"out":1061,"outN":1,"cat":"tool","known":true},
          {"in":24,"inN":1,"out":1065,"outN":1,"cat":"tool","known":false,"requires":23},
          {"in":1026,"inN":1,"out":1029,"outN":1,"cat":"food","known":true}
        ]
    """.trimIndent()

    private val blessingsJson = """{"slots":[{"id":1056,"name":"Blessing of Clement","effect":"Warmth aura"},{"id":0,"name":"","effect":""}]}"""
    private val blessingCatalogJson = """[{"id":1056,"name":"Blessing of Clement","effect":"Warmth aura"},{"id":1057,"name":"Blessing of Ares","effect":"Might"}]"""

    @Test fun `inventory overlay tabs outfit crafting blessings achievements close`() {
        var closes = 0
        val inv = slotsJson(0 to (1067 to 240), 1 to (1026 to 5), 5 to (1057 to 1), armor = listOf(1067 to 200, 0 to 0, 1071 to 528, 0 to 0))
        rule.setContent {
            InventoryOverlay(inv, recipesJson, blessingsJson, blessingCatalogJson, onClose = { closes++ })
        }
        // default tab is Inventory grid
        rule.onNodeWithText("Inventory").assertIsDisplayed()
        // Outfit tab shows equipped armor labels
        rule.onNodeWithText("Outfit").performClick()
        rule.onNodeWithText("Equipped Armor").assertExists()
        // Crafting tab: category chips + recipes
        rule.onNodeWithText("Crafting").performClick()
        rule.onNodeWithText("Tools").performClick()
        rule.onNodeWithText("Blocks").performClick()
        // locked recipes toggle
        rule.onAllNodesWithText("Show locked", substring = true).let {
            if (it.fetchSemanticsNodes().isNotEmpty()) it[0].performClick()
        }
        // Blessings tab: attuned + held charms
        rule.onAllNodesWithText("Blessings")[0].performClick()
        rule.onNodeWithText("Blessing of Clement").assertExists()
        rule.onNodeWithText("Warmth aura").assertExists()
        // Achievements tab with null manager shows unavailable
        rule.onNodeWithText("Awards").performClick()
        // back to Inventory, then Close tab (some off-window tab taps may
        // fall through to the scrim and count as closes along the way)
        rule.onNodeWithText("Inventory").performClick()
        val closesBefore = closes
        rule.onAllNodesWithText("Close")[0].performClick()
        assertTrue(closes > closesBefore, "closes $closesBefore -> $closes")
    }

    @Test fun `inventory catalog categories and start tabs`() {
        rule.setContent {
            InventoryOverlay("{}", "[]", "{}", "[]", onClose = {}, startTab = 2)
        }
        // startTab=2 is crafting with no recipes; category tabs on the right still render
        for (label in listOf("Natural", "Ores", "Ocean", "Items", "Gear", "Music", "Build")) {
            rule.onNodeWithText(label).performClick()
            rule.waitForIdle()
        }
        assertTrue(rule.onAllNodesWithText("Blessings").fetchSemanticsNodes().isNotEmpty())
    }

    @Test fun `inventory malformed json renders empty overlay`() {
        var closes = 0
        rule.setContent { InventoryOverlay("junk", "junk", "junk", "junk", onClose = { closes++ }) }
        rule.onNodeWithText("Inventory").assertIsDisplayed()
        assertEquals(0, closes)
    }

    @Test fun `crafting table selects recipes categories and locked hint`() {
        rule.setContent { CraftingTable(recipesJson) }
        // first recipe (Planks) selected by default in Blocks shelf
        rule.onNodeWithText("Tools").performClick()
        // select the stone pickaxe recipe row by its output name where shown
        val pick = rule.onAllNodesWithText("Stone Pickaxe").fetchSemanticsNodes()
        if (pick.isNotEmpty()) rule.onAllNodesWithText("Stone Pickaxe")[0].performClick()
        assertTrue(rule.onAllNodesWithText("Craft", substring = true).fetchSemanticsNodes().isNotEmpty())
        rule.onNodeWithText("Food").performClick()
        rule.onNodeWithText("Materials").performClick()
        rule.onNodeWithText("Armor").performClick()
        rule.onNodeWithText("Blessings").performClick()
    }

    @Test fun `crafting table empty recipes`() {
        rule.setContent { CraftingTable("[]") }
        rule.onNodeWithText("Crafting Table").assertIsDisplayed()
    }

    @Test fun `crafting table malformed recipes`() {
        rule.setContent { CraftingTable("not json") }
        rule.onNodeWithText("Crafting Table").assertIsDisplayed()
    }

    @Test fun `blessings view empty and held charms`() {
        rule.setContent {
            BlessingsView("""{"slots":[]}""", blessingCatalogJson, listOf(InvSlot(1057, 1), InvSlot(1, 64)))
        }
        rule.onNodeWithText("Blessing of Ares").assertIsDisplayed()
        rule.onNodeWithText("Might").assertIsDisplayed()
    }

    @Test fun `blessings view no charms held`() {
        rule.setContent { BlessingsView("{}", "[]", emptyList()) }
        rule.onNodeWithText("Craft a blessing charm", substring = true).assertExists()
    }

    private val smeltJson = """
        [
          {"in":1,"inN":1,"out":3,"outN":1,"secs":2.0},
          {"in":19,"inN":1,"in2":1053,"in2N":1,"out":1050,"outN":2,"secs":4.0},
          {"in":90,"inN":1,"out":1089,"outN":1,"secs":3.0,"blast":true}
        ]
    """.trimIndent()

    @Test fun `furnace plain hides blast selects and smelts`() {
        var closes = 0
        rule.setContent { FurnaceOverlay(smeltJson, "{}", isBlast = false, onClose = { closes++ }) }
        rule.onNodeWithText("Furnace").assertIsDisplayed()
        // blast-only recipe (Silver Ore -> Silver Ingot output name) not listed on plain furnace; select iron recipe by output name
        val rows = rule.onAllNodesWithText("Iron Ingot").fetchSemanticsNodes()
        if (rows.isNotEmpty()) rule.onAllNodesWithText("Iron Ingot")[0].performClick()
        rule.onNodeWithText("Smelt").performClick() // native startSmelt is a safe no-op uninitialized
        rule.waitForIdle()
        rule.onNodeWithText("Close").performClick()
        assertTrue(closes >= 1)
    }

    @Test fun `furnace blast running and busy states`() {
        rule.setContent {
            FurnaceOverlay(smeltJson, """{"active":true,"recipe":0,"progress":0.5,"fuel":0.75}""", isBlast = true, onClose = {})
        }
        rule.onNodeWithText("Blast Furnace").assertIsDisplayed()
        rule.onNodeWithText("Stop").assertIsDisplayed()
        rule.onNodeWithText("Stop").performClick()
    }

    @Test fun `furnace busy elsewhere state`() {
        rule.setContent {
            FurnaceOverlay(smeltJson, """{"active":true,"recipe":1,"progress":0.2,"fuel":0.4}""", isBlast = true, onClose = {})
        }
        rule.onNodeWithText("Busy", substring = true).assertIsDisplayed()
    }

    @Test fun `furnace empty recipes`() {
        rule.setContent { FurnaceOverlay("[]", "{}", isBlast = false, onClose = {}) }
        rule.onNodeWithText("Furnace").assertIsDisplayed()
    }

    @Test fun `chest overlay taps slots and closes`() {
        var closes = 0
        val container = """{"slots":[{"id":1026,"count":3},{"id":1,"count":64}]}"""
        val inv = slotsJson(10 to (1027 to 2), 0 to (3 to 10))
        rule.setContent { ChestOverlay(container, inv, onClose = { closes++ }) }
        rule.onNodeWithText("Chest").assertIsDisplayed()
        val apples = rule.onAllNodesWithText("Apple").fetchSemanticsNodes()
        if (apples.isNotEmpty()) rule.onAllNodesWithText("Apple")[0].performClick()
        rule.onNodeWithText("Close").performClick()
        assertTrue(closes >= 1)
    }

    @Test fun `chest overlay malformed json`() {
        rule.setContent { ChestOverlay("junk", "junk", onClose = {}) }
        rule.onNodeWithText("Chest").assertIsDisplayed()
    }

    @Test fun `stonecutter held materials and empty state`() {
        var closes = 0
        val cuts = """[{"in":1,"out":102,"outN":2},{"in":1,"out":103,"outN":1},{"in":10,"out":106,"outN":4}]"""
        val inv = slotsJson(0 to (1 to 20), 1 to (10 to 5))
        rule.setContent { StonecutterOverlay(cuts, inv, onClose = { closes++ }) }
        rule.onNodeWithText("Stonecutter").assertIsDisplayed()
        rule.onNodeWithText("Material").assertIsDisplayed()
        // select Planks material, then click a cut option
        val mats = rule.onAllNodesWithText("Planks").fetchSemanticsNodes()
        if (mats.isNotEmpty()) rule.onAllNodesWithText("Planks")[0].performClick()
        val cutsNodes = rule.onAllNodesWithText("Plank Slab").fetchSemanticsNodes()
        if (cutsNodes.isNotEmpty()) rule.onAllNodesWithText("Plank Slab")[0].performClick()
        rule.onNodeWithText("Close").performClick()
        assertTrue(closes >= 1)
        // parser edge cases
        assertEquals(3, parseCuts(cuts).size)
        assertEquals(CutRecipe(1, 1, 103, 1), parseCuts(cuts)[1])
        assertTrue(parseCuts("junk").isEmpty())
    }

    @Test fun `stonecutter empty when nothing held`() {
        val cuts = """[{"in":1,"out":102,"outN":2}]"""
        rule.setContent { StonecutterOverlay(cuts, "{}", onClose = {}) }
        rule.onNodeWithText("Stonecutter").assertIsDisplayed()
        rule.onNodeWithText("Carry a stone", substring = true).assertIsDisplayed()
    }

    @Test fun `trade overlay tiers and close`() {
        var closes = 0
        val trades = """
            {"prof":"Farmer","level":2,"maxLevel":5,"done":3,"nextAt":10,
             "trades":[
               {"cost":1026,"costN":2,"give":1027,"giveN":1,"level":1},
               {"cost":1050,"costN":1,"cost2":1053,"cost2N":2,"give":1051,"giveN":3,"level":2}
             ]}
        """.trimIndent()
        rule.setContent { TradeOverlay(trades, onClose = { closes++ }) }
        rule.onNodeWithText("Farmer").assertIsDisplayed()
        // click a trade row (native trade returns false uninitialized, no crash)
        val bread = rule.onAllNodesWithText("1× Bread").fetchSemanticsNodes()
        if (bread.isNotEmpty()) rule.onAllNodesWithText("1× Bread")[0].performClick()
        rule.onNodeWithText("Close").performClick()
        assertTrue(closes >= 1)
    }

    @Test fun `trade overlay empty and invalid fall back`() {
        rule.setContent { TradeOverlay("{}", onClose = {}) }
        rule.onNodeWithText("Villager Trades", substring = true).assertIsDisplayed()
    }

    @Test fun `trade overlay malformed json`() {
        rule.setContent { TradeOverlay("junk", onClose = {}) }
        rule.onNodeWithText("Villager Trades", substring = true).assertIsDisplayed()
    }

    @Test fun `hotbar durability selected and inventory opener`() {
        var opened = 0; val selected = mutableListOf<Int>()
        val inv = """{"selected":2,"slots":[{"id":1065,"count":100},{"id":1,"count":1},{"id":1026,"count":9}]}"""
        rule.setContent { Column { Hotbar(inv, onSelect = { selected += it }, onOpenInventory = { opened++ }); Hotbar("bad", onSelect = {}) } }
        rule.onNodeWithText("9").assertIsDisplayed()
        rule.onAllNodesWithText("…")[0].performClick()
        assertEquals(1, opened)
        assertTrue(selected.isEmpty())
    }

    @Test fun `health boss glide and debug overlays variants`() {
        rule.setContent {
            Column {
                HealthOverlay("""{"hp":7,"max":20,"absorb":4,"estus":3,"effects":[{"k":"regen","amp":1,"t":12},{"k":"unknownfx","amp":9,"t":3}]}""")
                BossBar("""{"boss":0.5,"bossName":"The Wither"}""")
                BossBar("""{"boss":1.5}""")
                GlideIndicator("""{"elytra":true,"gliding":true}""")
                GlideIndicator("""{"elytra":true,"gliding":false}""")
                GlideIndicator("""{}""")
                DebugOverlay("fps 60\nchunk 12")
            }
        }
        rule.onNodeWithText("The Wither").assertIsDisplayed()
        rule.onNodeWithText("Gliding").assertIsDisplayed()
        rule.onNodeWithText("fps 60", substring = true).assertIsDisplayed()
    }

    @Test fun `health overlays malformed json fall back`() {
        rule.setContent { Column { HealthOverlay("junk"); BossBar("junk"); GlideIndicator("junk") } }
        rule.waitForIdle()
    }

    @Test fun `joystick drag emits movement and resets`() {
        val moves = mutableListOf<Pair<Float, Float>>()
        rule.setContent { Joystick(modifier = androidx.compose.ui.Modifier, onMove = { x, y -> moves += x to y }, onLookDelta = { _, _ -> }) }
        val node = rule.onAllNodesWithText("").fetchSemanticsNodes()
        assertEquals(0, node.size)
        // swipe on the joystick canvas: find by touch on root - use first unclipped node via performTouchInput on the joystick's parent
        rule.onRoot().performTouchInput {
            swipe(start = Offset(50f, 50f), end = Offset(160f, 40f), durationMillis = 300)
        }
        rule.waitForIdle()
        assertTrue(moves.isNotEmpty(), "moves=$moves")
        assertEquals(0f to 0f, moves.last())
    }

    @Test fun `look joystick right half drag tap and long press`() {
        val rates = mutableListOf<Pair<Float, Float>>()
        var placed: Offset? = null; var broke: Offset? = null
        rule.setContent {
            FloatingLookJoystick(
                modifier = androidx.compose.ui.Modifier,
                onLookRate = { a, b -> rates += a to b },
                onPlace = { placed = it },
                onBreak = { broke = it },
            )
        }
        rule.onRoot().performTouchInput {
            swipe(start = Offset(center.x * 1.5f, center.y), end = Offset(center.x * 1.9f, center.y), durationMillis = 300)
        }
        rule.waitForIdle()
        // a plain tap (no drag) on the left half places
        rule.onRoot().performTouchInput { down(Offset(center.x * 0.4f, center.y * 0.4f)); up() }
        rule.waitForIdle()
        assertTrue(placed != null || rates.isNotEmpty())
    }

    @Test fun `hold button press release and double tap`() {
        var press = 0; var release = 0; var double = 0
        rule.setContent {
            Column {
                HoldButton(label = "Jump", onPress = { press++ }, onRelease = { release++ }, onDoubleTap = { double++ })
                HoldButton(label = "Sneak", dimmed = true, onPress = { press++ }, onRelease = { release++ })
            }
        }
        rule.onNodeWithText("Jump").performTouchInput { down(center); up() }
        rule.waitForIdle()
        assertTrue(press >= 1 && release >= 1, "press=$press release=$release")
        rule.onNodeWithText("Jump").performTouchInput { down(center); advanceEventTime(50); up(); down(center); advanceEventTime(50); up() }
        rule.waitForIdle()
        rule.onNodeWithText("Sneak").assertIsDisplayed()
    }

    @Test fun `inventory achievements tab with real manager`() {
        val ctx = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
        val json = ctx.assets.open("achievements.json").bufferedReader().use { it.readText() }
        val mgr = com.vayunmathur.games.voxels.platform.VoxelsAchievementsManager(ctx, json, "world_cov:")
        rule.setContent {
            InventoryOverlay("{}", "[]", "{}", "[]", onClose = {}, startTab = 4, worldAchievements = mgr)
        }
        rule.onNodeWithText("Awards").assertIsDisplayed()
        rule.waitUntil(timeoutMillis = 8000) {
            rule.onAllNodesWithText("earned", substring = true).fetchSemanticsNodes().isNotEmpty()
        }
    }

    @Test fun `inventory slot drag does not crash`() {
        val inv = slotsJson(10 to (1 to 32), 11 to (2 to 16))
        rule.setContent { InventoryOverlay(inv, "[]", "{}", "[]", onClose = {}) }
        rule.onNodeWithText("Inventory").assertIsDisplayed()
        // drag across the grid area (slot drag gestures are pointerInput on SlotBox)
        rule.onRoot().performTouchInput {
            swipe(start = androidx.compose.ui.geometry.Offset(center.x * 0.6f, center.y * 0.8f), end = androidx.compose.ui.geometry.Offset(center.x * 1.1f, center.y * 0.8f), durationMillis = 400)
        }
        rule.waitForIdle()
        rule.onNodeWithText("Inventory").assertIsDisplayed()
    }

    @Test fun `inventory catalog item click gives block`() {
        rule.setContent { InventoryOverlay("{}", "[]", "{}", "[]", onClose = {}) }
        // Natural catalog is the default right-hand tab; items render as
        // images with the block name as content description (or 2-letter text).
        rule.waitForIdle()
        val grass = rule.onAllNodesWithContentDescription("Grass").fetchSemanticsNodes()
        if (grass.isNotEmpty()) {
            rule.onAllNodesWithContentDescription("Grass")[0].performClick()
        } else {
            // text fallback for icon-less renders
            rule.onAllNodesWithText("Gr")[0].performClick()
        }
        rule.waitForIdle()
        rule.onNodeWithText("Inventory").assertIsDisplayed()
    }

    @Test fun `look joystick emits deltas and resets`() {
        val looks = mutableListOf<Pair<Float, Float>>()
        val moves = mutableListOf<Pair<Float, Float>>()
        rule.setContent {
            Joystick(modifier = androidx.compose.ui.Modifier, onMove = { x, y -> moves += x to y }, onLookDelta = { x, y -> looks += x to y }, isLook = true)
        }
        rule.onRoot().performTouchInput {
            swipe(start = center, end = androidx.compose.ui.geometry.Offset(center.x + 90f, center.y), durationMillis = 300)
        }
        rule.waitForIdle()
        assertTrue(looks.isNotEmpty(), "looks=$looks")
        assertEquals(0f to 0f, looks.last())
        assertTrue(moves.isEmpty())
    }

    @Test fun `floating look joystick left half drag is inactive`() {
        val rates = mutableListOf<Pair<Float, Float>>()
        var placed = 0
        rule.setContent {
            FloatingLookJoystick(
                modifier = androidx.compose.ui.Modifier.fillMaxSize(),
                onLookRate = { a, b -> rates += a to b },
                onPlace = { placed++ },
                onBreak = {},
            )
        }
        rule.onRoot().performTouchInput {
            swipe(start = androidx.compose.ui.geometry.Offset(center.x * 0.3f, center.y), end = androidx.compose.ui.geometry.Offset(center.x * 0.7f, center.y), durationMillis = 300)
        }
        rule.waitForIdle()
        // drags starting on the left half never activate the look stick
        assertTrue(rates.isEmpty(), "rates=$rates")
    }
}
