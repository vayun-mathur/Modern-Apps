package com.vayunmathur.camera.platform

import com.vayunmathur.camera.domain.LumaPlaneMath
import com.vayunmathur.camera.domain.LumaStats
import com.vayunmathur.camera.domain.PlaneView
import com.vayunmathur.library.log.NativeLoader
import java.nio.ByteBuffer


/**
 * JNI bridge to the native `camera_stitch` Rust library (feature-based panorama
 * stitcher + night burst aligner). Replaces the previous OpenCV dependency.
 *
 * Usage: newSession() -> addFrame()* -> stitch()/merge() -> free().
 */
object StitchNative {
    val isAvailable: Boolean = NativeLoader.load("camera_stitch", "StitchNative")

    /** Opens a stitch session. [sphere] selects full-sphere vs flat panorama. */
    external fun newSession(sphere: Boolean): Long

    /** Adds one JPEG-compressed frame with its gyro orientation (degrees). Decoded on demand at stitch time. */
    external fun addFrame(handle: Long, jpeg: ByteArray, yaw: Float, pitch: Float, roll: Float)

    /** Stitches the added frames into a panorama; returns JPEG bytes or null. Consumes the frames. */
    external fun stitch(handle: Long): ByteArray?

    /**
     * Runs registration only (features/match/estimate/BA/wave) and returns a
     * compact binary blob describing the compose canvas + per-frame camera
     * solutions for the GPU compositor. Does NOT consume the frames, so a CPU
     * [stitch] fallback can still run. Returns null on failure.
     */
    external fun estimate(handle: Long): ByteArray?

    /** Aligns + merges the added frames (night mode, JPEG path); returns JPEG bytes or null. Consumes the frames. */
    external fun merge(handle: Long): ByteArray?

    /** Releases the session (also cleans night registry if mixed). */
    external fun free(handle: Long)

    // --- Lossless night path: RGBA frames without double JPEG (no quality loss) ---

    /** Opens a night session that holds RGBA frames directly (lossless). */
    external fun newNightSession(): Long

    /** Adds one RGBA frame (len = w*h*4, R,G,B,A order) to a night session. */
    external fun addNightRgbaFrame(handle: Long, rgba: ByteArray, width: Int, height: Int)

    /** Aligns + merges the RGBA night session; returns JPEG bytes or null. Consumes session. */
    external fun mergeNight(handle: Long): ByteArray?

    /** Releases a night session (also cleans pano registry if mixed). */
    external fun freeNight(handle: Long)

    /**
     * Encodes one RGBA frame (len = width*height*4) to AVIF bytes, or null on
     * failure. [quality] is 1..100, [speed] is rav1e's 1..10 (10 = fastest).
     * Callers must check [isAvailable] first and fall back to JPEG on null.
     */
    external fun encodeAvif(rgba: ByteArray, width: Int, height: Int, quality: Float, speed: Int): ByteArray?

    // --- Zero-copy luma on-ramp (Camera2 migration Phase 3) ---

    /**
     * Luma statistics from a *direct* Y-plane [buffer], read in place by
     * Rust — no bytes cross JNI. Returns
     * `[mean, gridWidth, gridHeight, grid...]`, or null for a non-direct /
     * unreadable buffer, in which case [lumaStats] falls back to the
     * identical Kotlin computation in [LumaPlaneMath]. This is the on-ramp
     * for moving the night-hysteresis / motion math native later; today
     * callers consume the same [LumaStats] either way.
     */
    external fun lumaStatsFromYPlane(
        buffer: ByteBuffer,
        width: Int,
        height: Int,
        rowStride: Int,
        pixelStride: Int,
    ): FloatArray?

    /**
     * [LumaStats] for [plane]: native when the buffer is direct and the
     * library is loaded, the Kotlin fallback otherwise. Both implement the
     * one contract in [LumaPlaneMath]; parity is proven on synthetic
     * buffers in `LumaStatsNativeTest`.
     */
    fun lumaStats(plane: PlaneView): LumaStats? {
        if (isAvailable && plane.buffer.isDirect) {
            val encoded = try {
                lumaStatsFromYPlane(
                    plane.buffer, plane.width, plane.height, plane.rowStride, plane.pixelStride,
                )
            } catch (e: UnsatisfiedLinkError) {
                null
            }
            decodeLumaStats(encoded)?.let { return it }
        }
        return LumaPlaneMath.stats(plane)
    }
}

/** Decodes the `[mean, gridWidth, gridHeight, grid...]` native encoding. */
internal fun decodeLumaStats(encoded: FloatArray?): LumaStats? {
    if (encoded == null || encoded.size < 3) return null
    val gridWidth = encoded[1].toInt()
    val gridHeight = encoded[2].toInt()
    if (gridWidth <= 0 || gridHeight <= 0) return null
    if (encoded.size != 3 + gridWidth * gridHeight) return null
    return LumaStats(
        mean = encoded[0].toDouble(),
        grid = encoded.copyOfRange(3, encoded.size),
        gridWidth = gridWidth,
        gridHeight = gridHeight,
    )
}
