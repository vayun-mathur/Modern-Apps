package com.vayunmathur.music.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.navigation3.runtime.NavBackStack
import com.vayunmathur.library.ui.ListPage
import com.vayunmathur.library.util.BottomBarItem
import com.vayunmathur.library.util.BottomNavBar
import com.vayunmathur.library.util.DatabaseViewModel
import com.vayunmathur.music.AlbumArt
import com.vayunmathur.music.PlaybackManager
import com.vayunmathur.music.R
import com.vayunmathur.music.Route
import com.vayunmathur.music.database.Album
import com.vayunmathur.music.database.Artist
import com.vayunmathur.music.database.Music
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlin.random.Random

@Composable
fun ArtistScreen(backStack: NavBackStack<Route>, viewModel: DatabaseViewModel) {
    val context = LocalContext.current
    val playbackManager = remember { PlaybackManager.getInstance(context) }

    val coroutineScope = rememberCoroutineScope()

    Scaffold(bottomBar = {
        BottomNavBar(backStack, listOf(
            BottomBarItem("Home", Route.Home, R.drawable.baseline_library_music_24),
            BottomBarItem("Albums", Route.Albums, R.drawable.baseline_album_24),
            BottomBarItem("Artists", Route.Artists, R.drawable.outline_person_24),
        ), Route.Artists)
    }) { paddingValues ->
        Box(Modifier.padding(paddingValues)) {
            ListPage<Artist, Route, Route.Song>(backStack, viewModel, "Music", { Text(it.name) }, {
            }, {
                Route.ArtistDetail(it)
            }, leadingContent = { artist ->
                val albums = remember { runBlocking { viewModel.getMatches<Artist, Album>(artist.id) } }
                val allAlbums by viewModel.data<Album>().collectAsState()
                val albumsUris = allAlbums.filter { it.id in albums }.map { it.uri.toUri() }
                AlbumArt(albumsUris, Modifier.size(40.dp))
            }, searchEnabled = true, bottomBar = {
                PlayingBottomBar(playbackManager, backStack)
            }, fab = {
                FloatingActionButton({
                    coroutineScope.launch {
                        val allSongs = viewModel.getAll<Music>()
                        val toPlayIndex = Random.nextInt(allSongs.size)
                        playbackManager.playSong(allSongs, toPlayIndex)
                        if (!playbackManager.shuffleMode.value)
                            playbackManager.toggleShuffle()
                    }
                }) {
                    Icon(painterResource(R.drawable.ic_shuffle), null)
                }
            })
        }
    }
}