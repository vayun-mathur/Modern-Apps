package com.vayunmathur.camera.platform.camera2

import com.vayunmathur.camera.domain.LensFacing
import com.vayunmathur.camera.platform.CameraMode

/** Which engine records SLOW_MO. */
enum class SloMoEngine { CAMERAX, CAMERA2 }

/**
 * Gate for the Camera2 slo-mo path. Default ON in two places: this
 * constant (the compile-time master switch) and the ViewModel's runtime
 * flow. Recording still stays on CameraX until
 * [SloMoEngineInput.recordingEngineReady] is true (see that field): the
 * gate only enables the decision, it never fakes a recording route.
 */
object SloMoCamera2Gate {
    const val ENABLED: Boolean = true
}

/** Inputs to the slo-mo engine selection rule. */
data class SloMoEngineInput(
    /** The Phase-5 gate. Default ON; recording still needs every condition below. */
    val camera2Enabled: Boolean,
    val mode: CameraMode,
    /** Slo-mo is back-only today (enforced by the mode switch and here). */
    val lensFacing: LensFacing?,
    /** `CONSTRAINED_HIGH_SPEED_VIDEO` in the facade snapshot. */
    val hasConstrainedHighSpeed: Boolean = false,
    /** [HighSpeedPlanner] found a fixed HFR size/range on this lens. */
    val hasSelection: Boolean = false,
    /**
     * Some encoder for the selected size can run at the capture rate
     * (probed from `MediaCodec` capabilities by the glue). An encoder that
     * cannot take 120/240 fps input cannot honestly record slo-mo.
     */
    val encoderSupportsCaptureRate: Boolean = false,
    /**
     * The constrained session is coupled to a recording engine (codec +
     * muxer) end to end. False today — coupling
     * `Camera2HighSpeedSession` to `Camera2VideoRecordingEngine` is NOT
     * mechanical. The seven pieces, in dependency order, with status
     * after the structural work of 2026-11-22 (see
     * [SloMoRecordingReadiness], whose per-piece flags mirror this list):
     *
     * 1. Encoder-surface handoff. DONE (structural): the engine is now
     *    codec-first — `prepareEncoderSurface()` configures the video
     *    codec and exposes its input surface before any camera open, and
     *    `start` reuses it; the ordering itself is the pure, tested
     *    [EncoderSurfaceHandoff] phase machine.
     * 2. Device/session ownership. OPEN: both classes still open the same
     *    cameraId — the engine owns a private device and a *regular*
     *    session; the high-speed session owns the device through the
     *    driver seam and a *constrained* session. The engine's regular
     *    `TEMPLATE_RECORD` request cannot deliver 120/240 fps and must
     *    not be substituted. Still missing: extracting the codec/muxer/
     *    drain core so it can run under an externally-owned session.
     * 3. Recording lifecycle. PARTIAL: `Camera2HighSpeedSession` now has
     *    a stop boundary ([Camera2HighSpeedSession.stopRecording]) that
     *    stops the repeating request before signalling EOS and never
     *    closes caller-owned surfaces. Still missing: the shared core
     *    from (2) for the EOS signal to land on, and start semantics.
     * 4. Preview surface in SLOW_MO. OPEN: there is no Camera2 slo-mo
     *    preview composable/bridge to supply the high-speed session's
     *    preview target (mirroring `Camera2Preview`); see also
     *    [PreviewEnginePolicy]'s SLOW_MO exclusion.
     * 5. Audio/muxer contract. DONE (structural): the engine takes a
     *    no-audio configuration (`audioEnabled = false`, CameraX slo-mo's
     *    contract) — no AudioRecord/AAC is created and the muxer starts
     *    on the video track alone via the tested [MuxerStartPolicy].
     * 6. Timestamp verification. OPEN: the muxer writes encoder PTS
     *    as-is; `stretchedPtsUs` is not wired into the drain path and
     *    `validateOutput` checks neither frame rate nor stretched
     *    duration. Needs device proof an encoder honours
     *    `KEY_CAPTURE_RATE` (or muxer-side stretching) plus read-back
     *    and external ffprobe checks that output is slow, not realtime.
     * 7. Save-path glue + fallback. OPEN: glue that tears CameraX down,
     *    builds engine+high-speed session together, routes the file
     *    through `saveVideoStaged`, and on failure rebinds the CameraX
     *    HFR ladder — plus the device verification in (6) before this
     *    flag may flip. Callers should pass
     *    [SloMoRecordingReadiness.recordingEngineReady] (false today).
     *
     * Until (2), (4), (6) and (7) land, no live recording may be routed
     * here: with pieces 1/3/5 in place the remaining work is the shared
     * drain core, the preview bridge, and device verification — in that
     * order.
     */
    val recordingEngineReady: Boolean = false,
)

/**
 * Explicit selection rule for slo-mo, mirroring [StillEnginePolicy]:
 * Camera2 records only when the gate is on *and* every honest-support
 * condition holds. Anything else stays on the CameraX high-speed session,
 * exactly as today.
 */
object SloMoEnginePolicy {

    fun select(input: SloMoEngineInput): SloMoEngine =
        if (exclusionReason(input) == null) SloMoEngine.CAMERA2 else SloMoEngine.CAMERAX

    fun usesCamera2(input: SloMoEngineInput): Boolean = select(input) == SloMoEngine.CAMERA2

    /** Why [input] cannot use Camera2, or null when it can. */
    fun exclusionReason(input: SloMoEngineInput): String? = when {
        !input.camera2Enabled -> "camera2 slo-mo gate off"
        input.mode != CameraMode.SLOW_MO -> "mode ${input.mode} is not SLOW_MO"
        input.lensFacing == LensFacing.FRONT -> "slo-mo is back-camera only"
        !input.hasConstrainedHighSpeed -> "no CONSTRAINED_HIGH_SPEED_VIDEO capability"
        !input.hasSelection -> "no fixed HFR size/range for slo-mo"
        !input.encoderSupportsCaptureRate -> "no encoder supports the capture rate at the selected size"
        !input.recordingEngineReady ->
            "Camera2 slo-mo recording engine not coupled/device-verified yet"
        else -> null
    }
}
