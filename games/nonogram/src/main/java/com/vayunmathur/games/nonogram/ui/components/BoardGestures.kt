package com.vayunmathur.games.nonogram.ui.components

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.runtime.MutableState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.isSpecified
import androidx.compose.ui.input.pointer.AwaitPointerEventScope
import androidx.compose.ui.input.pointer.PointerEvent
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import com.vayunmathur.games.nonogram.platform.NonogramGameActions

/** How the opening moments of a touch resolved, before it became a stroke or a pinch. */
private sealed interface Press {
    /** Lifted within the long-press timeout without leaving touch slop. */
    data object Tap : Press

    /** Left touch slop before the long-press timeout: a drag in the mode's own mark. */
    data class Drag(val position: Offset) : Press

    /** A second finger came down: zoom and pan, never a mark. */
    data object Pinch : Press

    /** The pointer went away without lifting (the gesture was taken from us). */
    data object Cancelled : Press
}

/** The grid's size, layout direction and zoom limits, which the gesture handler restarts on. */
internal data class GridInput(val n: Int, val rtl: Boolean, val maxZoom: Float)

/**
 * One gesture handler for the whole grid, turning touches into strokes on [actions] and pinches into
 * [zoom] changes.
 *
 *  - Tap: a one-cell stroke in the current mode.
 *  - Drag: begins once the finger leaves touch slop; the stroke follows the finger cell by cell.
 *  - Long press: [onLongPress] (haptics), then a stroke in the *other* mode, which keeps following
 *    the finger — so long-press-and-drag crosses a run in fill mode, or fills one in cross mode.
 *    This keeps long press meaning exactly what it did before ("the other mark") while extending it
 *    to runs, so the mode toggle stays the single answer to "what does a plain touch do".
 *  - Two fingers: pinch to zoom and drag to pan, clamped to the grid. A second finger landing during
 *    a stroke ends that stroke and turns the rest of the gesture into a pinch.
 *
 * Every touch is hit-tested in grid coordinates through the current zoom ([GridViewport.cellAt]).
 * Every change is consumed so an enclosing scroll or double-tap detector never sees grid touches.
 */
internal fun Modifier.boardGestures(
    input: GridInput,
    zoom: MutableState<BoardZoom>,
    actions: () -> NonogramGameActions,
    onLongPress: () -> Unit,
): Modifier = pointerInput(input, zoom) {
    fun cellAt(position: Offset) =
        GridViewport(size.width.toFloat(), input.n, input.rtl, zoom.value).cellAt(position.x, position.y)

    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        down.consume()
        val start = cellAt(down.position)
        val press = withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
            awaitTapOrDrag(down.id, down.position, viewConfiguration.touchSlop)
        }
        val pinchAfter = when (press) {
            Press.Tap -> {
                actions().beginStroke(start, alternate = false)
                actions().endStroke()
                false
            }
            is Press.Drag -> {
                actions().beginStroke(start, alternate = false)
                actions().extendStroke(cellAt(press.position))
                followStroke(down.id, actions, ::cellAt)
            }
            null -> {
                onLongPress()
                actions().beginStroke(start, alternate = true)
                followStroke(down.id, actions, ::cellAt)
            }
            Press.Pinch -> true
            Press.Cancelled -> false
        }
        if (pinchAfter) trackPinch(zoom, input.maxZoom)
    }
}

private fun PointerEvent.pressedCount() = changes.count { it.pressed }

private suspend fun AwaitPointerEventScope.awaitTapOrDrag(id: PointerId, origin: Offset, slop: Float): Press {
    while (true) {
        val event = awaitPointerEvent()
        if (event.pressedCount() > 1) return Press.Pinch
        val change = event.changes.firstOrNull { it.id == id } ?: return Press.Cancelled
        change.consume()
        if (!change.pressed) return Press.Tap
        if ((change.position - origin).getDistance() > slop) return Press.Drag(change.position)
    }
}

/**
 * Feeds the finger's cell to the stroke until it lifts; always ends the stroke, even if cancelled.
 * Returns true if a second finger came down, so the caller carries on with a pinch.
 */
private suspend fun AwaitPointerEventScope.followStroke(
    id: PointerId,
    actions: () -> NonogramGameActions,
    cellAt: (Offset) -> Int,
): Boolean {
    try {
        while (true) {
            val event = awaitPointerEvent()
            if (event.pressedCount() > 1) return true
            val change = event.changes.firstOrNull { it.id == id } ?: return false
            // positionChanged() reads as false once consumed, so check before consuming.
            val moved = change.positionChanged()
            change.consume()
            if (!change.pressed) return false
            if (moved) actions().extendStroke(cellAt(change.position))
        }
    } finally {
        actions().endStroke()
    }
}

/** Zooms about the fingers' centre and pans with it until every finger has lifted. */
private suspend fun AwaitPointerEventScope.trackPinch(zoom: MutableState<BoardZoom>, maxZoom: Float) {
    while (true) {
        val event = awaitPointerEvent()
        if (event.pressedCount() == 0) break
        val side = size.width.toFloat()
        val centroid = event.calculateCentroid(useCurrent = true)
        if (centroid.isSpecified && side > 0f) {
            val pan = event.calculatePan()
            val frame = PinchFrame(
                centroidX = centroid.x / side,
                centroidY = centroid.y / side,
                panX = pan.x / side,
                panY = pan.y / side,
                factor = event.calculateZoom(),
            )
            zoom.value = zoom.value.transformed(frame, maxZoom)
        }
        event.changes.forEach { it.consume() }
    }
}
