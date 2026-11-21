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
        return pool.maxWithOrNull(compareBy({ it.area }, { it.width }, { it.height }))
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
