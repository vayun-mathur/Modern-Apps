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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.net.toUri
import com.vayunmathur.library.util.NavBackStack
import com.vayunmathur.library.ui.IconNavigation
import com.vayunmathur.library.ui.IconPlay
import com.vayunmathur.library.util.DatabaseViewModel
import com.vayunmathur.music.util.AddToPlaylistButton
import com.vayunmathur.music.util.AlbumArt
import com.vayunmathur.music.util.PlaybackManager
import com.vayunmathur.music.Route
import com.vayunmathur.music.data.Album
import com.vayunmathur.music.data.Artist
import com.vayunmathur.music.data.Music
import com.vayunmathur.music.R
import kotlinx.coroutines.runBlocking

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ArtistDetailScreen(backStack: NavBackStack<Route>, viewModel: DatabaseViewModel, artistId: Long) {
    val artist by viewModel.getState<Artist>(artistId)
    val allMusic by viewModel.data<Music>().collectAsState()
    val artistsMusic = remember(allMusic, artistId) {
        allMusic.filter { it.artistId == artistId }
    }
    val albumIds by viewModel.getMatchesState<Artist, Album>(artistId)
    val allAlbums by viewModel.data<Album>().collectAsState()
    val albums by remember { derivedStateOf {
        albumIds.map { id -> allAlbums.find { it.id == id }!! }
    } }

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
                    val albumIds by viewModel.getMatchesState<Artist, Album>(artist.id)
                    val allAlbums by viewModel.data<Album>().collectAsState()
                    val albumsUris by remember { derivedStateOf { allAlbums.filter { it.id in albumIds }.map { it.uri.toUri() } } }

                    AlbumArt(albumsUris, Modifier
                        .size(260.dp)
                        .clip(RoundedCornerShape(24.dp))
                        .background(Color.DarkGray))

                    Spacer(modifier = Modifier.height(24.dp))

                    ListItem({
                        Text(artist.name, style = MaterialTheme.typography.titleLarge)
                    }, Modifier, {Text(stringResource(R.string.label_artist))}, {
                        Text("${artistsMusic.size} songs • 1:25:02")
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
                            playbackManager.playSong(artistsMusic, 0)
                        },
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(containerColor = Color.White.copy(alpha = 0.15f)),
                        shape = RoundedCornerShape(50.dp),
                        contentPadding = PaddingValues(vertical = 12.dp)
                    ) {
                        IconPlay(tint = Color.White)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(stringResource(R.string.label_play), color = Color.White)
                    }

                    Button(
                        onClick = {
                            playbackManager.playShuffled(artistsMusic)
                        },
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(containerColor = Color.White),
                        shape = RoundedCornerShape(50.dp),
                        contentPadding = PaddingValues(vertical = 12.dp)
                    ) {
                        Icon(painterResource(com.vayunmathur.music.R.drawable.ic_shuffle), contentDescription = null, tint = Color.Black)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(stringResource(R.string.label_shuffle), color = Color.Black)
                    }
                }
            }

            // Track List Header
            item {
                Text(stringResource(R.string.label_albums), fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
            }

            // Track Items
            itemsIndexed(albums) { idx, album ->
                ListItem({
                    Text(album.name)
                }, Modifier.clickable{
                    backStack.add(Route.AlbumDetail(album.id))
                }, {}, {
                    Text("3:02")
                }, leadingContent = {
                    AlbumArt(album.uri.toUri(), Modifier.size(48.dp))
                })
            }

            // Track List Header
            item {
                Text(stringResource(R.string.label_songs), fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
            }

            // Track Items
            itemsIndexed(artistsMusic) { idx, music ->
                ListItem({
                    Text(music.title)
                }, Modifier.clickable{
                    playbackManager.playSong(artistsMusic, idx)
                }, trailingContent = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("3:02")
                        AddToPlaylistButton(backStack, music)
                    }
                }, leadingContent = {
                    AlbumArt(music.uri.toUri(), Modifier.size(48.dp))
                })
            }
        }
    }
}