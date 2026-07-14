package com.vayunmathur.music.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import com.vayunmathur.library.util.NavBackStack
import com.vayunmathur.library.ui.IconPause
import com.vayunmathur.library.ui.IconPlay
import com.vayunmathur.library.ui.ListPage
import com.vayunmathur.library.ui.invisibleClickable
import com.vayunmathur.music.util.AlbumArt
import com.vayunmathur.music.util.MusicViewModel
import com.vayunmathur.music.util.AddToPlaylistButton
import com.vayunmathur.music.R
import com.vayunmathur.music.Route
import com.vayunmathur.music.data.Music

/**
 * Songs tab content. No Scaffold / no BottomNavBar — those live in the
 * surrounding [MusicTabsScreen]. ListPage's own Scaffold is kept (it owns
 * the TopAppBar with the embedded search bar and the shuffle FAB).
 */
@Composable
fun HomeTabContent(backStack: NavBackStack<Route>, musicViewModel: MusicViewModel) {
    val currentMediaItem by musicViewModel.currentMediaItem.collectAsState()
    val currentSource by musicViewModel.currentSource.collectAsState()
    val music by musicViewModel.music.collectAsState()

    ListPage<Music, Route, Route.Song>(backStack, music, stringResource(R.string.page_title_music), { song ->
        val isPlaying = currentMediaItem?.mediaId == song.id.toString() && currentSource == "all_songs"
        Text(
            text = song.title,
            color = if (isPlaying) MaterialTheme.colorScheme.primary else Color.Unspecified,
            fontWeight = if (isPlaying) FontWeight.Bold else FontWeight.Normal
        )
    }, {
        Text(it.artist)
    }, { toPlay ->
        val allSongs = musicViewModel.music.value
        val toPlayIndex = allSongs.indexOfFirst { it.id == toPlay }
        musicViewModel.playSong(allSongs, toPlayIndex, sourceId = "all_songs", sourceName = "All Songs")
        Route.Song
    }, leadingContent = { song ->
        val isPlaying = currentMediaItem?.mediaId == song.id.toString() && currentSource == "all_songs"
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (isPlaying) {
                Icon(
                    painter = painterResource(com.vayunmathur.library.R.drawable.outline_play_arrow_24),
                    contentDescription = "Playing",
                    modifier = Modifier.size(24.dp).padding(end = 8.dp),
                    tint = MaterialTheme.colorScheme.primary
                )
            }
            AlbumArt(song.uri.toUri(), Modifier.size(40.dp))
        }
    }, trailingContent = { song ->
        AddToPlaylistButton(backStack, song)
    }, itemModifier = { song ->
        val isPlaying = currentMediaItem?.mediaId == song.id.toString() && currentSource == "all_songs"
        if (isPlaying) Modifier.clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.secondaryContainer)
        else Modifier
    }, searchEnabled = true, fab = {
        ShufflePlayFab(musicViewModel)
    }, sortOrder = Comparator.comparing { it.title })
}

@Composable
fun ShufflePlayFab(musicViewModel: MusicViewModel) {
    val allSongs by musicViewModel.music.collectAsState()

    if(allSongs.isNotEmpty()) {
        FloatingActionButton({
            musicViewModel.playShuffled(allSongs, sourceId = "all_songs", sourceName = "All Songs")
        }) {
            Icon(painterResource(R.drawable.ic_shuffle), null)
        }
    }
}


@Composable
fun PlayingBottomBar(
    musicViewModel: MusicViewModel,
    backStack: NavBackStack<Route>
) {
    val currentItem by musicViewModel.currentMediaItem.collectAsState()
    val isPlaying by musicViewModel.isPlaying.collectAsState()
    val progress by musicViewModel.currentPosition.collectAsState()
    val duration by musicViewModel.duration.collectAsState()

    val progressFactor = if (duration > 0) progress.toFloat() / duration.toFloat() else 0f

    val item = currentItem ?: return
    val metadata = item.mediaMetadata

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
                content = {
                    Text(
                        text = metadata.title?.toString() ?: stringResource(R.string.unknown_title),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                },
                supportingContent = {
                    Text(
                        text = metadata.artist?.toString() ?: stringResource(R.string.unknown_artist),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                },
                leadingContent = {
                    AlbumArt(metadata.artworkUri!!, Modifier.size(48.dp).clip(RoundedCornerShape(8.dp)))
                },
                trailingContent = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = { musicViewModel.togglePlayPause() }) {
                            if (isPlaying) IconPause() else IconPlay()
                        }
                        IconButton(onClick = { musicViewModel.skipNext() }) {
                            Icon(painterResource(R.drawable.ic_skip_next), contentDescription = null)
                        }
                    }
                }
            )
        }
    }
}
