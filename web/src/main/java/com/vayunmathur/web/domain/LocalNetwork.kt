package com.vayunmathur.web.domain

/** How a host relates to the local network, before any name resolution. */
enum class HostKind {
    /** Provably on the local network from the host string alone. */
    LAN,

    /** Provably not, or not in a form we are willing to interpret. */
    PUBLIC,

    /** An ordinary dotted name: only a lookup can say where it points. */
    NEEDS_DNS,
}

/** Resolves a host to its addresses. Injected so policy tests never touch the network. */
fun interface HostResolver {
    /** Textual addresses for [host], or empty when resolution fails. */
    fun resolve(host: String): List<String>
}

/**
 * Whether a host belongs to the local network, decided syntactically.
 *
 * Android's network-security-config can only match exact hostnames, so "cleartext on the
 * LAN only" cannot be expressed in XML for a browser that takes arbitrary user-typed URLs.
 * This is the classifier the app-layer gate ([LanPolicy]) is built on.
 *
 * Pure Kotlin (no `android.*`) so it runs in JVM unit tests.
 */
object LocalNetwork {

    /** Hosts that only a numeric interpretation could explain, e.g. `10.1` or `2130706433`. */
    private val NUMERIC_ONLY = Regex("^[0-9.]+$")

    private const val IPV6_SLOTS = 8
    private const val IPV6_MAPPED_SLOTS = 6
    private const val IPV6_BYTE_LENGTH = 16
    private const val IPV4_TAIL_OFFSET = 12

    /**
     * The host of [url], lowercased and without port, userinfo, brackets or IPv6 zone id.
     *
     * Tolerates scheme-less input (`nas.local`) because the omnibox hands us exactly that.
     * IPv6 brackets are consumed before the port so `[::1]:8080` yields `::1`.
     */
    fun hostOf(url: String): String {
        val afterScheme = if (url.contains("://")) url.substringAfter("://") else url
        val end = afterScheme.indexOfFirst { it == '/' || it == '?' || it == '#' }
        val authority = (if (end < 0) afterScheme else afterScheme.substring(0, end))
            .substringAfterLast('@')
        val host = if (authority.startsWith("[")) {
            val close = authority.indexOf(']')
            if (close < 0) authority.substring(1) else authority.substring(1, close)
        } else {
            authority.substringBefore(':')
        }
        val bare = host.substringBefore('%').lowercase()
        return if (bare.length > 1 && bare.endsWith('.')) bare.dropLast(1) else bare
    }

    /** Where [host] points, as far as its own text can say. */
    fun classify(host: String): HostKind {
        if (host.isEmpty()) return HostKind.PUBLIC
        if (host.contains(':')) {
            return if (parseIpv6(host)?.let(::isPrivateIpv6) == true) HostKind.LAN else HostKind.PUBLIC
        }
        parseIpv4(host)?.let { return if (isPrivateIpv4(it)) HostKind.LAN else HostKind.PUBLIC }
        // A numeric host we could not parse is one of Chromium's legacy IP spellings
        // (`10.1`, `2130706433`). Reimplementing that canonicalisation is not worth it, and
        // sending it to DNS would be pointless, so fail closed.
        if (NUMERIC_ONLY.matches(host)) return HostKind.PUBLIC
        if (isLanHostname(host)) return HostKind.LAN
        return HostKind.NEEDS_DNS
    }

    /** True for a dotted-quad or an IPv6 form; [hostOf] has already removed any port. */
    fun isIpLiteral(host: String): Boolean = host.contains(':') || parseIpv4(host) != null

    /** The no-DNS answer, for main-thread callers that must not block. */
    fun isLanHostSyntactic(host: String): Boolean = classify(host) == HostKind.LAN

    /**
     * Names that are local by definition. `.internal` is excluded because it collides with
     * real cloud-provider zones that resolve publicly.
     */
    private fun isLanHostname(host: String): Boolean =
        !host.contains('.') ||
            host == "localhost" || host.endsWith(".localhost") ||
            host == "local" || host.endsWith(".local") ||
            host == "home.arpa" || host.endsWith(".home.arpa")

    // ------------------------------------------------------------------- IPv4

    /** Strict dotted quad only. Shorthand and hex forms are rejected, so they fail closed. */
    private fun parseIpv4(host: String): IntArray? {
        val parts = host.split('.')
        if (parts.size != 4) return null
        val octets = IntArray(4)
        for (i in 0..3) {
            val part = parts[i]
            if (part.isEmpty() || part.length > 3 || !part.all { it in '0'..'9' }) return null
            // The URL parser reads a leading-zero part as octal (`010` is 8), so a
            // decimal reading here would classify a different address than the one
            // the renderer connects to. Reject and let the caller fail closed.
            if (part.length > 1 && part[0] == '0') return null
            val value = part.toInt()
            if (value > 255) return null
            octets[i] = value
        }
        return octets
    }

