package com.vayunmathur.games.voxels.ui

import com.vayunmathur.games.voxels.network.VoxelsRoles
import kotlin.test.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
class VoxelLogicTest {

    @Test fun `smelt recipes parse full and defaulted fields`() {
        val recipes = parseSmeltRecipes(
            """[{"in":1,"inN":2,"out":3,"outN":1,"secs":2.5},{"in":4,"inN":1,"in2":5,"in2N":3,"out":6,"outN":2,"secs":4.0,"blast":true}]"""
        )
        assertEquals(2, recipes.size)
        assertEquals(SmeltRecipe(1, 2, 0, 0, 3, 1, 2.5f, false), recipes[0])
        assertEquals(SmeltRecipe(4, 1, 5, 3, 6, 2, 4.0f, true), recipes[1])
        assertTrue(parseSmeltRecipes("not json").isEmpty())
        assertTrue(parseSmeltRecipes("[]").isEmpty())
        assertTrue(parseSmeltRecipes("""[{"in":1}]""").isEmpty()) // missing required fields drops the whole list
    }

    @Test fun `smelt state defaults on missing or malformed json`() {
        assertEquals(SmeltState(true, 2, 0.5f, 0.25f), parseSmeltState("""{"active":true,"recipe":2,"progress":0.5,"fuel":0.25}"""))
        assertEquals(SmeltState(false, 0, 0f, 0f), parseSmeltState("{}"))
        assertEquals(SmeltState(false, 0, 0f, 0f), parseSmeltState("junk"))
    }

    @Test fun `durability items cover tools armor and specials only`() {
        assertTrue(isDurabilityItem(1059)) // wood pickaxe
        assertTrue(isDurabilityItem(1074)) // diamond boots
        assertTrue(isDurabilityItem(1093)) // adamant pickaxe
        assertTrue(isDurabilityItem(1140)) // bronze boots
        assertTrue(isDurabilityItem(1082)) // flint and steel
        assertTrue(isDurabilityItem(1084)) // elytra
        assertTrue(isDurabilityItem(1149)) // fishing rod
        assertFalse(isDurabilityItem(1)) // stone block
        assertFalse(isDurabilityItem(1026)) // apple
        assertFalse(isDurabilityItem(1099)) // blessing
    }

    @Test fun `catalog names blocks items and blessings`() {
        assertEquals("Stone", blockNames[1])
        assertEquals("Diamond Ore", blockNames[20])
        assertEquals("Apple", blockNames[1026])
        assertEquals("Blessing of Athena", blockNames[1141])
        assertEquals("Fishing Rod", blockNames[1149])
        assertEquals(null, blockNames[99999])
        assertTrue(blockNames.values.all { it.isNotBlank() })
    }

    @Test fun `roles gate editing`() {
        assertTrue(VoxelsRoles.canEdit(VoxelsRoles.OWNER))
        assertTrue(VoxelsRoles.canEdit(VoxelsRoles.EDITOR))
        assertFalse(VoxelsRoles.canEdit("viewer"))
        assertFalse(VoxelsRoles.canEdit(""))
    }
}
