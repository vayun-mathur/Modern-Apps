package com.vayunmathur.camera.platform.camera2

import android.hardware.camera2.CameraMetadata
import com.vayunmathur.camera.domain.FlashSetting
import kotlin.math.roundToInt

/**
 * Camera2 migration Phase 2: preview controls as raw `CaptureRequest` fields.
 *
 * The UI-facing state ([Camera2ControlState]) is what the screen already
 * holds (zoom ratio, torch, EV, tap point). [resolve] turns it into the
 * exact request parameters for one lens, using only the facade snapshot —
 * clamping and fallbacks happen here, purely, so the driver only writes
 * fields. Re-resolving on every change and re-issuing the repeating request
 * is the session's job.
 */

/** What the user asked for. EV is in stops (e.g. +1.0), zoom is a ratio ≥ min. */
data class Camera2ControlState(
    val zoomRatio: Float = 1f,
    val torchOn: Boolean = false,
    val exposureCompEv: Float = 0f,
    val afRegion: MeteringRegion? = null,
    val aeRegion: MeteringRegion? = null,
    /** User still-flash setting; preview ignores it, stills resolve it. */
    val flashSetting: FlashSetting = FlashSetting.OFF,
    /**
     * Manual exposure (both null = AE on). When either is set the
     * request locks AE off and carries the pair, clamped to the lens's
     * advertised ranges at resolve time.
     */
    val exposureTimeNs: Long? = null,
    val sensitivityIso: Int? = null,
    /** Post-processing toggles (#662): true requests HIGH_QUALITY, false OFF. */
    val noiseReductionHighQuality: Boolean = true,
    val edgeHighQuality: Boolean = true,
) {
    companion object {
        val DEFAULT = Camera2ControlState()
    }
}

/**
 * Resolved `CaptureRequest` fields for one preview request. Null fields are
 * left unset on the builder (HAL default applies).
 */
data class ResolvedRequestParams(
    /** `CONTROL_ZOOM_RATIO`; null when the crop-region fallback is used. */
    val zoomRatio: Float?,
    /** `SCALER_CROP_REGION`; null when [zoomRatio] carries the zoom. */
    val cropRegion: SensorRect?,
    /** `FLASH_MODE` (`CameraMetadata.FLASH_MODE_*`). */
    val flashMode: Int,
    /** `CONTROL_AE_EXPOSURE_COMPENSATION`, in HAL steps (not EV). */
    val aeExposureCompensationSteps: Int,
    /** `CONTROL_AF_REGIONS`; empty when the lens takes no AF regions. */
    val afRegions: List<MeteringRegion>,
    /** `CONTROL_AE_REGIONS`; empty when the lens takes no AE regions. */
    val aeRegions: List<MeteringRegion>,
    /** True when a tap point is being metered (AF/AE triggered at a point). */
    val meteringActive: Boolean,
    /** Manual exposure pair (clamped); both null keeps AE on. */
    val manualExposureTimeNs: Long? = null,
    val manualSensitivityIso: Int? = null,
    /** NOISE_REDUCTION_MODE / EDGE_MODE values; TONEMAP_MODE or null to leave unset. */
    val noiseReductionMode: Int = android.hardware.camera2.CameraMetadata.NOISE_REDUCTION_MODE_HIGH_QUALITY,
    val edgeMode: Int = android.hardware.camera2.CameraMetadata.EDGE_MODE_HIGH_QUALITY,
    val tonemapMode: Int? = android.hardware.camera2.CameraMetadata.TONEMAP_MODE_HIGH_QUALITY,
)

object Camera2Controls {

