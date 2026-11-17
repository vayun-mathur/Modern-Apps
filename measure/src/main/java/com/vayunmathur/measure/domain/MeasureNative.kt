// PACKAGE STRUCTURE EXCEPTION (JNI): FQN frozen for native RegisterNatives/symbol mangling
package com.vayunmathur.measure.domain

/**
 * JNI bridge to the Rust visual-inertial odometry engine (`measure/src/main/rust`).
 *
 * [available] guards every call: Compose previews, unit tests and any non-arm64 host
 * have no `.so` to load, and the measure UI degrades to "AR unavailable" rather than
 * crashing. Same pattern as the astronomy engine's native bridge.
 */
object MeasureNative {

    val available: Boolean = runCatching { System.loadLibrary("measure_vio") }.isSuccess

    /** Quality codes mirroring the Rust `vio::Quality` discriminants. */
    const val QUALITY_INITIALISING = 0
    const val QUALITY_LIMITED = 1
    const val QUALITY_GOOD = 2
    const val QUALITY_LOST = 3

    /** Returns a session handle, or 0 on failure. */
    fun createSession(fx: Double, fy: Double, cx: Double, cy: Double): Long =
        if (!available) 0L else nativeCreateSession(fx, fy, cx, cy)

    fun destroySession(handle: Long) {
        if (available && handle != 0L) nativeDestroySession(handle)
    }

    fun reset(handle: Long) {
        if (available && handle != 0L) nativeReset(handle)
    }

    /**
     * Feed one luminance plane. [rowStride] is passed through so the caller can hand
     * over the CameraX `ImageProxy` Y plane without repacking it first.
     */
    fun pushFrame(
        handle: Long,
        yPlane: ByteArray,
        width: Int,
        height: Int,
        rowStride: Int,
        timestampNs: Long,
    ): Int =
        if (!available || handle == 0L) QUALITY_LOST
        else nativePushFrame(handle, yPlane, width, height, rowStride, timestampNs)

    /**
     * Push a batch of IMU samples packed as `[tNs, gx, gy, gz, ax, ay, az]` per sample.
     * Batched because at 400 Hz per-sample JNI overhead would exceed the integration cost.
     */
    fun pushImu(handle: Long, packed: DoubleArray) {
        if (available && handle != 0L && packed.isNotEmpty()) nativePushImu(handle, packed)
    }

    /**
     * Screen tap to metric world point: `[x, y, z, onPlane]`, or null while tracking
     * has no metric scale yet.
     */
    fun rayToWorld(handle: Long, px: Float, py: Float): DoubleArray? {
        if (!available || handle == 0L) return null
        return nativeRayToWorld(handle, px, py)?.takeIf { it.size == 4 }
    }

    /**
     * All sane hit candidates along a screen ray, parsed from the flat
     * native array. Selection policy ([HitSelector]) is applied by the
     * caller so it stays unit-testable; empty when not measurable.
     */
    fun hitTest(handle: Long, px: Float, py: Float): List<HitCandidate> {
        if (!available || handle == 0L) return emptyList()
        val flat = nativeHitTest(handle, px, py) ?: return emptyList()
        if (flat.size % HIT_CANDIDATE_STRIDE != 0) return emptyList()
        return flat.toList().chunked(HIT_CANDIDATE_STRIDE).mapNotNull { c ->
            val kind = when (c[0].toInt()) {
                KIND_PLANE -> HitKind.Plane
                KIND_FEATURE -> HitKind.FeaturePoint
                else -> return@mapNotNull null
            }
            HitCandidate(
                kind = kind,
                position = Vec3(c[1], c[2], c[3]),
                distanceM = c[4],
                angularErrorDeg = c[5],
                normalDotUp = c[6].takeIf { it >= 0.0 },
            )
        }
    }

    private const val HIT_CANDIDATE_STRIDE = 7
    private const val KIND_PLANE = 0
    private const val KIND_FEATURE = 1

    /**
     * `[quality, scaleConfidence, landmarkCount, trackedCount, hasPlane, gx, gy, gz]`,
     * or null when the handle is unknown.
     */
    fun getState(handle: Long): DoubleArray? {
        if (!available || handle == 0L) return null
        return nativeGetState(handle)?.takeIf { it.size == 8 }
    }

    /**
     * Project metric world points to normalised screen coordinates.
     * Input is a flat `[x, y, z]` array; output is `[nx, ny, visible]` per point.
     */
    fun projectPoints(handle: Long, world: DoubleArray, width: Double, height: Double): DoubleArray {
        if (!available || handle == 0L || world.isEmpty()) return DoubleArray(0)
        return nativeProjectPoints(handle, world, width, height) ?: DoubleArray(0)
    }

    private external fun nativeCreateSession(fx: Double, fy: Double, cx: Double, cy: Double): Long
    private external fun nativeDestroySession(handle: Long)
    private external fun nativeReset(handle: Long)
    private external fun nativePushFrame(
        handle: Long,
        yPlane: ByteArray,
        width: Int,
        height: Int,
        rowStride: Int,
        timestampNs: Long,
    ): Int

    private external fun nativePushImu(handle: Long, packed: DoubleArray)
    private external fun nativeRayToWorld(handle: Long, px: Float, py: Float): DoubleArray?
    private external fun nativeHitTest(handle: Long, px: Float, py: Float): DoubleArray?
    private external fun nativeGetState(handle: Long): DoubleArray?
    private external fun nativeProjectPoints(
        handle: Long,
        world: DoubleArray,
        width: Double,
        height: Double,
    ): DoubleArray?
}
