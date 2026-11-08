package com.vayunmathur.web.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LocalNetworkTest {

    private fun kind(host: String) = LocalNetwork.classify(host)

    // ------------------------------------------------------------------ hostOf

    @Test
    fun `strips userinfo port path query and fragment`() {
        assertEquals("192.168.1.1", LocalNetwork.hostOf("http://user:pw@192.168.1.1:8080/a?b#c"))
    }

    @Test
    fun `consumes IPv6 brackets before the port`() {
        assertEquals("fe80::1", LocalNetwork.hostOf("http://[fe80::1]:8080/"))
        assertEquals("::1", LocalNetwork.hostOf("http://[::1]:3000"))
    }

    @Test
    fun `cuts an IPv6 zone id`() {
        assertEquals("fe80::1", LocalNetwork.hostOf("http://[fe80::1%25wlan0]/x"))
    }

    @Test
    fun `accepts scheme-less input`() {
        assertEquals("nas.local", LocalNetwork.hostOf("nas.local"))
        assertEquals("192.168.1.1", LocalNetwork.hostOf("192.168.1.1:8080"))
    }

    @Test
    fun `lowercases and drops one trailing dot`() {
        assertEquals("nas.local", LocalNetwork.hostOf("http://NAS.Local./"))
    }

    // -------------------------------------------------------------------- IPv4

    @Test
    fun `respects the 172 16 slash 12 boundaries`() {
        assertEquals(HostKind.PUBLIC, kind("172.15.255.255"))
        assertEquals(HostKind.LAN, kind("172.16.0.0"))
        assertEquals(HostKind.LAN, kind("172.31.255.255"))
        assertEquals(HostKind.PUBLIC, kind("172.32.0.0"))
    }

    @Test
    fun `respects the 192 168 and 169 254 boundaries`() {
        assertEquals(HostKind.PUBLIC, kind("192.167.1.1"))
        assertEquals(HostKind.LAN, kind("192.168.1.1"))
        assertEquals(HostKind.PUBLIC, kind("192.169.1.1"))
        assertEquals(HostKind.PUBLIC, kind("169.253.1.1"))
        assertEquals(HostKind.LAN, kind("169.254.1.1"))
    }

    @Test
    fun `treats loopback and this-network as LAN`() {
        assertEquals(HostKind.LAN, kind("127.0.0.1"))
        assertEquals(HostKind.LAN, kind("10.0.0.1"))
        assertEquals(HostKind.LAN, kind("0.0.0.0"))
    }

    @Test
    fun `CGNAT space is not LAN`() {
        // 100.64/10 is carrier space shared with strangers, not a network the user controls.
        assertEquals(HostKind.PUBLIC, kind("100.64.0.1"))
    }

    @Test
    fun `public addresses are public`() {
        assertEquals(HostKind.PUBLIC, kind("8.8.8.8"))
        assertEquals(HostKind.PUBLIC, kind("1.2.3.4"))
    }

    @Test
    fun `non-canonical numeric forms fail closed`() {
        assertEquals(HostKind.PUBLIC, kind("2130706433"))
        assertEquals(HostKind.PUBLIC, kind("10.1"))
        assertEquals(HostKind.PUBLIC, kind("192.168.1.256"))
    }

    @Test
    fun `leading-zero octets fail closed`() {
        // The URL parser reads a leading-zero part as octal, so `010.0.0.1`
        // resolves to 8.0.0.1, not 10.0.0.1. Decimal classification here would
        // call it LAN while the renderer connects elsewhere — fail closed.
        assertEquals(HostKind.PUBLIC, kind("010.0.0.1"))
        assertEquals(HostKind.PUBLIC, kind("192.168.001.1"))
        assertEquals(HostKind.PUBLIC, kind("::ffff:010.0.0.1"))
        // A lone zero is not an octal prefix; this-network stays LAN.
        assertEquals(HostKind.LAN, kind("0.0.0.0"))
    }

    // -------------------------------------------------------------------- IPv6

    @Test
    fun `respects the unique-local boundary`() {
        assertEquals(HostKind.PUBLIC, kind("fb00::1"))
        assertEquals(HostKind.LAN, kind("fc00::1"))
        assertEquals(HostKind.LAN, kind("fd12:3456::1"))
    }

    @Test
    fun `respects the link-local boundary`() {
        assertEquals(HostKind.LAN, kind("fe80::1"))
        assertEquals(HostKind.LAN, kind("febf::1"))
        assertEquals(HostKind.PUBLIC, kind("fec0::1"))
    }

    @Test
    fun `IPv6 loopback is LAN`() {
        assertEquals(HostKind.LAN, kind("::1"))
    }

    @Test
    fun `IPv4-mapped addresses delegate to the IPv4 rules`() {
        assertEquals(HostKind.LAN, kind("::ffff:192.168.1.1"))
        assertEquals(HostKind.PUBLIC, kind("::ffff:8.8.8.8"))
    }

    @Test
    fun `malformed IPv6 is not private`() {
        assertEquals(HostKind.PUBLIC, kind("fc00:::1"))
        assertEquals(HostKind.PUBLIC, kind("fc00::1::2"))
        assertEquals(HostKind.PUBLIC, kind("fc00:1:2:3:4:5:6:7:8"))
        assertEquals(HostKind.PUBLIC, kind("fc00:zzzz::1"))
    }

    @Test
    fun `public IPv6 is public`() {
        assertEquals(HostKind.PUBLIC, kind("2001:4860:4860::8888"))
    }

    // --------------------------------------------------------------- hostnames

    @Test
    fun `local suffixes and dotless hosts are LAN`() {
        assertEquals(HostKind.LAN, kind("localhost"))
        assertEquals(HostKind.LAN, kind("dev.localhost"))
        assertEquals(HostKind.LAN, kind("local"))
        assertEquals(HostKind.LAN, kind("nas.local"))
        assertEquals(HostKind.LAN, kind("home.arpa"))
        assertEquals(HostKind.LAN, kind("router.home.arpa"))
        assertEquals(HostKind.LAN, kind("router"))
    }

    @Test
    fun `a suffix that merely contains local is not LAN`() {
        assertEquals(HostKind.NEEDS_DNS, kind("localhost.evil.com"))
        assertEquals(HostKind.NEEDS_DNS, kind("mylocal.com"))
        assertEquals(HostKind.NEEDS_DNS, kind("notlocalhost.com"))
    }

    @Test
    fun `internal is not treated as local`() {
        // Collides with real cloud-provider zones that resolve publicly.
        assertEquals(HostKind.NEEDS_DNS, kind("db.internal"))
    }

    @Test
    fun `ordinary names need DNS`() {
        assertEquals(HostKind.NEEDS_DNS, kind("example.com"))
    }

    @Test
    fun `an empty host is public`() {
        assertEquals(HostKind.PUBLIC, kind(""))
    }

    // ------------------------------------------------------------- convenience

    @Test
    fun `isIpLiteral covers both families`() {
        assertTrue(LocalNetwork.isIpLiteral("192.168.1.1"))
        assertTrue(LocalNetwork.isIpLiteral("fe80::1"))
        assertFalse(LocalNetwork.isIpLiteral("nas.local"))
        assertFalse(LocalNetwork.isIpLiteral("1.2.3.4.example.com"))
    }

    @Test
    fun `isLanHostSyntactic never says yes for a name that needs DNS`() {
        assertTrue(LocalNetwork.isLanHostSyntactic("nas.local"))
        assertFalse(LocalNetwork.isLanHostSyntactic("example.com"))
    }
}
