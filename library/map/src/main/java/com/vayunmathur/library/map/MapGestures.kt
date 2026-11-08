package com.vayunmathur.library.map

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.pointer.AwaitPointerEventScope
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.sign

/**
 * A tap on the map: the geographic position under the finger, the screen
 * point (Dp from the viewport top-left) that produced it, and the POI it
 * landed on if it landed on one. The screen point is what
 * [MapFeaturePicker]-style hit-testing needs; the geo alone cannot
 * recover it once the camera moves.
 *
 * [poi] rides on the same click rather than arriving through a callback of its own so a
 * host sees one tap as one event. With two callbacks the host would have to guess whether
 * the other had already claimed the tap, and a POI tap would both open the POI and fall
 * through to whatever the host does with empty map.
 */
data class MapClick(
    val position: GeoPoint,
    val screen: DpOffset,
    /**
     * The topmost drawn POI under the finger, or null.
     *
     * Only ever set when [LayerOptions.poi] is on, and only for labels the collision pass
     * actually placed last frame — a POI hidden behind another is not reported, because it
     * is not on screen to be tapped.
     */
    val poi: PlacedLabel? = null,
)
/**
 * Viewport measurement and every pan/zoom/tap gesture the map supports.
 *
 * Extracted from [RasterMap] so the WebGPU renderer behaves identically rather
 * than approximately: pan, pinch, double-tap-to-zoom and the double-tap-hold-swipe
 * "quick zoom" are what users of five apps already have, and a second
 * implementation would drift from this one. It also means the px-to-Dp conversion
 * happens in exactly one place, which is the regression the plan is most worried
 * about.
 */
@Composable
@Suppress("LongMethod")
internal fun Modifier.mapGestures(
    cameraState: CameraState,
    gestures: GestureOptions,
    zoomRange: ClosedFloatingPointRange<Float>,
    density: Float,
    onMapClick: (GeoPoint) -> Unit,
    onMapClickWithScreen: ((MapClick) -> Unit)? = null,
): Modifier {
    val scope = rememberCoroutineScope()
    // In-flight double-tap zoom animation, cancelled as soon as a new gesture wants
    // to drive the camera itself.
    val zoomAnim = remember { mutableStateOf<Job?>(null) }

    fun toDp(offsetPx: Offset) = Offset(offsetPx.x / density, offsetPx.y / density)
    fun clickAt(offsetPx: Offset) {
        val projection = cameraState.projection ?: return
        val dp = toDp(offsetPx)
        val screen = DpOffset(dp.x.dp, dp.y.dp)
        onMapClick(projection.positionFromScreenLocation(screen))
        // The screen point alongside the geo: hit-testing needs the Dp point
        // the tap landed on, which the geo alone cannot recover.
        onMapClickWithScreen?.invoke(MapClick(projection.positionFromScreenLocation(screen), screen))
    }

    return this
        .onSizeChanged {
            cameraState.setViewport(Size(it.width / density, it.height / density))
        }
        .pointerInput(cameraState, gestures, zoomRange, density) {
            detectTransformGestures { centroid, pan, zoom, rotation ->
                if (gestures.isTransformDisabled) return@detectTransformGestures
                zoomAnim.value?.cancel()
                // The globe is north-up level: the twist gesture has no bearing
                // to drive there (see `CameraState.onGesture`), so drop the
                // rotation rather than spinning a camera the globe never draws.
                cameraState.onGesture(
                    centroidDp = toDp(centroid),
                    panDp = toDp(pan),
                    zoomChange = zoom,
                    rotationDeg = if (cameraState.isGlobeActive) 0f else rotation,
                    minZoom = zoomRange.start.toDouble(),
                    maxZoom = zoomRange.endInclusive.toDouble(),
                    scrollEnabled = gestures.isScrollEnabled,
                    zoomEnabled = gestures.isZoomEnabled,
                    rotateEnabled = gestures.isRotateEnabled,
                )
            }
        }
        // Declared after the transform detector so it sees pointer events first and can consume
        // a two-finger vertical drag out from under it, turning that gesture into a tilt instead
        // of a pan. A pinch (fingers moving apart / opposite) or a twist is left untouched, so
        // zoom still works.
        .pointerInput(cameraState, gestures, density) {
            if (gestures.isTiltDisabled) {
                return@pointerInput
            }
            // Tilt is a flat-map gesture: the globe path is level by construction
            // (its shaders ignore pitch), so a two-finger drag there must not
            // tilt a camera the globe never draws tilted.
            detectVerticalTiltGestures { dyPx ->
                if (!cameraState.isGlobeActive) cameraState.onTilt(dyPx / density)
            }
        }
        // Declared after the transform detector so it sees pointer events first and
        // can consume a quick-zoom drag out from under it.
        .pointerInput(cameraState, gestures, zoomRange, density) {
            if (!gestures.isZoomEnabled) {
                // Nothing to disambiguate against, so report taps immediately.
                detectTapGestures { clickAt(it) }
            } else {
                var quickZoomStart = cameraState.position
                detectTapAndQuickZoomGestures(
                    onTap = { clickAt(it) },
                    onDoubleTap = { anchor ->
                        zoomAnim.value?.cancel()
                        zoomAnim.value = scope.launch {
                            cameraState.animateZoomBy(
                                deltaZoom = 1.0,
                                anchorDp = toDp(anchor),
                                minZoom = zoomRange.start.toDouble(),
                                maxZoom = zoomRange.endInclusive.toDouble(),
                            )
                        }
                    },
                    onQuickZoomStart = {
                        zoomAnim.value?.cancel()
                        quickZoomStart = cameraState.position
                    },
                    onQuickZoom = { anchor, dragPx ->
                        cameraState.onQuickZoom(
                            from = quickZoomStart,
                            anchorDp = toDp(anchor),
                            dragDp = dragPx / density,
                            minZoom = zoomRange.start.toDouble(),
                            maxZoom = zoomRange.endInclusive.toDouble(),
                        )
                    },
                )
            }
        }
}

