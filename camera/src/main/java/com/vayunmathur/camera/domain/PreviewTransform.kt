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
 * Two different transforms live here, in two different spaces:
 *
 * - [compute]: the *geometry* of the upright image — the buffer rotated
 *   by the relative rotation ([AnalysisRotation.rotationDegrees]:
 *   sensor vs display, front lenses add) has [displayedWidth] x
 *   [displayedHeight] (aspect swapped for 90/270), and fills the view at
 *   the uniform scale `max(viewW / displayedW, viewH / displayedH)`,
 *   centred. This describes where image content sits; it is NOT a
 *   TextureView matrix.
 * - [computeTextureAffine]: the matrix `TextureView.setTransform`
 *   actually consumes. That matrix is NOT a sampling (view -> texture)
 *   transform and NOT in texture pixels. In HWUI a TextureView layer is
 *   drawn as `Concat(layerTransform, textureMatrix)` (Layer.draw in
 *   libs/hwui): `textureMatrix` already maps buffer pixels onto the
 *   layer rect (the view size) — the normalized 0..1 texture space and
 *   the SurfaceTexture quarter-turn live inside it, which is why the
 *   pre-transform preview was *stretched*, never sideways — and the
 *   app-supplied `layerTransform` is concatenated OUTSIDE it. So
 *   `setTransform` is a placement transform in *view pixels*, applied
 *   to content that already fills the view (non-uniformly when the
 *   aspects differ). The app matrix must contain NO rotation of its
 *   own: any quarter turn here rotates an already-upright image. The
 *   7c4429d74 wiring fed [compute]'s rotated placement affine straight
 *   to `setTransform`, double-applying the rotation — back sensor 90
 *   and front sensor 270 both rendered a quarter-turn from the
 *   (correct, EXIF-rotated) captured still.
 *
 *   1c8a688eb then replaced that with the *inverse* fill scale in
 *   displayed-texture pixels (`1 / fillScale`, translation in texture
 *   pixels): correct as a view -> texture sampling map, but as a
 *   view-pixel placement it shrinks the content about the origin.
 *   At an exact aspect match it is `0.893` with zero translation, so
 *   the preview occupies only the top-left of the box and the right
 *   and bottom strips are never drawn — the owner-reported "cut off
 *   on the right and bottom". The correct placement rescales the
 *   already view-filling content about the view centre by
 *   `(fillScale * displayed / view)` per axis (each >= 1, one == 1):
 *   identity at a matching aspect, a centred cover-crop otherwise.
 *
 * Uniform scale is the other invariant: with no app transform at all
 * the view stretches the buffer non-uniformly whenever the stream
 * aspect (e.g. a 4:3 sensor frame, displayed as 3:4) differs from the
 * box aspect (e.g. a 9:16 still-crop box). Any non-uniform fallback is
 * stretch, never a valid preview.
 */
object PreviewTransform {

    /**
     * Row-major 3x3 affine (last row 0,0,1) mapping buffer pixels to view
     * pixels: `view = M * buffer`. [a]/[b]/[c]/[d] are the 2x2 linear part,
     * [tx]/[ty] the translation. [rotationDegrees] is the relative rotation
     * applied, [scale] the uniform fill scale, and [displayedWidth] /
     * [displayedHeight] the buffer size after rotation (aspect swapped for
     * 90/270). For [compute]'s placement form the linear part is always
     * `scale * R(rotation)`, so its two column norms are both [scale] —
     * the no-stretch invariant tests assert. For [computeTextureAffine]'s
     * view -> view form [scale] is the effective uniform fill scale of
     * the composition with TextureView's base stretch, and the column
     * norms are the per-axis cover factors (see that function's doc).
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

    /**
     * The `TextureView.setTransform` matrix for one stream/view pairing,
     * as an [Affine] mapping *view* pixels to *view* pixels: it places
     * the content TextureView has already stretched onto the view rect
     * (see the class doc for the HWUI composition). [Affine.map] thus
     * converts a point of the identity (untransformed) rendering to
     * where that same content point lands under the transform.
     *
     * Deliberately no rotation (the stream's own texture transform
     * already applied the relative rotation, so the relative rotation
     * only decides the displayed size the scale is computed from, via
     * [compute]): [Affine.rotationDegrees] is always 0.
     *
     * The linear part is the cover rescale of the base-stretched
     * content, per axis `fillScale * displayed / view` — >= 1 on both
     * axes and == 1 on the axis that sets the fill scale, so the
     * content always COVERS the view (never letterboxes) and the
     * cropped axis overflows symmetrically. The translation scales
     * about the view centre (`viewCentre - K * viewCentre`), so the
     * view centre is a fixed point and the crop is centred. Composed
     * with TextureView's base stretch (`displayed -> view`), the
     * effective displayed -> view scale is the uniform [Affine.scale]
     * (`fillScale`); the matrix's own column norms are the per-axis
     * cover factors, not that scale, so [isUniformScale] does not
     * apply to this form — uniformity holds for the composition.
     *
     * At a matching aspect the result is exactly the identity: the
     * base stretch is already the uniform fill, and any non-identity
     * matrix only shrinks/shifts the preview inside its box.
     * Null for degenerate sizes, like [compute].
     */
    fun computeTextureAffine(
        streamWidth: Int,
        streamHeight: Int,
        viewWidth: Int,
        viewHeight: Int,
        sensorOrientationDegrees: Int,
        displayRotationDegrees: Int,
        facing: LensFacing?,
    ): Affine? {
        val placement = compute(
            streamWidth, streamHeight, viewWidth, viewHeight,
            sensorOrientationDegrees, displayRotationDegrees, facing,
        ) ?: return null
        if (placement.scale == 0f) return null
        val displayedWidth = placement.displayedWidth
        val displayedHeight = placement.displayedHeight
        // Cover factors relative to the already view-filling base
        // rendering. fillScale * displayed >= view on both axes (that
        // is what fillScale is the max of), so both factors are >= 1.
        val coverX = placement.scale * displayedWidth / viewWidth
        val coverY = placement.scale * displayedHeight / viewHeight
        return Affine(
            a = coverX, b = 0f, c = 0f, d = coverY,
            // Scale about the view centre: centre is a fixed point and
            // the overflow (crop) splits equally on opposite sides.
            tx = viewWidth / 2f - coverX * viewWidth / 2f,
            ty = viewHeight / 2f - coverY * viewHeight / 2f,
            rotationDegrees = 0,
            scale = placement.scale,
            displayedWidth = displayedWidth,
            displayedHeight = displayedHeight,
        )
    }

    /** True when [affine] scales both axes equally (no stretch), within [tolerance]. */
    fun isUniformScale(affine: Affine, tolerance: Float = 0.001f): Boolean {
        val colX = kotlin.math.sqrt(affine.a * affine.a + affine.c * affine.c)
        val colY = kotlin.math.sqrt(affine.b * affine.b + affine.d * affine.d)
        return abs(colX - colY) <= tolerance && abs(colX - affine.scale) <= tolerance
    }
}
