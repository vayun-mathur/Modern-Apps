package com.vayunmathur.camera.platform

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import com.vayunmathur.library.log.Log
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import com.vayunmathur.camera.data.MediaStoreSaver
import com.vayunmathur.camera.data.saveProcessedStill
import com.vayunmathur.camera.data.saveStillBytes
import com.vayunmathur.camera.data.setLastCaptureUri
import com.vayunmathur.camera.platform.camera2.Camera2Session
import com.vayunmathur.camera.platform.camera2.CapturedStill

/**
 * True when this single must be processed in memory (portrait bokeh or
 * a warmth/shadows bake). Format routing (AVIF vs JPEG/JPEG_R) is
 * handled inside `saveCamera2Still` for plain singles, so it no longer
 * forces this path by itself.
 */
private fun CameraViewModel.singleCaptureProcessed(): Boolean =
    cameraModeMutable.value == CameraMode.PORTRAIT ||
        warmthMutable.value != 0f || shadowsMutable.value != 0f

/** Resolved per-shot state for one single capture. */
private data class SingleCapturePlan(
    val warmth: Float,
    val shadows: Float,
    val mirror: Boolean,
    val bokeh: Boolean,
    val strength: Float,
    /** Transient still exposure (night override or full manual split), or null. */
    val nightExposure: NightExposure?,
    /** Exposure for the long-exposure countdown overlay, or null. */
    val exposureNanos: Long?,
)

internal fun CameraViewModel.captureSinglePhoto() {
    val session = activeCamera2Session ?: return
    isCapturingMutable.value = true
    val plan = buildSingleCapturePlan()
    // Transient still exposure: lock it on the session (stills resolve
    // the control state at capture time), capture, then restore.
    if (plan.nightExposure != null) {
        session.setManualExposure(plan.nightExposure.nanos, plan.nightExposure.iso)
    }
    runSingleCapture(session, plan)
}

private fun CameraViewModel.buildSingleCapturePlan(): SingleCapturePlan {
    val stop = CameraViewModel.EXPOSURE_TIME_STOPS[exposureTimeIndexMutable.value]
    // The night override only applies on a fully-auto exposure in a
    // night-eligible scene, and never under the vendor extension
    // preview, which runs its own multi-frame AE.
    val nightExposure = if (nightCaptureApplies() && !nightPreviewActiveMutable.value) {
        computeNightExposure()
    } else {
        null
    }
    // Long manual shutter: the live request runs the capped preview
    // split, so the still needs its full exposure locked (night takes
    // precedence — it only applies where there is no manual split).
    val stillExposure = nightExposure ?: currentExposureSplit()
        ?.takeIf { it.stillOverrideRequired }
        ?.let { NightExposure(it.stillNanos, it.stillIso) }
    return SingleCapturePlan(
        warmth = warmthMutable.value,
        shadows = shadowsMutable.value,
        mirror = mirrorCaptures,
        bokeh = cameraModeMutable.value == CameraMode.PORTRAIT,
        strength = blurStrengthMutable.value,
        nightExposure = stillExposure,
        exposureNanos = stop.nanos ?: nightExposure?.nanos,
    )
}

