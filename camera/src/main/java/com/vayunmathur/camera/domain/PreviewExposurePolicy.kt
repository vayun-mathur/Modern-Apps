package com.vayunmathur.camera.domain

import kotlin.math.roundToLong

/**
 * Splits a manual still exposure into the exposure the live preview may run and the
 * exposure the still itself must get.
 *
 * The sensor produces one frame stream per capture session: CameraX merges the
 * Preview, ImageCapture and ImageAnalysis use cases bound together into a single
 * repeating capture request, so a 1s SENSOR_EXPOSURE_TIME pushed onto that request
 * (via Camera2CameraControl, which overlays the whole session) caps every stream —
 * preview included — at ~1 fps. Real cameras keep the viewfinder smooth by never
 * running the long exposure in preview: the preview runs a short exposure instead
 * and the long exposure is only in effect for the still.
 *
 * [split] therefore caps the preview at [PREVIEW_MAX_EXPOSURE_NANOS] (1/30s, so the
 * viewfinder keeps ~30 fps) and raises the preview ISO in proportion to the capped
 * time, so the preview stays about as bright as the still will be (clamped to the
 * sensor's ISO ceiling when known; the HAL clamps again regardless). The still
 * values pass through untouched, so what a capture exposes never changes — the
 * caller applies them only at capture time and restores the preview split after.
 */
object PreviewExposurePolicy {

    /** Longest exposure the preview stream may run: 1/30s (~30 fps viewfinder). */
    const val PREVIEW_MAX_EXPOSURE_NANOS: Long = 33_333_333L

    /** Preview/still pair derived from one manual exposure; see [split]. */
    data class SplitExposure(
        val previewNanos: Long,
        val previewIso: Int,
        val stillNanos: Long,
        val stillIso: Int,
    ) {
        /** True when the still cannot just inherit the live (preview) exposure. */
        val stillOverrideRequired: Boolean
            get() = previewNanos != stillNanos || previewIso != stillIso
    }

    /**
     * Splits [stillNanos]/[stillIso] for preview vs still. Exposures at or below
     * the preview cap split to themselves (no override needed at capture time).
     * [isoMax] is the sensor's sensitivity ceiling when known, else null.
     */
    fun split(stillNanos: Long, stillIso: Int, isoMax: Int?): SplitExposure {
        if (stillNanos <= PREVIEW_MAX_EXPOSURE_NANOS) {
            return SplitExposure(stillNanos, stillIso, stillNanos, stillIso)
        }
        val gain = stillNanos.toDouble() / PREVIEW_MAX_EXPOSURE_NANOS.toDouble()
        var previewIso = (stillIso * gain).roundToLong()
            .coerceAtLeast(stillIso.toLong())
            .coerceAtLeast(1L)
        if (isoMax != null) previewIso = previewIso.coerceAtMost(isoMax.toLong())
        return SplitExposure(
            previewNanos = PREVIEW_MAX_EXPOSURE_NANOS,
            previewIso = previewIso.toInt(),
            stillNanos = stillNanos,
            stillIso = stillIso,
        )
    }

    /** Frame-rate ceiling (fps) a repeating request with [exposureNanos] can run. */
    fun maxFps(exposureNanos: Long): Double =
        if (exposureNanos <= 0L) Double.POSITIVE_INFINITY else 1_000_000_000.0 / exposureNanos
}
