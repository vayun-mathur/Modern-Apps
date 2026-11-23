package com.vayunmathur.camera.platform

import com.vayunmathur.library.log.Log
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import com.vayunmathur.camera.data.MediaStoreSaver
import com.vayunmathur.camera.data.saveProcessedStill
import com.vayunmathur.camera.data.saveStillBytes
import com.vayunmathur.camera.data.saveVideoStaged
import com.vayunmathur.camera.data.scanSafDoc
import com.vayunmathur.camera.data.setLastCaptureUri

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
        if (camera2RecordingState.sloMoControl != null) {
            stopCamera2SloMoRecording()
        } else {
            // Recording state without a live slo-mo control: nothing owns
            // the camera, so just clear the recording UI state.
            stopRecordingTimer()
            isRecordingMutable.value = false
        }
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
            startSloMoRecordingRouted()
        }
        return
    }

    startSloMoRecordingRouted()
}

/** Slo-mo record press: Camera2 or an honest error (there is no other engine). */
private fun CameraViewModel.startSloMoRecordingRouted() {
    if (camera2RecordingState.sloMoControl != null) {
        startCamera2SloMoRecording()
    } else {
        Log.error("SloMo", "No Camera2 slo-mo session live; recording not started")
        com.vayunmathur.library.util.AppMessages.show(
            app.getString(com.vayunmathur.camera.R.string.camera_recording_failed)
        )
    }
}

/** Shutter/video countdown tick and timelapse speed-up factor. */
private const val COUNTDOWN_TICK_MS = 1000L
private const val TIMELAPSE_SPEED_FACTOR = 8f

/** Settle pause between teardown and rebind for an adaptive-bitrate record press. */
private const val ADAPTIVE_REBIND_SETTLE_MS = 40L

/** Settle pause for the preview session to release the camera before the engine opens it. */
private const val RECORDING_SETTLE_MS = 350L

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
    // Tier must also be a stream size this lens advertises (PRIVATE is the
    // honest proxy for the encoder surface): a tier the HAL cannot stream
    // fails the capture-session configure, as UHD on a 1080p front lens does.
    val manager0 = app.getSystemService(android.hardware.camera2.CameraManager::class.java)
    val facing0 = selectedLensMutable.value?.facing ?: currentLensFacing()
    val cameraId0 = manager0?.let {
        com.vayunmathur.camera.platform.camera2.CharacteristicsFacade.resolveCamera2Id(it, facing0)
    }
    val snapshot0 = if (manager0 != null && cameraId0 != null) {
        com.vayunmathur.camera.platform.camera2.CharacteristicsFacade
            .tryFromCameraManager(manager0, cameraId0)?.snapshot
    } else {
        null
    }
    val privateSizes = snapshot0?.streamConfig
        ?.sizesFor(android.graphics.ImageFormat.PRIVATE).orEmpty()
    val tier = listOf(
        com.vayunmathur.camera.domain.VideoQualityTier.UHD,
        com.vayunmathur.camera.domain.VideoQualityTier.FHD,
        com.vayunmathur.camera.domain.VideoQualityTier.HD,
        com.vayunmathur.camera.domain.VideoQualityTier.SD,
    ).firstOrNull { candidate ->
        (privateSizes.isEmpty() || privateSizes.any {
            it.width == candidate.width && it.height == candidate.height
        }) && CodecSupport.supportsVideoBitrate(
            mime, candidate.width, candidate.height, 30, candidate.baseBitrateBps
        )
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
    val cameraId = cameraId0
    if (cameraId == null) {
        Log.error("Camera2Recorder", "No Camera2 id for facing $facing0; recording not started")
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
        // Physical mic from the bind-time snapshot (held during a take).
        audioInputDevice = bindTimeSettings.value.audioInputDevice,
    )
    val controller = com.vayunmathur.camera.domain.LiveBitrateController(decision)
    val engine = Camera2VideoRecordingEngine(
        context = app,
        config = config,
        controller = controller,
        motionSupplier = { sceneMotionEstimator.lastClassification },
        onMotionSample = { sample -> onSceneMotionSample(sample) },
        onError = { e -> onVideoEngineError(e) },
    )
    camera2RecordingState.engine = engine
    camera2RecordingState.outputFile = cacheFile
    camera2RecordingState.displayName = displayName
    camera2RecordingState.mode = recordingMode
    camera2RecordingState.timestamp = timestamp

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
            camera2RecordingState.previewTexture.value = engine.ownedPreviewTexture()
            isRecordingMutable.value = true
            startRecordingTimer()
        } catch (e: Exception) {
            Log.error("Camera2Recorder", "Camera2 recording failed to start", e)
            camera2RecordingState.engine = null
            camera2RecordingMutable.value = false
            cacheFile.delete()
            com.vayunmathur.library.util.AppMessages.show(
                app.getString(com.vayunmathur.camera.R.string.camera_recording_failed)
            )
        }
    }
}

/**
 * Engine error (configure/open/session failure): finalize through the
 * normal stop path so the partial file is validated, discarded on
 * failure, and the recording state/preview are restored — never a
 * stuck "recording" state with no outcome.
 */
private fun CameraViewModel.onVideoEngineError(e: Exception) {
    Log.error("Camera2Recorder", "Camera2 recording engine error", e)
    if (camera2RecordingState.engine == null) return
    stopCamera2Recording()
}

/** Stops the in-flight Camera2 recording (or cancels a pending start). */
fun CameraViewModel.stopCamera2Recording() {
    val engine = camera2RecordingState.engine
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
    val displayName = camera2RecordingState.displayName ?: cacheFile.nameWithoutExtension
    val recordingMode = camera2RecordingState.mode ?: CameraMode.VIDEO
    val timestamp = camera2RecordingState.timestamp ?: ""
    camera2RecordingState.engine = null
    camera2RecordingState.outputFile = null
    camera2RecordingState.previewTexture.value = null
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
    if (camera2RecordingState.engine != null) {
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
    if (camera2RecordingState.engine != null) {
        Log.status("CameraViewModel", "Mic mute applies from the next recording on the Camera2 path")
    }
    viewModelScope.launch { ds.setString("camera_mic_muted", micMutedMutable.value.toString()) }
}

/**
 * Captures a still while previewing in a video mode (video snapshot),
 * on the live Camera2 session. While a recording owns the camera the
 * snapshot affordance is hidden (`videoSnapshotSupported` stays false),
 * so this is a defensive no-op then.
 */
fun CameraViewModel.captureVideoSnapshot() {
    if (isCapturingMutable.value || camera2RecordingState.engine != null) return
    val session = activeCamera2Session ?: return
    session.capture(
        mirrored = mirrorCaptures,
        callback = object : com.vayunmathur.camera.platform.camera2.Camera2Session.StillCallback {
            override fun onStillCaptured(still: com.vayunmathur.camera.platform.camera2.CapturedStill) {
                onCamera2StillCaptured(still) { uri ->
                    if (uri != null) setLastCaptureUri(uri)
                }
            }

            override fun onStillFailed(message: String) {
                Log.error("CameraViewModel", "Video snapshot failed: $message")
            }
        },
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
