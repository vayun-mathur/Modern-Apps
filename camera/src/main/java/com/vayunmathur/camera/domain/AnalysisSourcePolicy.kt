package com.vayunmathur.camera.domain

/** Which pipeline feeds the per-frame analysis consumers for one session. */
enum class AnalysisSource { CAMERAX, CAMERA2_IMAGE_READER }

/**
 * What one session asks its analysis stream to do.
 *
 * @property camera2Enabled the policy flag for the Camera2/ImageReader path.
 *   Default on ([AnalysisSourcePolicy.CAMERA2_ENABLED]): cheap-plane-only
 *   sessions may use the ImageReader path; QR/bitmap sessions stay CameraX.
 * @property needsQr the session decodes QR/barcodes from the analysis stream.
 * @property needsBitmapFrame a consumer needs a converted `Bitmap` frame
 *   (portrait segmentation, panorama/motion-photo frame copies).
 * @property cheapPlaneConsumersOnly every consumer of this session can run
 *   from Y-plane views alone (luma sampling, Y-grid motion diff).
 */
data class AnalysisSourceInput(
    val camera2Enabled: Boolean = AnalysisSourcePolicy.CAMERA2_ENABLED,
    val needsQr: Boolean,
    val needsBitmapFrame: Boolean,
    val cheapPlaneConsumersOnly: Boolean,
)

/**
 * Per-session analysis-source selection (Camera2 migration Phase 3).
 *
 * The ImageReader path delivers plane views with no bitmap conversion, so it
 * may only serve sessions whose consumers are all cheap plane consumers:
 *
 * - QR stays on CameraX: ZXing's `PlanarYUVLuminanceSource` consumes a
 *   contiguous `ByteArray`, so feeding it from a plane means copying the
 *   whole Y plane per decode — the copy this phase exists to remove — and
 *   the decode path's rotation/crop handling is written against `ImageProxy`.
 * - Portrait bokeh and panorama stay on CameraX: the segmenters
 *   (`SelfieSegmenter.segment(Bitmap)`) and `PanoramaEngine.offerFrame(Bitmap)`
 *   take converted bitmaps today; neither has a plane entry point.
 * - Motion-Photo frame copies likewise need a bitmap per frame.
 *
 * Everything else (night-hysteresis luma, video motion grid) is plane-only
 * and may move once the flag is enabled for that session.
 */
object AnalysisSourcePolicy {
    /** Master policy flag: default on (per-session exclusions still apply). */
    const val CAMERA2_ENABLED: Boolean = true

    fun select(input: AnalysisSourceInput): AnalysisSource = when {
        !input.camera2Enabled -> AnalysisSource.CAMERAX
        input.needsQr -> AnalysisSource.CAMERAX
        input.needsBitmapFrame -> AnalysisSource.CAMERAX
        !input.cheapPlaneConsumersOnly -> AnalysisSource.CAMERAX
        else -> AnalysisSource.CAMERA2_IMAGE_READER
    }
}
