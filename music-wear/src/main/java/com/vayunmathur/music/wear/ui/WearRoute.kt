package com.vayunmathur.music.wear.ui

/** Watch navigation destinations mirroring the phone `Route` 1:1; detail routes carry IDs, never selection state. */
sealed interface WearRoute {
    /** Phone Route.Home (Songs tab). */
    data object Home : WearRoute

    /** Phone Route.Albums tab. */
    data object Albums : WearRoute

    /** Phone Route.Artists tab. */
    data object Artists : WearRoute

    /** Phone Route.Playlists tab. */
    data object Playlists : WearRoute

    /** Phone Route.Song (now-playing full screen). */
    data object NowPlaying : WearRoute

    /** Phone Route.AlbumDetail. */
    data class AlbumDetail(val albumId: Long) : WearRoute

    /** Phone Route.ArtistDetail. */
    data class ArtistDetail(val artistId: Long) : WearRoute

    /** Phone Route.PlaylistDetail. */
    data class PlaylistDetail(val playlistId: Long) : WearRoute

    /** Phone Route.AddToPlaylistDialog, as a watch sheet. */
    data class AddToPlaylist(val musicId: Long) : WearRoute

    /** Up-next queue (part of the phone now-playing screen). */
    data object Queue : WearRoute

    /** Search (phone scaffold search on every tab), as its own watch page. */
    data object Search : WearRoute

    /** About/library info (the phone has no settings page; see module report). */
    data object Settings : WearRoute
}
