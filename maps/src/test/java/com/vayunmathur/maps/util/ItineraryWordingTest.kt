package com.vayunmathur.maps.util
import com.vayunmathur.maps.domain.WALK_LEG_PLACEHOLDER
import com.vayunmathur.maps.util.OfflineRouter
import com.vayunmathur.maps.domain.RouteService
import com.vayunmathur.maps.domain.arriveAt
import com.vayunmathur.maps.domain.leaveAt
import com.vayunmathur.maps.domain.phraseWalkLegs
import com.vayunmathur.maps.domain.walkLegMinutes

import com.vayunmathur.library.map.GeoPoint
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * How a transit itinerary is worded: a walk leg's duration, and the "Leave at" /
 * "Arrive at" bookends.
 *
 * All four are derived from the raw native legs, so none of them needs a device.
 * The walk phrasing takes its plural lookup as a lambda for exactly that reason —
 * what is worth pinning is *which* steps get rephrased and off *which* duration,
 * not the wording of the resource.
 */
class ItineraryWordingTest {

    /** A native leg. Only the fields these functions read are meaningful. */
    private fun leg(
        isTransit: Boolean,
        depSecs: Int = 0,
        arrSecs: Int = 0,
        seconds: Long = 0,
        maneuverId: Int = 0,
    ) = OfflineRouter.RawStep(
        maneuverId = maneuverId,
        roadName = if (isTransit) "N" else "Walk",
        distanceMm = 0,
        duration10ms = seconds * 100,
        geometry = DoubleArray(0),
        speedRatio = 1.0,
        isTransit = isTransit,
        gtfsFeed = null,
        stopCode = null,
        endStopCode = null,
        stopCount = 0,
        lanePacked = IntArray(0),
        headsign = null,
        routeColor = 0,
        depSecs = depSecs,
        arrSecs = arrSecs,
        boardStopId = null,
        alightStopId = null,
        elevations = DoubleArray(0),
        ascentM = 0.0,
        descentM = 0.0,
    )

    private val wait = RouteService.API.Maneuver.WAIT.ordinal

    private fun walkStep(duration: kotlin.time.Duration) = RouteService.Step(
        distanceMeters = 100.0,
        staticDuration = duration,
        polyline = listOf(GeoPoint(0.0, 0.0)),
        navInstruction = RouteService.API.NavInstruction(
            RouteService.API.Maneuver.MANEUVER_UNSPECIFIED,
            WALK_LEG_PLACEHOLDER,
        ),
        travelMode = RouteService.TravelMode.WALK,
    )

    private fun label(minutes: Int) = "Walk $minutes min"

    // --- Walk-leg wording ---

    @Test
    fun `a walk leg is phrased from its duration, not its road name`() {
        val phrased = phraseWalkLegs(
            listOf(walkStep(8.minutes)),
            RouteService.TravelMode.TRANSIT,
            ::label,
        )
        assertEquals("Walk 8 min", phrased.single().navInstruction.instructions)
    }

    /**
     * The placeholder is what lets coalescing merge adjacent walk legs, so it must
     * never survive to the UI — the whole point of rephrasing after the merge.
     */
    @Test
    fun `no walk placeholder survives phrasing`() {
        val phrased = phraseWalkLegs(
            listOf(walkStep(3.minutes), walkStep(5.minutes)),
            RouteService.TravelMode.TRANSIT,
            ::label,
        )
        assertEquals(
            listOf("Walk 3 min", "Walk 5 min"),
            phrased.map { it.navInstruction.instructions },
        )
    }

    /**
     * Two adjacent walk legs are merged before phrasing, so the row shows the summed
     * duration. Pinned as the merged step's own duration, since embedding the text
     * earlier is precisely what would have stopped the merge happening.
     */
    @Test
    fun `a merged pair of walk legs is phrased from the summed duration`() {
        val merged = walkStep(3.minutes + 5.minutes)
        val phrased = phraseWalkLegs(
            listOf(merged),
            RouteService.TravelMode.TRANSIT,
            ::label,
        )
        assertEquals("Walk 8 min", phrased.single().navInstruction.instructions)
    }

