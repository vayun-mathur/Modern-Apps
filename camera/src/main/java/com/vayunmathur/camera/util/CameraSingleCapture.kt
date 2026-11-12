package com.vayunmathur.camera.util

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Rect
import android.net.Uri
import androidx.annotation.OptIn
import com.vayunmathur.library.log.Log
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal fun CameraViewModel.captureSinglePhoto() {
    val capture = imageCapture ?: return
    isCapturingMutable.value = true
    val fileName = "IMG_${MediaStoreSaver.timestamp()}.jpg"
    val pending = prepareStillSave(fileName) ?: run {
        isCapturingMutable.value = false
        return
    }
    val plan = buildSingleCapturePlan(pending) ?: run {
        isCapturingMutable.value = false
        return
    }
    if (plan.nightExposure != null && plan.cam2Control != null) {
        applyNightExposureThen(plan, ::runSingleCapture)
    } else {
        runSingleCapture(plan)
    }
}

/** Everything doCapture/runSingleCapture needs, resolved up front. */
@Suppress("DEPRECATION")
@OptIn(ExperimentalCamera2Interop::class)
private data class SingleCapturePlan(
    val capture: ImageCapture,
    val pending: PendingStill,
    val outputOptions: ImageCapture.OutputFileOptions,
    val exposureNanos: Long?,
    val nightIso: Int?,
    val cam2Control: androidx.camera.camera2.interop.Camera2CameraControl?,
    val nightExposure: NightExposure?,
    val warmth: Float,
    val shadows: Float,
    val mirror: Boolean,
    val bokeh: Boolean,
    val strength: Float
)

/**
 * Resolves the capture use cases, night override and preview-effect state.
 * Null when there is no save target (caller clears the in-flight flag).
 */
@Suppress("DEPRECATION")
@OptIn(ExperimentalCamera2Interop::class)
private fun CameraViewModel.buildSingleCapturePlan(pending: PendingStill): SingleCapturePlan? {
    val capture = imageCapture ?: return null
    val outputOptions = pending.outputOptions

    val stop = CameraViewModel.EXPOSURE_TIME_STOPS[exposureTimeIndexMutable.value]
    // Manual shutter/ISO are already applied live via applyManualControls(); the only transient
    // per-capture override here is the night-mode emulation (fully-auto exposure + night active).
    // Skip it when the vendor NIGHT extension preview is bound: that session runs its own
    // multi-frame AE and rejects Camera2-interop AE_MODE_OFF/manual-exposure options, which makes
    // the capture fail. In that case a plain takePicture() lets the extension produce the shot.
    // Also skip it when flash is on (issue #721) or the ImageCapture is ZSL (#914); see
    // nightCaptureApplies(). The capturePhoto() router already keeps those shots on the plain
    // path; this guard covers any direct captureSinglePhoto() entry (e.g. night-burst fallback).
    val nightExposure = if (nightCaptureApplies() && !nightPreviewActiveMutable.value) {
        computeNightExposure()
    } else {
        null
    }
    // Used only to drive the long-exposure countdown overlay.
    val exposureNanos = stop.nanos ?: nightExposure?.nanos
    val cam2Control = try {
        boundCamera?.cameraControl?.let {
            androidx.camera.camera2.interop.Camera2CameraControl.from(it)
        }
    } catch (e: IllegalArgumentException) {
        Log.status("CameraViewModel", "Camera2 control unavailable", e)
        null
    }
    return SingleCapturePlan(
        capture = capture,
        pending = pending,
        outputOptions = outputOptions,
        exposureNanos = exposureNanos,
        nightIso = nightExposure?.iso,
        cam2Control = cam2Control,
        nightExposure = nightExposure,
        warmth = warmthMutable.value,
        shadows = shadowsMutable.value,
        mirror = mirrorCaptures,
        bokeh = cameraModeMutable.value == CameraMode.PORTRAIT,
        strength = blurStrengthMutable.value
    )
}

