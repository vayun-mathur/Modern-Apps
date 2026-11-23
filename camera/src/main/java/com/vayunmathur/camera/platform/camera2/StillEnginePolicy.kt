package com.vayunmathur.camera.platform.camera2

import com.vayunmathur.camera.platform.CameraMode

/** Which Camera2 route takes a still. */
enum class StillEngine {
    /** Direct still on the Camera2 session. */
    CAMERA2,

    /** Camera2 ZSL ring: an already-exposed YUV frame, reprocessed or direct-encoded. */
    CAMERA2_RING,

    /** A dedicated Camera2 route (night, processed, Motion Photo, burst, capture-for-result). */
    CAMERA2_ROUTED,
}

/**
 * Inputs to the still-route selection rule. Every flag describes a shot
 * that is not a plain direct still; those shots take their dedicated
 * Camera2 route instead (night burst/extension, processed bake, Motion
 * Photo, burst loop, capture-for-result — all session-based).
 */
data class StillEngineInput(
    val mode: CameraMode,
    /** A JPEG `ImageReader` output was configured on the Camera2 session. */
    val hasJpegOutput: Boolean = false,
    /** Night route: vendor extension or custom burst/long-exposure path. */
    val nightRoute: Boolean = false,
    /** Warmth/shadows/portrait bake applied after the still. */
    val processedRoute: Boolean = false,
    /** Motion Photo (JPEG + MP4 trailer from the analysis ring buffer). */
    val motionPhotoRoute: Boolean = false,
    /** Capture-for-result (`ACTION_IMAGE_CAPTURE`) for a caller. */
    val captureForResult: Boolean = false,
    /** Press-and-hold burst (a sequence, not a plain single capture). */
    val burst: Boolean = false,
    /** Reprocess-ring availability for this bind. */
    val ringEnabled: Boolean = false,
    /** Facade snapshot reports YUV reprocessing for this lens. */
    val reprocessSupported: Boolean = false,
    /** A YUV ring output is configured and holding frames on the session. */
    val hasRingOutput: Boolean = false,
)

/**
 * Explicit selection rule for stills: a plain single PHOTO capture on a
 * session with a JPEG output takes the direct Camera2 still (or the ZSL
 * ring when it is eligible); every other shot takes its dedicated
 * Camera2 route. There is no other engine.
 */
object StillEnginePolicy {

    fun select(input: StillEngineInput): StillEngine = when {
        ringExclusionReason(input) == null -> StillEngine.CAMERA2_RING
        exclusionReason(input) == null -> StillEngine.CAMERA2
        else -> StillEngine.CAMERA2_ROUTED
    }

    /** True when this shot takes the plain direct/ring Camera2 still route. */
    fun usesCamera2(input: StillEngineInput): Boolean = select(input) != StillEngine.CAMERA2_ROUTED

    /** True when this shot should be served from the reprocess ZSL ring. */
    fun usesRing(input: StillEngineInput): Boolean = select(input) == StillEngine.CAMERA2_RING

    /** Why [input] cannot use the ring, or null when it can. */
    fun ringExclusionReason(input: StillEngineInput): String? = when {
        !input.ringEnabled -> "reprocess ring not enabled for this bind"
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

    /** Why [input] cannot take the plain direct still route, or null when it can. */
    fun exclusionReason(input: StillEngineInput): String? = when {
        input.mode != CameraMode.PHOTO -> "mode ${input.mode} takes its dedicated Camera2 route"
        !input.hasJpegOutput -> "no JPEG output on the Camera2 session"
        input.nightRoute -> "night captures take the night route"
        input.processedRoute -> "processed (bake/bokeh) captures take the processed route"
        input.motionPhotoRoute -> "Motion Photo takes its own route"
        input.captureForResult -> "capture-for-result takes its own route"
        input.burst -> "burst takes its own route"
        else -> null
    }
}
