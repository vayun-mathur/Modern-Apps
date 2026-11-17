package com.vayunmathur.vpn.data

import com.vayunmathur.library.network.NetworkClient
import com.vayunmathur.vpn.domain.MavpnUpdateMetadata
import com.vayunmathur.vpn.domain.parseMavpnUpdateMetadata
import com.vayunmathur.vpn.domain.verifySha256

/**
 * Fetches `mavpn-update/1` metadata and replacement bundles for a manual,
 * user-triggered update check. Provider hosts are user-supplied, so requests
 * go through [NetworkClient] with system trust (the app already initialises
 * `NetworkClient` with `TrustBundle.SYSTEM` for exactly this reason; see
 * `vpn/build.gradle.kts`). No background or silent checks are made.
 *
 * The byte fetcher is injectable so unit tests exercise check/download logic
 * without a network or any non-HTTPS test seam.
 */
class MavpnUpdateClient(
    private val fetchBytes: suspend (String) -> ByteArray = { url ->
        val response = NetworkClient.execute(url, useSystemTrust = true)
        if (!response.isSuccess) error("HTTP ${response.status} fetching update")
        response.bytes
    },
) {
    suspend fun fetchMetadata(updateUrl: String): MavpnUpdateMetadata =
        parseMavpnUpdateMetadata(fetchBytes(updateUrl).decodeToString())

    /**
     * Downloads a replacement bundle and verifies it before any import runs.
     * Throws without touching local state when the download or the SHA-256
     * check fails, so a bad update can never partially replace a bundle.
     */
    suspend fun fetchVerifiedBundle(metadata: MavpnUpdateMetadata): ByteArray {
        val downloadUrl = metadata.downloadUrl ?: error("Update metadata has no downloadUrl")
        val bytes = fetchBytes(downloadUrl)
        val sha = metadata.sha256 ?: error("Update metadata has no sha256; refusing unverified bundle")
        if (!verifySha256(bytes, sha)) error("Downloaded bundle failed SHA-256 verification")
        return bytes
    }
}
