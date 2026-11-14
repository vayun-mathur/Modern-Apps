package com.vayunmathur.uniflix.ui.tv

import com.vayunmathur.library.util.NavKey
import com.vayunmathur.uniflix.domain.MediaType
import kotlinx.serialization.Serializable

/**
 * TV navigation graph: the rail tabs plus the pushed detail/player/addon pages.
 *
 * The phone [com.vayunmathur.uniflix.Route] graph stays exactly as it is — this is a
 * separate sealed interface so a TV destination can never leak into the phone back
 * stack or vice versa. mediaType rides as the same lowercase "movie"/"tv" string the
 * phone route uses, so conversion stays trivial.
 */
@Serializable
sealed interface TvRoute : NavKey {
    /** The rail: search, home, movies, shows, my list, addons. No swipe, D-pad only. */
    @Serializable
    data class Main(val initialTab: Int = TV_TAB_HOME) : TvRoute

    /** Immutable value describing detail; carries mediaType, tmdbId. */
    @Serializable
    data class Detail(val mediaType: String, val tmdbId: Int) : TvRoute

    /** Immutable value describing watch. */
    @Serializable
    data class Watch(
        val mediaType: String,
        val tmdbId: Int,
        val season: Int = 0,
        val episode: Int = 0,
    ) : TvRoute

    /** Immutable value describing addon detail; carries addonDbId. */
    @Serializable
    data class AddonDetail(val addonDbId: Long) : TvRoute

    /** A full grid page reached from a home "See all" action. */
    @Serializable
    data class Grid(val kind: String) : TvRoute
}

/** Grid page kinds reachable from home "See all" actions. */
const val TV_GRID_CONTINUE = "continue"
const val TV_GRID_TRENDING_MOVIES = "trending-movies"
const val TV_GRID_TRENDING_TV = "trending-tv"
const val TV_GRID_TOP_MOVIES = "top-movies"
const val TV_GRID_TOP_TV = "top-tv"
/** Rail indices in [TvMainTabs], matching the rail order. */
const val TV_TAB_SEARCH = 0
const val TV_TAB_HOME = 1
const val TV_TAB_MOVIES = 2
const val TV_TAB_SHOWS = 3
const val TV_TAB_MY_LIST = 4
const val TV_TAB_ADDONS = 5

internal fun mediaTypeOf(raw: String): MediaType =
    if (raw == "tv") MediaType.TV else MediaType.MOVIE
