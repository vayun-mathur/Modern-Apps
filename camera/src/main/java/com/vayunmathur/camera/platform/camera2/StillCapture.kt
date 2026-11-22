package com.vayunmathur.camera.platform.camera2

import android.hardware.camera2.CameraMetadata
import com.vayunmathur.camera.domain.FlashModePolicy
import com.vayunmathur.camera.domain.FlashResolution
import com.vayunmathur.camera.domain.FlashSetting
import com.vayunmathur.camera.domain.LensFacing

/**
 * Camera2 migration Phase 4a: plain single stills on the Camera2 session.
 *
 * A still is a `TEMPLATE_STILL_CAPTURE` request targeted at a JPEG
 * `ImageReader` surface (the session's second output, alongside preview).
 * This file is the pure half: it resolves the session's current control
 * state (zoom / torch / EV / metering, plus the user's flash setting) into
 * exact request fields, and maps the resulting `CaptureResult` values into
 * the shape the existing save pipeline already consumes for in-memory
 * CameraX captures (`jpegBytes` + `rotationDegrees` + `mirrored`, see
 * `stampStillBytes` / `saveStillBytes`). No reprocess/ZSL ring, RAW,
 * Motion Photo or portrait work lives here — [StillEnginePolicy] keeps
 * those on CameraX.
 */

/** Raw `CaptureResult` values for one still, as read by the driver. */
data class RawStillMetadata(
    val sensorTimestampNs: Long? = null,
    val exposureTimeNs: Long? = null,
    val iso: Int? = null,
    val focalLengthMm: Float? = null,
    val frameNumber: Long? = null,
    val aeState: Int? = null,
    val afState: Int? = null,
    val flashState: Int? = null,
    /** `COLOR_CORRECTION_GAINS` (R, G-even, G-odd, B) for RAW linearisation. */
    val colorCorrectionGains: List<Float>? = null,
)

/**
 * Metadata for one captured still, in the shape the existing save pipeline
 * consumes: the CameraX in-memory paths hand `stampStillBytes` /
 * `writeCaptureExif` a JPEG plus a rotation and a mirror flag; the exposure
 * fields ride along for EXIF/logging parity with what CameraX surfaces via
 * `CaptureResult` interop snapshots (`lastAeIso` / `lastAeExposureNanos`).
 */
data class StillCaptureMetadata(
    /** Clockwise degrees to rotate the sensor JPEG upright (EXIF sense). */
    val rotationDegrees: Int,
    /** True when the saved image must be flipped (front lens, mirrored). */
    val mirrored: Boolean,
    val sensorTimestampNs: Long? = null,
    val exposureTimeNs: Long? = null,
    val iso: Int? = null,
    val focalLengthMm: Float? = null,
    val frameNumber: Long? = null,
)

/** Which still output a [CapturedStill] came from. */
enum class StillOutputTag {
    JPEG,

    /** JPEG_R surface: [CapturedStill.jpegBytes] are an Ultra HDR JPEG (gain map embedded). */
    ULTRA_HDR_JPEG_R,
}

/**
 * Pure still-output routing: JPEG_R only when the caller asked for Ultra
 * HDR *and* a JPEG_R output exists on the session; plain JPEG in every
 * other case. Requesting Ultra HDR can therefore never fail a capture —
 * unsupported devices, a failed JPEG_R reader, and plain requests all land
 * on the JPEG output that Phase 4a already requires.
 */
object StillOutputRouter {
    fun select(ultraHdrRequested: Boolean, hasUltraHdrOutput: Boolean): StillOutputTag =
        if (ultraHdrRequested && hasUltraHdrOutput) {
            StillOutputTag.ULTRA_HDR_JPEG_R
        } else {
            StillOutputTag.JPEG
        }
}

/** One finished still: HAL JPEG bytes plus their mapped metadata. */
data class CapturedStill(
    val jpegBytes: ByteArray,
    val jpegWidth: Int,
    val jpegHeight: Int,
    val metadata: StillCaptureMetadata,
    /** Output the bytes came from; JPEG_R bytes carry EXIF + gain map untouched. */
    val tag: StillOutputTag = StillOutputTag.JPEG,
) {
    override fun equals(other: Any?): Boolean =
        other is CapturedStill && jpegWidth == other.jpegWidth &&
            jpegHeight == other.jpegHeight && metadata == other.metadata &&
            tag == other.tag &&
            jpegBytes.contentEquals(other.jpegBytes)

    override fun hashCode(): Int {
        var result = jpegBytes.contentHashCode()
        result = 31 * result + jpegWidth
        result = 31 * result + jpegHeight
        result = 31 * result + metadata.hashCode()
        result = 31 * result + tag.hashCode()
        return result
    }
}

/**
 * Resolved `CaptureRequest` fields for one `TEMPLATE_STILL_CAPTURE`.
 * Carries everything the preview resolution produced (zoom/torch/EV/
 * metering) plus the still-only AE/flash fields.
 */
