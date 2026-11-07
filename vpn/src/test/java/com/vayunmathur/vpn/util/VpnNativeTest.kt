package com.vayunmathur.vpn.util

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.Assume

class VpnNativeTest {
    private fun nativeAvailable(): Boolean = try {
        VpnNative.init(); true
    } catch (_: UnsatisfiedLinkError) { false } catch (_: NoClassDefFoundError) { false }

    @Test fun keyGenerationAndDerivation() {
        Assume.assumeTrue("host vpn_wireguard lib unavailable", nativeAvailable())
        val priv = VpnNative.generatePrivateKey()
        assertTrue(priv.isNotBlank())
        val pub1 = VpnNative.derivePublicKey(priv)
        val pub2 = VpnNative.derivePublicKey(priv)
        assertTrue(pub1.isNotBlank()); assertEquals(pub1, pub2) // derivation is deterministic
        assertTrue(priv != pub1)
    }

    @Test fun tunnelLifecycleAndStats() {
        Assume.assumeTrue("host vpn_wireguard lib unavailable", nativeAvailable())
        val priv = VpnNative.generatePrivateKey()
        val pub = VpnNative.derivePublicKey(priv)
        val handle = VpnNative.newTunnel(priv, pub, "", 25)
        assertTrue(handle > 0, "handle=$handle")
        try {
            val hs = VpnNative.formatHandshakeInit(handle)
            assertNotNull(hs); assertTrue(hs.isNotEmpty())
            // Garbage incoming packet is rejected, not a crash
            assertNull(VpnNative.consumeIncomingPacketDetailed(handle, byteArrayOf(1, 2, 3)))
            val stats = VpnNative.getStats(handle)
            assertNotNull(stats); assertTrue(stats.contains("handshake"), "stats=$stats")
            // Encapsulating a non-IP payload may return null or a packet, but must not crash
            VpnNative.encapsulate(handle, byteArrayOf(0x45, 0, 0, 20))
            VpnNative.tickTimersDetailed(handle)
        } finally { VpnNative.freeTunnel(handle) }
    }

    @Test fun invalidTunnelArgsReturnError() {
        Assume.assumeTrue("host vpn_wireguard lib unavailable", nativeAvailable())
        val handle = VpnNative.newTunnel("not-base64!!", "also-bad!!", "", 0)
        assertTrue(handle <= 0, "bad keys should fail, got handle=$handle")
        if (handle > 0) VpnNative.freeTunnel(handle)
    }
}
