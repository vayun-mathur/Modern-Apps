package com.vayunmathur.photos.data

/** Which tool panel is showing in the video editor's bottom bar. */
enum class VideoTool { Trim, CropRotate, Audio, Filters }

/**
 * One-tap color looks for video. Each maps to a small set of color effects in
 * [com.vayunmathur.photos.platform.VideoEditViewModel.buildVideoEffects]; the
 * per-slider brightness/contrast/saturation are applied on top.
 */
enum class VideoFilterPreset { None, Mono, Warm, Cool, Vivid }

/**
 * Immutable, single source of truth for the video editor. Both the live preview
 * (ExoPlayer effects/clipping/volume) and the export (Media3 Transformer) derive
 * from one instance so what you see is what you get.
 *
 * - Trim: [trimStartMs]..[trimEndMs] within [durationMs].
 * - Crop/rotate: [rotationDegrees] (0/90/180/270), [flipHorizontal], and an
 *   optional [cropLeft]/[cropTop]/[cropRight]/[cropBottom] rectangle in
 *   normalized [0,1] coordinates (null bounds ⇒ full frame).
 * - Audio: [muted].
 * - Color: [brightness]/[contrast]/[saturation] (0f = neutral) + [filterPreset].
 */
data class VideoEditState(
    val durationMs: Long = 0L,
    val trimStartMs: Long = 0L,
    val trimEndMs: Long = 0L,
    val rotationDegrees: Int = 0,
    val flipHorizontal: Boolean = false,
    val cropLeft: Float? = null,
    val cropTop: Float? = null,
    val cropRight: Float? = null,
    val cropBottom: Float? = null,
    val muted: Boolean = false,
    val brightness: Float = 0f,
    val contrast: Float = 0f,
    val saturation: Float = 0f,
    val filterPreset: VideoFilterPreset = VideoFilterPreset.None,
) {
    /** True if the trim range is a strict sub-range of the full clip. */
    val isTrimmed: Boolean
        get() = durationMs > 0L && (trimStartMs > 0L || trimEndMs < durationMs)

    /** True if a crop rectangle smaller than the full frame is set. */
    val isCropped: Boolean
        get() = cropLeft != null && cropTop != null && cropRight != null && cropBottom != null &&
            (cropLeft > 0f || cropTop > 0f || cropRight < 1f || cropBottom < 1f)

    /** True if any edit is active — used to warn/enable Save. */
    val hasEdits: Boolean
        get() = isTrimmed || isCropped || rotationDegrees != 0 || flipHorizontal || muted ||
            brightness != 0f || contrast != 0f || saturation != 0f ||
            filterPreset != VideoFilterPreset.None

    /** True if any pixel-level (crop/rotate/flip/color) edit is active. */
    val hasVideoEffects: Boolean
        get() = isCropped || rotationDegrees != 0 || flipHorizontal ||
            brightness != 0f || contrast != 0f || saturation != 0f ||
            filterPreset != VideoFilterPreset.None
}
