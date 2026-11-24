package com.vayunmathur.camera.domain

/**
 * Single authoritative router for preview gestures: pinch (2+ pointers)
 * drives zoom, a single-finger tap fires focus, and a single-finger
 * long-press fires focus-lock. Pure (no Compose/Android) so the routing
 * rules are unit-testable without a pointer-input harness.
 *
 * Root cause this replaces: after CameraX was removed (its viewfinder
 * owned pinch-to-zoom via `isPinchToZoomEnabled`), the only remaining
 * preview gesture handler was `detectTapGestures`. A pinch therefore
 * surfaced as a tap and fired autofocus instead of zooming.
 *
 * Contract:
 * - The moment a second pointer goes down the gesture is a pinch: no tap
 *   and no long-press is ever emitted for it, however it ends.
 * - Pinch zoom is continuous: each incremental scale factor accumulates
 *   onto the zoom captured when the pinch started, is clamped to the
 *   device [minZoomRatio]..[maxZoomRatio] range, and is emitted live via
 *   [onZoom] so the session applies it mid-gesture.
 * - A long-press that already fired is not retroactively turned into a
 *   pinch by a late second finger (the focus lock stands).
 * - A cancelled gesture emits nothing.
 */
class PreviewGestureRouter(
    private val onZoom: (Float) -> Unit,
    private val onTap: (x: Float, y: Float) -> Unit,
    private val onLongPress: (x: Float, y: Float) -> Unit,
) {
    /** Device zoom range; updated from the live session before each pinch. */
    var minZoomRatio: Float = 1f
        private set
    var maxZoomRatio: Float = 1f
        private set

    private var pointerCount = 0
    private var pinchActive = false
    private var longPressFired = false
    private var movedBeyondSlop = false
    private var downX = 0f
    private var downY = 0f
    private var lastX = 0f
    private var lastY = 0f
    private var zoomAtPinchStart = 1f
    private var accumulatedScale = 1f

    val isPinchActive: Boolean get() = pinchActive
    val isLongPressFired: Boolean get() = longPressFired

    /** True while a single-finger long-press can still fire. */
    val isLongPressPending: Boolean
        get() = !pinchActive && !longPressFired && !movedBeyondSlop && pointerCount == 1

    /** Sets the clamp range; a degenerate/non-finite range collapses to 1x. */
    fun updateZoomRange(minZoom: Float, maxZoom: Float) {
        if (!minZoom.isFinite() || !maxZoom.isFinite() || minZoom <= 0f || maxZoom < minZoom) {
            minZoomRatio = 1f
            maxZoomRatio = 1f
        } else {
            minZoomRatio = minZoom
            maxZoomRatio = maxZoom
        }
    }

    /**
     * A pointer went down. [pointerCount] is the total now pressed,
     * ([x], [y]) its position, and [currentZoom] the zoom at gesture start
     * (read live, never a bind-time snapshot).
     */
    fun pointerDown(pointerCount: Int, x: Float, y: Float, currentZoom: Float) {
        this.pointerCount = pointerCount
        if (pointerCount == 1 && !pinchActive) {
            longPressFired = false
            movedBeyondSlop = false
            downX = x
            downY = y
            lastX = x
            lastY = y
        }
        if (pointerCount >= 2 && !pinchActive && !longPressFired) {
            pinchActive = true
            zoomAtPinchStart = currentZoom.takeIf { it.isFinite() && it > 0f } ?: 1f
            accumulatedScale = 1f
        }
    }

    /**
     * The (single) pressed pointer moved; tap/long-press use this position.
     * Moving beyond [touchSlopPx] from the down point cancels tap and
     * long-press eligibility (same rule as `detectTapGestures`).
     */
    fun pointerMoved(x: Float, y: Float, touchSlopPx: Float = Float.MAX_VALUE) {
        if (!pinchActive) {
            lastX = x
            lastY = y
            val dx = x - downX
            val dy = y - downY
            if (dx * dx + dy * dy > touchSlopPx * touchSlopPx) movedBeyondSlop = true
        }
    }

    /**
     * One incremental pinch scale factor (Compose `calculateZoom()`).
     * No-op unless a pinch is active; invalid factors are dropped, not
     * clamped into a spurious zoom jump.
     */
    fun pinch(scaleFactor: Float) {
        if (!pinchActive) return
        if (!scaleFactor.isFinite() || scaleFactor <= 0f) return
        accumulatedScale *= scaleFactor
        val target = (zoomAtPinchStart * accumulatedScale)
            .coerceIn(minZoomRatio, maxZoomRatio)
        onZoom(target)
    }

    /** Long-press deadline reached: fires focus-lock once, single-finger only. */
    fun longPressTimeout() {
        if (pinchActive || longPressFired || movedBeyondSlop || pointerCount != 1) return
        longPressFired = true
        onLongPress(lastX, lastY)
    }

    /** All pointers are up: a plain single-finger release is one tap. */
    fun gestureEnd() {
        val wasPinch = pinchActive
        val wasLongPress = longPressFired
        val wasSlop = movedBeyondSlop
        val x = lastX
        val y = lastY
        reset()
        if (!wasPinch && !wasLongPress && !wasSlop) onTap(x, y)
    }

    /** The gesture was cancelled (e.g. the input scope left): emit nothing. */
    fun gestureCancel() = reset()

    private fun reset() {
        pointerCount = 0
        pinchActive = false
        longPressFired = false
        movedBeyondSlop = false
        accumulatedScale = 1f
    }
}
