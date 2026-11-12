package com.vayunmathur.photos.util
import com.vayunmathur.photos.R

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
import com.vayunmathur.library.log.Log
import androidx.core.app.NotificationCompat
import androidx.core.database.getLongOrNull
import androidx.core.net.toUri
import androidx.exifinterface.media.ExifInterface
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.ListenableWorker.Result as WorkResult
import com.vayunmathur.library.ocr.OcrEngine
import com.vayunmathur.library.util.DataStoreUtils
import com.vayunmathur.photos.data.ClipResult
import com.vayunmathur.photos.data.ExifResult
import com.vayunmathur.photos.data.FaceRepository
import com.vayunmathur.photos.data.OcrResult
import com.vayunmathur.photos.data.Person
import com.vayunmathur.photos.data.Photo
import com.vayunmathur.photos.data.PhotoFace
import com.vayunmathur.photos.data.PhotoScanRepository
import com.vayunmathur.photos.data.PhotoScanTarget
import com.vayunmathur.photos.data.PhotosRepository
import com.vayunmathur.photos.data.VideoData
import com.vayunmathur.photos.data.toJson
import com.vayunmathur.photos.domain.MIN_OCR_DIM
import com.vayunmathur.photos.domain.decodeForOcr
import com.vayunmathur.photos.domain.toLayout
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import android.database.CursorIndexOutOfBoundsException
import java.io.IOException
import java.util.concurrent.TimeUnit

class SyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): WorkResult = withContext(Dispatchers.IO) {
        setForeground(createForegroundInfo())
        val repository = PhotosRepository.get(applicationContext)
        val scanRepository = PhotoScanRepository.get(applicationContext)
        val dataStore = DataStoreUtils.getInstance(applicationContext)
        
        val triggeredUris = triggeredContentUris
        val lastGeneration = dataStore.getLong("last_photos_generation") ?: 0L
        val currentGeneration = MediaStore.getGeneration(applicationContext, MediaStore.VOLUME_EXTERNAL)

        if (triggeredUris.isNotEmpty()) {
            syncPhotos(applicationContext, repository, triggeredUris.toList())
        } else {
            // Rows predating the mimeType column are invisible to an incremental
            // scan (their GENERATION_MODIFIED hasn't moved), so scan everything
            // until they're all backfilled.
            val needsMimeBackfill = scanRepository.countMissingMimeType() > 0
            syncLibrary(
                applicationContext, repository, lastGeneration, needsMimeBackfill,
                DataStoreOneTimeFlag(dataStore, FAVORITES_RESCAN_DONE_KEY),
            )
        }
        
        setExifData(scanRepository, applicationContext)
        
