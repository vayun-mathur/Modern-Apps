package com.vayunmathur.vpn.service

import java.net.InetAddress

/**
 * Pure Kotlin IP packet parser for TUN data path.
 * Supports IPv4 and IPv6, UDP and TCP.
 * Zero external dependency; bounds-checked for hot path.
 */
object PacketInspector {

    /** Immutable value describing parsed packet. */
    data class ParsedPacket(
        val protocolNumber: Int,
        val protocol: String,
        val srcIp: String,
        val dstIp: String,
        val srcPort: Int,
        val dstPort: Int,
        val payloadOffset: Int,
        val payloadLength: Int,
        val totalLength: Int,
        val isTcpSyn: Boolean = false,
        val isTcpFinOrRst: Boolean = false,
    )

    private const val IPV4_MIN_HEADER = 20
    private const val IPV6_HEADER_LEN = 40
    private const val UDP_HEADER_LEN = 8
    private const val TCP_MIN_HEADER = 20
    private const val BYTE_MASK = 0xFF
    private const val NIBBLE_MASK = 0xF
    private const val BYTE_SHIFT = 8
    private const val IPV4_ADDR_LEN = 4
    private const val IPV6_ADDR_LEN = 16
    private const val PROTO_TCP = 6
    private const val PROTO_UDP = 17
    private const val PROTO_ICMP = 1
    private const val PROTO_ICMPV6 = 58
    private const val TCP_FLAG_FIN = 0x01
    private const val TCP_FLAG_SYN = 0x02
    private const val TCP_FLAG_RST = 0x04
    private const val TCP_FLAGS_OFF = 13
    private const val TCP_DATA_OFF_WORD = 12
    private const val IP_VERSION_4 = 4
    private const val IP_VERSION_6 = 6
    private const val PORT_PAIR_LEN = 4
    private const val HEX_RADIX = 16

    fun parse(packet: ByteArray): ParsedPacket? {
        if (packet.isEmpty()) return null
        val version = (packet[0].toInt() shr 4) and NIBBLE_MASK
        return when (version) {
            IP_VERSION_4 -> parseIpv4(packet)
            IP_VERSION_6 -> parseIpv6(packet)
            else -> null
        }
    }

    private fun parseIpv4(b: ByteArray): ParsedPacket? {
        if (b.size < IPV4_MIN_HEADER) return null
        val ihl = (b[0].toInt() and NIBBLE_MASK) * IPV4_ADDR_LEN
        if (ihl < IPV4_MIN_HEADER || b.size < ihl) return null
        val totalLen = u16(b, 2)
        val protoNum = uByte(b, 9)
        val srcIp = ipv4ToString(b, 12)
        val dstIp = ipv4ToString(b, 16)
        return parseTransport(b, ihl, protoNum, srcIp, dstIp, totalLen.coerceAtMost(b.size))
    }

    private fun parseIpv6(b: ByteArray): ParsedPacket? {
        if (b.size < IPV6_HEADER_LEN) return null
        val payloadLen = u16(b, 4)
        val nextHeader = uByte(b, 6)
        val totalLen = IPV6_HEADER_LEN + payloadLen
        val srcIp = ipv6ToString(b, 8)
        val dstIp = ipv6ToString(b, 24)
        return parseTransport(b, IPV6_HEADER_LEN, nextHeader, srcIp, dstIp, totalLen.coerceAtMost(b.size))
    }

    private fun parseTransport(
        b: ByteArray,
        ipHeaderLen: Int,
        protoNum: Int,
        srcIp: String,
        dstIp: String,
        totalLen: Int,
    ): ParsedPacket? {
        if (b.size < ipHeaderLen) return null
        val remaining = b.size - ipHeaderLen
        val result = when (protoNum) {
            PROTO_UDP -> parseUdp(b, ipHeaderLen, remaining, protoNum, srcIp, dstIp, totalLen)
            PROTO_TCP -> parseTcp(b, ipHeaderLen, remaining, protoNum, srcIp, dstIp, totalLen)
            else -> parseOther(b, ipHeaderLen, protoNum, srcIp, dstIp, totalLen)
        }
        return result
    }

    private fun parseUdp(
        b: ByteArray,
        ipHeaderLen: Int,
        remaining: Int,
        protoNum: Int,
        srcIp: String,
        dstIp: String,
        totalLen: Int,
    ): ParsedPacket? {
        if (remaining < UDP_HEADER_LEN) return null
        val ports = readPorts(b, ipHeaderLen) ?: return null
        val payloadOff = ipHeaderLen + UDP_HEADER_LEN
        val payloadLen = (totalLen - ipHeaderLen - UDP_HEADER_LEN).coerceAtLeast(0)
        return ParsedPacket(
            protocolNumber = protoNum,
            protocol = protocolName(protoNum),
            srcIp = srcIp,
            dstIp = dstIp,
            srcPort = ports.first,
            dstPort = ports.second,
            payloadOffset = payloadOff,
            payloadLength = payloadLen.coerceAtMost(b.size - payloadOff),
            totalLength = totalLen,
        )
    }