/** Applies the transient night exposure, then runs the capture (or the capture directly). */
@Suppress("DEPRECATION")
@OptIn(ExperimentalCamera2Interop::class)
private fun CameraViewModel.applyNightExposureThen(plan: SingleCapturePlan, doCapture: (SingleCapturePlan) -> Unit) {
    try {
        val options = androidx.camera.camera2.interop.CaptureRequestOptions.Builder()
            .setCaptureRequestOption(
                android.hardware.camera2.CaptureRequest.CONTROL_AE_MODE,
                android.hardware.camera2.CameraMetadata.CONTROL_AE_MODE_OFF
            )
            .setCaptureRequestOption(
                android.hardware.camera2.CaptureRequest.SENSOR_EXPOSURE_TIME,
                plan.nightExposure!!.nanos
            )
        if (plan.nightIso != null) {
            options.setCaptureRequestOption(
                android.hardware.camera2.CaptureRequest.SENSOR_SENSITIVITY,
                plan.nightIso
            )
        }
        plan.cam2Control!!.setCaptureRequestOptions(options.build())
            .addListener({ doCapture(plan) }, ContextCompat.getMainExecutor(app))
    } catch (e: IllegalStateException) {
        Log.status("CameraViewModel", "Failed to set night exposure", e)
        doCapture(plan)
    } catch (e: IllegalArgumentException) {
        Log.status("CameraViewModel", "Failed to set night exposure", e)
        doCapture(plan)
    }
}

/** Runs the single capture described by [plan] (night override already applied). */
private fun CameraViewModel.runSingleCapture(plan: SingleCapturePlan) {
    if (plan.exposureNanos != null && plan.exposureNanos >= CameraViewModel.NIGHT_TARGET_EXPOSURE_NANOS) {
        startLongExposureCountdown(plan.exposureNanos)
    }

    fun restoreAfterNight() {
        // Undo the transient night override by re-asserting the (auto) manual-control state.
        if (plan.nightExposure != null) applyManualControls()
    }

    fun finishCapture(uri: Uri?) {
        isCapturingMutable.value = false
        stopLongExposureCountdown()
        restoreAfterNight()
        plan.pending.closeStream()
        if (uri != null) setLastCaptureUri(uri)
    }

    if (plan.bokeh || plan.warmth != 0f || plan.shadows != 0f) {
        captureProcessedSingle(plan, ::finishCapture)
        return
    }

    plan.capture.takePicture(
        plan.outputOptions,
        ContextCompat.getMainExecutor(app),
        object : ImageCapture.OnImageSavedCallback {
            override fun onCaptureStarted() {
                emitShutterFeedback()
            }
            override fun onImageSaved(outputFileResults: ImageCapture.OutputFileResults) {
                finishCapture(plan.pending.resolveUri(outputFileResults))
            }
            override fun onError(exception: ImageCaptureException) {
                finishCapture(null)
            }
        }
    )
}

/**
 * In-memory capture with the preview's bokeh/colour baked in (plus EXIF copied back),
 * since the warmth/shadows adjustment and portrait bokeh only live in the preview
 * RenderEffect. Re-encoding drops the JPEG's EXIF, so it is copied back from the
 * original frame (plus GPS and the orientation tag) to match the normal path.
 * Caveat: this processed path is always SDR JPEG — decoding to an ARGB_8888 bitmap
 * discards any Ultra HDR gain map, so processed captures lose HDR even when
 * Ultra HDR is otherwise active. Normal (unprocessed) captures keep the gain map.
 */
private fun CameraViewModel.captureProcessedSingle(
    plan: SingleCapturePlan,
    finishCapture: (Uri?) -> Unit
) {
    plan.capture.takePicture(
        ContextCompat.getMainExecutor(app),
        object : ImageCapture.OnImageCapturedCallback() {
            override fun onCaptureStarted() {
                emitShutterFeedback()
            }
            override fun onCaptureSuccess(image: ImageProxy) {
                val degrees = image.imageInfo.rotationDegrees
                // cropRect reflects setCropAspectRatio; apply it to the decoded bitmap
                // since the raw JPEG buffer is always full-frame for in-memory captures.
                val cropRect = Rect(image.cropRect)
                val sourceJpeg = try {
                    image.planes[0].buffer.let { buf ->
                        ByteArray(buf.remaining()).also { buf.get(it) }
                    }
                } finally {
                    image.close()
                }
                viewModelScope.launch {
                    val uri = withContext(Dispatchers.IO) {
                        runCatching {
                            processSingleFrame(plan, sourceJpeg, cropRect, degrees)
                        }.getOrNull()
                    }
                    finishCapture(uri)
                }
            }
            override fun onError(exception: ImageCaptureException) {
                Log.error("CameraViewModel", "Adjusted capture failed", exception)
                finishCapture(null)
            }
        }
    )
}

