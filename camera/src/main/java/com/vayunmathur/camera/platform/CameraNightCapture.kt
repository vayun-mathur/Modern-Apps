package com.vayunmathur.camera.platform

import androidx.annotation.OptIn
import androidx.camera.camera2.interop.ExperimentalCamera2Interop

import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.Rect
import android.net.Uri
import com.vayunmathur.library.log.Log
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.extensions.ExtensionMode
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.lifecycle.awaitInstance
import androidx.camera.core.Preview
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewModelScope
import com.vayunmathur.camera.platform.lensSelector
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume
import kotlin.math.roundToInt
import com.vayunmathur.camera.data.MediaStoreSaver
import com.vayunmathur.camera.data.prepareStillSave
import com.vayunmathur.camera.data.saveProcessedStill
import com.vayunmathur.camera.data.saveStillBitmap
import com.vayunmathur.camera.data.setLastCaptureUri
import com.vayunmathur.camera.data.closeStream
import com.vayunmathur.camera.data.resolveUri
import com.vayunmathur.camera.data.setFlashMode

/**
 * On-demand NIGHT-extension capture: briefly rebinds a Preview + ImageCapture session with the
 * extension-enabled selector, takes one standard shot (the vendor handles the multi-frame night
 * merge internally), saves it, then restores the normal analysis-backed photo session so the
 * moon button keeps tracking brightness. The extension does its own timing, so no manual
 * long-exposure countdown here.
 */
@Suppress("DEPRECATION")
internal suspend fun CameraViewModel.captureNightPhotoExtension() {
    // Ownership snapshot for the finally-restore: this capture borrows the session from
    // the UI session effect. If the effect tore down / rebound while the shot was in
    // flight (mode switch, lens flip, backgrounding), it owns the current session and
    // restoring here would strand the new mode on a photo session.
    val startMode = cameraModeMutable.value
    val startTeardownCount = sessionTeardownCount
    isCapturingMutable.value = true
    // Drop the photo session so the UI's analyzer effect re-attaches PhotoAnalyzer once we restore.
    photoSessionActiveMutable.value = false
    try {
        val provider = ProcessCameraProvider.awaitInstance(app)
        cameraProvider = provider
        val mgr = getExtensionsManager(provider)
        if (mgr == null) {
            // Unreachable in practice: entry is gated by isNightExtensionAvailable(), which
            // already resolved the manager. Bail out; finally restores the normal session.
            Log.status("CameraViewModel", "ExtensionsManager missing at night capture; skipping")
            return
        }
        provider.unbindAll()
        imageAnalysis = null
        analysisStreamActiveMutable.value = false

        val baseSelector = lensSelector(lensFacingMutable.value, selectedLensMutable.value)
        val nightSelector = mgr.getExtensionEnabledCameraSelector(baseSelector, ExtensionMode.NIGHT)

        val preview = Preview.Builder().build()
        preview.setSurfaceProvider { request -> surfaceRequestMutable.value = request }

        val owner = ManualLifecycleOwner()
        owner.start()
        sessionLifecycleOwner = owner

        val capture = ImageCapture.Builder()
            .setFlashMode(getImageCaptureFlashMode())
            .build()
        imageCapture = capture
        capture.setScreenFlash(getScreenFlashForCapture())
        // Match the still crop the normal photo session uses (1:1 / 3:2 / 16:9 / 4:3), or
        // night-extension shots always save full-frame while the preview shows cropped.
        capture.setCropAspectRatio(currentCropAspectRatio())
        boundCamera = bindSession(provider, owner, nightSelector, preview, capture)

        val pending = prepareStillSave("IMG_${MediaStoreSaver.timestamp()}.jpg")
        if (pending == null) {
            Log.status("CameraViewModel", "Night extension capture skipped: no save target")
            return
        }
        val outputOptions = pending.outputOptions

        val savedUri = suspendCancellableCoroutine<Uri?> { cont ->
            capture.takePicture(
                outputOptions,
                ContextCompat.getMainExecutor(app),
                object : ImageCapture.OnImageSavedCallback {
                    override fun onImageSaved(outputFileResults: ImageCapture.OutputFileResults) {
                        pending.closeStream()
                        cont.resume(pending.resolveUri(outputFileResults))
                    }
                    override fun onError(exception: ImageCaptureException) {
                        Log.error("CameraViewModel", "Night extension capture failed", exception)
                        pending.closeStream()
                        cont.resume(null)
                    }
                }
            )
        }
        if (savedUri != null) setLastCaptureUri(savedUri)
    } catch (e: IllegalStateException) {
        Log.error("CameraViewModel", "Night extension capture path failed", e)
    } catch (e: IllegalArgumentException) {
        Log.error("CameraViewModel", "Night extension capture path failed", e)
    } finally {
        // Rebind the normal 3-stream session only when this capture still owns the
        // session (same PHOTO mode, no intervening teardown); otherwise the UI session
        // effect's fresh bind owns it and a blind setupPhotoSession() here would unbind
        // that session and leave the new mode with a dead photo preview.
        if (nightCaptureRestoreApplies(startMode, cameraModeMutable.value, startTeardownCount, sessionTeardownCount)) {
            setupPhotoSession()
        }
        isCapturingMutable.value = false
    }
}

