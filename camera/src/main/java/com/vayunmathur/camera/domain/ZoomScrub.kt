package com.vayunmathur.camera.domain

import kotlin.math.exp
import kotlin.math.ln

/**
 * Pure zoom-scrub seam for the ZoomBar: maps a horizontal position (or a
 * cumulative drag delta) across the bar to a continuous zoom ratio.
 *
 * Zoom ratios are multiplicative (2x is as far from 1x as 1x is from
 * 0.5x), so the mapping interpolates in log space: the bar's midpoint
 * is the geometric mean of the range, not its arithmetic mean. Every
 * entry point clamps to the device range and sanitises degenerate or
 * non-finite ranges the same way [PreviewGestureRouter.updateZoomRange]
 * does, so a scrub can never emit a ratio the session would reject.
 */

/** Sanitised [minZoom]..[maxZoom]; invalid/non-finite collapses to 1x..1x. */
internal fun sanitizedZoomRange(minZoom: Float, maxZoom: Float): Pair<Float, Float> =
    if (!minZoom.isFinite() || !maxZoom.isFinite() || minZoom <= 0f || maxZoom < minZoom) {
        1f to 1f
    } else {
        minZoom to maxZoom
    }

private fun logRatioForFraction(fraction: Double, minZoom: Double, maxZoom: Double): Float =
    exp(ln(minZoom) + fraction * (ln(maxZoom) - ln(minZoom))).toFloat()

/**
 * Ratio at [fraction] (0 = bar start, 1 = bar end) of [minZoom]..[maxZoom]
 * on a log scale. Endpoints return [minZoom]/[maxZoom] exactly; a fraction
 * outside 0..1 (or non-finite) clamps to the nearer end. A degenerate
 * range (min == max) returns that ratio for every fraction.
 */
fun zoomRatioForFraction(fraction: Float, minZoom: Float, maxZoom: Float): Float {
    val (min, max) = sanitizedZoomRange(minZoom, maxZoom)
    if (min == max) return min
    val f = if (fraction.isFinite()) fraction.coerceIn(0f, 1f).toDouble() else 0.0
    return logRatioForFraction(f, min.toDouble(), max.toDouble())
        .coerceIn(min, max)
}

/**
 * Ratio at horizontal position [xPx] within a bar [widthPx] wide.
 * A non-positive/non-finite width has no position to map, so it returns
 * the range minimum (the scrub simply has not started meaningfully).
 */
fun zoomRatioForPosition(xPx: Float, widthPx: Float, minZoom: Float, maxZoom: Float): Float {
    val (min, max) = sanitizedZoomRange(minZoom, maxZoom)
    if (min == max) return min
    if (!widthPx.isFinite() || widthPx <= 0f || !xPx.isFinite()) return min
    return zoomRatioForFraction(xPx / widthPx, min, max)
}

/** Inverse of [zoomRatioForFraction]: where [ratio] sits in the range, 0..1. */
fun fractionForZoomRatio(ratio: Float, minZoom: Float, maxZoom: Float): Float {
    val (min, max) = sanitizedZoomRange(minZoom, maxZoom)
    if (min == max) return 0f
    val clamped = if (ratio.isFinite()) ratio.coerceIn(min, max) else min
    return ((ln(clamped.toDouble()) - ln(min.toDouble())) /
        (ln(max.toDouble()) - ln(min.toDouble()))).toFloat().coerceIn(0f, 1f)
}

/**
 * Ratio after dragging [cumulativeDeltaPx] from [startRatio] across a bar
 * [widthPx] wide: one full bar-width of drag traverses the whole log
 * range. A non-positive/non-finite width leaves [startRatio] (clamped).
 */
fun zoomRatioForDragDelta(
    startRatio: Float,
    cumulativeDeltaPx: Float,
    widthPx: Float,
    minZoom: Float,
    maxZoom: Float,
): Float {
    val (min, max) = sanitizedZoomRange(minZoom, maxZoom)
    if (min == max) return min
    val start = if (startRatio.isFinite()) startRatio.coerceIn(min, max) else min
    if (!widthPx.isFinite() || widthPx <= 0f || !cumulativeDeltaPx.isFinite()) return start
    val logSpan = ln(max.toDouble()) - ln(min.toDouble())
    val target = exp(ln(start.toDouble()) + (cumulativeDeltaPx / widthPx).toDouble() * logSpan)
    return target.toFloat().coerceIn(min, max)
}

/**
 * Stateful scrub router for the ZoomBar's single gesture handler, in the
 * style of [PreviewGestureRouter]: the UI feeds it pointer positions and
 * it emits live ratios through [onZoom] (wired to
 * `CameraViewModel.setZoomRatio`, which clamps again and composes with
 * the panorama/photosphere zoom lock, so locked modes ignore scrubs
 * exactly like pinch — the bar is hidden there regardless).
 *
 * The bar width is frozen at drag start: the live-ratio chip the ZoomBar
 * inserts mid-scrub changes the row's width, and re-deriving the fraction
 * from the new width would make the ratio jump under a stationary finger.
 * A gesture only becomes a scrub once it moves past the touch slop; the
 * handler uses [dragEnd]'s result to tell a scrub from a chip tap.
 */
class ZoomScrubRouter(
    private val onZoom: (Float) -> Unit,
) {
    var minZoomRatio: Float = 1f
        private set
    var maxZoomRatio: Float = 1f
        private set

    private var widthAtDragStartPx = 0f
    private var downXPx = 0f
    private var scrubbing = false

    val isScrubbing: Boolean get() = scrubbing

    /** Sets the clamp range; a degenerate/non-finite range collapses to 1x. */
    fun updateZoomRange(minZoom: Float, maxZoom: Float) {
        val (min, max) = sanitizedZoomRange(minZoom, maxZoom)
        minZoomRatio = min
        maxZoomRatio = max
    }

    /** A pointer went down at [xPx] on a bar [widthPx] wide. */
    fun dragStart(xPx: Float, widthPx: Float) {
        downXPx = xPx
        widthAtDragStartPx = widthPx
        scrubbing = false
    }

    /**
     * The pointer moved to [xPx]. Past [touchSlopPx] from the down point
     * the gesture is a scrub and every further move emits its ratio live.
     */
    fun dragTo(xPx: Float, touchSlopPx: Float = 0f) {
        if (!scrubbing) {
            val dx = xPx - downXPx
            if (dx * dx <= touchSlopPx * touchSlopPx) return
            scrubbing = true
        }
        onZoom(zoomRatioForPosition(xPx, widthAtDragStartPx, minZoomRatio, maxZoomRatio))
    }

    /** All pointers up: true when the gesture was a scrub (not a tap). */
    fun dragEnd(): Boolean {
        val wasScrubbing = scrubbing
        reset()
        return wasScrubbing
    }

    /** The gesture was cancelled: no tap must be inferred from it. */
    fun dragCancel() = reset()

    private fun reset() {
        scrubbing = false
        widthAtDragStartPx = 0f
    }
}
