package com.vayunmathur.library.map

/**
 * Single owner of the native fetch-freeze engaged flag.
 *
 * While a pinch or camera animation drives the zoom, the Rust side freezes tile
 * fetching at the session's start zoom so intermediate zooms do not churn the
 * wanted set; the freeze releases when the motion settles and the final zoom
 * fetches. Two producers can end a session — the touch-end observer in
 * `mapGestures` (via `CameraState.gestureEndHandler`) and the quiet timeout in
 * the `VulkanMapSurface` gesture-session loop — and both must funnel through
 * this one object. If the explicit release cleared native state while the loop
 * still believed itself frozen, the next gesture's rising edge would skip
 * `markActive` and run unfrozen; if the loop cleared it behind the observer's
 * back, the symmetric desync would freeze a gesture that already ended. The
 * `engaged` flag here is the only copy of that truth.
 *
 * `release` wakes the frame loop because the settled zoom still needs its
 * fetch-and-draw pass; `markActive` deliberately does not
 * ([SurfaceMapRenderer.setGestureActive] never wakes — the gesture frames
 * themselves already do).
 */
internal class GestureFreeze(private val renderer: SurfaceMapRenderer) {
    private var engaged = false

    /** Freeze fetching at [zoom] on the session's rising edge; later calls are no-ops. */
    fun markActive(zoom: Float) {
        if (engaged) return
        engaged = true
        renderer.setGestureActive(true, zoom)
    }

    /** Release the freeze and wake the loop; idempotent so both end signals can call it. */
    fun release() {
        if (!engaged) return
        engaged = false
        renderer.setGestureActive(false, 0f)
        // Wake the loop: if the idle grace already lapsed, nothing else would draw
        // the frame that lets the native side settle and fetch the final zoom.
        renderer.invalidate()
    }
}