/**
 * Whether [captureNightPhotoExtension]'s finally may restore the normal photo session:
 * only when the capture still owns the session — it started in PHOTO, the mode never
 * changed, and no teardown (mode switch / lens flip / lifecycle rebind) intervened.
 */
internal fun nightCaptureRestoreApplies(
    startMode: CameraMode,
    currentMode: CameraMode,
    startTeardownCount: Int,
    currentTeardownCount: Int
): Boolean =
    startMode == CameraMode.PHOTO && currentMode == startMode && currentTeardownCount == startTeardownCount

/** Frame-count progress label for the night burst (no fabricated duration estimate). */
internal fun nightCaptureProgressLabel(captured: Int, total: Int): String =
    if (total <= 0) "Capturing" else "Capturing ${captured.coerceIn(0, total)}/$total"

/** Progress-ring fraction for [captured] of [total] night frames. */
internal fun nightCaptureProgressFraction(captured: Int, total: Int): Float =
    if (total <= 0) 0f else captured.coerceIn(0, total).toFloat() / total.toFloat()

internal const val NIGHT_MERGING_LABEL = "Merging…"

/**
 * Multi-frame night capture: locks the sensor to a per-frame night exposure/ISO, collects a
 * burst off the ImageAnalysis stream, then aligns + merges + brightens it (via
 * [NightCaptureEngine]) and saves. Falls back to the single long-exposure capture if the burst
 * is empty or the merge fails, so the user always gets a shot.
 */
internal fun CameraViewModel.captureNightPhotoCustom() {
    isCapturingMutable.value = true
    val perFrame = computeNightExposure(CameraViewModel.NIGHT_BURST_PER_FRAME_NANOS)
    // Honest progress: frames are sequential full stills of unknown duration, so the
    // overlay tracks frames actually captured (then merging), not perFrame * count.
    showNightCaptureProgress(0)

    captureNightBurst(perFrame, { captured -> showNightCaptureProgress(captured) }, ::onNightBurstDone)
}

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
    // AVIF stills carry no EXIF (orientation is in the pixels); only stamp JPEG.
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
@Suppress("DEPRECATION")
@OptIn(ExperimentalCamera2Interop::class)
internal fun CameraViewModel.captureNightBurst(
    exposure: NightExposure,
    onFrameCaptured: (Int) -> Unit = {},
    onDone: (List<Bitmap>) -> Unit
) {
    if (imageCapture == null) {
        onDone(emptyList())
        return
    }
    val cam2Control = camera2ControlQuietly()
    val driver = NightBurstDriver(
        viewModel = this,
        cam2Control = cam2Control,
        mirror = mirrorCaptures,
        exposure = exposure,
        onFrameCaptured = onFrameCaptured,
        onDone = onDone
    )
    driver.start()
}

