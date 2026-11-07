package com.vayunmathur.auto.platform

import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Auto module: [CarLauncherState] — the car display's shared UI state.
 * Setters publish to their flows; hideApp drops one app; hideNowPlaying
 * only clears an idle snapshot so a stale "Playing" still clears on pause.
 */
@RunWith(RobolectricTestRunner::class)
class CarLauncherStateTest {

    @BeforeTest
    fun reset() {
        CarLauncherState.setNowPlaying(
            NowPlayingInfo(title = null, artist = null, playing = false, positionMs = 0L, durationMs = null),
        )
        CarLauncherState.hideNowPlaying()
        CarLauncherState.setDrivingRestricted(false)
        CarLauncherState.setNight(false)
        CarLauncherState.setActiveCall(null)
        CarLauncherState.selectApp(null)
        CarLauncherState.setDiscovered(emptyList())
        CarLauncherState.setPinnedOrder(emptyList())
    }

    @Test
    fun `now playing publishes and hide clears a showable snapshot`() {
        val live = NowPlayingInfo(title = "Song", artist = "Band", playing = true, positionMs = 0L, durationMs = null)
        CarLauncherState.setNowPlaying(live)
        assertEquals(live, CarLauncherState.nowPlaying.value)
        // Hide clears the card including a stale resume snapshot; the
        // launcher re-shows on the next visible snapshot. An already-idle
        // snapshot is retained (the card itself gates on shouldShowCard).
        CarLauncherState.hideNowPlaying()
        assertNull(CarLauncherState.nowPlaying.value)
        val idle = live.copy(playing = false, title = null)
        CarLauncherState.setNowPlaying(idle)
        CarLauncherState.hideNowPlaying()
        assertEquals(idle, CarLauncherState.nowPlaying.value)
    }

    @Test
    fun `flags and selection publish`() {
        CarLauncherState.setDrivingRestricted(true)
        assertTrue(CarLauncherState.drivingRestricted.value)
        CarLauncherState.setNight(true)
        assertTrue(CarLauncherState.nightDark.value)
        CarLauncherState.selectApp("maps")
        assertEquals("maps", CarLauncherState.selectedApp.value)
        CarLauncherState.selectApp(null)
        assertNull(CarLauncherState.selectedApp.value)
    }

    @Test
    fun `active call publishes and clears`() {
        val call = ActiveCallInfo(telecomState = 1, number = "555", acceptedMs = 0L)
        CarLauncherState.setActiveCall(call)
        assertEquals(call, CarLauncherState.activeCall.value)
        CarLauncherState.setActiveCall(null)
        assertNull(CarLauncherState.activeCall.value)
    }

    @Test
    fun `phone status and pinned order publish`() {
        val status = PhoneStatus(signalBars = 3, batteryPercent = 80, batteryCharging = false)
        CarLauncherState.setPhoneStatus(status)
        assertEquals(status, CarLauncherState.phoneStatus.value)
        CarLauncherState.setPinnedOrder(listOf("a", "b"))
        assertEquals(listOf("a", "b"), CarLauncherState.pinnedOrder.value)
    }

    @Test
    fun `actions update transforms the stored actions`() {
        var taps = 0
        CarLauncherState.updateActions { it.copy(onMediaTap = { taps++ }) }
        CarLauncherState.actions.value.onMediaTap()
        assertEquals(1, taps)
        // Restore the no-op default so later suites see a clean slate.
        CarLauncherState.updateActions { CarLauncherActions() }
        CarLauncherState.actions.value.onMediaTap()
        assertEquals(1, taps)
    }

    @Test
    fun `defaults are empty and unrestricted`() {
        assertFalse(CarLauncherState.drivingRestricted.value)
        assertFalse(CarLauncherState.nightDark.value)
        assertNull(CarLauncherState.selectedApp.value)
        assertEquals(emptyList(), CarLauncherState.discovered.value)
    }
}