data class ResolvedStillParams(
    val zoomRatio: Float?,
    val cropRegion: SensorRect?,
    /** `FLASH_MODE` (`CameraMetadata.FLASH_MODE_*`). */
    val flashMode: Int,
    /** `CONTROL_AE_MODE` (`CameraMetadata.CONTROL_AE_MODE_*`). */
    val aeMode: Int,
    val aeExposureCompensationSteps: Int,
    val afRegions: List<MeteringRegion>,
    val aeRegions: List<MeteringRegion>,
    val meteringActive: Boolean,
    /** How illumination resolved for this lens + setting. */
    val flashResolution: FlashResolution,
    /**
     * True when illumination is the display (front lens, no flash unit):
     * no HAL request key can fire it, so the UI/glue must light the screen
     * around the capture. The request itself carries flash OFF.
     */
    val requiresScreenFlash: Boolean,
    /** `JPEG_ORIENTATION`: clockwise degrees, matching [StillCaptureMetadata]. */
    val jpegOrientationDegrees: Int,
    val mirrored: Boolean,
)

object StillCapturePlanner {

    /**
     * Rotation (clockwise, EXIF sense) for a JPEG from this lens at
     * [displayRotationDegrees]. Uses the same relative rotation as
     * [MeteringTransform] (`sensor - display`, normalised): e.g. the usual
     * back lens (sensor 90°) in portrait (display 0°) yields 90, exactly
     * the `rotationDegrees` CameraX reports for that frame, which is what
     * `stampStillBytes`/`rawStillOrientationString` expect.
     */
    fun jpegRotationDegrees(
        sensorOrientationDegrees: Int,
        displayRotationDegrees: Int,
    ): Int {
        val relative = (sensorOrientationDegrees - displayRotationDegrees) % 360
        return (relative + 360) % 360
    }

    /**
     * Resolves the still request for the session's current [state].
     *
     * Flash mapping (Camera2 request keys, where one exists):
     * - OFF / no unit -> `CONTROL_AE_MODE_ON` + `FLASH_MODE_OFF`
     * - hardware AUTO -> `CONTROL_AE_MODE_ON_AUTO_FLASH`
     * - hardware ON -> `CONTROL_AE_MODE_ON_ALWAYS_FLASH`
     * - screen (front, no unit; [FlashModePolicy]) -> `CONTROL_AE_MODE_ON`
     *   + `FLASH_MODE_OFF` and [ResolvedStillParams.requiresScreenFlash]:
     *   Camera2 has no screen-flash key, the display lighting is UI work.
     * - torch already on wins over a one-shot flash: `FLASH_MODE_TORCH`
     *   with plain AE, as the LED is already lit for preview.
     */
    fun resolve(
        state: Camera2ControlState,
        snapshot: LensCharacteristicsSnapshot,
        displayRotationDegrees: Int = 0,
        mirrored: Boolean = false,
    ): ResolvedStillParams {
        val preview = Camera2Controls.resolve(state, snapshot)
        val facing = snapshot.lensFacing ?: LensFacing.BACK
        val resolution = FlashModePolicy.resolve(facing, snapshot.hasFlashUnit, state.flashSetting)
        val torchLit = preview.flashMode == CameraMetadata.FLASH_MODE_TORCH
        val (aeMode, flashMode) = when {
            torchLit -> CameraMetadata.CONTROL_AE_MODE_ON to CameraMetadata.FLASH_MODE_TORCH
            resolution == FlashResolution.HARDWARE && state.flashSetting == FlashSetting.AUTO ->
                CameraMetadata.CONTROL_AE_MODE_ON_AUTO_FLASH to CameraMetadata.FLASH_MODE_SINGLE
            resolution == FlashResolution.HARDWARE && state.flashSetting == FlashSetting.ON ->
                CameraMetadata.CONTROL_AE_MODE_ON_ALWAYS_FLASH to CameraMetadata.FLASH_MODE_SINGLE
            else -> CameraMetadata.CONTROL_AE_MODE_ON to CameraMetadata.FLASH_MODE_OFF
        }
        return ResolvedStillParams(
            zoomRatio = preview.zoomRatio,
            cropRegion = preview.cropRegion,
            flashMode = flashMode,
            aeMode = aeMode,
            aeExposureCompensationSteps = preview.aeExposureCompensationSteps,
            afRegions = preview.afRegions,
            aeRegions = preview.aeRegions,
            meteringActive = preview.meteringActive,
            flashResolution = resolution,
            requiresScreenFlash = resolution == FlashResolution.SCREEN,
            jpegOrientationDegrees = jpegRotationDegrees(
                snapshot.sensorOrientationDegrees, displayRotationDegrees,
            ),
            mirrored = mirrored,
        )
    }
}

/** Maps driver-raw result values into the save-pipeline metadata shape. */
object StillMetadataMapper {

    fun map(
        raw: RawStillMetadata,
        rotationDegrees: Int,
        mirrored: Boolean,
    ): StillCaptureMetadata = StillCaptureMetadata(
        rotationDegrees = ((rotationDegrees % 360) + 360) % 360,
        mirrored = mirrored,
        sensorTimestampNs = raw.sensorTimestampNs,
        exposureTimeNs = raw.exposureTimeNs,
        iso = raw.iso,
        focalLengthMm = raw.focalLengthMm,
        frameNumber = raw.frameNumber,
    )
}
