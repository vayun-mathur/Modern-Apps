package com.vayunmathur.camera.platform

import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Mutable Camera2 recording state owned by [CameraViewModel]: the live
 * video engine (normal/timelapse/cinematic) and the slo-mo core, plus
 * the staged-file bookkeeping the finalize path needs. Kept in one
 * holder so the ViewModel stays within its file-length budget.
 */
internal class Camera2RecordingState {
    /** Live Camera2 recording engine (video modes); null when not recording. */
    var engine: Camera2VideoRecordingEngine? = null

    /** Staged output file of the in-flight Camera2 recording. */
    var outputFile: java.io.File? = null

    /** Display name / mode / timestamp of the in-flight recording, for finalize. */
    var displayName: String? = null
    var mode: CameraMode? = null
    var timestamp: String? = null

    /** True while a Camera2 recording owns the camera (UI shows its preview). */
    val recordingActive = MutableStateFlow(false)

    /** The recording engine's preview texture, published once it starts. */
    val previewTexture = MutableStateFlow<android.graphics.SurfaceTexture?>(null)

    /** Live Camera2 slo-mo recording core; null when idle. */
    var sloMoCore: Camera2RecordingCore? = null
    var sloMoOutputFile: java.io.File? = null
    var sloMoDisplayName: String? = null

    /** Record start/stop control from the live slo-mo preview holder. */
    var sloMoControl: SloMoRecordingControl? = null
}

/**
 * Start/stop for one Camera2 slo-mo recording, supplied by the preview
 * holder that owns the high-speed session + shared core. [start]
 * begins muxing into the given file; the finalize callback reports
 * (file, true) only when the output validated readable AND stretched
 * (piece 6). [stop] ends the recording; finalization is async through
 * the [start] callback.
 */
internal class SloMoRecordingControl(
    val start: (java.io.File, (java.io.File, Boolean) -> Unit) -> Unit,
    val stop: () -> Unit,
)
