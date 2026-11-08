package com.vayunmathur.uniflix.data

import com.vayunmathur.library.network.NetworkClient
import com.vayunmathur.uniflix.domain.Episode
import com.vayunmathur.uniflix.domain.MediaDetail
import com.vayunmathur.uniflix.domain.MediaItem
import com.vayunmathur.uniflix.domain.MediaType
import com.vayunmathur.uniflix.domain.SeasonSummary
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** TMDB catalog client via the self-hosted proxy. Keyless: the server holds the TMDB API key. */
class TmdbApi {
    private val json = Json { ignoreUnknownKeys = true }

    private fun url(path: String): String = "$BASE$path"

    private suspend fun getItems(requestUrl: String): List<MediaItem> {
        val resp = NetworkClient.execute(requestUrl)
        if (!resp.isSuccess) error("TMDB request failed: ${resp.status}")
        return json.decodeFromString(TmdbList.serializer(), resp.text).results.map { it.toMediaItem() }
    }

    suspend fun trendingMovies(): List<MediaItem> =
        getItems(url("/trending/movie"))

    suspend fun trendingTv(): List<MediaItem> =
        getItems(url("/trending/tv"))

    suspend fun topRatedMovies(): List<MediaItem> =
        getItems(url("/top/movie"))

    suspend fun topRatedTv(): List<MediaItem> =
        getItems(url("/top/tv"))

    suspend fun discoverMovies(): List<MediaItem> =
        getItems(url("/discover/movie"))

    suspend fun discoverTv(): List<MediaItem> =
        getItems(url("/discover/tv"))

    suspend fun search(query: String): List<MediaItem> {
        val encoded = java.net.URLEncoder.encode(query, Charsets.UTF_8.name())
        return getItems(url("/search?query=$encoded"))
    }

    suspend fun movieDetail(tmdbId: Int): MediaDetail {
        val requestUrl = url("/movie/$tmdbId")
        val resp = NetworkClient.execute(requestUrl)
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
        val requestUrl = url("/tv/$tmdbId")
        val resp = NetworkClient.execute(requestUrl)
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
        val resp = NetworkClient.execute(url("/tv/$tmdbId/season/$season"))
        if (!resp.isSuccess) error("TMDB request failed: ${resp.status}")
        return json.decodeFromString(TmdbSeason.serializer(), resp.text).episodes.map {
            Episode(season, it.episodeNumber, it.name, it.overview, it.stillPath)
        }
    }

    private suspend fun movieRecommendations(tmdbId: Int): List<MediaItem> =
        getItems(url("/movie/$tmdbId/recommendations"))

    companion object {
        private const val BASE = "https://api.maproj.org/api/tmdb"
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