        // OCR and face grouping are both always on (no opt-in). Each worker is
        // inert if its data/model assets are missing.
        OCRWorker.enqueue(applicationContext)
        FaceWorker.enqueue(applicationContext)
        ClipWorker.enqueue(applicationContext)
        
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
        ocrMutex.withLock {
            setForeground(createForegroundInfo())
            val scanRepository = PhotoScanRepository.get(applicationContext)
            runOCR(scanRepository, applicationContext)
            WorkResult.success()
        }
    }

    private fun createForegroundInfo(): ForegroundInfo = applicationContext.syncForegroundInfo(
        notificationId = 102,
        channelId = "ocr_worker",
        channelName = "Photo Indexing",
        title = "Analyzing Photos",
        text = "Reading text in your photos...",
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

/**
 * A persisted "has this one-off job run yet" bit. An interface so the
 * once-only behaviour of [syncLibrary] is testable without DataStore.
 */
internal interface OneTimeFlag {
    suspend fun isSet(): Boolean
    suspend fun set()
}

private class DataStoreOneTimeFlag(
    private val dataStore: DataStoreUtils,
    private val key: String,
) : OneTimeFlag {
    override suspend fun isSet(): Boolean = dataStore.getBooleanAwait(key)
    override suspend fun set() = dataStore.setBoolean(key, true)
}

/**
 * Set once the post-upgrade full rescan for IS_FAVORITE (#912) has run.
 * MIGRATION_19_20 adds the column as 0 everywhere, and an incremental scan
 * only sees rows whose GENERATION_MODIFIED moved since the last sync, so
 * photos favourited in another app before the upgrade would never show up
 * without one full pass.
 */
internal const val FAVORITES_RESCAN_DONE_KEY = "favorites_rescan_done"

/**
 * The untriggered library sync: incremental from [lastGeneration], unless a
 * full scan is owed — rows still missing their mimeType, or the one-time
 * favourites rescan behind [favoritesRescan] has not run yet. The flag is set
 * only after a full scan completed, so it runs exactly once.
 */
internal suspend fun syncLibrary(
    context: Context,
    repository: PhotosRepository,
    lastGeneration: Long,
    needsMimeBackfill: Boolean,
    favoritesRescan: OneTimeFlag,
) {
    val needsFavoritesRescan = !favoritesRescan.isSet()
    val fullScan = needsMimeBackfill || needsFavoritesRescan
    syncPhotos(context, repository, null, if (fullScan) 0L else lastGeneration)
    if (needsFavoritesRescan) favoritesRescan.set()
}

suspend fun syncPhotos(
    context: Context,
    repository: PhotosRepository,
    uris: List<Uri>? = null,
    lastGeneration: Long = 0L,
) {
    // Single read of the local DB reused for both deletion detection and update diffing.
    val existing = repository.getAll()
    // 1. Get all IDs currently in MediaStore to detect deletions
    val allMediaStoreIds = collectMediaStoreIds(context)

    // 2. Handle deletions
    val localIds = existing.map { it.id }.toSet()
    val toDelete = if (uris != null) {
        val triggeredIds = uris.mapNotNull { runCatching { ContentUris.parseId(it) }.getOrNull() }.toSet()
        triggeredIds - allMediaStoreIds
    } else {
        localIds - allMediaStoreIds
    }

    if (toDelete.isNotEmpty()) {
        toDelete.chunked(DELETE_ID_CHUNK_SIZE).forEach { chunk ->
            repository.deleteByIds(chunk)
        }
    }

    // 3. Process additions/updates
    val selection = buildSyncSelection(uris, lastGeneration)

    val existingPhotos = existing.associateBy { it.id }
    val newOrUpdatedPhotos = mutableListOf<Photo>()
    val cursorScope = CursorScope(existingPhotos, newOrUpdatedPhotos)

    queryMediaStore(
        context,
        MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
        IMAGE_PROJECTION,
        selection,
        false,
        cursorScope,
    )
    queryMediaStore(
        context,
        MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
        VIDEO_PROJECTION,
        selection,
        true,
        cursorScope,
    )

    if (newOrUpdatedPhotos.isNotEmpty()) {
        repository.upsertAll(newOrUpdatedPhotos)
    }
    // Favourite-only diffs (a toggle here or in another gallery app) are written
    // by column: an upsert of the row rebuilt above would drop its CLIP
    // embedding and OCR geometry and send it back through indexing.
    for ((value, ids) in cursorScope.favoriteChanges.entries.groupBy({ it.value }, { it.key })) {
        ids.chunked(DELETE_ID_CHUNK_SIZE).forEach { chunk -> repository.setFavorite(chunk, value) }
    }
}

private const val MILLIS_PER_SECOND = 1000L

/** SQLite bind-variable ceiling; deletions and favourite writes are chunked under it. */
private const val DELETE_ID_CHUNK_SIZE = 900

/** OCR preview length in the worker log line. */
private const val OCR_LOG_PREVIEW_CHARS = 50

private fun isPhotoChanged(
    existing: Photo?,
    date: Long,
    contentUri: String,
    videoData: VideoData?,
    width: Int,
    height: Int,
    dateModified: Long,
    isTrashed: Boolean,
    mimeType: String?,
    album: String?,
): Boolean {
    if (existing == null) return true
    return existing.date != date ||
        existing.uri != contentUri ||
        existing.videoData != videoData ||
        existing.width != width ||
        existing.height != height ||
        existing.dateModified != dateModified ||
        existing.isTrashed != isTrashed ||
        existing.mimeType != mimeType ||
        existing.album != album
}

/** Column indices for one MediaStore cursor, resolved once per query. */
private class CursorColumns(cursor: android.database.Cursor, isVideo: Boolean) {
    val id: Int = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
    val name: Int = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
    val dateTaken: Int = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_TAKEN)
    val dateAdded: Int = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_ADDED)
    val dateModified: Int = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_MODIFIED)
    val width: Int = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.WIDTH)
    val height: Int = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.HEIGHT)
    val isTrashed: Int = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.IS_TRASHED)
    val mimeType: Int = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.MIME_TYPE)
    val album: Int = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.BUCKET_DISPLAY_NAME)
    val isFavorite: Int = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.IS_FAVORITE)
    val duration: Int = if (isVideo) cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DURATION) else -1
}

