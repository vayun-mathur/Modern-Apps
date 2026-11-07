package com.vayunmathur.auto.platform

import android.content.res.Configuration
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.library.carhost.HostNavState
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import android.os.Looper
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
class CarDisplayTest {
    private val ctx get() = ApplicationProvider.getApplicationContext<android.content.Context>()
    @BeforeTest fun reset() {
        CarLauncherState.setDrivingRestricted(false); CarLauncherState.setNight(false)
        CarLauncherState.setActiveCall(null); CarLauncherState.selectApp(null)
    }
    private fun idleMain() = Shadows.shadowOf(Looper.getMainLooper()).idle()
    private fun display() = CarDisplay(ctx, 800, 480, 160)

    @Test fun `setters publish to launcher state without a presentation`() {
        val d = display()
        val info = NowPlayingInfo(title = "Song", artist = "Band", playing = true, positionMs = 0, durationMs = null)
        d.setNowPlaying(info)
        assertEquals(info, CarLauncherState.nowPlaying.value)
        d.setDrivingRestricted(true); assertTrue(CarLauncherState.drivingRestricted.value)
        d.setParkedBrowsingGate(true); assertFalse(CarLauncherState.drivingRestricted.value)
        d.setParkedBrowsingGate(false); assertTrue(CarLauncherState.drivingRestricted.value)
        d.setNight(true); assertTrue(CarLauncherState.nightDark.value)
        val call = ActiveCallInfo(telecomState = 1, number = "555", acceptedMs = 0)
        d.setActiveCall(call); assertEquals(call, CarLauncherState.activeCall.value)
        d.setActiveCall(null); assertNull(CarLauncherState.activeCall.value)
        idleMain()
    }

    @Test fun `night forwards to map applier and host nav publishes`() {
        val d = display()
        var dark: Boolean? = null
        d.mapDarkApplier = { dark = it }
        d.setNight(true)
        assertEquals(true, dark)
        val nav = HostNavState(navigating = true, cue = "Turn")
        d.setHostNavState(nav)
        assertEquals(nav, CarLauncherState.hostNav.value)
        idleMain()
    }

    @Test fun `tap bounds contain edges inclusively`() {
        val b = CarDisplay.TapBounds(10, 20, 110, 120)
        assertTrue(b.contains(10f, 20f)); assertTrue(b.contains(110f, 120f))
        assertFalse(b.contains(9f, 50f)); assertFalse(b.contains(50f, 121f))
    }

    @Test fun `lifecycle without show is safe`() {
        val d = display()
        d.setParked(true); d.setParked(false)
        d.onNightConfigChanged(Configuration())
        d.startFrameInvalidation(30); d.stopFrameInvalidation()
        d.updateConfig(800, 480, 160) // same size: no-op even without surface
        d.release(); idleMain()
        // input with no decor view drops
        assertFalse(d.input.injectTouch(0, listOf(Triple(1f, 2f, 0)), 0))
        assertFalse(d.input.injectKey(4, true))
        assertFalse(d.input.injectScroll(1))
    }

    @Test fun `late wired callbacks are stored`() {
        val d = display()
        var media = 0
        d.onMediaTap = { media++ }
        d.onMediaTap?.invoke(); assertEquals(1, media)
        d.phoneStatusSource = { PhoneStatus(signalBars = 2, batteryPercent = 50, batteryCharging = false) }
        assertEquals(50, d.phoneStatusSource?.invoke()?.batteryPercent)
        idleMain()
    }
}
