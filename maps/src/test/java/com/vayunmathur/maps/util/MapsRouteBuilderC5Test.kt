package com.vayunmathur.maps.util

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * C5 route-builder transit branches: ride instructions with stop codes,
 * walk-leg placeholders that coalesce, and transit details assembly.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MapsRouteBuilderC5Test {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    private fun raw(
        maneuverId: Int = RouteService.API.Maneuver.STRAIGHT.ordinal,
        roadName: String = "El Camino Real",
        distanceMm: Long = 100_000L,
        duration10ms: Long = 6_000L,
        geometry: DoubleArray = doubleArrayOf(-122.0, 37.0, -122.1, 37.1),
        isTransit: Boolean = false,
        gtfsFeed: String? = null,
        stopCode: String? = null,
        endStopCode: String? = null,
        stopCount: Int = 0,
        headsign: String? = null,
        routeColor: Int = 0,
        depSecs: Int = 0,
        arrSecs: Int = 0,
    ) = OfflineRouter.RawStep(
        maneuverId = maneuverId,
        roadName = roadName,
        distanceMm = distanceMm,
        duration10ms = duration10ms,
        geometry = geometry,
        speedRatio = 1.0,
        isTransit = isTransit,
        gtfsFeed = gtfsFeed,
        stopCode = stopCode,
        endStopCode = endStopCode,
        stopCount = stopCount,
        lanePacked = intArrayOf(),
        headsign = headsign,
        routeColor = routeColor,
        depSecs = depSecs,
        arrSecs = arrSecs,
        boardStopId = null,
        alightStopId = null,
        elevations = doubleArrayOf(),
        ascentM = 0.0,
        descentM = 0.0,
    )

    @Test
    fun `transit ride step names line stops and carries details`() {
        val route = OfflineRouterRouteBuilder.buildRoute(
            context,
            arrayOf(
                raw(
                    maneuverId = RouteService.API.Maneuver.STRAIGHT.ordinal,
                    roadName = "N Judah",
                    isTransit = true,
                    gtfsFeed = "sfmta",
                    stopCode = "Board St",
                    endStopCode = "Alight Ave",
                    stopCount = 4,
                    headsign = "Downtown",
                    routeColor = 0xC60C30,
                    depSecs = 36_000,
                    arrSecs = 36_900,
                ),
            ),
            RouteService.TravelMode.TRANSIT,
        )
        val step = route.step.single()
        assertTrue(step.navInstruction.instructions.contains("N Judah"), step.navInstruction.instructions)
        val details = step.transitDetails
        assertNotNull(details, "transit details assembled")
        assertEquals("Downtown", details!!.headsign)
        assertEquals(4, details.stopCount)
        assertEquals("Board St", details.stopDetails.departureStop.name)
        assertEquals("Alight Ave", details.stopDetails.arrivalStop.name)
        assertEquals("N Judah", details.transitLine?.name)
        assertEquals("#C60C30", details.transitLine?.color)
    }

    @Test
    fun `adjacent transit walk legs coalesce into one phrased leg`() {
        val route = OfflineRouterRouteBuilder.buildRoute(
            context,
            arrayOf(
                raw(roadName = "Walk", distanceMm = 50_000L, duration10ms = 3_000L),
                raw(roadName = "Walk", distanceMm = 50_000L, duration10ms = 3_000L),
            ),
            RouteService.TravelMode.TRANSIT,
        )
        assertEquals(1, route.step.size)
        assertEquals(100.0, route.step.single().distanceMeters)
        assertTrue(
            route.step.single().navInstruction.instructions.isNotBlank(),
            route.step.single().navInstruction.instructions,
        )
    }

    @Test
    fun `transit step without feed still builds a step`() {
        val route = OfflineRouterRouteBuilder.buildRoute(
            context,
            arrayOf(raw(isTransit = true, roadName = "Local Bus")),
            RouteService.TravelMode.TRANSIT,
        )
        assertEquals(1, route.step.size)
        assertTrue(route.distanceMeters > 0)
    }
}
