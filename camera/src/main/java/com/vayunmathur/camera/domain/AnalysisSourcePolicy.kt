package com.vayunmathur.camera.domain

/** Which pipeline feeds the per-frame analysis consumers for one session. */
enum class AnalysisSource { CAMERA2_IMAGE_READER }

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
 *
 * Bitmap-conversion assessment (2026-11-22): the conversion itself now
 * exists and is honest — `Yuv420ToArgb` (pure, stride-aware, BT.601
 * limited range) plus `AnalysisFrame.toBitmapFromPlanes()` produce the
 * same sensor-oriented bitmap `ImageProxy.toBitmap()` hands these
 * consumers, with rotation carried separately exactly as today. What
 * still blocks feeding `segment(Bitmap)`/`offerFrame` from Camera2, in
 * order:
 *
 * 1. No rotation/crop metadata on the Camera2 frame. `AnalysisFrame`
 *    carries planes + timestamp only; CameraX consumers read
 *    `imageInfo.rotationDegrees` (BokehAnalyzer's orient-to-display,
 *    PanoramaEngine's per-frame upright, MotionPhotoEncoder's clip
 *    rotation) and, on the night path, `ImageProxy.cropRect`. Deriving
 *    the Camera2 equivalent needs sensor orientation + display rotation
 *    + facing at the analysis source (the still path's
 *    `StillCapturePlanner.jpegRotationDegrees` is the back-lens half of
 *    that formula; the front-lens analysis formula is unproven), and no
 *    crop is ever applied to the Camera2 analysis stream.
 * 2. The bitmap sessions never compose a Camera2 session. Portrait,
 *    panorama and Motion-Photo (PHOTO with its excluded still routes)
 *    bind CameraX sessions; under `PreviewEnginePolicy` a Camera2
 *    preview would unbind the CameraX capture those modes still need.
 *    The analysis source cannot move before the preview/capture does.
 * 3. No perf win from the move alone: `segment(Bitmap)` still needs a
 *    converted bitmap per frame, so Camera2 would pay the same
 *    conversion `toBitmap()` pays today. The win is a single camera
 *    session, not a cheaper frame — worth having only after (1)-(2).
 *
 * Until (1) and (2) land, `needsBitmapFrame` keeps the session on
 * CameraX; do not relax this policy on the strength of the conversion
 * alone.
 */
object AnalysisSourcePolicy {
    /**
     * CameraX is removed: the Camera2 ImageReader stream is the only
     * analysis source. QR decodes from the Y plane in place
     * (`PhotoAnalyzer.onAnalysisFrame`), and bitmap consumers
     * (portrait segmentation, panorama, Motion Photo) convert via
     * `Yuv420ToArgb` with the frame's rotation/crop metadata
     * ([AnalysisRotation]/[FrameCrop]) — the exclusions that used to
     * keep those sessions on CameraX no longer exist.
     */
    fun select(input: AnalysisSourceInput): AnalysisSource =
        AnalysisSource.CAMERA2_IMAGE_READER
}