    /**
     * Resolves [state] against [snapshot]. Never throws: every value is
     * clamped to what the lens advertises, and unsupported features degrade
     * to their no-op field values (torch off without a flash unit, no
     * metering regions when the HAL reports zero region slots).
     */
    fun resolve(
        state: Camera2ControlState,
        snapshot: LensCharacteristicsSnapshot,
    ): ResolvedRequestParams {
        val zoom = state.zoomRatio.coerceIn(snapshot.minZoomRatio, snapshot.maxZoomRatio)
        val supportsZoomRatio = snapshot.supportsZoomRatioKey
        val activeArray = SensorRect.ofSize(snapshot.activeArrayWidth, snapshot.activeArrayHeight)
        val crop = if (supportsZoomRatio) {
            null
        } else {
            MeteringTransform.cropRegionForZoom(activeArray, zoom)
        }
        val flashMode = if (state.torchOn && snapshot.hasFlashUnit) {
            CameraMetadata.FLASH_MODE_TORCH
        } else {
            CameraMetadata.FLASH_MODE_OFF
        }
        return ResolvedRequestParams(
            zoomRatio = if (supportsZoomRatio) zoom else null,
            cropRegion = crop,
            flashMode = flashMode,
            aeExposureCompensationSteps = evToSteps(state.exposureCompEv, snapshot),
            afRegions = if (snapshot.maxAfRegions > 0) {
                listOfNotNull(state.afRegion)
            } else {
                emptyList()
            },
            aeRegions = if (snapshot.maxAeRegions > 0) {
                listOfNotNull(state.aeRegion)
            } else {
                emptyList()
            },
            meteringActive = state.afRegion != null || state.aeRegion != null,
            manualExposureTimeNs = if (state.exposureTimeNs != null || state.sensitivityIso != null) {
                val range = snapshot.exposureTimeRangeNs
                if (range != null && state.exposureTimeNs != null) {
                    state.exposureTimeNs.coerceIn(range.lower, range.upper)
                } else {
                    state.exposureTimeNs
                }
            } else {
                null
            },
            noiseReductionMode = if (state.noiseReductionHighQuality) {
                android.hardware.camera2.CameraMetadata.NOISE_REDUCTION_MODE_HIGH_QUALITY
            } else {
                android.hardware.camera2.CameraMetadata.NOISE_REDUCTION_MODE_OFF
            },
            edgeMode = if (state.edgeHighQuality) {
                android.hardware.camera2.CameraMetadata.EDGE_MODE_HIGH_QUALITY
            } else {
                android.hardware.camera2.CameraMetadata.EDGE_MODE_OFF
            },
            tonemapMode = if (state.noiseReductionHighQuality || state.edgeHighQuality) {
                android.hardware.camera2.CameraMetadata.TONEMAP_MODE_HIGH_QUALITY
            } else {
                null
            },
            manualSensitivityIso = if (state.exposureTimeNs != null || state.sensitivityIso != null) {
                val range = snapshot.sensitivityRange
                if (range != null && state.sensitivityIso != null) {
                    state.sensitivityIso.coerceIn(range.lower, range.upper)
                } else {
                    state.sensitivityIso
                }
            } else {
                null
            },
        )
    }

    /**
     * Converts an EV value to `CONTROL_AE_EXPOSURE_COMPENSATION` steps using
     * the lens's step size, clamped to the advertised EV range. A lens with
     * no readable step/range takes 0 (HAL default).
     */
    fun evToSteps(ev: Float, snapshot: LensCharacteristicsSnapshot): Int {
        val step = snapshot.exposureCompensationStepEv ?: return 0
        val range = snapshot.exposureCompensationEvRange ?: return 0
        if (step <= 0f) return 0
        val clampedEv = ev.coerceIn(range.lower, range.upper)
        return (clampedEv / step).roundToInt()
    }

    /** The active array as a [SensorRect], or an empty rect when unreadable. */
    fun activeArrayOf(snapshot: LensCharacteristicsSnapshot): SensorRect =
        SensorRect.ofSize(snapshot.activeArrayWidth, snapshot.activeArrayHeight)

    /** The crop window the current zoom implies (for metering transforms). */
    fun currentCropRegion(
        state: Camera2ControlState,
        snapshot: LensCharacteristicsSnapshot,
    ): SensorRect {
        val activeArray = activeArrayOf(snapshot)
        val zoom = state.zoomRatio.coerceIn(snapshot.minZoomRatio, snapshot.maxZoomRatio)
        return MeteringTransform.cropRegionForZoom(activeArray, zoom)
    }
}
