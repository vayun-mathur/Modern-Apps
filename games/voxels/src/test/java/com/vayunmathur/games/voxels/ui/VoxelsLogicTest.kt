package com.vayunmathur.games.voxels.ui

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.runner.RunWith

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Robolectric tests for voxels JSON parsers (org.json), catalog and durability rules. */
@RunWith(AndroidJUnit4::class)
class VoxelsLogicTest {
    @Test fun parseSmeltRecipesAndState() {
        val r = parseSmeltRecipes("""[{"in":1,"inN":2,"out":3,"outN":1,"secs":2.5,"blast":true},{"in":4,"inN":1,"in2":5,"in2N":3,"out":6,"outN":2,"secs":1.0}]""")
        assertEquals(2, r.size); assertTrue(r[0].blast); assertEquals(5, r[1].in2Id); assertEquals(2.5f, r[0].secs)
        assertEquals(emptyList(), parseSmeltRecipes("junk")); assertEquals(emptyList(), parseSmeltRecipes("{}"))
        val s = parseSmeltState("""{"active":true,"recipe":2,"progress":0.5,"fuel":0.25}""")
        assertTrue(s.active); assertEquals(2, s.recipe); assertEquals(0.5f, s.progress)
        assertEquals(SmeltState(false, 0, 0f, 0f), parseSmeltState("junk"))
    }
    @Test fun parseCutsDefaultsOutN() {
        val c = parseCuts("""[{"in":1,"out":102},{"in":1,"out":103,"outN":4}]""")
        assertEquals(listOf(CutRecipe(0, 1, 102, 1), CutRecipe(1, 1, 103, 4)), c)
        assertEquals(emptyList(), parseCuts("[]")); assertEquals(emptyList(), parseCuts("x"))
    }
    @Test fun durabilityBoundaries() {
        assertTrue(isDurabilityItem(1059)); assertTrue(isDurabilityItem(1074)); assertTrue(isDurabilityItem(1093))
        assertTrue(isDurabilityItem(1140)); assertTrue(isDurabilityItem(1082)); assertTrue(isDurabilityItem(1151))
        assertFalse(isDurabilityItem(1058)); assertFalse(isDurabilityItem(1)); assertFalse(isDurabilityItem(1083))
    }
    @Test fun catalogNamesAndDefaults() {
        assertEquals("Stone", blockNames[1]); assertEquals("Crafting Table", blockNames[34])
        assertEquals("Stonecutter", blockNames[118]); assertTrue(blockNames.size > 150)
        val inv = InventoryState()
        assertEquals(0, inv.selected); assertEquals(9, inv.slots.size); assertTrue(inv.slots.all { it.id == 0 })
        val h = HealthJson(); assertEquals(20f, h.hp); assertFalse(h.dead)
    }
}
