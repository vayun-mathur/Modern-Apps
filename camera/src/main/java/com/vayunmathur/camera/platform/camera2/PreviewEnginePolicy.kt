package com.vayunmathur.camera.platform.camera2

import com.vayunmathur.camera.platform.CameraMode

/** Which engine renders the viewfinder preview. */
enum class PreviewEngine { CAMERAX, CAMERA2 }

/**
 * Inputs to the preview-engine selection rule.
 *
 * @property camera2Enabled the per-mode Camera2 preview gate. Default OFF:
 *   CameraX remains the live path and the Camera2 engine is never built.
 * @property mode the camera mode the preview is for (PHOTO first).
 */
data class PreviewEngineInput(
    val camera2Enabled: Boolean,
    val mode: CameraMode,
)

/**
 * Explicit selection rule for the two preview engines, mirroring
 * `RecordingPathPolicy` for recording: the Camera2 path is selected only
 * when the gate is explicitly on *and* the mode is one the Camera2 session
 * honestly supports. Phase 2 supports PHOTO only (preview + controls, no
 * still capture yet), so every other mode stays on CameraX even with the
 * gate on.
 */
object PreviewEnginePolicy {

    /** Modes the Camera2 preview engine supports today (PHOTO first). */
    val supportedModes: Set<CameraMode> = setOf(CameraMode.PHOTO)

    fun select(input: PreviewEngineInput): PreviewEngine = when {
        !input.camera2Enabled -> PreviewEngine.CAMERAX
        input.mode !in supportedModes -> PreviewEngine.CAMERAX
        else -> PreviewEngine.CAMERA2
    }

    /** True when [mode] should render through the Camera2 preview engine. */
    fun usesCamera2(enabled: Boolean, mode: CameraMode): Boolean =
        select(PreviewEngineInput(enabled, mode)) == PreviewEngine.CAMERA2
}
