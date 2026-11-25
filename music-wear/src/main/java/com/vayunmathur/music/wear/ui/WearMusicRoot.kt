package com.vayunmathur.music.wear.ui

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.ScalingLazyListScope
import androidx.wear.compose.foundation.lazy.items
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material.Chip
import androidx.wear.compose.material.ChipDefaults
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Text
import com.vayunmathur.music.wear.R
import com.vayunmathur.music.wear.data.WearMusicStore
import com.vayunmathur.music.wear.domain.WearMusicOp
import com.vayunmathur.music.wear.domain.WearOpKind
import com.vayunmathur.music.wear.domain.WearPageId
import com.vayunmathur.music.wear.domain.WearPageKey
import com.vayunmathur.music.wear.domain.WearPageRequest
import com.vayunmathur.music.wear.domain.WearPageSnapshot
import com.vayunmathur.music.wear.domain.WearPlaybackState
import com.vayunmathur.music.wear.sync.WearLinkBridge

/** Root switch over [WearRoute]: every phone page has exactly one watch screen here. */
@Composable
fun WearMusicRoot(store: WearMusicStore, route: WearRoute, onRoute: (WearRoute) -> Unit) {
    when (val current = route) {
        WearRoute.Home -> WearHomeScreen(store, onRoute)
        WearRoute.Albums -> WearAlbumListScreen(store, onRoute)
        WearRoute.Artists -> WearArtistListScreen(store, onRoute)
        WearRoute.Playlists -> WearPlaylistListScreen(store, onRoute)
        WearRoute.NowPlaying -> WearNowPlayingScreen(store, onRoute)
        is WearRoute.AlbumDetail -> WearTrackListScreen(
            store, WearPageId(WearPageKey.ALBUM_DETAIL, current.albumId),
            stringResource(R.string.wear_albums), onRoute,
        ) { onRoute(WearRoute.Albums) }
        is WearRoute.ArtistDetail -> WearTrackListScreen(
            store, WearPageId(WearPageKey.ARTIST_DETAIL, current.artistId),
            stringResource(R.string.wear_artists), onRoute,
        ) { onRoute(WearRoute.Artists) }
        is WearRoute.PlaylistDetail -> WearTrackListScreen(
            store, WearPageId(WearPageKey.PLAYLIST_DETAIL, current.playlistId),
            stringResource(R.string.wear_playlists), onRoute,
        ) { onRoute(WearRoute.Playlists) }
        is WearRoute.AddToPlaylist -> WearAddToPlaylistScreen(store, current.musicId, onRoute)
        WearRoute.Queue -> WearQueueScreen(store, onRoute)
        WearRoute.Search -> WearSearchScreen(store, onRoute)
        WearRoute.Settings -> WearSettingsScreen(store, onRoute)
    }
}

/**
 * Watch home: library navigation first (albums/artists/playlists/search/
 * now-playing/about), then the Songs page. Before this hub existed the
 * Albums/Artists/Playlists/Search/Settings routes were unreachable.
 */
@Composable
fun WearHomeScreen(store: WearMusicStore, onRoute: (WearRoute) -> Unit) {
    val page = WearPageId(WearPageKey.HOME)
    WearPageLoader(store, page)
    val pages by store.pages.collectAsState()
    val playback by store.playback.collectAsState()
    WearHomeContent(
        page = page,
        snapshot = pages[page],
        playback = playback,
        onEnqueueOp = store::enqueueOp,
        onRoute = onRoute,
    )
}

/**
 * Stateless home body behind [WearHomeScreen]: page snapshot and playback state are
 * parameters, so Compose previews (src/screenshotTest) render literal fixtures with
 * no store or phone.
 */
