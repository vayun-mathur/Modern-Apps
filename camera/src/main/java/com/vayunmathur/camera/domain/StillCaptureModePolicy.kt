package com.vayunmathur.camera.domain

/** CameraX capture mode for the photo session's still use case. */
enum class StillCaptureMode { ZERO_SHUTTER_LAG, MINIMIZE_LATENCY }

/**
 * What the capture-mode choice depends on, snapshotted when the photo session binds.
 *
 * @property zslSupported the bound camera reports ZSL support (CameraInfo.isZslSupported:
 *   PRIVATE reprocessing, no ZSL-disabling quirk).
 * @property ultraHdr the still is Ultra HDR (JPEG_R) rather than plain JPEG.
 * @property ultraHdrReprocessable the camera lists JPEG_R as a valid reprocessing output for
 *   PRIVATE input, i.e. a ZSL frame can be reprocessed straight to Ultra HDR.
 * @property autoExposure exposure and ISO are both on Auto (no live manual AE override).
 * @property nightMode night mode is engaged (its capture paths lock AE/AWB per shot).
 */
data class StillCaptureInputs(
    val zslSupported: Boolean,
    val ultraHdr: Boolean,
    val ultraHdrReprocessable: Boolean,
    val autoExposure: Boolean,
    val nightMode: Boolean,
)

/**
 * Chooses zero-shutter-lag capture whenever it can't change the picture (#914).
 *
 * ZSL hands back a frame CameraX already holds in a small ring buffer instead of starting a new
 * exposure, but that buffer only accepts frames whose auto-exposure, auto-white-balance and
 * focus have converged, and it is never flushed while bound. With a manual exposure or a night
 * lock applied, every new frame is rejected and a press would return a stale frame from before
 * the change, so those states keep the regular pipeline. Ultra HDR stays on unless ZSL frames
 * can be reprocessed to JPEG_R, so the quality setting wins over latency there.
 *
 * Flash is deliberately not an input: CameraX already skips ZSL per shot while flash is ON or
 * AUTO, and flash can change without a rebind. Extensions never share this session (the night
 * extension binds its own ImageCapture), and CameraX ignores ZSL under an extension anyway.
 *
 * MINIMIZE_LATENCY is CameraX's default, so the fallback keeps the existing behaviour (and the
 * same default JPEG quality, 95) rather than switching to MAXIMIZE_QUALITY's slower pipeline.
 */
object StillCaptureModePolicy {
    /** ZSL when [inputs] allow it, else MINIMIZE_LATENCY. */
    fun choose(inputs: StillCaptureInputs): StillCaptureMode {
        val hdrCompatible = !inputs.ultraHdr || inputs.ultraHdrReprocessable
        val stateCompatible = inputs.autoExposure && !inputs.nightMode
        return if (inputs.zslSupported && hdrCompatible && stateCompatible) {
            StillCaptureMode.ZERO_SHUTTER_LAG
        } else {
            StillCaptureMode.MINIMIZE_LATENCY
        }
    }
}
