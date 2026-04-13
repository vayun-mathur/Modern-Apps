package com.vayunmathur.photos.util
import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import androidx.core.database.getLongOrNull
import androidx.core.net.toUri
import androidx.exifinterface.media.ExifInterface
import androidx.sqlite.db.SimpleSQLiteQuery
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.ListenableWorker.Result as WorkResult
import com.vayunmathur.library.util.DataStoreUtils
import com.vayunmathur.library.util.buildDatabase
import com.vayunmathur.library.util.getAll
import com.vayunmathur.photos.data.MIGRATION_1_2
import com.vayunmathur.photos.data.MIGRATION_2_3
import com.vayunmathur.photos.data.Photo
import com.vayunmathur.photos.data.PhotoDatabase
import com.vayunmathur.photos.data.VideoData
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

class SyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): WorkResult = withContext(Dispatchers.IO) {
        val database = applicationContext.buildDatabase<PhotoDatabase>(listOf(MIGRATION_1_2, MIGRATION_2_3))
        val dataStore = DataStoreUtils.getInstance(applicationContext)
        
        val triggeredUris = triggeredContentUris
        val lastGeneration = dataStore.getLong("last_photos_generation") ?: 0L
        val currentGeneration = MediaStore.getGeneration(applicationContext, MediaStore.VOLUME_EXTERNAL)

        if (triggeredUris.isNotEmpty()) {
            syncPhotos(applicationContext, database, triggeredUris.toList())
        } else {
            syncPhotos(applicationContext, database, null, lastGeneration)
        }
        
        val photos = database.photoDao().getAll<Photo>()
        setExifData(photos, database, applicationContext)
        
        dataStore.setLong("last_photos_generation", currentGeneration)
        
        // Enqueue next observation
        enqueue(applicationContext)
        