@Composable
internal fun WearHomeContent(
    page: WearPageId,
    snapshot: WearPageSnapshot?,
    playback: WearPlaybackState,
    onEnqueueOp: (WearMusicOp) -> Unit,
    onRoute: (WearRoute) -> Unit,
) {
    // Hoisted: ScalingLazyColumn's scope lambda is not a @Composable context.
    val appName = stringResource(R.string.app_name)
    val nowPlayingLabel = stringResource(R.string.wear_now_playing)
    val albumsLabel = stringResource(R.string.wear_albums)
    val artistsLabel = stringResource(R.string.wear_artists)
    val playlistsLabel = stringResource(R.string.wear_playlists)
    val searchLabel = stringResource(R.string.wear_search)
    val settingsLabel = stringResource(R.string.wear_settings)
    val songsLabel = stringResource(R.string.wear_songs)
    ScalingLazyColumn(state = rememberScalingLazyListState()) {
        item { WearTitle(appName) }
        wearNavItem(nowPlayingLabel, WearRoute.NowPlaying, onRoute)
        wearNavItem(albumsLabel, WearRoute.Albums, onRoute)
        wearNavItem(artistsLabel, WearRoute.Artists, onRoute)
        wearNavItem(playlistsLabel, WearRoute.Playlists, onRoute)
        wearNavItem(searchLabel, WearRoute.Search, onRoute)
        wearNavItem(settingsLabel, WearRoute.Settings, onRoute)
        item { WearTitle(songsLabel) }
        wearTrackItems(onEnqueueOp, page, snapshot, playback, onRoute)
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

/** Centred screen title, inset so the round bezel never clips it. */
@Composable
internal fun WearTitle(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.title3,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
    )
}

/** Centred body note (loading / empty / offline honesty). */
@Composable
internal fun WearNote(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.body2,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
    )
}

/** One navigation chip (full-width, round-safe horizontal inset). */
internal fun ScalingLazyListScope.wearNavItem(
    label: String,
    route: WearRoute,
    onRoute: (WearRoute) -> Unit,
) {
    item {
        Chip(
            onClick = { onRoute(route) },
            label = { Text(label, maxLines = 1) },
            colors = ChipDefaults.secondaryChipColors(),
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
        )
    }
}

/** Trailing back chip used by every non-home music screen. */
internal fun ScalingLazyListScope.wearBackItem(onBack: () -> Unit) {
    item {
        Chip(
            onClick = onBack,
            label = { Text(stringResource(R.string.wear_back), maxLines = 1) },
            colors = ChipDefaults.secondaryChipColors(),
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
        )
    }
}

/**
 * Track rows for one page snapshot: tap plays the page's queue on the phone
 * starting at that track. A missing snapshot is an explicit loading/offline
 * note (never a blank list); an empty one is the synced-empty note.
 */
internal fun ScalingLazyListScope.wearTrackItems(
    onEnqueueOp: (WearMusicOp) -> Unit,
    page: WearPageId,
    snapshot: WearPageSnapshot?,
    playback: WearPlaybackState,
    onRoute: (WearRoute) -> Unit,
) {
    if (snapshot == null) {
        item { WearNote(stringResource(R.string.wear_loading)) }
        item { WearNote(stringResource(R.string.wear_no_phone)) }
        return
    }
    if (snapshot.tracks.isEmpty()) {
        item { WearNote(stringResource(R.string.wear_empty)) }
        return
    }
    items(snapshot.tracks, key = { it.id }) { track ->
        Chip(
            label = { Text((if (track.id == playback.trackId) "▶ " else "") + track.title, maxLines = 1) },
            secondaryLabel = { Text(track.artist, maxLines = 1) },
            onClick = {
                onEnqueueOp(
                    WearMusicOp(
                        WearOpKind.PLAY_TRACKS,
                        trackIds = snapshot.tracks.map { it.id },
                        startIndex = snapshot.tracks.indexOf(track),
                    ),
                )
                onRoute(WearRoute.NowPlaying)
            },
            colors = ChipDefaults.secondaryChipColors(),
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
        )
    }
    if (snapshot.nextCursor.isNotEmpty()) {
        item {
            val context = LocalContext.current
            Chip(
                label = { Text(stringResource(R.string.wear_load_more), maxLines = 1) },
                onClick = { WearLinkBridge.requestPage(context, WearPageRequest(page, snapshot.nextCursor)) },
                colors = ChipDefaults.secondaryChipColors(),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
            )
        }
    }
}
