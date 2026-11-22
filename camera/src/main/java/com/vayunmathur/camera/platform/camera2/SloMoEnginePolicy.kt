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
     * muxer) end to end. False today: `Camera2HighSpeedSession` can stream
     * to an encoder surface, but the Phase-1 engine still opens its own
     * device, so no live recording may be routed here yet. Device
     * verification (encoder honouring `KEY_CAPTURE_RATE`, ffprobe of the
     * output) is also outstanding — see the Phase-5 report.
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
