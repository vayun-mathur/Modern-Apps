package com.vayunmathur.games.voxels.domain

import kotlin.test.Test
import kotlin.test.assertEquals

class BackTargetTest {
    @Test fun `back with nothing open pauses instead of quitting`() {
        assertEquals(BackTarget.PAUSE, backTarget(OverlayFlags()))
    }

    @Test fun `back from the pause menu resumes`() {
        assertEquals(BackTarget.RESUME, backTarget(OverlayFlags(paused = true)))
    }

    @Test fun `back closes the top-most overlay first`() {
        assertEquals(BackTarget.CHEST, backTarget(OverlayFlags(chest = true, inventory = true, paused = true)))
        assertEquals(BackTarget.INVENTORY, backTarget(OverlayFlags(inventory = true, paused = true)))
        assertEquals(BackTarget.TRADE, backTarget(OverlayFlags(trade = true)))
        assertEquals(BackTarget.FURNACE, backTarget(OverlayFlags(furnace = true)))
        assertEquals(BackTarget.STONECUTTER, backTarget(OverlayFlags(stonecutter = true)))
    }
}
