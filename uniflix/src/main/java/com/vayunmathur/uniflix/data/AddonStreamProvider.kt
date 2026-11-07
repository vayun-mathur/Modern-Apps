package com.vayunmathur.uniflix.data

import com.vayunmathur.uniflix.domain.AddonManifest
import com.vayunmathur.uniflix.domain.AddonStreamsResponse
import com.vayunmathur.uniflix.domain.ManifestError
import com.vayunmathur.uniflix.domain.MediaType
import com.vayunmathur.uniflix.domain.StreamProvider
import com.vayunmathur.uniflix.domain.VideoSource
import com.vayunmathur.uniflix.domain.addonResourceType
import com.vayunmathur.uniflix.domain.addonStreamId
import kotlinx.serialization.json.Json

private const val ADDON_TIMEOUT_MS = 15_000L
private const val ERROR_PREVIEW_CHARS = 120

/**
 * A user-installed URL addon (Stremio-style) as a [StreamProvider].
 *
 * Third parties publish a `manifest.json` over HTTPS; Uniflix fetches it once
 * at install/refresh (see [fetchManifest]) and resolves streams via
 * `GET {base}/stream/{type}/{id}.json`. No fork of Uniflix is needed to add one.
 *
 * The provider holds only the base URL + display name, so playback never
 * re-fetches the manifest — validation happens once at install time.
 */
class AddonStreamProvider(
    val baseUrl: String,
    val displayName: String,
) : StreamProvider() {
    override val name: String get() = displayName
    override val supports4k: Boolean = false

    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun resolve(
        tmdbId: Int,
        mediaType: MediaType,
        season: Int,
        episode: Int,
        server: String?,
    ): VideoSource =
        fetchStreams(tmdbId, mediaType, season, episode).firstOrNull()
            ?: error("Addon '$displayName' has no streams for this title")

    override suspend fun alternates(
        tmdbId: Int,
        mediaType: MediaType,
        season: Int,
        episode: Int,
    ): List<VideoSource> =
        fetchStreams(tmdbId, mediaType, season, episode).drop(1)

    private suspend fun fetchStreams(
        tmdbId: Int,
        mediaType: MediaType,
        season: Int,
        episode: Int,
    ): List<VideoSource> {
        val url = "$baseUrl/stream/${addonResourceType(mediaType)}/${addonStreamId(tmdbId, mediaType, season, episode)}.json"
        val resp = runCatching {
            uniflixGet(url, connectTimeoutMs = ADDON_TIMEOUT_MS)
        }.getOrElse {
            error("Addon '$displayName' unreachable: ${it.message}")
        }
        if (!resp.isSuccess) {
            error("Addon '$displayName' returned ${resp.status}: ${resp.text.take(ERROR_PREVIEW_CHARS)}")
        }
        val parsed = runCatching {
            json.decodeFromString(AddonStreamsResponse.serializer(), resp.text)
        }.getOrElse {
            error("Addon '$displayName' returned malformed streams: ${it.message}")
        }
        return parsed.streams.mapNotNull { it.toVideoSource(displayName) }
    }

    companion object {
        private val manifestJson = Json { ignoreUnknownKeys = true }

        /** Fetches and validates the manifest at [manifestUrl] (bare base or full `.../manifest.json`). */
        suspend fun fetchManifest(manifestUrl: String): Pair<String, AddonManifest> {
            val base = normalizeBaseUrl(manifestUrl)
            val resp = runCatching {
                uniflixGet("$base/manifest.json", connectTimeoutMs = ADDON_TIMEOUT_MS)
            }.getOrElse {
                throw ManifestError.Network("Could not reach $manifestUrl: ${it.message}")
            }
            if (!resp.isSuccess) {
                throw ManifestError.Network("Manifest request failed: ${resp.status}")
            }
            val manifest = runCatching {
                manifestJson.decodeFromString(AddonManifest.serializer(), resp.text).validate()
            }.getOrElse {
                if (it is ManifestError) throw it
                throw ManifestError.Parse("Not a stream addon manifest: ${it.message}", it)
            }
            return base to manifest
        }

        /**
         * Normalizes user input to a bare base URL. Accepts a full manifest URL
         * or a bare host; enforces HTTPS except loopback/private (dev addons).
         */
        fun normalizeBaseUrl(input: String): String {
            val trimmed = input.trim().trimEnd('/')
            val withoutManifest = trimmed.removeSuffix("/manifest.json")
            val withScheme = if ("://" in withoutManifest) withoutManifest else "https://$withoutManifest"
            val uri = runCatching { java.net.URI(withScheme) }.getOrNull()
                ?: throw ManifestError.Parse("Not a valid addon URL: $input")
            val scheme = uri.scheme?.lowercase()
            val host = uri.host?.lowercase()
                ?: throw ManifestError.Parse("Not a valid addon URL: $input")
            if (scheme == "http" && !isLoopbackOrPrivate(host)) {
                throw ManifestError.InsecureUrl(input.trim())
            }
            if (scheme != "http" && scheme != "https") {
                throw ManifestError.Parse("Addon URL must be http(s): $input")
            }
            return "$scheme://$host${if (uri.port != -1) ":${uri.port}" else ""}${uri.path.trimEnd('/')}"
        }

        private fun isLoopbackOrPrivate(host: String): Boolean {
            if (host == "localhost" || host.endsWith(".localhost")) return true
            // Literal IPs only: resolving a DNS name here would do network I/O
            // on the caller's thread (main, from the manager UI). Non-IP http
            // hosts stay rejected as insecure — use https or a literal IP.
            if (!isLiteralIp(host)) return false
            val addr = runCatching { java.net.InetAddress.getByName(host) }.getOrNull() ?: return false
            return addr.isLoopbackAddress || addr.isSiteLocalAddress
        }

        private fun isLiteralIp(host: String): Boolean {
            val cleaned = host.removePrefix("[").removeSuffix("]")
            if (cleaned.contains(':')) return true // IPv6 literal
            val parts = cleaned.split('.')
            return parts.size == 4 && parts.all { it.toIntOrNull() in 0..255 }
        }
    }
}
