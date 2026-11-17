package com.vayunmathur.music.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import com.vayunmathur.library.ui.DetailLazyColumn
import com.vayunmathur.library.ui.ExperimentalMaterial3Api
import com.vayunmathur.library.ui.ListItem
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.net.toUri
import com.vayunmathur.library.util.NavBackStack
import com.vayunmathur.library.util.sharedContainer
import com.vayunmathur.library.util.sharedText
import com.vayunmathur.library.ui.IconPlay
import com.vayunmathur.library.ui.appBarScrollBehavior
import com.vayunmathur.music.ui.components.PlayShuffleRow
import com.vayunmathur.music.ui.components.PlayingBottomBar
import com.vayunmathur.music.ui.components.TrackListItem
import com.vayunmathur.music.platform.AlbumArt
import com.vayunmathur.music.ui.components.AddToPlaylistButton
import com.vayunmathur.music.platform.MusicViewModel
import com.vayunmathur.music.Route
import com.vayunmathur.music.data.Album
import com.vayunmathur.music.data.Artist
import com.vayunmathur.music.data.Music
import com.vayunmathur.music.R
import com.vayunmathur.music.platform.formatDuration

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

    DetailLazyColumn(
        title = {},
        onNavigateBack = { backStack.pop() },
        bottomBar = { PlayingBottomBar(musicViewModel, backStack, Modifier.navigationBarsPadding()) },
        scrollBehavior = appBarScrollBehavior(),
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
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .sharedContainer("music-artist-art-$artistId"))

                    Spacer(modifier = Modifier.height(24.dp))

                    ListItem({
                        Text(
                            artist.name.ifBlank { stringResource(R.string.unknown_artist) },
                            style = MaterialTheme.typography.titleLarge,
                            modifier = Modifier.sharedText("music-artist-name-$artistId"),
                        )
                    }, Modifier, {Text(stringResource(R.string.label_artist))}, {
                        Text(stringResource(R.string.artist_info_format, pluralStringResource(R.plurals.num_songs_format, artistsMusic.size, artistsMusic.size), formatDuration(artistTotalDurationMs)))
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
                    content = { Text(album.name, modifier = Modifier.sharedText("music-album-title-${album.id}")) },
                    modifier = Modifier.clickable {
                        // Another album off the same artist replaces the detail instead of
                        // stacking AlbumDetail on AlbumDetail.
                        val route = Route.AlbumDetail(album.id)
                        if (backStack.last() is Route.AlbumDetail) backStack.setLast(route) else backStack.add(route)
                    },
                    supportingContent = { Text(albumYear) },
                    trailingContent = { Text(formatDuration(albumDurationMs)) },
                    leadingContent = {
                        AlbumArt(
                            album.uri.toUri(),
                            Modifier.size(48.dp).clip(RoundedCornerShape(8.dp)).sharedContainer("music-album-art-${album.id}"),
                        )
                    }
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
                            IconPlay(
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