/**
 * Two-finger vertical-drag tilt, reported to [onTilt] as the signed vertical movement in **px**
 * since the last event (down is positive). Mirrors Google Maps: both fingers dragged up together
 * tilts into the map, down flattens it.
 *
 * Claims the gesture only while both fingers move the same way vertically and vertically more
 * than horizontally — a parallel drag. A pinch (opposite vertical directions) or a mostly-
 * horizontal move is left unconsumed, so [detectTransformGestures] still gets its pinch-zoom and
 * single-finger pan. Sits alongside the transform detector; consuming the moves it claims is what
 * stops that detector from also panning on them.
 */
private suspend fun PointerInputScope.detectVerticalTiltGestures(onTilt: (Float) -> Unit) =
    awaitEachGesture {
        awaitFirstDown(requireUnconsumed = false)
        val tracker = TiltTracker(viewConfiguration.touchSlop)
        tiltLoop(tracker, onTilt)
    }

@Suppress("LoopWithTooManyJumpStatements")
private suspend fun AwaitPointerEventScope.tiltLoop(
    tracker: TiltTracker,
    onTilt: (Float) -> Unit
) {
    while (true) {
        val event = awaitPointerEvent()
        val pressed = event.changes.filter { it.pressed }
        if (pressed.isEmpty()) break
        // Only a two-finger gesture tilts. Any other count resets so a finger added or lifted
        // mid-gesture cannot leave a stale baseline that jumps the pitch.
        if (pressed.size != TILT_FINGERS) {
            tracker.reset()
            continue
        }
        val tilt = tracker.advance(pressed[0], pressed[1])
        if (tilt != null) onTilt(tilt)
    }
}

private const val TILT_FINGERS = 2

/** Tracks the two-finger parallel-drag state for the tilt detector. */
private class TiltTracker(private val slop: Float) {
    private val previous = mutableMapOf<PointerId, Offset>()
    private var tilting = false
    private var accumulated = 0f

    fun reset() {
        previous.clear()
        tilting = false
        accumulated = 0f
    }

    /**
     * Advances one frame; returns the tilt delta to report, or null when this frame
     * claims nothing (new fingers, non-parallel move, or still within slop).
     */
    fun advance(first: PointerInputChange, second: PointerInputChange): Float? {
        val prevFirst = previous[first.id]
        val prevSecond = previous[second.id]
        previous[first.id] = first.position
        previous[second.id] = second.position
        if (prevFirst == null || prevSecond == null) return null
        val delta = parallelVerticalDelta(first, second, prevFirst, prevSecond) ?: run {
            tilting = false
            return null
        }
        if (!tilting) {
            accumulated += delta
            if (abs(accumulated) < slop) return null
            tilting = true
        }
        first.consume()
        second.consume()
        return delta
    }

