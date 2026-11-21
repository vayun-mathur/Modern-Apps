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
    /**
     * Sizes legal for a `SurfaceTexture` (TextureView) preview output, from
     * `getOutputSizes(SurfaceTexture::class.java)`. Empty when built from plain
     * sizes (tests) — preview selection then falls back to the format lists.
     */
    val surfaceTextureSizes: List<Size> = emptyList(),
) {
    fun sizesFor(format: Int): List<Size> = formats[format]?.sizes.orEmpty()
    fun maxSizeFor(format: Int): Size? = formats[format]?.maxSize
    val supportsRawSensor: Boolean get() = ImageFormat.RAW_SENSOR in formats

    companion object {
        val EMPTY = StreamConfigSummary(emptyMap())

        /** Builds a summary from plain sizes — the fake-map entry point for tests. */
        fun fromSizes(
            sizesByFormat: Map<Int, List<Size>>,
            surfaceTextureSizes: List<Size> = emptyList(),
        ): StreamConfigSummary = StreamConfigSummary(
            sizesByFormat.mapValues { (format, sizes) -> FormatSummary(format, sizes) },
            surfaceTextureSizes,
        )

        fun fromMap(map: StreamConfigurationMap?): StreamConfigSummary {
            if (map == null) return EMPTY
            val byFormat = map.outputFormats.associateWith { format ->
                runCatching { map.getOutputSizes(format)?.toList().orEmpty() }.getOrDefault(emptyList())
            }
            val textureSizes = runCatching {
                map.getOutputSizes(android.graphics.SurfaceTexture::class.java)?.toList().orEmpty()
            }.getOrDefault(emptyList())
            return fromSizes(byFormat, textureSizes)
        }
    }
}

/**
 * Pure-data summary of one extension mode's advertised support, read once
 * from `CameraExtensionCharacteristics` (API 31+). [jpegSizes] /
 * [previewSizes] are the `getExtensionSupportedSizes` lists for the JPEG
 * still output and the `SurfaceTexture` preview output — the two outputs an
 * `ExtensionSessionConfiguration` for this mode would carry. Latency is the
 * `getEstimatedCaptureLatencyRangeMillis` range per JPEG size (millis, null
 * entries omitted): an estimate for UI expectations, never a support gate.
 */
