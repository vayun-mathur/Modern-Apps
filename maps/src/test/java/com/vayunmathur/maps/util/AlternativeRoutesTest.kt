package com.vayunmathur.maps.util
import com.vayunmathur.maps.domain.AlternativeRoutes
import com.vayunmathur.maps.domain.RouteService

import com.vayunmathur.library.map.GeoPoint
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes

/** Diversity gating on synthetic routes: no graph or schedule needed. */
class AlternativeRoutesTest {

    private fun pt(lon: Double, lat: Double) = GeoPoint(lon, lat)

    private fun roadRoute(points: List<GeoPoint>, minutes: Int = 10): RouteService.Route {
        val steps = points.zipWithNext().map {
            RouteService.Step(
                distanceMeters = 100.0,
                staticDuration = 1.minutes,
                polyline = it.toList(),
                navInstruction = RouteService.API.NavInstruction(),
                travelMode = RouteService.TravelMode.DRIVE,
            )
        }
        return RouteService.Route(
            duration = minutes.minutes,
            distanceMeters = steps.sumOf { it.distanceMeters },
            polyline = points,
            step = steps,
        )
    }

    /** A straight east-west corridor and a genuinely different northern one. */
    private val primary = roadRoute(listOf(pt(0.0, 0.0), pt(0.01, 0.0), pt(0.02, 0.0), pt(0.03, 0.0)))
    private val northern = roadRoute(
        listOf(pt(0.0, 0.0), pt(0.01, 0.01), pt(0.02, 0.01), pt(0.03, 0.0)),
    )

    @Test
    fun `one-street swap driving variant is rejected`() {
        // Same corridor, jogged by ~11 m for one block: almost all shared.
        val swap = roadRoute(listOf(pt(0.0, 0.0), pt(0.01, 0.0001), pt(0.02, 0.0), pt(0.03, 0.0)))
        assertTrue(AlternativeRoutes.sharedGeometryRatio(primary, swap) >= AlternativeRoutes.MAX_SHARED_GEOMETRY_RATIO)
        assertEquals(emptyList(), AlternativeRoutes.diverseAlternatives(primary, listOf(swap), transit = false))
    }

    @Test
    fun `distinct corridor driving alternative is accepted`() {
        assertTrue(AlternativeRoutes.sharedGeometryRatio(primary, northern) < AlternativeRoutes.MAX_SHARED_GEOMETRY_RATIO)
        assertEquals(listOf(northern), AlternativeRoutes.diverseAlternatives(primary, listOf(northern), transit = false))
    }

    @Test
    fun `single-route case yields exactly one route`() {
        assertEquals(emptyList(), AlternativeRoutes.diverseAlternatives(primary, emptyList(), transit = false))
        assertEquals(emptyList(), AlternativeRoutes.diverseAlternatives(primary, listOf(primary), transit = false))
    }

    private fun transitRoute(
        legs: List<Triple<String, String, String>>,
        walkMeters: Double = 200.0,
        rideMeters: Double = 5_000.0,
        durationMinutes: Int = 20,
        polyline: List<GeoPoint> = listOf(pt(0.0, 0.0), pt(0.02, 0.0)),
    ): RouteService.Route {
        val steps = mutableListOf<RouteService.Step>()
        if (walkMeters > 0) {
            steps += RouteService.Step(
                distanceMeters = walkMeters, staticDuration = 3.minutes,
                polyline = listOf(pt(0.0, 0.0), pt(0.001, 0.0)),
                navInstruction = RouteService.API.NavInstruction(),
                travelMode = RouteService.TravelMode.WALK,
            )
        }
        legs.forEach { (line, board, alight) ->
            steps += RouteService.Step(
                distanceMeters = rideMeters, staticDuration = 10.minutes,
                polyline = listOf(pt(0.001, 0.0), pt(0.02, 0.0)),
                navInstruction = RouteService.API.NavInstruction(),
                travelMode = RouteService.TravelMode.TRANSIT,
                transitDetails = RouteService.API.TransitDetails(
                    headsign = "Downtown", stopCount = 5,
                    transitLine = RouteService.API.TransitLine(name = line, nameShort = line, color = "#000"),
                    stopDetails = RouteService.API.StopDetails(
                        arrivalTime = "10:10", departureTime = "10:00",
                        arrivalStop = RouteService.API.Stop(alight),
                        departureStop = RouteService.API.Stop(board),
                    ),
                ),
            )
        }
        return RouteService.Route(
            duration = durationMinutes.minutes,
            distanceMeters = walkMeters + rideMeters * legs.size,
            polyline = polyline,
            step = steps,
        )
    }