    // Parallel and vertical: same vertical direction, each finger moving more up/down than
    // sideways. Rejects the pinch (dyFirst*dySecond < 0) and the mostly-horizontal pan.
    private fun parallelVerticalDelta(
        first: PointerInputChange,
        second: PointerInputChange,
        prevFirst: Offset,
        prevSecond: Offset
    ): Float? {
        val dyFirst = first.position.y - prevFirst.y
        val dySecond = second.position.y - prevSecond.y
        val dxFirst = first.position.x - prevFirst.x
        val dxSecond = second.position.x - prevSecond.x
        if (dyFirst * dySecond <= 0f) return null
        if (abs(dyFirst) < abs(dxFirst) || abs(dySecond) < abs(dxSecond)) return null
        return (dyFirst + dySecond) / 2f
    }
}

/** Single-pointer tap gestures: [onTap], [onDoubleTap], and the "quick zoom" that
 * follows a double-tap the user holds and swipes — [onQuickZoomStart] then
 * [onQuickZoom] with the tapped anchor and the signed vertical drag in px (down is
 * positive). A gesture reports either a double-tap or a quick zoom, never both.
 *
 * Meant to sit alongside [detectTransformGestures]: it stays out of the way of
 * pan/pinch, and once a quick-zoom drag passes touch slop it consumes the moves so
 * the transform detector cancels instead of also panning.
 */
private suspend fun PointerInputScope.detectTapAndQuickZoomGestures(
    onTap: (Offset) -> Unit,
    onDoubleTap: (Offset) -> Unit,
    onQuickZoomStart: () -> Unit,
    onQuickZoom: (anchor: Offset, dragPx: Float) -> Unit,
) = awaitEachGesture {
    val down = awaitFirstDown()
    down.consume()
    val up = waitForUpOrCancellation() ?: return@awaitEachGesture
    up.consume()

    val secondDown = awaitSecondDown(up)
    if (secondDown == null) {
        onTap(up.position)
        return@awaitEachGesture
    }
    secondDown.consume()

    // Zoom about the tapped point, which stays put for the whole gesture
    // (following the finger instead would drift the map out from under it).
    val anchor = up.position
    val slop = viewConfiguration.touchSlop
    val drag = QuickZoomDrag(slop)
    quickZoomLoop(secondDown, drag, anchor, onQuickZoomStart, onQuickZoom)
    if (!drag.started) onDoubleTap(anchor)
}

@Suppress("LoopWithTooManyJumpStatements")
private suspend fun AwaitPointerEventScope.quickZoomLoop(
    secondDown: PointerInputChange,
    drag: QuickZoomDrag,
    anchor: Offset,
    onQuickZoomStart: () -> Unit,
    onQuickZoom: (anchor: Offset, dragPx: Float) -> Unit
) {
    while (true) {
        val event = awaitPointerEvent()
        // A second finger means the user wants a pinch; hand it over untouched.
        if (event.changes.size > 1) return
        val change = event.changes.firstOrNull { it.id == secondDown.id } ?: break
        if (!change.pressed) {
            change.consume()
            break
        }
        val dy = change.position.y - secondDown.position.y
        val dragDelta = drag.advance(dy)
        if (dragDelta == null) continue
        if (!drag.started) {
            drag.start(dy)
            onQuickZoomStart()
        }
        change.consume()
        onQuickZoom(anchor, dy - drag.origin)
    }
}

/** Tracks the slop-gated drag of a quick-zoom gesture. */
private class QuickZoomDrag(private val slop: Float) {
    var started = false
        private set
    var origin = 0f
        private set

    /** Returns non-null once the drag has passed slop (or already had). */
    fun advance(dy: Float): Float? {
        if (started) return dy
        if (abs(dy) < slop) return null
        return dy
    }

    /** Starts measuring from the slop boundary so the zoom doesn't jump. */
    fun start(dy: Float) {
        // Start measuring from the slop boundary so the zoom doesn't jump.
        origin = dy - slop * sign(dy)
        started = true
    }
}

/** The second down of a double-tap, or null if none arrives in time. */
private suspend fun AwaitPointerEventScope.awaitSecondDown(
    firstUp: PointerInputChange,
): PointerInputChange? = withTimeoutOrNull(viewConfiguration.doubleTapTimeoutMillis) {
    val minUptime = firstUp.uptimeMillis + viewConfiguration.doubleTapMinTimeMillis
    var change: PointerInputChange
    do {
        change = awaitFirstDown()
    } while (change.uptimeMillis < minUptime)
    change
}
