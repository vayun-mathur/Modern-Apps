package com.vayunmathur.camera.platform

import com.vayunmathur.library.log.Log
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.video.FileOutputOptions
import androidx.camera.video.VideoRecordEvent
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import com.vayunmathur.camera.data.MediaStoreSaver
import com.vayunmathur.camera.data.prepareStillSave
import com.vayunmathur.camera.data.saveProcessedStill
import com.vayunmathur.camera.data.saveStillBytes
import com.vayunmathur.camera.data.saveVideoStaged
import com.vayunmathur.camera.data.scanSafDoc
import com.vayunmathur.camera.data.setLastCaptureUri
import com.vayunmathur.camera.data.closeStream
import com.vayunmathur.camera.data.resolveUri

@android.annotation.SuppressLint("MissingPermission")
fun CameraViewModel.toggleHighSpeedRecording() {
    // Cancel countdown if active
    if (timerCountdownMutable.value > 0) {
        timerCountdownJob?.cancel()
        timerCountdownJob = null
        timerCountdownMutable.value = 0
        return
    }

    if (isRecordingMutable.value) {
        highSpeedRecording?.stop()
        highSpeedRecording = null
        stopRecordingTimer()
        return
    }

    val timer = timerDurationMutable.value
    if (timer.seconds > 0) {
        // Start countdown before recording
        timerCountdownJob = viewModelScope.launch {
            for (i in timer.seconds downTo 1) {
                timerCountdownMutable.value = i
                delay(COUNTDOWN_TICK_MS)
            }
            timerCountdownMutable.value = 0
            timerCountdownJob = null
            startHighSpeedRecording()
        }
        return
    }

    startHighSpeedRecording()
}

/** Shutter/video countdown tick and timelapse speed-up factor. */
private const val COUNTDOWN_TICK_MS = 1000L
private const val TIMELAPSE_SPEED_FACTOR = 8f

/** Settle pause between teardown and rebind for an adaptive-bitrate record press. */
private const val ADAPTIVE_REBIND_SETTLE_MS = 40L

/** Settle pause for the preview session to release the camera before the engine opens it. */
private const val RECORDING_SETTLE_MS = 350L

@android.annotation.SuppressLint("MissingPermission")
internal fun CameraViewModel.startHighSpeedRecording() {
    if (isRecordingMutable.value) return
    val videoCapture = highSpeedVideoCapture ?: return

    val timestamp = MediaStoreSaver.timestamp()
    val displayName = "SLOMO_$timestamp"

    // Stage in cache so the finalize step can copy to MediaStore or the SAF
    // folder through one shared path (direct MediaStoreOutputOptions can't
    // target a SAF tree).
    val cacheFile = java.io.File(app.cacheDir, "${displayName}.mp4")
    val outputOptions = FileOutputOptions.Builder(cacheFile).build()

    startRecordingTimer()

    Log.debug("SloMo", "Starting high-speed recording at ${sloMoFps}fps")

    highSpeedRecording = videoCapture.output
        .prepareRecording(app, outputOptions)
        .start(ContextCompat.getMainExecutor(app)) { event ->
            if (event is VideoRecordEvent.Finalize) {
                stopRecordingTimer()
                if (event.hasError()) {
                    Log.error("SloMo", "Recording error: ${event.error} - ${event.cause?.message}")
                    cacheFile.delete()
                } else {
                    Log.dev("SloMo", "High-speed recording saved: ${event.outputResults.outputUri}")
                    viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                        if (cacheFile.exists()) {
                            saveVideoStaged(displayName, cacheFile)?.let { setLastCaptureUri(it) }
                            cacheFile.delete()
                        }
                    }
                }
            }
        }
}

@android.annotation.SuppressLint("MissingPermission")
fun CameraViewModel.toggleRecording() {
    // Cancel countdown if active
    if (timerCountdownMutable.value > 0) {
        timerCountdownJob?.cancel()
        timerCountdownJob = null
        timerCountdownMutable.value = 0
        return
    }

    if (isRecordingMutable.value || camera2RecordingMutable.value) {
        stopCamera2Recording()
        return
    }

    val timer = timerDurationMutable.value
    if (timer.seconds > 0) {
        // Start countdown before recording
        timerCountdownJob = viewModelScope.launch {
            for (i in timer.seconds downTo 1) {
                timerCountdownMutable.value = i
                delay(COUNTDOWN_TICK_MS)
            }
            timerCountdownMutable.value = 0
            timerCountdownJob = null
            startRecording()
        }
        return
    }

    startRecording()
}

