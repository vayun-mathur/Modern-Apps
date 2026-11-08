package com.vayunmathur.library.map

import android.app.Application
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The gesture-freeze release contract.
 *
 * While a pinch drives the zoom, the Rust side freezes tile fetching at the session's
 * start zoom; the freeze must release the instant the finger lifts so the settled zoom
 * fetches without waiting out the quiet timeout. Two producers can end a session — the
 * touch-end observer and the quiet-timeout loop — so a single [GestureFreeze] owns the
 * engaged flag and both end signals funnel through it. That sharing is what these tests
 * pin:
 *
 * - an unengaged release wakes nothing (a tap that never moved the camera owes no frame);
 * - mark-then-release wakes exactly once, and a second release is a silent no-op
 *   (the observer and the timeout both firing must not double-release);
 * - engaging freezes once no matter how many frames mark it, and the next session
 *   re-arms cleanly (no desync where a later gesture runs unfrozen or a dead one sticks).
 *
 * Runs on plain JVM unit tests with no Robolectric, like [FrameLoopWakeTest]: with no
 * surface attached (`handle == 0`) `setGestureActive` is a native no-op and `invalidate`
 * only bumps [SurfaceMapRenderer.wakeCount], so the whole contract is observable as
 * wake counts. `Application()` is enough of a `Context` for that.
 */
class GestureFreezeTest {

    private fun freezeOn(renderer: SurfaceMapRenderer) = GestureFreeze(renderer)

    private fun renderer() = SurfaceMapRenderer(Application(), density = 3f)

    /** An unengaged release wakes nothing: taps that never froze owe no frame. */
    @Test
    fun release_without_engage_wakes_nothing() {
        val r = renderer()
        val before = r.wakeCount
        freezeOn(r).release()
        assertEquals(before, r.wakeCount, "release without markActive must not wake the loop")
    }

    /** Engaging freezes without waking: the gesture frames themselves already wake. */
    @Test
    fun mark_active_wakes_nothing() {
        val r = renderer()
        val before = r.wakeCount
        freezeOn(r).markActive(12f)
        assertEquals(before, r.wakeCount, "markActive must not wake the loop")
    }

    /** Mark-then-release wakes exactly once, and the repeat release is silent. */
    @Test
    fun mark_then_release_wakes_exactly_once() {
        val r = renderer()
        val freeze = freezeOn(r)
        freeze.markActive(12f)
        val before = r.wakeCount
        freeze.release()
        assertEquals(before + 1, r.wakeCount, "release must wake the loop exactly once")
        freeze.release()
        assertEquals(before + 1, r.wakeCount, "the second end signal must be a silent no-op")
    }

    /** Repeated marks freeze once, and the next session re-arms cleanly. */
    @Test
    fun sessions_rearm_cleanly() {
        val r = renderer()
        val freeze = freezeOn(r)
        // Every gesture frame marks; only the rising edge may engage.
        freeze.markActive(12f)
        freeze.markActive(12.5f)
        val before = r.wakeCount
        freeze.release()
        assertEquals(before + 1, r.wakeCount, "one session releases exactly one wake")
        // The next gesture is a new session, not a desync: it freezes and releases again.
        freeze.markActive(13f)
        freeze.release()
        assertEquals(before + 2, r.wakeCount, "the next session must re-arm and release again")
    }

    /** The animation gate starts clear: a fresh camera reports no animation running. */
    @Test
    fun animation_gate_starts_clear() {
        assertFalse(CameraState().animationActive, "no animation is running on a fresh camera")
    }

    /** The end rendezvous starts null: hosts without a surface keep the old behavior. */
    @Test
    fun end_rendezvous_starts_null() {
        assertTrue(
            CameraState().gestureEndHandler == null,
            "no end signal fires until a surface registers",
        )
    }
}
