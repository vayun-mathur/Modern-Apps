package com.vayunmathur.uniflix.data

import com.vayunmathur.library.network.RawResponse
import com.vayunmathur.library.network.StaticMirror
import com.vayunmathur.uniflix.domain.Episode
import com.vayunmathur.uniflix.domain.MediaDetail
import com.vayunmathur.uniflix.domain.MediaItem
import com.vayunmathur.uniflix.domain.MediaType
import com.vayunmathur.uniflix.domain.SeasonSummary
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * TMDB catalog client via the self-hosted proxy. Keyless: the server holds the TMDB API key.
 *
 * Catalog pages are the same for every user, so the server also publishes them to R2
 * (location_share_server `src/handlers/tmdb.rs`): lists hourly, title pages whenever
 * someone fetches them. Every page except search is read from that static copy first
 * ([STATIC_BASE] + the same path + `.json`) and from the proxy when the copy is missing
 * or older than the page's freshness window.
 */
class TmdbApi {
    private val json = Json { ignoreUnknownKeys = true }

    private fun url(path: String): String = "$BASE$path"

    /** Static copy of [path] when present and fresh, else the proxy's answer. */
    private suspend fun catalogGet(path: String, maxAgeMs: Long): RawResponse {
        val static = runCatching { uniflixGet("$STATIC_BASE$path.json") }.getOrNull()
        if (static != null && StaticMirror.accept(static, maxAgeMs, System.currentTimeMillis())) return static
        return uniflixGet(url(path))
    }

    private fun decodeItems(resp: RawResponse): List<MediaItem> {
        if (!resp.isSuccess) error("TMDB request failed: ${resp.status}")
        return json.decodeFromString(TmdbList.serializer(), resp.text).results.map { it.toMediaItem() }
    }

    private suspend fun getList(path: String): List<MediaItem> = decodeItems(catalogGet(path, LIST_MAX_AGE_MS))

    suspend fun trendingMovies(): List<MediaItem> =
        getList("/trending/movie")

    suspend fun trendingTv(): List<MediaItem> =
        getList("/trending/tv")

    suspend fun topRatedMovies(): List<MediaItem> =
        getList("/top/movie")

    suspend fun topRatedTv(): List<MediaItem> =
        getList("/top/tv")

    suspend fun discoverMovies(): List<MediaItem> =
        getList("/discover/movie")

    suspend fun discoverTv(): List<MediaItem> =
        getList("/discover/tv")

    /** Free-text search has unbounded keys: always the proxy. */
    suspend fun search(query: String): List<MediaItem> {
        val encoded = java.net.URLEncoder.encode(query, Charsets.UTF_8.name())
        return decodeItems(uniflixGet(url("/search?query=$encoded")))
    }

    suspend fun movieDetail(tmdbId: Int): MediaDetail {
        val resp = catalogGet("/movie/$tmdbId", DETAIL_MAX_AGE_MS)
        if (!resp.isSuccess) error("TMDB request failed: ${resp.status}")
        val dto = json.decodeFromString(TmdbMovieDetail.serializer(), resp.text)
        val recs = runCatching { movieRecommendations(tmdbId) }.getOrDefault(emptyList())
        return MediaDetail(
            tmdbId = dto.id,
            mediaType = MediaType.MOVIE,
            title = dto.title,
            year = dto.releaseDate?.take(4)?.toIntOrNull(),
            overview = dto.overview,
            posterPath = dto.posterPath,
            backdropPath = dto.backdropPath,
            voteAverage = dto.voteAverage,
            runtimeMinutes = dto.runtime,
            recommendations = recs,
        )
    }

    suspend fun tvDetail(tmdbId: Int): MediaDetail {
        val resp = catalogGet("/tv/$tmdbId", DETAIL_MAX_AGE_MS)
        if (!resp.isSuccess) error("TMDB request failed: ${resp.status}")
        val dto = json.decodeFromString(TmdbTvDetail.serializer(), resp.text)
        return MediaDetail(
            tmdbId = dto.id,
            mediaType = MediaType.TV,
            title = dto.name,
            year = dto.firstAirDate?.take(4)?.toIntOrNull(),
            overview = dto.overview,
            posterPath = dto.posterPath,
            backdropPath = dto.backdropPath,
            voteAverage = dto.voteAverage,
            seasons = dto.seasons.map {
                SeasonSummary(it.seasonNumber, it.name, it.episodeCount, it.posterPath)
            },
        )
    }

