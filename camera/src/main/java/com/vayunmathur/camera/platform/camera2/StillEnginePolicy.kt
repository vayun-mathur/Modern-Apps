package com.vayunmathur.camera.platform.camera2

import com.vayunmathur.camera.platform.CameraMode

/** Which engine takes a still. */
enum class StillEngine {
    CAMERAX,
    CAMERA2,

    /** Camera2 ZSL ring: an already-exposed YUV frame, reprocessed or direct-encoded. */
    CAMERA2_RING,
}

/**
 * Inputs to the still-engine selection rule. Every flag that describes a
 * non-plain capture is an exclusion for Phase 4a (see [StillEnginePolicy]).
 */
data class StillEngineInput(
    /** The Phase-2 gate. Default ON: Camera2 is the live still path when supported. */
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
    /** Depth-phase ring gate (compile-time [ReprocessRingGate] AND runtime). */
    val ringEnabled: Boolean = false,
    /** Facade snapshot reports YUV reprocessing for this lens. */
    val reprocessSupported: Boolean = false,
    /** A YUV ring output is configured and holding frames on the session. */
    val hasRingOutput: Boolean = false,
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
 * - RAW captures: no RAW output is configured in this phase (the ring is
 *   structured so a RAW10 ring could slot in beside the YUV one later)
 *
 * Ring extension (depth phase): [StillEngine.CAMERA2_RING] is selected
 * only when [StillEngineInput.ringEnabled] is on, the facade reports YUV
 * reprocessing, a ring output exists, and every plain-single-photo rule
 * above already passes. Everything else stays exactly as today. The
 * direct-encode fallback in `Camera2Session.captureFromRing` covers a
 * runtime reprocess failure after this policy has passed (facade said
 * supported, the writer/session refused) — it is not a second route in.
 */
object StillEnginePolicy {

    fun select(input: StillEngineInput): StillEngine = when {
        ringExclusionReason(input) == null -> StillEngine.CAMERA2_RING
        exclusionReason(input) == null -> StillEngine.CAMERA2
        else -> StillEngine.CAMERAX
    }

    /** True when this shot should be taken on the Camera2 session (any path). */
    fun usesCamera2(input: StillEngineInput): Boolean = select(input) != StillEngine.CAMERAX

    /** True when this shot should be served from the reprocess ZSL ring. */
    fun usesRing(input: StillEngineInput): Boolean = select(input) == StillEngine.CAMERA2_RING

    /** Why [input] cannot use the ring, or null when it can. */
    fun ringExclusionReason(input: StillEngineInput): String? = when {
        !input.ringEnabled -> "reprocess ring gate off"
        !input.reprocessSupported -> "YUV reprocessing not supported for this lens"
        !input.hasRingOutput -> "no YUV ring output on the Camera2 session"
        exclusionReason(input) != null -> exclusionReason(input)
        else -> null
    }

    /** Why HAL reprocessing cannot run for [input], or null when it can. */
    fun reprocessExclusionReason(input: StillEngineInput): String? = when {
        ringExclusionReason(input) != null -> ringExclusionReason(input)
        !input.reprocessSupported -> "YUV reprocessing not supported for this lens"
        else -> null
    }

    /** True when the ring frame should go through ImageWriter reprocessing. */
    fun usesReprocess(input: StillEngineInput): Boolean =
        reprocessExclusionReason(input) == null

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