/** Mutable accumulation state shared across the image/video cursor passes. */
private class CursorScope(
    val existingPhotos: Map<Long, Photo>,
    val newOrUpdatedPhotos: MutableList<Photo>,
    /** Rows whose only difference is IS_FAVORITE: id -> new value. */
    val favoriteChanges: MutableMap<Long, Boolean> = mutableMapOf(),
)

private fun processCursor(cursor: android.database.Cursor, isVideo: Boolean, scope: CursorScope) {
    val cols = CursorColumns(cursor, isVideo)
    val baseUri = if (isVideo) {
        MediaStore.Video.Media.EXTERNAL_CONTENT_URI
    } else {
        MediaStore.Images.Media.EXTERNAL_CONTENT_URI
    }
    while (cursor.moveToNext()) {
        try {
            readCursorRow(cursor, cols, isVideo, baseUri, scope)
        } catch (e: CursorIndexOutOfBoundsException) {
            Log.error("SyncWorker", "Error processing photo/video from cursor", e)
        } catch (e: IllegalStateException) {
            Log.error("SyncWorker", "Error processing photo/video from cursor", e)
        }
    }
}

private fun readCursorRow(
    cursor: android.database.Cursor,
    cols: CursorColumns,
    isVideo: Boolean,
    baseUri: Uri,
    scope: CursorScope,
) {
    val id = cursor.getLong(cols.id)
    val name = cursor.getString(cols.name)
    val dateTaken = cursor.getLongOrNull(cols.dateTaken)
    val date = if (dateTaken != null && dateTaken > 0) {
        dateTaken
    } else {
        cursor.getLong(cols.dateAdded) * MILLIS_PER_SECOND
    }
    val dateModified = cursor.getLong(cols.dateModified)
    val width = cursor.getInt(cols.width)
    val height = cursor.getInt(cols.height)
    val isTrashed = cursor.getInt(cols.isTrashed) == 1
    val mimeType = cursor.getString(cols.mimeType)
    val album = cursor.getString(cols.album)
    val isFavorite = cursor.getInt(cols.isFavorite) == 1
    val contentUri = ContentUris.withAppendedId(baseUri, id).toString()
    val videoData = if (isVideo) VideoData(cursor.getLong(cols.duration)) else null

    val existing = scope.existingPhotos[id]
    val changed = isPhotoChanged(
        existing, date, contentUri, videoData, width, height,
        dateModified, isTrashed, mimeType, album,
    )
    if (changed) {
        scope.newOrUpdatedPhotos += Photo(
            id, name, contentUri, date, width, height, dateModified,
            existing?.exifSet ?: false, existing?.lat, existing?.long, videoData,
            existing?.panoData, isTrashed,
            faceScanned = existing?.faceScanned ?: false,
            ocrText = existing?.ocrText, ocrScanned = existing?.ocrScanned ?: false,
            mimeType = mimeType, album = album, isFavorite = isFavorite,
        )
    } else if (existing != null && existing.isFavorite != isFavorite) {
        scope.favoriteChanges[id] = isFavorite
    }
}

private fun queryMediaStore(
    context: Context,
    uri: Uri,
    projection: Array<String>,
    selection: String?,
    isVideo: Boolean,
    scope: CursorScope,
) {
    val kind = if (isVideo) "videos" else "images"
    try {
        val bundle = Bundle().apply {
            putString(ContentResolver.QUERY_ARG_SQL_SELECTION, selection)
            putInt(MediaStore.QUERY_ARG_MATCH_TRASHED, MediaStore.MATCH_INCLUDE)
        }
        context.contentResolver.query(uri, projection, bundle, null)?.use { processCursor(it, isVideo, scope) }
    } catch (e: SecurityException) {
        Log.error("SyncWorker", "Error querying MediaStore for $kind", e)
    } catch (e: IllegalArgumentException) {
        Log.error("SyncWorker", "Error querying MediaStore for $kind", e)
    }
}

private fun collectMediaStoreIds(context: Context): MutableSet<Long> {
    val ids = mutableSetOf<Long>()
    collectIdsInto(context, MediaStore.Images.Media.EXTERNAL_CONTENT_URI, ids)
    collectIdsInto(context, MediaStore.Video.Media.EXTERNAL_CONTENT_URI, ids)
    return ids
}