/** Camera2 control handle; null (logged) when unavailable. */
@Suppress("DEPRECATION")
@OptIn(ExperimentalCamera2Interop::class)
internal fun CameraViewModel.camera2ControlQuietly():
    androidx.camera.camera2.interop.Camera2CameraControl? {
    return try {
        boundCamera?.cameraControl?.let {
            androidx.camera.camera2.interop.Camera2CameraControl.from(it)
        }
    } catch (e: IllegalArgumentException) {
        Log.status("CameraViewModel", "Camera2 control unavailable", e)
        null
    } catch (e: NullPointerException) {
        Log.status("CameraViewModel", "Camera2 control unavailable", e)
        null
    }
}

/**
 * Owns one night-burst sequence: locks the night exposure, fires full-resolution
 * captures back-to-back, converts each to an upright bitmap and restores 3A + the
 * manual-control state when done.
 */
@Suppress("DEPRECATION")
@OptIn(ExperimentalCamera2Interop::class)
private class NightBurstDriver(
    private val viewModel: CameraViewModel,
    private val cam2Control: androidx.camera.camera2.interop.Camera2CameraControl?,
    private val mirror: Boolean,
    private val exposure: NightExposure,
    private val onFrameCaptured: (Int) -> Unit = {},
    private val onDone: (List<Bitmap>) -> Unit
) {
    private val collected = mutableListOf<Bitmap>()
    private var alreadyDone = false
    // Captures attempted (successes + failures). Failures skip a frame, but
    // without this bound a persistently failing capture would retry forever
    // and the burst (and its locked exposure) would never finish.
    private var attempted = 0

    fun start() {
        if (cam2Control != null) {
            viewModel.lockNightExposure(cam2Control, exposure, ::takeNext)
        } else {
            takeNext()
        }
    }

    private fun restore3A() {
        if (cam2Control != null) {
            try {
                cam2Control.setCaptureRequestOptions(
                    androidx.camera.camera2.interop.CaptureRequestOptions.Builder()
                        .clearCaptureRequestOption(android.hardware.camera2.CaptureRequest.SENSOR_EXPOSURE_TIME)
                        .clearCaptureRequestOption(android.hardware.camera2.CaptureRequest.SENSOR_SENSITIVITY)
                        .clearCaptureRequestOption(android.hardware.camera2.CaptureRequest.CONTROL_AE_MODE)
                        .clearCaptureRequestOption(android.hardware.camera2.CaptureRequest.CONTROL_AWB_LOCK)
                        .build()
                )
            } catch (e: IllegalStateException) {
                Log.status("CameraViewModel", "Failed to restore auto 3A after night burst", e)
            } catch (e: IllegalArgumentException) {
                Log.status("CameraViewModel", "Failed to restore auto 3A after night burst", e)
            }
        }
    }

    private fun finish() {
        if (alreadyDone) return
        alreadyDone = true
        restore3A()
        // Re-assert (auto) manual-control state to fully undo night override.
        try {
            viewModel.applyManualControls()
        } catch (_: Exception) {}
        onDone(collected.toList())
    }

    private fun takeNext() {
        if (attempted >= NightCaptureEngine.NIGHT_BURST_COUNT) {
            finish()
            return
        }
        attempted++
        val cap = viewModel.imageCapture ?: run {
            finish()
            return
        }
        cap.takePicture(
            ContextCompat.getMainExecutor(viewModel.app),
            object : ImageCapture.OnImageCapturedCallback() {
                override fun onCaptureSuccess(image: ImageProxy) {
                    if (convertFrame(image)) onFrameCaptured(collected.size)
                    takeNext()
                }

                override fun onError(exception: ImageCaptureException) {
                    Log.status("CameraViewModel", "Night frame capture failed, continuing", exception)
                    takeNext()
                }
            }
        )
    }

    /** Converts one burst frame to an upright bitmap and collects it; true if collected. */
    private fun convertFrame(image: ImageProxy): Boolean {
        return try {
            // toBitmap() is provided by CameraX (used also in BokehAnalyzer).
            // Apply the session's cropRect (setCropAspectRatio): the raw frame is
            // always full-frame, so without this the burst ignores the non-4:3 crop.
            val cropRect = android.graphics.Rect(image.cropRect)
            val raw = viewModel.cropToRect(image.toBitmap(), cropRect)
            val matrix = Matrix().apply {
                postRotate(image.imageInfo.rotationDegrees.toFloat())
                if (mirror) postScale(-1f, 1f)
            }
            val upright = Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height, matrix, true)
            if (upright !== raw) raw.recycle()
            collected.add(upright)
            true
        } catch (e: IllegalStateException) {
            Log.status("CameraViewModel", "Failed to convert night frame", e)
            false
        } catch (e: IllegalArgumentException) {
            Log.status("CameraViewModel", "Failed to convert night frame", e)
            false
        } catch (e: NullPointerException) {
            Log.status("CameraViewModel", "Failed to convert night frame", e)
            false
        } catch (e: OutOfMemoryError) {
            // Skip this frame under memory pressure; the burst can still merge fewer frames.
            Log.status("CameraViewModel", "Night frame conversion OOM; skipping frame", e)
            false
        } finally {
            image.close()
        }
    }
}

