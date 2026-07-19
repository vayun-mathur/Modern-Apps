package com.vayunmathur.camera.util

import android.util.Log

/**
 * JNI bridge to the native `camera_stitch` Rust library (feature-based panorama
 * stitcher + night burst aligner). Replaces the previous OpenCV dependency.
 *
 * Usage: newSession() -> addFrame()* -> stitch()/merge() -> free().
 */
object StitchNative {
    val isAvailable: Boolean = try {
        System.loadLibrary("camera_stitch")
        Log.i("StitchNative", "libcamera_stitch loaded")
        true
    } catch (t: Throwable) {
        Log.e("StitchNative", "System.loadLibrary(camera_stitch) failed", t)
        false
    }

    /** Opens a stitch session. [sphere] selects full-sphere vs flat panorama. */
    external fun newSession(sphere: Boolean): Long

    /** Adds one RGBA frame (row-major, length = width*height*4) with its gyro orientation (degrees). */
    external fun addFrame(handle: Long, rgba: ByteArray, width: Int, height: Int, yaw: Float, pitch: Float, roll: Float)

    /** Stitches the added frames into a panorama; returns JPEG bytes or null. Consumes the frames. */
    external fun stitch(handle: Long): ByteArray?

    /** Aligns + merges the added frames (night mode); returns JPEG bytes or null. Consumes the frames. */
    external fun merge(handle: Long): ByteArray?

    /** Releases the session. */
    external fun free(handle: Long)
}
