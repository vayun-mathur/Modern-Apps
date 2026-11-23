package com.vayunmathur.camera.platform.camera2

import com.vayunmathur.camera.platform.CameraMode

/**
 * Preview-engine selection: Camera2 is the only preview engine. Every
 * mode is supported because each mode's capture/analysis also lives on
 * the Camera2 session (stills, recording, slo-mo, night extension,
 * analysis bitmaps). A latched bind failure does not select another
 * engine — it surfaces the bind-failed error UI with retry.
 */
object PreviewEnginePolicy {

    /** Modes the Camera2 preview engine supports: all of them. */
    val supportedModes: Set<CameraMode> = CameraMode.entries.toSet()

    /**
     * Why the preview cannot serve this bind, or null when it can. The
     * only exclusion left is a latched failure for this exact bind,
     * which keeps a failing device from retry-looping inside one bind;
     * a manual retry clears it.
     */
    fun exclusionReason(mode: CameraMode, fallbackLatched: Boolean = false): String? = when {
        fallbackLatched -> "camera2 bind failure latched for this session"
        mode !in supportedModes -> "mode $mode is not supported"
        else -> null
    }

    /** True when [mode] should render through the Camera2 preview engine. */
    fun usesCamera2(mode: CameraMode, fallbackLatched: Boolean = false): Boolean =
        exclusionReason(mode, fallbackLatched) == null
}

/**
 * One-shot latch for one Camera2 bind attempt. The first configure/bind
 * failure for [bindKey] latches and reports `true` (the caller surfaces
 * the bind-failed error with retry); further failures for the same key
 * report `false` so a failing device cannot loop. A different key
 * (lens/mode), a manual retry, or [reset] starts a fresh attempt. Pure;
 * its owner (the ViewModel) publishes the latched key via a StateFlow,
 * so executor-thread failures are safely visible to the UI.
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
