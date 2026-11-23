package com.vayunmathur.camera.domain

/**
 * Inputs for the Motion Photo diversion, snapshotted at capture time.
 *
 * @property enabled the user's Motion Photo on/off setting. Off means a
 *   plain still always, regardless of every other input.
 * @property plainPhoto a plain PHOTO capture (not another mode, and not
 *   capturing for a caller).
 * @property noBake no warmth/shadows bake is applied to the shot.
 * @property avifPrecludes an effective AVIF selection, which precludes the
 *   diversion because a Motion Photo is a JPEG with an MP4 trailer (see
 *   [StillFormatRouting.avifPrecludesMotionPhoto]).
 * @property nativeFourThree the capture is at the native 4:3 ratio: the
 *   motion still is saved as raw JPEG bytes, which cannot carry a crop, so
 *   other ratios take the single-shot path that saves a cropped JPEG.
 */
data class MotionPhotoInputs(
    val enabled: Boolean,
    val plainPhoto: Boolean,
    val noBake: Boolean,
    val avifPrecludes: Boolean,
    val nativeFourThree: Boolean,
)

/** Pure eligibility for diverting a still to the Motion Photo path. */
object MotionPhotoEligibility {
    fun isEligible(inputs: MotionPhotoInputs): Boolean =
        inputs.enabled && inputs.plainPhoto && inputs.noBake &&
            !inputs.avifPrecludes && inputs.nativeFourThree
}
