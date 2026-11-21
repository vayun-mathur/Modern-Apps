package com.vayunmathur.camera.platform

import android.view.Surface
import com.vayunmathur.camera.domain.RecordingPath
import java.io.File

/**
 * Clean boundary between the two video recording pipelines, so a recording
 * request looks the same whichever engine serves it — and so the stills
 * migration can later follow the same shape (one request in, one outcome out,
 * engine-specific surfaces/sessions hidden behind the implementation).
 *
 * - [Camera2VideoRecordingEngine]: Camera2-first. Owns the CameraDevice,
 *   CaptureSession and request, with preview + encoder + analysis surfaces.
 *   The only engine that can re-target the encoder mid-recording.
 * - [CameraXVideoRecordingEngine]: the existing CameraX Recorder path. It is
 *   the degraded fallback for everything the Camera2 path cannot honestly
 *   provide (HLG, vendor stabilization, AV1, a failed Camera2 open, or the
 *   gate being off) — its bitrate is fixed when its Recorder is built and is
 *   adapted only before `prepareRecording`.
 */
interface VideoRecordingEngine {
    val path: RecordingPath

    /**
     * Starts recording. Camera2 implementations require [request.cameraId]
     * and take ownership of the camera device (any CameraX session must be
     * torn down first); [request.previewSurface] is where preview frames go
     * (Camera2 may supply its own owned surface when null).
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

/**
 * CameraX Recorder engine: delegates to the existing session/record plumbing.
 * The lambdas keep this adapter free of CameraX session state of its own.
 */
class CameraXVideoRecordingEngine(
    private val startImpl: (VideoRecordingRequest) -> Unit,
    private val stopImpl: () -> Unit,
) : VideoRecordingEngine {
    override val path: RecordingPath = RecordingPath.CAMERAX_RECORDER

    override fun start(request: VideoRecordingRequest, onFinalized: (VideoRecordingOutcome) -> Unit) {
        // Finalization for this engine is reported by the caller's existing
        // VideoRecordEvent plumbing; the adapter only drives start/stop.
        startImpl(request)
    }

    override fun stop() = stopImpl()
}