@android.annotation.SuppressLint("MissingPermission")
internal fun CameraViewModel.startRecording() {
    if (isRecordingMutable.value || camera2RecordingMutable.value) return
    val recordingMode = cameraModeMutable.value

    // Codec choice from the bind-time settings snapshot, probed against the
    // platform encoder registry (the Camera2 engine configures MediaCodec
    // directly; an encoder that cannot take the size/rate rejects the tier).
    val selectedCodec = bindTimeSettings.value.videoCodec
    val useAv1 = selectedCodec == VideoCodec.AV1 && CodecSupport.isHardwareAv1EncoderAvailable
    val useHevc = !useAv1 && selectedCodec != VideoCodec.AVC && CodecSupport.isHevcEncoderAvailable
    recordingWithAv1 = useAv1
    recordingWithHevc = useHevc
    val mime = when {
        useAv1 -> android.media.MediaFormat.MIMETYPE_VIDEO_AV1
        useHevc -> android.media.MediaFormat.MIMETYPE_VIDEO_HEVC
        else -> android.media.MediaFormat.MIMETYPE_VIDEO_AVC
    }
    val tier = listOf(
        com.vayunmathur.camera.domain.VideoQualityTier.UHD,
        com.vayunmathur.camera.domain.VideoQualityTier.FHD,
        com.vayunmathur.camera.domain.VideoQualityTier.HD,
        com.vayunmathur.camera.domain.VideoQualityTier.SD,
    ).firstOrNull { candidate ->
        CodecSupport.supportsVideoBitrate(mime, candidate.width, candidate.height, 30, candidate.baseBitrateBps)
    } ?: com.vayunmathur.camera.domain.VideoQualityTier.FHD
    boundVideoQualityTier = tier

    val decision = computeAdaptiveBitrateDecision(tier, useAv1 = useAv1, useHevc = useHevc)
    boundVideoBitrateBps = decision.bitrateBps
    if (decision.forcedReduction) {
        // The one user-visible adaptive note, and only for a forced cut:
        // scene-only savings stay silent by design.
        val noteRes = if (
            com.vayunmathur.camera.domain.AdaptiveReduction.LOW_STORAGE in decision.reductions
        ) {
            com.vayunmathur.camera.R.string.lower_bitrate_low_storage
        } else {
            com.vayunmathur.camera.R.string.lower_bitrate_thermal
        }
        com.vayunmathur.library.util.AppMessages.show(app.getString(noteRes))
    }

    // Camera2 id for the selected lens (CameraManager ids only, never the
    // lens's logical id — see the slo-mo glue for the same contract).
    val manager = app.getSystemService(android.hardware.camera2.CameraManager::class.java)
    val facing = selectedLensMutable.value?.facing
        ?: currentLensFacing()
    val cameraId = manager?.let {
        com.vayunmathur.camera.platform.camera2.CharacteristicsFacade.resolveCamera2Id(it, facing)
    }
    if (cameraId == null) {
        Log.error("Camera2Recorder", "No Camera2 id for facing $facing; recording not started")
        com.vayunmathur.library.util.AppMessages.show(
            app.getString(com.vayunmathur.camera.R.string.camera_recording_failed)
        )
        return
    }

    val timestamp = MediaStoreSaver.timestamp()
    val displayName = "${recordingPrefix(recordingMode)}_$timestamp"
    val cacheFile = java.io.File(app.cacheDir, "VID_$timestamp.mp4")
    val audioEnabled = (recordingMode == CameraMode.VIDEO || recordingMode == CameraMode.CINEMATIC) &&
        !micMutedMutable.value

    val config = CustomRecordingConfig(
        width = tier.width,
        height = tier.height,
        fps = 30,
        videoMime = mime,
        audioEnabled = audioEnabled,
    )
    val controller = com.vayunmathur.camera.domain.LiveBitrateController(decision)
    val engine = Camera2VideoRecordingEngine(
        context = app,
        config = config,
        controller = controller,
        motionSupplier = { sceneMotionEstimator.lastClassification },
        onMotionSample = { sample -> onSceneMotionSample(sample) },
        onError = { e -> Log.error("Camera2Recorder", "Camera2 recording engine error", e) },
    )
    activeVideoEngine = engine
    activeVideoOutputFile = cacheFile
    activeVideoDisplayName = displayName
    activeVideoMode = recordingMode
    activeVideoTimestamp = timestamp

    // The engine owns the camera device: the preview session (Camera2 or
    // any transitional binding) must release it first. Flipping the
    // recording flag switches the viewfinder to the engine's preview;
    // a short settle lets the preview session close before the open.
    camera2RecordingMutable.value = true
    teardownSession()
    viewModelScope.launch {
        delay(RECORDING_SETTLE_MS)
        if (!camera2RecordingMutable.value) return@launch // stopped during settle
        try {
            engine.start(VideoRecordingRequest(cacheFile, cameraId = cameraId, audioEnabled = audioEnabled)) { outcome ->
                onCamera2RecordingFinalized(outcome)
            }
            isRecordingMutable.value = true
            startRecordingTimer()
        } catch (e: Exception) {
            Log.error("Camera2Recorder", "Camera2 recording failed to start", e)
            activeVideoEngine = null
            camera2RecordingMutable.value = false
            cacheFile.delete()
            com.vayunmathur.library.util.AppMessages.show(
                app.getString(com.vayunmathur.camera.R.string.camera_recording_failed)
            )
        }
    }
}

