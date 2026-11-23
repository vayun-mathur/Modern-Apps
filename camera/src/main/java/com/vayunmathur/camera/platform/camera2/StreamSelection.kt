package com.vayunmathur.camera.platform.camera2

import android.graphics.ImageFormat
import android.util.Size
import kotlin.math.abs

/**
 * Camera2 migration Phase 2: deterministic preview stream selection from the
 * [CharacteristicsFacade] snapshot. The requested preview size is validated
 * against the `StreamConfigurationMap` summary *before* any device is opened —
 * an unsupported size fails fast with a typed reason instead of surfacing as
 * a HAL configure failure after a trial bind.
 *
 * Selection is a pure function of the supported list: same input, same output
 * on every device and in every test. No HAL iteration order leaks in — ties
 * break on width, then height.
 */

/** Plain width/height pair so selection math needs no Android types. */
data class FrameSize(val width: Int, val height: Int) {
    val area: Int get() = width * height
    /** width / height as a float; 0 for a degenerate height. */
    val aspect: Float get() = if (height == 0) 0f else width.toFloat() / height

    fun toSize(): Size = Size(width, height)

    companion object {
        fun from(size: Size): FrameSize = FrameSize(size.width, size.height)
    }
}

/** Why a preview-size request cannot be honoured. */
sealed interface PreviewSizeRejection {
    /** The HAL reported no preview-capable sizes at all. */
    data object NoSupportedSizes : PreviewSizeRejection

    /** [requested] is not in the supported list and no close substitute exists. */
    data class Unsupported(val requested: FrameSize) : PreviewSizeRejection
}

object StreamSelection {

    /** Aspect ratios within this absolute tolerance count as the same shape. */
    const val ASPECT_TOLERANCE = 0.02f

    /** Pixel cap for the RAW ring stream (see [selectRawRingSize]). */
    const val MAX_RAW_RING_PIXELS: Long = 12_000_000L

    /**
     * Pixel cap for the YUV ring stream (see [selectRingSize]). A
     * full-sensor YUV stream beside the full-sensor JPEG (and any
     * JPEG_R/RAW outputs) exceeds what many HALs will configure — the
     * device can die at configure time — while the ring only needs
     * enough resolution for a ZSL reprocess/direct-encode still.
     */
    const val MAX_RING_PIXELS: Long = 4_000_000L

    /**
     * Supported preview sizes for a TextureView surface, in facade order:
     * the explicit `SurfaceTexture` list when the HAL reported one, else the
     * PRIVATE list (what a preview surface consumes), else YUV.
     */
    fun supportedPreviewSizes(snapshot: LensCharacteristicsSnapshot): List<FrameSize> {
        val config = snapshot.streamConfig
        val sizes = when {
            config.surfaceTextureSizes.isNotEmpty() -> config.surfaceTextureSizes
            config.sizesFor(ImageFormat.PRIVATE).isNotEmpty() -> config.sizesFor(ImageFormat.PRIVATE)
            else -> config.sizesFor(ImageFormat.YUV_420_888)
        }
        return sizes.map { FrameSize.from(it) }.distinct()
    }

    /** True only when [requested] is exactly one of the supported sizes. */
    fun isSupported(requested: FrameSize, supported: List<FrameSize>): Boolean =
        requested in supported

    /**
     * Picks the preview size for [requested]:
     * 1. An exact match wins.
     * 2. Else, among sizes sharing the requested aspect (within
     *    [ASPECT_TOLERANCE]), the smallest that still covers the request,
     *    or the largest available when none covers it.
     * 3. Else (no same-aspect size), the closest aspect, then closest area.
     *
     * Returns null only when [supported] is empty — the caller must not open
     * the device in that case.
     */
    fun selectPreviewSize(requested: FrameSize, supported: List<FrameSize>): FrameSize? {
        if (supported.isEmpty()) return null
        if (requested in supported) return requested
        val sameAspect = supported.filter { abs(it.aspect - requested.aspect) <= ASPECT_TOLERANCE }
        if (sameAspect.isNotEmpty()) {
            val covering = sameAspect.filter { it.area >= requested.area }
            return (if (covering.isNotEmpty()) {
                covering.minWithOrNull(compareBy({ it.area }, { it.width }, { it.height }))
            } else {
                sameAspect.maxWithOrNull(compareBy({ it.area }, { it.width }, { it.height }))
            })
        }
        return supported.minWithOrNull(
            compareBy(
                { abs(it.aspect - requested.aspect) },
                { abs(it.area - requested.area) },
                { it.width },
                { it.height },
            ),
        )
    }

    /**
     * Validates [requested] up front: [Result.success] carries the size to
     * configure (the request itself or its deterministic substitute); failure
     * carries the typed rejection so the session can refuse before opening.
     */
    fun validatePreviewSize(
        requested: FrameSize,
        supported: List<FrameSize>,
    ): Result<FrameSize> {
        if (supported.isEmpty()) {
            return Result.failure(IllegalStateException("no supported preview sizes"))
        }
        val selected = selectPreviewSize(requested, supported)
            ?: return Result.failure(IllegalStateException("no supported preview sizes"))
        return Result.success(selected)
    }