private fun collectIdsInto(context: Context, baseUri: Uri, ids: MutableSet<Long>) {
    try {
        val bundle = Bundle().apply {
            putInt(MediaStore.QUERY_ARG_MATCH_TRASHED, MediaStore.MATCH_INCLUDE)
        }
        context.contentResolver.query(baseUri, arrayOf(MediaStore.MediaColumns._ID), bundle, null)?.use { cursor ->
            val idCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
            while (cursor.moveToNext()) {
                try {
                    ids.add(cursor.getLong(idCol))
                } catch (e: CursorIndexOutOfBoundsException) {
                    Log.error("SyncWorker", "Error reading ID from MediaStore cursor", e)
                } catch (e: IllegalStateException) {
                    Log.error("SyncWorker", "Error reading ID from MediaStore cursor", e)
                }
            }
        }
    } catch (e: SecurityException) {
        Log.error("SyncWorker", "Error querying MediaStore for IDs: $baseUri", e)
    } catch (e: IllegalArgumentException) {
        Log.error("SyncWorker", "Error querying MediaStore for IDs: $baseUri", e)
    }
}

private fun buildSyncSelection(uris: List<Uri>?, lastGeneration: Long): String? = when {
    uris != null -> {
        val ids = uris.mapNotNull { runCatching { ContentUris.parseId(it) }.getOrNull() }
        if (ids.isEmpty()) null else "_id IN (${ids.joinToString(",")})"
    }
    lastGeneration > 0 -> {
        "${MediaStore.MediaColumns.GENERATION_MODIFIED} > $lastGeneration"
    }
    else -> null
}

private val IMAGE_PROJECTION = arrayOf(
    MediaStore.Images.Media._ID,
    MediaStore.Images.Media.DISPLAY_NAME,
    MediaStore.Images.Media.DATE_TAKEN,
    MediaStore.Images.Media.DATE_ADDED,
    MediaStore.Images.Media.WIDTH,
    MediaStore.Images.Media.HEIGHT,
    MediaStore.Images.Media.DATE_MODIFIED,
    MediaStore.Images.Media.IS_TRASHED,
    MediaStore.Images.Media.MIME_TYPE,
    MediaStore.Images.Media.BUCKET_DISPLAY_NAME,
    MediaStore.Images.Media.IS_FAVORITE,
)

private val VIDEO_PROJECTION = arrayOf(
    MediaStore.Video.Media._ID,
    MediaStore.Video.Media.DISPLAY_NAME,
    MediaStore.Video.Media.DATE_TAKEN,
    MediaStore.Video.Media.DATE_ADDED,
    MediaStore.Video.Media.WIDTH,
    MediaStore.Video.Media.HEIGHT,
    MediaStore.Video.Media.DURATION,
    MediaStore.Video.Media.DATE_MODIFIED,
    MediaStore.Video.Media.IS_TRASHED,
    MediaStore.Video.Media.MIME_TYPE,
    MediaStore.Video.Media.BUCKET_DISPLAY_NAME,
    MediaStore.Video.Media.IS_FAVORITE,
)

