package com.vayunmathur.camera.platform

import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.Rect
import android.net.Uri
import com.vayunmathur.library.log.Log
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt
import com.vayunmathur.camera.data.MediaStoreSaver
import com.vayunmathur.camera.data.saveProcessedStill
import com.vayunmathur.camera.data.saveStillBitmap
import com.vayunmathur.camera.data.saveStillBytes
import com.vayunmathur.camera.data.setLastCaptureUri
import com.vayunmathur.camera.data.setFlashMode

internal fun CameraViewModel.captureNightPhotoCustom() {
    isCapturingMutable.value = true
    val perFrame = computeNightExposure(CameraViewModel.NIGHT_BURST_PER_FRAME_NANOS)
    // Honest progress: frames are sequential full stills of unknown duration, so the
    // overlay tracks frames actually captured (then merging), not perFrame * count.
    showNightCaptureProgress(0)

    captureNightBurst(perFrame, { captured -> showNightCaptureProgress(captured) }, ::onNightBurstDone)
}

/** Frame-count progress label for the night burst (no fabricated duration estimate). */
internal fun nightCaptureProgressLabel(captured: Int, total: Int): String =
    if (total <= 0) "Capturing" else "Capturing ${captured.coerceIn(0, total)}/$total"

/** Progress-ring fraction for [captured] of [total] night frames. */
internal fun nightCaptureProgressFraction(captured: Int, total: Int): Float =
    if (total <= 0) 0f else captured.coerceIn(0, total).toFloat() / total.toFloat()

internal const val NIGHT_MERGING_LABEL = "Merging…"

/** Publishes frame-count progress to the existing long-exposure overlay flows. */
internal fun CameraViewModel.showNightCaptureProgress(captured: Int) {
    longExposureTimerJob?.cancel()
    longExposureTimerJob = null
    longExposureProgressMutable.value =
        nightCaptureProgressFraction(captured, NightCaptureEngine.NIGHT_BURST_COUNT)
    longExposureRemainingMutable.value =
        nightCaptureProgressLabel(captured, NightCaptureEngine.NIGHT_BURST_COUNT)
}

/** Publishes the merging state to the existing long-exposure overlay flows. */
internal fun CameraViewModel.showNightMerging() {
    longExposureTimerJob?.cancel()
    longExposureTimerJob = null
    longExposureProgressMutable.value = 1f
    longExposureRemainingMutable.value = NIGHT_MERGING_LABEL
}

/** Handles the finished night burst: merges + saves, or falls back to single capture. */
internal fun CameraViewModel.onNightBurstDone(frames: List<Bitmap>) {
    if (frames.isEmpty()) {
        Log.status("CameraViewModel", "Night burst produced no frames; falling back to single capture")
        stopLongExposureCountdown()
        captureSinglePhoto()
        return
    }
    viewModelScope.launch {
        showNightMerging()
        val uri = withContext(Dispatchers.Default) {
            mergeNightFrames(frames)
        }
        if (uri != null) {
            finishNightMerge(uri)
        } else {
            Log.status("CameraViewModel", "Night merge failed; falling back to single capture")
            stopLongExposureCountdown()
            captureSinglePhoto()
        }
    }
}

/** Completes a successful night merge: stamps EXIF, clears state, publishes the URI. */
internal suspend fun CameraViewModel.finishNightMerge(uri: Uri) {
    // Merged pixels are already upright/mirrored, so the orientation tag is normal.
    // AVIF stills already carry their Exif item from saveProcessedStill;
    // only JPEG needs the file-level stamp here.
    val isAvif = runCatching { app.contentResolver.getType(uri) }.getOrNull() == "image/avif"
    if (!isAvif) {
        withContext(Dispatchers.IO) { writeCaptureExif(uri, null, 0) }
    }
    isCapturingMutable.value = false
    stopLongExposureCountdown()
    setLastCaptureUri(uri)
}

/**
 * Merges the burst via the native engine and saves the bitmap; null on failure.
 * Hard failure path: OOM / native / allocation failure (any [Throwable] except
 * cancellation) falls back to saving the middle burst frame, so the user still
 * gets a shot instead of losing the whole burst.
 */
