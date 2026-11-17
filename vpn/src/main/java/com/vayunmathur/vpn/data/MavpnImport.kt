package com.vayunmathur.vpn.data

import android.content.Context
import com.vayunmathur.vpn.domain.GeoServer
import com.vayunmathur.vpn.domain.MavpnBundle
import com.vayunmathur.vpn.domain.ParsedMavpnServer
import java.io.File
import java.security.MessageDigest

/**
 * Stable on-disk/database identity for one imported bundle: a slug of the
 * provider plus a short digest of provider + server ids, so re-importing the
 * same provider catalogue reuses the same bundle instead of duplicating it.
 * The digest is over ids only — never over config (key) material.
 */
fun bundleIdFor(provider: String, serverIds: List<String>): String {
    val slug = provider.lowercase().map { char -> if (char.isLetterOrDigit()) char else '-' }
        .joinToString("").trim('-').ifBlank { "provider" }
    val digest = MessageDigest.getInstance("SHA-256")
        .digest((provider + "\n" + serverIds.sorted().joinToString("\n")).encodeToByteArray())
    val short = digest.take(DIGEST_BYTES).joinToString("") { "%02x".format(it) }
    return "$slug-$short"
}

/**
 * Maps a validated bundle to selectable configs in the app's existing tunnel
 * model. Each imported server becomes a normal [VpnConfig] (so the tunnel
 * list, detail screen and activation path work unchanged), carrying its
 * provider/country/city/bundle metadata alongside.
 */
fun MavpnBundle<WgQuickImport>.toVpnConfigs(
    bundleId: String,
    existing: List<VpnConfig> = emptyList(),
    derivePublicKey: (String) -> String = { "" },
): List<VpnConfig> = servers.map { server ->
    server.toVpnConfig(bundleId, manifest.provider, existing, derivePublicKey)
}

fun ParsedMavpnServer<WgQuickImport>.toVpnConfig(
    bundleId: String,
    provider: String,
    existing: List<VpnConfig> = emptyList(),
    derivePublicKey: (String) -> String = { "" },
): VpnConfig {
    val imp = config
    val previous = existing.firstOrNull { it.bundleId == bundleId && it.bundleServerId == entry.id }
    return VpnConfig(
        id = previous?.id ?: 0,
        name = "$provider — ${entry.city}",
        privateKey = imp.privateKey,
        publicKey = runCatching { derivePublicKey(imp.privateKey) }.getOrDefault(""),
        address = imp.address,
        dns = imp.dns,
        mtu = imp.mtu,
        peerPublicKey = imp.peerPublicKey,
        peerPresharedKey = imp.peerPresharedKey,
        peerAllowedIPs = imp.peerAllowedIps,
        peerEndpoint = effectiveEndpoint { imp.peerEndpoint },
        peerKeepalive = imp.peerKeepalive,
        lastUsed = previous?.lastUsed ?: 0,
        provider = provider,
        countryCode = entry.country,
        city = entry.city,
        bundleId = bundleId,
        bundleServerId = entry.id,
    )
}

/** Flattens a config into the geo picker's server model, or null for non-bundle configs. */
fun VpnConfig.toGeoServerOrNull(): GeoServer? =
    if (bundleId.isBlank() && provider.isBlank()) null else GeoServer(
        configId = id,
        provider = provider,
        countryCode = countryCode,
        city = city,
        name = name,
        endpoint = peerEndpoint,
    )

private const val DIGEST_BYTES = 6

/**
 * Stores imported bundles under app-private storage:
 * `files/mavpn-bundles/<bundleId>/manifest.json` + `servers/<name>.conf`.
 * File names come only from the validated manifest (the parser already
 * rejected traversal), and every write is re-confined to the bundle dir.
 * Config contents — including private keys — are never logged here.
 */
object MavpnBundleStore {
    fun bundleDir(context: Context, bundleId: String): File = bundleDir(context.filesDir, bundleId)

    fun bundleDir(filesDir: File, bundleId: String): File =
        File(File(filesDir, "mavpn-bundles"), safeSegment(bundleId))

    fun save(context: Context, bundleId: String, bundle: MavpnBundle<WgQuickImport>, manifestJson: String) {
        val dir = bundleDir(context, bundleId)
        val serversDir = File(dir, "servers").apply { mkdirs() }
        File(dir, "manifest.json").writeText(manifestJson)
        for (server in bundle.servers) {
            val target = File(serversDir, File(server.entry.file).name)
            check(target.canonicalPath.startsWith(serversDir.canonicalPath)) { "Server file escapes bundle dir" }
            target.writeText(server.confText)
        }
    }

    /** Provider names of every bundle currently stored, sorted; empty when none were imported. */
    fun storedBundleIds(context: Context): List<String> =
        File(context.filesDir, "mavpn-bundles").listFiles { file -> file.isDirectory }
            ?.map { it.name }?.sorted().orEmpty()

    /**
     * Best-effort removal of one bundle's stored files, for when its last
     * config has been deleted from the database. The database is the source
     * of truth, so a failure here (or a bundle that was never stored) must
     * never fail the delete that triggered it: it reports whether the bundle
     * directory is gone instead of throwing. [safeSegment] confines the target to `mavpn-bundles/`, so a
     * hostile bundle id cannot delete anything outside it.
     */
    fun delete(context: Context, bundleId: String): Boolean = delete(context.filesDir, bundleId)

    fun delete(filesDir: File, bundleId: String): Boolean =
        runCatching {
            val dir = bundleDir(filesDir, bundleId)
            dir.deleteRecursively()
            !dir.exists()
        }.getOrDefault(false)

    private fun safeSegment(raw: String): String =
        raw.map { char -> if (char.isLetterOrDigit() || char in "._-") char else '-' }
            .joinToString("").ifBlank { "bundle" }
}