internal fun Context.syncForegroundInfo(
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

/**
 * Parse EXIF/XMP for every photo that hasn't been through it yet and write the
 * geo/panorama columns back.
 *
 * Driven off a projection of the un-parsed rows rather than the whole library:
 * this used to be handed `getAll()` (a `SELECT *` over every photo, embeddings
 * included) and wrote those same blobs straight back via `upsertAll`. The write
 * is column-targeted for the same reason — reconstructing a [Photo] from the
 * projection and upserting it would write NULL over every clipEmbedding.
 */
suspend fun setExifData(scanRepository: PhotoScanRepository, context: Context) = coroutineScope {
    val targets = scanRepository.getUnscannedForExif()
    targets.chunked(EXIF_CHUNK).forEach { chunk ->
        val results = chunk.map { target ->
            async(Dispatchers.IO) {
                try {
                    val (latLong, panoData) = context.contentResolver.openInputStream(
                        MediaStore.setRequireOriginal(
                            target.uri.toUri()
                        )
                    )?.use { inputStream ->
                        val exif = ExifInterface(inputStream)
                        val ll = exif.latLong
                        val pano = PanoXmpParser.parse(exif.getAttribute(ExifInterface.TAG_XMP))
                        Pair(ll, pano)
                    } ?: Pair(null, null)
                    ExifResult(
                        id = target.id,
                        lat = latLong?.getOrNull(0),
                        long = latLong?.getOrNull(1),
                        pano = panoData,
                    )
                } catch (_: Exception) {
                    // Mark as set even on error to avoid retry every time.
                    ExifResult(id = target.id, lat = null, long = null, pano = null)
                }
            }
        }.awaitAll()
        scanRepository.setExifResults(results)
    }
}

private const val EXIF_CHUNK = 50

/**
 * Sustained on-device AI indexing (OCR, CLIP, face models) heats the phone.
 * On top of the short per-item pauses, each indexing loop takes a longer
 * "cooling break" every [BATCH_COOLDOWN_EVERY] items it actually ran inference
 * on, idling for [BATCH_COOLDOWN_MS] so the device can shed heat before the next
 * batch. [processed] is the running count of AI-processed items in this run.
 */
internal suspend fun coolDownBetweenBatches(processed: Int, tag: String) {
    if (processed > 0 && processed % BATCH_COOLDOWN_EVERY == 0) {
        Log.status(tag, "Cooling break after $processed items: pausing ${BATCH_COOLDOWN_MS}ms to let the device cool")
        delay(BATCH_COOLDOWN_MS)
    }
}

// Number of AI-processed items between cooling breaks, and how long each break
// lasts. Kept modest so indexing still makes steady progress in the background.
private const val BATCH_COOLDOWN_EVERY = 20
private const val BATCH_COOLDOWN_MS = 5_000L

suspend fun runOCR(scanRepository: PhotoScanRepository, context: Context) = coroutineScope {
    val photos = scanRepository.getUnscannedForOCR()
    if (photos.isEmpty()) return@coroutineScope

    val ocrEngine = OcrEngine(context)
    // Inert (but no crash) if the OCR model assets can't be loaded.
    if (!ocrEngine.isAvailable()) {
        Log.status("OCRWorker", "OCR models unavailable; skipping OCR")
        return@coroutineScope
    }

    // Accumulated so a scan invalidates the Photo table once per flush rather
    // than once per photo. Flushed in a `finally` so cancellation and errors
    // still persist the work already done.
    val pendingResults = mutableListOf<OcrResult>()
    val pendingSkipped = mutableListOf<Long>()
    suspend fun flush() {
        if (pendingResults.isNotEmpty()) {
            scanRepository.setOcrResults(pendingResults.toList())
            pendingResults.clear()
        }
        if (pendingSkipped.isNotEmpty()) {
            scanRepository.setOcrScanned(pendingSkipped.toList())
            pendingSkipped.clear()
        }
    }

    var processed = 0
    try {
        for (photo in photos) {
            ensureActive()

            // Skip tiny images (icons/thumbnails); mark scanned so we don't retry.
            val largestDim = maxOf(photo.width, photo.height)
            if (largestDim in 1 until MIN_OCR_DIM) {
                pendingSkipped += photo.id
                if (pendingSkipped.size >= INDEX_FLUSH_EVERY) flush()
                continue
            }

            val result = try {
                val bitmap = decodeForOcr(context, photo.uri.toUri())
                if (bitmap != null) {
                    // Boxes are stored relative to this bitmap, so read its size
                    // before recycling it.
                    val width = bitmap.width
                    val height = bitmap.height
                    try {
                        ocrEngine.recognizeDetailed(bitmap).toLayout(width, height)
                    } finally {
                        bitmap.recycle()
                    }
                } else {
                    null
                }
            } catch (e: IOException) {
                Log.error("OCRWorker", "Error running OCR for photo ${photo.id}", e)
                null
            } catch (e: IllegalStateException) {
                Log.error("OCRWorker", "Error running OCR for photo ${photo.id}", e)
                null
            }

            // Store result and mark scanned regardless of outcome (mirrors faces).
            val text = result?.text
            pendingResults += OcrResult(
                id = photo.id,
                text = text,
                boxes = result?.takeIf { it.boxes.isNotEmpty() }?.toJson(),
            )
            if (pendingResults.size >= INDEX_FLUSH_EVERY) flush()
            Log.status("OCRWorker", "OCR for ${photo.id}: ${text?.take(OCR_LOG_PREVIEW_CHARS)?.replace("\n", " ")}")

            // Short pause between images keeps sustained CPU/battery use low.
            delay(OCR_INTER_ITEM_DELAY_MS)
            // Longer cooling break every batch so the device can shed heat.
            processed++
            coolDownBetweenBatches(processed, "OCRWorker")
        }
    } finally {
        ocrEngine.close()
        // runCatching so a failed flush cannot replace the exception that unwound us
        // (a CancellationException in particular). Unflushed photos simply stay
        // unscanned and are picked up by the next run.
        withContext(NonCancellable) { runCatching { flush() } }
    }
}

/**
 * How many indexed photos accumulate before their rows are written. Each flush
 * is one transaction and therefore one Photo-table invalidation, which every
 * gallery flow reacts to — at one write per photo the UI spent a scan
 * recomputing rather than drawing.
 */
internal const val INDEX_FLUSH_EVERY = 25

private const val OCR_INTER_ITEM_DELAY_MS = 250L
