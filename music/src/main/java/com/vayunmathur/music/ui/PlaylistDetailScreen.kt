package com.vayunmathur.music.ui

import androidx.compose.ui.res.pluralStringResource
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.net.toUri
import com.vayunmathur.library.ui.AlertDialog
import com.vayunmathur.library.ui.ConfirmDialog
import com.vayunmathur.library.ui.DetailLazyColumn
import com.vayunmathur.library.ui.ExperimentalMaterial3Api
import com.vayunmathur.library.ui.IconButton
import com.vayunmathur.library.ui.IconClose
import com.vayunmathur.library.ui.IconDelete
import com.vayunmathur.library.ui.IconLibraryMusic
import com.vayunmathur.library.ui.IconPlay
import com.vayunmathur.library.ui.ListItem
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.R as UiR
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.ui.TextButton
import com.vayunmathur.library.ui.TextField
import com.vayunmathur.library.ui.appBarScrollBehavior
import com.vayunmathur.library.util.NavBackStack
import com.vayunmathur.library.util.sharedContainer
import com.vayunmathur.library.util.sharedText
import com.vayunmathur.music.ui.components.PlayShuffleRow
import com.vayunmathur.music.ui.components.PlayingBottomBar
import com.vayunmathur.music.ui.components.TrackListItem
import com.vayunmathur.music.platform.AlbumArt
import com.vayunmathur.music.platform.MusicViewModel
import com.vayunmathur.music.R
import com.vayunmathur.music.Route
import com.vayunmathur.music.data.Playlist

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlaylistDetailScreen(backStack: NavBackStack<Route>, musicViewModel: MusicViewModel, playlistId: Long) {
    val playlist by musicViewModel.playlistState(playlistId)

    if (playlist == null) {
        return
    }

    val allMusic by musicViewModel.music.collectAsState()
    val matchedIds by musicViewModel.matchedMusicForPlaylist(playlistId)
    val musicInPlaylist = remember(allMusic, matchedIds) {
        val idSet = matchedIds.toSet()
        allMusic.filter { it.id in idSet }
    }

    val currentMediaItem by musicViewModel.currentMediaItem.collectAsState()
    val currentSource by musicViewModel.currentSource.collectAsState()

    DetailLazyColumn(
        title = {},
        onNavigateBack = { backStack.pop() },
        actions = {
                    var showDeleteDialog by remember { mutableStateOf(false) }
                    IconButton(onClick = { showDeleteDialog = true }) {
                        com.vayunmathur.library.ui.IconDelete()
                    }
                    if (showDeleteDialog) {
                        ConfirmDialog(
                            title = stringResource(R.string.dialog_delete_playlist),
                            message = stringResource(R.string.dialog_delete_playlist_confirm, playlist!!.name),
                            confirmLabel = stringResource(UiR.string.delete),
                            dismissLabel = stringResource(UiR.string.cancel),
                            onConfirm = { val toDelete = playlist!!
                                    backStack.pop()
                                    musicViewModel.deletePlaylist(toDelete) },
                            onDismiss = { showDeleteDialog = false },
                            destructive = true,
                        )
                    }
        },
        bottomBar = { PlayingBottomBar(musicViewModel, backStack, Modifier.navigationBarsPadding()) },
        scrollBehavior = appBarScrollBehavior(),
    ) {
            item {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Box(Modifier
                        .size(260.dp)
                        .clip(RoundedCornerShape(24.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .sharedContainer("music-playlist-art-$playlistId"),
                        contentAlignment = Alignment.Center
                    ) {
                        if (musicInPlaylist.isEmpty()) {
                            IconLibraryMusic(Modifier.size(100.dp))
                        } else {
                            AlbumArt(musicInPlaylist.map { it.uri.toUri() }, Modifier.fillMaxSize())
                        }
                    }

                    Spacer(modifier = Modifier.height(24.dp))

                    ListItem({
                        var showRenameDialog by remember { mutableStateOf(false) }
                        var newName by remember(playlist!!.name) { mutableStateOf(playlist!!.name) }
                        Text(playlist!!.name, style = MaterialTheme.typography.titleLarge, modifier = Modifier.clickable {
                            showRenameDialog = true
                        }.sharedText("music-playlist-name-$playlistId"))

                        if (showRenameDialog) {
                            AlertDialog(
                                onDismissRequest = { showRenameDialog = false },
                                title = { Text(stringResource(R.string.dialog_rename_playlist)) },
                                text = {
                                    TextField(value = newName, onValueChange = { newName = it })
                                },
                                confirmButton = {
                                    TextButton(
                                        enabled = newName.isNotBlank(),
                                        onClick = {
                                            musicViewModel.renamePlaylist(playlist!!, newName.trim())
                                            showRenameDialog = false
                                        }
                                    ) {
                                        Text(stringResource(UiR.string.rename))
                                    }
                                },
                                dismissButton = {
                                    TextButton(onClick = { showRenameDialog = false }) {
                                        Text(stringResource(UiR.string.cancel))
                                    }
                                }
                            )
                        }
                    }, Modifier, {Text(stringResource(R.string.label_playlist))}, {
                        Text(pluralStringResource(R.plurals.num_songs_format, musicInPlaylist.size, musicInPlaylist.size))
                    })
                }
            }

            // Action Buttons
            item {
                PlayShuffleRow(
                    onPlay = {
                        musicViewModel.playSong(musicInPlaylist, 0, sourceId = "playlist_$playlistId", sourceName = playlist!!.name)
                    },
                    onShuffle = {
                        musicViewModel.playShuffled(musicInPlaylist, sourceId = "playlist_$playlistId", sourceName = playlist!!.name)
                    },
                )
            }

            // Track List
            itemsIndexed(musicInPlaylist) { idx, music ->
                val isPlaying = currentMediaItem?.mediaId == music.id.toString() && currentSource == "playlist_$playlistId"
                TrackListItem(
                    title = music.title,
                    isPlaying = isPlaying,
                    artUri = music.uri.toUri(),
                    onClick = {
                        musicViewModel.playSong(musicInPlaylist, idx, sourceId = "playlist_$playlistId", sourceName = playlist!!.name)
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
                        IconButton({
                            musicViewModel.removeMusicFromPlaylist(playlistId, music.id)
                        }) {
                            IconClose()
                        }
                    },
                )
            }
    }
}
