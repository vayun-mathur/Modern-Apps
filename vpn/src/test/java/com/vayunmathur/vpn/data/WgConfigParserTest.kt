package com.vayunmathur.vpn.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class WgConfigParserTest {
    private val fullConf = """
        # a comment
        ; another comment
        [Interface]
        PrivateKey = cGFzc3dvcmQxMjM0NTY3ODkwYWJjZGVmZ2hpamtsbW4=
        Address = 10.0.0.2/32, fd00::2/128
        DNS = 1.1.1.1
        MTU = 1420

        [Peer]
        PublicKey = cGVlcnB1YmxpY2tleTEyMzQ1Njc4OTBhYmNkZWZnaGk=
        PresharedKey = cHNrMTIzNDU2Nzg5MGFiY2RlZmdoaWprbG1ub3BxcnM=
        AllowedIPs = 0.0.0.0/0, ::/0
        Endpoint = vpn.example.com:51820
        PersistentKeepalive = 25
    """.trimIndent()

    @Test fun parsesFullConf() {
        val imp = WgConfigParser.parse(fullConf).getOrThrow()
        assertEquals("cGFzc3dvcmQxMjM0NTY3ODkwYWJjZGVmZ2hpamtsbW4=", imp.privateKey)
        assertEquals("10.0.0.2/32, fd00::2/128", imp.address)
        assertEquals("1.1.1.1", imp.dns)
        assertEquals(1420, imp.mtu)
        assertEquals("cGVlcnB1YmxpY2tleTEyMzQ1Njc4OTBhYmNkZWZnaGk=", imp.peerPublicKey)
        assertEquals("cHNrMTIzNDU2Nzg5MGFiY2RlZmdoaWprbG1ub3BxcnM=", imp.peerPresharedKey)
        assertEquals("vpn.example.com:51820", imp.peerEndpoint)
        assertEquals(25, imp.peerKeepalive)
    }

    @Test fun defaultsForMissingOptionalFields() {
        val imp = WgConfigParser.parse("[Interface]\nPrivateKey = abc=\n[Peer]\nPublicKey = def=\n").getOrThrow()
        assertEquals("", imp.address); assertEquals("", imp.dns)
        assertEquals(1280, imp.mtu); assertEquals("", imp.peerPresharedKey)
        assertEquals("0.0.0.0/0, ::/0", imp.peerAllowedIps)
        assertEquals("", imp.peerEndpoint); assertEquals(25, imp.peerKeepalive)
    }

    @Test fun missingSectionsAndKeysFail() {
        assertTrue(WgConfigParser.parse("").isFailure)
        assertTrue(WgConfigParser.parse("[Interface]\nPrivateKey = x\n").isFailure) // no Peer
        assertTrue(WgConfigParser.parse("[Peer]\nPublicKey = x\n").isFailure) // no Interface
        assertTrue(WgConfigParser.parse("[Interface]\nAddress = 1.2.3.4\n[Peer]\nPublicKey = x\n").isFailure) // no PrivateKey
        assertTrue(WgConfigParser.parse("[Interface]\nPrivateKey = x\n[Peer]\nEndpoint = e:1\n").isFailure) // no peer key
    }

    @Test fun entriesBeforeAnySectionAreIgnoredAndBadMtuFallsBack() {
        val conf = "PrivateKey = ignored\n[Interface]\nPrivateKey = real=\nMTU = notanumber\n[Peer]\nPublicKey = pk=\nPersistentKeepalive = xyz\n"
        val imp = WgConfigParser.parse(conf).getOrThrow()
        assertEquals("real=", imp.privateKey); assertEquals(1280, imp.mtu); assertEquals(25, imp.peerKeepalive)
    }

    @Test fun caseInsensitiveKeysAndWhitespace() {
        val imp = WgConfigParser.parse("[Interface]\n  privatekey   =   spaced=  \n[Peer]\nPUBLICKEY = PK=\n").getOrThrow()
        assertEquals("spaced=", imp.privateKey); assertEquals("PK=", imp.peerPublicKey)
    }

    @Test fun roundTripThroughToWgQuick() {
        val cfg = VpnConfig(
            name = "t", privateKey = "priv=", address = "10.0.0.2/32", dns = "1.1.1.1", mtu = 1420,
            peerPublicKey = "pub=", peerPresharedKey = "psk=", peerEndpoint = "host:51820", peerKeepalive = 30,
        )
        val text = WgConfigParser.toWgQuick(cfg)
        val back = WgConfigParser.parse(text).getOrThrow()
        assertEquals(cfg.privateKey, back.privateKey); assertEquals(cfg.address, back.address)
        assertEquals(cfg.mtu, back.mtu); assertEquals(cfg.peerPublicKey, back.peerPublicKey)
        assertEquals(cfg.peerEndpoint, back.peerEndpoint); assertEquals(30, back.peerKeepalive)
    }

    @Test fun toWgQuickOmitsBlankAndZeroFields() {
        val cfg = VpnConfig(privateKey = "p=", peerPublicKey = "k=", mtu = 0, peerKeepalive = 0)
        val text = WgConfigParser.toWgQuick(cfg)
        assertTrue("Address" !in text); assertTrue("DNS" !in text); assertTrue("MTU" !in text)
        assertTrue("PresharedKey" !in text); assertTrue("Endpoint" !in text); assertTrue("PersistentKeepalive" !in text)
        assertTrue("AllowedIPs = 0.0.0.0/0, ::/0" in text)
    }

    @Test fun endpointHostAndPort() {
        assertEquals("vpn.example.com", VpnConfig(peerEndpoint = "vpn.example.com:51820").endpointHost())
        assertEquals(51820, VpnConfig(peerEndpoint = "vpn.example.com:51820").endpointPort())
        assertEquals(51820, VpnConfig(peerEndpoint = "vpn.example.com").endpointPort()) // default
        assertEquals(51820, VpnConfig(peerEndpoint = "vpn.example.com:abc").endpointPort())
        assertEquals("", VpnConfig(peerEndpoint = "").endpointHost())
        assertEquals(51820, VpnConfig(peerEndpoint = "").endpointPort())
        // Known limitation pinned as-is: a bracketed IPv6 endpoint host parses as "["
        // (substringBefore(':')), so IPv6-literal endpoints do not resolve. Recorded, not fixed here.
        assertEquals("[", VpnConfig(peerEndpoint = "[::1]:51820").endpointHost())
        assertEquals(1234, VpnConfig(peerEndpoint = "10.0.0.1:1234").endpointPort())
    }

    @Test fun entityModelRoundTripAndDefaults() {
        val cfg = VpnConfig(id = 5, name = "n", privateKey = "a", publicKey = "b", address = "c", dns = "d", mtu = 1400, peerPublicKey = "e", peerPresharedKey = "f", peerAllowedIPs = "g", peerEndpoint = "h:1", peerKeepalive = 9, lastUsed = 42)
        assertEquals(cfg, cfg.toEntity().toModel())
        val def = VpnConfig()
        assertEquals(1280, def.mtu); assertEquals("0.0.0.0/0, ::/0", def.peerAllowedIPs); assertEquals(25, def.peerKeepalive)
        assertEquals(VpnStats(), VpnStats(0, 0, 0, 0f, 0))
    }
}
