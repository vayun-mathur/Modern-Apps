package com.vayunmathur.music.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.net.toUri
import com.vayunmathur.library.ui.IconClose
import com.vayunmathur.library.ui.IconNavigation
import com.vayunmathur.library.ui.IconPlay
import com.vayunmathur.library.util.DatabaseViewModel
import com.vayunmathur.library.util.NavBackStack
import com.vayunmathur.music.AlbumArt
import com.vayunmathur.music.PlaybackManager
import com.vayunmathur.music.Route
import com.vayunmathur.music.database.Music
import com.vayunmathur.music.database.Playlist
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlaylistDetailScreen(backStack: NavBackStack<Route>, viewModel: DatabaseViewModel, playlistId: Long) {
    val playlist by viewModel.getState<Playlist>(playlistId)
    val allMusic by viewModel.data<Music>().collectAsState()
    var musicInPlaylist by remember { mutableStateOf(emptyList<Music>()) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(allMusic, playlistId) {
        val musicIds = viewModel.getMatches<Playlist, Music>(playlistId)
        musicInPlaylist = allMusic.filter { musicIds.contains(it.id) }
    }

    val context = LocalContext.current
    val playbackManager = remember { PlaybackManager.getInstance(context) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { },
                navigationIcon = { IconNavigation(backStack) }
            )
        },
        bottomBar = { PlayingBottomBar(playbackManager, backStack) },
    ) { paddingValues ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(horizontal = 16.dp)
        ) {
            item {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Box(Modifier
                        .size(260.dp)
                        .clip(RoundedCornerShape(24.dp))
                        .background(Color.DarkGray),
                        contentAlignment = Alignment.Center
                    ) {
                        if (musicInPlaylist.isEmpty()) {
                            Icon(painterResource(com.vayunmathur.music.R.drawable.baseline_library_music_24), null, Modifier.size(100.dp))
                        } else {
                            AlbumArt(musicInPlaylist.map { it.uri.toUri() }, Modifier.fillMaxSize())
                        }
                    }

                    Spacer(modifier = Modifier.height(24.dp))

                    ListItem({
                        var showRenameDialog by remember { mutableStateOf(false) }
                        var newName by remember(playlist.name) { mutableStateOf(playlist.name) }
                        Text(playlist.name, style = MaterialTheme.typography.titleLarge, modifier = Modifier.clickable {
                            showRenameDialog = true
                        })

                        if (showRenameDialog) {
                            AlertDialog(
                                onDismissRequest = { showRenameDialog = false },
                                title = { Text("Rename Playlist") },
                                text = {
                                    TextField(value = newName, onValueChange = { newName = it })
                                },
                                confirmButton = {
                                    TextButton(onClick = {
                                        scope.launch {
                                            viewModel.upsert(playlist.copy(name = newName))
                                            showRenameDialog = false
                                        }
                                    }) {
                                        Text("Rename")
                                    }
                                },
                                dismissButton = {
                                    TextButton(onClick = { showRenameDialog = false }) {
                                        Text("Cancel")
                                    }
                                }
                            )
                        }
                    }, Modifier, {Text("Playlist")}, {
                        Text("${musicInPlaylist.size} songs")
                    })
                }
            }

            // Action Buttons
            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 24.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Button(
                        onClick = {
                            playbackManager.playSong(musicInPlaylist, 0)
                        },
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(containerColor = Color.White.copy(alpha = 0.15f)),
                        shape = RoundedCornerShape(50.dp),
                        contentPadding = PaddingValues(vertical = 12.dp)
                    ) {
                        IconPlay(tint = Color.White)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Play", color = Color.White)
                    }

                    Button(
                        onClick = {
                            playbackManager.playShuffled(musicInPlaylist)
                        },
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(containerColor = Color.White),
                        shape = RoundedCornerShape(50.dp),
                        contentPadding = PaddingValues(vertical = 12.dp)
                    ) {
                        Icon(painterResource(com.vayunmathur.music.R.drawable.ic_shuffle), contentDescription = null, tint = Color.Black)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Shuffle", color = Color.Black)
                    }
                }
            }

            // Track List
            itemsIndexed(musicInPlaylist) { idx, music ->
                ListItem({
                    Text(music.title)
                }, Modifier.clickable{
                    playbackManager.playSong(musicInPlaylist, idx)
                }, {}, {
                }, trailingContent = {
                    IconButton({
                        scope.launch {
                            viewModel.unmatch<Playlist, Music>(playlistId, music.id)
                            val musicIds = viewModel.getMatches<Playlist, Music>(playlistId)
                            musicInPlaylist = allMusic.filter { musicIds.contains(it.id) }
                        }
                    }) {
                        IconClose()
                    }
                }, leadingContent = {
                    AlbumArt(music.uri.toUri(), Modifier.size(48.dp))
                })
            }
        }
    }
}
