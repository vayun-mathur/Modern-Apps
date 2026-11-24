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

    /**
     * The recording preview viewfinder's own surface, registered by the
     * recording TextureView when its surface is created (and cleared on
     * destroy). The recording engine targets exactly this surface, so
     * the frames the session writes are the frames the on-screen view
     * renders — there is no engine-owned texture for the UI to adopt.
     */
    var previewSurface: android.view.Surface? = null

    /** Buffer size the recording preview texture is pinned to, set at record start. */
    var previewStreamSize: com.vayunmathur.camera.platform.camera2.FrameSize? = null

    /** Camera id / audio choice of a start waiting for the preview surface. */
    var pendingCameraId: String? = null
    var pendingAudioEnabled: Boolean = true

    /** True between the record press and the engine actually starting. */
    var startPending: Boolean = false

    /** True once a pending start has been launched (starts at most once). */
    var startLaunched: Boolean = false

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
