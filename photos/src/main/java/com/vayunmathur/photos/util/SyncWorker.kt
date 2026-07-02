package com.vayunmathur.photos.util

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.ContentResolver
import android.content.ContentUris
import android.content.Context
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.database.getLongOrNull
import androidx.core.net.toUri
import androidx.exifinterface.media.ExifInterface
import androidx.sqlite.db.SimpleSQLiteQuery
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.ListenableWorker.Result as WorkResult
import com.vayunmathur.library.util.DataStoreUtils
import com.vayunmathur.library.util.buildDatabase
import com.vayunmathur.photos.data.Photo
import com.vayunmathur.photos.data.PhotoFace
import com.vayunmathur.photos.data.PhotoOCR
import com.vayunmathur.photos.data.PhotoDatabase
import com.vayunmathur.photos.data.VideoData
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

class SyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): WorkResult = withContext(Dispatchers.IO) {
        setForeground(createForegroundInfo())
        val database = applicationContext.buildDatabase<PhotoDatabase>()
        val dataStore = DataStoreUtils.getInstance(applicationContext)
        
        val triggeredUris = triggeredContentUris
        val lastGeneration = dataStore.getLong("last_photos_generation") ?: 0L
        val currentGeneration = MediaStore.getGeneration(applicationContext, MediaStore.VOLUME_EXTERNAL)

        if (triggeredUris.isNotEmpty()) {
            syncPhotos(applicationContext, database, triggeredUris.toList())
        } else {
            syncPhotos(applicationContext, database, null, lastGeneration)
        }
        
        val photos = database.photoDao().getAll()
        setExifData(photos, database, applicationContext)
        
        if (dataStore.getBoolean("image_understanding_enabled", false)) {
            OCRWorker.enqueue(applicationContext)
        }

        if (dataStore.getBoolean("face_match_enabled", false)) {
            FaceWorker.enqueue(applicationContext)
        }
        
        dataStore.setLong("last_photos_generation", currentGeneration)
        
        // Enqueue next observation
        enqueue(applicationContext)
        
        WorkResult.success()
    }

    private fun createForegroundInfo(): ForegroundInfo = applicationContext.syncForegroundInfo(
        notificationId = 101,
        channelId = "sync_worker",
        channelName = "Photo Sync",
        title = "Syncing Photos",
        text = "Indexing photos and extracting text...",
    )

    companion object {
        const val WORK_NAME = "SyncWorker"

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
                ExistingWorkPolicy.KEEP,
                request
            )
        }

        fun runOnce(context: Context) {
            val request = OneTimeWorkRequestBuilder<SyncWorker>().build()
            WorkManager.getInstance(context).enqueueUniqueWork(
                WORK_NAME,
                ExistingWorkPolicy.REPLACE,
                request
            )
        }
    }
}

class OCRWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): WorkResult = withContext(Dispatchers.IO) {
        val dataStore = DataStoreUtils.getInstance(applicationContext)
        if (!dataStore.getBoolean("image_understanding_enabled", false)) {
            return@withContext WorkResult.success()
        }
        ocrMutex.withLock {
            setForeground(createForegroundInfo())
            val database =
                applicationContext.buildDatabase<PhotoDatabase>()
            val photos = database.photoDao().getAll()
            runOCR(photos, database, applicationContext)
            WorkResult.success()
        }
    }

    private fun createForegroundInfo(): ForegroundInfo = applicationContext.syncForegroundInfo(
        notificationId = 102,
        channelId = "ocr_worker",
        channelName = "Photo Indexing",
        title = "Analyzing Photos",
        text = "Extracting scene information...",
    )

    companion object {
        private const val WORK_NAME = "OCRWorker"
        private val ocrMutex = Mutex() // Shared across all instances of OCRWorker


        fun enqueue(context: Context) {
            val request = OneTimeWorkRequestBuilder<OCRWorker>().build()
            WorkManager.getInstance(context).enqueueUniqueWork(
                WORK_NAME,
                ExistingWorkPolicy.KEEP,
                request
            )
        }
    }
}

