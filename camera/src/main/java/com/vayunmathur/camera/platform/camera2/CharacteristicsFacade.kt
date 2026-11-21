package com.vayunmathur.camera.platform.camera2

import android.graphics.ImageFormat
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.params.StreamConfigurationMap
import android.os.Build
import android.util.Range
import android.util.Size
import com.vayunmathur.camera.domain.LensFacing

/**
 * Camera2 migration Phase 0: cached, pure-data snapshot of everything the
 * existing CameraX-era probes compute ad hoc per bind (see `LensSelector.kt`,
 * `CameraManualControls.readManualControlRanges`,
 * `CameraSessions.highestFpsRange` / `preferredStabilizationMode`,
 * `CameraNightCapture.computeNightExposure`,
 * `CameraPhotoSessionSetup.isJpegRReprocessable`).
 *
 * All [CameraCharacteristics] reads happen once, at construction; accessors
 * never touch the HAL / binder again. The snapshot types are plain data so
 * they can be built from fake maps in unit tests without a device.
 */

/** Summary of one output format in a [StreamConfigurationMap]. */
data class FormatSummary(
    val format: Int,
    val sizes: List<Size>,
) {
    val maxSize: Size? get() = sizes.maxWithOrNull(compareBy({ it.width * it.height }, { it.width }))
    val sizeCount: Int get() = sizes.size
}

/** Pure-data summary of a stream configuration map (never holds the map itself). */
data class StreamConfigSummary(
    val formats: Map<Int, FormatSummary>,
) {
    fun sizesFor(format: Int): List<Size> = formats[format]?.sizes.orEmpty()
    fun maxSizeFor(format: Int): Size? = formats[format]?.maxSize
    val supportsRawSensor: Boolean get() = ImageFormat.RAW_SENSOR in formats

    companion object {
        val EMPTY = StreamConfigSummary(emptyMap())

        /** Builds a summary from plain sizes — the fake-map entry point for tests. */
        fun fromSizes(sizesByFormat: Map<Int, List<Size>>): StreamConfigSummary =
            StreamConfigSummary(sizesByFormat.mapValues { (format, sizes) -> FormatSummary(format, sizes) })

        fun fromMap(map: StreamConfigurationMap?): StreamConfigSummary {
            if (map == null) return EMPTY
            val byFormat = map.outputFormats.associateWith { format ->
                runCatching { map.getOutputSizes(format)?.toList().orEmpty() }.getOrDefault(emptyList())
            }
            return fromSizes(byFormat)
        }
    }
}

/** Immutable per-lens capability snapshot. Pure data; safe to pass across threads. */
data class LensCharacteristicsSnapshot(
    val cameraId: String,
    /** Raw `LENS_FACING` value (`CameraMetadata.LENS_FACING_*`), or null when unreadable. */
    val lensFacingRaw: Int?,
    val physicalCameraIds: Set<String>,
    val isLogicalMultiCamera: Boolean,
    val zoomRatioRange: Range<Float>?,
    val hasFlashUnit: Boolean,
    val exposureTimeRangeNs: Range<Long>?,
    val sensitivityRange: Range<Int>?,
    /** Exposure-compensation range converted to EV steps; null when unreadable. */
    val exposureCompensationEvRange: Range<Float>?,
    val videoStabilizationModes: List<Int>,
    val opticalStabilizationModes: List<Int>,
    val aeTargetFpsRanges: List<Range<Int>>,
    val streamConfig: StreamConfigSummary,
    val hardwareLevel: Int?,
    val availableCapabilities: Set<Int>,
    /** Extension modes (`CameraExtensionCharacteristics.EXTENSION_*`), empty pre-API 31. */
    val availableExtensionModes: List<Int>,
    val focalLengthsMm: List<Float>,
    val colorFilterArrangement: Int?,
) {
    val lensFacing: LensFacing? = when (lensFacingRaw) {
        CameraMetadata.LENS_FACING_BACK -> LensFacing.BACK
        CameraMetadata.LENS_FACING_FRONT -> LensFacing.FRONT
        else -> null
    }
    val minZoomRatio: Float get() = zoomRatioRange?.lower ?: 1f
    val maxZoomRatio: Float get() = zoomRatioRange?.upper ?: 1f
    val supportsRaw: Boolean
        get() = CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_RAW in availableCapabilities
    val supportsReprocess: Boolean
        get() = CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_YUV_REPROCESSING in availableCapabilities ||
            CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_PRIVATE_REPROCESSING in availableCapabilities
    val supportsManualSensor: Boolean
        get() = CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR in availableCapabilities
    fun hasExtension(mode: Int): Boolean = mode in availableExtensionModes
}

/**
 * Facade over [CameraManager]/[CameraCharacteristics] for one lens.
 *
 * Ownership: construct once per lens (construction performs the only IPC),
 * read [snapshot] freely afterwards, discard on lens switch — there is no
 * refresh API by design.
 */
