package com.vayunmathur.camera.platform.camera2

import com.vayunmathur.camera.platform.CameraMode

/** Which engine takes a still. */
enum class StillEngine { CAMERAX, CAMERA2 }

/**
 * Inputs to the still-engine selection rule. Every flag that describes a
 * non-plain capture is an exclusion for Phase 4a (see [StillEnginePolicy]).
 */
data class StillEngineInput(
    /** The Phase-2 gate. Default OFF: CameraX remains the live path. */
    val camera2Enabled: Boolean,
    val mode: CameraMode,
    /** A JPEG `ImageReader` output was configured on the Camera2 session. */
    val hasJpegOutput: Boolean = false,
    /** Night route: vendor extension or custom burst/long-exposure path. */
    val nightRoute: Boolean = false,
    /** Warmth/shadows/portrait bake that only the CameraX path applies. */
    val processedRoute: Boolean = false,
    /** Motion Photo (JPEG + MP4 trailer from the analysis ring buffer). */
    val motionPhotoRoute: Boolean = false,
    /** Capture-for-result (`ACTION_IMAGE_CAPTURE`) for a caller. */
    val captureForResult: Boolean = false,
    /** Press-and-hold burst (a sequence, not a plain single capture). */
    val burst: Boolean = false,
)

/**
 * Explicit selection rule for stills, mirroring [PreviewEnginePolicy]:
 * Camera2 takes a still only when the gate is on *and* the shot is a plain
 * single PHOTO capture on a session that has a JPEG output.
 *
 * Documented exclusions (all stay on CameraX, even with the gate on):
 * - every mode other than PHOTO (portrait, night mode, panorama, video…)
 * - the night route (extension night or custom night/long exposure)
 * - processed captures (warmth/shadows bake, portrait bokeh) — Phase 4a
 *   does not re-encode through those pipelines from Camera2 bytes
 * - Motion Photo, burst, and capture-for-result
 * - RAW, reprocess/ZSL-ring captures: no such output is configured on the
 *   Camera2 session in this phase at all
 */
object StillEnginePolicy {

    fun select(input: StillEngineInput): StillEngine =
        if (exclusionReason(input) == null) StillEngine.CAMERA2 else StillEngine.CAMERAX

    /** True when this shot should be taken on the Camera2 session. */
    fun usesCamera2(input: StillEngineInput): Boolean = select(input) == StillEngine.CAMERA2

    /** Why [input] cannot use Camera2, or null when it can. */
    fun exclusionReason(input: StillEngineInput): String? = when {
        !input.camera2Enabled -> "camera2 gate off"
        input.mode != CameraMode.PHOTO -> "mode ${input.mode} is CameraX-only in Phase 4a"
        !input.hasJpegOutput -> "no JPEG output on the Camera2 session"
        input.nightRoute -> "night captures stay on CameraX in Phase 4a"
        input.processedRoute -> "processed (bake/bokeh) captures stay on CameraX in Phase 4a"
        input.motionPhotoRoute -> "Motion Photo stays on CameraX in Phase 4a"
        input.captureForResult -> "capture-for-result stays on CameraX in Phase 4a"
        input.burst -> "burst stays on CameraX in Phase 4a"
        else -> null
    }
}
