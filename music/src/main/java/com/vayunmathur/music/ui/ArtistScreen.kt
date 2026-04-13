package com.vayunmathur.music.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import com.vayunmathur.library.util.NavBackStack
import com.vayunmathur.library.ui.ListPage
import com.vayunmathur.library.util.BottomBarItem
import com.vayunmathur.library.util.BottomNavBar
import com.vayunmathur.library.util.DatabaseViewModel
import com.vayunmathur.music.util.AlbumArt
import com.vayunmathur.music.util.PlaybackManager
import com.vayunmathur.music.R
import com.vayunmathur.music.Route
import com.vayunmathur.music.data.Album
import com.vayunmathur.music.data.Artist
import kotlinx.coroutines.runBlocking

@Composable
fun ArtistScreen(backStack: NavBackStack<Route>, viewModel: DatabaseViewModel) {
    val context = LocalContext.current
    val playbackManager = remember { PlaybackManager.getInstance(context) }

    Scaffold(bottomBar = {
        BottomNavBar(backStack, listOf(
            BottomBarItem(stringResource(R.string.nav_home), Route.Home, R.drawable.baseline_library_music_24),
            BottomBarItem(stringResource(R.string.nav_albums), Route.Albums, R.drawable.baseline_album_24),
            BottomBarItem(stringResource(R.string.nav_artists), Route.Artists, R.drawable.outline_person_24),
            BottomBarItem(stringResource(R.string.nav_playlists), Route.Playlists, R.drawable.baseline_library_music_24),
        ), Route.Artists)
    }) { paddingValues ->
        Box(Modifier.padding(paddingValues).consumeWindowInsets(paddingValues)) {
            ListPage<Artist, Route, Route.Song>(backStack, viewModel, stringResource(R.string.page_title_music), { Text(it.name) }, {
            }, {
                Route.ArtistDetail(it)
            }, leadingContent = { artist ->
                val albums by viewModel.getMatchesState<Artist, Album>(artist.id)
                val allAlbums by viewModel.data<Album>().collectAsState()
                val albumsUris by remember { derivedStateOf { allAlbums.filter { it.id in albums }.map { it.uri.toUri() } } }
                AlbumArt(albumsUris, Modifier.size(40.dp))
            }, searchEnabled = true, bottomBar = {
                PlayingBottomBar(playbackManager, backStack)
            }, fab = {
                ShufflePlayFab(viewModel, playbackManager)
            }, sortOrder = Comparator.comparing { it.name })
        }
    }
}