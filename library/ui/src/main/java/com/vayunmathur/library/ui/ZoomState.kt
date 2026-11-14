package com.vayunmathur.library.ui

import androidx.compose.runtime.Immutable
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.IntSize

/**
 * Scale and pan of zoomable content, applied as a `graphicsLayer` around the content's
 * centre: `scaleX = scaleY = scale`, `translationX/Y = offset`.
 *
 * Pure value + math so the gesture handling in [zoomGestures] stays a thin shell and the
 * geometry can be unit-tested without a pointer. Scale never drops below 1 — content is
 * laid out to fit, and zooming out past fit only adds letterboxing — and the pan is clamped
 * so the scaled content always covers its container instead of sliding off-screen.
 */
@Immutable
data class ZoomState(val scale: Float = 1f, val offset: Offset = Offset.Zero) {

    /** True once the content is meaningfully magnified (a pinch rounding to 1 is not). */
    val isZoomed: Boolean get() = scale > ZOOMED_THRESHOLD

    /**
     * Applies one pinch/pan step. [zoomChange] multiplies the scale around [centroid] (in
     * container coordinates), so the point between the user's fingers stays under them, then
     * [pan] moves the content. The result is clamped to `1..maxScale` and to the container.
     * Returns the identity state when the scale settles back at 1.
     */
    fun transformed(
        zoomChange: Float,
        pan: Offset,
        centroid: Offset,
        containerSize: IntSize,
        maxScale: Float = DEFAULT_MAX_SCALE,
    ): ZoomState {
        val newScale = (scale * zoomChange).coerceIn(1f, maxScale)
        if (newScale <= 1f) return ZoomState()
        val anchor = centroid - containerSize.center()
        // The content point under the centroid is (anchor - offset) / scale from the centre;
        // solve for the offset that keeps it under the centroid at the new scale.
        val anchored = anchor - (anchor - offset) * (newScale / scale)
        return ZoomState(newScale, clampZoomOffset(anchored + pan, newScale, containerSize))
    }

    /**
     * The double-tap toggle: back to fit when zoomed, otherwise to [zoomedScale] centred on
     * [tap] so the tapped detail is what gets magnified.
     */
    fun toggledAt(
        tap: Offset,
        containerSize: IntSize,
        zoomedScale: Float = DEFAULT_DOUBLE_TAP_SCALE,
    ): ZoomState =
        if (scale > 1f) {
            ZoomState()
        } else {
            ZoomState().transformed(zoomedScale, Offset.Zero, tap, containerSize, zoomedScale)
        }

    companion object {
        /** Pinch ceiling: enough to read small UI text in a phone screenshot or photo. */
        const val DEFAULT_MAX_SCALE = 5f

        /** Double-tap target scale. */
        const val DEFAULT_DOUBLE_TAP_SCALE = 2.5f

        private const val ZOOMED_THRESHOLD = 1.01f
    }
}

/**
 * Largest translation, per axis, that still keeps content scaled by [scale] covering a
 * [containerSize] box (zero at fit).
 */
fun maxZoomOffset(scale: Float, containerSize: IntSize): Offset {
    val extra = (scale - 1f).coerceAtLeast(0f) / 2f
    return Offset(containerSize.width * extra, containerSize.height * extra)
}

/** [offset] clamped to `±maxZoomOffset(scale, containerSize)` on each axis. */
fun clampZoomOffset(offset: Offset, scale: Float, containerSize: IntSize): Offset {
    val max = maxZoomOffset(scale, containerSize)
    // `+ 0f` turns the -0f that clamping a negative value into [-0, 0] yields back into 0f;
    // Offset compares bit patterns, so an at-fit offset must be exactly Offset.Zero.
    return Offset(offset.x.coerceIn(-max.x, max.x) + 0f, offset.y.coerceIn(-max.y, max.y) + 0f)
}

private fun IntSize.center(): Offset = Offset(width / 2f, height / 2f)