/** Decodes, adjusts, re-encodes and saves one processed frame. */
private fun CameraViewModel.processSingleFrame(
    plan: SingleCapturePlan,
    sourceJpeg: ByteArray,
    cropRect: Rect,
    degrees: Int
): Uri? {
    val decodedRaw = BitmapFactory.decodeByteArray(sourceJpeg, 0, sourceJpeg.size)
        ?: error("Could not decode captured frame")
    val decoded = cropToRect(decodedRaw, cropRect)
    // The bokeh renderer folds the colour matrix and the mirror in
    // as it composites; it leaves `decoded` alone if it can't run,
    // so fall back to the plain colour pass.
    val adjusted = (if (plan.bokeh) {
        stillBokeh.render(decoded, degrees, plan.strength, plan.warmth, plan.shadows, plan.mirror)
    } else null)
        ?: applyColorAdjustments(decoded, plan.warmth, plan.shadows, plan.mirror)
    val name = "IMG_${MediaStoreSaver.timestamp()}.jpg"
    return saveStillBitmap(name, adjusted)
        ?.also { writeCaptureExif(it, sourceJpeg, degrees, mirrored = plan.mirror) }
        .also { adjusted.recycle() }
}

/**
 * Capture path for the system IMAGE_CAPTURE intent. If the caller supplied an EXTRA_OUTPUT
 * Uri, the full-resolution JPEG is written there and [onSaved] is invoked with a null
 * thumbnail (the documented "no data extra needed" contract). Otherwise a downscaled
 * thumbnail Bitmap is returned for the result "data" extra.
 */
fun CameraViewModel.capturePhotoForResult(onSaved: (Bitmap?) -> Unit, onError: () -> Unit) {
    val capture = imageCapture ?: return onError()
    if (isCapturingMutable.value) return
    isCapturingMutable.value = true
    val executor = ContextCompat.getMainExecutor(app)
    val outputUri = resultOutputUri

    // Portrait bokeh / warmth / shadows only exist in the preview, so a shot taken in those
    // modes has to be re-processed before it goes back to the caller, exactly as
    // captureSinglePhoto() does for the gallery.
    if (cameraModeMutable.value == CameraMode.PORTRAIT || warmthMutable.value != 0f || shadowsMutable.value != 0f) {
        capturePhotoForResultProcessed(capture, outputUri, onSaved, onError)
        return
    }

    if (outputUri != null) {
        val outputStream = try {
            app.contentResolver.openOutputStream(outputUri)
        } catch (e: java.io.FileNotFoundException) {
            Log.error("CameraViewModel", "Could not open EXTRA_OUTPUT for writing", e)
            null
        } catch (e: SecurityException) {
            Log.error("CameraViewModel", "Could not open EXTRA_OUTPUT for writing", e)
            null
        }
        if (outputStream == null) {
            isCapturingMutable.value = false
            return onError()
        }
        val metadata = ImageCapture.Metadata().apply {
            updateLocation()
            if (locationEnabledMutable.value) location = lastLocation
            isReversedHorizontal = mirrorCaptures
        }
        val outputOptions = ImageCapture.OutputFileOptions.Builder(outputStream)
            .setMetadata(metadata)
            .build()
        capture.takePicture(outputOptions, executor, object : ImageCapture.OnImageSavedCallback {
            override fun onImageSaved(outputFileResults: ImageCapture.OutputFileResults) {
                isCapturingMutable.value = false
                onSaved(null)
            }
            override fun onError(exception: ImageCaptureException) {
                isCapturingMutable.value = false
                Log.error("CameraViewModel", "IMAGE_CAPTURE to EXTRA_OUTPUT failed", exception)
                onError()
            }
        })
    } else {
        capture.takePicture(executor, object : ImageCapture.OnImageCapturedCallback() {
            override fun onCaptureSuccess(image: ImageProxy) {
                isCapturingMutable.value = false
                val mirror = mirrorCaptures
                val thumbnail = try {
                    downscaledThumbnail(image, mirror)
                } finally {
                    image.close()
                }
                onSaved(thumbnail)
            }
            override fun onError(exception: ImageCaptureException) {
                isCapturingMutable.value = false
                Log.error("CameraViewModel", "IMAGE_CAPTURE thumbnail capture failed", exception)
                onError()
            }
        })
    }
}