data class ExtensionModeSupport(
    val mode: Int,
    val jpegSizes: List<Size> = emptyList(),
    val previewSizes: List<Size> = emptyList(),
    val estimatedCaptureLatencyRangeMillisByJpegSize: Map<Size, Range<Long>> = emptyMap(),
) {
    fun estimatedLatencyFor(jpegSize: Size): Range<Long>? =
        estimatedCaptureLatencyRangeMillisByJpegSize[jpegSize]
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
    /**
     * Per-extension advertised sizes + estimated capture latency, keyed by
     * extension mode. An entry exists for every mode in
     * [availableExtensionModes] when built from the real characteristics;
     * fake-built snapshots may carry modes without an entry (probe then
     * reports no session-config support, never a guessed yes).
     */
    val extensionSupport: Map<Int, ExtensionModeSupport> = emptyMap(),
    val focalLengthsMm: List<Float>,
    val colorFilterArrangement: Int?,
    /** `SENSOR_INFO_ACTIVE_ARRAY_SIZE` width in pixels; 0 when unreadable. */
    val activeArrayWidth: Int = 0,
    /** `SENSOR_INFO_ACTIVE_ARRAY_SIZE` height in pixels; 0 when unreadable. */
    val activeArrayHeight: Int = 0,
    /** `SENSOR_ORIENTATION` in degrees (0/90/180/270); 0 when unreadable. */
    val sensorOrientationDegrees: Int = 0,
    /**
     * `CONTROL_AE_COMPENSATION_STEP` as a float (EV per compensation step,
     * e.g. 1/6); null when unreadable. Converts an EV value to the integer
     * step count `CONTROL_AE_EXPOSURE_COMPENSATION` takes.
     */
    val exposureCompensationStepEv: Float? = null,
    /**
     * `CONTROL_MAX_REGIONS_AF` / `CONTROL_MAX_REGIONS_AE`; 0 when unreadable.
     * A zero means tap metering regions are not honoured by the HAL.
     */
    val maxAfRegions: Int = 0,
    val maxAeRegions: Int = 0,
    /**
     * Sizes legal for a constrained high-speed session
     * (`StreamConfigurationMap.getHighSpeedVideoSizes`); empty when the lens
     * advertises none. Phase 5 (slo-mo) selects the encoder/preview size
     * exclusively from this list — a regular stream size is not a substitute.
     */
    val highSpeedVideoSizes: List<Size> = emptyList(),
    /**
     * Advertised FPS ranges keyed by high-speed size
     * (`getHighSpeedVideoFpsRangesFor`). Only fixed ranges (lower == upper)
     * are usable for a pinned slo-mo request; see `HighSpeedPlanner`.
     */
    val highSpeedFpsRangesBySize: Map<Size, List<Range<Int>>> = emptyMap(),
    /**
     * True when `CONTROL_ZOOM_RATIO_RANGE` itself was reported, i.e. the
     * request key `CONTROL_ZOOM_RATIO` is honoured. False when [zoomRatioRange]
     * was synthesised from `SCALER_AVAILABLE_MAX_DIGITAL_ZOOM` — zoom then
     * goes through `SCALER_CROP_REGION` instead.
     */
    val supportsZoomRatioKey: Boolean = false,
    /**
     * Input sizes for reprocessing (`StreamConfigurationMap.getInputSizes`),
     * keyed by input format. Empty when never read (fake snapshots may
     * omit it — reprocess support then falls back to the capability bit).
     */
    val reprocessInputSizesByFormat: Map<Int, List<Size>> = emptyMap(),
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
        get() = supportsYuvReprocessing || supportsPrivateReprocessing
    /** `YUV_REPROCESSING` capability: a YUV ring frame can be reprocessed. */
    val supportsYuvReprocessing: Boolean
        get() = CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_YUV_REPROCESSING in
            availableCapabilities
    /** `PRIVATE_REPROCESSING` capability (PRIVATE input, not our YUV ring). */
    val supportsPrivateReprocessing: Boolean
        get() = CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_PRIVATE_REPROCESSING in
            availableCapabilities
    /**
     * The reprocess input format for this lens, or null when unsupported.
     * YUV is preferred (it is the ring format); PRIVATE is reported so a
     * future PRIVATE ring can use it, but the YUV ring cannot queue to it.
     */
    val reprocessInputFormat: Int?
        get() = when {
            supportsYuvReprocessing &&
                (reprocessInputSizesByFormat.isEmpty() ||
                    ImageFormat.YUV_420_888 in reprocessInputSizesByFormat) ->
                ImageFormat.YUV_420_888
            supportsPrivateReprocessing &&
                (reprocessInputSizesByFormat.isEmpty() ||
                    ImageFormat.PRIVATE in reprocessInputSizesByFormat) ->
                ImageFormat.PRIVATE
            else -> null
        }
    /** Advertised input sizes for [reprocessInputFormat] (empty if unread). */
    val reprocessInputSizes: List<Size>
        get() = reprocessInputFormat?.let { reprocessInputSizesByFormat[it].orEmpty() }
            .orEmpty()
    /** True when the YUV ring can honestly reprocess on this lens. */
    val supportsYuvRingReprocess: Boolean
        get() = reprocessInputFormat == ImageFormat.YUV_420_888
    val supportsManualSensor: Boolean
        get() = CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR in availableCapabilities
    /** True when the HAL offers `CONSTRAINED_HIGH_SPEED_VIDEO` (Phase 5). */
    val supportsConstrainedHighSpeedVideo: Boolean
        get() = CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_CONSTRAINED_HIGH_SPEED_VIDEO in
            availableCapabilities
    fun hasExtension(mode: Int): Boolean = mode in availableExtensionModes

    /** Advertised support for [mode], or null when never probed for it. */
    fun extensionSupportFor(mode: Int): ExtensionModeSupport? = extensionSupport[mode]
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
            activeArrayWidth: Int = 0,
            activeArrayHeight: Int = 0,
            sensorOrientationDegrees: Int = 0,
            exposureCompensationStepEv: Float? = null,
            maxAfRegions: Int = 0,
            maxAeRegions: Int = 0,
            supportsZoomRatioKey: Boolean = zoomRatioRange != null,
            surfaceTextureSizes: List<Size> = emptyList(),
            highSpeedVideoSizes: List<Size> = emptyList(),
            highSpeedFpsRangesBySize: Map<Size, List<Range<Int>>> = emptyMap(),
            extensionSupport: Map<Int, ExtensionModeSupport> = emptyMap(),
            reprocessInputSizesByFormat: Map<Int, List<Size>> = emptyMap(),
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
                streamConfig = StreamConfigSummary.fromSizes(sizesByFormat, surfaceTextureSizes),
                hardwareLevel = hardwareLevel,
                availableCapabilities = availableCapabilities,
                availableExtensionModes = availableExtensionModes,
                focalLengthsMm = focalLengthsMm,
                colorFilterArrangement = colorFilterArrangement,
                activeArrayWidth = activeArrayWidth,
                activeArrayHeight = activeArrayHeight,
                sensorOrientationDegrees = sensorOrientationDegrees,
                exposureCompensationStepEv = exposureCompensationStepEv,
                maxAfRegions = maxAfRegions,
                maxAeRegions = maxAeRegions,
                supportsZoomRatioKey = supportsZoomRatioKey,
                highSpeedVideoSizes = highSpeedVideoSizes,
                highSpeedFpsRangesBySize = highSpeedFpsRangesBySize,
                extensionSupport = extensionSupport,
                reprocessInputSizesByFormat = reprocessInputSizesByFormat,
            ),
        )

        /** Reads every field from [chars] exactly once. Never throws on a missing key. */
        fun fromCharacteristics(
            cameraId: String,
            chars: CameraCharacteristics,
            availableExtensionModes: List<Int> = emptyList(),
            extensionSupport: Map<Int, ExtensionModeSupport> = emptyMap(),
        ): CharacteristicsFacade {
            fun <T> get(key: CameraCharacteristics.Key<T>): T? =
                runCatching { chars.get(key) }.getOrNull()

            val capabilities = get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)
                ?.toSet().orEmpty()
            val zoomRatioRange = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE)
            } else {
                null
            }
            val zoomRange = zoomRatioRange
                ?: get(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM)
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
            // Constrained-high-speed tables live on the same stream map but
            // are not part of the generic format summary: read them once here.
            val streamMap = get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            val highSpeedSizes = runCatching {
                streamMap?.highSpeedVideoSizes?.toList().orEmpty()
            }.getOrDefault(emptyList())
            val reprocessInputSizes = runCatching {
                streamMap?.inputFormats?.associateWith { format ->
                    runCatching { streamMap.getInputSizes(format)?.toList().orEmpty() }
                        .getOrDefault(emptyList())
                }.orEmpty()
            }.getOrDefault(emptyMap())
            val highSpeedRanges = highSpeedSizes.associateWith { size ->
                runCatching {
                    streamMap?.getHighSpeedVideoFpsRangesFor(size)?.toList().orEmpty()
                }.getOrDefault(emptyList())
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
                    activeArrayWidth =
                        get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)?.width() ?: 0,
                    activeArrayHeight =
                        get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)?.height() ?: 0,
                    sensorOrientationDegrees = get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0,
                    exposureCompensationStepEv = compensationStep?.toFloat(),
                    maxAfRegions = get(CameraCharacteristics.CONTROL_MAX_REGIONS_AF) ?: 0,
                    maxAeRegions = get(CameraCharacteristics.CONTROL_MAX_REGIONS_AE) ?: 0,
                    supportsZoomRatioKey = zoomRatioRange != null,
                    highSpeedVideoSizes = highSpeedSizes,
                    highSpeedFpsRangesBySize = highSpeedRanges,
                    extensionSupport = extensionSupport,
                    reprocessInputSizesByFormat = reprocessInputSizes,
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
            var extensions: List<Int> = emptyList()
            var support: Map<Int, ExtensionModeSupport> = emptyMap()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                runCatching {
                    val extChars = manager.getCameraExtensionCharacteristics(cameraId)
                    extensions = extChars.supportedExtensions
                    support = extensions.associateWith { mode ->
                        val jpegSizes = runCatching {
                            extChars.getExtensionSupportedSizes(mode, ImageFormat.JPEG)
                        }.getOrDefault(emptyList())
                        val previewSizes = runCatching {
                            extChars.getExtensionSupportedSizes(
                                mode, android.graphics.SurfaceTexture::class.java,
                            )
                        }.getOrDefault(emptyList())
                        val latency = jpegSizes.mapNotNull { size ->
                            runCatching {
                                extChars.getEstimatedCaptureLatencyRangeMillis(
                                    mode, size, ImageFormat.JPEG,
                                )
                            }.getOrNull()?.let { size to it }
                        }.toMap()
                        ExtensionModeSupport(mode, jpegSizes, previewSizes, latency)
                    }
                }.onFailure {
                    extensions = emptyList()
                    support = emptyMap()
                }
            }
            return fromCharacteristics(cameraId, chars, extensions, support)
        }
    }
}