    /** Supported JPEG still sizes in facade order (empty = no still output). */
    fun supportedJpegSizes(snapshot: LensCharacteristicsSnapshot): List<FrameSize> =
        snapshot.streamConfig.sizesFor(ImageFormat.JPEG)
            .map { FrameSize.from(it) }.distinct()

    /**
     * The JPEG still size to configure: the largest the HAL offers (ties on
     * width, then height), matching CameraX's default highest-resolution
     * still. Null when the lens reports no JPEG sizes — preview still
     * works, still capture is unavailable ([StillEnginePolicy] excludes it).
     */
    fun selectJpegSize(snapshot: LensCharacteristicsSnapshot): FrameSize? =
        supportedJpegSizes(snapshot)
            .maxWithOrNull(compareBy({ it.area }, { it.width }, { it.height }))

    /** Supported JPEG_R (Ultra HDR) still sizes in facade order. */
    fun supportedJpegRSizes(snapshot: LensCharacteristicsSnapshot): List<FrameSize> =
        snapshot.streamConfig.sizesFor(ImageFormat.JPEG_R)
            .map { FrameSize.from(it) }.distinct()

    /**
     * The JPEG_R still size to configure: the largest advertised, matching
     * [selectJpegSize]. Null when the lens reports no JPEG_R sizes (pre-API 34
     * or no Ultra HDR output) — the session then has no Ultra HDR output and
     * still capture falls back to plain JPEG.
     */
    fun selectJpegRSize(snapshot: LensCharacteristicsSnapshot): FrameSize? =
        supportedJpegRSizes(snapshot)
            .maxWithOrNull(compareBy({ it.area }, { it.width }, { it.height }))

    /** Supported YUV_420_888 sizes in facade order (empty = no ring). */
    fun supportedYuvSizes(snapshot: LensCharacteristicsSnapshot): List<FrameSize> =
        snapshot.streamConfig.sizesFor(ImageFormat.YUV_420_888)
            .map { FrameSize.from(it) }.distinct()

    /**
     * The ring size: the largest advertised reprocess-input size that is
     * also a YUV output (the only size a reprocess request can honestly
     * consume), else the largest YUV output. Null when the lens reports
     * no YUV sizes — no ring output is configured then.
     */
    fun selectRingSize(snapshot: LensCharacteristicsSnapshot): FrameSize? {
        val yuv = supportedYuvSizes(snapshot)
        if (yuv.isEmpty()) return null
        val inputSizes = snapshot.reprocessInputSizes.map { FrameSize.from(it) }
        val reprocessable = yuv.filter { it in inputSizes }
        val pool = if (reprocessable.isNotEmpty()) reprocessable else yuv
        val underCap = pool.filter { it.area.toLong() <= MAX_RING_PIXELS }
        return if (underCap.isNotEmpty()) {
            underCap.maxWithOrNull(compareBy({ it.area }, { it.width }, { it.height }))
        } else {
            pool.minWithOrNull(compareBy({ it.area }, { it.width }, { it.height }))
        }
    }

    /** Pixel cap for the analysis stream: the CameraX analysis cap (~1.2 MP, 1280x960). */
    const val MAX_ANALYSIS_PIXELS: Long = 1280L * 960L

    /**
     * The analysis size: the largest YUV size at or under
     * [MAX_ANALYSIS_PIXELS] (luma/motion consumers gain nothing from sensor
     * resolution), else the smallest YUV size over the cap. Null when the
     * lens reports no YUV sizes — no analysis output is configured then.
     */
    fun selectAnalysisSize(snapshot: LensCharacteristicsSnapshot): FrameSize? {
        val yuv = supportedYuvSizes(snapshot)
        if (yuv.isEmpty()) return null
        val underCap = yuv.filter { it.area.toLong() <= MAX_ANALYSIS_PIXELS }
        return if (underCap.isNotEmpty()) {
            underCap.maxWithOrNull(compareBy({ it.area }, { it.width }, { it.height }))
        } else {
            yuv.minWithOrNull(compareBy({ it.area }, { it.width }, { it.height }))
        }
    }

    /** Supported RAW sizes for the facade's RAW ring format (empty = no RAW ring). */
    fun supportedRawSizes(snapshot: LensCharacteristicsSnapshot): List<FrameSize> {
        val format = snapshot.rawRingFormat?.toImageFormat() ?: return emptyList()
        return snapshot.streamConfig.sizesFor(format).map { FrameSize.from(it) }.distinct()
    }

    /**
     * The RAW ring size: the largest advertised RAW size, capped at
     * [MAX_RAW_RING_PIXELS] — a full-sensor RAW ring at 8+ frames is a
     * memory and HAL-bandwidth liability the night burst does not need,
     * so the largest size at or under the cap wins, else the smallest
     * over it. Null when the lens reports no RAW sizes.
     */
    fun selectRawRingSize(snapshot: LensCharacteristicsSnapshot): FrameSize? {
        val raw = supportedRawSizes(snapshot)
        if (raw.isEmpty()) return null
        val underCap = raw.filter { it.area.toLong() <= MAX_RAW_RING_PIXELS }
        return if (underCap.isNotEmpty()) {
            underCap.maxWithOrNull(compareBy({ it.area }, { it.width }, { it.height }))
        } else {
            raw.minWithOrNull(compareBy({ it.area }, { it.width }, { it.height }))
        }
    }