/** Locks AE off + night exposure/ISO + AWB, then runs [onLocked]. Falls through on failure. */
@Suppress("DEPRECATION")
@OptIn(ExperimentalCamera2Interop::class)
internal fun CameraViewModel.lockNightExposure(
    cam2Control: androidx.camera.camera2.interop.Camera2CameraControl,
    exposure: NightExposure,
    onLocked: () -> Unit
) {
    try {
        val optsBuilder = androidx.camera.camera2.interop.CaptureRequestOptions.Builder()
            .setCaptureRequestOption(
                android.hardware.camera2.CaptureRequest.CONTROL_AE_MODE,
                android.hardware.camera2.CameraMetadata.CONTROL_AE_MODE_OFF
            )
            .setCaptureRequestOption(
                android.hardware.camera2.CaptureRequest.SENSOR_EXPOSURE_TIME,
                exposure.nanos
            )
            .setCaptureRequestOption(
                android.hardware.camera2.CaptureRequest.CONTROL_AWB_LOCK, true
            )
        exposure.iso?.let {
            optsBuilder.setCaptureRequestOption(
                android.hardware.camera2.CaptureRequest.SENSOR_SENSITIVITY, it
            )
        }
        cam2Control.setCaptureRequestOptions(optsBuilder.build())
            .addListener({ onLocked() }, ContextCompat.getMainExecutor(app))
    } catch (e: IllegalStateException) {
        Log.status("CameraViewModel", "Failed to set night exposure for burst", e)
        onLocked()
    } catch (e: IllegalArgumentException) {
        Log.status("CameraViewModel", "Failed to set night exposure for burst", e)
        onLocked()
    }
}

internal data class NightExposure(val nanos: Long, val iso: Int?)

/**
 * Derives a night exposure/ISO from the bound sensor's characteristics: clamps [targetNanos]
 * into the sensor's exposure-time range and picks a high fraction of its sensitivity range.
 * Falls back to [targetNanos] (and auto ISO) if the characteristics are unavailable.
 */
@Suppress("DEPRECATION")
@OptIn(ExperimentalCamera2Interop::class)
internal fun CameraViewModel.computeNightExposure(
    targetNanos: Long = CameraViewModel.NIGHT_TARGET_EXPOSURE_NANOS
): NightExposure {
    val fallback = NightExposure(targetNanos, null)
    return try {
        val cam = boundCamera ?: return fallback
        val info = androidx.camera.camera2.interop.Camera2CameraInfo.from(cam.cameraInfo)
        val expRange = info.getCameraCharacteristic(
            android.hardware.camera2.CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE
        )
        val isoRange = info.getCameraCharacteristic(
            android.hardware.camera2.CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE
        )
        val nanos = expRange?.let {
            targetNanos.coerceIn(it.lower, it.upper)
        } ?: targetNanos
        val iso = isoRange?.let {
            (it.lower + ((it.upper - it.lower) * CameraViewModel.NIGHT_ISO_FRACTION).roundToInt())
                .coerceIn(it.lower, it.upper)
        }
        NightExposure(nanos, iso)
    } catch (e: IllegalArgumentException) {
        Log.status("CameraViewModel", "Failed to read sensor ranges for night mode", e)
        fallback
    }
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
