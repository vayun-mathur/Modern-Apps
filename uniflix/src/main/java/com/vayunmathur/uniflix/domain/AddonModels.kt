package com.vayunmathur.uniflix.domain

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Stremio stream URL form Uniflix requests: `tmdb:{id}` for movies, `tmdb:{id}:{s}:{e}` for episodes. */
fun addonStreamId(tmdbId: Int, mediaType: MediaType, season: Int = 0, episode: Int = 0): String =
    if (mediaType == MediaType.TV) "tmdb:$tmdbId:$season:$episode" else "tmdb:$tmdbId"

/** Stremio resource type for a catalog title. */
fun addonResourceType(mediaType: MediaType): String =
    if (mediaType == MediaType.TV) "series" else "movie"

/** One stream offered by an addon for `GET {base}/stream/{type}/{id}.json`. */
@Serializable
data class AddonStream(
    val url: String,
    val name: String? = null,
    val title: String? = null,
    val behaviorHints: AddonBehaviorHints? = null,
    val subtitles: List<AddonSubtitle> = emptyList(),
) {
    /** Maps onto the player-facing [VideoSource]; drops blank URLs (addon bug, not fatal). */
    fun toVideoSource(fallbackTitle: String): VideoSource? {
        if (url.isBlank()) return null
        return VideoSource(
            streamUrl = url,
            title = name?.takeIf { it.isNotBlank() } ?: fallbackTitle,
            subtitles = subtitles.mapNotNull { it.toSubtitleTrack() },
        )
    }
}

/** Immutable value describing addon behavior hints. */
@Serializable
data class AddonBehaviorHints(
    @SerialName("notWebReady") val notWebReady: Boolean = false,
)

/** Immutable value describing addon subtitle. */
@Serializable
data class AddonSubtitle(
    val url: String,
    val lang: String = "",
    val id: String? = null,
) {
    fun toSubtitleTrack(): SubtitleTrack? {
        if (url.isBlank()) return null
        return SubtitleTrack(
            language = lang.ifBlank { id ?: "en" },
            displayName = id ?: lang,
            url = url,
        )
    }
}

/** Envelope of `GET {base}/stream/{type}/{id}.json`. */
@Serializable
data class AddonStreamsResponse(
    val streams: List<AddonStream> = emptyList(),
)
