package com.vayunmathur.music.ui

import android.content.ContentUris
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.provider.MediaStore
import android.util.Size
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.navigation3.runtime.NavBackStack
import coil.compose.AsyncImage
import com.vayunmathur.library.ui.ListPage
import com.vayunmathur.library.util.DatabaseViewModel
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
    ListPage<Music, Route, Route.Song>(backStack, viewModel, "Music", { Text(it.title) }, {
        Text(it.artist)
    }, { Route.Song(it) }, leadingContent = { music ->
        var bitmap: Bitmap? by remember { mutableStateOf(null) }
        LaunchedEffect(Unit) {
            bitmap = getThumbnail(context, music.uri.toUri())
        }
        AsyncImage(
             bitmap,
            contentDescription = "Album Art",
            modifier = Modifier.size(40.dp)
        )
    })
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