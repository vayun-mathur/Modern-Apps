package com.vayunmathur.maps.data.transit

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Slice 1/3 (maps): [TransitousDataSource] board mapping + caching, driven
 * through the [TransitousDataSource.fetchStoptimes] seam with canned MOTIS
 * wire DTOs — no network.
 *
 * Two contracts under test: the wire quirks map onto stable app models
 * (fallbacks everywhere, unusable entries dropped, never a throw), and the
 * cache separates live successes (20 s) from failures (3 s).
 */
class TransitousSlice1Test {

    private fun stoptime(
        scheduled: String? = "2026-11-06T17:00:00+01:00",
        realtime: String? = "2026-11-06T17:05:00+01:00",
        routeShortName: String? = "N",
        agencyName: String? = "Slice1 Transit",
        headsign: String? = "Downtown",
        stopName: String? = "Slice1 Station",
        track: String? = "2",
        scheduledTrack: String? = null,
        mode: String? = "SUBWAY",
        routeColor: String? = "#0055A4",
        realTime: Boolean = true,
        cancelled: Boolean = false,
        tripId: String? = null,
    ) = MotisStopTime(
        place = MotisPlace(
            name = stopName,
            scheduledDeparture = scheduled,
            departure = realtime,
            track = track,
            scheduledTrack = scheduledTrack,
        ),
        mode = mode,
        realTime = realTime,
        headsign = headsign,
        agencyName = agencyName,
        routeShortName = routeShortName,
        routeColor = routeColor,
        tripId = tripId,
        cancelled = cancelled,
    )

    private val defaultFetch = TransitousDataSource.fetchStoptimes

    private fun runWithDto(
        dto: MotisStoptimes?,
        block: suspend (calls: MutableList<String>) -> Unit,
    ) = runBlocking {
        val calls = mutableListOf<String>()
        TransitousDataSource.fetchStoptimes = { stopId -> calls += stopId; dto }
        try {
            block(calls)
        } finally {
            TransitousDataSource.fetchStoptimes = defaultFetch
        }
    }

    @Test
    fun `a full stoptime maps every field with a five-minute delay`() =
        runWithDto(MotisStoptimes(listOf(stoptime()))) {
            val dep = TransitousDataSource.departures("slice1-full").single()
            assertEquals("N", dep.line)
            assertEquals("Downtown", dep.headsign)
            // 17:00 scheduled vs 17:05 realtime, both +01:00.
            assertEquals(5, dep.delayMinutes)
            assertEquals(dep.scheduledMillis + 5 * 60_000L, dep.realtimeMillis)
            assertEquals(true, dep.realTime)
            assertEquals("2", dep.platform)
            assertEquals("SUBWAY", dep.mode)
            assertEquals("0055A4", dep.routeColor)
            assertEquals(false, dep.cancelled)
        }

    @Test
    fun `line falls back from short name to agency to empty`() =
        runWithDto(
            MotisStoptimes(
                listOf(
                    stoptime(routeShortName = "  ", agencyName = "Slice1 Agency"),
                    stoptime(routeShortName = null, agencyName = "  "),
                ),
            ),
        ) {
            val deps = TransitousDataSource.departures("slice1-line-fallback")
            assertEquals(listOf("Slice1 Agency", ""), deps.map { it.line })
        }

    @Test
    fun `headsign falls back to the stop name`() =
        runWithDto(MotisStoptimes(listOf(stoptime(headsign = " ", stopName = "Fallback Halt")))) {
            assertEquals("Fallback Halt", TransitousDataSource.departures("slice1-head").single().headsign)
        }

    @Test
    fun `platform prefers the realtime track over the scheduled one`() =
        runWithDto(
            MotisStoptimes(
                listOf(
                    stoptime(track = "3", scheduledTrack = "1"),
                    stoptime(track = null, scheduledTrack = "1"),
                    stoptime(track = " ", scheduledTrack = " "),
                ),
            ),
        ) {
            val platforms = TransitousDataSource.departures("slice1-track").map { it.platform }
            assertEquals(listOf("3", "1", null), platforms)
        }

    @Test
    fun `entries without a usable departure time are dropped`() =
        runWithDto(
            MotisStoptimes(
                listOf(
                    stoptime(scheduled = null, realtime = null),
                    stoptime(scheduled = "garbage", realtime = "garbage"),
                    stoptime().copy(place = null),
                    stoptime(),
                ),
            ),
        ) {
            assertEquals(1, TransitousDataSource.departures("slice1-drop").size)
        }

