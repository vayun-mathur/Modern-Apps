package com.vayunmathur.music.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.BottomAppBar
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import com.vayunmathur.library.util.NavBackStack
import com.vayunmathur.library.ui.IconPause
import com.vayunmathur.library.ui.IconPlay
import com.vayunmathur.library.ui.ListPage
import com.vayunmathur.library.ui.invisibleClickable
import com.vayunmathur.library.util.BottomBarItem
import com.vayunmathur.library.util.BottomNavBar
import com.vayunmathur.library.util.DatabaseViewModel
import com.vayunmathur.music.AlbumArt
import com.vayunmathur.music.PlaybackManager
import com.vayunmathur.music.R
import com.vayunmathur.music.Route
import com.vayunmathur.music.database.Music
import kotlinx.coroutines.launch
import kotlin.random.Random

@Composable
fun HomeScreen(backStack: NavBackStack<Route>, viewModel: DatabaseViewModel) {
    val context = LocalContext.current
    val playbackManager = remember { PlaybackManager.getInstance(context) }

    Scaffold(bottomBar = {
        BottomNavBar(backStack, listOf(
            BottomBarItem("Home", Route.Home, R.drawable.baseline_library_music_24),
            BottomBarItem("Albums", Route.Albums, R.drawable.baseline_album_24),
            BottomBarItem("Artists", Route.Artists, R.drawable.outline_person_24),
        ), Route.Home)
    }) { paddingValues ->
        Box(Modifier.padding(paddingValues).consumeWindowInsets(paddingValues)) {
            ListPage<Music, Route, Route.Song>(backStack, viewModel, "Music", { Text(it.title) }, {
                Text(it.artist)
            }, { toPlay ->
                val allSongs = viewModel.getAll<Music>()
                val toPlayIndex = allSongs.indexOfFirst { it.id == toPlay }
                playbackManager.playSong(allSongs, toPlayIndex)
                Route.Song
            }, leadingContent = { music ->
                AlbumArt(music.uri.toUri(), Modifier.size(40.dp))
            }, searchEnabled = true, bottomBar = {
                PlayingBottomBar(playbackManager, backStack)
            }, fab = {
                ShufflePlayFab(viewModel, playbackManager)
            }, sortOrder = Comparator.comparing { it.title })
        }
    }
}

@Composable
fun ShufflePlayFab(viewModel: DatabaseViewModel, playbackManager: PlaybackManager) {
    val coroutineScope = rememberCoroutineScope()
    val allSongs by viewModel.data<Music>().collectAsState()

    if(allSongs.isNotEmpty()) {
        FloatingActionButton({
            coroutineScope.launch {
                val toPlayIndex = Random.nextInt(allSongs.size)
                playbackManager.playSong(allSongs, toPlayIndex)
                if (!playbackManager.shuffleMode.value)
                    playbackManager.toggleShuffle()
            }
        }) {
            Icon(painterResource(R.drawable.ic_shuffle), null)
        }
    }
}


@Composable
fun PlayingBottomBar(
    playbackManager: PlaybackManager,
    backStack: NavBackStack<Route>
) {
    val currentItem by playbackManager.currentMediaItem.collectAsState()
    val isPlaying by playbackManager.isPlaying.collectAsState()
    val progress by playbackManager.currentPosition.collectAsState()
    val duration by playbackManager.duration.collectAsState()

    val progressFactor = if (duration > 0) progress.toFloat() / duration.toFloat() else 0f

    if (currentItem != null) {
        val metadata = currentItem!!.mediaMetadata

        BottomAppBar(
            Modifier.height(100.dp).invisibleClickable{
                backStack.add(Route.Song)
            }
        ) {
            Column {
                // Progress bar pinned to the top of the bar
                LinearProgressIndicator(
                    progress = { progressFactor },
                    modifier = Modifier.fillMaxWidth().height(2.dp),
                    color = MaterialTheme.colorScheme.primary,
                    trackColor = Color.Transparent
                )

                ListItem(
                    modifier = Modifier.fillMaxWidth(),
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                    // Leading: The Album Art
                    headlineContent = {
                        Text(
                            text = metadata.title?.toString() ?: "Unknown Title",
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    },
                    supportingContent = {
                        Text(
                            text = metadata.artist?.toString() ?: "Unknown Artist",
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    },
                    leadingContent = {
                        AlbumArt(metadata.artworkUri!!, Modifier.size(48.dp).clip(RoundedCornerShape(8.dp)))
                    },
                    trailingContent = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            IconButton(onClick = { playbackManager.togglePlayPause() }) {
                                if(isPlaying) {
                                    IconPause()
                                } else {
                                    IconPlay()
                                }
                            }
                            IconButton(onClick = { playbackManager.skipNext() }) {
                                Icon(painterResource(R.drawable.ic_skip_next), contentDescription = null)
                            }
                        }
                    }
                )
            }
        }
    }
}