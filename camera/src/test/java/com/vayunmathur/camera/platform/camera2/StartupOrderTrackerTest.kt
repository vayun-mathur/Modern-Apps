package com.vayunmathur.camera.platform.camera2

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Ordering contract for the launch-to-first-frame path: the Camera2
 * preview bind must be requested before the background capability probes
 * (CameraX lens enumeration, extension / slo-mo probes) complete, so the
 * first frame never waits on CameraX initialisation.
 */
class StartupOrderTrackerTest {

    @Test
    fun `bind requested before probe completion reports true`() {
        val tracker = StartupOrderTracker()
        tracker.recordBindRequested()
        tracker.recordProbeCompleted()
        assertEquals(true, tracker.bindRequestedBeforeProbeCompleted())
        assertEquals(
            listOf(
                StartupOrderTracker.Event.BIND_REQUESTED,
                StartupOrderTracker.Event.PROBE_COMPLETED,
            ),
            tracker.events(),
        )
    }

    @Test
    fun `probe completing first reports false - the regression this guards`() {
        val tracker = StartupOrderTracker()
        tracker.recordProbeCompleted()
        tracker.recordBindRequested()
        assertEquals(false, tracker.bindRequestedBeforeProbeCompleted())
    }

    @Test
    fun `missing either event reports null`() {
        assertNull(StartupOrderTracker().bindRequestedBeforeProbeCompleted())
        val bindOnly = StartupOrderTracker().apply { recordBindRequested() }
        assertNull(bindOnly.bindRequestedBeforeProbeCompleted())
        val probeOnly = StartupOrderTracker().apply { recordProbeCompleted() }
        assertNull(probeOnly.bindRequestedBeforeProbeCompleted())
    }

    @Test
    fun `first record wins - a rebind cannot rewrite startup ordering`() {
        val tracker = StartupOrderTracker()
        assertTrue(tracker.recordBindRequested())
        tracker.recordProbeCompleted()
        assertFalse(tracker.recordBindRequested())
        assertFalse(tracker.recordProbeCompleted())
        assertEquals(true, tracker.bindRequestedBeforeProbeCompleted())
    }
}
