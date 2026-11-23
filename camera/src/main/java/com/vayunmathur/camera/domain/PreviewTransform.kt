package com.vayunmathur.camera.domain

import kotlin.math.abs
import kotlin.math.max

/**
 * Pure centre-crop (FILL) transform for the Camera2 TextureView preview:
 * the CameraX `PreviewView` FILL behaviour the Camera2 path must match.
 *
 * Coordinate spaces:
 * - Buffer: the camera stream in sensor orientation, [streamWidth] x
 *   [streamHeight] (the size pinned via `SurfaceTexture.setDefaultBufferSize`,
 *   chosen by `StreamSelection` at or under the 1080p LIMITED cap).
 * - View: the TextureView in display orientation, [viewWidth] x [viewHeight]
 *   (the letterboxed still-crop box from `CameraPreviewBox`; that box keeps
 *   its promised ratio via `previewRatioForWindow`, this transform only
 *   decides how the stream fills it).
 *
 * The buffer must be rotated by the relative rotation
 * ([AnalysisRotation.rotationDegrees]: sensor vs display, front lenses add)
 * to sit upright, then uniformly scaled by
 * `max(viewW / displayedW, viewH / displayedH)` and centred. Uniform scale
 * is the whole point: the old path set no TextureView transform at all, so
 * the view stretched the buffer non-uniformly whenever the stream aspect
 * (e.g. a 4:3 sensor frame capped to 1440x1080, displayed as 3:4) differed
 * from the box aspect (e.g. a 9:16 still-crop box). Any non-uniform
 * fallback is stretch, never a valid preview.
 */
object PreviewTransform {

    /**
     * Row-major 3x3 affine (last row 0,0,1) mapping buffer pixels to view
     * pixels: `view = M * buffer`. [a]/[b]/[c]/[d] are the 2x2 linear part,
     * [tx]/[ty] the translation. [rotationDegrees] is the relative rotation
     * applied, [scale] the uniform fill scale, and [displayedWidth] /
     * [displayedHeight] the buffer size after rotation (aspect swapped for
     * 90/270). The linear part is always `scale * R(rotation)`, so its two
     * column norms are both [scale] — the no-stretch invariant tests assert.
     */
    data class Affine(
        val a: Float,
        val b: Float,
        val c: Float,
        val d: Float,
        val tx: Float,
        val ty: Float,
        val rotationDegrees: Int,
        val scale: Float,
        val displayedWidth: Float,
        val displayedHeight: Float,
    ) {
        /** Aspect (width/height) of the upright, displayed stream. */
        val displayedAspect: Float
            get() = if (displayedHeight == 0f) 0f else displayedWidth / displayedHeight

        /** Maps one buffer pixel to view pixels. */
        fun map(x: Float, y: Float): Pair<Float, Float> =
            (a * x + b * y + tx) to (c * x + d * y + ty)

        /** Row-major 3x3 values for `android.graphics.Matrix.setValues`. */
        fun toMatrixValues(): FloatArray =
            floatArrayOf(a, b, tx, c, d, ty, 0f, 0f, 1f)
    }

    /**
     * The FILL transform for one stream/view pairing, or null when any size
     * is degenerate (no honest transform exists; the caller keeps black).
     * [displayRotationDegrees] is the display (window) rotation 0/90/180/270,
     * not the physical device angle: the activity is portrait-locked, so the
     * window rotation is what the TextureView is actually laid out against,
     * and a rotation change re-derives through this same function.
     */
    fun compute(
        streamWidth: Int,
        streamHeight: Int,
        viewWidth: Int,
        viewHeight: Int,
        sensorOrientationDegrees: Int,
        displayRotationDegrees: Int,
        facing: LensFacing?,
    ): Affine? {
        if (streamWidth <= 0 || streamHeight <= 0 || viewWidth <= 0 || viewHeight <= 0) {
            return null
        }
        val rotation = AnalysisRotation.rotationDegrees(
            sensorOrientationDegrees, displayRotationDegrees, facing,
        )
        val swapped = rotation == 90 || rotation == 270
        val displayedWidth = (if (swapped) streamHeight else streamWidth).toFloat()
        val displayedHeight = (if (swapped) streamWidth else streamHeight).toFloat()
        val scale = max(
            viewWidth / displayedWidth,
            viewHeight / displayedHeight,
        )
        // Linear part: scale * R(clockwise) in screen coords (y down).
        // East (1,0) rotated 90 CW points south (0,1); south points west.
        val (la, lb, lc, ld) = when (rotation) {
            90 -> floatArrayOf(0f, -1f, 1f, 0f)
            180 -> floatArrayOf(-1f, 0f, 0f, -1f)
            270 -> floatArrayOf(0f, 1f, -1f, 0f)
            else -> floatArrayOf(1f, 0f, 0f, 1f)
        }
        val a = scale * la
        val b = scale * lb
        val c = scale * lc
        val d = scale * ld
        // Centre both frames: t = viewCentre - L * bufferCentre.
        val bufferCx = streamWidth / 2f
        val bufferCy = streamHeight / 2f
        val tx = viewWidth / 2f - (a * bufferCx + b * bufferCy)
        val ty = viewHeight / 2f - (c * bufferCx + d * bufferCy)
        return Affine(
            a = a, b = b, c = c, d = d, tx = tx, ty = ty,
            rotationDegrees = rotation, scale = scale,
            displayedWidth = displayedWidth, displayedHeight = displayedHeight,
        )
    }

    /** True when [affine] scales both axes equally (no stretch), within [tolerance]. */
    fun isUniformScale(affine: Affine, tolerance: Float = 0.001f): Boolean {
        val colX = kotlin.math.sqrt(affine.a * affine.a + affine.c * affine.c)
        val colY = kotlin.math.sqrt(affine.b * affine.b + affine.d * affine.d)
        return abs(colX - colY) <= tolerance && abs(colX - affine.scale) <= tolerance
    }
}