/** Stops the in-flight Camera2 recording (or cancels a pending start). */
fun CameraViewModel.stopCamera2Recording() {
    val engine = activeVideoEngine
    stopRecordingTimer()
    isRecordingMutable.value = false
    if (engine == null) {
        camera2RecordingMutable.value = false
        return
    }
    // engine.stop() joins its drain threads; run it off the main thread.
    // The outcome callback (save/error + preview restore) fires from there.
    viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
        engine.stop()
    }
}

/** Finalize for the Camera2 engine: save the staged file or surface the error. */
private fun CameraViewModel.onCamera2RecordingFinalized(outcome: VideoRecordingOutcome) {
    val cacheFile = outcome.outputFile
    val displayName = activeVideoDisplayName ?: cacheFile.nameWithoutExtension
    val recordingMode = activeVideoMode ?: CameraMode.VIDEO
    val timestamp = activeVideoTimestamp ?: ""
    activeVideoEngine = null
    activeVideoOutputFile = null
    camera2RecordingMutable.value = false
    if (outcome.error != null) {
        Log.error("Camera2Recorder", "Recording failed; discarding ${cacheFile.name}", outcome.error)
        cacheFile.delete()
        com.vayunmathur.library.util.AppMessages.show(
            app.getString(com.vayunmathur.camera.R.string.camera_recording_failed)
        )
        return
    }
    viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
        if (!cacheFile.exists()) return@launch
        val fileToSave = if (recordingMode == CameraMode.TIMELAPSE) {
            remuxTimelapse(cacheFile, timestamp)
        } else {
            cacheFile
        }
        saveVideoStaged(displayName, fileToSave)?.let { setLastCaptureUri(it) }
        fileToSave.delete()
    }
}

/** Filename prefix per recording mode. */
private fun recordingPrefix(recordingMode: CameraMode): String {
    return when (recordingMode) {
        CameraMode.TIMELAPSE -> "TL"
        CameraMode.CINEMATIC -> "CINE"
        else -> "VID"
    }
}

/** (Removed: the CameraX finalize handler; Camera2 finalize is [onCamera2RecordingFinalized].) */

