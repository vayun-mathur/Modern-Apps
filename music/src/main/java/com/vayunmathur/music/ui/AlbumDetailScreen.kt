package com.vayunmathur.music.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.navigation3.runtime.NavBackStack
import com.vayunmathur.library.util.DatabaseViewModel
import com.vayunmathur.music.Route
import com.vayunmathur.music.database.Album
import com.vayunmathur.music.database.Music
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.net.toUri
import coil.compose.AsyncImage
import com.vayunmathur.library.ui.IconNavigation
import com.vayunmathur.library.ui.IconPlay
import com.vayunmathur.music.AlbumArt
import com.vayunmathur.music.PlaybackManager

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AlbumDetailScreen(backStack: NavBackStack<Route>, viewModel: DatabaseViewModel, albumId: Long) {
    val album by viewModel.getState<Album>(albumId)
    val allMusic by viewModel.data<Music>().collectAsState()
    val musicInAlbum = remember(allMusic, albumId) {
        allMusic.filter { it.albumId == albumId }
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
            // Header: Album Art
            item {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    AlbumArt(album.uri.toUri(), Modifier
                        .size(260.dp)
                        .clip(RoundedCornerShape(24.dp))
                        .background(Color.DarkGray))

                    Spacer(modifier = Modifier.height(24.dp))

                    ListItem({
                        Text(album.name, style = MaterialTheme.typography.titleLarge)
                    }, Modifier, {Text("Album")}, {
                        Text("${album.artistString(viewModel)}\nJan 2016 • ${musicInAlbum.size} songs • 1:25:02")
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
                            playbackManager.playSong(musicInAlbum, 0)
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
                            playbackManager.playShuffled(musicInAlbum)
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

            // Track List Header
            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = "Songs",
                        color = Color.White,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }

            // Track Items
            itemsIndexed(musicInAlbum) { idx, music ->
                ListItem({
                    Text(music.title)
                }, Modifier.clickable{
                    playbackManager.playSong(musicInAlbum, idx)
                }, {}, {
                    Text("3:02")
                }, leadingContent = {
                    AlbumArt(music.uri.toUri(), Modifier.size(48.dp))
                })
            }
        }
    }
}