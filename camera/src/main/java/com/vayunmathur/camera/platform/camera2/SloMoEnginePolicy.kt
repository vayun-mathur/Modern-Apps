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
     * muxer) end to end. False today — assessed 2026-11-22: coupling
     * `Camera2HighSpeedSession` to `Camera2VideoRecordingEngine` is NOT
     * mechanical. The exact missing pieces, in dependency order:
     *
     * 1. Encoder-surface handoff. The engine creates its codec input
     *    surface internally in `openCamera()` (`videoCodec.createInputSurface()`
     *    after `setupEncoders`) and never exposes it before the camera is
     *    opened. The high-speed session needs that surface *before* open,
     *    as a configure-time session target. Missing: a codec-first split
     *    of the engine (configure codec -> expose input surface -> start
     *    drain/muxer separately from camera open).
     * 2. Device/session ownership. Both classes open the same cameraId:
     *    the engine owns a private `CameraDevice`/`HandlerThread` and a
     *    *regular* capture session; the high-speed session owns the device
     *    through the driver seam and a *constrained* session
     *    (`SESSION_HIGH_SPEED` + `createHighSpeedRequestList`). The
     *    engine's regular `TEMPLATE_RECORD` request with a pinned FPS
     *    range cannot deliver 120/240 fps and must not be substituted.
     *    Missing: an engine variant that accepts an externally-owned
     *    session (or extracts the codec/muxer/drain core both can share).
     * 3. Recording lifecycle. `Camera2HighSpeedSession` has open/close
     *    only — no start/stop-recording boundary, no EOS/drain
     *    coordination with a codec. The engine's `stop()` assumes it owns
     *    session+device+codec. Missing: start/stop semantics on the
     *    high-speed session that signal EOS to the external codec and
     *    only then close, without closing caller-owned surfaces.
     * 4. Preview surface in SLOW_MO. `PreviewEnginePolicy` serves PHOTO
     *    only; SLOW_MO preview is a CameraX `surfaceRequest`. There is no
     *    Camera2 slo-mo preview composable/bridge to supply the
     *    high-speed session's preview target (mirroring `Camera2Preview`).
     * 5. Audio/muxer contract. CameraX slo-mo records *no* audio; the
     *    engine always starts `AudioRecord`+AAC and its muxer only starts
     *    once *both* tracks appear. Missing: a no-audio mode (muxer starts
     *    on the video track alone) for the slo-mo configuration.
     * 6. Timestamp verification. The muxer writes encoder `BufferInfo`
     *    PTS as-is; the pure `HighSpeedSelection.stretchedPtsUs` fallback
     *    is not wired into the drain path, and `validateOutput` does not
     *    check frame rate / stretched duration. Missing: either proof a
     *    device encoder honours `KEY_CAPTURE_RATE`, or muxer-side PTS
     *    stretching, plus read-back checks (and external ffprobe) that
     *    the output is slow, not realtime HFR.
     * 7. Save-path glue + fallback. `setupHighSpeedSession` /
     *    recording start-stop plumbing expects an engine that opens the
     *    camera itself (`VideoRecordingEngine.start`). Missing: glue that
     *    tears CameraX down, builds engine+high-speed session together,
     *    routes the file through `saveVideoStaged`, and on any failure
     *    rebinds the CameraX HFR ladder (fallback-latch philosophy) —
     *    plus the device verification in (6) before this flag may flip.
     *
     * Until (1)-(7) land, no live recording may be routed here.
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
