package com.vayunmathur.library.avatar

import com.vayunmathur.library.log.Log
import com.vayunmathur.library.log.NativeLoader

/**
 * The 3D avatar engine bridge, shaped like kart's `KartNative` and
 * `MapNative`.
 *
 * Native calls are no-ops behind [isAvailable] = false (no `libyuu_avatar`
 * on this device), and hero surfaces fall back to the placeholder instead. The
 * signatures below are the contract the Rust engine implements.
 *
 * Surface lifecycle mirrors kart/voxels: each view owns a stable [surfaceId]
 * (Kotlin-owned, passed on every call), the engine spawns a 60 Hz render
 * thread per surface and ticks the avatar there. Traits + pose cross as JSON
 * strings; the pose gallery reads back the preset ids.
 */
internal object YuuAvatarNative {
    /** False until `libyuu_avatar` ships. Checked once per surface, like kart. */
    val isAvailable: Boolean = NativeLoader.load("yuu_avatar", "YuuAvatarNative") && initEngine()

    /**
     * Load + init are one guarded unit: [NativeLoader.load] only loads, and
     * `nativeInit` can fail with any `Throwable` (`UnsatisfiedLinkError` if a
     * symbol is missing, `NoClassDefFoundError`/`LinkageError` from the JNI
     * registration, ...), not just the two errors the hand-rolled guard used
     * to catch. Any failure leaves [isAvailable] false so hero surfaces use
     * the placeholder instead of poisoning this class's initializer. The
     * `nativeInit` return value stays ignored, as before. Logging is
     * `runCatching`-guarded for the same host-JVM reason as in [NativeLoader]:
     * `Log` can throw on a plain JVM, and that must not escape class init.
     */
    private fun initEngine(): Boolean = try {
        nativeInit()
        true
    } catch (failure: Throwable) {
        runCatching {
            Log.error("YuuAvatarNative", "libyuu_avatar init failed - using placeholder", failure)
        }
        false
    }

    /**
     * Set traits + pose for a surface. `configJson` is the `YuuFaceConfig`
     * JSON; `poseJson` is a preset id (e.g. "wave") or `{"id": ...}`. No-op
     * without the library.
     */
    fun setConfig(surfaceId: Int, configJson: String, poseJson: String) {
        if (!isAvailable) return
        nativeSetConfig(surfaceId, configJson, poseJson)
    }

    /** Pose preset ids as JSON (`["idle","wave",...]`) for the gallery. */
    fun getPosesJson(): String = if (isAvailable) nativeGetPosesJson() else "[]"

    /**
     * Hand the bundled `base_upperbody.glb` to native via a detached APK fd
     * (ClipHandle pattern — avoids a multi-MB heap copy). The engine parses
     * it into the shared mesh on the next surface creation; absent asset
     * keeps the procedural fallback.
     */
    fun setBundledMeshFd(fd: Int, offset: Long, length: Long) {
        if (!isAvailable) return
        nativeSetBundledMesh(fd, offset, length)
    }

    /** Surface lifecycle, called by the avatar surface view. */
    fun surfaceCreated(surface: android.view.Surface, surfaceId: Int) {
        if (!isAvailable) return
        nativeSurfaceCreated(surface, surfaceId)
    }

    fun surfaceChanged(surfaceId: Int, width: Int, height: Int) {
        if (!isAvailable) return
        nativeSurfaceChanged(surfaceId, width, height)
    }

    fun surfaceDestroyed(surfaceId: Int) {
        if (!isAvailable) return
        nativeSurfaceDestroyed(surfaceId)
    }

    fun setPaused(surfaceId: Int, paused: Boolean) {
        if (!isAvailable) return
        nativeSetPaused(surfaceId, paused)
    }

    /**
     * User drag-rotate delta for a surface, in radians. No-op without the
     * library. Pitch clamps native-side; yaw is unlimited.
     */
    fun onRotateInput(surfaceId: Int, yawDelta: Float, pitchDelta: Float) {
        if (!isAvailable) return
        nativeOnRotateInput(surfaceId, yawDelta, pitchDelta)
    }

    /** Set whether a surface auto-orbits (editor disables on creation). */
    fun setAutoRotate(surfaceId: Int, autoRotate: Boolean) {
        if (!isAvailable) return
        nativeSetAutoRotate(surfaceId, autoRotate)
    }

    fun onDestroy() {
        if (!isAvailable) return
        nativeOnDestroy()
    }

    private external fun nativeInit(): Boolean
    private external fun nativeSetConfig(surfaceId: Int, configJson: String, poseJson: String)
    private external fun nativeGetPosesJson(): String
    private external fun nativeSetBundledMesh(fd: Int, offset: Long, length: Long)
    private external fun nativeSurfaceCreated(surface: android.view.Surface, surfaceId: Int)
    private external fun nativeSurfaceChanged(surfaceId: Int, width: Int, height: Int)
    private external fun nativeSurfaceDestroyed(surfaceId: Int)
    private external fun nativeSetPaused(surfaceId: Int, paused: Boolean)
    private external fun nativeOnRotateInput(surfaceId: Int, yawDelta: Float, pitchDelta: Float)
    private external fun nativeSetAutoRotate(surfaceId: Int, autoRotate: Boolean)
    private external fun nativeOnDestroy()
}
