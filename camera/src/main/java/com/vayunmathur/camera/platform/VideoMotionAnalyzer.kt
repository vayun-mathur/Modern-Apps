package com.vayunmathur.camera.platform

import android.os.SystemClock
import com.vayunmathur.camera.domain.FrameDiffSample
import com.vayunmathur.camera.domain.LumaPlaneMath
import com.vayunmathur.camera.domain.PlaneView
import com.vayunmathur.camera.domain.SceneMotionMath
import com.vayunmathur.library.log.Log

/** Minimum gap between motion samples: ~5 Hz is plenty for a scene classification. */
internal const val VIDEO_MOTION_SAMPLE_INTERVAL_MS = 200L

/** Longest side of the luminance grid the frame difference runs over (~9k samples). */
internal const val VIDEO_MOTION_GRID_MAX_SIDE = 96

/**
 * Cheapest motion signal for the video preview: frame-difference over a heavily
 * downsampled Y-plane grid, reusing the PhotoAnalyzer pattern (read plane 0 once,
 * stride it, close the proxy on every path).
 *
 * VIDEO mode binds no analyzer today, so this is a new dedicated ImageAnalysis
 * stream for the video session (the photo stream's PhotoAnalyzer is not reused:
 * it also pays for ZXing and Motion-Photo bitmap copies). Only a grid vs the
 * previous kept grid is compared — no bitmaps, no per-frame allocation beyond
 * the two grids — and sampling is capped at ~5 Hz on the shared analysis thread.
 *
 * Emits [FrameDiffSample]s; the [SceneMotionEstimator] smoothing/classification
 * lives in the ViewModel so it survives analyzer re-creation across binds.
 */
class VideoMotionAnalyzer(
    private val onSample: (FrameDiffSample) -> Unit,
) {
    private var lastSampleAtMs = 0L
    private var previousGrid: FloatArray? = null
    private var previousGridWidth = 0

    /**
     * Plane-based entry point shared by the CameraX analyzer below and the
     * Camera2 ImageReader analysis source: same cadence, same grid, same
     * diff — the logic is not forked per pipeline.
     */
    fun onYPlane(plane: PlaneView) {
        val nowMs = SystemClock.elapsedRealtime()
        if (nowMs - lastSampleAtMs < VIDEO_MOTION_SAMPLE_INTERVAL_MS) return
        lastSampleAtMs = nowMs
        val (grid, gridWidth) = LumaPlaneMath.grid(plane) ?: return
        val previous = previousGrid
        previousGrid = grid
        if (previous == null || previous.size != grid.size || previousGridWidth != gridWidth) {
            previousGridWidth = gridWidth
            return // first frame of a (re)bind: no diff to take yet
        }
        previousGridWidth = gridWidth
        SceneMotionMath.sample(previous, grid, gridWidth)?.let { sample ->
            try {
                onSample(sample)
            } catch (e: IllegalStateException) {
                Log.error("VideoMotion", "onSample callback threw", e)
            } catch (e: IllegalArgumentException) {
                Log.error("VideoMotion", "onSample callback threw", e)
            }
        }
    }

}

/**
 * Downsamples a Y plane to a grid of at most [VIDEO_MOTION_GRID_MAX_SIDE] samples
 * per side. Shared by the CameraX [VideoMotionAnalyzer] and the Camera2 custom
 * path's ImageReader analysis surface, so both pipelines feed the estimator the
 * same signal. Returns the grid and its width, or null when unreadable.
 *
 * Delegates to [LumaPlaneMath] — the single definition of this sampling —
 * so the Camera2 [com.vayunmathur.camera.domain.PlaneView] entry point and
 * this buffer entry point can never drift apart.
 */
internal fun sampleYPlaneGrid(
    buffer: java.nio.ByteBuffer,
    rowStride: Int,
    pixelStride: Int,
    width: Int,
    height: Int,
): Pair<FloatArray, Int>? = LumaPlaneMath.grid(buffer, rowStride, pixelStride, width, height)
