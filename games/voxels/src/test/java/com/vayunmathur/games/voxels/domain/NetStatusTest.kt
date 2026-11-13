package com.vayunmathur.games.voxels.domain

import kotlin.test.Test
import kotlin.test.assertEquals

class NetStatusTest {
    @Test fun `joining shows connecting, not connection lost`() {
        assertEquals(NetStatus.Connecting, netStatusOf(live = false, everConnected = false))
        assertEquals(NetStatus.Connected, netStatusOf(live = true, everConnected = false))
        assertEquals(NetStatus.Reconnecting, netStatusOf(live = false, everConnected = true))
    }

    @Test fun `peer diffs report joins and leaves`() {
        val c = peerChanges(setOf("a", "b"), setOf("b", "c"))
        assertEquals(setOf("c"), c.joined)
        assertEquals(setOf("a"), c.left)
        assertEquals(PeerChanges(emptySet(), emptySet()), peerChanges(setOf("x"), setOf("x")))
    }

    @Test fun `players are labelled by their id suffix`() {
        assertEquals("9f3e", playerSuffix("0123-4567-9f3e"))
    }
}
