package com.vayunmathur.music.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.net.toUri
import com.vayunmathur.library.util.NavBackStack
import com.vayunmathur.library.ui.IconNavigation
import com.vayunmathur.library.ui.IconPlay
import com.vayunmathur.music.util.AddToPlaylistButton
import com.vayunmathur.music.util.AlbumArt
import com.vayunmathur.music.util.MusicViewModel
import com.vayunmathur.music.Route
import com.vayunmathur.music.data.Album
import com.vayunmathur.music.data.Artist
import com.vayunmathur.music.data.Music
import com.vayunmathur.music.R
import com.vayunmathur.music.util.formatDuration

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ArtistDetailScreen(backStack: NavBackStack<Route>, musicViewModel: MusicViewModel, artistId: Long) {
    val artistValue by musicViewModel.artistState(artistId)
    val artist = artistValue ?: return
    val allMusic by musicViewModel.music.collectAsState()
    val artistsMusic = remember(allMusic, artistId) {
        allMusic.filter { it.artistId == artistId }
    }
    val artistTotalDurationMs = remember(artistsMusic) {
        artistsMusic.sumOf { it.duration }
    }
    val albumIds by musicViewModel.matchedAlbumsForArtist(artistId)
    val allAlbums by musicViewModel.albums.collectAsState()
    val albums by remember { derivedStateOf {
        albumIds.mapNotNull { id -> allAlbums.find { it.id == id } }
    } }

    val currentMediaItem by musicViewModel.currentMediaItem.collectAsState()
    val currentSource by musicViewModel.currentSource.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { },
                navigationIcon = { IconNavigation(backStack) }
            )
        },
        bottomBar = { PlayingBottomBar(musicViewModel, backStack) },
    ) { paddingValues ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(horizontal = 16.dp)
        ) {
            // Header: Album Art
            item {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    val albumsUris = remember(albums) { albums.map { it.uri.toUri() } }

                    AlbumArt(albumsUris, Modifier
                        .size(260.dp)
                        .clip(RoundedCornerShape(24.dp))
                        .background(Color.DarkGray))

                    Spacer(modifier = Modifier.height(24.dp))

                    ListItem({
                        Text(artist.name, style = MaterialTheme.typography.titleLarge)
                    }, Modifier, {Text(stringResource(R.string.label_artist))}, {
                        Text(stringResource(R.string.artist_info_format, artistsMusic.size, formatDuration(artistTotalDurationMs)))
                    })
                }
            }

            // Action Buttons
            item {
                PlayShuffleRow(
                    onPlay = {
                        musicViewModel.playSong(artistsMusic, 0, sourceId = "artist_$artistId", sourceName = artist.name)
                    },
                    onShuffle = {
                        musicViewModel.playShuffled(artistsMusic, sourceId = "artist_$artistId", sourceName = artist.name)
                    },
                )
            }

            // Track List Header
            item {
                Text(stringResource(R.string.label_albums), fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
            }

            // Track Items
            itemsIndexed(albums) { idx, album ->
                val albumMusic = remember(allMusic, album.id) {
                    allMusic.filter { it.albumId == album.id }
                }

                val albumDurationMs = remember(albumMusic) {
                    albumMusic.sumOf { it.duration }
                }

                val albumYear = remember(albumMusic) {
                    val year = albumMusic.firstOrNull()?.year ?: 0
                    if (year > 0) year.toString() else "Unknown"
                }

                ListItem(
                    content = { Text(album.name) },
                    modifier = Modifier.clickable {
                        backStack.add(Route.AlbumDetail(album.id))
                    },
                    supportingContent = { Text(albumYear) },
                    trailingContent = { Text(formatDuration(albumDurationMs)) },
                    leadingContent = { AlbumArt(album.uri.toUri(), Modifier.size(48.dp)) }
                )
            }

            // Track List Header
            item {
                Text(stringResource(R.string.label_songs), fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
            }

            // Track Items
            itemsIndexed(artistsMusic) { idx, music ->
                val isPlaying = currentMediaItem?.mediaId == music.id.toString() && currentSource == "artist_$artistId"
                TrackListItem(
                    title = music.title,
                    isPlaying = isPlaying,
                    artUri = music.uri.toUri(),
                    onClick = {
                        musicViewModel.playSong(artistsMusic, idx, sourceId = "artist_$artistId", sourceName = artist.name)
                    },
                    leading = if (isPlaying) {
                        {
                            Icon(
                                painter = painterResource(com.vayunmathur.library.R.drawable.outline_play_arrow_24),
                                contentDescription = "Playing",
                                modifier = Modifier.size(24.dp).padding(end = 8.dp),
                                tint = MaterialTheme.colorScheme.primary,
                            )
                        }
                    } else null,
                    trailing = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(formatDuration(music.duration))
                            AddToPlaylistButton(backStack, music)
                        }
                    },
                )
            }
        }
    }
}