    suspend fun seasonEpisodes(tmdbId: Int, season: Int): List<Episode> {
        val resp = catalogGet("/tv/$tmdbId/season/$season", DETAIL_MAX_AGE_MS)
        if (!resp.isSuccess) error("TMDB request failed: ${resp.status}")
        return json.decodeFromString(TmdbSeason.serializer(), resp.text).episodes.map {
            Episode(season, it.episodeNumber, it.name, it.overview, it.stillPath)
        }
    }

    private suspend fun movieRecommendations(tmdbId: Int): List<MediaItem> =
        decodeItems(catalogGet("/movie/$tmdbId/recommendations", DETAIL_MAX_AGE_MS))

    companion object {
        private const val BASE = "https://api.maproj.org/api/tmdb"
        private const val STATIC_BASE = "${StaticMirror.BASE}/tmdb/v1"

        /** Lists are republished hourly; older means the server stopped. */
        private const val LIST_MAX_AGE_MS = 6L * 60 * 60 * 1000

        /** Title pages are rewritten when fetched after 3 days; refresh weekly. */
        private const val DETAIL_MAX_AGE_MS = 7L * 24 * 60 * 60 * 1000
    }
}

@Serializable
private data class TmdbList(val results: List<TmdbItem> = emptyList())

@Serializable
private data class TmdbItem(
    val id: Int,
    @SerialName("media_type") val mediaType: String? = null,
    val title: String? = null,
    val name: String? = null,
    @SerialName("release_date") val releaseDate: String? = null,
    @SerialName("first_air_date") val firstAirDate: String? = null,
    val overview: String = "",
    @SerialName("poster_path") val posterPath: String? = null,
    @SerialName("backdrop_path") val backdropPath: String? = null,
    @SerialName("vote_average") val voteAverage: Double = 0.0,
) {
    fun toMediaItem(): MediaItem {
        val type = if (mediaType == "tv") MediaType.TV else MediaType.MOVIE
        val date = releaseDate ?: firstAirDate
        return MediaItem(
            tmdbId = id,
            mediaType = type,
            title = title ?: name ?: "",
            year = date?.take(4)?.toIntOrNull(),
            overview = overview,
            posterPath = posterPath,
            backdropPath = backdropPath,
            voteAverage = voteAverage,
        )
    }
}

@Serializable
private data class TmdbMovieDetail(
    val id: Int,
    val title: String,
    @SerialName("release_date") val releaseDate: String? = null,
    val overview: String = "",
    @SerialName("poster_path") val posterPath: String? = null,
    @SerialName("backdrop_path") val backdropPath: String? = null,
    @SerialName("vote_average") val voteAverage: Double = 0.0,
    val runtime: Int? = null,
)

@Serializable
private data class TmdbTvDetail(
    val id: Int,
    val name: String,
    @SerialName("first_air_date") val firstAirDate: String? = null,
    val overview: String = "",
    @SerialName("poster_path") val posterPath: String? = null,
    @SerialName("backdrop_path") val backdropPath: String? = null,
    @SerialName("vote_average") val voteAverage: Double = 0.0,
    val seasons: List<TmdbSeasonSummary> = emptyList(),
)

@Serializable
private data class TmdbSeasonSummary(
    @SerialName("season_number") val seasonNumber: Int,
    val name: String,
    @SerialName("episode_count") val episodeCount: Int = 0,
    @SerialName("poster_path") val posterPath: String? = null,
)

@Serializable
private data class TmdbSeason(val episodes: List<TmdbEpisode> = emptyList())

@Serializable
private data class TmdbEpisode(
    @SerialName("episode_number") val episodeNumber: Int,
    val name: String,
    val overview: String = "",
    @SerialName("still_path") val stillPath: String? = null,
)
