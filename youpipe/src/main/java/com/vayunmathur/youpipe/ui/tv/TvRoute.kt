package com.vayunmathur.youpipe.ui.tv

import com.vayunmathur.library.util.NavKey
import kotlinx.serialization.Serializable

/**
 * TV navigation graph: mirrors the phone [com.vayunmathur.youpipe.Route] destinations a D-pad
 * can reach, without the phone pager's swipe or its search overlay.
 *
 * The phone graph stays exactly as it is — this is a separate sealed interface so a TV
 * destination can never leak into the phone back stack or vice versa. Dialogs (add-to-playlist,
 * create-playlist, create-category) are entries here rather than overlays, because a TV has no
 * bottom sheet to host them: each is a full D-pad-traversable list screen with its own focus.
 *
 * The six rail tabs are siblings: selecting one resets the back stack to that route
 * (see the route-backed TvAppFrame), so BACK, deep links and process restore see
 * the tab actually on screen. The old `Main(initialTab)` wrapper is gone;
 * [tvRouteForTab] turns a legacy tab index into the start sibling.
 */
@Serializable
sealed interface TvRoute : NavKey {
    /** Rail tab: search. A sibling route, opened via the rail (see TvRouteDestination). */
    @Serializable
    data object Search : TvRoute

    /** Rail tab: home feed (the default start tab). */
    @Serializable
    data object Home : TvRoute

    /** Rail tab: subscriptions. */
    @Serializable
    data object Subscriptions : TvRoute

    /** Rail tab: watch history. */
    @Serializable
    data object History : TvRoute

    /** Rail tab: saved / library hub (playlists, watch later, downloads entry). */
    @Serializable
    data object Saved : TvRoute

    /** Rail tab: settings hub. */
    @Serializable
    data object Settings : TvRoute

    /** Details for one video: description, chapters, related, comments. Opened from the player. */
    @Serializable
    data class Video(val videoID: Long) : TvRoute

    /** Fullscreen playback for one video - what selecting any video opens. BACK returns. */
    @Serializable
    data class VideoPlayerRoute(val videoID: Long) : TvRoute

    /** Immutable value describing channel; carries channelID. */
    @Serializable
    data class Channel(val channelID: String) : TvRoute

    /** Immutable value describing subscription feed; carries category. */
    @Serializable
    data class SubscriptionFeed(val category: String?) : TvRoute

    /** Immutable value describing create subscription category; carries id. */
    @Serializable
    data class CreateSubscriptionCategory(val id: String?) : TvRoute

    /** Singleton holding downloads state and operations. */
    @Serializable
    data object Downloads : TvRoute

    /** Immutable value describing playlist detail; carries playlistId. */
    @Serializable
    data class PlaylistDetail(val playlistId: Long) : TvRoute

    /** Singleton holding create playlist state and operations. */
    @Serializable
    data object CreatePlaylist : TvRoute

    /** Staged membership checkboxes as a focusable list; confirms with the focused OK row. */
    @Serializable
    data class AddToPlaylist(val videoID: Long, val includeWatchLater: Boolean = true) : TvRoute

    /** Singleton holding settings general state and operations. */
    @Serializable
    data object SettingsGeneral : TvRoute

    /** Singleton holding settings sponsor block state and operations. */
    @Serializable
    data object SettingsSponsorBlock : TvRoute

    /** Singleton holding settings recommendations state and operations. */
    @Serializable
    data object SettingsRecommendations : TvRoute

    /** Singleton holding settings data state and operations. */
    @Serializable
    data object SettingsData : TvRoute

    /** Singleton holding manage interests state and operations. */
    @Serializable
    data object ManageInterests : TvRoute
}

/** Rail indices, matching the rail order. Kept for the legacy initialTab int. */
const val TV_RAIL_SEARCH = 0
const val TV_RAIL_HOME = 1
const val TV_RAIL_SUBSCRIPTIONS = 2
const val TV_RAIL_HISTORY = 3
const val TV_RAIL_SAVED = 4
const val TV_RAIL_SETTINGS = 5
const val TV_RAIL_COUNT = 6

/** The sibling route for a legacy rail [tab] index, clamped to the rail range. */
internal fun tvRouteForTab(tab: Int): TvRoute = when (tab.coerceIn(TV_RAIL_SEARCH, TV_RAIL_SETTINGS)) {
    TV_RAIL_SEARCH -> TvRoute.Search
    TV_RAIL_HOME -> TvRoute.Home
    TV_RAIL_SUBSCRIPTIONS -> TvRoute.Subscriptions
    TV_RAIL_HISTORY -> TvRoute.History
    TV_RAIL_SAVED -> TvRoute.Saved
    else -> TvRoute.Settings
}