suspend fun syncPhotos(context: Context, database: PhotoDatabase, uris: List<Uri>? = null, lastGeneration: Long = 0L) {
    val photoDao = database.photoDao()
    // Single read of the local DB reused for both deletion detection and update diffing.
    val existing = photoDao.getAll()
    // 1. Get all IDs currently in MediaStore to detect deletions
    val allMediaStoreIds = mutableSetOf<Long>()
    fun collectIds(baseUri: Uri) {
        try {
            val bundle = Bundle().apply {
                putInt(MediaStore.QUERY_ARG_MATCH_TRASHED, MediaStore.MATCH_INCLUDE)
            }
            context.contentResolver.query(baseUri, arrayOf(MediaStore.MediaColumns._ID), bundle, null)?.use { cursor ->
                val idCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
                while (cursor.moveToNext()) {
                    try {
                        allMediaStoreIds.add(cursor.getLong(idCol))
                    } catch (e: Exception) {
                        Log.e("SyncWorker", "Error reading ID from MediaStore cursor", e)
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("SyncWorker", "Error querying MediaStore for IDs: $baseUri", e)
        }
    }
    collectIds(MediaStore.Images.Media.EXTERNAL_CONTENT_URI)
    collectIds(MediaStore.Video.Media.EXTERNAL_CONTENT_URI)

    // 2. Handle deletions
    val localIds = existing.map { it.id }.toSet()
    val toDelete = if (uris != null) {
        val triggeredIds = uris.mapNotNull { runCatching { ContentUris.parseId(it) }.getOrNull() }.toSet()
        triggeredIds - allMediaStoreIds
    } else {
        localIds - allMediaStoreIds
    }

    if (toDelete.isNotEmpty()) {
        toDelete.chunked(900).forEach { chunk ->
            photoDao.deleteByIds(chunk)
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

    val existingPhotos = existing.associateBy { it.id }
    val newOrUpdatedPhotos = mutableListOf<Photo>()

    fun processCursor(cursor: android.database.Cursor, isVideo: Boolean) {
        val idColumn = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
        val nameColumn = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
        val dateTakenColumn = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_TAKEN)
        val dateAddedColumn = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_ADDED)
        val dateModifiedColumn = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_MODIFIED)
        val widthColumn = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.WIDTH)
        val heightColumn = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.HEIGHT)
        val isTrashedColumn = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.IS_TRASHED)
        val durationColumn = if (isVideo) cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DURATION) else -1

        val baseUri = if (isVideo) MediaStore.Video.Media.EXTERNAL_CONTENT_URI else MediaStore.Images.Media.EXTERNAL_CONTENT_URI

        while (cursor.moveToNext()) {
            try {
                val id = cursor.getLong(idColumn)
                val name = cursor.getString(nameColumn)
                val dateTaken = cursor.getLongOrNull(dateTakenColumn)
                val date = if (dateTaken != null && dateTaken > 0) dateTaken else (cursor.getLong(dateAddedColumn) * 1000)
                val dateModified = cursor.getLong(dateModifiedColumn)
                val width = cursor.getInt(widthColumn)
                val height = cursor.getInt(heightColumn)
                val isTrashed = cursor.getInt(isTrashedColumn) == 1
                val contentUri = ContentUris.withAppendedId(baseUri, id).toString()
                val videoData = if (isVideo) VideoData(cursor.getLong(durationColumn)) else null

                val existing = existingPhotos[id]
                if (existing == null || existing.date != date || existing.uri != contentUri || existing.videoData != videoData || existing.width != width || existing.height != height || existing.dateModified != dateModified || existing.isTrashed != isTrashed) {
                    newOrUpdatedPhotos += Photo(id, name, contentUri, date, width, height, dateModified, existing?.exifSet ?: false, existing?.lat, existing?.long, videoData, isTrashed, faceScanned = existing?.faceScanned ?: false)
                }
            } catch (e: Exception) {
                Log.e("SyncWorker", "Error processing photo/video from cursor", e)
            }
        }
    }

    try {
        val bundle = Bundle().apply {
            putString(ContentResolver.QUERY_ARG_SQL_SELECTION, selection)
            putInt(MediaStore.QUERY_ARG_MATCH_TRASHED, MediaStore.MATCH_INCLUDE)
        }
        context.contentResolver.query(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            arrayOf(MediaStore.Images.Media._ID, MediaStore.Images.Media.DISPLAY_NAME, MediaStore.Images.Media.DATE_TAKEN, MediaStore.Images.Media.DATE_ADDED, MediaStore.Images.Media.WIDTH, MediaStore.Images.Media.HEIGHT, MediaStore.Images.Media.DATE_MODIFIED, MediaStore.Images.Media.IS_TRASHED),
            bundle, null
        )?.use { processCursor(it, false) }
    } catch (e: Exception) {
        Log.e("SyncWorker", "Error querying MediaStore for images", e)
    }

    try {
        val bundle = Bundle().apply {
            putString(ContentResolver.QUERY_ARG_SQL_SELECTION, selection)
            putInt(MediaStore.QUERY_ARG_MATCH_TRASHED, MediaStore.MATCH_INCLUDE)
        }
        context.contentResolver.query(
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
            arrayOf(MediaStore.Video.Media._ID, MediaStore.Video.Media.DISPLAY_NAME, MediaStore.Video.Media.DATE_TAKEN, MediaStore.Video.Media.DATE_ADDED, MediaStore.Video.Media.WIDTH, MediaStore.Video.Media.HEIGHT, MediaStore.Video.Media.DURATION, MediaStore.Video.Media.DATE_MODIFIED, MediaStore.Video.Media.IS_TRASHED),
            bundle, null
        )?.use { processCursor(it, true) }
    } catch (e: Exception) {
        Log.e("SyncWorker", "Error querying MediaStore for videos", e)
    }

    if (newOrUpdatedPhotos.isNotEmpty()) {
        photoDao.upsertAll(newOrUpdatedPhotos)
    }
}

