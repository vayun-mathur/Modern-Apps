package com.vayunmathur.music.wear.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.wear.compose.material.Chip
import androidx.wear.compose.material.ChipDefaults
import androidx.wear.compose.material.Text
import com.vayunmathur.music.wear.data.WearMusicStore
import com.vayunmathur.music.wear.domain.WearMusicOp
import com.vayunmathur.music.wear.domain.WearOpKind
import com.vayunmathur.music.wear.domain.WearPageId
import com.vayunmathur.music.wear.domain.WearPageKey
import com.vayunmathur.music.wear.domain.WearPageRequest
import com.vayunmathur.music.wear.sync.WearLinkBridge

/** Root switch over [WearRoute]: every phone page has exactly one watch screen here. */
@Composable
fun WearMusicRoot(store: WearMusicStore, route: WearRoute, onRoute: (WearRoute) -> Unit) {
    when (val current = route) {
        WearRoute.Home -> WearTrackListScreen(store, WearPageId(WearPageKey.HOME), "Songs", onRoute)
        WearRoute.Albums -> WearAlbumListScreen(store, onRoute)
        WearRoute.Artists -> WearArtistListScreen(store, onRoute)
        WearRoute.Playlists -> WearPlaylistListScreen(store, onRoute)
        WearRoute.NowPlaying -> WearNowPlayingScreen(store, onRoute)
        is WearRoute.AlbumDetail -> WearTrackListScreen(store, WearPageId(WearPageKey.ALBUM_DETAIL, current.albumId), "Album", onRoute)
        is WearRoute.ArtistDetail -> WearTrackListScreen(store, WearPageId(WearPageKey.ARTIST_DETAIL, current.artistId), "Artist", onRoute)
        is WearRoute.PlaylistDetail -> WearTrackListScreen(store, WearPageId(WearPageKey.PLAYLIST_DETAIL, current.playlistId), "Playlist", onRoute)
        is WearRoute.AddToPlaylist -> WearAddToPlaylistScreen(store, current.musicId, onRoute)
        WearRoute.Queue -> WearQueueScreen(store, onRoute)
        WearRoute.Search -> WearSearchScreen(store, onRoute)
        WearRoute.Settings -> WearSettingsScreen(store, onRoute)
    }
}

/** Requests the first snapshot for [page] when the watch has not visited it yet. */
@Composable
fun WearPageLoader(store: WearMusicStore, page: WearPageId) {
    val context = LocalContext.current
    val pages by store.pages.collectAsState()
    LaunchedEffect(page, pages.containsKey(page)) {
        if (!pages.containsKey(page)) WearLinkBridge.requestPage(context, WearPageRequest(page))
    }
}

/** Shared track rows: tap plays the page's queue on the phone starting at that track. */
@Composable
fun WearTrackRows(store: WearMusicStore, page: WearPageId, onRoute: (WearRoute) -> Unit) {
    val context = LocalContext.current
    val pages by store.pages.collectAsState()
    val playback by store.playback.collectAsState()
    val snapshot = pages[page]
    if (snapshot == null) {
        Text("Loading from your phone\u2026")
        return
    }
    LazyColumn {
        items(snapshot.tracks) { track ->
            Chip(
                label = { Text((if (track.id == playback.trackId) "\u25B6 " else "") + track.title) },
                secondaryLabel = { Text(track.artist) },
                onClick = {
                    store.enqueueOp(
                        WearMusicOp(
                            WearOpKind.PLAY_TRACKS,
                            trackIds = snapshot.tracks.map { it.id },
                            startIndex = snapshot.tracks.indexOf(track),
                        ),
                    )
                    onRoute(WearRoute.NowPlaying)
                },
                colors = ChipDefaults.secondaryChipColors(),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        if (snapshot.nextCursor.isNotEmpty()) {
            item {
                Chip(
                    label = { Text("Load more") },
                    onClick = { WearLinkBridge.requestPage(context, WearPageRequest(page, snapshot.nextCursor)) },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}
