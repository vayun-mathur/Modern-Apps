package com.vayunmathur.maps.util

import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.library.map.GeoPoint
import com.vayunmathur.maps.data.SpecificFeature
import kotlinx.coroutines.runBlocking
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.Test
import kotlin.test.assertNull

/**
 * Slice 3/3 (root package files M-Z): [OfflineRouterRoadRoutes] entry guards.
 *
 * Multi-waypoint planning needs the native graph; a route with fewer than an
 * origin and a destination resolves to null before any JNI is touched, which
 * is exactly what these pin — including that TRANSIT never reaches the road
 * graph even when short (the mode split happens on the same path).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RoadRoutesSlice3Test {

    private val context get() = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val user = GeoPoint(-122.4, 37.8)

    private fun singleWaypointRoute() = SpecificFeature.Route(
        waypoints = listOf(
            SpecificFeature.GenericPlace(
                name = "Only",
                phone = null,
                website = null,
                openingHours = null,
                position = GeoPoint(-122.3, 37.7),
            ),
        ),
    )

    private fun emptyRoute() = SpecificFeature.Route(waypoints = emptyList())

    @Test
    fun `drive with a single waypoint plans nothing`() = runBlocking {
        assertNull(
            OfflineRouterRoadRoutes.getRouteForMode(
                context, singleWaypointRoute(), user, RouteService.TravelMode.DRIVE,
            ),
        )
    }

    @Test
    fun `transit with a single waypoint plans nothing without touching the graph`() = runBlocking {
        assertNull(
            OfflineRouterRoadRoutes.getRouteForMode(
                context, singleWaypointRoute(), user, RouteService.TravelMode.TRANSIT,
            ),
        )
    }

    @Test
    fun `empty waypoints plan nothing`() = runBlocking {
        assertNull(
            OfflineRouterRoadRoutes.getRouteMulti(
                context, emptyRoute(), user, RouteService.TravelMode.WALK,
            ),
        )
        assertNull(
            OfflineRouterRoadRoutes.getRouteForMode(
                context, emptyRoute(), user, RouteService.TravelMode.BICYCLE,
            ),
        )
    }
}
