package com.vayunmathur.music.ui

import android.content.ContentUris
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.provider.MediaStore
import android.util.Size
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.BottomAppBar
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.navigation3.runtime.NavBackStack
import coil.compose.AsyncImage
import com.vayunmathur.library.ui.ListPage
import com.vayunmathur.library.ui.invisibleClickable
import com.vayunmathur.library.util.DataStoreUtils
import com.vayunmathur.library.util.DatabaseViewModel
import com.vayunmathur.music.PlaybackManager
import com.vayunmathur.music.R
import com.vayunmathur.music.Route
import com.vayunmathur.music.database.Music
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

fun getThumbnail(context: Context, uri: Uri): Bitmap? {
    return try {
        context.contentResolver.loadThumbnail(
            uri,
            Size(300, 300),
            null
        )
    } catch (e: Exception) {
        null // Fallback to a placeholder
    }
}

@Composable
fun HomeScreen(backStack: NavBackStack<Route>, viewModel: DatabaseViewModel) {
    val context = LocalContext.current
    val playbackManager = remember { PlaybackManager.getInstance(context) }

    ListPage<Music, Route, Route.Song>(backStack, viewModel, "Music", { Text(it.title) }, {
        Text(it.artist)
    }, {toPlay ->
        val allSongs = viewModel.getAll<Music>()
        val toPlayIndex = allSongs.indexOfFirst { it.id == toPlay }
        playbackManager.playSong(allSongs, toPlayIndex)
        Route.Song
       }, leadingContent = { music ->
           AlbumArt(music.uri.toUri(), Modifier.size(40.dp))
    }, searchEnabled = true, bottomBar = {
        PlayingBottomBar(playbackManager, backStack)
    })
}

@Composable
fun AlbumArt(artUri: Uri, modifier: Modifier) {
    val context = LocalContext.current
    var bitmap: Bitmap? by remember { mutableStateOf(null) }
    LaunchedEffect(Unit) {
        bitmap = getThumbnail(context, artUri)
    }
    AsyncImage(
        bitmap,
        contentDescription = "Album Art",
        modifier = modifier
    )
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
                                Icon(
                                    if (isPlaying) painterResource(R.drawable.ic_pause) else painterResource(R.drawable.ic_play_arrow),
                                    contentDescription = null
                                )
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

suspend fun saveMediaToFile(context: Context, viewModel: DatabaseViewModel) {
    viewModel.replaceAll(getMedia(context))
}

suspend fun getMedia(context: Context): List<Music> = withContext(Dispatchers.IO) {
    val musicList = mutableListOf<Music>()
    val projection = arrayOf(
        MediaStore.Audio.Media._ID,
        MediaStore.Audio.Media.TITLE,
        MediaStore.Audio.Media.ARTIST,
        MediaStore.Audio.Media.ALBUM,
        MediaStore.Audio.Media.ALBUM_ID
    )

    // Filter to only get music files
    val selection = "${MediaStore.Audio.Media.IS_MUSIC} != 0"
    val sortOrder = "${MediaStore.Audio.Media.TITLE} ASC"

    context.contentResolver.query(
        MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
        projection,
        selection,
        null,
        sortOrder
    )?.use { cursor ->
        val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
        val titleColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
        val artistColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
        val albumColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM)

        while (cursor.moveToNext()) {
            val id = cursor.getLong(idColumn)
            val title = cursor.getString(titleColumn)
            val artist = cursor.getString(artistColumn)
            val album = cursor.getString(albumColumn)

            // Construct the actual File URI
            val contentUri = ContentUris.withAppendedId(
                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                id
            ).toString()

            musicList.add(
                Music(
                    id = id,
                    title = title,
                    artist = artist,
                    album = album,
                    uri = contentUri,
                )
            )
        }
    }
    return@withContext musicList
}