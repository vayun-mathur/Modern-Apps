package com.vayunmathur.music.util
import android.content.ContentUris
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import android.net.Uri
import android.provider.MediaStore
import android.util.Size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import coil.compose.AsyncImage
import com.vayunmathur.library.util.DatabaseViewModel
import com.vayunmathur.library.util.NavBackStack
import com.vayunmathur.music.data.Album
import com.vayunmathur.music.data.Artist
import com.vayunmathur.music.data.Music
import com.vayunmathur.music.data.Playlist
import com.vayunmathur.music.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.core.graphics.createBitmap
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.ui.res.painterResource

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

fun albumArtistPairs(music: List<Music>, artists: List<Artist>, albums: List<Album>): List<Pair<Album, Artist>> {
    val albumArtistPairs = mutableListOf<Pair<Album, Artist>>()
    for(music in music) {
        val album = albums.firstOrNull { it.id == music.albumId } ?: continue
        val artist = artists.firstOrNull { it.id == music.artistId } ?: continue
        albumArtistPairs += Pair(album, artist)
    }
    return albumArtistPairs.distinct()
}

suspend fun getAlbums(context: Context): List<Album> = withContext(Dispatchers.IO) {
    val musicList = mutableListOf<Album>()
    val projection = arrayOf(
        MediaStore.Audio.Albums._ID,
        MediaStore.Audio.Albums.ALBUM,
        MediaStore.Audio.Albums.ARTIST,
        MediaStore.Audio.Albums.ARTIST_ID,
    )

    // Filter to only get music files
    val sortOrder = "${MediaStore.Audio.Albums.ALBUM} ASC"

    context.contentResolver.query(
        MediaStore.Audio.Albums.EXTERNAL_CONTENT_URI,
        projection,
        null,
        null,
        sortOrder
    )?.use { cursor ->
        val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Albums._ID)
        val titleColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Albums.ALBUM)

        while (cursor.moveToNext()) {
            val id = cursor.getLong(idColumn)
            val title = cursor.getString(titleColumn)

            // Construct the actual File URI
            val contentUri = ContentUris.withAppendedId(
                MediaStore.Audio.Albums.EXTERNAL_CONTENT_URI,
                id
            ).toString()

            musicList.add(Album(id, title, contentUri))
        }
    }
    return@withContext musicList
}


suspend fun getArtists(context: Context): List<Artist> = withContext(Dispatchers.IO) {
    val musicList = mutableListOf<Artist>()
    val projection = arrayOf(
        MediaStore.Audio.Artists._ID,
        MediaStore.Audio.Artists.ARTIST,
    )

    // Filter to only get music files
    val sortOrder = "${MediaStore.Audio.Artists.ARTIST} ASC"

    context.contentResolver.query(
        MediaStore.Audio.Artists.EXTERNAL_CONTENT_URI,
        projection,
        null,
        null,
        sortOrder
    )?.use { cursor ->
        val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Artists._ID)
        val titleColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Artists.ARTIST)

        while (cursor.moveToNext()) {
            val id = cursor.getLong(idColumn)
            val title = cursor.getString(titleColumn)

            // Construct the actual File URI
            val contentUri = ContentUris.withAppendedId(
                MediaStore.Audio.Artists.EXTERNAL_CONTENT_URI,
                id
            ).toString()

            musicList.add(Artist(id, title, contentUri))
        }
    }
    return@withContext musicList
}

@Composable
fun AlbumArt(artUri: Uri, modifier: Modifier) {
    val context = LocalContext.current
    var bitmap: Bitmap? by remember { mutableStateOf(null) }
    LaunchedEffect(artUri) {
        bitmap = getThumbnail(context, artUri)
    }
    AsyncImage(
        bitmap,
        contentDescription = "Album Art",
        modifier = modifier
    )
}
@Composable
fun AlbumArt(artUris: List<Uri>, modifier: Modifier) {
    val context = LocalContext.current
    var bitmap: Bitmap? by remember { mutableStateOf(null) }

    // Re-run whenever the list of URIs changes
    LaunchedEffect(artUris) {
        withContext(Dispatchers.IO) {
            bitmap = if (artUris.size > 1) {
                createCollageBitmap(context, artUris.take(4))
            } else {
                // Fallback for single image
                artUris.firstOrNull()?.let { getThumbnail(context, it) }
            }
        }
    }

    AsyncImage(
        model = bitmap,
        contentDescription = "Album Art Grid",
        modifier = modifier
    )
}

/**
 * Creates a 2x2 grid bitmap from a list of Uris
 */
fun createCollageBitmap(context: Context, uris: List<Uri>): Bitmap {
    val size = 512 // Define a standard size for the output square
    val halfSize = size / 2
    val result = createBitmap(size, size)
    val canvas = Canvas(result)

    uris.forEachIndexed { index, uri ->
        val thumb = getThumbnail(context, uri) ?: return@forEachIndexed

        // Calculate grid position
        val left = (index % 2) * halfSize
        val top = (index / 2) * halfSize

        val rect = Rect(left, top, left + halfSize, top + halfSize)
        canvas.drawBitmap(thumb, null, rect, null)
    }

    return result
}

@Composable
fun AddToPlaylistButton(backStack: NavBackStack<Route>, music: Music) {
    IconButton(onClick = { backStack.add(Route.AddToPlaylistDialog(music.id)) }) {
        Icon(painterResource(R.drawable.ic_more_vert), contentDescription = "Add to playlist")
    }
}
