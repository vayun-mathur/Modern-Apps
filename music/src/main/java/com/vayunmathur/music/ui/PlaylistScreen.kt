package com.vayunmathur.music.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.vayunmathur.library.ui.IconAdd
import com.vayunmathur.library.ui.ListPage
import com.vayunmathur.library.util.BottomBarItem
import com.vayunmathur.library.util.BottomNavBar
import com.vayunmathur.library.util.DatabaseViewModel
import com.vayunmathur.library.util.NavBackStack
import com.vayunmathur.music.PlaybackManager
import com.vayunmathur.music.R
import com.vayunmathur.music.Route
import com.vayunmathur.music.database.Playlist
import kotlinx.coroutines.launch

@Composable
fun PlaylistScreen(backStack: NavBackStack<Route>, viewModel: DatabaseViewModel) {
    val context = LocalContext.current
    val playbackManager = remember { PlaybackManager.getInstance(context) }
    val scope = rememberCoroutineScope()
    Scaffold(bottomBar = {
        BottomNavBar(backStack, listOf(
            BottomBarItem("Home", Route.Home, R.drawable.baseline_library_music_24),
            BottomBarItem("Albums", Route.Albums, R.drawable.baseline_album_24),
            BottomBarItem("Artists", Route.Artists, R.drawable.outline_person_24),
            BottomBarItem("Playlists", Route.Playlists, R.drawable.baseline_library_music_24),
        ), Route.Playlists)
    }) { paddingValues ->
        Box(Modifier.padding(paddingValues).consumeWindowInsets(paddingValues)) {
            ListPage<Playlist, Route, Route.Song>(backStack, viewModel, "Playlists", { Text(it.name) }, {
            }, {
                Route.PlaylistDetail(it)
            }, searchEnabled = true, bottomBar = {
                PlayingBottomBar(playbackManager, backStack)
            }, fab = {
                ShufflePlayFab(viewModel, playbackManager)
            }, sortOrder = Comparator.comparing { it.name }, otherActions = {
                IconButton(onClick = {
                    scope.launch {
                        viewModel.upsert(Playlist(name = "New Playlist"))
                    }
                }) {
                    IconAdd()
                }
            })
        }
    }
}