    @Test
    fun `a missing realtime time reads as on time`() =
        runWithDto(MotisStoptimes(listOf(stoptime(realtime = null)))) {
            val dep = TransitousDataSource.departures("slice1-ontime").single()
            assertEquals(0, dep.delayMinutes)
            assertEquals(dep.scheduledMillis, dep.realtimeMillis)
        }

    @Test
    fun `a plain Z instant parses as well as an offset one`() =
        runWithDto(
            MotisStoptimes(
                listOf(
                    stoptime(scheduled = "2026-11-06T16:00:00Z", realtime = "2026-11-06T16:00:00Z"),
                ),
            ),
        ) {
            val dep = TransitousDataSource.departures("slice1-zulu").single()
            assertEquals(0, dep.delayMinutes)
            assertTrue(dep.scheduledMillis > 0)
        }

    @Test
    fun `cancelled trips and trip ids survive the mapping`() =
        runWithDto(MotisStoptimes(listOf(stoptime(cancelled = true, tripId = "  ")))) {
            val dep = TransitousDataSource.departures("slice1-cancel").single()
            assertEquals(true, dep.cancelled)
            assertNull(dep.tripId, "a blank trip id is not a trip id")
        }

    @Test
    fun `a blank colour is not a colour`() =
        runWithDto(MotisStoptimes(listOf(stoptime(routeColor = "  ")))) {
            assertNull(TransitousDataSource.departures("slice1-nocolor").single().routeColor)
        }

    @Test
    fun `a successful board is served and then cached`() =
        runWithDto(MotisStoptimes(listOf(stoptime()))) { calls ->
            val stop = "slice1-stop-cache"
            val first = TransitousDataSource.departures(stop)
            assertEquals(1, first.size)
            assertEquals(first, TransitousDataSource.departures(stop))
            assertEquals(1, calls.size, "the second read must come from cache")
        }

    @Test
    fun `force bypasses even a fresh cache entry`() =
        runWithDto(MotisStoptimes(listOf(stoptime()))) { calls ->
            val stop = "slice1-stop-force"
            TransitousDataSource.departures(stop)
            TransitousDataSource.departures(stop, force = true)
            assertEquals(2, calls.size)
        }

    @Test
    fun `a failed fetch returns empty and is remembered only briefly`() {
        runBlocking {
            var n = 0
            TransitousDataSource.fetchStoptimes = { n++; null }
            try {
                val stop = "slice1-stop-fail"
                assertTrue(TransitousDataSource.departures(stop).isEmpty())
                // Still inside the 3 s failure TTL: served from the failure entry.
                assertTrue(TransitousDataSource.departures(stop).isEmpty())
                assertEquals(1, n)
                // But a manual refresh always retries.
                assertTrue(TransitousDataSource.departures(stop, force = true).isEmpty())
                assertEquals(2, n)
            } finally {
                TransitousDataSource.fetchStoptimes = defaultFetch
            }
        }
    }

    @Test
    fun `motis wire dtos default everything`() {
        assertTrue(MotisStoptimes().stopTimes.isEmpty())
        val place = MotisPlace()
        assertNull(place.name)
        assertNull(place.departure)
        assertNull(place.scheduledDeparture)
        val st = MotisStopTime()
        assertNull(st.place)
        assertEquals(false, st.realTime)
        assertEquals(false, st.cancelled)
    }

    @Test
    fun `stop and trip models hold their documented shapes`() {
        val stop = TransitStop(id = "m1", name = "Slice1 Station", lat = 37.0, lon = -122.0)
        assertEquals("m1", stop.id)
        val tripStop = TripStop("A", 1.0, 2.0, 1000L, 2000L, "")
        assertEquals("", tripStop.motisId)
        val itinerary = TripItinerary("N", "Downtown", "0055A4", "SUBWAY", false, listOf(tripStop))
        assertEquals(1, itinerary.stops.size)
        assertEquals(false, itinerary.cancelled)
        val rail = RailLine("N", "0055A4", "SUBWAY", emptyList(), 0, 1, 255)
        assertEquals(1, rail.lanes)
        // Departure's offline join key survives a copy.
        val dep = Departure("N", "D", 1L, 1L, 0, false, null, null, null, false)
        assertEquals(99L, dep.copy(tripVehicleId = 99L).tripVehicleId)
    }
}
