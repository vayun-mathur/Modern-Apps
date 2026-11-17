package com.vayunmathur.photos.domain

import android.content.Context
import android.database.sqlite.SQLiteException
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import com.vayunmathur.library.log.Log
import androidx.core.net.toUri
import com.vayunmathur.library.ocr.OcrEngine
import com.vayunmathur.photos.data.OcrBox
import com.vayunmathur.photos.data.OcrLayout
import com.vayunmathur.photos.data.Photo
import com.vayunmathur.photos.data.PhotoScanRepository
import com.vayunmathur.photos.data.parseOcrLayout
import com.vayunmathur.photos.data.toJson
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal const val OCR_DECODE_MAX = 1280
internal const val MIN_OCR_DIM = 64

/** Decode a downscaled software bitmap for OCR (long side capped at [OCR_DECODE_MAX]). */
internal fun decodeForOcr(context: Context, uri: Uri): Bitmap? {
    return try {
        val source = ImageDecoder.createSource(context.contentResolver, uri)
        ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            decoder.isMutableRequired = false
            val maxDim = maxOf(info.size.width, info.size.height)
            if (maxDim > OCR_DECODE_MAX) {
                val scale = OCR_DECODE_MAX.toFloat() / maxDim
                decoder.setTargetSize(
                    (info.size.width * scale).toInt().coerceAtLeast(1),
                    (info.size.height * scale).toInt().coerceAtLeast(1),
                )
            }
        }
    } catch (e: IOException) {
        Log.error(TAG, "Failed to decode $uri for OCR", e)
        null
    } catch (e: SecurityException) {
        Log.error(TAG, "Failed to decode $uri for OCR", e)
        null
    }
}

/**
 * Supplies the viewer with a photo's OCR geometry, reading [Photo.ocrBoxes] and
 * falling back to running OCR on the spot for rows written before that column
 * existed — writing the result back so the next open is instant.
 *
 * Runs on its own [OcrEngine] rather than sharing the indexing worker's, so an
 * in-progress library scan can't make the viewer wait on that engine's mutex.
 * The engine is created on first use and released once nothing is waiting.
 * In-flight work is memoised per photo id so fast-swiping (or a composition
 * being cancelled and restarted) can't run the same photo twice, and nothing
 * here throws: a missing model or an undecodable image just yields null.
 */
object OcrBoxStore {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Mutex()
    private val inFlight = mutableMapOf<Long, Deferred<OcrLayout?>>()

    suspend fun layoutFor(context: Context, photo: Photo): OcrLayout? {
        if (photo.videoData != null) return null
        // Only the application context is retained: the engine outlives the
        // composable that asked for it (see the write-back note below).
        val appContext = context.applicationContext
        val scanRepository = PhotoScanRepository.get(appContext)
        parseOcrLayout(scanRepository.getOcrBoxes(photo.id))?.let { return it }

        // Tiny images (icons/thumbnails) are skipped by the indexer too.
        val largestDim = maxOf(photo.width, photo.height)
        if (largestDim in 1 until MIN_OCR_DIM) return null

        // Runs in [scope], not the caller's, so a swipe that cancels the caller
        // still finishes the write-back — and the next open reads it from the DB.
        val job = lock.withLock {
            inFlight.getOrPut(photo.id) {
                scope.async {
                    try {
                        recognizeAndStore(appContext, photo, scanRepository)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: IOException) {
                        Log.error(TAG, "On-demand OCR failed for photo ${photo.id}", e)
                        null
                    } catch (e: SQLiteException) {
                        Log.error(TAG, "On-demand OCR failed for photo ${photo.id}", e)
                        null
                    } finally {
                        release(photo.id)
                    }
                }
            }
        }
        return job.await()
    }

    /** Forget a finished job; engines are per-call and already closed. */
    private suspend fun release(photoId: Long) = lock.withLock {
        inFlight -= photoId
    }

    private suspend fun recognizeAndStore(
        context: Context,
        photo: Photo,
        scanRepository: PhotoScanRepository,
    ): OcrLayout? {
        // A fresh engine per photo: retaining one in this singleton would
        // statically hold its Context (StaticFieldLeak). Creation is cheap
        // relative to decode + recognition, and close() frees the models.
        val ocr = OcrEngine(context)
        try {
            if (!ocr.isAvailable()) return null
            return recognizeWith(ocr, context, photo, scanRepository)
        } finally {
            ocr.close()
        }
    }

    private suspend fun recognizeWith(
        ocr: OcrEngine,
        context: Context,
        photo: Photo,
        scanRepository: PhotoScanRepository,
    ): OcrLayout? {
        val bitmap = decodeForOcr(context, photo.uri.toUri()) ?: return null
        // The stored geometry is relative to this bitmap, so its dimensions have
        // to be read before it's recycled.
        val width = bitmap.width
        val height = bitmap.height
        val result = try {
            ocr.recognizeDetailed(bitmap)
        } finally {
            bitmap.recycle()
        }
        // An empty result is stored too, so a photo with no text in it isn't
        // re-OCR'd every time it's opened.
        val layout = result.toLayout(width, height)
        scanRepository.setOcrBoxes(photo.id, layout.toJson())
        return layout
    }
}

/** Adapt an [OcrEngine.OcrResult] into the persisted [OcrLayout] shape. */
internal fun OcrEngine.OcrResult.toLayout(width: Int, height: Int) = OcrLayout(
    w = width,
    h = height,
    boxes = boxes.map { box ->
        OcrBox(
            text = box.text,
            left = box.left,
            top = box.top,
            right = box.right,
            bottom = box.bottom,
            quad = box.corners.flatMap { listOf(it.x, it.y) },
            vertical = box.vertical,
        )
    },
)

private const val TAG = "PhotoOcr"
