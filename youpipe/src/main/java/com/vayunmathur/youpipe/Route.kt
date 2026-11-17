package com.vayunmathur.youpipe

import com.vayunmathur.library.util.NavKey
import kotlinx.serialization.Serializable

/** Contract for route. */
@Serializable
sealed interface Route : NavKey {
    /** Home tab (search / recommendations feed). */
    @Serializable
    data object Home : Route

    /** Subscriptions tab. */
    @Serializable
    data object Subscriptions : Route

    /** Watch-history tab. */
    @Serializable
    data object History : Route

    /** Saved (playlists / downloads hub) tab. */
    @Serializable
    data object Saved : Route

    /** Settings tab. */
    @Serializable
    data object Settings : Route

    /** Immutable value describing video page; carries videoID. */
    @Serializable
    data class VideoPage(val videoID: Long) : Route

    /** Immutable value describing channel page; carries channelID. */
    @Serializable
    data class ChannelPage(val channelID: String): Route

    /** Immutable value describing subscription videos page; carries category. */
    @Serializable
    data class SubscriptionVideosPage(val category: String?): Route

    /** Immutable value describing create subscription category; carries id. */
    @Serializable
    data class CreateSubscriptionCategory(val id: String?): Route

    /** Singleton holding downloads state and operations. */
    @Serializable
    data object Downloads: Route

    /** Immutable value describing playlist detail; carries playlistId. */
    @Serializable
    data class PlaylistDetail(val playlistId: Long): Route

    /** Singleton holding create playlist state and operations. */
    @Serializable
    data object CreatePlaylist: Route

    /** Immutable value describing add to playlist; carries videoID, includeWatchLater. */
    @Serializable
    data class AddToPlaylist(val videoID: Long, val includeWatchLater: Boolean = true): Route

    /** Immutable value describing add to watch later; carries videoID. */
    @Serializable
    data class AddToWatchLater(val videoID: Long): Route

    /** Singleton holding settings general state and operations. */
    @Serializable
    data object SettingsGeneral: Route

    /** Singleton holding settings sponsor block state and operations. */
    @Serializable
    data object SettingsSponsorBlock: Route

    /** Singleton holding settings recommendations state and operations. */
    @Serializable
    data object SettingsRecommendations: Route

    /** Singleton holding settings data state and operations. */
    @Serializable
    data object SettingsData: Route

    /** Singleton holding manage interests state and operations. */
    @Serializable
    data object ManageInterests: Route
}