/** Speeds the timelapse cache file up; falls back to the raw file on remux failure. */
internal fun remuxTimelapse(cacheFile: java.io.File, timestamp: String): java.io.File {
    val processed = java.io.File(cacheFile.parent, "TL_$timestamp.mp4")
    return try {
        VideoProcessor.adjustSpeed(cacheFile, processed, TIMELAPSE_SPEED_FACTOR)
        // Only trade the raw recording for the remux once the output is
        // known-good; a missing/empty output keeps the raw file.
        if (!processed.exists() || processed.length() == 0L) {
            throw java.io.IOException("Timelapse remux produced no output")
        }
        cacheFile.delete()
        processed
    } catch (e: java.io.IOException) {
        // MediaMuxer can reject an av01 track (or an oversized keyframe)
        // on some devices; keep the raw recording rather than crash.
        Log.error("CameraViewModel", "Timelapse remux failed; saving unprocessed", e)
        processed.delete()
        cacheFile
    } catch (e: IllegalStateException) {
        // MediaMuxer can reject an av01 track (or an oversized keyframe)
        // on some devices; keep the raw recording rather than crash.
        Log.error("CameraViewModel", "Timelapse remux failed; saving unprocessed", e)
        processed.delete()
        cacheFile
    } catch (e: IllegalArgumentException) {
        // Extractor/muxer argument failures (e.g. a sample that still
        // exceeds the buffer) must not crash the finalize coroutine or
        // lose the recording; keep the raw recording.
        Log.error("CameraViewModel", "Timelapse remux failed; saving unprocessed", e)
        processed.delete()
        cacheFile
    } catch (e: java.nio.BufferOverflowException) {
        // Same fallback as above for an oversized sample write.
        Log.error("CameraViewModel", "Timelapse remux failed; saving unprocessed", e)
        processed.delete()
        cacheFile
    }
}

/**
 * Pauses or resumes the active recording. The MediaCodec engine has no
 * pause primitive (dropping frames would tear the muxed timeline), so
 * pause is unavailable on the Camera2 path: the toggle is a no-op that
 * leaves the recording running, and the UI hides the affordance while
 * a Camera2 recording is active.
 */
fun CameraViewModel.togglePauseRecording() {
    if (activeVideoEngine != null) {
        Log.status("CameraViewModel", "Pause is unavailable on the Camera2 recording path")
        return
    }
}

/**
 * Toggles mic mute. On the Camera2 path mute is a record-start decision
 * (`audioEnabled` when the engine is built); a mid-recording toggle is
 * persisted and applies to the next recording.
 */
fun CameraViewModel.toggleMicMuted() {
    micMutedMutable.value = !micMutedMutable.value
    if (activeVideoEngine != null) {
        Log.status("CameraViewModel", "Mic mute applies from the next recording on the Camera2 path")
    }
    viewModelScope.launch { ds.setString("camera_mic_muted", micMutedMutable.value.toString()) }
}

/**
 * True when a video/slo-mo snapshot must be captured in memory instead of
 * on the JPEG file path: an effective AVIF selection (the file path can
 * only write the HAL JPEG). Read at capture time, never cached at bind.
 */
internal fun CameraViewModel.videoSnapshotInMemory(): Boolean =
    com.vayunmathur.camera.domain.StillFormatRouting.plainSingleNeedsInMemory(
        requestedStillFormat(), StitchNative.isAvailable,
    )

/**
 * Captures a still while recording (video snapshot, including slo-mo),
 * using the ImageCapture bound alongside the video session. No-op if the
 * device couldn't bind the extra ImageCapture use case. With AVIF
 * selected the snapshot is captured in memory and lands through the
 * EXIF-carrying `StillEncoder`, exactly like a plain AVIF single (an
 * Ultra HDR source stays JPEG_R); JPEG keeps the file path unchanged.
 */
fun CameraViewModel.captureVideoSnapshot() {
    if (isCapturingMutable.value) return
    val capture = imageCapture ?: return
    if (videoSnapshotInMemory()) {
        captureVideoSnapshotInMemory(capture)
        return
    }
    val pending = prepareStillSave("IMG_${MediaStoreSaver.timestamp()}.jpg") ?: return
    val outputOptions = pending.outputOptions
    capture.takePicture(
        outputOptions,
        ContextCompat.getMainExecutor(app),
        object : ImageCapture.OnImageSavedCallback {
            override fun onImageSaved(outputFileResults: ImageCapture.OutputFileResults) {
                pending.closeStream()
                pending.resolveUri(outputFileResults)?.let { setLastCaptureUri(it) }
            }
            override fun onError(exception: ImageCaptureException) {
                Log.error("CameraViewModel", "Video snapshot failed", exception)
                pending.closeStream()
            }
        }
    )
}

