package com.vayunmathur.music

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import androidx.sqlite.db.SimpleSQLiteQuery
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.ListenableWorker.Result as WorkResult
import com.vayunmathur.library.util.DatabaseViewModel
import com.vayunmathur.library.util.buildDatabase
import com.vayunmathur.library.util.getAll
import com.vayunmathur.music.database.Album
import com.vayunmathur.music.database.Artist
import com.vayunmathur.music.database.Music
import com.vayunmathur.music.database.MusicDatabase
import com.vayunmathur.music.database.Playlist
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

class SyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): WorkResult = withContext(Dispatchers.IO) {
        val database = applicationContext.buildDatabase<MusicDatabase>(listOf(MIGRATION_1_2))
        val viewModel = DatabaseViewModel(
            database,
            Music::class to database.musicDao(),
            Album::class to database.albumDao(),
            Artist::class to database.artistDao(),
            Playlist::class to database.playlistDao(),
            matchingDao = database.matchingDao()
        )
        
        val triggeredUris = triggeredContentUris
        if (triggeredUris.isNotEmpty()) {
            syncMusic(applicationContext, database, viewModel, triggeredUris.toList())
        } else {
            syncMusic(applicationContext, database, viewModel)
        }
        
        // Enqueue next observation
        enqueue(applicationContext)
        
        WorkResult.success()
    }

    companion object {
        private const val WORK_NAME = "MusicSyncWorker"

        fun enqueue(context: Context) {
            val constraints = Constraints.Builder()
                .addContentUriTrigger(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, true)
                .addContentUriTrigger(MediaStore.Audio.Albums.EXTERNAL_CONTENT_URI, true)
                .addContentUriTrigger(MediaStore.Audio.Artists.EXTERNAL_CONTENT_URI, true)
                .setTriggerContentUpdateDelay(1, TimeUnit.SECONDS)
                .setTriggerContentMaxDelay(5, TimeUnit.SECONDS)
                .build()

            val request = OneTimeWorkRequestBuilder<SyncWorker>()
                .setConstraints(constraints)
                .build()

            WorkManager.getInstance(context).enqueueUniqueWork(
                WORK_NAME,
                ExistingWorkPolicy.REPLACE,
                request
            )
        }

        fun runOnce(context: Context) {
            val request = OneTimeWorkRequestBuilder<SyncWorker>().build()
            WorkManager.getInstance(context).enqueue(request)
        }
    }
}

suspend fun syncMusic(context: Context, database: MusicDatabase, viewModel: DatabaseViewModel, uris: List<Uri>? = null) {
    val musicDao = database.musicDao()

    val musicList = mutableListOf<Music>()
    val projection = arrayOf(
        MediaStore.Audio.Media._ID,
        MediaStore.Audio.Media.TITLE,
        MediaStore.Audio.Media.ARTIST,
        MediaStore.Audio.Media.ARTIST_ID,
        MediaStore.Audio.Media.ALBUM,
        MediaStore.Audio.Media.ALBUM_ID,
    )

    val selection = if (uris != null) {
        val ids = uris.mapNotNull { runCatching { ContentUris.parseId(it) }.getOrNull() }
        if (ids.isEmpty()) "${MediaStore.Audio.Media.IS_MUSIC} != 0"
        else "${MediaStore.Audio.Media.IS_MUSIC} != 0 AND ${MediaStore.Audio.Media._ID} IN (${ids.joinToString(",")})"
    } else "${MediaStore.Audio.Media.IS_MUSIC} != 0"

    context.contentResolver.query(
        MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
        projection,
        selection,
        null,
        null
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
            val contentUri = ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id).toString()

            musicList.add(Music(id, title, artist, artistID, album, albumID, contentUri))
        }
    }

    if (uris == null) {
        viewModel.replaceAll(musicList)
    } else {
        musicDao.upsertAll(musicList)
        // Handle deletions for triggered IDs not found in MediaStore
        val triggeredIds = uris.mapNotNull { runCatching { ContentUris.parseId(it) }.getOrNull() }.toSet()
        val foundIds = musicList.map { it.id }.toSet()
        val deletedIds = triggeredIds - foundIds
        if (deletedIds.isNotEmpty()) {
            musicDao.observeNothing(SimpleSQLiteQuery("DELETE FROM Music WHERE id IN (${deletedIds.joinToString(",")})"))
        }
    }

    // Always refresh albums and artists for now to keep it simple and consistent
    val allMusic = musicDao.getAll<Music>()
    val albums = getAlbums(context)
    val artists = getArtists(context)
    
    viewModel.replaceAll(albums)
    viewModel.replaceAll(artists)
    viewModel.clearMatchings<Album, Artist>()
    viewModel.addPairs(albumArtistPairs(allMusic, artists, albums))
}
