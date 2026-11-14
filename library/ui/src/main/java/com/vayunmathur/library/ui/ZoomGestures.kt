package com.vayunmathur.library.ui

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.isSpecified
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.IntSize

/**
 * Pinch-to-zoom, pan, double-tap zoom and single tap for a full-screen media viewer.
 *
 * Put the returned modifier on the box that receives touches, and apply [zoom] to the
 * content with a `graphicsLayer` (scale around the centre, translate by the offset). The
 * state is hoisted: gestures read [zoom] as a [State], so a pinch updates the layer without
 * recomposing the content, and every change goes through [onZoomChange].
 *
 * Built to live inside a `HorizontalPager`: at fit scale a one-finger drag is left
 * unconsumed so the pager swipes; once zoomed, drags past the touch slop pan the content,
 * and a drag that runs into the left or right edge is released to the pager again so the
 * next page can be reached without zooming out first. Pinches are always consumed; taps
 * never are, so single and double taps work at any scale.
 *
 * Pinches zoom around the point between the fingers, up to [ZoomState.DEFAULT_MAX_SCALE];
 * a double tap zooms into the tapped point at [ZoomState.DEFAULT_DOUBLE_TAP_SCALE], or back
 * to fit. The geometry is [ZoomState]'s.
 *
 * @param containerSize live reader for the measured content box, used as the zoom pivot
 *   and to clamp the pan.
 * @param onTap single tap (e.g. toggle viewer chrome); delayed by the double-tap timeout.
 * @param acceptsTap gates both tap kinds by position, so taps that land on an overlay drawn
 *   over the content (a metadata card, say) do not also zoom or toggle the viewer.
 */
@Composable
fun zoomGestures(
    zoom: State<ZoomState>,
    containerSize: () -> IntSize,
    onZoomChange: (ZoomState) -> Unit,
    onTap: (Offset) -> Unit = {},
    acceptsTap: (Offset) -> Boolean = { true },
): Modifier {
    // pointerInput(Unit) never re-keys, so every input it reads goes through state.
    val latestSize by rememberUpdatedState(containerSize)
    val latestOnZoomChange by rememberUpdatedState(onZoomChange)
    val latestOnTap by rememberUpdatedState(onTap)
    val latestAcceptsTap by rememberUpdatedState(acceptsTap)
    return Modifier
        .pointerInput(Unit) {
            detectTapGestures(
                onTap = { offset -> if (latestAcceptsTap(offset)) latestOnTap(offset) },
                onDoubleTap = { offset ->
                    if (latestAcceptsTap(offset)) {
                        latestOnZoomChange(
                            zoom.value.toggledAt(offset, latestSize())
                        )
                    }
                },
            )
        }
        .pointerInput(Unit) {
            awaitEachGesture {
                awaitFirstDown(requireUnconsumed = false)
                // Nothing moves or is consumed until the touch has travelled past the slop or a
                // second finger pinches. Consuming the up of a stationary tap would cancel
                // detectTapGestures above, so a zoomed image could never be tapped or
                // double-tapped back to fit.
                var pastSlop = false
                var slopPan = Offset.Zero
                do {
                    val event = awaitPointerEvent()
                    val zoomChange = event.calculateZoom()
                    val pan = event.calculatePan()
                    val isPinching = zoomChange != 1f
                    if (!pastSlop) {
                        slopPan += pan
                        pastSlop = isPinching || slopPan.getDistance() > viewConfiguration.touchSlop
                    }
                    val current = zoom.value
                    if (pastSlop && (current.isZoomed || isPinching)) {
                        val size = latestSize()
                        val centroid = event.calculateCentroid(useCurrent = false)
                            .takeIf { it.isSpecified } ?: Offset(size.width / 2f, size.height / 2f)
                        val next = current.transformed(zoomChange, pan, centroid, size)
                        latestOnZoomChange(next)
                        val max = maxZoomOffset(next.scale, size)
                        val atEdge = (pan.x > 0f && next.offset.x >= max.x) ||
                            (pan.x < 0f && next.offset.x <= -max.x)
                        if (next.scale > 1f && (isPinching || !atEdge)) {
                            event.changes.forEach { it.consume() }
                        }
                    }
                } while (event.changes.any { it.pressed })
            }
        }
}
