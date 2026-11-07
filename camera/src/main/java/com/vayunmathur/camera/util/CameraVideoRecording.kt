package com.vayunmathur.camera.util

import com.vayunmathur.library.log.Log
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.video.FileOutputOptions
import androidx.camera.video.VideoRecordEvent
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

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

    if (isRecordingMutable.value) {
        currentRecording?.stop()
        currentRecording = null
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
            startRecording()
        }
        return
    }

    startRecording()
}

@android.annotation.SuppressLint("MissingPermission")
internal fun CameraViewModel.startRecording() {
    if (isRecordingMutable.value) return
    val capture = videoCapture ?: return

    val timestamp = MediaStoreSaver.timestamp()
    val recordingMode = cameraModeMutable.value
    val displayName = "${recordingPrefix(recordingMode)}_$timestamp"

    val cacheFile = java.io.File(app.cacheDir, "VID_$timestamp.mp4")
    val outputOptions = FileOutputOptions.Builder(cacheFile).build()

    startRecordingTimer()

    val audioEnabled = recordingMode == CameraMode.VIDEO || recordingMode == CameraMode.CINEMATIC
    var pending = capture.output.prepareRecording(app, outputOptions)
    if (audioEnabled) {
        pending = pending.withAudioEnabled()
    }
    currentRecording = pending.start(ContextCompat.getMainExecutor(app)) { event ->
        if (event is VideoRecordEvent.Finalize) {
            onRecordingFinalized(cacheFile, timestamp, recordingMode, displayName, event)
        }
    }
    // Apply the current mic-mute state to the freshly-started recording.
    if (audioEnabled) {
        applyInitialMicMute()
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

/** Handles the finalize event: stops the timer and stages the file for save. */
private fun CameraViewModel.onRecordingFinalized(
    cacheFile: java.io.File,
    timestamp: String,
    recordingMode: CameraMode,
    displayName: String,
    event: VideoRecordEvent.Finalize
) {
    stopRecordingTimer()
    // A recording that finalized with an error (encoder failure, no valid
    // data, interrupted mux) leaves a truncated/unplayable cache file.
    // Saving it anyway puts a corrupt video in the gallery (the Slo-Mo path
    // already discards these; the normal path did not). Drop it instead.
    if (event.hasError()) {
        Log.error(
            "CameraViewModel",
            "Recording finalized with error ${event.error}; discarding ${cacheFile.name}",
            event.cause
        )
        cacheFile.delete()
        return
    }
    viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
        if (!cacheFile.exists()) return@launch
        val fileToSave = if (recordingMode == CameraMode.TIMELAPSE) {
            remuxTimelapse(cacheFile, timestamp)
        } else {
            cacheFile
        }
        saveVideoStaged(displayName, fileToSave)?.let {
            setLastCaptureUri(it)
        }
        fileToSave.delete()
    }
}

/** Speeds the timelapse cache file up; falls back to the raw file on remux failure. */
private fun remuxTimelapse(cacheFile: java.io.File, timestamp: String): java.io.File {
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
    }
}

/** Applies the persisted mic-mute state to the freshly-started recording. */
private fun CameraViewModel.applyInitialMicMute() {
    try {
        currentRecording?.mute(micMutedMutable.value)
    } catch (e: IllegalStateException) {
        Log.status("CameraViewModel", "Failed to apply initial mic mute", e)
    }
}

/** Pauses or resumes the active recording (video/cinematic). No-op if not recording. */
fun CameraViewModel.togglePauseRecording() {
    val recording = currentRecording ?: return
    try {
        if (recordingPausedMutable.value) {
            recording.resume()
            recordingPausedMutable.value = false
        } else {
            recording.pause()
            recordingPausedMutable.value = true
        }
    } catch (e: IllegalStateException) {
        Log.status("CameraViewModel", "Failed to pause/resume recording", e)
    }
}

/** Toggles mic mute; applies live to the active recording. Persisted for the next session. */
fun CameraViewModel.toggleMicMuted() {
    micMutedMutable.value = !micMutedMutable.value
    try {
        currentRecording?.mute(micMutedMutable.value)
    } catch (e: IllegalStateException) {
        Log.status("CameraViewModel", "Failed to toggle mic mute", e)
    }
    viewModelScope.launch { ds.setString("camera_mic_muted", micMutedMutable.value.toString()) }
}

/**
 * Captures a still while recording (video snapshot), using the ImageCapture bound alongside the
 * video session. No-op if the device couldn't bind the extra ImageCapture use case.
 */
fun CameraViewModel.captureVideoSnapshot() {
    if (isCapturingMutable.value) return
    val capture = imageCapture ?: return
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