/** Runs the single capture described by [plan] on [session]. */
private fun CameraViewModel.runSingleCapture(session: Camera2Session, plan: SingleCapturePlan) {
    if (plan.exposureNanos != null && plan.exposureNanos >= CameraViewModel.NIGHT_TARGET_EXPOSURE_NANOS) {
        startLongExposureCountdown(plan.exposureNanos)
    }

    fun finishCapture(uri: Uri?) {
        isCapturingMutable.value = false
        stopLongExposureCountdown()
        // Undo the transient still override by re-asserting live state.
        if (plan.nightExposure != null) applyManualControls()
        if (uri != null) setLastCaptureUri(uri)
    }

    session.capture(
        mirrored = plan.mirror,
        callback = object : Camera2Session.StillCallback {
            override fun onStillCaptured(still: CapturedStill) {
                emitShutterFeedback()
                if (plan.bokeh || plan.warmth != 0f || plan.shadows != 0f) {
                    // Processed still: the source JPEG is secured, so
                    // release the shutter now and decode/adjust/encode on
                    // the bounded still-save queue; the thumbnail updates
                    // when the save lands. A full queue keeps the inline
                    // save below (never a dropped or rerouted shot).
                    if (stillSaveQueue.tryReserve()) {
                        finishCapture(null)
                        stillSaveScope.launch(stillSaveDispatcher) {
                            try {
                                val uri = stillSaveMutex.withLock {
                                    runCatching {
                                        processSingleFrame(
                                            plan, still.jpegBytes, still.metadata.rotationDegrees,
                                        )
                                    }.onFailure {
                                        Log.error("CameraViewModel", "Single capture save failed", it)
                                    }.getOrNull()
                                }
                                if (uri != null) setLastCaptureUri(uri)
                            } finally {
                                stillSaveQueue.release()
                            }
                        }
                    } else {
                        viewModelScope.launch {
                            val uri = withContext(Dispatchers.IO) {
                                runCatching {
                                    processSingleFrame(plan, still.jpegBytes, still.metadata.rotationDegrees)
                                }.getOrNull()
                            }
                            finishCapture(uri)
                        }
                    }
                } else {
                    onCamera2StillCaptured(still) { uri -> finishCapture(uri) }
                }
            }

            override fun onStillFailed(message: String) {
                Log.error("CameraViewModel", "Single capture failed: $message")
                finishCapture(null)
            }
        },
    )
}

/** Decodes, adjusts, re-encodes and saves one processed frame. */
private fun CameraViewModel.processSingleFrame(
    plan: SingleCapturePlan,
    sourceJpeg: ByteArray,
    degrees: Int,
): Uri? {
    // Plain AVIF single + Ultra HDR source: decoding would discard the
    // gain map and AVIF `tmap` muxing is not implemented, so the shot
    // stays JPEG_R — stamped (orientation/GPS, gain map preserved by
    // ExifInterface) exactly like the Motion Photo still. Processed
    // shots (bokeh/colour) trade HDR for the effect, as before.
    val plainAvif = !plan.bokeh && plan.warmth == 0f && plan.shadows == 0f &&
        photoFormatMutable.value == PhotoFormat.AVIF
    if (plainAvif && com.vayunmathur.camera.domain.StillFormatRouting.isUltraHdrJpeg(sourceJpeg)) {
        val stamped = stampStillBytes(sourceJpeg, degrees, plan.mirror)
        val name = com.vayunmathur.camera.domain.StillFormatRouting.displayName(
            MediaStoreSaver.timestamp(), com.vayunmathur.camera.domain.StillFormat.JPEG
        )
        return saveStillBytes(name, stamped)
    }
    val decoded = BitmapFactory.decodeByteArray(sourceJpeg, 0, sourceJpeg.size)
        ?: error("Could not decode captured frame")
    // The bokeh renderer folds the colour matrix and the mirror in as
    // it composites; it leaves `decoded` alone if it can't run, so fall
    // back to the plain colour pass.
    val adjusted = (if (plan.bokeh) {
        stillBokeh.render(decoded, degrees, plan.strength, plan.warmth, plan.shadows, plan.mirror)
    } else null)
        ?: applyColorAdjustments(decoded, plan.warmth, plan.shadows, plan.mirror)
    val saved = saveProcessedStill(adjusted, degrees, sourceJpeg)
    adjusted.recycle()
    if (saved == null) return null
    if (saved.format == com.vayunmathur.camera.domain.StillFormat.JPEG) {
        writeCaptureExif(saved.uri, sourceJpeg, degrees, mirrored = plan.mirror)
    }
    return saved.uri
}

/**
 * Capture path for the system IMAGE_CAPTURE intent. If the caller supplied an EXTRA_OUTPUT
 * Uri, the full-resolution JPEG is written there and [onSaved] is invoked with a null
 * thumbnail (the documented "no data extra needed" contract). Otherwise a downscaled
 * thumbnail Bitmap is returned for the result "data" extra. Locks the full still
 * exposure first when the preview runs a capped split of a long manual shutter,
 * restoring the preview split on completion.
 */
fun CameraViewModel.capturePhotoForResult(onSaved: (Bitmap?) -> Unit, onError: () -> Unit) {
    val split = currentExposureSplit()?.takeIf { it.stillOverrideRequired }
    val session = activeCamera2Session
    if (split == null || session == null || isCapturingMutable.value) {
        capturePhotoForResultInternal(onSaved, onError)
        return
    }
    session.setManualExposure(split.stillNanos, split.stillIso)
    capturePhotoForResultInternal(
        { bitmap -> applyManualControls(); onSaved(bitmap) },
        { applyManualControls(); onError() },
    )
}

