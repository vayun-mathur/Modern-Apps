package com.vayunmathur.vpn.service

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PacketInspectorTest {
    private fun u16(v: Int) = byteArrayOf((v shr 8).toByte(), (v and 0xFF).toByte())

    private fun ipv4Packet(proto: Int, payload: ByteArray, src: ByteArray = byteArrayOf(10, 0, 0, 2), dst: ByteArray = byteArrayOf(1, 2, 3, 4), ihlWords: Int = 5): ByteArray {
        val total = ihlWords * 4 + payload.size
        val hdr = ByteArray(ihlWords * 4)
        hdr[0] = (4 shl 4 or ihlWords).toByte()
        hdr[2] = (total shr 8).toByte(); hdr[3] = (total and 0xFF).toByte()
        hdr[9] = proto.toByte()
        System.arraycopy(src, 0, hdr, 12, 4); System.arraycopy(dst, 0, hdr, 16, 4)
        return hdr + payload
    }

    private fun udpPayload(srcPort: Int, dstPort: Int, data: ByteArray): ByteArray =
        u16(srcPort) + u16(dstPort) + u16(8 + data.size) + u16(0) + data

    private fun tcpPayload(srcPort: Int, dstPort: Int, flags: Int, data: ByteArray, dataOffWords: Int = 5): ByteArray {
        val hdr = ByteArray(dataOffWords * 4)
        System.arraycopy(u16(srcPort), 0, hdr, 0, 2); System.arraycopy(u16(dstPort), 0, hdr, 2, 2)
        hdr[12] = (dataOffWords shl 4).toByte(); hdr[13] = flags.toByte()
        return hdr + data
    }

    private fun ipv6Packet(nextHeader: Int, payload: ByteArray): ByteArray {
        val hdr = ByteArray(40)
        hdr[0] = (6 shl 4).toByte()
        hdr[4] = (payload.size shr 8).toByte(); hdr[5] = (payload.size and 0xFF).toByte()
        hdr[6] = nextHeader.toByte()
        for (i in 0 until 16) { hdr[8 + i] = i.toByte(); hdr[24 + i] = (0xF0 - i).toByte() }
        return hdr + payload
    }

    @Test fun emptyAndUnknownVersionRejected() {
        assertNull(PacketInspector.parse(ByteArray(0)))
        assertNull(PacketInspector.parse(byteArrayOf(0x50, 1, 2, 3))) // version 5
    }

    @Test fun ipv4UdpParsed() {
        val p = PacketInspector.parse(ipv4Packet(17, udpPayload(1234, 53, byteArrayOf(1, 2, 3))))
        assertNotNull(p)
        assertEquals("UDP", p.protocol); assertEquals(17, p.protocolNumber)
        assertEquals("10.0.0.2", p.srcIp); assertEquals("1.2.3.4", p.dstIp)
        assertEquals(1234, p.srcPort); assertEquals(53, p.dstPort)
        assertEquals(20 + 8, p.payloadOffset); assertEquals(3, p.payloadLength)
        assertEquals(31, p.totalLength)
    }

    @Test fun ipv4UdpWithOptionsHeader() {
        val p = PacketInspector.parse(ipv4Packet(17, udpPayload(1, 2, byteArrayOf(9)), ihlWords = 6))
        assertNotNull(p); assertEquals(24 + 8, p.payloadOffset)
    }

    @Test fun ipv4TcpSynAndFinFlags() {
        val syn = PacketInspector.parse(ipv4Packet(6, tcpPayload(443, 5555, 0x02, ByteArray(0))))
        assertNotNull(syn); assertTrue(syn.isTcpSyn); assertTrue(!syn.isTcpFinOrRst); assertEquals("TCP", syn.protocol)
        val fin = PacketInspector.parse(ipv4Packet(6, tcpPayload(443, 5555, 0x01, byteArrayOf(1))))
        assertTrue(fin!!.isTcpFinOrRst)
        val rst = PacketInspector.parse(ipv4Packet(6, tcpPayload(443, 5555, 0x04, ByteArray(0))))
        assertTrue(rst!!.isTcpFinOrRst)
        val data = PacketInspector.parse(ipv4Packet(6, tcpPayload(80, 1000, 0x10, byteArrayOf(1, 2, 3, 4))))
        assertEquals(4, data!!.payloadLength)
    }

    @Test fun ipv4IcmpHasNoPorts() {
        val p = PacketInspector.parse(ipv4Packet(1, byteArrayOf(8, 0, 0, 0)))
        assertNotNull(p); assertEquals("ICMP", p.protocol); assertEquals(0, p.srcPort); assertEquals(4, p.payloadLength)
        val other = PacketInspector.parse(ipv4Packet(99, byteArrayOf(1)))
        assertEquals("OTHER", other!!.protocol)
    }

    @Test fun truncatedPacketsRejected() {
        assertNull(PacketInspector.parse(byteArrayOf(0x45, 0, 0))) // v4 but < 20 bytes
        assertNull(PacketInspector.parse(ipv4Packet(17, byteArrayOf(1, 2, 3)))) // UDP header truncated
        assertNull(PacketInspector.parse(ipv4Packet(6, byteArrayOf(1, 2, 3)))) // TCP header truncated
        assertNull(PacketInspector.parse(ByteArray(20) { 0x60 })) // v6 but < 40
        // IHL too small
        val bad = ipv4Packet(17, udpPayload(1, 2, ByteArray(0))); bad[0] = (4 shl 4 or 4).toByte()
        assertNull(PacketInspector.parse(bad))
        // TCP data offset too small
        val badTcp = ipv4Packet(6, tcpPayload(1, 2, 0, ByteArray(0), dataOffWords = 5)); badTcp[20 + 12] = (4 shl 4).toByte()
        assertNull(PacketInspector.parse(badTcp))
    }

    @Test fun ipv6UdpAndTcpParsed() {
        val udp = PacketInspector.parse(ipv6Packet(17, udpPayload(53, 9999, byteArrayOf(7, 7))))
        assertNotNull(udp); assertEquals("UDP", udp.protocol); assertEquals(53, udp.srcPort); assertEquals(2, udp.payloadLength)
        assertTrue(udp.srcIp.isNotBlank() && udp.dstIp.isNotBlank())
        val tcp = PacketInspector.parse(ipv6Packet(6, tcpPayload(1, 2, 0x02, ByteArray(0))))
        assertTrue(tcp!!.isTcpSyn)
        val icmpv6 = PacketInspector.parse(ipv6Packet(58, byteArrayOf(1, 2)))
        assertEquals("ICMPv6", icmpv6!!.protocol)
    }

    @Test fun declaredLengthLargerThanBufferClamped() {
        var pkt = ipv4Packet(17, udpPayload(1, 2, byteArrayOf(1, 2, 3)))
        // Corrupt total length upwards
        pkt[2] = 0xFF.toByte(); pkt[3] = 0xFF.toByte()
        val p = PacketInspector.parse(pkt)
        assertNotNull(p); assertEquals(3, p.payloadLength)
    }
}
