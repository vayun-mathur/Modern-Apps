package com.vayunmathur.uniflix.ui.tv

import com.vayunmathur.library.util.NavKey
import com.vayunmathur.uniflix.domain.MediaType
import kotlinx.serialization.Serializable

/**
 * TV navigation graph: the six rail tabs as sibling routes plus the pushed
 * detail/player/addon pages.
 *
 * The phone [com.vayunmathur.uniflix.Route] graph stays exactly as it is — this is a
 * separate sealed interface so a TV destination can never leak into the phone back
 * stack or vice versa. mediaType rides as the same lowercase "movie"/"tv" string the
 * phone route uses, so conversion stays trivial.
 *
 * The rail tabs are siblings: selecting one resets the back stack to that route
 * (see the route-backed TvAppFrame), so BACK, deep links and process restore see
 * the tab actually on screen. The old `Main(initialTab)` wrapper is gone;
 * [tvRouteForTab] turns a legacy tab index into the start sibling.
 */
@Serializable
sealed interface TvRoute : NavKey {
    /** Rail tab: search. */
    @Serializable
    data object Search : TvRoute

    /** Rail tab: home (the default start tab). */
    @Serializable
    data object Home : TvRoute

    /** Rail tab: movies browse. */
    @Serializable
    data object Movies : TvRoute

    /** Rail tab: shows browse. */
    @Serializable
    data object Shows : TvRoute

    /** Rail tab: my list. */
    @Serializable
    data object MyList : TvRoute

    /** Rail tab: addon manager. */
    @Serializable
    data object Addons : TvRoute

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
/** Rail indices, matching the rail order. Kept for the legacy initialTab int. */
const val TV_TAB_SEARCH = 0
const val TV_TAB_HOME = 1
const val TV_TAB_MOVIES = 2
const val TV_TAB_SHOWS = 3
const val TV_TAB_MY_LIST = 4
const val TV_TAB_ADDONS = 5

/** The sibling route for a legacy rail [tab] index, clamped to the rail range. */
internal fun tvRouteForTab(tab: Int): TvRoute = when (tab.coerceIn(TV_TAB_SEARCH, TV_TAB_ADDONS)) {
    TV_TAB_SEARCH -> TvRoute.Search
    TV_TAB_HOME -> TvRoute.Home
    TV_TAB_MOVIES -> TvRoute.Movies
    TV_TAB_SHOWS -> TvRoute.Shows
    TV_TAB_MY_LIST -> TvRoute.MyList
    else -> TvRoute.Addons
}

internal fun mediaTypeOf(raw: String): MediaType =
    if (raw == "tv") MediaType.TV else MediaType.MOVIE
