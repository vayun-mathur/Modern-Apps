package com.vayunmathur.camera.platform

import android.view.Surface
import java.io.File

/**
 * Clean boundary for the Camera2/MediaCodec video recording pipeline:
 * one request in, one outcome out, engine-specific surfaces/sessions
 * hidden behind the implementation. [Camera2VideoRecordingEngine] owns
 * the CameraDevice, CaptureSession and request, with preview + encoder
 * + analysis surfaces, and is the only recording engine.
 */
interface VideoRecordingEngine {
    /**
     * Starts recording. Implementations require [request.cameraId] and
     * take ownership of the camera device (any preview session must be
     * torn down first); [request.previewSurface] is where preview frames
     * go (the engine may supply its own owned surface when null).
     */
    fun start(request: VideoRecordingRequest, onFinalized: (VideoRecordingOutcome) -> Unit)

    /** Stops and finalizes; the outcome arrives via the [start] callback. */
    fun stop()
}

/** One recording, engine-agnostic. */
data class VideoRecordingRequest(
    val outputFile: File,
    val cameraId: String? = null,
    val previewSurface: Surface? = null,
    val audioEnabled: Boolean = true,
)

/**
 * A finished (or failed) recording. [error] != null means [outputFile] must
 * not be saved (implementations delete corrupt output before reporting).
 */
data class VideoRecordingOutcome(
    val outputFile: File,
    val error: Exception? = null,
)
