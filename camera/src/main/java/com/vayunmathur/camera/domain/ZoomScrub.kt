package com.vayunmathur.camera.domain

import kotlin.math.exp
import kotlin.math.ln

/**
 * Pure zoom-scrub seam for the ZoomBar: maps a cumulative drag delta
 * (relative to the ratio anchored at drag start) to a continuous zoom
 * ratio.
 *
 * Zoom ratios are multiplicative (2x is as far from 1x as 1x is from
 * 0.5x), so the mapping interpolates in log space: the bar's midpoint
 * is the geometric mean of the range, not its arithmetic mean. Every
 * entry point clamps to the device range and sanitises degenerate or
 * non-finite ranges the same way [PreviewGestureRouter.updateZoomRange]
 * does, so a scrub can never emit a ratio the session would reject.
 *
 * Scrubbing is RELATIVE, never absolute: the finger's touchdown
 * position on the bar carries no zoom meaning. [ZoomScrubRouter]
 * anchors on the current ratio at drag start and scales it by the
 * drag delta only (see [zoomRatioForDragDelta]), so a small movement
 * produces a small ratio change wherever the finger lands, and
 * touch-down alone emits nothing.
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
 *
 * Absolute position mapping — NOT used for scrubbing (a scrub is
 * relative to its anchor, see [zoomRatioForDragDelta]); retained as
 * the pure position/fraction mapping the inverse helpers round-trip
 * against.
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
 * Number of bar widths of drag that traverses the whole log zoom range
 * (min -> max as a multiplicative factor).
 *
 * Sensitivity choice: 2 bar widths for the full range. One bar width
 * therefore multiplies the anchored ratio by `sqrt(max / min)` (e.g.
 * ~4.5x over a 0.5x..10x range), and a touch-slop-sized movement moves
 * the ratio by only a few percent. One width for the full range (the
 * previous feel) made short scrubs twitchy: a quarter-bar flick from
 * 1x already landed past 2x on a 0.5x..10x device. Two widths keeps a
 * single confident swipe able to cross most of the usable range while
 * fine adjustment near the anchor stays fine.
 */
internal const val ZOOM_SCRUB_FULL_RANGE_WIDTHS = 2f

/**
 * Ratio after dragging [cumulativeDeltaPx] from [startRatio] across a bar
 * [widthPx] wide: [ZOOM_SCRUB_FULL_RANGE_WIDTHS] bar-widths of drag
 * traverse the whole log range, i.e.
 * `ratio = startRatio * exp(ln(max/min) * dx / (widths * widthPx))`,
 * clamped to the range. The result depends only on the delta and the
 * anchored [startRatio] — never on where on the bar the drag began.
 * A non-positive/non-finite width leaves [startRatio] (clamped).
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
    val widths = (cumulativeDeltaPx / widthPx).toDouble() / ZOOM_SCRUB_FULL_RANGE_WIDTHS
    val target = exp(ln(start.toDouble()) + widths * logSpan)
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
 * The bar width and the current ratio are frozen (anchored) at drag
 * start: the live-ratio chip the ZoomBar inserts mid-scrub changes the
 * row's width, and re-deriving anything from the new width or the
 * finger's absolute position would make the ratio jump under a
 * stationary finger. Every emission is [zoomRatioForDragDelta] from
 * the anchor, driven by `x - downX` only. A gesture only becomes a
 * scrub once it moves past the touch slop (touch-down and sub-slop
 * movement emit nothing); the handler uses [dragEnd]'s result to tell
 * a scrub from a chip tap.
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
    private var anchorRatio = 1f
    private var scrubbing = false

    val isScrubbing: Boolean get() = scrubbing

    /** Sets the clamp range; a degenerate/non-finite range collapses to 1x. */
    fun updateZoomRange(minZoom: Float, maxZoom: Float) {
        val (min, max) = sanitizedZoomRange(minZoom, maxZoom)
        minZoomRatio = min
        maxZoomRatio = max
    }

    /**
     * A pointer went down at [xPx] on a bar [widthPx] wide, with
     * [currentRatio] the live zoom ratio to anchor the scrub on
     * (clamped into the range at emission time). Emits nothing.
     */
    fun dragStart(xPx: Float, widthPx: Float, currentRatio: Float) {
        downXPx = xPx
        widthAtDragStartPx = widthPx
        anchorRatio = currentRatio
        scrubbing = false
    }

    /**
     * The pointer moved to [xPx]. Past [touchSlopPx] from the down point
     * the gesture is a scrub and every further move emits its ratio live,
     * computed from the delta `xPx - downX` against the anchored ratio —
     * never from the absolute position.
     */
    fun dragTo(xPx: Float, touchSlopPx: Float = 0f) {
        if (!scrubbing) {
            val dx = xPx - downXPx
            if (dx * dx <= touchSlopPx * touchSlopPx) return
            scrubbing = true
        }
        onZoom(
            zoomRatioForDragDelta(
                anchorRatio,
                xPx - downXPx,
                widthAtDragStartPx,
                minZoomRatio,
                maxZoomRatio,
            )
        )
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
        anchorRatio = 1f
    }
}