class CharacteristicsFacade private constructor(
    val snapshot: LensCharacteristicsSnapshot,
) {
    val cameraId: String get() = snapshot.cameraId

    companion object {
        /** Builds a facade from an already-materialised snapshot (tests / fakes). */
        fun fromSnapshot(snapshot: LensCharacteristicsSnapshot): CharacteristicsFacade =
            CharacteristicsFacade(snapshot)

        /** Builds a facade from plain format sizes plus scalar fields (tests / fakes). */
        fun fromFakeMap(
            cameraId: String,
            lensFacingRaw: Int? = null,
            physicalCameraIds: Set<String> = emptySet(),
            zoomRatioRange: Range<Float>? = null,
            hasFlashUnit: Boolean = false,
            exposureTimeRangeNs: Range<Long>? = null,
            sensitivityRange: Range<Int>? = null,
            exposureCompensationEvRange: Range<Float>? = null,
            videoStabilizationModes: List<Int> = emptyList(),
            opticalStabilizationModes: List<Int> = emptyList(),
            aeTargetFpsRanges: List<Range<Int>> = emptyList(),
            sizesByFormat: Map<Int, List<Size>> = emptyMap(),
            hardwareLevel: Int? = null,
            availableCapabilities: Set<Int> = emptySet(),
            availableExtensionModes: List<Int> = emptyList(),
            focalLengthsMm: List<Float> = emptyList(),
            colorFilterArrangement: Int? = null,
        ): CharacteristicsFacade = fromSnapshot(
            LensCharacteristicsSnapshot(
                cameraId = cameraId,
                lensFacingRaw = lensFacingRaw,
                physicalCameraIds = physicalCameraIds,
                isLogicalMultiCamera =
                    CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_LOGICAL_MULTI_CAMERA in availableCapabilities,
                zoomRatioRange = zoomRatioRange,
                hasFlashUnit = hasFlashUnit,
                exposureTimeRangeNs = exposureTimeRangeNs,
                sensitivityRange = sensitivityRange,
                exposureCompensationEvRange = exposureCompensationEvRange,
                videoStabilizationModes = videoStabilizationModes,
                opticalStabilizationModes = opticalStabilizationModes,
                aeTargetFpsRanges = aeTargetFpsRanges,
                streamConfig = StreamConfigSummary.fromSizes(sizesByFormat),
                hardwareLevel = hardwareLevel,
                availableCapabilities = availableCapabilities,
                availableExtensionModes = availableExtensionModes,
                focalLengthsMm = focalLengthsMm,
                colorFilterArrangement = colorFilterArrangement,
            ),
        )

        /** Reads every field from [chars] exactly once. Never throws on a missing key. */
        fun fromCharacteristics(
            cameraId: String,
            chars: CameraCharacteristics,
            availableExtensionModes: List<Int> = emptyList(),
        ): CharacteristicsFacade {
            fun <T> get(key: CameraCharacteristics.Key<T>): T? =
                runCatching { chars.get(key) }.getOrNull()

            val capabilities = get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)
                ?.toSet().orEmpty()
            val zoomRange = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE)
            } else {
                null
            } ?: get(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM)
                ?.let { max -> Range(1f, max) }
            val compensationRange = get(CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE)
            val compensationStep = get(CameraCharacteristics.CONTROL_AE_COMPENSATION_STEP)
            val evRange = if (compensationRange != null && compensationStep != null) {
                val step = compensationStep.toFloat()
                Range(compensationRange.lower * step, compensationRange.upper * step)
            } else {
                null
            }
            val physicalIds = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                runCatching { chars.physicalCameraIds }.getOrDefault(emptySet())
            } else {
                emptySet()
            }
            return fromSnapshot(
                LensCharacteristicsSnapshot(
                    cameraId = cameraId,
                    lensFacingRaw = get(CameraCharacteristics.LENS_FACING),
                    physicalCameraIds = physicalIds,
                    isLogicalMultiCamera =
                        CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_LOGICAL_MULTI_CAMERA in capabilities,
                    zoomRatioRange = zoomRange,
                    hasFlashUnit = get(CameraCharacteristics.FLASH_INFO_AVAILABLE) ?: false,
                    exposureTimeRangeNs = get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE),
                    sensitivityRange = get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE),
                    exposureCompensationEvRange = evRange,
                    videoStabilizationModes =
                        get(CameraCharacteristics.CONTROL_AVAILABLE_VIDEO_STABILIZATION_MODES)?.toList().orEmpty(),
                    opticalStabilizationModes =
                        get(CameraCharacteristics.LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION)?.toList().orEmpty(),
                    aeTargetFpsRanges =
                        get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)?.toList().orEmpty(),
                    streamConfig = StreamConfigSummary.fromMap(
                        get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP),
                    ),
                    hardwareLevel = get(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL),
                    availableCapabilities = capabilities,
                    availableExtensionModes = availableExtensionModes,
                    focalLengthsMm =
                        get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.toList().orEmpty(),
                    colorFilterArrangement = get(CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT),
                ),
            )
        }

        /**
         * Full entry point: characteristics + extension availability for [cameraId].
         * Extension probing (`getCameraExtensionCharacteristics`, API 31+) degrades
         * to an empty list on older releases or on any HAL failure.
         */
        fun fromCameraManager(manager: CameraManager, cameraId: String): CharacteristicsFacade {
            val chars = manager.getCameraCharacteristics(cameraId)
            val extensions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                runCatching {
                    manager.getCameraExtensionCharacteristics(cameraId).supportedExtensions
                }.getOrDefault(emptyList())
            } else {
                emptyList()
            }
            return fromCharacteristics(cameraId, chars, extensions)
        }
    }
}
