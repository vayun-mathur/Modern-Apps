package com.vayunmathur.photos.platform

import android.content.Context
import android.provider.MediaStore
import androidx.core.net.toUri
import androidx.exifinterface.media.ExifInterface
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.ListenableWorker.Result as WorkResult
import com.vayunmathur.photos.data.ExifResult
import com.vayunmathur.photos.data.PhotoScanRepository
import com.vayunmathur.photos.domain.PanoXmpParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext

/**
 * Trailing EXIF pass for triggered / foreground imports.
 *
 * A triggered sync upserts its rows first (visible in the gallery flow) and
 * enqueues this worker instead of running [setExifData] inline, so geo /
 * panorama enrichment lands on the already-visible rows shortly after —
 * without a large unscanned backlog delaying the new photo's worker.
 * Generation-complete syncs do NOT use this worker for their own pass:
 * they run EXIF inline ahead of the watermark write (see [exifRunsInline]).
 */
class ExifWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): WorkResult = withContext(Dispatchers.IO) {
        setForeground(createForegroundInfo())
        val scanRepository = PhotoScanRepository.get(applicationContext)
        setExifData(scanRepository, applicationContext)
        WorkResult.success()
    }

    private fun createForegroundInfo(): ForegroundInfo = applicationContext.syncForegroundInfo(
        notificationId = 105,
        channelId = "exif_worker",
        channelName = "Photo Sync",
        title = "Reading Photo Details",
        text = "Extracting locations and photo details...",
    )

    /** Shared constants and helpers for ExifWorker. */
    companion object {
        const val WORK_NAME = "ExifWorker"

        fun enqueue(context: Context) {
            val request = OneTimeWorkRequestBuilder<ExifWorker>().build()
            WorkManager.getInstance(context).enqueueUniqueWork(
                WORK_NAME,
                ExistingWorkPolicy.KEEP,
                request,
            )
        }
    }
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
