package com.vayunmathur.uniflix.domain

import kotlinx.serialization.Serializable

/** Movie or series, as keyed across catalog, artwork, and local watch state. */
enum class MediaType { MOVIE, TV }

/** A catalog row: id + artwork paths, resolved to CDN URLs by TmdbImageUrl. */
@Serializable
data class MediaItem(
    val tmdbId: Int,
    val mediaType: MediaType,
    val title: String,
    val year: Int? = null,
    val overview: String = "",
    val posterPath: String? = null,
    val backdropPath: String? = null,
    val voteAverage: Double = 0.0,
)

/** Full detail for the detail screen, mapped from the TMDB append_to_response payload. */
@Serializable
data class MediaDetail(
    val tmdbId: Int,
    val mediaType: MediaType,
    val title: String,
    val year: Int? = null,
    val overview: String = "",
    val posterPath: String? = null,
    val backdropPath: String? = null,
    val voteAverage: Double = 0.0,
    val runtimeMinutes: Int? = null,
    val seasons: List<SeasonSummary> = emptyList(),
    val recommendations: List<MediaItem> = emptyList(),
)

/** Immutable value describing season summary. */
@Serializable
data class SeasonSummary(
    val seasonNumber: Int,
    val name: String,
    val episodeCount: Int,
    val stillPath: String? = null,
)

/** Immutable value describing episode. */
@Serializable
data class Episode(
    val seasonNumber: Int,
    val episodeNumber: Int,
    val name: String,
    val overview: String = "",
    val stillPath: String? = null,
)

/** A playable stream plus its subtitle tracks, supplied by a StreamProvider. */
@Serializable
data class VideoSource(
    val streamUrl: String,
    val title: String,
    val subtitles: List<SubtitleTrack> = emptyList(),
)

/** One subtitle track, fetched by the player from its own URL. */
@Serializable
data class SubtitleTrack(
    val language: String,
    val displayName: String,
    val url: String,
)
