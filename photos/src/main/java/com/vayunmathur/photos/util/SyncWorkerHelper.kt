package com.vayunmathur.photos.util

import android.content.Context
import com.vayunmathur.library.log.Log
import androidx.core.net.toUri
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.ListenableWorker.Result as WorkResult
import com.vayunmathur.library.util.DataStoreUtils
import com.vayunmathur.photos.data.ClipResult
import com.vayunmathur.photos.data.PhotoScanRepository
import com.vayunmathur.photos.data.PhotoScanTarget
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Clip Worker: a context implementation for clip worker. */
class ClipWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): WorkResult = withContext(Dispatchers.IO) {
        clipMutex.withLock {
            setForeground(createForegroundInfo())
            val scanRepository = PhotoScanRepository.get(applicationContext)
            runClipIndexing(scanRepository, applicationContext)
            WorkResult.success()
        }
    }

    private fun createForegroundInfo(): ForegroundInfo = applicationContext.syncForegroundInfo(
        notificationId = 104,
        channelId = "clip_worker",
        channelName = "Photo Search Indexing",
        title = "Understanding Photos",
        text = "Indexing photos for visual search on-device...",
    )

    /** Shared constants and helpers for ClipWorker. */
    companion object {
        const val WORK_NAME = "ClipWorker"
        private val clipMutex = Mutex()

        fun enqueue(context: Context) {
            val request = OneTimeWorkRequestBuilder<ClipWorker>().build()
            WorkManager.getInstance(context).enqueueUniqueWork(
                WORK_NAME,
                ExistingWorkPolicy.KEEP,
                request,
            )
        }
    }
}

/**
 * Embed any not-yet-embedded library photos into a TinyCLIP vector — served by
 * the downloaded ET pair (see [ClipEmbedder]) — and store the L2-normalised
 * vector on the [Photo] row so semantic search can cosine-compare them against
 * the query's text embedding.
 *
 * Mirrors [runOCR]: incremental (only un-embedded, non-video photos), one image
 * at a time, throttled between items to keep battery/CPU low, and marks each
 * photo scanned regardless of per-image outcome so we never retry it forever.
 *
 * Gated on the ET pair being available ([ClipEmbedder.embeddingSupport]): if it cannot be opened
 * (pair not downloaded), skip indexing and leave `clipScanned=0` so OCR/filename search still works.
 */
suspend fun runClipIndexing(scanRepository: PhotoScanRepository, context: Context) = coroutineScope {
    val dataStore = DataStoreUtils.getInstance(context)

    // The ET pair is a runtime download, so this fails until the download completes.
    if (ClipEmbedder.embeddingSupport(context) != ClipEmbedder.Support.READY) {
        Log.status("ClipWorker", "TinyCLIP embedder unavailable; skipping semantic indexing")
        return@coroutineScope
    }

    // If the embedder version OR the model id changed, old embeddings are incompatible (e.g.
    // OpenAssistant's 768-d SigLIP2 space -> TinyCLIP's 512-d): clear them and re-embed every
    // photo. Photo rows themselves (OCR text, faces) are untouched.
    val modelId = ClipEmbedder.embeddingInfo(context).modelId
    val storedVersion = dataStore.getLong("clip_embedder_version") ?: 0L
    val storedModelId = dataStore.getString("clip_model_id")
    if (storedVersion != ClipEmbedder.EMBEDDER_VERSION.toLong() || storedModelId != modelId) {
        scanRepository.resetClipScanned()
        dataStore.setLong("clip_embedder_version", ClipEmbedder.EMBEDDER_VERSION.toLong())
        dataStore.setString("clip_model_id", modelId)
    }

    val photos = scanRepository.getUnscannedForClip()
    if (photos.isEmpty()) return@coroutineScope

    // See INDEX_FLUSH_EVERY: batched so a scan doesn't invalidate the Photo
    // table once per embedded photo.
    val pending = mutableListOf<ClipResult>()
    suspend fun flush() {
        if (pending.isEmpty()) return
        scanRepository.setClipResults(pending.toList())
        pending.clear()
    }

    var processed = 0
    try {
        for (photo in photos) {
            ensureActive()

            val t0 = System.currentTimeMillis()
            val embedding = pending.embedOnePhoto(context, photo) { flush() }
                ?: continue
            val elapsedMs = System.currentTimeMillis() - t0
            Log.dev("ClipWorker", "Embedded photo ${photo.id} (${embedding.size}d) in ${elapsedMs}ms")
            pending += ClipResult(id = photo.id, embedding = ClipEmbedder.floatsToBytes(embedding))
            if (pending.size >= INDEX_FLUSH_EVERY) flush()

            // Short pause between images keeps sustained CPU/battery use low.
            delay(CLIP_INTER_ITEM_DELAY_MS)
            // Longer cooling break every batch so the device can shed heat.
            processed++
            coolDownBetweenBatches(processed, "ClipWorker")
        }
    } catch (_: StopIndexing) {
        // Systemic failure (model failed to load): photos stay unscanned and
        // retry on the next pass; the run still completes normally.
        Unit
    } finally {
        // Covers the systemic-failure return above as well as cancellation, so
        // photos already embedded in this run are never re-embedded.
        withContext(NonCancellable) { runCatching { flush() } }
    }
}

/**
 * Embed one photo for the indexing run. Returns the vector, or null when the
 * photo was skipped (undecodable image: marked scanned with no vector, plus
 * the inter-item delay, so the caller just continues). Systemic failures
 * throw [StopIndexing] so the run aborts WITHOUT marking scanned.
 */
private suspend fun MutableList<ClipResult>.embedOnePhoto(
    context: Context,
    photo: PhotoScanTarget,
    flush: suspend () -> Unit,
): FloatArray? = try {
    ClipEmbedder.imageEmbedding(context, photo.uri.toUri())
} catch (e: ClipEmbedder.ImageFailedException) {
    skipUnembeddablePhoto(photo, flush, e.message)
    null
} catch (e: CancellationException) {
    throw e
} catch (e: IllegalStateException) {
    pauseIndexing(e)
} catch (e: IOException) {
    pauseIndexing(e)
}

/** Queue a null vector for an undecodable image so it is marked scanned and skipped. */
private suspend fun MutableList<ClipResult>.skipUnembeddablePhoto(
    photo: PhotoScanTarget,
    flush: suspend () -> Unit,
    message: String?,
) {
    // The embedder is healthy but this one image couldn't be decoded: mark it scanned
    // with no vector so we skip it rather than blocking the queue on it forever.
    Log.status("ClipWorker", "Skipping un-embeddable photo ${photo.id}: $message")
    this += ClipResult(id = photo.id, embedding = null)
    if (size >= INDEX_FLUSH_EVERY) flush()
    delay(CLIP_INTER_ITEM_DELAY_MS)
}

/** Stop the run WITHOUT marking scanned so these photos retry on the next pass. */
private fun pauseIndexing(e: Exception): Nothing {
    Log.status("ClipWorker", "Embedding unavailable; pausing indexing", e)
    throw StopIndexing()
}

/** Signals a systemic embedding failure: abort the run, retry next pass. */
private class StopIndexing : CancellationException("CLIP indexing paused")

private const val CLIP_INTER_ITEM_DELAY_MS = 250L
