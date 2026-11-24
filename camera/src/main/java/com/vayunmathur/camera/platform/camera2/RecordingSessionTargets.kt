package com.vayunmathur.camera.platform.camera2

/** One output role in a Camera2 recording capture session. */
enum class RecordingTargetRole {
    /** MediaCodec encoder input surface: the recorded file's frames. */
    ENCODER,

    /**
     * The visible viewfinder's surface. It must be a configure-time AND a
     * repeating-request target for the whole recording: dropping it (or
     * targeting a surface the on-screen view does not own) leaves the
     * encoder recording while the preview goes black.
     */
    PREVIEW,

    /** Low-rate YUV analysis surface (scene-motion sampling). */
    ANALYSIS,
}

/**
 * Target composition for the recording session, pure so the
 * preview-stays-live contract is unit-testable without a HAL.
 *
 * Configure-time and repeating targets are the same list by design: the
 * preview surface is never swapped or dropped mid-recording, and the
 * encoder never records without the viewfinder being fed (when a preview
 * surface was supplied at all).
 */
object RecordingSessionTargets {

    /** Configure-time output targets, encoder first. */
    fun configureTargets(
        hasPreviewSurface: Boolean,
        hasAnalysisSurface: Boolean,
    ): List<RecordingTargetRole> = compose(hasPreviewSurface, hasAnalysisSurface)

    /** Repeating-request targets: identical to [configureTargets]. */
    fun repeatingTargets(
        hasPreviewSurface: Boolean,
        hasAnalysisSurface: Boolean,
    ): List<RecordingTargetRole> = compose(hasPreviewSurface, hasAnalysisSurface)

    private fun compose(
        hasPreviewSurface: Boolean,
        hasAnalysisSurface: Boolean,
    ): List<RecordingTargetRole> = buildList {
        add(RecordingTargetRole.ENCODER)
        if (hasPreviewSurface) add(RecordingTargetRole.PREVIEW)
        if (hasAnalysisSurface) add(RecordingTargetRole.ANALYSIS)
    }
}

/** Which viewfinder the camera screen shows. */
enum class RecordingViewfinder {
    /** The recording preview: a live TextureView fed by the recording session. */
    RECORDING,

    /** The normal (non-recording) preview routing. */
    STANDARD,
}

/**
 * Recording-preview wiring decisions, pure for unit tests.
 *
 * The recording viewfinder is shown for the entire recording (never a
 * black placeholder swapped in at record start), and the recording
 * engine may only open the camera once that viewfinder has published
 * its own surface: the engine targets the *view's* surface, so the
 * frames the HAL writes to the preview target are by construction the
 * frames the on-screen TextureView renders. (The previous wiring let
 * the engine create a detached SurfaceTexture and handed that texture
 * to a fresh TextureView via `setSurfaceTexture`; that adopted texture
 * is not the view's own surface, and the view stayed black while the
 * encoder kept recording.)
 */
object RecordingPreviewPolicy {

    /** The viewfinder to compose for the current recording state. */
    fun viewfinder(recordingActive: Boolean): RecordingViewfinder =
        if (recordingActive) RecordingViewfinder.RECORDING else RecordingViewfinder.STANDARD

    /**
     * True exactly when a pending recording start may proceed: a start
     * was requested, has not been launched yet, and the recording
     * viewfinder has published its surface. A surface arriving with no
     * pending start (or after launch, or a destroyed/null surface)
     * never (re)starts the engine.
     */
    fun shouldStartEngine(
        startPending: Boolean,
        alreadyLaunched: Boolean,
        previewSurfaceAvailable: Boolean,
    ): Boolean = startPending && !alreadyLaunched && previewSurfaceAvailable
}
