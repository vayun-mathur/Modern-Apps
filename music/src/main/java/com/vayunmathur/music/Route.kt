package com.vayunmathur.music

import com.vayunmathur.library.util.NavKey
import kotlinx.serialization.Serializable

/** Contract for route. */
@Serializable
sealed interface Route : NavKey {
    /** Singleton holding home state and operations. */
    @Serializable
    data object Home : Route

    /** Albums tab: sibling of [Home], switched with `backStack.reset`. */
    @Serializable
    data object Albums : Route

    /** Artists tab: sibling of [Home], switched with `backStack.reset`. */
    @Serializable
    data object Artists : Route

    /** Playlists tab: sibling of [Home], switched with `backStack.reset`. */
    @Serializable
    data object Playlists : Route

    /** Singleton holding song state and operations. */
    @Serializable
    data object Song : Route

    /** Immutable value describing album detail; carries albumId. */
    @Serializable
    data class AlbumDetail(val albumId: Long) : Route

    /** Immutable value describing artist detail; carries artistId. */
    @Serializable
    data class ArtistDetail(val artistId: Long) : Route

    /** Immutable value describing playlist detail; carries playlistId. */
    @Serializable
    data class PlaylistDetail(val playlistId: Long) : Route

    /** Immutable value describing add to playlist dialog; carries musicId. */
    @Serializable
    data class AddToPlaylistDialog(val musicId: Long) : Route
}
