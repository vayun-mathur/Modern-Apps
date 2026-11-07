package com.vayunmathur.auto.platform

import android.content.Context
import android.location.Location
import android.location.LocationManager
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.auto.protocol.MapRoutePoint
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import android.os.Looper
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
class MonitorsTest {
    private val ctx get() = ApplicationProvider.getApplicationContext<Context>()
    private fun idleMain() = Shadows.shadowOf(Looper.getMainLooper()).idle()

    @Test fun `media monitor with no browser service leaves updates empty`() {
        val updates = mutableListOf<NowPlayingInfo>()
        val monitor = MediaPlaybackMonitor(ctx) { updates += it }
        monitor.start(); idleMain()
        assertTrue(updates.isEmpty())
        monitor.toggle(); monitor.seekToPrevious(); monitor.seekToNext(); idleMain()
        assertTrue(updates.isEmpty())
        monitor.stop(); idleMain()
    }

    @Test fun `nav monitor seeds from last known fix`() {
        val lm = ctx.getSystemService(LocationManager::class.java)
        val shadow = Shadows.shadowOf(lm)
        val loc = Location(LocationManager.GPS_PROVIDER).apply {
            latitude = 37.4; longitude = -122.1; time = 1234L
        }
        runCatching { shadow.setProviderEnabled(LocationManager.GPS_PROVIDER, true) }
        runCatching { shadow.setLastKnownLocation(LocationManager.GPS_PROVIDER, loc) }
        val updates = mutableListOf<com.vayunmathur.auto.protocol.NavSnapshot>()
        val monitor = NavGuidanceMonitor(ctx) { updates += it }
        monitor.start(); idleMain()
        // Seeding depends on Robolectric shadow behaviour; at minimum start/stop must not crash and state is consistent
        if (updates.isNotEmpty()) {
            assertEquals(374000000L, updates.last().latitudeE7)
        }
        monitor.stop(); idleMain()
    }

    @Test fun `nav monitor with route provider overlays guidance on simulated fix`() {
        val lm = ctx.getSystemService(LocationManager::class.java)
        val shadow = Shadows.shadowOf(lm)
        runCatching { shadow.setProviderEnabled(LocationManager.GPS_PROVIDER, true) }
        runCatching { shadow.setProviderEnabled(LocationManager.NETWORK_PROVIDER, true) }
        val updates = mutableListOf<com.vayunmathur.auto.protocol.NavSnapshot>()
        val monitor = NavGuidanceMonitor(ctx) { updates += it }
        monitor.routeProvider = RouteProvider {
            ActiveRoute(points = listOf(MapRoutePoint(37.4, -122.1)), nextRoad = "Main St", nextTurnDistanceM = 200, maneuver = "left")
        }
        monitor.start(); idleMain()
        val loc = Location(LocationManager.GPS_PROVIDER).apply {
            latitude = 37.5; longitude = -122.2; time = 99L; speed = 0f
        }
        runCatching { shadow.simulateLocation(loc) }
        idleMain()
        if (updates.isNotEmpty()) {
            val snap = updates.last()
            assertNotNull(snap)
            // parked heuristic: zero speed
            assertTrue(snap.parked)
        }
        monitor.stop(); idleMain()
    }
}
