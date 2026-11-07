package com.vayunmathur.vpn.service

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DnsCacheAndTrackerTest {
    private fun u16(v: Int) = byteArrayOf((v shr 8).toByte(), (v and 0xFF).toByte())

    private fun parsed(protocol: String = "UDP", srcIp: String = "10.0.0.2", dstIp: String = "8.8.8.8", srcPort: Int = 40000, dstPort: Int = 53, payload: ByteArray): PacketInspector.ParsedPacket {
        // Build a fake IPv4/UDP packet whose payload sits at offset 28
        val pkt = ByteArray(28 + payload.size)
        pkt[0] = 0x45; pkt[9] = 17
        System.arraycopy(payload, 0, pkt, 28, payload.size)
        return PacketInspector.ParsedPacket(17, protocol, srcIp, dstIp, srcPort, dstPort, 28, payload.size, pkt.size)
    }

    private fun packetFor(p: PacketInspector.ParsedPacket, payload: ByteArray): ByteArray {
        val pkt = ByteArray(p.payloadOffset + payload.size)
        System.arraycopy(payload, 0, pkt, p.payloadOffset, payload.size)
        return pkt
    }

    private fun dnsQuery(txId: Int, domain: String): ByteArray {
        var q = ByteArray(0)
        for (label in domain.split('.')) { val b = label.toByteArray(); q += byteArrayOf(b.size.toByte()) + b }
        q += byteArrayOf(0)
        return u16(txId) + u16(0x0100) + u16(1) + u16(0) + u16(0) + u16(0) + q + u16(1) + u16(1)
    }

    private fun dnsResponse(txId: Int, domain: String, answerIp: ByteArray): ByteArray {
        var q = ByteArray(0)
        for (label in domain.split('.')) { val b = label.toByteArray(); q += byteArrayOf(b.size.toByte()) + b }
        q += byteArrayOf(0)
        val question = q + u16(1) + u16(1)
        // Answer: pointer to offset 12, type A, class IN, TTL, rdlen 4, ip
        val answer = byteArrayOf(0xC0.toByte(), 12) + u16(1) + u16(1) + byteArrayOf(0, 0, 0, 60) + u16(4) + answerIp
        return u16(txId) + u16(0x8180) + u16(1) + u16(1) + u16(0) + u16(0) + question + answer
    }

    @Test fun putGetClearAndBlankRejected() {
        val c = DnsCache()
        c.put("1.2.3.4", "example.com"); assertEquals("example.com", c.get("1.2.3.4"))
        c.put("1.2.3.4", ""); assertEquals("example.com", c.get("1.2.3.4")) // blank ignored
        assertEquals(mapOf("1.2.3.4" to "example.com"), c.allEntries())
        c.clear(); assertNull(c.get("1.2.3.4")); assertTrue(c.allEntries().isEmpty())
    }

    @Test fun lruEvictsOldestBeyondMax() {
        val c = DnsCache(maxSize = 2)
        c.put("1.1.1.1", "a.com"); c.put("2.2.2.2", "b.com"); c.get("1.1.1.1"); c.put("3.3.3.3", "c.com")
        assertNull(c.get("2.2.2.2")); assertEquals("a.com", c.get("1.1.1.1")); assertEquals("c.com", c.get("3.3.3.3"))
    }

    @Test fun queryThenResponsePopulatesCache() {
        val c = DnsCache()
        val q = dnsQuery(0x1234, "example.com")
        val qp = parsed(dstPort = 53, srcPort = 40000, payload = q)
        assertEquals("example.com", c.onPacket(qp, packetFor(qp, q)))
        val r = dnsResponse(0x1234, "example.com", byteArrayOf(93, 184.toByte(), 216.toByte(), 34))
        val rp = parsed(dstPort = 40000, srcPort = 53, payload = r)
        assertEquals("example.com", c.onPacket(rp, packetFor(rp, r)))
        assertEquals("example.com", c.get("93.184.216.34"))
        // Unknown txid response returns null
        val r2 = dnsResponse(0x9999, "other.com", byteArrayOf(1, 1, 1, 1))
        val rp2 = parsed(dstPort = 40000, srcPort = 53, payload = r2)
        assertNull(c.onPacket(rp2, packetFor(rp2, r2)))
    }

    @Test fun onPacketRejectsNonDnsAndShort() {
        val c = DnsCache()
        val tcp = parsed(protocol = "TCP", payload = ByteArray(20))
        assertNull(c.onPacket(tcp, packetFor(tcp, ByteArray(20))))
        val short = parsed(payload = ByteArray(5))
        assertNull(c.onPacket(short, packetFor(short, ByteArray(5))))
        val otherPort = parsed(srcPort = 1234, dstPort = 9999, payload = ByteArray(20))
        assertNull(c.onPacket(otherPort, packetFor(otherPort, ByteArray(20))))
        // Truncated query does not throw
        val trunc = parsed(payload = dnsQuery(1, "a.com").copyOf(14))
        c.onPacket(trunc, packetFor(trunc, dnsQuery(1, "a.com").copyOf(14)))
    }

    private fun flowPacket(protocol: String = "UDP", srcIp: String = "10.0.0.2", dstIp: String = "8.8.8.8", srcPort: Int = 40000, dstPort: Int = 53) =
        PacketInspector.ParsedPacket(17, protocol, srcIp, dstIp, srcPort, dstPort, 28, 0, 28)

    @Test fun trackerAggregatesTxRxAndDrainsOnce() {
        val t = ConnectionTracker()
        assertTrue(t.drainDirty().isEmpty())
        t.onPacket(flowPacket(), ConnectionTracker.Direction.TX, 100, uid = -1, packageName = null, appLabel = "Unknown")
        t.onPacket(flowPacket(), ConnectionTracker.Direction.TX, 50, uid = 10123, packageName = "com.example", appLabel = "Example")
        // RX is the same flow with endpoints swapped
        val rx = PacketInspector.ParsedPacket(17, "UDP", "8.8.8.8", "10.0.0.2", 53, 40000, 28, 0, 28)
        t.onPacket(rx, ConnectionTracker.Direction.RX, 900, uid = -1, packageName = null, appLabel = "Unknown")
        val snap = t.snapshot()
        assertEquals(1, snap.size)
        assertEquals(150L, snap[0].txBytes); assertEquals(900L, snap[0].rxBytes)
        assertEquals(10123, snap[0].uid); assertEquals("com.example", snap[0].packageName) // late attribution upgraded
        val batch = t.drainDirty()
        assertEquals(1, batch.size); assertEquals(1L, batch[0].requestCount)
        assertTrue(t.drainDirty().isEmpty()) // no longer dirty
        // New packet re-dirties
        t.onPacket(flowPacket(), ConnectionTracker.Direction.TX, 10, uid = -1, packageName = null, appLabel = "Unknown")
        assertEquals(160L, t.drainDirty().single().txBytes)
    }

    @Test fun trackerUpdateIdsAndDnsAndClear() {
        val t = ConnectionTracker()
        val dns = DnsCache(); t.setDnsCache(dns)
        t.recordDnsMapping("8.8.8.8", "dns.google")
        assertEquals("dns.google", dns.get("8.8.8.8"))
        t.onPacket(flowPacket(), ConnectionTracker.Direction.TX, 10, uid = 1, packageName = "a.b", appLabel = "AB")
        val batch = t.drainDirty()
        assertEquals("dns.google", batch.single().domain) // domain from DNS cache
        t.updateIds(batch.map { it.copy(id = 77L) })
        assertEquals(77L, t.snapshot().single().id)
        t.clear(); assertTrue(t.snapshot().isEmpty())
    }

    @Test fun trackerDomainOverrideWinsAndFlowsAreDistinct() {
        val t = ConnectionTracker()
        t.onPacket(flowPacket(dstPort = 53), ConnectionTracker.Direction.TX, 10, domainOverride = "override.com", uid = 1, packageName = null, appLabel = "x")
        t.onPacket(flowPacket(dstPort = 443), ConnectionTracker.Direction.TX, 20, uid = 1, packageName = null, appLabel = "x")
        val snap = t.snapshot().sortedBy { it.remotePort }
        assertEquals(2, snap.size); assertEquals("override.com", snap.first { it.remotePort == 53 }.domain)
    }

    @Test fun trackerGlobalInstanceLifecycle() {
        ConnectionTracker.clearGlobal()
        val a = ConnectionTracker.getOrCreate()
        assertTrue(a === ConnectionTracker.getOrCreate())
        ConnectionTracker.clearGlobal()
        assertTrue(a !== ConnectionTracker.getOrCreate())
        ConnectionTracker.clearGlobal()
    }
}