internal fun CameraViewModel.capturePhotoForResultInternal(onSaved: (Bitmap?) -> Unit, onError: () -> Unit) {
    val session = activeCamera2Session ?: return onError()
    if (isCapturingMutable.value) return
    isCapturingMutable.value = true
    val outputUri = resultOutputUri

    // Portrait bokeh / warmth / shadows only exist in the preview, so a shot taken in those
    // modes has to be re-processed before it goes back to the caller, exactly as
    // captureSinglePhoto() does for the gallery.
    if (cameraModeMutable.value == CameraMode.PORTRAIT || warmthMutable.value != 0f || shadowsMutable.value != 0f) {
        capturePhotoForResultProcessed(session, outputUri, onSaved, onError)
        return
    }

    session.capture(
        mirrored = mirrorCaptures,
        callback = object : Camera2Session.StillCallback {
            override fun onStillCaptured(still: CapturedStill) {
                isCapturingMutable.value = false
                if (outputUri != null) {
                    viewModelScope.launch(Dispatchers.IO) {
                        val wrote = try {
                            val stamped = stampStillBytes(
                                still.jpegBytes, still.metadata.rotationDegrees, still.metadata.mirrored,
                            )
                            app.contentResolver.openOutputStream(outputUri)?.use { out ->
                                out.write(stamped)
                            } != null
                        } catch (e: Exception) {
                            Log.error("CameraViewModel", "IMAGE_CAPTURE to EXTRA_OUTPUT failed", e)
                            false
                        }
                        if (wrote) {
                            writeCaptureExif(outputUri, still.jpegBytes, still.metadata.rotationDegrees, still.metadata.mirrored)
                            onSaved(null)
                        } else {
                            onError()
                        }
                    }
                } else {
                    val mirror = mirrorCaptures
                    val thumbnail = try {
                        val decoded = BitmapFactory.decodeByteArray(still.jpegBytes, 0, still.jpegBytes.size)
                            ?: error("Could not decode captured frame")
                        downscaledThumbnail(decoded, still.metadata.rotationDegrees, mirror)
                    } catch (e: Exception) {
                        Log.error("CameraViewModel", "IMAGE_CAPTURE thumbnail decode failed", e)
                        onError()
                        return
                    }
                    onSaved(thumbnail)
                }
            }

            override fun onStillFailed(message: String) {
                isCapturingMutable.value = false
                Log.error("CameraViewModel", "IMAGE_CAPTURE capture failed: $message")
                onError()
            }
        },
    )
}

/**
 * [capturePhotoForResult] for a shot that needs the preview's portrait bokeh / colour
 * adjustments baked in: capture, re-run them over the full-resolution frame, then
 * either re-encode into the caller's EXTRA_OUTPUT stream or hand back the "data" thumbnail.
 * Shares captureSinglePhoto()'s Ultra HDR caveat — the decode to ARGB_8888 drops the gain map.
 */
internal fun CameraViewModel.capturePhotoForResultProcessed(
    session: Camera2Session,
    outputUri: Uri?,
    onSaved: (Bitmap?) -> Unit,
    onError: () -> Unit,
) {
    val warmth = warmthMutable.value
    val shadows = shadowsMutable.value
    val mirror = mirrorCaptures
    val bokeh = cameraModeMutable.value == CameraMode.PORTRAIT
    val strength = blurStrengthMutable.value

    session.capture(
        mirrored = mirror,
        callback = object : Camera2Session.StillCallback {
            override fun onStillCaptured(still: CapturedStill) {
                val degrees = still.metadata.rotationDegrees
                val sourceJpeg = still.jpegBytes
                viewModelScope.launch {
                    val result = withContext(Dispatchers.IO) {
                        runCatching {
                            val decoded = BitmapFactory.decodeByteArray(sourceJpeg, 0, sourceJpeg.size)
                                ?: error("Could not decode captured frame")
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

            override fun onStillFailed(message: String) {
                isCapturingMutable.value = false
                Log.error("CameraViewModel", "Processed IMAGE_CAPTURE capture failed: $message")
                onError()
            }
        },
    )
}