    /**
     * `100.64/10` (CGNAT) is deliberately absent: that is carrier space shared with
     * strangers, not a network the user controls.
     */
    private fun isPrivateIpv4(o: IntArray): Boolean = when {
        o[0] == 10 -> true
        o[0] == 172 && o[1] in 16..31 -> true
        o[0] == 192 && o[1] == 168 -> true
        o[0] == 169 && o[1] == 254 -> true
        o[0] == 127 -> true
        o[0] == 0 -> true
        else -> false
    }

    // ------------------------------------------------------------------- IPv6

    /** The 16 bytes of [text], or null when it is not a valid address. */
    private fun parseIpv6(text: String): ByteArray? {
        if (hasDoubleElision(text)) return null
        val elision = text.indexOf("::")
        val parts = splitHeadTail(text, elision) ?: return null
        val (mapped, head, tail) = extractMappedTail(parts.first, parts.second) ?: return null
        val slots = if (mapped != null) IPV6_MAPPED_SLOTS else IPV6_SLOTS
        if (!groupCountsFit(head.size, tail.size, slots, elision >= 0)) return null
        return assembleIpv6(head, tail, mapped, slots)
    }

    /** True when `::` appears twice, which can never be a valid address. */
    private fun hasDoubleElision(text: String): Boolean {
        val first = text.indexOf("::")
        return first >= 0 && text.indexOf("::", first + 2) >= 0
    }

    /** The groups on each side of the `::` elision, or null when either side is malformed. */
    private fun splitHeadTail(text: String, elision: Int): Pair<List<String>, List<String>>? {
        val head = splitGroups(if (elision >= 0) text.substring(0, elision) else text) ?: return null
        val tail = splitGroups(if (elision >= 0) text.substring(elision + 2) else "") ?: return null
        return head to tail
    }

    /**
     * Pulls an `::ffff:a.b.c.d` IPv4 tail off the group lists.
     * Returns the mapped octets (or null) plus the remaining head and tail.
     */
    private fun extractMappedTail(
        head: List<String>,
        tail: List<String>,
    ): Triple<IntArray?, List<String>, List<String>>? {
        val last = tail.lastOrNull() ?: head.lastOrNull()
        if (last == null || !last.contains('.')) return Triple(null, head, tail)
        val mapped = parseIpv4(last) ?: return null
        // An `::ffff:a.b.c.d` tail occupies the final two hextets.
        return if (tail.isNotEmpty()) {
            Triple(mapped, head, tail.dropLast(1))
        } else {
            Triple(mapped, head.dropLast(1), tail)
        }
    }

    /** True when the group counts can fill [slots] hextets, with or without an elision. */
    private fun groupCountsFit(head: Int, tail: Int, slots: Int, elided: Boolean): Boolean =
        if (elided) head + tail <= slots - 1 else head == slots && tail == 0

    /** Writes the parsed groups into the 16 address bytes, or null on a bad hextet. */
    private fun assembleIpv6(
        head: List<String>,
        tail: List<String>,
        mapped: IntArray?,
        slots: Int,
    ): ByteArray? {
        val bytes = ByteArray(IPV6_BYTE_LENGTH)
        head.forEachIndexed { i, group ->
            val value = parseHextet(group) ?: return null
            writeHextet(bytes, i, value)
        }
        tail.forEachIndexed { i, group ->
            val value = parseHextet(group) ?: return null
            writeHextet(bytes, slots - tail.size + i, value)
        }
        mapped?.forEachIndexed { i, octet -> bytes[IPV4_TAIL_OFFSET + i] = octet.toByte() }
        return bytes
    }

    private fun writeHextet(bytes: ByteArray, slot: Int, value: Int) {
        bytes[slot * 2] = (value shr 8).toByte()
        bytes[slot * 2 + 1] = value.toByte()
    }

    /** Colon-separated groups, or null if any is empty (which means a malformed address). */
    private fun splitGroups(text: String): List<String>? {
        if (text.isEmpty()) return emptyList()
        val groups = text.split(':')
        return if (groups.any { it.isEmpty() }) null else groups
    }

    private fun parseHextet(group: String): Int? {
        if (group.isEmpty() || group.length > 4) return null
        var value = 0
        for (c in group) {
            val digit = c.digitToIntOrNull(16) ?: return null
            value = value * 16 + digit
        }
        return value
    }

    private fun isPrivateIpv6(b: ByteArray): Boolean {
        val b0 = b[0].toInt() and 0xFF
        val b1 = b[1].toInt() and 0xFF
        // ::1
        if (b.take(15).all { it == 0.toByte() } && b[15] == 1.toByte()) return true
        // ::ffff:a.b.c.d delegates to the IPv4 rules, so the mapped form cannot smuggle
        // a private address past them (or a public one into them).
        if (b.take(10).all { it == 0.toByte() } &&
            (b[10].toInt() and 0xFF) == 0xFF && (b[11].toInt() and 0xFF) == 0xFF
        ) {
            return isPrivateIpv4(IntArray(4) { b[12 + it].toInt() and 0xFF })
        }
        if (b0 and 0xFE == 0xFC) return true // fc00::/7 unique local
        if (b0 == 0xFE && b1 and 0xC0 == 0x80) return true // fe80::/10 link local
        return false
    }
}