/**
 * [capturePhotoForResult] for a shot that needs the preview's portrait bokeh / colour
 * adjustments baked in: capture in-memory, re-run them over the full-resolution frame, then
 * either re-encode into the caller's EXTRA_OUTPUT stream or hand back the "data" thumbnail.
 * Shares captureSinglePhoto()'s Ultra HDR caveat — the decode to ARGB_8888 drops the gain map.
 */
internal fun CameraViewModel.capturePhotoForResultProcessed(
    capture: ImageCapture,
    outputUri: Uri?,
    onSaved: (Bitmap?) -> Unit,
    onError: () -> Unit,
) {
    val warmth = warmthMutable.value
    val shadows = shadowsMutable.value
    val mirror = mirrorCaptures
    val bokeh = cameraModeMutable.value == CameraMode.PORTRAIT
    val strength = blurStrengthMutable.value

    capture.takePicture(
        ContextCompat.getMainExecutor(app),
        object : ImageCapture.OnImageCapturedCallback() {
            override fun onCaptureSuccess(image: ImageProxy) {
                val degrees = image.imageInfo.rotationDegrees
                val cropRect = Rect(image.cropRect)
                val sourceJpeg = try {
                    image.planes[0].buffer.let { buf ->
                        ByteArray(buf.remaining()).also { buf.get(it) }
                    }
                } finally {
                    image.close()
                }
                viewModelScope.launch {
                    val result = withContext(Dispatchers.IO) {
                        runCatching {
                            val decoded = cropToRect(
                                BitmapFactory.decodeByteArray(sourceJpeg, 0, sourceJpeg.size),
                                cropRect
                            )
                            val adjusted = (if (bokeh) {
                                stillBokeh.render(decoded, degrees, strength, warmth, shadows, mirror)
                            } else null)
                                ?: applyColorAdjustments(decoded, warmth, shadows, mirror)
                            // The gallery path leaves rotation to the EXIF tag, but a caller's
                            // Uri may not open "rw" for writeCaptureExif, so bake it into the
                            // pixels here and tag the result upright.
                            val upright = uprightCopy(adjusted, degrees, if (outputUri == null) 512 else 0)
                            adjusted.recycle()
                            if (outputUri == null) {
                                upright
                            } else {
                                val wrote = try {
                                    app.contentResolver.openOutputStream(outputUri)?.use { out ->
                                        upright.compress(Bitmap.CompressFormat.JPEG, 95, out)
                                    } ?: false
                                } finally {
                                    upright.recycle()
                                }
                                if (!wrote) error("Could not write to EXTRA_OUTPUT")
                                writeCaptureExif(outputUri, sourceJpeg, 0)
                                null
                            }
                        }
                    }
                    isCapturingMutable.value = false
                    result.fold(
                        onSuccess = { onSaved(it) },
                        onFailure = {
                            Log.error("CameraViewModel", "Processed IMAGE_CAPTURE failed", it)
                            onError()
                        }
                    )
                }
            }
            override fun onError(exception: ImageCaptureException) {
                isCapturingMutable.value = false
                Log.error("CameraViewModel", "Processed IMAGE_CAPTURE capture failed", exception)
                onError()
            }
        }
    )
}
