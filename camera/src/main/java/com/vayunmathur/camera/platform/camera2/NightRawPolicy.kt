package com.vayunmathur.camera.platform.camera2

import com.vayunmathur.camera.platform.CameraMode

/** Inputs to the RAW-night selection rule. */
data class RawNightInput(
    /** Compile-time [RawRingGate] AND the runtime explicit RAW selection. */
    val rawGateOn: Boolean,
    /** The user explicitly selected RAW night (never inferred from "it's dark"). */
    val explicitRawSelected: Boolean,
    val mode: CameraMode,
    /** This shutter is the night route (auto night / night mode), not plain PHOTO. */
    val nightRoute: Boolean,
    /** Facade reports the RAW capability plus a RAW stream size. */
    val rawSupported: Boolean,
    /** A RAW ring output is configured and holding frames on the session. */
    val hasRawRingOutput: Boolean,
    /** DNG metadata assembled completely (see [DngMetadataAssembler]). */
    val dngMetadataComplete: Boolean = false,
)

/**
 * Explicit selection rule for the RAW night merge (depth Phase 2).
 *
 * RAW is never the silent default: it takes a night shutter only when the
 * gate is on, the user explicitly selected RAW night, the facade proves
 * RAW support, and a RAW ring is live. Everything else stays on the
 * existing night routing (vendor extension, then the YUV burst) exactly
 * as before. [RawNightInput.dngMetadataComplete] gates nothing here —
 * it is carried so the report/UI can state whether a DNG sidecar could
 * even be assembled; this phase writes none (see `DngMetadata.kt`).
 */
object RawNightPolicy {

    fun usesRawNight(input: RawNightInput): Boolean = exclusionReason(input) == null

    /** Why [input] cannot use the RAW night merge, or null when it can. */
    fun exclusionReason(input: RawNightInput): String? = when {
        !input.rawGateOn -> "raw ring gate off"
        !input.explicitRawSelected -> "RAW night not explicitly selected"
        !input.nightRoute -> "not a night capture"
        input.mode != CameraMode.PHOTO -> "mode ${input.mode} is not PHOTO"
        !input.rawSupported -> "RAW not supported for this lens"
        !input.hasRawRingOutput -> "no RAW ring output on the Camera2 session"
        else -> null
    }
}