        WorkResult.success()
    }

    companion object {
        private const val WORK_NAME = "SyncWorker"

        fun enqueue(context: Context) {
            val constraints = Constraints.Builder()
                .addContentUriTrigger(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, true)
                .addContentUriTrigger(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, true)
                .setTriggerContentUpdateDelay(500, TimeUnit.MILLISECONDS)
                .setTriggerContentMaxDelay(2, TimeUnit.SECONDS)
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

suspend fun syncPhotos(context: Context, database: PhotoDatabase, uris: List<Uri>? = null, lastGeneration: Long = 0L) {
    val photoDao = database.photoDao()
    
    // 1. Get all IDs currently in MediaStore to detect deletions
    val allMediaStoreIds = mutableSetOf<Long>()
    fun collectIds(baseUri: Uri) {
        context.contentResolver.query(baseUri, arrayOf(MediaStore.MediaColumns._ID), null, null, null)?.use { cursor ->
            val idCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
            while (cursor.moveToNext()) allMediaStoreIds.add(cursor.getLong(idCol))
        }
    }
    collectIds(MediaStore.Images.Media.EXTERNAL_CONTENT_URI)
    collectIds(MediaStore.Video.Media.EXTERNAL_CONTENT_URI)

    // 2. Handle deletions
    val toDelete = if (uris != null) {
        // Triggered sync: only delete triggered items that no longer exist
        val triggeredIds = uris.mapNotNull { runCatching { ContentUris.parseId(it) }.getOrNull() }.toSet()
        triggeredIds - allMediaStoreIds
    } else {
        // Full or incremental sync: delete everything local not in MediaStore
        val localIds = photoDao.getAll<Photo>().map { it.id }.toSet()
        localIds - allMediaStoreIds
    }

    if (toDelete.isNotEmpty()) {
        toDelete.chunked(900).forEach { chunk ->
            photoDao.observeNothing(SimpleSQLiteQuery("DELETE FROM Photo WHERE id IN (${chunk.joinToString(",")})"))
        }
    }

    // 3. Process additions/updates
    val selection = when {
        uris != null -> {
            val ids = uris.mapNotNull { runCatching { ContentUris.parseId(it) }.getOrNull() }
            if (ids.isEmpty()) null else "_id IN (${ids.joinToString(",")})"
        }
        lastGeneration > 0 -> {
            "${MediaStore.MediaColumns.GENERATION_MODIFIED} > $lastGeneration"
        }
        else -> null
    }

    // Pre-fetch local data for comparison to avoid redundant updates
    val existingPhotos = if (selection != null) {
        if (uris != null) {
            val ids = uris.mapNotNull { runCatching { ContentUris.parseId(it) }.getOrNull() }
            photoDao.getAll<Photo>("id IN (${ids.joinToString(",")})").associateBy { it.id }
        } else {
            // For generation-based sync, we don't know exactly which IDs changed, 
            // so we might need all local photos for comparison if we want to be surgical.
            // For simplicity and correctness, fetch all.
            photoDao.getAll<Photo>().associateBy { it.id }
        }
    } else {
        // Full sync: we'll check against all local data
        photoDao.getAll<Photo>().associateBy { it.id }
    }

    val newOrUpdatedPhotos = mutableListOf<Photo>()

    fun processCursor(cursor: android.database.Cursor, isVideo: Boolean) {
        val idColumn = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
        val nameColumn = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
        val dateTakenColumn = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_TAKEN)
        val dateAddedColumn = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_ADDED)
        val dateModifiedColumn = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_MODIFIED)
        val widthColumn = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.WIDTH)
        val heightColumn = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.HEIGHT)
        val durationColumn = if (isVideo) cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DURATION) else -1

        val baseUri = if (isVideo) MediaStore.Video.Media.EXTERNAL_CONTENT_URI else MediaStore.Images.Media.EXTERNAL_CONTENT_URI

        while (cursor.moveToNext()) {
            val id = cursor.getLong(idColumn)
            val name = cursor.getString(nameColumn)
            val dateTaken = cursor.getLongOrNull(dateTakenColumn)
            val date = if (dateTaken != null && dateTaken > 0) dateTaken else (cursor.getLong(dateAddedColumn) * 1000)
            val dateModified = cursor.getLong(dateModifiedColumn)
            val width = cursor.getInt(widthColumn)
            val height = cursor.getInt(heightColumn)
            val contentUri = ContentUris.withAppendedId(baseUri, id).toString()
            val videoData = if (isVideo) VideoData(cursor.getLong(durationColumn)) else null

            val existing = existingPhotos[id]
            if (existing == null || existing.date != date || existing.uri != contentUri || existing.videoData != videoData || existing.width != width || existing.height != height || existing.dateModified != dateModified) {
                newOrUpdatedPhotos += Photo(id, name, contentUri, date, width, height, dateModified, existing?.exifSet ?: false, existing?.lat, existing?.long, videoData)
            }
        }
    }

    // Query MediaStore for the specific selection (generation or IDs)
    context.contentResolver.query(
        MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
        arrayOf(MediaStore.Images.Media._ID, MediaStore.Images.Media.DISPLAY_NAME, MediaStore.Images.Media.DATE_TAKEN, MediaStore.Images.Media.DATE_ADDED, MediaStore.Images.Media.WIDTH, MediaStore.Images.Media.HEIGHT, MediaStore.Images.Media.DATE_MODIFIED),
        selection, null, null
    )?.use { processCursor(it, false) }

    context.contentResolver.query(
        MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
        arrayOf(MediaStore.Video.Media._ID, MediaStore.Video.Media.DISPLAY_NAME, MediaStore.Video.Media.DATE_TAKEN, MediaStore.Video.Media.DATE_ADDED, MediaStore.Video.Media.WIDTH, MediaStore.Video.Media.HEIGHT, MediaStore.Video.Media.DURATION, MediaStore.Video.Media.DATE_MODIFIED),
        selection, null, null
    )?.use { processCursor(it, true) }

    if (newOrUpdatedPhotos.isNotEmpty()) {
        photoDao.upsertAll(newOrUpdatedPhotos)
    }
}

suspend fun setExifData(photos: List<Photo>, database: PhotoDatabase, context: Context) = coroutineScope {
    val photoDao = database.photoDao()
    val ps = photos.filter { !it.exifSet }.sortedByDescending { it.date }
    ps.chunked(50).forEachIndexed { index, photosChunk ->
        val newPhotos = photosChunk.map { photo ->
            async(Dispatchers.IO) {
                try {
                    val (lat, long) = context.contentResolver.openInputStream(
                        MediaStore.setRequireOriginal(
                            photo.uri.toUri()
                        )
                    )?.use { inputStream ->
                        val exif = ExifInterface(inputStream)
                        val latLong = exif.latLong
                        val lat = latLong?.getOrNull(0)
                        val long = latLong?.getOrNull(1)
                        listOf(lat, long)
                    } ?: listOf(null, null)
                    photo.copy(exifSet = true, lat = lat, long = long)
                } catch (e: Exception) {
                    photo.copy(exifSet = true) // Mark as set even on error to avoid retry every time
                }
            }
        }.awaitAll()
        photoDao.upsertAll(newPhotos)
    }
}
