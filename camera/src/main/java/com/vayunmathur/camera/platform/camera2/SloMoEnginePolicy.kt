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
     * muxer) end to end. The seven pieces, in dependency order (see
     * [SloMoRecordingReadiness], whose per-piece flags mirror this list):
     *
     * 1. Encoder-surface handoff. DONE: the engine is codec-first —
     *    `prepareEncoderSurface()` configures the video codec and exposes
     *    its input surface before any camera open, and `start` reuses it;
     *    the ordering itself is the pure, tested [EncoderSurfaceHandoff].
     * 2. Device/session ownership. DONE: the codec/muxer/drain core is
     *    extracted (`Camera2RecordingCore`, [SharedRecordingCoreLifecycle])
     *    and runs under an externally-owned session; the engine delegates
     *    to the same core for its regular session. The engine's regular
     *    `TEMPLATE_RECORD` request is never substituted for the
     *    constrained high-speed request.
     * 3. Recording lifecycle. DONE: `Camera2HighSpeedSession.stopRecording`
     *    stops the repeating request before signalling EOS on the shared
     *    core and never closes caller-owned surfaces.
     * 4. Preview surface in SLOW_MO. DONE: `Camera2SloMoPreview` supplies
     *    the high-speed session's preview target behind the slo-mo gate
     *    ([SloMoPreviewPolicy]); live selection additionally requires
     *    [recordingEngineReady], so it is not composed live yet. See also
     *    [PreviewEnginePolicy]'s SLOW_MO exclusion.
     * 5. Audio/muxer contract. DONE: the engine/core take a no-audio
     *    configuration (`audioEnabled = false`, CameraX slo-mo's
     *    contract) — no AudioRecord/AAC is created and the muxer starts
     *    on the video track alone via [MuxerStartPolicy].
     * 6. Timestamp verification. OPEN (device verification, out of scope
     *    for the structural work): the muxer writes encoder PTS as-is;
     *    `stretchedPtsUs` is not wired into the drain path and
     *    `validateOutput` checks neither frame rate nor stretched
     *    duration. Needs device proof an encoder honours
     *    `KEY_CAPTURE_RATE` (or muxer-side stretching) plus read-back
     *    and external ffprobe checks that output is slow, not realtime.
     *    This piece alone keeps [recordingEngineReady] false.
     * 7. Save-path glue + fallback. DONE: `handleCamera2SloMoFinalized`
     *    routes a finished file through `saveVideoStaged`; on failure it
     *    discards the file, latches this bind one-shot
     *    (`Camera2FallbackLatch`, mirroring preview's), and rebinds the
     *    CameraX HFR ladder via `setupHighSpeedSession`. Callers pass
     *    [SloMoRecordingReadiness.recordingEngineReady] (false today,
     *    solely because of piece 6).
     *
     * Until piece 6 lands, no live recording may be routed here.
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