    @Test
    fun `early-alight walk variant of the same ride is rejected`() {
        val full = transitRoute(listOf(Triple("Red", "A", "C")))
        // Same line, same board, alighting one stop early and walking the
        // rest: a truncation of the primary's signature, not an alternative.
        val earlyAlight = transitRoute(listOf(Triple("Red", "A", "B")), walkMeters = 900.0, rideMeters = 3_000.0)
        assertEquals(emptyList(), AlternativeRoutes.diverseAlternatives(full, listOf(earlyAlight), transit = true))
    }

    @Test
    fun `same line re-departed later is not an alternative`() {
        val full = transitRoute(listOf(Triple("Red", "A", "C")))
        val nextBus = transitRoute(listOf(Triple("Red", "A", "C")))
        assertEquals(emptyList(), AlternativeRoutes.diverseAlternatives(full, listOf(nextBus), transit = true))
    }

    @Test
    fun `distinct-line transit alternative is accepted`() {
        val full = transitRoute(listOf(Triple("Red", "A", "C")))
        val otherLine = transitRoute(listOf(Triple("Blue", "A", "C")))
        assertEquals(listOf(otherLine), AlternativeRoutes.diverseAlternatives(full, listOf(otherLine), transit = true))
    }

    @Test
    fun `walk-heavy transit re-pad is rejected`() {
        val full = transitRoute(listOf(Triple("Red", "A", "C")))
        val walkHeavy = transitRoute(listOf(Triple("Blue", "B", "C")), walkMeters = 6_000.0, rideMeters = 2_000.0)
        assertTrue(AlternativeRoutes.walkShare(walkHeavy) > AlternativeRoutes.MAX_WALK_SHARE)
        assertEquals(emptyList(), AlternativeRoutes.diverseAlternatives(full, listOf(walkHeavy), transit = true))
    }

    @Test
    fun `alight early and transfer to a parallel bus on the same corridor is rejected`() {
        // (a) Ride-through on Red vs alight at B and continue on the
        // parallel Blue over the same stops and geometry: the transfer
        // re-covers the primary's corridor, so it is not an alternative.
        val primary = transitRoute(listOf(Triple("Red", "A", "C")))
        val parallel = transitRoute(
            listOf(Triple("Red", "A", "B"), Triple("Blue", "B", "C")),
        )
        assertEquals(emptyList(), AlternativeRoutes.diverseAlternatives(primary, listOf(parallel), transit = true))
    }

    @Test
    fun `one extra transfer arriving about the same time is rejected`() {
        // (b) A different second service on a different corridor, but the
        // extra transfer buys no time: transfer count is a cost, and
        // ~0 minutes of saving does not pay for it.
        val primary = transitRoute(listOf(Triple("Red", "A", "C")), durationMinutes = 20)
        val sameTime = transitRoute(
            listOf(Triple("Blue", "A", "D"), Triple("Green", "D", "E")),
            durationMinutes = 19,
            polyline = listOf(pt(0.0, 0.0), pt(0.01, 0.01), pt(0.02, 0.01), pt(0.03, 0.0)),
        )
        assertEquals(emptyList(), AlternativeRoutes.diverseAlternatives(primary, listOf(sameTime), transit = true))
    }

    @Test
    fun `extra transfer substantially faster on a different line is accepted`() {
        // (c) One more transfer, but 9 minutes faster on genuinely
        // different services and geometry: the saving pays for it.
        val primary = transitRoute(listOf(Triple("Red", "A", "C")), durationMinutes = 25)
        val faster = transitRoute(
            listOf(Triple("Blue", "A", "D"), Triple("Green", "D", "E")),
            durationMinutes = 16,
            polyline = listOf(pt(0.0, 0.0), pt(0.01, 0.01), pt(0.02, 0.01), pt(0.03, 0.0)),
        )
        assertEquals(listOf(faster), AlternativeRoutes.diverseAlternatives(primary, listOf(faster), transit = true))
    }

    @Test
    fun `eta order of accepted alternatives is preserved`() {
        val slower = roadRoute(listOf(pt(0.0, 0.0), pt(0.01, 0.012), pt(0.02, 0.012), pt(0.03, 0.0)), minutes = 18)
        val accepted = AlternativeRoutes.diverseAlternatives(primary, listOf(northern, slower), transit = false)
        assertEquals(listOf(northern, slower).filter { it in accepted }, accepted.filter { it in listOf(northern, slower) })
        assertEquals(northern, accepted.first())
    }
}
