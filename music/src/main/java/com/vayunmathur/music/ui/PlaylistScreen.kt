package com.vayunmathur.music.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import com.vayunmathur.library.ui.IconAdd
import com.vayunmathur.library.ui.ListPage
import com.vayunmathur.library.util.BottomBarItem
import com.vayunmathur.library.util.BottomNavBar
import com.vayunmathur.library.util.DatabaseViewModel
import com.vayunmathur.library.util.NavBackStack
import com.vayunmathur.music.util.AlbumArt
import com.vayunmathur.music.util.PlaybackManager
import com.vayunmathur.music.R
import com.vayunmathur.music.Route
import com.vayunmathur.music.data.Music
import com.vayunmathur.music.data.Playlist
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

@Composable
fun PlaylistScreen(backStack: NavBackStack<Route>, viewModel: DatabaseViewModel) {
    val context = LocalContext.current
    val playbackManager = remember { PlaybackManager.getInstance(context) }
    val scope = rememberCoroutineScope()
    Scaffold(bottomBar = {
        BottomNavBar(backStack, listOf(
            BottomBarItem(stringResource(R.string.nav_home), Route.Home, R.drawable.baseline_library_music_24),
            BottomBarItem(stringResource(R.string.nav_albums), Route.Albums, R.drawable.baseline_album_24),
            BottomBarItem(stringResource(R.string.nav_artists), Route.Artists, R.drawable.outline_person_24),
            BottomBarItem(stringResource(R.string.nav_playlists), Route.Playlists, R.drawable.baseline_library_music_24),
        ), Route.Playlists)
    }) { paddingValues ->
        Box(Modifier.padding(paddingValues).consumeWindowInsets(paddingValues)) {
            ListPage<Playlist, Route, Route.Song>(backStack, viewModel, stringResource(R.string.page_title_playlists), { Text(it.name) }, {
            }, {
                Route.PlaylistDetail(it)
            }, leadingContent = { playlist ->
                val songIds = remember { runBlocking { viewModel.getMatches<Playlist, Music>(playlist.id) } }
                val allMusic by viewModel.data<Music>().collectAsState()
                val musicUris = allMusic.filter { it.id in songIds }.map { it.uri.toUri() }
                AlbumArt(musicUris, Modifier.size(40.dp))
            }, searchEnabled = true, bottomBar = {
                PlayingBottomBar(playbackManager, backStack)
            }, fab = {
                ShufflePlayFab(viewModel, playbackManager)
            }, sortOrder = Comparator.comparing { it.name }, otherActions = {
                IconButton(onClick = {
                    scope.launch {
                        viewModel.upsert(Playlist(name = context.getString(R.string.new_playlist)))
                    }
                }) {
                    IconAdd()
                }
            })
        }
    }
}
