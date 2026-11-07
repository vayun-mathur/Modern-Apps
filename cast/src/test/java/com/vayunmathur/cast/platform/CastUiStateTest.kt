package com.vayunmathur.cast.platform

import com.vayunmathur.cast.domain.CastDevice
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CastUiStateTest {

    private val tv = CastDevice(id = "tv1", friendlyName = "Living Room", host = "10.0.0.2", port = 9)

    @Test
    fun `defaults are disconnected and cannot mirror`() {
        val state = CastUiState()
        assertEquals(CastConnection.Disconnected, state.connection)
        assertEquals(MirrorPhase.Idle, state.mirrorPhase)
        assertFalse(state.isMirroring)
        assertFalse(state.canMirror)
        assertTrue(state.devices.isEmpty())
        assertFalse(state.isScanning)
    }

    @Test
    fun `isMirroring only when phase is Mirroring`() {
        assertTrue(CastUiState(mirrorPhase = MirrorPhase.Mirroring).isMirroring)
        assertFalse(CastUiState(mirrorPhase = MirrorPhase.Negotiating).isMirroring)
        assertFalse(CastUiState(mirrorPhase = MirrorPhase.Failed).isMirroring)
        assertFalse(CastUiState(mirrorPhase = MirrorPhase.Idle).isMirroring)
    }

    @Test
    fun `canMirror only when connected`() {
        assertTrue(CastUiState(connection = CastConnection.Connected, connectedDevice = tv).canMirror)
        for (c in CastConnection.entries.filter { it != CastConnection.Connected }) {
            assertFalse(CastUiState(connection = c, connectedDevice = tv).canMirror, "canMirror for $c")
        }
    }

    @Test
    fun `noop actions do nothing and do not throw`() {
        val actions = CastActions.Noop
        actions.startScan(); actions.stopScan(); actions.connect(tv); actions.disconnect()
        actions.submitPairCode("123456"); actions.startMirroring(); actions.stopMirroring()
        actions.openLocalNetworkSettings()
    }

    @Test
    fun `serving and failed content results carry their payload`() {
        val serving = ContentSessionResult.Serving(receiverName = "TV", hasVideo = false)
        assertEquals("TV", serving.receiverName)
        assertFalse(serving.hasVideo)
        assertEquals(7, ContentSessionResult.Failed(7).reason)
    }

    @Test
    fun `client state defaults and outcome equality`() {
        val state = com.vayunmathur.cast.domain.ClientState()
        kotlin.test.assertEquals(com.vayunmathur.cast.domain.ClientPhase.Idle, state.phase)
        kotlin.test.assertNull(state.failure)
        kotlin.test.assertNull(state.negotiation)
        val needs = com.vayunmathur.cast.platform.HandshakeOutcome.NeedsCode(2, codeChanged = true)
        kotlin.test.assertEquals(needs, needs.copy())
        kotlin.test.assertNotEquals(needs, needs.copy(attemptsLeft = 1))
        val serving = ContentSessionResult.Serving("TV", hasVideo = true)
        kotlin.test.assertTrue(serving.hasVideo)
    }

    @Test
    fun `device defaults instance name to id and version to zero`() {
        val d = CastDevice(id = "abc", friendlyName = "TV", host = "h", port = 1)
        assertEquals("abc", d.instanceName)
        assertEquals(0, d.protocolVersion)
        assertEquals(d, d.copy())
        assertEquals("Bedroom", d.copy(friendlyName = "Bedroom").friendlyName)
    }
}
