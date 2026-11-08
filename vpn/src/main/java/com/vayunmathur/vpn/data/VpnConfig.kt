package com.vayunmathur.vpn.data

import com.vayunmathur.library.util.DatabaseItem
import kotlinx.serialization.Serializable

@Serializable
data class VpnConfig(
    override val id: Long = 0,
    val name: String = "",
    val privateKey: String = "",
    val publicKey: String = "",
    val address: String = "",
    val dns: String = "",
    val mtu: Int = 1280,
    val peerPublicKey: String = "",
    val peerPresharedKey: String = "",
    val peerAllowedIPs: String = "0.0.0.0/0, ::/0",
    val peerEndpoint: String = "",
    val peerKeepalive: Int = 25,
    val lastUsed: Long = 0,
) : DatabaseItem

data class WgQuickImport(
    val privateKey: String,
    val address: String,
    val dns: String,
    val mtu: Int,
    val peerPublicKey: String,
    val peerPresharedKey: String,
    val peerAllowedIps: String,
    val peerEndpoint: String,
    val peerKeepalive: Int,
)

object WgConfigParser {
    fun parse(confText: String): Result<WgQuickImport> = runCatching {
        buildImport(splitSections(confText))
    }

    private fun splitSections(confText: String): Map<String, Map<String, String>> {
        val sections = mutableMapOf<String, MutableMap<String, String>>()
        var cur = ""
        confText.lineSequence().forEach { raw ->
            cur = consumeLine(raw, cur, sections)
        }
        return sections
    }

    private fun consumeLine(
        raw: String,
        cur: String,
        sections: MutableMap<String, MutableMap<String, String>>,
    ): String {
        val line = raw.trim()
        if (isComment(line)) return cur
        if (isSectionHeader(line)) {
            val name = line.removeSurrounding("[", "]").trim()
            sections.getOrPut(name) { mutableMapOf() }
            return name
        }
        storeEntry(line, cur, sections)
        return cur
    }

    private fun isComment(line: String): Boolean =
        line.isEmpty() || line.startsWith("#") || line.startsWith(";")

    private fun isSectionHeader(line: String): Boolean =
        line.startsWith("[") && line.endsWith("]")

    private fun storeEntry(
        line: String,
        cur: String,
        sections: MutableMap<String, MutableMap<String, String>>,
    ) {
        val idx = line.indexOf('=')
        if (idx <= 0 || cur.isEmpty()) return
        val key = line.substring(0, idx).trim().lowercase()
        sections[cur]?.set(key, line.substring(idx + 1).trim())
    }

    private fun buildImport(sections: Map<String, Map<String, String>>): WgQuickImport {
        val iface = sections["Interface"] ?: error("Missing [Interface]")
        val peer = sections["Peer"] ?: error("Missing [Peer]")
        return WgQuickImport(
            privateKey = iface["privatekey"] ?: error("Missing PrivateKey"),
            address = iface["address"] ?: "",
            dns = iface["dns"] ?: "",
            mtu = iface["mtu"]?.toIntOrNull() ?: 1280,
            peerPublicKey = peer["publickey"] ?: error("Missing Peer PublicKey"),
            peerPresharedKey = peer["presharedkey"] ?: "",
            peerAllowedIps = peer["allowedips"] ?: "0.0.0.0/0, ::/0",
            peerEndpoint = peer["endpoint"] ?: "",
            peerKeepalive = peer["persistentkeepalive"]?.toIntOrNull() ?: 25,
        )
    }

    fun toWgQuick(c: VpnConfig): String = buildString {
        appendLine("[Interface]")
        appendLine("PrivateKey = ${c.privateKey}")
        if (c.address.isNotBlank()) appendLine("Address = ${c.address}")
        if (c.dns.isNotBlank()) appendLine("DNS = ${c.dns}")
        if (c.mtu != 0) appendLine("MTU = ${c.mtu}")
        appendLine()
        appendLine("[Peer]")
        appendLine("PublicKey = ${c.peerPublicKey}")
        if (c.peerPresharedKey.isNotBlank()) appendLine("PresharedKey = ${c.peerPresharedKey}")
        appendLine("AllowedIPs = ${c.peerAllowedIPs}")
        if (c.peerEndpoint.isNotBlank()) appendLine("Endpoint = ${c.peerEndpoint}")
        if (c.peerKeepalive > 0) appendLine("PersistentKeepalive = ${c.peerKeepalive}")
    }
}

fun VpnConfig.endpointHost(): String = endpointHostOf(peerEndpoint)

/**
 * Host part of a wg-quick `Endpoint` value (`host:port`). Bracketed IPv6
 * literals (`[2001:db8::1]:51820`) yield the address inside the brackets;
 * anything else yields the text before the first `:`.
 */
fun endpointHostOf(peerEndpoint: String): String {
    val ep = peerEndpoint.trim()
    if (ep.startsWith("[")) {
        val end = ep.indexOf(']')
        if (end > 1) return ep.substring(1, end).trim()
    }
    return ep.substringBefore(':').trim()
}
fun VpnConfig.endpointPort(): Int = peerEndpoint.substringAfterLast(':').toIntOrNull() ?: DEFAULT_WG_PORT

private const val DEFAULT_WG_PORT = 51820

data class VpnStats(
    val handshakeAgoMs: Long = 0,
    val txBytes: Long = 0,
    val rxBytes: Long = 0,
    val loss: Float = 0f,
    val rttMs: Int = 0,
)