    private fun parseTcp(
        b: ByteArray,
        ipHeaderLen: Int,
        remaining: Int,
        protoNum: Int,
        srcIp: String,
        dstIp: String,
        totalLen: Int,
    ): ParsedPacket? {
        if (remaining < TCP_MIN_HEADER) return null
        val ports = readPorts(b, ipHeaderLen) ?: return null
        val tcpHeaderLen = tcpHeaderLen(b, ipHeaderLen) ?: return null
        if (b.size < ipHeaderLen + tcpHeaderLen) return null
        val flags = uByte(b, ipHeaderLen + TCP_FLAGS_OFF)
        return ParsedPacket(
            protocolNumber = protoNum,
            protocol = protocolName(protoNum),
            srcIp = srcIp,
            dstIp = dstIp,
            srcPort = ports.first,
            dstPort = ports.second,
            payloadOffset = ipHeaderLen + tcpHeaderLen,
            payloadLength = payloadLen(b, totalLen, ipHeaderLen, tcpHeaderLen),
            totalLength = totalLen,
            isTcpSyn = flags and TCP_FLAG_SYN != 0,
            isTcpFinOrRst = flags and TCP_FLAG_FIN != 0 || flags and TCP_FLAG_RST != 0,
        )
    }

    private fun parseOther(
        b: ByteArray,
        ipHeaderLen: Int,
        protoNum: Int,
        srcIp: String,
        dstIp: String,
        totalLen: Int,
    ): ParsedPacket? {
        // Non TCP/UDP - no ports
        return ParsedPacket(
            protocolNumber = protoNum,
            protocol = protocolName(protoNum),
            srcIp = srcIp,
            dstIp = dstIp,
            srcPort = 0,
            dstPort = 0,
            payloadOffset = ipHeaderLen,
            payloadLength = (totalLen - ipHeaderLen).coerceAtLeast(0).coerceAtMost(b.size - ipHeaderLen),
            totalLength = totalLen,
        )
    }

    private fun protocolName(protoNum: Int): String = when (protoNum) {
        PROTO_TCP -> "TCP"
        PROTO_UDP -> "UDP"
        PROTO_ICMP -> "ICMP"
        PROTO_ICMPV6 -> "ICMPv6"
        else -> "OTHER"
    }

    private fun readPorts(b: ByteArray, off: Int): Pair<Int, Int>? {
        if (off + PORT_PAIR_LEN > b.size) return null
        return u16(b, off) to u16(b, off + 2)
    }

    private fun tcpHeaderLen(b: ByteArray, ipHeaderLen: Int): Int? {
        val dataOffset = (b[ipHeaderLen + TCP_DATA_OFF_WORD].toInt() shr 4) and NIBBLE_MASK
        val len = dataOffset * IPV4_ADDR_LEN
        if (len < TCP_MIN_HEADER) return null
        return len
    }

    private fun payloadLen(b: ByteArray, totalLen: Int, ipHeaderLen: Int, tcpHeaderLen: Int): Int {
        val raw = (totalLen - ipHeaderLen - tcpHeaderLen).coerceAtLeast(0)
        return raw.coerceAtMost(b.size - ipHeaderLen - tcpHeaderLen)
    }

    private fun uByte(b: ByteArray, pos: Int): Int = b[pos].toInt() and BYTE_MASK

    private fun u16(b: ByteArray, pos: Int): Int =
        (uByte(b, pos) shl BYTE_SHIFT) or uByte(b, pos + 1)

    private fun ipv4ToString(b: ByteArray, off: Int): String {
        val viaInet = runCatching {
            InetAddress.getByAddress(b.copyOfRange(off, off + IPV4_ADDR_LEN)).hostAddress
        }.getOrNull()
        return viaInet ?: fallbackIpv4(b, off)
    }

    private fun fallbackIpv4(b: ByteArray, off: Int): String {
        val parts = (0 until IPV4_ADDR_LEN).joinToString(".") { uByte(b, off + it).toString() }
        return parts
    }

    private fun ipv6ToString(b: ByteArray, off: Int): String {
        val viaInet = runCatching {
            InetAddress.getByAddress(b.copyOfRange(off, off + IPV6_ADDR_LEN)).hostAddress
        }.getOrNull()
        if (viaInet != null) return viaInet
        // Fallback manual
        val parts = mutableListOf<String>()
        for (i in 0 until IPV6_ADDR_LEN step 2) {
            parts.add(u16(b, off + i).toString(HEX_RADIX))
        }
        return parts.joinToString(":")
    }
}