internal suspend fun CameraViewModel.mergeNightFrames(frames: List<Bitmap>): Uri? {
    val merged = try {
        NightCaptureEngine.merge(frames)
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (e: Throwable) {
        Log.status("CameraViewModel", "Night merge threw; using middle-frame fallback", e)
        null
    }
    if (merged != null) {
        frames.forEach { it.recycle() }
        // Merged pixels are already upright, so rotation 0: AVIF encodes
        // them as-is, JPEG keeps the EXIF path in finishNightMerge.
        val saved = saveProcessedStill(merged, 0)
        merged.recycle()
        return saved?.uri
    }
    // Middle-frame fallback (frames are still owned here; save before recycling).
    val middle = frames.getOrNull(frames.size / 2)
    val fallbackUri = middle?.let {
        try {
            saveProcessedStill(it, 0)?.uri
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Throwable) {
            Log.status("CameraViewModel", "Night middle-frame fallback save failed", e)
            null
        }
    }
    frames.forEach { it.recycle() }
    return fallbackUri
}

/**
 * Proper night burst: full-res capture via [ImageCapture] instead of low-res
 * [ImageAnalysis]. Locks 3A to [exposure], then fires [NightCaptureEngine.NIGHT_BURST_COUNT]
 * full-resolution in-memory captures, each converted to upright/consistent bitmap.
 * This avoids the previous path's analysis-resolution limitation and double JPEG
 * loss (now passed lossless RGBA to Rust via [StitchNative.newNightSession]).
 *
 * Falls back to empty list if [imageCapture] is unavailable, which makes
 * [captureNightPhotoCustom] fall back to single-frame long-exposure.
 */
internal fun CameraViewModel.captureNightBurst(
    exposure: NightExposure,
    onFrameCaptured: (Int) -> Unit = {},
    onDone: (List<Bitmap>) -> Unit
) {
    val session = activeCamera2Session ?: run {
        onDone(emptyList())
        return
    }
    // Lock the night exposure on the session (preview + stills inherit
    // it through the control state; AE locks off in the still planner
    // whenever a manual pair is present).
    session.setManualExposure(exposure.nanos, exposure.iso)
    val collected = mutableListOf<Bitmap>()
    var attempted = 0
    var finished = false
    fun finish() {
        if (finished) return
        finished = true
        // Restore AE + the user's manual-control state.
        session.setManualExposure(null, null)
        try {
            applyManualControls()
        } catch (_: Exception) {}
        onDone(collected.toList())
    }
    fun takeNext() {
        if (attempted >= NightCaptureEngine.NIGHT_BURST_COUNT) {
            finish()
            return
        }
        attempted++
        session.capture(
            mirrored = mirrorCaptures,
            callback = object : com.vayunmathur.camera.platform.camera2.Camera2Session.StillCallback {
                override fun onStillCaptured(still: com.vayunmathur.camera.platform.camera2.CapturedStill) {
                    val bmp = decodeNightFrame(still)
                    if (bmp != null) {
                        collected.add(bmp)
                        onFrameCaptured(collected.size)
                    }
                    takeNext()
                }

                override fun onStillFailed(message: String) {
                    Log.status("CameraViewModel", "Night frame capture failed, continuing: $message")
                    takeNext()
                }
            },
        )
    }
    takeNext()
}

/** Decodes one burst still to an upright bitmap; null when undecodable. */
private fun decodeNightFrame(
    still: com.vayunmathur.camera.platform.camera2.CapturedStill,
): Bitmap? {
    return try {
        val raw = android.graphics.BitmapFactory.decodeByteArray(still.jpegBytes, 0, still.jpegBytes.size)
            ?: return null
        val matrix = android.graphics.Matrix().apply {
            postRotate(still.metadata.rotationDegrees.toFloat())
            if (still.metadata.mirrored) postScale(-1f, 1f)
        }
        val upright = Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height, matrix, true)
        if (upright !== raw) raw.recycle()
        upright
    } catch (e: IllegalStateException) {
        Log.status("CameraViewModel", "Failed to decode night frame", e)
        null
    } catch (e: IllegalArgumentException) {
        Log.status("CameraViewModel", "Failed to decode night frame", e)
        null
    } catch (e: OutOfMemoryError) {
        // Skip this frame under memory pressure; the burst can still merge fewer frames.
        Log.status("CameraViewModel", "Night frame decode OOM; skipping frame", e)
        null
    }
}

internal data class NightExposure(val nanos: Long, val iso: Int?)

/**
 * Derives a night exposure/ISO from the bound sensor's characteristics: clamps [targetNanos]
 * into the sensor's exposure-time range and picks a high fraction of its sensitivity range.
 * Falls back to [targetNanos] (and auto ISO) if the characteristics are unavailable.
 */
internal fun CameraViewModel.computeNightExposure(
    targetNanos: Long = CameraViewModel.NIGHT_TARGET_EXPOSURE_NANOS
): NightExposure {
    val fallback = NightExposure(targetNanos, null)
    val snapshot = activeCamera2Session?.characteristics ?: return fallback
    val expRange = snapshot.exposureTimeRangeNs
    val isoRange = snapshot.sensitivityRange
    val nanos = expRange?.let {
        targetNanos.coerceIn(it.lower, it.upper)
    } ?: targetNanos
    val iso = isoRange?.let {
        (it.lower + ((it.upper - it.lower) * CameraViewModel.NIGHT_ISO_FRACTION).roundToInt())
            .coerceIn(it.lower, it.upper)
    }
    return NightExposure(nanos, iso)
}

/** Nanos per millisecond; countdown tick step. */
private const val NANOS_PER_MILLI = 1_000_000L
private const val LONG_EXPOSURE_TICK_MS = 50L

internal fun CameraViewModel.startLongExposureCountdown(nanos: Long) {
    val durationMs = nanos / NANOS_PER_MILLI
    longExposureProgressMutable.value = 1f
    longExposureRemainingMutable.value = formatExposureRemaining(durationMs)
    longExposureTimerJob?.cancel()
    longExposureTimerJob = viewModelScope.launch {
        val start = System.currentTimeMillis()
        while (true) {
            val elapsed = System.currentTimeMillis() - start
            val remaining = (durationMs - elapsed).coerceAtLeast(0)
            longExposureProgressMutable.value = remaining.toFloat() / durationMs
            longExposureRemainingMutable.value = formatExposureRemaining(remaining)
            if (remaining <= 0) break
            delay(LONG_EXPOSURE_TICK_MS)
        }
    }
}

internal fun CameraViewModel.stopLongExposureCountdown() {
    longExposureTimerJob?.cancel()
    longExposureTimerJob = null
    longExposureProgressMutable.value = 0f
    longExposureRemainingMutable.value = ""
}

private fun formatExposureRemaining(ms: Long): String {
    val seconds = ms / 1000f
    return "%.1fs".format(seconds)
}
