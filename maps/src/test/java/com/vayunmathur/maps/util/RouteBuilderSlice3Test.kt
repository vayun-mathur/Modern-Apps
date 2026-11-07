package com.vayunmathur.maps.util

import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.maps.R
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Slice 3/3 (root package files M-Z): [OfflineRouterRouteBuilder] turns
 * native [OfflineRouter.RawStep]s into [RouteService.Route]s, plus the
 * itinerary wording in `ItineraryWording.kt` (`leaveAt`/`arriveAt`,
 * `phraseWalkLegs`).
 *
 * The steps are hand-built — no pack files, no JNI — but the instruction
 * text comes from the real string resources under Robolectric, so the
 * maneuver templates, wait phrasing, coalescing and elevation profile are
 * asserted end to end.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RouteBuilderSlice3Test {

    private val context get() = ApplicationProvider.getApplicationContext<android.content.Context>()

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
        lanes: IntArray = intArrayOf(),
        headsign: String? = null,
        routeColor: Int = 0,
        depSecs: Int = 0,
        arrSecs: Int = 0,
        elevations: DoubleArray = doubleArrayOf(),
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
        lanePacked = lanes,
        headsign = headsign,
        routeColor = routeColor,
        depSecs = depSecs,
        arrSecs = arrSecs,
        boardStopId = null,
        alightStopId = null,
        elevations = elevations,
        ascentM = 0.0,
        descentM = 0.0,
    )

    private fun build(vararg steps: OfflineRouter.RawStep, mode: RouteService.TravelMode = RouteService.TravelMode.DRIVE) =
        OfflineRouterRouteBuilder.buildRoute(context, arrayOf(*steps), mode)

    // --- instruction text ---

    @Test
    fun `named depart names the road`() {
        val route = build(raw(maneuverId = RouteService.API.Maneuver.DEPART.ordinal))
        val instr = route.step.single().navInstruction.instructions
        assertTrue("El Camino Real" in instr, instr)
        assertEquals(RouteService.API.Maneuver.DEPART, route.step.single().navInstruction.maneuver)
    }

    @Test
    fun `unnamed straight uses the unnamed template`() {
        val route = build(raw(roadName = ""))
        val instr = route.step.single().navInstruction.instructions
        assertTrue(instr.isNotBlank(), "empty instruction")
        assertTrue("El Camino Real" !in instr, instr)
    }

    @Test
    fun `wait leg phrases minutes at a named stop`() {
        val route = build(
            raw(
                maneuverId = RouteService.API.Maneuver.WAIT.ordinal,
                duration10ms = 300_00L,
                stopCode = "S1",
            ),
        )
        val instr = route.step.single().navInstruction.instructions
        assertTrue("5 min" in instr, instr)
        assertTrue("S1" in instr, instr)
    }

    @Test
    fun `short wait phrases seconds`() {
        val route = build(raw(maneuverId = RouteService.API.Maneuver.WAIT.ordinal, duration10ms = 45_00L))
        assertTrue("45 sec" in route.step.single().navInstruction.instructions)
    }

    @Test
    fun `unknown maneuver id falls back to unspecified`() {
        val route = build(raw(maneuverId = 9999, roadName = "Main St"))
        val step = route.step.single()
        assertEquals(RouteService.API.Maneuver.MANEUVER_UNSPECIFIED, step.navInstruction.maneuver)
        assertTrue("Main St" in step.navInstruction.instructions)
    }

    // --- distances, durations, geometry ---

    @Test
    fun `native units convert to meters and seconds`() {
        val route = build(raw(distanceMm = 1_500_000L, duration10ms = 9_000L))
        assertEquals(1500.0, route.step.single().distanceMeters)
        assertEquals(90.seconds, route.step.single().staticDuration)
        assertEquals(1500.0, route.distanceMeters)
    }

    @Test
    fun `geometry decodes lon-lat pairs and dedupes the join`() {
        val route = build(
            raw(geometry = doubleArrayOf(1.0, 2.0, 3.0, 4.0)),
            raw(geometry = doubleArrayOf(3.0, 4.0, 5.0, 6.0)),
        )
        assertEquals(3, route.polyline.size)
        assertEquals(com.vayunmathur.library.map.GeoPoint(1.0, 2.0), route.polyline.first())
        assertEquals(com.vayunmathur.library.map.GeoPoint(5.0, 6.0), route.polyline.last())
    }

    // --- lanes ---

    @Test
    fun `packed lanes decode directions and the active flag`() {
        val straight = RouteService.API.Maneuver.STRAIGHT.ordinal
        val right = RouteService.API.Maneuver.TURN_RIGHT.ordinal
        // dirMask bit set for STRAIGHT + active bit.
        val code = (1 shl straight) * 2 + 1
        val route = build(raw(lanes = intArrayOf(code, (1 shl right) * 2)))
        val lanes = route.step.single().lanes
        assertEquals(2, lanes.size)
        assertEquals(listOf(RouteService.API.Maneuver.STRAIGHT), lanes[0].directions)
        assertTrue(lanes[0].active)
        assertEquals(listOf(RouteService.API.Maneuver.TURN_RIGHT), lanes[1].directions)
        assertTrue(!lanes[1].active)
    }

    @Test
    fun `empty lane mask defaults to straight`() {
        val route = build(raw(lanes = intArrayOf(0)))
        assertEquals(listOf(RouteService.API.Maneuver.STRAIGHT), route.step.single().lanes.single().directions)
    }

    // --- coalescing ---

    @Test
    fun `consecutive same-road straights merge`() {
        val route = build(
            raw(distanceMm = 100_000L, duration10ms = 6_000L),
            raw(distanceMm = 200_000L, duration10ms = 12_000L),
        )
        assertEquals(1, route.step.size)
        assertEquals(300.0, route.step.single().distanceMeters)
        assertEquals(180.seconds, route.step.single().staticDuration)
    }

    @Test
    fun `a real turn breaks the merge`() {
        val route = build(
            raw(maneuverId = RouteService.API.Maneuver.STRAIGHT.ordinal),
            raw(maneuverId = RouteService.API.Maneuver.TURN_LEFT.ordinal),
        )
        assertEquals(2, route.step.size)
    }

    @Test
    fun `transit legs never merge`() {
        val leg = raw(isTransit = true, gtfsFeed = "sf", stopCode = "A", endStopCode = "B", routeColor = 0x00FF00)
        val route = build(leg, leg, mode = RouteService.TravelMode.TRANSIT)
        assertEquals(2, route.step.size)
    }

    // --- transit details ---

    @Test
    fun `transit leg carries line colour and stop times`() {
        val route = build(
            raw(
                isTransit = true,
                roadName = "38",
                gtfsFeed = "sf",
                stopCode = "A",
                endStopCode = "B",
                stopCount = 5,
                headsign = "Downtown",
                routeColor = 0xFF0000,
                depSecs = 3600,
                arrSecs = 3900,
            ),
            mode = RouteService.TravelMode.TRANSIT,
        )
        val details = route.step.single().transitDetails
        assertTrue(details != null)
        assertEquals("#FF0000", details.transitLine.color)
        assertEquals("Downtown", details.headsign)
        assertEquals(5, details.stopCount)
        assertEquals("01:00", details.stopDetails.departureTime)
        assertEquals("01:05", details.stopDetails.arrivalTime)
        assertEquals(RouteService.TravelMode.TRANSIT, route.step.single().travelMode)
    }

    @Test
    fun `non-transit steps carry no transit details`() {
        assertNull(build(raw()).step.single().transitDetails)
    }

    // --- itinerary bookends ---

    @Test
    fun `leaveAt subtracts the lead walk and arriveAt adds the tail`() {
        val walk = raw(duration10ms = 300_00L)
        val ride = raw(
            isTransit = true, gtfsFeed = "sf", stopCode = "A", endStopCode = "B",
            depSecs = 3600, arrSecs = 3900,
        )
        val steps = arrayOf(walk, ride, walk)
        // Lead walk is 300 s, tail walk is 300 s.
        assertEquals(formatServiceTime(3600 - 300), leaveAt(steps))
        assertEquals(formatServiceTime(3900 + 300), arriveAt(steps))
    }

    @Test
    fun `bookends are null with no timetabled ride`() {
        assertNull(leaveAt(arrayOf(raw())))
        assertNull(arriveAt(arrayOf(raw())))
    }

    @Test
    fun `route carries the bookends and totals`() {
        val walk = raw(duration10ms = 300_00L)
        val ride = raw(
            isTransit = true, gtfsFeed = "sf", stopCode = "A", endStopCode = "B",
            depSecs = 3600, arrSecs = 3900, routeColor = 1,
        )
        val route = build(walk, ride, mode = RouteService.TravelMode.TRANSIT)
        assertEquals(formatServiceTime(3300), route.departureTime)
        assertEquals(formatServiceTime(3900), route.arrivalTime)
    }

    // --- walk-leg phrasing ---

    @Test
    fun `walk legs phrase their merged duration`() {
        val steps = listOf(
            RouteService.Step(
                distanceMeters = 100.0,
                staticDuration = 90.seconds,
                polyline = emptyList(),
                navInstruction = RouteService.API.NavInstruction(
                    RouteService.API.Maneuver.MANEUVER_UNSPECIFIED, WALK_LEG_PLACEHOLDER,
                ),
                travelMode = RouteService.TravelMode.WALK,
            ),
        )
        val phrased = phraseWalkLegs(steps, RouteService.TravelMode.TRANSIT) { "walk $it min" }
        // 90 s rounds up to 2 min.
        assertEquals("walk 2 min", phrased.single().navInstruction.instructions)
    }

    @Test
    fun `walk-leg minutes floor at one`() {
        assertEquals(1, walkLegMinutes(0.seconds))
        assertEquals(1, walkLegMinutes(30.seconds))
        assertEquals(2, walkLegMinutes(61.seconds))
    }

    @Test
    fun `phraseWalkLegs is a no-op outside transit mode`() {
        val steps = listOf(
            RouteService.Step(
                distanceMeters = 1.0,
                staticDuration = 1.seconds,
                polyline = emptyList(),
                navInstruction = RouteService.API.NavInstruction(
                    RouteService.API.Maneuver.MANEUVER_UNSPECIFIED, WALK_LEG_PLACEHOLDER,
                ),
                travelMode = RouteService.TravelMode.WALK,
            ),
        )
        assertEquals(steps, phraseWalkLegs(steps, RouteService.TravelMode.DRIVE) { "x" })
    }

    // --- elevation ---

    @Test
    fun `elevation profile pairs cumulative distance with height`() {
        val route = build(
            raw(
                geometry = doubleArrayOf(0.0, 0.0, 0.0, 0.01),
                elevations = doubleArrayOf(10.0, 20.0),
            ),
        )
        val profile = route.elevationProfile
        assertEquals(2, profile.size)
        assertEquals(0.0, profile[0].distanceMeters)
        assertEquals(10.0, profile[0].elevationMeters)
        assertTrue(profile[1].distanceMeters > 1000.0, "${profile[1].distanceMeters}")
        assertEquals(20.0, profile[1].elevationMeters)
    }

    @Test
    fun `no elevation data means no profile`() {
        assertTrue(build(raw()).elevationProfile.isEmpty())
    }

    @Test
    fun `maneuver string resources are non-empty`() {
        assertTrue(context.getString(R.string.maneuver_depart, "X").contains("X"))
    }
}
