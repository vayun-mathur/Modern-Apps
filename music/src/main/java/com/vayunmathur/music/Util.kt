package com.vayunmathur.music

import android.content.ContentUris
import android.content.Context
import android.graphics.Bitmap
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
import com.vayunmathur.music.database.Album
import com.vayunmathur.music.database.Artist
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

suspend fun saveMediaToFile(context: Context, viewModel: DatabaseViewModel) {
    val musics = getMedia(context)
    val albums = getAlbums(context)
    val artists = getArtists(context)
    viewModel.replaceAll(musics)
    viewModel.replaceAll(albums)
    viewModel.replaceAll(artists)
    viewModel.clearMatchings()
    viewModel.addPairs(albumArtistPairs(musics, artists, albums))
}

fun albumArtistPairs(music: List<Music>, artists: List<Artist>, albums: List<Album>): List<Pair<Album, Artist>> {
    val albumArtistPairs = mutableListOf<Pair<Album, Artist>>()
    for(music in music) {
        albumArtistPairs += Pair(albums.first { it.id == music.albumId }, artists.first { it.id == music.artistId })
    }
    return albumArtistPairs.distinct()
}

suspend fun getMedia(context: Context): List<Music> = withContext(Dispatchers.IO) {
    val musicList = mutableListOf<Music>()
    val projection = arrayOf(
        MediaStore.Audio.Media._ID,
        MediaStore.Audio.Media.TITLE,
        MediaStore.Audio.Media.ARTIST,
        MediaStore.Audio.Media.ARTIST_ID,
        MediaStore.Audio.Media.ALBUM,
        MediaStore.Audio.Media.ALBUM_ID,
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
        val artistIDColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST_ID)
        val albumIDColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM_ID)

        while (cursor.moveToNext()) {
            val id = cursor.getLong(idColumn)
            val title = cursor.getString(titleColumn)
            val artist = cursor.getString(artistColumn)
            val album = cursor.getString(albumColumn)
            val artistID = cursor.getLong(artistIDColumn)
            val albumID = cursor.getLong(albumIDColumn)

            // Construct the actual File URI
            val contentUri = ContentUris.withAppendedId(
                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                id
            ).toString()

            musicList.add(Music(id, title, artist, artistID, album, albumID, contentUri))
        }
    }
    return@withContext musicList
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