/** In-memory (AVIF) video/slo-mo snapshot: decode, encode via `saveProcessedStill`, save. */
private fun CameraViewModel.captureVideoSnapshotInMemory(capture: ImageCapture) {
    val mirror = mirrorCaptures
    capture.takePicture(
        ContextCompat.getMainExecutor(app),
        object : ImageCapture.OnImageCapturedCallback() {
            override fun onCaptureSuccess(image: androidx.camera.core.ImageProxy) {
                val degrees = image.imageInfo.rotationDegrees
                val cropRect = android.graphics.Rect(image.cropRect)
                val sourceJpeg = try {
                    image.planes[0].buffer.let { buf ->
                        ByteArray(buf.remaining()).also { buf.get(it) }
                    }
                } finally {
                    image.close()
                }
                viewModelScope.launch {
                    val uri = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                        runCatching {
                            if (com.vayunmathur.camera.domain.StillFormatRouting
                                    .isUltraHdrJpeg(sourceJpeg)
                            ) {
                                saveStillBytes(
                                    "IMG_${MediaStoreSaver.timestamp()}.jpg",
                                    stampStillBytes(sourceJpeg, degrees, mirror),
                                )
                            } else {
                                val decodedRaw = android.graphics.BitmapFactory
                                    .decodeByteArray(sourceJpeg, 0, sourceJpeg.size)
                                    ?: error("Could not decode video snapshot")
                                val decoded = cropToRect(decodedRaw, cropRect)
                                val adjusted = applyColorAdjustments(decoded, 0f, 0f, mirror)
                                val saved = saveProcessedStill(adjusted, degrees, sourceJpeg)
                                adjusted.recycle()
                                if (saved != null &&
                                    saved.format == com.vayunmathur.camera.domain.StillFormat.JPEG
                                ) {
                                    writeCaptureExif(saved.uri, sourceJpeg, degrees, mirrored = mirror)
                                }
                                saved?.uri
                            }
                        }.getOrNull()
                    }
                    if (uri != null) setLastCaptureUri(uri)
                }
            }

            override fun onError(exception: ImageCaptureException) {
                Log.error("CameraViewModel", "Video snapshot failed", exception)
            }
        }
    )
}

fun CameraViewModel.startPanorama() {
    if (panoramaEngine.isSweeping.value || panoramaEngine.isStitching.value || isCapturingMutable.value) return
    panoramaEngine.startSweep()
}

fun CameraViewModel.stopPanorama() = finishPanoramaSweep()

fun CameraViewModel.startPhotosphere() {
    if (panoramaEngine.isSweeping.value || panoramaEngine.isStitching.value || isCapturingMutable.value) return
    panoramaEngine.startSweep(fullSphere = true)
}

fun CameraViewModel.stopPhotosphere() = finishPanoramaSweep()

internal fun CameraViewModel.finishPanoramaSweep() {
    if (isFinishingPano) return
    isFinishingPano = true
    panoramaEngine.stopSweep()
    viewModelScope.launch {
        val result = panoramaEngine.stitch()
        if (result == null) {
            Log.error("CameraViewModel", "Panorama stitch failed – no output (check registration)")
        } else {
            val (jpeg, info) = result
            Log.status("CameraViewModel", "Panorama stitched ${jpeg.size} bytes, saving")
            val uri = panoramaEngine.saveToMediaStore(jpeg, info, saveTargetMutable.value)
            if (uri != null) {
                Log.status("CameraViewModel", "Panorama saved uri=$uri")
                scanSafDoc(uri)
                setLastCaptureUri(uri)
            } else {
                Log.error("CameraViewModel", "Panorama MediaStore save returned null")
            }
        }
        panoramaEngine.reset()
        isFinishingPano = false
    }
}
