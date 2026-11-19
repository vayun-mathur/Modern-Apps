package com.vayunmathur.camera.platform

import com.vayunmathur.library.log.NativeLoader


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
}
