package com.vayunmathur.music.ui

import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import com.vayunmathur.library.ui.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import com.vayunmathur.library.ui.EmptyState
import com.vayunmathur.library.ui.ListPage
import com.vayunmathur.library.util.NavBackStack
import com.vayunmathur.library.util.sharedContainer
import com.vayunmathur.library.util.sharedText
import com.vayunmathur.music.platform.AlbumArt
import com.vayunmathur.music.platform.MusicViewModel
import com.vayunmathur.music.R
import com.vayunmathur.music.Route
import com.vayunmathur.music.data.Playlist
import com.vayunmathur.music.ui.components.NewPlaylistFab
import com.vayunmathur.music.ui.components.ShufflePlayFab

@Composable
fun PlaylistsTabContent(backStack: NavBackStack<Route>, musicViewModel: MusicViewModel) {
    val playlists by musicViewModel.playlists.collectAsState()
    val loaded by musicViewModel.loaded.collectAsState()
    if (playlists.isEmpty() && loaded) {
        EmptyState(title = stringResource(R.string.empty_playlists_title), message = stringResource(R.string.empty_playlists_message), action = { NewPlaylistFab(musicViewModel) })
        return
    }
    // Playlists come from Room via `SELECT * FROM Playlist`, which has no ORDER BY, so unlike the
    // MediaStore-backed tabs this one still sorts client-side. Hoisted because a Comparator built
    // at the call site is a new instance every recomposition, which invalidates ListPage's
    // memoised sort.
    val byName = remember { compareBy<Playlist> { it.name } }
    ListPage<Playlist, Route, Route.Song>(backStack, playlists, stringResource(R.string.nav_playlists), {
        Text(it.name, modifier = Modifier.sharedText("music-playlist-name-${it.id}"))
    }, {
    }, {
        Route.PlaylistDetail(it)
    }, leadingContent = { playlist ->
        val songIds by musicViewModel.matchedMusicForPlaylist(playlist.id)
        val allMusic by musicViewModel.music.collectAsState()
        val musicUris = allMusic.filter { it.id in songIds }.map { it.uri.toUri() }
        AlbumArt(musicUris, Modifier.size(40.dp).clip(RoundedCornerShape(8.dp)).sharedContainer("music-playlist-art-${playlist.id}"))
    }, searchEnabled = true, fab = {
        NewPlaylistFab(musicViewModel)
        ShufflePlayFab(musicViewModel)
    }, searchString = { it.name }, sortOrder = byName, isRefreshing = !loaded)
}
