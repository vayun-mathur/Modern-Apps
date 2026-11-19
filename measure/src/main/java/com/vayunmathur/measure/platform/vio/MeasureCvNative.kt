package com.vayunmathur.measure.platform.vio

import com.vayunmathur.library.log.NativeLoader

import com.vayunmathur.measure.domain.Pose3
import com.vayunmathur.measure.domain.Vec3

/** One tracked correspondence, in pixels: previous -> current. [id] is stable for the feature's life. */
data class CvPair(
    val id: Long,
    val prevX: Double,
    val prevY: Double,
    val currX: Double,
    val currY: Double,
)

/** Per-frame output of the Rust CV core. */
data class CvFrameFlow(
    val firstFrame: Boolean,
    val pairs: List<CvPair>,
    /** Relative pose previous -> current (`X_curr = R * X_prev + t`, unit `t`). */
    val pose: Pose3?,
    /** Feature points seeded for the next frame, after top-up. */
    val pointCount: Int,
    val seeded: Int = 0,
    val forwardOk: Int = 0,
    val fbSurvivors: Int = 0,
    /** RANSAC inliers; -1 = did not run, -2 = ran but no pose. */
    val ransacInliers: Int = -1,
)

/**
 * JNI bridge to the in-tree Rust CV core (`measure/src/main/rust`, crate
 * `measure_cv`): Shi-Tomasi corners, pyramidal Lucas-Kanade tracking with a
 * forward-backward check, and essential-matrix pose recovery. No third-party
 * native CV library ships in the APK.
 *
 * [available] guards every call: Compose previews, JVM unit tests and any
 * host without the `.so` degrade to "AR unavailable" rather than crashing.
 */
object MeasureCvNative {

    val available: Boolean by lazy { NativeLoader.load("measure_cv", "MeasureCvNative") }

    fun createSession(fx: Double, fy: Double, cx: Double, cy: Double): Long =
        if (!available) 0L else nativeCreateSession(fx, fy, cx, cy)

    fun destroySession(handle: Long) {
        if (available && handle != 0L) nativeDestroySession(handle)
    }

    fun reset(handle: Long) {
        if (available && handle != 0L) nativeReset(handle)
    }

    /**
     * Feed one luminance plane. [rowStride] is passed through so the caller
     * can hand over the CameraX `ImageProxy` Y plane without repacking it.
     * Null when the session is unavailable or the frame was rejected.
     */
    fun processFrame(
        handle: Long,
        yPlane: ByteArray,
        width: Int,
        height: Int,
        rowStride: Int,
    ): CvFrameFlow? {
        if (!available || handle == 0L) return null
        val raw = nativeProcessFrame(handle, yPlane, width, height, rowStride)
        if (raw.size < HEADER_SIZE) return null
        val pairCount = raw[0].toInt()
        val pose = if (raw[1] != 0.0) {
            val r = DoubleArray(9) { raw[4 + it] }
            val t = Vec3(raw[13], raw[14], raw[15])
            val len = kotlin.math.sqrt(t.x * t.x + t.y * t.y + t.z * t.z)
            if (len > 1e-9) Pose3(r, Vec3(t.x / len, t.y / len, t.z / len)) else null
        } else {
            null
        }
        val pairs = ArrayList<CvPair>(pairCount)
        for (i in 0 until pairCount) {
            val base = HEADER_SIZE + i * VALUES_PER_PAIR
            if (base + LAST_PAIR_OFFSET >= raw.size) break
            pairs += CvPair(
                raw[base].toLong(),
                raw[base + 1], raw[base + 2], raw[base + 3], raw[base + LAST_PAIR_OFFSET],
            )
        }
        return CvFrameFlow(
            firstFrame = raw[2] != 0.0,
            pairs = pairs,
            pose = pose,
            pointCount = raw[3].toInt(),
            seeded = if (raw.size > 16) raw[16].toInt() else 0,
            forwardOk = if (raw.size > 17) raw[17].toInt() else 0,
            fbSurvivors = if (raw.size > 18) raw[18].toInt() else 0,
            ransacInliers = if (raw.size > 19) raw[19].toInt() else -1,
        )
    }

    private const val HEADER_SIZE = 20
    /** Values per tracked pair in the native array: id, prevX, prevY, currX, currY. */
    private const val VALUES_PER_PAIR = 5
    /** Offset of the last (current-y) value within one tracked pair. */
    private const val LAST_PAIR_OFFSET = 4

    private external fun nativeCreateSession(
        fx: Double,
        fy: Double,
        cx: Double,
        cy: Double,
    ): Long

    private external fun nativeDestroySession(handle: Long)

    private external fun nativeReset(handle: Long)

    private external fun nativeProcessFrame(
        handle: Long,
        yPlane: ByteArray,
        width: Int,
        height: Int,
        rowStride: Int,
    ): DoubleArray
}
