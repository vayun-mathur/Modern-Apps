package com.vayunmathur.maps.util
import com.vayunmathur.maps.data.OfflineRouterRoadRoutes
import com.vayunmathur.maps.domain.RouteService

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.library.map.GeoPoint
import com.vayunmathur.maps.intents.OrderLookupClient
import com.vayunmathur.maps.intents.RideEstimateClient
import com.vayunmathur.maps.intents.RideHandoffContract
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.test.runTest
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * C5 cross-app IPC failure paths and the multi-leg route accumulator:
 * absent partner apps degrade to null, the booking deep link encodes
 * its parameters, and leg concatenation dedups the shared joint point
 * while offsetting elevation.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MapsIpcAccumC5Test {

    private val app: Application get() = ApplicationProvider.getApplicationContext()

    @Test
    fun `booking deep link encodes endpoints and labels`() {
        val link = RideHandoffContract.bookingDeepLink(
            37.7749, -122.4194, "Home Base",
            37.3382, -121.8863, "Work & Co",
        )
        assertTrue(link.startsWith("taxi://book?"), link)
        assertTrue(link.contains("pickup_lat=37.7749"), link)
        assertTrue(link.contains("dest_lng=-121.8863"), link)
        assertTrue(link.contains("pickup_label=Home%20Base"), link)
        assertTrue(link.contains("dest_label=Work%20%26%20Co"), link)
        // Blank labels are omitted, not sent empty.
        val bare = RideHandoffContract.bookingDeepLink(1.0, 2.0, "  ", 3.0, 4.0, null)
        assertTrue(!bare.contains("label"), bare)
    }

    @Test
    fun `absent partner apps degrade estimates and lookups to null`() = runTest {
        assertEquals(false, RideEstimateClient.isInstalled(app))
        assertNull(RideEstimateClient.estimate(app, 37.0, -122.0, 37.1, -122.1))
        assertNull(OrderLookupClient.lookup(app, "C5 Burgers", 37.0, -122.0))
        assertNull(OrderLookupClient.lookup(app, "   ", 37.0, -122.0))
    }

    private fun pt(lon: Double, lat: Double) = GeoPoint(lon, lat)

    private fun leg(points: List<GeoPoint>, dist: Double, ascent: Double): RouteService.Route =
        RouteService.Route(
            10.minutes, dist, points,
            listOf(
                RouteService.Step(
                    dist, 60.seconds, points,
                    RouteService.API.NavInstruction(), RouteService.TravelMode.DRIVE,
                ),
            ),
            elevationProfile = listOf(
                RouteService.ElevationPoint(0.0, 10.0),
                RouteService.ElevationPoint(dist, 20.0),
            ),
            ascentMeters = ascent,
            descentMeters = ascent / 2,
        )

    @Test
    fun `combined accumulator joins legs at the shared point`() {
        val clazz = Class.forName(
            "com.vayunmathur.maps.data.OfflineRouterRoadRoutes\$CombinedAccumulator",
        )
        val acc = clazz.getDeclaredConstructor().apply { isAccessible = true }.newInstance()
        val append = clazz.getDeclaredMethod("append", RouteService.Route::class.java)
            .apply { isAccessible = true }
        val build = clazz.getDeclaredMethod("build").apply { isAccessible = true }

        val leg1 = leg(listOf(pt(-122.0, 37.0), pt(-122.0, 37.001)), 100.0, 5.0)
        val leg2 = leg(listOf(pt(-122.0, 37.001), pt(-122.0, 37.002)), 200.0, 7.0)
        append.invoke(acc, leg1)
        append.invoke(acc, leg2)
        val route = build.invoke(acc) as RouteService.Route
        // The shared joint appears once, not twice.
        assertEquals(3, route.polyline.size)
        assertEquals(300.0, route.distanceMeters)
        assertEquals(1200, route.duration.inWholeSeconds)
        assertEquals(12.0, route.ascentMeters)
        // The second leg's profile is offset by the first leg's distance.
        assertEquals(
            listOf(0.0, 100.0, 100.0, 300.0),
            route.elevationProfile.map { it.distanceMeters },
        )
        assertEquals(2, route.step.size)
    }

    @Test
    fun `combined accumulator keeps disjoint leg joints`() {
        val clazz = Class.forName(
            "com.vayunmathur.maps.data.OfflineRouterRoadRoutes\$CombinedAccumulator",
        )
        val acc = clazz.getDeclaredConstructor().apply { isAccessible = true }.newInstance()
        val append = clazz.getDeclaredMethod("append", RouteService.Route::class.java)
            .apply { isAccessible = true }
        val build = clazz.getDeclaredMethod("build").apply { isAccessible = true }
        append.invoke(acc, leg(listOf(pt(0.0, 0.0), pt(0.0, 0.001)), 50.0, 1.0))
        append.invoke(acc, leg(listOf(pt(0.0, 0.005), pt(0.0, 0.006)), 50.0, 1.0))
        val route = build.invoke(acc) as RouteService.Route
        assertEquals(4, route.polyline.size)
    }
}
