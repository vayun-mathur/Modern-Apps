package com.vayunmathur.vpn.service

import java.net.InetAddress

/**
 * DNS snooping: observes UDP port 53 traffic (queries and responses) to build IP -> domain map.
 * Also handles SNI fallback via [SniParser].
 */
class DnsCache(private val maxSize: Int = 1500) {

    // LRU IP -> Domain
    private val ipToDomain = object : LinkedHashMap<String, String>(maxSize, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>): Boolean = size > maxSize
    }

    // Transaction ID -> query domains (for response correlation)
    private val txIdToDomains = mutableMapOf<Int, MutableList<String>>()
    private val txIdMaxSize = 500

    // Synchronize via @Synchronized; called from single tunnel thread but keep safe.
    @Synchronized
    fun put(ip: String, domain: String) {
        if (domain.isBlank()) return
        // Only overwrite if new or we want fresher - always put
        ipToDomain[ip] = domain
    }

    @Synchronized
    fun get(ip: String): String? = ipToDomain[ip]

    @Synchronized
    fun clear() {
        ipToDomain.clear()
        txIdToDomains.clear()
    }

    @Synchronized
    fun allEntries(): Map<String, String> = LinkedHashMap(ipToDomain)

    /**
     * Called from tunnel thread for each parsed packet that is UDP with port 53 src or dst.
     * Returns a DNS-related domain if observed (for direct logging), and side-effects populating cache.
     */
    @Synchronized
    fun onPacket(parsed: PacketInspector.ParsedPacket, packet: ByteArray): String? {
        // Need UDP payload
        if (parsed.protocol != "UDP") return null
        if (parsed.payloadLength < DNS_HEADER_LEN) return null
        if (parsed.payloadOffset + parsed.payloadLength > packet.size) return null

        val payloadOff = parsed.payloadOffset
        val isQuery = parsed.srcPort != DNS_PORT && parsed.dstPort == DNS_PORT
        val isResponse = parsed.srcPort == DNS_PORT

        return try {
            when {
                isQuery -> handleDnsQuery(packet, payloadOff)
                isResponse -> handleDnsResponse(packet, payloadOff)
                else -> null
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun handleDnsQuery(packet: ByteArray, off: Int): String? {
        // DNS header: [0..1] txid, [2..11] flags+counts, [12..] QNAME
        val txId = u16(packet, off + TX_ID_OFF)
        val qdCount = u16(packet, off + QD_COUNT_OFF)
        var pos = off + DNS_HEADER_LEN
        val domains = mutableListOf<String>()
        repeat(qdCount.coerceAtMost(MAX_QUESTIONS)) {
            val decoded = decodeName(packet, pos, off) ?: return@repeat
            if (decoded.name.isNotBlank()) domains.add(decoded.name)
            // QTYPE(2) + QCLASS(2)
            pos = decoded.end + QUESTION_TAIL_LEN
        }
        if (domains.isEmpty()) return null
        evictTxIdIfFull()
        txIdToDomains[txId] = domains.toMutableList()
        return domains.firstOrNull()
    }

    private fun handleDnsResponse(packet: ByteArray, off: Int): String? {
        val txId = u16(packet, off + TX_ID_OFF)
        val anCount = u16(packet, off + AN_COUNT_OFF)
        if (anCount == 0) return null

        val queryDomains = txIdToDomains[txId] ?: return null
        val responseDomain = queryDomains.firstOrNull() ?: return null

        val answersStart = skipQuestions(packet, off) ?: return null
        scanAnswers(packet, answersStart, off, anCount, responseDomain)
        txIdToDomains.remove(txId)
        return responseDomain
    }

    private fun skipQuestions(packet: ByteArray, off: Int): Int? {
        var pos = off + DNS_HEADER_LEN
        val qdCount = u16(packet, off + QD_COUNT_OFF)
        repeat(qdCount.coerceAtMost(MAX_QUESTIONS)) {
            val decoded = decodeName(packet, pos, off) ?: return@repeat
            pos = decoded.end + QUESTION_TAIL_LEN
        }
        return pos
    }

    private fun scanAnswers(packet: ByteArray, start: Int, base: Int, anCount: Int, domain: String) {
        var pos = start
        // Parse answers for A/AAAA
        repeat(anCount.coerceAtMost(MAX_ANSWERS)) {
            pos = scanOneAnswer(packet, pos, base, domain) ?: return
        }
    }

    private fun scanOneAnswer(packet: ByteArray, pos: Int, base: Int, domain: String): Int? {
        if (pos + RR_FIXED_LEN >= packet.size) return null
        val nameEnd = decodeName(packet, pos, base)?.end ?: return pos + RR_FIXED_LEN
        var cur = nameEnd
        if (cur + RR_FIXED_LEN > packet.size) return null
        val rtype = u16(packet, cur + RTYPE_OFF)
        val rdLength = u16(packet, cur + RDLENGTH_OFF)
        cur += RR_FIXED_LEN
        if (cur + rdLength > packet.size) return null
        if (rtype == TYPE_A && rdLength == IPV4_LEN) {
            recordAddress(packet, cur, IPV4_LEN, domain)
        } else if (rtype == TYPE_AAAA && rdLength == IPV6_LEN) {
            recordAddress(packet, cur, IPV6_LEN, domain)
        }
        return cur + rdLength
    }

    private fun recordAddress(packet: ByteArray, off: Int, len: Int, domain: String) {
        try {
            val ip = InetAddress.getByAddress(packet.copyOfRange(off, off + len)).hostAddress ?: ""
            if (ip.isNotBlank()) ipToDomain[ip] = domain
        } catch (_: Exception) {
        }
    }

    /**
     * Decodes DNS QNAME at [startPos] with compression pointer support (0xC0).
     * Returns name plus position after the name.
     */
    private fun decodeName(packet: ByteArray, startPos: Int, packetOffset: Int): DecodedName? {
        val state = NameDecodeState(startPos)
        while (state.pos < packet.size) {
            if (!state.budget.consume()) return null
            val lenByte = uByte(packet, state.pos)
            when {
                lenByte == LABEL_END -> {
                    state.pos++
                    return state.finish()
                }

                isPointer(lenByte) -> {
                    if (!state.followPointer(packet, packetOffset)) return null
                }

                lenByte < MAX_LABEL_LEN -> {
                    if (!state.readLabel(packet)) return null
                }

                else -> return null
            }
        }
        return state.finishIfComplete()
    }

    private fun isPointer(lenByte: Int): Boolean = lenByte and POINTER_MASK == POINTER_MASK

    private fun uByte(packet: ByteArray, pos: Int): Int = packet[pos].toInt() and BYTE_MASK

    private fun u16(packet: ByteArray, pos: Int): Int =
        (uByte(packet, pos) shl BYTE_SHIFT) or uByte(packet, pos + 1)

    private fun evictTxIdIfFull() {
        if (txIdToDomains.size > txIdMaxSize) {
            // evict oldest entry
            txIdToDomains.keys.firstOrNull()?.let { txIdToDomains.remove(it) }
        }
    }

    private data class DecodedName(val name: String, val end: Int)

    private class NameDecodeState(var pos: Int) {
        val labels = mutableListOf<String>()
        var jumped = false
        var jumpEndPos = -1
        val budget = LoopBudget(MAX_NAME_JUMPS)

        fun finish(): DecodedName {
            val finalPos = if (jumped && jumpEndPos != -1) jumpEndPos else pos
            return DecodedName(labels.joinToString(DOMAIN_SEPARATOR), finalPos)
        }

        fun finishIfComplete(): DecodedName? {
            if (labels.isEmpty()) return null
            return finish()
        }

        fun followPointer(packet: ByteArray, packetOffset: Int): Boolean {
            if (pos + 1 >= packet.size) return false
            val lenByte = packet[pos].toInt() and BYTE_MASK
            val pointer = (lenByte and POINTER_VALUE_MASK shl BYTE_SHIFT) or
                (packet[pos + 1].toInt() and BYTE_MASK)
            val target = packetOffset + pointer
            if (target < packetOffset || target >= packet.size) return false
            if (!jumped) {
                jumpEndPos = pos + POINTER_LEN
            }
            jumped = true
            pos = target
            return true
        }

        fun readLabel(packet: ByteArray): Boolean {
            val labelLen = packet[pos].toInt() and BYTE_MASK
            pos++
            if (pos + labelLen > packet.size) return false
            val label = runCatching {
                String(packet, pos, labelLen, Charsets.UTF_8)
            }.getOrNull() ?: return false
            labels.add(label)
            pos += labelLen
            return true
        }
    }

    private class LoopBudget(private var remaining: Int) {
        /** Returns false once the budget is exhausted. Prevents infinite pointer loops. */
        fun consume(): Boolean {
            if (remaining <= 0) return false
            remaining--
            return true
        }
    }

    /** Shared constants and helpers for DnsCache. */
    companion object {
        private const val DNS_PORT = 53
        private const val DNS_HEADER_LEN = 12
        private const val TX_ID_OFF = 0
        private const val QD_COUNT_OFF = 4
        private const val AN_COUNT_OFF = 6
        private const val MAX_QUESTIONS = 4
        private const val MAX_ANSWERS = 50
        private const val QUESTION_TAIL_LEN = 4
        private const val RR_FIXED_LEN = 10
        private const val RTYPE_OFF = 0
        private const val RDLENGTH_OFF = 8
        private const val TYPE_A = 1
        private const val TYPE_AAAA = 28
        private const val IPV4_LEN = 4
        private const val IPV6_LEN = 16
        private const val BYTE_MASK = 0xFF
        private const val BYTE_SHIFT = 8
        private const val POINTER_MASK = 0xC0
        private const val POINTER_VALUE_MASK = 0x3F
        private const val POINTER_LEN = 2
        private const val LABEL_END = 0
        private const val MAX_LABEL_LEN = 64
        private const val MAX_NAME_JUMPS = 64
        private const val DOMAIN_SEPARATOR = "."
    }
}
