package com.vayunmathur.camera.platform.camera2

import com.vayunmathur.camera.platform.CameraMode

/** Which engine renders the viewfinder preview. */
enum class PreviewEngine { CAMERAX, CAMERA2 }

/**
 * Inputs to the preview-engine selection rule.
 *
 * @property camera2Enabled the per-mode Camera2 preview gate. Default ON:
 *   Camera2 is the live path unless the user opts out in Settings.
 * @property mode the camera mode the preview is for (PHOTO first).
 * @property fallbackLatched a Camera2 configure/bind failure already
 *   latched for this bind attempt: stay on CameraX, never retry-loop.
 */
data class PreviewEngineInput(
    val camera2Enabled: Boolean,
    val mode: CameraMode,
    val fallbackLatched: Boolean = false,
)

/**
 * Explicit selection rule for the two preview engines, mirroring
 * `RecordingPathPolicy` for recording: the Camera2 path is selected only
 * when the gate is explicitly on *and* the mode is one the Camera2 session
 * honestly supports. Phase 2 supports PHOTO only; Phase 4a adds plain
 * PHOTO stills on that preview (see `StillEnginePolicy` for which shots
 * still stay on CameraX), so every other mode stays on CameraX even with
 * the gate on.
 *
 * Per-mode assessment (2026-11-22, CameraX-holdout reduction): extending
 * preview alone is only coherent when the mode's capture/analysis also
 * lives on the Camera2 session. When Camera2 preview is selected the
 * CameraX session is NOT bound at all (`CameraScreenEffects` tears it
 * down and skips the bind), so a mode whose capture needs a bound CameraX
 * use case would show a viewfinder with a dead shutter/recorder:
 *
 * - PHOTO: coherent and live. Plain stills are served by the Camera2
 *   session itself ([StillEnginePolicy]); its excluded shots fall back
 *   per-shot only because the session already owns the camera.
 * - PORTRAIT: blocked. The capture is a processed CameraX still (bokeh
 *   bake) and the preview mask comes from a CameraX `ImageAnalysis`
 *   bitmap segmenter. Camera2 has neither a portrait still route nor
 *   (yet) a bitmap analysis feed for that session; see
 *   `AnalysisSourcePolicy`.
 * - PANORAMA / PHOTOSPHERE: blocked. Capture is analysis bitmap frames
 *   (`PanoramaEngine.offerFrame(Bitmap)`) from CameraX `ImageAnalysis`;
 *   there is no bound CameraX analysis stream under a Camera2 preview.
 * - VIDEO / TIMELAPSE / CINEMATIC: blocked. Recording is the CameraX
 *   Recorder (`videoCapture` bound by the video session;
 *   `startRecording` silently no-ops when it is null), and the Camera2
 *   recording engine is not device-verified (`CustomRecordingGate`).
 *   An idle-only Camera2 preview would need a record-press rebind to
 *   CameraX (teardown, settle, video-session bind, then start) that does
 *   not exist today; showing Camera2 preview until the press and then
 *   going black for the rebind is a worse, unverified trade, not a
 *   reduction. Revisit only with that rebind implemented end to end.
 * - SLOW_MO: blocked for live selection. Preview, capture and recording
 *   are one CameraX high-speed session. The Camera2 preview surface now
 *   exists (`Camera2SloMoPreview`, piece 4), but selecting it would
 *   unbind the CameraX session that still owns recording until the
 *   Camera2 recording engine is device-verified (piece 6); see
 *   `SloMoEnginePolicy` and `SloMoPreviewPolicy`, which gate the
 *   slo-mo preview's own live selection on `recordingEngineReady`.
 *
 * Adding a mode to [supportedModes] therefore requires, in the same
 * change, its capture/analysis route on the Camera2 session (or a
 * verified capture-time rebind); a preview-only flip is a split-brain
 * regression, not progress.
 */
object PreviewEnginePolicy {

    /** Modes the Camera2 preview engine supports today (PHOTO first). */
    val supportedModes: Set<CameraMode> = setOf(CameraMode.PHOTO)

    fun select(input: PreviewEngineInput): PreviewEngine =
        if (exclusionReason(input) == null) PreviewEngine.CAMERA2 else PreviewEngine.CAMERAX

    /** Why [input] cannot use Camera2 preview, or null when it can. */
    fun exclusionReason(input: PreviewEngineInput): String? = when {
        !input.camera2Enabled -> "camera2 preview gate off"
        input.fallbackLatched -> "camera2 bind failure latched for this session"
        input.mode == CameraMode.PHOTO -> null
        input.mode == CameraMode.PORTRAIT ->
            "PORTRAIT capture and bitmap segmentation stay on CameraX; " +
                "a Camera2 preview would leave neither bound"
        input.mode == CameraMode.PANORAMA || input.mode == CameraMode.PHOTOSPHERE ->
            "panorama capture needs CameraX analysis bitmap frames; " +
                "a Camera2 preview would leave no analysis stream bound"
        input.mode == CameraMode.SLOW_MO ->
            "slo-mo preview stays with the CameraX high-speed session until the " +
                "Camera2 recording engine is device-verified; the Camera2 preview " +
                "surface exists but selecting it would unbind CameraX recording"
        else ->
            "video-mode recording stays on the CameraX Recorder; " +
                "a Camera2 preview would leave no Recorder bound to record into"
    }

    /** True when [mode] should render through the Camera2 preview engine. */
    fun usesCamera2(enabled: Boolean, mode: CameraMode, fallbackLatched: Boolean = false): Boolean =
        select(PreviewEngineInput(enabled, mode, fallbackLatched)) == PreviewEngine.CAMERA2
}

/**
 * One-shot fallback latch for one Camera2 bind attempt. The first
 * configure/bind failure for [bindKey] latches and reports `true` (the
 * caller switches that session to CameraX); further failures for the
 * same key report `false` so a failing device cannot loop
 * Camera2 -> CameraX -> Camera2. A different key (lens/mode), a manual
 * retry, re-enabling the gate, or [reset] starts a fresh attempt.
 * Pure; its owner (the ViewModel) publishes the latched key via a
 * StateFlow, so executor-thread failures are safely visible to the UI.
 */
class Camera2FallbackLatch {
    private var latchedKey: String? = null

    val latchedBindKey: String? get() = latchedKey

    fun isLatched(bindKey: String): Boolean = latchedKey == bindKey

    /** Records a failure; true only for the first failure of this key. */
    fun recordFailure(bindKey: String): Boolean {
        if (latchedKey == bindKey) return false
        latchedKey = bindKey
        return true
    }

    fun clear(bindKey: String) {
        if (latchedKey == bindKey) latchedKey = null
    }

    fun reset() {
        latchedKey = null
    }

    companion object {
        /** Stable key for one preview bind attempt (lens + mode). */
        fun bindKey(cameraId: String, mode: CameraMode): String = "$cameraId:${mode.name}"
    }
}
