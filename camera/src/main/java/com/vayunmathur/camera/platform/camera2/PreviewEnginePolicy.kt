package com.vayunmathur.camera.platform.camera2

import com.vayunmathur.camera.platform.CameraMode

/** Which engine renders the viewfinder preview. */
enum class PreviewEngine { CAMERAX, CAMERA2 }

/**
 * Inputs to the preview-engine selection rule.
 *
 * @property camera2Enabled the per-mode Camera2 preview gate. Default ON:
 *   Camera2 is the live path unless the user opts out in Settings.
 * @property mode the camera mode the preview is for (PHOTO first).
 * @property fallbackLatched a Camera2 configure/bind failure already
 *   latched for this bind attempt: stay on CameraX, never retry-loop.
 */
data class PreviewEngineInput(
    val camera2Enabled: Boolean,
    val mode: CameraMode,
    val fallbackLatched: Boolean = false,
)

/**
 * Explicit selection rule for the two preview engines, mirroring
 * `RecordingPathPolicy` for recording: the Camera2 path is selected only
 * when the gate is explicitly on *and* the mode is one the Camera2 session
 * honestly supports. Phase 2 supports PHOTO only; Phase 4a adds plain
 * PHOTO stills on that preview (see `StillEnginePolicy` for which shots
 * still stay on CameraX), so every other mode stays on CameraX even with
 * the gate on.
 */
object PreviewEnginePolicy {

    /** Modes the Camera2 preview engine supports today (PHOTO first). */
    val supportedModes: Set<CameraMode> = setOf(CameraMode.PHOTO)

    fun select(input: PreviewEngineInput): PreviewEngine = when {
        !input.camera2Enabled -> PreviewEngine.CAMERAX
        input.fallbackLatched -> PreviewEngine.CAMERAX
        input.mode !in supportedModes -> PreviewEngine.CAMERAX
        else -> PreviewEngine.CAMERA2
    }

    /** True when [mode] should render through the Camera2 preview engine. */
    fun usesCamera2(enabled: Boolean, mode: CameraMode, fallbackLatched: Boolean = false): Boolean =
        select(PreviewEngineInput(enabled, mode, fallbackLatched)) == PreviewEngine.CAMERA2
}

/**
 * One-shot fallback latch for one Camera2 bind attempt. The first
 * configure/bind failure for [bindKey] latches and reports `true` (the
 * caller switches that session to CameraX); further failures for the
 * same key report `false` so a failing device cannot loop
 * Camera2 -> CameraX -> Camera2. A different key (lens/mode), a manual
 * retry, re-enabling the gate, or [reset] starts a fresh attempt.
 * Pure; its owner (the ViewModel) publishes the latched key via a
 * StateFlow, so executor-thread failures are safely visible to the UI.
 */
class Camera2FallbackLatch {
    private var latchedKey: String? = null

    val latchedBindKey: String? get() = latchedKey

    fun isLatched(bindKey: String): Boolean = latchedKey == bindKey

    /** Records a failure; true only for the first failure of this key. */
    fun recordFailure(bindKey: String): Boolean {
        if (latchedKey == bindKey) return false
        latchedKey = bindKey
        return true
    }

    fun clear(bindKey: String) {
        if (latchedKey == bindKey) latchedKey = null
    }

    fun reset() {
        latchedKey = null
    }

    companion object {
        /** Stable key for one preview bind attempt (lens + mode). */
        fun bindKey(cameraId: String, mode: CameraMode): String = "$cameraId:${mode.name}"
    }
}