    @Test
    fun `a ride leg is left alone`() {
        val ride = walkStep(4.minutes).copy(
            travelMode = RouteService.TravelMode.TRANSIT,
            navInstruction = RouteService.API.NavInstruction(
                RouteService.API.Maneuver.RIDE,
                "Ride N from Alpha to Gamma",
            ),
        )
        val phrased = phraseWalkLegs(
            listOf(ride),
            RouteService.TravelMode.TRANSIT,
            ::label,
        )
        assertEquals("Ride N from Alpha to Gamma", phrased.single().navInstruction.instructions)
    }

    @Test
    fun `driving steps are untouched even if they carry the placeholder`() {
        val steps = listOf(walkStep(4.minutes))
        assertEquals(
            steps,
            phraseWalkLegs(steps, RouteService.TravelMode.DRIVE, ::label),
        )
    }

    /** Never "0 min": a sub-minute walk is still a walk. */
    @Test
    fun `a sub-minute walk rounds up to one minute`() {
        assertEquals(1, walkLegMinutes(1.seconds))
        assertEquals(1, walkLegMinutes(59.seconds))
        assertEquals(1, walkLegMinutes(60.seconds))
    }

    @Test
    fun `walk minutes round up rather than truncating`() {
        assertEquals(2, walkLegMinutes(61.seconds))
        assertEquals(5, walkLegMinutes(4.minutes + 1.seconds))
    }

    // --- Leave at / Arrive at ---

    @Test
    fun `leave at is the first ride departure minus the leading walk`() {
        val steps = arrayOf(
            leg(isTransit = false, seconds = 300), // 5 min walk to the stop
            leg(isTransit = true, depSecs = 32_400, arrSecs = 34_200), // 09:00 -> 09:30
        )
        assertEquals("08:55", leaveAt(steps))
    }

    /**
     * A wait leg is slack at the stop, not travel: subtracting it too would tell the
     * user to leave earlier than they need to.
     */
    @Test
    fun `a wait before the ride does not move leave at any earlier`() {
        val steps = arrayOf(
            leg(isTransit = false, seconds = 300),
            leg(isTransit = false, seconds = 600, maneuverId = wait),
            leg(isTransit = true, depSecs = 32_400, arrSecs = 34_200),
        )
        assertEquals("08:55", leaveAt(steps))
    }

    @Test
    fun `arrive at is the last ride arrival plus the trailing walk`() {
        val steps = arrayOf(
            leg(isTransit = true, depSecs = 32_400, arrSecs = 34_200), // lands 09:30
            leg(isTransit = false, seconds = 420), // 7 min walk off the end
        )
        assertEquals("09:37", arriveAt(steps))
    }

    @Test
    fun `both bookends span the outermost rides of a two-ride itinerary`() {
        val steps = arrayOf(
            leg(isTransit = false, seconds = 300),
            leg(isTransit = true, depSecs = 32_400, arrSecs = 33_000),
            leg(isTransit = false, seconds = 120),
            leg(isTransit = true, depSecs = 33_600, arrSecs = 34_800), // lands 09:40
            leg(isTransit = false, seconds = 300),
        )
        assertEquals("08:55", leaveAt(steps))
        assertEquals("09:45", arriveAt(steps))
    }

    /** Nothing to bracket: a walk-only itinerary omits both rows. */
    @Test
    fun `a walk-only itinerary has neither bookend`() {
        val steps = arrayOf(leg(isTransit = false, seconds = 900))
        assertNull(leaveAt(steps))
        assertNull(arriveAt(steps))
    }

    /** A ride with no timetable (`0 secs`) cannot anchor a wall-clock row. */
    @Test
    fun `a ride without times has neither bookend`() {
        val steps = arrayOf(leg(isTransit = true, depSecs = 0, arrSecs = 0))
        assertNull(leaveAt(steps))
        assertNull(arriveAt(steps))
    }
}

