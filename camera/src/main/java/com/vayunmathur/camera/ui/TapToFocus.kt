package com.vayunmathur.camera.ui

import android.os.SystemClock
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import com.vayunmathur.camera.domain.PreviewGestureRouter
import com.vayunmathur.library.log.Log
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull

/** Minimum gap between two tap-to-focus requests; faster repeats are one gesture, not two. */
private const val FOCUS_TAP_DEBOUNCE_MS = 120L

/** How long the focus ring stays up (its own fade runs inside [FocusIndicator]). */
private const val FOCUS_INDICATOR_LIFETIME_MS = 1_350L

/**
 * Normalises a coordinate-transformer output (surface-buffer pixels) into the
 * 0..1 range [MeteringPoint] expects, clamped to the
 * surface. Pure so it stays unit-testable.
 *
 * The clamp matters: with `ContentScale.Crop` a tap at the box edge can map
 * just outside the buffer, and CameraX rejects an out-of-range point — the
 * future then fails and, before this fix, was discarded unread, so the tap
 * silently did nothing. A non-finite transform (matrix not populated yet) or
 * a degenerate surface falls back to the centre rather than dropping the tap.
 */
internal fun normalizedMeteringCoordinates(
    transformedX: Float,
    transformedY: Float,
    surfaceWidth: Float,
    surfaceHeight: Float,
): Pair<Float, Float> {
    if (!transformedX.isFinite() || !transformedY.isFinite() || surfaceWidth <= 0f || surfaceHeight <= 0f) {
        return 0.5f to 0.5f
    }
    return (transformedX / surfaceWidth).coerceIn(0f, 1f) to
        (transformedY / surfaceHeight).coerceIn(0f, 1f)
}

/**
 * In-screen tap-to-focus state for one preview: where the ring is, whether
 * the tap locked metering, and the debounce clock. Deliberately NOT
 * navigation — a focus point is transient state of this one screen.
 */
internal class TapToFocusState {
    var point by mutableStateOf<Offset?>(null)
        private set
    var tapId by mutableIntStateOf(0)
        private set
    var locked by mutableStateOf(false)
        private set
    var lastTapMs by mutableLongStateOf(0L)

    fun show(point: Offset, locked: Boolean) {
        this.point = point
        this.locked = locked
        tapId++
    }

    fun clear() {
        point = null
    }
}

/**
 * The preview's single authoritative gesture handler: pinch-to-zoom,
 * tap-to-focus and long-press-to-lock focus, all routed by one
 * [PreviewGestureRouter] inside one `awaitEachGesture` block.
 *
 * This replaces two historical handlers that each owned half the job:
 * CameraXViewfinder's built-in pinch (deleted with CameraX in 846c79fe2)
 * and a lone `detectTapGestures` tap detector. With only the tap detector
 * left, a pinch surfaced as a tap and fired autofocus instead of zooming.
 * Two competing `pointerInput` detectors would race the same way, so
 * there is exactly one here: a second pointer converts the gesture to a
 * pinch and no focus event is ever emitted for it.
 *
 * Zoom is read live at pinch start via [currentZoom]/[zoomRange] (never a
 * bind-time snapshot) and applied live via [onZoom], which the ViewModel
 * forwards to the Camera2 session (and clamps again there).
 */
internal fun Modifier.previewGestures(
    state: TapToFocusState,
    mirrorPreview: Boolean,
    currentZoom: () -> Float,
    zoomRange: () -> Pair<Float, Float>,
    onZoom: (Float) -> Unit,
    onFocus: (Float, Float) -> Unit,
    onLockFocus: (Float, Float) -> Unit,
    onFallback: () -> Unit,
): Modifier = pointerInput(mirrorPreview) {
    fun showIndicator(tapOffset: Offset, locked: Boolean) {
        // tapOffset is in the (possibly extra-mirrored) preview layer's
        // coordinates; the indicator is drawn by a sibling outside that
        // layer, so flip back into visual coordinates.
        val visual = if (mirrorPreview) {
            Offset(size.width - tapOffset.x, tapOffset.y)
        } else {
            tapOffset
        }
        state.show(visual, locked)
    }

    fun focusAt(x: Float, y: Float, locked: Boolean) {
        // Indicator first, regardless of AF outcome: it proves the tap
        // registered even if metering then fails.
        showIndicator(Offset(x, y), locked)
        if (size.width > 0 && size.height > 0) {
            val handler = if (locked) onLockFocus else onFocus
            handler(x / size.width, y / size.height)
        } else {
            onFallback()
        }
    }

    val router = PreviewGestureRouter(
        onZoom = onZoom,
        onTap = { x, y ->
            val now = SystemClock.uptimeMillis()
            if (now - state.lastTapMs >= FOCUS_TAP_DEBOUNCE_MS) {
                state.lastTapMs = now
                focusAt(x, y, locked = false)
            }
        },
        onLongPress = { x, y -> focusAt(x, y, locked = true) },
    )

    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        router.pointerDown(1, down.position.x, down.position.y, currentZoom())
        val longPressDeadlineMs = down.uptimeMillis + viewConfiguration.longPressTimeoutMillis
        var lastEventMs = down.uptimeMillis
        try {
            while (true) {
                val event = if (router.isLongPressPending) {
                    val remaining = (longPressDeadlineMs - lastEventMs).coerceAtLeast(0L)
                    withTimeoutOrNull(remaining) { awaitPointerEvent() }
                } else {
                    awaitPointerEvent()
                }
                if (event == null) {
                    router.longPressTimeout()
                    continue
                }
                lastEventMs = event.changes.maxOf { it.uptimeMillis }
                val pressed = event.changes.filter { it.pressed }
                when {
                    pressed.size >= 2 -> {
                        val (minZoom, maxZoom) = zoomRange()
                        router.updateZoomRange(minZoom, maxZoom)
                        router.pointerDown(
                            pressed.size,
                            pressed.map { it.position.x }.average().toFloat(),
                            pressed.map { it.position.y }.average().toFloat(),
                            currentZoom(),
                        )
                        router.pinch(event.calculateZoom())
                        event.changes.forEach { it.consume() }
                    }
                    pressed.size == 1 -> router.pointerMoved(
                        pressed[0].position.x,
                        pressed[0].position.y,
                        viewConfiguration.touchSlop,
                    )
                    else -> break
                }
            }
            router.gestureEnd()
        } catch (e: Exception) {
            router.gestureCancel()
            throw e
        }
    }
}

/**
 * Hosts the tap-to-focus ring above the preview overlays (below the
 * settings column) and expires it after [FOCUS_INDICATOR_LIFETIME_MS].
 * The Canvas carries no pointer input, so it never eats the next tap.
 */
@Composable
internal fun TapToFocus(
    state: TapToFocusState,
    modifier: Modifier = Modifier,
) {
    LaunchedEffect(state.tapId) {
        if (state.tapId > 0) {
            delay(FOCUS_INDICATOR_LIFETIME_MS)
            state.clear()
        }
    }
    val point = state.point ?: return
    FocusIndicator(
        point = point,
        tapId = state.tapId,
        locked = state.locked,
        modifier = modifier,
    )
}
