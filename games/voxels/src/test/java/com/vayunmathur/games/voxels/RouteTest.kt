package com.vayunmathur.games.voxels

import com.vayunmathur.library.util.NavBackStack
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class RouteTest {
    @Test fun `game stack starts on the world it was opened for`() {
        val stack = NavBackStack<Route>(arrayOf(Route.Game("world-7")))
        assertEquals(Route.Game("world-7"), stack.last())
        assertEquals("world-7", (stack.last() as Route.Game).worldId)
    }

    @Test fun `back pops overlays in stack order, top first`() {
        // The old hand-encoded priority (settings over chest over inventory) is now
        // simply the order the screens were pushed: Back takes the top one first.
        val stack = NavBackStack<Route>(arrayOf(Route.Game("w")))
        stack.add(Route.Inventory(startTab = 2))
        stack.add(Route.Chest)
        stack.add(Route.Settings)
        assertEquals(Route.Settings, stack.last())
        stack.pop()
        assertEquals(Route.Chest, stack.last())
        stack.pop()
        val inventory = stack.last()
        assertIs<Route.Inventory>(inventory)
        assertEquals(2, inventory.startTab)
        stack.pop()
        assertEquals(Route.Game("w"), stack.last())
    }

    @Test fun `overlay routes carry their opening arguments`() {
        assertTrue(Route.Furnace(isBlast = true).isBlast)
        assertEquals(0, Route.Inventory().startTab)
        assertEquals(3, Route.Inventory(startTab = 3).startTab)
    }

    @Test fun `menu creator pushes over the world list and pops back`() {
        val stack = NavBackStack<Route>(arrayOf(Route.Worlds))
        stack.add(Route.CreateWorld)
        assertEquals(Route.CreateWorld, stack.last())
        stack.pop()
        assertEquals(Route.Worlds, stack.last())
    }
}
