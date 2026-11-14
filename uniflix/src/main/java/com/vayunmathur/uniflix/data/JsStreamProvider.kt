package com.vayunmathur.uniflix.data

import com.vayunmathur.uniflix.domain.JsPluginManifest
import com.vayunmathur.uniflix.domain.MediaType
import com.vayunmathur.uniflix.domain.StreamProvider
import com.vayunmathur.uniflix.domain.SubtitleTrack
import com.vayunmathur.uniflix.domain.VideoSource
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
private data class JsResolveArgs(
    val tmdb: Int,
    val type: String,
    val season: Int = 0,
    val episode: Int = 0,
    val server: String? = null,
    val config: String = "{}",
)

@Serializable
private data class JsResolveResult(
    val streamUrl: String = "",
    val title: String = "",
    val subtitles: List<JsSubtitle> = emptyList(),
) {
    fun toVideoSource(fallbackTitle: String): VideoSource? {
        if (streamUrl.isBlank()) return null
        return VideoSource(
            streamUrl = streamUrl,
            title = title.ifBlank { fallbackTitle },
            subtitles = subtitles.mapNotNull {
                if (it.url.isBlank()) null
                else SubtitleTrack(
                    language = it.language.ifBlank { "en" },
                    displayName = it.displayName.ifBlank { it.language },
                    url = it.url,
                )
            },
        )
    }
}

@Serializable
private data class JsSubtitle(
    val language: String = "",
    val displayName: String = "",
    val url: String = "",
)

private val pluginJson = Json { ignoreUnknownKeys = true }

/**
 * An installable JavaScript plugin as a [StreamProvider].
 *
 * Delegates resolution to [JsPluginEngine]: builds JSON args from the catalog
 * id, calls the plugin's `resolve()`/`servers()`/`alternates()`, and maps the
 * returned JSON onto [VideoSource]. All failures are wrapped with the plugin
 * name so the registry's per-addon error badge reads correctly.
 */
class JsStreamProvider(
    private val manifest: JsPluginManifest,
    private val displayName: String,
    private val configJson: String = "{}",
    private val network: NetworkBridge = NetworkClientBridge,
) : StreamProvider() {
    override val name: String get() = displayName
    override val supports4k: Boolean = false

    private fun engine() = JsPluginEngine(manifest, network)

    override suspend fun resolve(
        tmdbId: Int,
        mediaType: MediaType,
        season: Int,
        episode: Int,
        server: String?,
    ): VideoSource {
        val args = pluginJson.encodeToString(
            JsResolveArgs.serializer(),
            JsResolveArgs(
                tmdb = tmdbId,
                type = if (mediaType == MediaType.TV) "series" else "movie",
                season = season,
                episode = episode,
                server = server,
                config = configJson,
            ),
        )
        val raw = runCatching { engine().resolve(args) }.getOrElse { failResolve(wrap(it)) }
        val result = runCatching {
            pluginJson.decodeFromString(JsResolveResult.serializer(), raw)
        }.getOrElse { failResolve(wrap("returned malformed result: ${it.message}")) }
        return result.toVideoSource(displayName)
            ?: failResolve(wrap("has no streams for this title"))
    }

    override suspend fun alternates(
        tmdbId: Int,
        mediaType: MediaType,
        season: Int,
        episode: Int,
    ): List<VideoSource> {
        val args = pluginJson.encodeToString(
            JsResolveArgs.serializer(),
            JsResolveArgs(
                tmdb = tmdbId,
                type = if (mediaType == MediaType.TV) "series" else "movie",
                season = season,
                episode = episode,
                config = configJson,
            ),
        )
        val raw = runCatching { engine().alternates(args) }.getOrElse { return emptyList() }
        if (raw.isBlank()) return emptyList()
        val list = runCatching {
            pluginJson.decodeFromString<List<JsResolveResult>>(raw)
        }.getOrDefault(emptyList())
        return list.mapNotNull { it.toVideoSource(displayName) }
    }

    override suspend fun servers(): List<String> {
        val raw = runCatching { engine().servers() }.getOrElse { failResolve(wrap(it)) }
        return runCatching {
            pluginJson.decodeFromString<List<String>>(raw)
        }.getOrElse { failResolve(wrap("returned malformed servers: ${it.message}")) }
            .map { it.trim() }
            .filter { it.isNotEmpty() }
    }

    /** Throws [t]; keeps throw sites out of the resolve paths. */
    private fun failResolve(t: Throwable): Nothing = throw t

    private fun wrap(cause: Throwable): Throwable =
        if (cause.message?.startsWith("'$displayName'") == true) cause
        else IllegalStateException("'$displayName' failed: ${cause.message}", cause)

    private fun wrap(message: String): Throwable =
        IllegalStateException("'$displayName' $message")
}