private fun Context.syncForegroundInfo(
    notificationId: Int,
    channelId: String,
    channelName: String,
    title: String,
    text: String,
): ForegroundInfo {
    val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    manager.createNotificationChannel(
        NotificationChannel(channelId, channelName, NotificationManager.IMPORTANCE_LOW)
    )
    val notification = NotificationCompat.Builder(this, channelId)
        .setContentTitle(title)
        .setContentText(text)
        .setSmallIcon(android.R.drawable.stat_notify_sync)
        .setOngoing(true)
        .build()
    return ForegroundInfo(notificationId, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
}

suspend fun setExifData(photos: List<Photo>, database: PhotoDatabase, context: Context) = coroutineScope {
    val photoDao = database.photoDao()
    val ps = photos.filter { !it.exifSet }.sortedByDescending { it.date }
    ps.chunked(50).forEach { photosChunk ->
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
                } catch (_: Exception) {
                    photo.copy(exifSet = true) // Mark as set even on error to avoid retry every time
                }
            }
        }.awaitAll()
        photoDao.upsertAll(newPhotos)
    }
}

suspend fun runOCR(photos: List<Photo>, database: PhotoDatabase, context: Context) = coroutineScope {
    val photoDao = database.photoDao()
    val dataStore = DataStoreUtils.getInstance(context)
    // Find photos that don't have OCR yet
    // Since it's FTS4, we might want to optimize this, but for now we'll just check existence
    // Actually, we can get all photoIds from PhotoOCR and filter
    val ocrIds = database.query(SimpleSQLiteQuery("SELECT rowid FROM PhotoOCR"), null).use { cursor ->
        val ids = mutableSetOf<Long>()
        while (cursor.moveToNext()) {
            ids.add(cursor.getLong(0))
        }
        ids
    }

    val ps = photos.filter { it.id !in ocrIds && it.videoData == null }.sortedByDescending { it.date }
    if (ps.isEmpty()) return@coroutineScope

    val ocrManager = OCRManager(context)

    // Check if model is available before processing
    if (!ocrManager.isAvailable()) {
        Log.w("SyncWorker", "OpenAssistant not installed, skipping OCR processing")
        return@coroutineScope
    }

    ps.forEach { photo ->
        ensureActive()
        if (!dataStore.getBoolean("image_understanding_enabled", false)) {
            return@coroutineScope
        }

        try {
            val result = ocrManager.runOCR(photo.uri.toUri())
            if (result != null) {
                val (ocrText, description) = result
                photoDao.upsertOCR(PhotoOCR(photo.id, ocrText, description))
                Log.i("SyncWorker", "OCR for ${photo.id} produced text: ${ocrText.take(50)}, description: ${description.take(50)}")
            } else {
                Log.w("SyncWorker", "OCR for ${photo.id} returned null, will retry later")
            }
        } catch (e: Exception) {
            Log.e("SyncWorker", "Error running OCR for photo ${photo.id}, will retry later", e)
        }

        // Throttle calls to the (shared, single-threaded) inference service.
        delay(OCR_INTER_ITEM_DELAY_MS)
    }
}

