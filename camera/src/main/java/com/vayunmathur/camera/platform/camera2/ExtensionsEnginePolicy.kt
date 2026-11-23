package com.vayunmathur.camera.platform.camera2

import com.vayunmathur.camera.platform.CameraMode

/** Which engine produces a night still. */
enum class NightEngine {
    /** Camera2 `CameraExtensionSession` with `EXTENSION_NIGHT` (Phase 6). */
    CAMERA2_EXTENSION,

    /** Our own burst + Rust merge (`captureNightPhotoCustom`, unchanged). */
    OWN_BURST,
}

/** Inputs to the night-engine selection rule. */
data class ExtensionsEngineInput(
    val mode: CameraMode,
    /** [Camera2ExtensionProbe] result for this lens (probe ok = usable). */
    val camera2ProbeOk: Boolean = false,
    /**
     * The persisted failure cache is clear (`!nightExtFailedRecently()` —
     * the same 7-day `night_ext_failed_at` store both engines record into;
     * see `CameraNightMode.kt`). A recent bind/async failure on *either*
     * engine suppresses *both* extensions, exactly as today.
     */
    val failureCacheClear: Boolean = true,
)

/**
 * Explicit selection rule for night captures, mirroring
 * [StillEnginePolicy]/[SloMoEnginePolicy]:
 *
 * 1. Camera2 extension only when the gate is on, the mode is PHOTO, the
 *    Camera2 probe passes, and the failure cache is clear.
 * 2. Else the CameraX extension when it is usable (its usable flag is
 *    already failure-cache gated by `refreshNightExtensionUsable`, and the
 *    explicit cache check here keeps a Camera2-recorded failure from
 *    immediately retrying through CameraX within the same decision).
 * 3. Else our own burst, unchanged.
 *
 * Extension strength UI: CameraX surfaces a live processing strength
 * (`CameraExtensionsInfo.getExtensionStrength()`); there is no Camera2
 * equivalent and no strength flow on the extension session, so the
 * indicator is shown only on the CameraX-extension path — never faked
 * from latency or frame counts on Camera2 (see [showsExtensionStrength]).
 */
object ExtensionsEnginePolicy {

    fun select(input: ExtensionsEngineInput): NightEngine = when {
        !input.failureCacheClear -> NightEngine.OWN_BURST
        input.mode == CameraMode.PHOTO && input.camera2ProbeOk ->
            NightEngine.CAMERA2_EXTENSION
        else -> NightEngine.OWN_BURST
    }

    fun usesCamera2Extension(input: ExtensionsEngineInput): Boolean =
        select(input) == NightEngine.CAMERA2_EXTENSION

    /** Why Camera2 did not take this capture, or null when it did. */
    fun camera2ExclusionReason(input: ExtensionsEngineInput): String? = when {
        !input.failureCacheClear -> "night extension failure cache still warm (7-day TTL)"
        input.mode != CameraMode.PHOTO -> "mode ${input.mode} is not PHOTO"
        !input.camera2ProbeOk -> "Camera2 NIGHT extension probe failed for this lens"
        else -> null
    }

    /**
     * True only when the live strength indicator may be shown: a strength
     * value exists *and* the serving engine is the CameraX extension. On
     * the Camera2 path the indicator is hidden (no equivalent flow).
     */
    fun showsExtensionStrength(engine: NightEngine, strength: Int?): Boolean =
        engine == NightEngine.CAMERA2_EXTENSION && strength != null
}