    /** 1080p pixel budget: the LIMITED guaranteed-combination cap for PRIV/YUV targets. */
    const val LIMITED_STREAM_MAX_PIXELS: Long = 1920L * 1080L

    /**
     * True when this lens must be planned against the conservative
     * guaranteed stream-combination table. Note the Android constants are
     * NOT ordered: LIMITED=0, FULL=1, LEGACY=2, LEVEL_3=3, EXTERNAL=4.
     * Only LEVEL_3 guarantees RAW/second-JPEG-class combinations, so every
     * other level (and an unreadable level) gets the guaranteed combo.
     */
    fun isLimitedOrLower(snapshot: LensCharacteristicsSnapshot): Boolean =
        snapshot.hardwareLevel !=
            android.hardware.camera2.CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_3

    /** Largest YUV size at or under [capPixels], else the smallest YUV size; null when no YUV. */
    fun selectYuvSizeAtMost(snapshot: LensCharacteristicsSnapshot, capPixels: Long): FrameSize? {
        val yuv = supportedYuvSizes(snapshot)
        if (yuv.isEmpty()) return null
        val under = yuv.filter { it.area.toLong() <= capPixels }
        return if (under.isNotEmpty()) {
            under.maxWithOrNull(compareBy({ it.area }, { it.width }, { it.height }))
        } else {
            yuv.minWithOrNull(compareBy({ it.area }, { it.width }, { it.height }))
        }
    }

    /** Sizes chosen for one capture session, legal by construction for the lens level. */
    data class SessionStreamPlan(
        val jpeg: FrameSize?,
        val jpegR: FrameSize?,
        val ring: FrameSize?,
        val raw: FrameSize?,
        val analysis: FrameSize?,
        /** Preview pixel cap implied by the combination, or null for no cap. */
        val previewMaxPixels: Long?,
    )

    /**
     * Plans one session's outputs from the guaranteed stream-combination
     * table for the lens's hardware level — never by trial configure.
     *
     * LIMITED/LEGACY (and unknown levels) guarantee, alongside a preview,
     * at most: one JPEG at MAX size, one YUV target at ≤1080p, and a
     * preview (PRIVATE) target at ≤1080p. There is no guaranteed second
     * JPEG-class stream (JPEG_R), no RAW stream, and no second YUV stream,
     * so the richest legal LIMITED combination is preview(≤1080p) +
     * JPEG(MAX) + one YUV(≤1080p). That single YUV serves analysis when a
     * consumer exists (QR/luma/motion keep working) and the ZSL ring
     * otherwise; stills fall back to a regular capture when the ring is
     * not configured. FULL/LEVEL_3 lenses keep the richer selection.
     */
    fun planSessionStreams(
        snapshot: LensCharacteristicsSnapshot,
        ringEnabled: Boolean,
        rawEnabled: Boolean,
        ultraHdrEnabled: Boolean,
        hasAnalysisConsumer: Boolean,
    ): SessionStreamPlan {
        val jpeg = selectJpegSize(snapshot)
        if (isLimitedOrLower(snapshot)) {
            return SessionStreamPlan(
                jpeg = jpeg,
                jpegR = null,
                // The single YUV serves the ring only when no analysis
                // consumer exists; a 1080p ring is not per-frame processed.
                ring = if (!hasAnalysisConsumer && ringEnabled) {
                    selectYuvSizeAtMost(snapshot, LIMITED_STREAM_MAX_PIXELS)
                } else {
                    null
                },
                raw = null,
                // Analysis is per-frame processed (plane stats), so it gets
                // the preview-class cap (<=1280x960), not a 1080p frame:
                // full-rate preview must not pay for sensor-class YUV.
                analysis = if (hasAnalysisConsumer) selectAnalysisSize(snapshot) else null,
                previewMaxPixels = LIMITED_STREAM_MAX_PIXELS,
            )
        }
        return SessionStreamPlan(
            jpeg = jpeg,
            jpegR = if (ultraHdrEnabled && snapshot.supportsJpegR) selectJpegRSize(snapshot) else null,
            ring = if (ringEnabled) selectRingSize(snapshot) else null,
            raw = if (rawEnabled) selectRawRingSize(snapshot) else null,
            analysis = if (hasAnalysisConsumer) selectAnalysisSize(snapshot) else null,
            previewMaxPixels = null,
        )
    }

    /** The rejection for [requested], or null when a size can be selected. */
    fun rejectionFor(
        requested: FrameSize,
        supported: List<FrameSize>,
    ): PreviewSizeRejection? = when {
        supported.isEmpty() -> PreviewSizeRejection.NoSupportedSizes
        selectPreviewSize(requested, supported) == null ->
            PreviewSizeRejection.Unsupported(requested)
        else -> null
    }
}
