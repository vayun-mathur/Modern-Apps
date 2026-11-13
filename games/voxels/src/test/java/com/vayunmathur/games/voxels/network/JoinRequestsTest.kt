package com.vayunmathur.games.voxels.network

import kotlin.test.Test
import kotlin.test.assertEquals

class JoinRequestsTest {
    private fun req(world: String, who: String) = VoxelsSync.JoinRequest(worldId = world, requesterId = who)

    @Test fun `only unhandled requests for owned worlds, once each`() {
        val all = listOf(req("w1", "a"), req("w1", "a"), req("w2", "b"), req("w1", "c"))
        val pending = JoinRequests.pending(all, owned = setOf("w1"), handled = setOf("w1|c"))
        assertEquals(listOf(req("w1", "a")), pending)
    }
}