private const val OCR_INTER_ITEM_DELAY_MS = 30_000L

class FaceWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): WorkResult = withContext(Dispatchers.IO) {
        val dataStore = DataStoreUtils.getInstance(applicationContext)
        if (!dataStore.getBoolean("face_match_enabled", false)) {
            return@withContext WorkResult.success()
        }
        faceMutex.withLock {
            setForeground(createForegroundInfo())
            val database = applicationContext.buildDatabase<PhotoDatabase>()
            runFaceIndexing(database, applicationContext)
            WorkResult.success()
        }
    }

    private fun createForegroundInfo(): ForegroundInfo = applicationContext.syncForegroundInfo(
        notificationId = 103,
        channelId = "face_worker",
        channelName = "People Indexing",
        title = "Finding People",
        text = "Matching faces to your contacts on-device...",
    )

    companion object {
        private const val WORK_NAME = "FaceWorker"
        private val faceMutex = Mutex()

        fun enqueue(context: Context) {
            val request = OneTimeWorkRequestBuilder<FaceWorker>().build()
            WorkManager.getInstance(context).enqueueUniqueWork(
                WORK_NAME,
                ExistingWorkPolicy.KEEP,
                request,
            )
        }
    }
}

/**
 * Index contact faces (once, when empty) then scan any not-yet-scanned library
 * photos for faces and match them to contacts. All on-device.
 */
suspend fun runFaceIndexing(database: PhotoDatabase, context: Context) {
    val photoDao = database.photoDao()
    val faceDao = database.faceDao()
    val dataStore = DataStoreUtils.getInstance(context)

    // 1. Make sure contact face templates exist. Cheap no-op once populated.
    val existingKeys = faceDao.getContactFaces().map { it.contactKey }.toSet()
    val newContactFaces = ContactFaceIndexer(context).index(existingKeys)
    if (newContactFaces.isNotEmpty()) faceDao.upsertContactFaces(newContactFaces)

    val contacts = faceDao.getContactFaces()
        .map { it.contactKey to FaceRecognizer.bytesToFloats(it.embedding) }
    val nameByKey = faceDao.getContactFaces().associate { it.contactKey to it.name }

    // 2. Scan library photos that haven't been scanned yet.
    val photos = photoDao.getUnscannedForFaces().sortedByDescending { it.date }
    for (photo in photos) {
        if (!dataStore.getBoolean("face_match_enabled", false)) return

        try {
            val bitmap = loadBitmapForFaces(context, photo.uri.toUri())
            if (bitmap != null) {
                val templates = FaceRecognizer.detectFaces(bitmap)
                bitmap.recycle()
                val faces = templates.map { template ->
                    val key = FaceRecognizer.bestMatch(template, contacts)
                    PhotoFace(
                        photoId = photo.id,
                        embedding = FaceRecognizer.floatsToBytes(template),
                        contactKey = key,
                        contactName = key?.let { nameByKey[it] },
                    )
                }
                if (faces.isNotEmpty()) faceDao.insertPhotoFaces(faces)
            }
        } catch (e: Exception) {
            Log.e("FaceWorker", "Error scanning faces for photo ${photo.id}", e)
        }

        // Mark scanned regardless of outcome so we don't retry forever.
        photoDao.upsertAll(listOf(photo.copy(faceScanned = true)))
    }
}

private fun loadBitmapForFaces(context: Context, uri: Uri): Bitmap? {
    return try {
        val source = ImageDecoder.createSource(context.contentResolver, uri)
        ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            decoder.isMutableRequired = false
            val maxDim = maxOf(info.size.width, info.size.height)
            val target = 720
            if (maxDim > target) {
                val scale = target.toFloat() / maxDim
                decoder.setTargetSize(
                    (info.size.width * scale).toInt().coerceAtLeast(1),
                    (info.size.height * scale).toInt().coerceAtLeast(1),
                )
            }
        }
    } catch (e: Exception) {
        Log.e("FaceWorker", "Failed to decode $uri for faces", e)
        null
    }
}
