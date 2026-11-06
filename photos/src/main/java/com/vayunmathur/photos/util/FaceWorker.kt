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
import com.vayunmathur.photos.data.Photo
import com.vayunmathur.photos.data.PhotoFace
import com.vayunmathur.photos.data.PhotoScanRepository
import com.vayunmathur.photos.data.PhotoScanTarget
import com.vayunmathur.photos.data.PhotosRepository
import com.vayunmathur.photos.data.ResolvedPersonRow
import com.vayunmathur.photos.data.VideoData
import com.vayunmathur.photos.data.toJson
import com.vayunmathur.photos.domain.ClusterInput
import com.vayunmathur.photos.domain.MIN_OCR_DIM
import com.vayunmathur.photos.domain.clusterFaces
import com.vayunmathur.photos.domain.decodeForOcr
import com.vayunmathur.photos.domain.resolveIdentities
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

class FaceWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): WorkResult = withContext(Dispatchers.IO) {
        faceMutex.withLock {
            setForeground(createForegroundInfo())
            val faceRepository = FaceRepository.get(applicationContext)
            val scanRepository = PhotoScanRepository.get(applicationContext)
            runFaceIndexing(faceRepository, scanRepository, applicationContext)
            WorkResult.success()
        }
    }

    private fun createForegroundInfo(): ForegroundInfo = applicationContext.syncForegroundInfo(
        notificationId = 103,
        channelId = "face_worker",
        channelName = "People Indexing",
        title = "Finding People",
        text = "Grouping photos of the same person on-device...",
    )

    companion object {
        const val WORK_NAME = "FaceWorker"
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
 * Scan not-yet-scanned library photos for faces, then regroup every stored face into
 * persons — all on-device and unsupervised.
 *
 * Scanning only stores faces, unassigned. Grouping is a separate batch pass over all
 * stored embeddings ([com.vayunmathur.photos.domain.clusterFaces]) run whenever new
 * faces landed, so the result never depends on the order photos were scanned in.
 */
suspend fun runFaceIndexing(
    faceRepository: FaceRepository,
    scanRepository: PhotoScanRepository,
    context: Context,
) {
    val dataStore = DataStoreUtils.getInstance(context)

    // Feature is inert without the on-device models (see FaceRecognizer docs).
    // Return WITHOUT marking photos scanned so they get processed once the
    // model assets are present.
    if (!FaceRecognizer.modelsAvailable(context)) {
        Log.status("FaceWorker", "Face models missing; skipping face indexing")
        return
    }

    // If the embedder model/version changed, the old embeddings are
    // incompatible: drop all clusters + faces and re-scan every photo so they
    // get re-grouped with the new model. Photo rows themselves are untouched.
    val storedVersion = dataStore.getLong("face_embedder_version") ?: 0L
    if (storedVersion != FaceRecognizer.EMBEDDER_VERSION.toLong()) {
        faceRepository.clearPersons()
        faceRepository.clearPhotoFaces()
        scanRepository.resetFaceScanned()
        dataStore.setLong("face_embedder_version", FaceRecognizer.EMBEDDER_VERSION.toLong())
    }

    val photos = scanRepository.getUnscannedForFaces()
    Log.status("FaceWorker", "Face indexing start: ${photos.size} photos to scan")
    var facesTotal = 0
    var photosWithFaces = 0
    var decoded = 0

    // See INDEX_FLUSH_EVERY. Face rows are accumulated alongside the scanned ids and
    // committed with them in one transaction, so a batch is all-or-nothing: a worker
    // killed mid-batch cannot leave rows behind that a re-scan would duplicate.
    // CLUSTER_DIRTY is raised before rows land, so a worker killed between a commit
    // and the grouping pass still regroups on its next run.
    val pendingScanned = mutableListOf<Long>()
    val pendingFaces = mutableListOf<PhotoFace>()
    suspend fun flush() {
        if (pendingScanned.isEmpty()) return
        if (pendingFaces.isNotEmpty()) dataStore.setLong(CLUSTER_DIRTY, 1L)
        faceRepository.commitFaceScan(pendingScanned.toList(), pendingFaces.toList())
        pendingScanned.clear()
        pendingFaces.clear()
    }

    try {
        for (photo in photos) {
            currentCoroutineContext().ensureActive()
            // Face indexing runs two models per photo (detector + embedder), so
            // pace it like OCR/CLIP: a short pause after each photo we actually ran
            // inference on, plus a longer cooling break every batch.
            val outcome = scanPhotoFaces(context, photo)
            if (outcome.didInference) {
                decoded++
                if (outcome.faces.isNotEmpty()) {
                    photosWithFaces++
                    facesTotal += outcome.faces.size
                    Log.status(
                        "FaceWorker",
                        "photo ${photo.id}: ${outcome.faces.size} face(s) (running total: $facesTotal)",
                    )
                }
                pendingFaces += outcome.faces
                delay(FACE_INTER_ITEM_DELAY_MS)
                coolDownBetweenBatches(decoded, "FaceWorker")
            }

            // Mark scanned regardless of outcome so we don't retry forever.
            pendingScanned += photo.id
            if (pendingScanned.size >= INDEX_FLUSH_EVERY) flush()
        }
    } finally {
        withContext(NonCancellable) { runCatching { flush() } }
    }
    Log.status(
        "FaceWorker",
        "Face scan done: decoded=$decoded/${photos.size}, $facesTotal faces in $photosWithFaces photos",
    )

    if ((dataStore.getLong(CLUSTER_DIRTY) ?: 0L) != 0L) {
        regroupFaces(faceRepository)
        dataStore.setLong(CLUSTER_DIRTY, 0L)
    }
}

/** DataStore flag: stored faces changed since the last grouping pass. */
private const val CLUSTER_DIRTY = "face_cluster_dirty"

/**
 * Regroup every stored face from scratch and write the result atomically, keeping
 * person ids (and so names) where the new groups mostly match the old ones.
 */
private suspend fun regroupFaces(faceRepository: FaceRepository) {
    val started = System.nanoTime()
    val rows = faceRepository.getClusterRows()
    val nameOfPerson = faceRepository.getPersons().associate { it.id to it.name }
    val inputs = rows.map { row ->
        ClusterInput(
            faceId = row.id,
            embedding = FaceRecognizer.bytesToFloats(row.embedding),
            clusterable = row.clusterable,
            label = row.clusterId?.let { nameOfPerson[it] },
        )
    }
    val result = withContext(Dispatchers.Default) { clusterFaces(inputs) }
    val previous = rows.mapNotNull { row -> row.clusterId?.let { row.id to it } }.toMap()
    val identities = resolveIdentities(result.clusters, previous, nameOfPerson.keys)
    faceRepository.applyClustering(
        persons = identities.persons.map {
            ResolvedPersonRow(
                personId = it.personId,
                centroid = FaceRecognizer.floatsToBytes(it.cluster.centroid),
                faceIds = it.cluster.faceIds.toList(),
            )
        },
        deletedPersonIds = identities.deletedPersonIds,
    )
    Log.status(
        "FaceWorker",
        "Face grouping: ${rows.size} faces (${rows.count { it.clusterable }} clusterable) -> " +
            "${result.clusters.size} people, ${result.unassigned.size} unassigned, " +
            "${TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)} ms",
    )
}

/** Outcome of scanning one photo for faces: whether inference ran plus any face rows. */
private class FaceScanOutcome(val didInference: Boolean, val faces: List<PhotoFace>)

/**
 * Decode one photo and detect + embed its faces. Inference failures yield no faces
 * (the photo is still marked scanned by the caller). Faces are stored unassigned.
 */
private fun scanPhotoFaces(context: Context, photo: PhotoScanTarget): FaceScanOutcome {
    val bitmap = try {
        loadBitmapForFaces(context, photo.uri.toUri())
    } catch (e: IOException) {
        Log.error("FaceWorker", "Error scanning faces for photo ${photo.id}", e)
        null
    } catch (e: SecurityException) {
        Log.error("FaceWorker", "Error scanning faces for photo ${photo.id}", e)
        null
    } ?: return FaceScanOutcome(false, emptyList())
    // Read before recycle: the box columns are normalised against
    // this bitmap, so its dimensions have to be stored with them.
    val srcWidth = bitmap.width
    val srcHeight = bitmap.height
    val faces = try {
        FaceRecognizer.detectAndEmbed(context, bitmap)
    } catch (e: IllegalArgumentException) {
        Log.error("FaceWorker", "Error scanning faces for photo ${photo.id}", e)
        emptyList()
    } catch (e: IllegalStateException) {
        Log.error("FaceWorker", "Error scanning faces for photo ${photo.id}", e)
        emptyList()
    } finally {
        bitmap.recycle()
    }
    val rows = faces.map { face ->
        PhotoFace(
            photoId = photo.id,
            clusterId = null,
            embedding = FaceRecognizer.floatsToBytes(face.embedding),
            clusterable = face.clusterable,
            left = face.left,
            top = face.top,
            right = face.right,
            bottom = face.bottom,
            srcWidth = srcWidth,
            srcHeight = srcHeight,
        )
    }
    return FaceScanOutcome(true, rows)
}

private const val FACE_INTER_ITEM_DELAY_MS = 250L

/**
 * Faces are cropped from this decode, so it is larger than the detector's 640 input:
 * faces in group shots then clear [com.vayunmathur.photos.domain.FaceQuality.MIN_FACE_SIDE_PX]
 * and their aligned crops are sharper.
 */
private const val FACE_DECODE_LONG_SIDE = 1280

private fun loadBitmapForFaces(context: Context, uri: Uri): Bitmap? {
    return try {
        val source = ImageDecoder.createSource(context.contentResolver, uri)
        ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            decoder.isMutableRequired = false
            val maxDim = maxOf(info.size.width, info.size.height)
            val target = FACE_DECODE_LONG_SIDE
            if (maxDim > target) {
                val scale = target.toFloat() / maxDim
                decoder.setTargetSize(
                    (info.size.width * scale).toInt().coerceAtLeast(1),
                    (info.size.height * scale).toInt().coerceAtLeast(1),
                )
            }
        }
    } catch (e: IOException) {
        Log.error("FaceWorker", "Failed to decode $uri for faces", e)
        null
    } catch (e: SecurityException) {
        Log.error("FaceWorker", "Failed to decode $uri for faces", e)
        null
    }
}
