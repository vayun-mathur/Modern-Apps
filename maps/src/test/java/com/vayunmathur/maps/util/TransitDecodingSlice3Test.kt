package com.vayunmathur.maps.util

import com.vayunmathur.library.map.GeoPoint
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Slice 3/3 (root package files M-Z): the offline-transit decoding helpers
 * split out of [OfflineRouterTransit] — the query [TransitClock], board-row
 * decoding ([TransitDepartures]), itinerary-stop decoding
 * ([TransitItineraries]) and the realtime overlay builder ([TransitBoards]).
 *
 * Pure Kotlin against hand-built native rows: no pack files, no network, no
 * JNI. Referencing `OfflineRouter.Raw*` does not run `OfflineRouter`'s
 * static init (loadLibrary), so these run on plain JVM unit tests.
 */
class TransitDecodingSlice3Test {

    private val midnight = 1_700_000_000_000L
    private val clock = TransitClock(
        depSecs = 3600,
        weekday = 0,
        date = 20240101,
        prevWeekday = 6,
        prevDate = 20231231,
        midnightMillis = midnight,
    )

    private fun rawDeparture(
        routeName: String = "Muni 38",
        delaySecs: Int = 0,
        cancelled: Boolean = false,
        routeColor: Int = 0,
        routeType: Int = 3,
    ) = OfflineRouter.RawDeparture(
        routeName = routeName,
        headsign = "Downtown",
        feed = "sf",
        stopCode = "1234",
        routeColor = routeColor,
        routeType = routeType,
        depSecs = 3600,
        delaySecs = delaySecs,
        cancelled = cancelled,
        realTime = delaySecs != 0,
        tripId = 42L,
    )

    // --- TransitClock ---

    @Test
    fun `transitClock derives weekday date and midnight in the feed zone`() {
        // Monday 2024-01-01 09:30 in New York.
        val at = ZonedDateTime.of(2024, 1, 1, 9, 30, 0, 0, ZoneId.of("America/New_York"))
        val got = transitClock("America/New_York") { at }
        assertEquals(0, got.weekday)
        assertEquals(20240101, got.date)
        assertEquals(6, got.prevWeekday)
        assertEquals(20231231, got.prevDate)
        assertEquals(9 * 3600 + 30 * 60, got.depSecs)
        assertEquals(
            at.toLocalDate().atStartOfDay(ZoneId.of("America/New_York")).toInstant().toEpochMilli(),
            got.midnightMillis,
        )
    }

    @Test
    fun `unknown zone falls back to the device zone without crashing`() {
        val at = ZonedDateTime.of(2024, 6, 4, 12, 0, 0, 0, ZoneId.systemDefault())
        val got = transitClock("Not/AZone") { at }
        // 2024-06-04 is a Tuesday -> index weekday 1.
        assertEquals(1, got.weekday)
        assertEquals(20240604, got.date)
    }

    @Test
    fun `formatServiceTime renders wall clocks with day markers`() {
        assertEquals("", formatServiceTime(0))
        assertEquals("", formatServiceTime(-5))
        assertEquals("09:30", formatServiceTime(9 * 3600 + 30 * 60))
        assertEquals("00:00 +1", formatServiceTime(86400))
        assertEquals("01:00 +1", formatServiceTime(90000))
    }

    // --- TransitDepartures ---

    @Test
    fun `departureOf converts schedule seconds to absolute millis`() {
        val d = TransitDepartures.departureOf(rawDeparture(), clock)
        assertEquals("Muni 38", d.line)
        assertEquals("Downtown", d.headsign)
        assertEquals(midnight + 3_600_000L, d.scheduledMillis)
        assertEquals(midnight + 3_600_000L, d.realtimeMillis)
        assertEquals(0, d.delayMinutes)
        assertEquals(42L, d.tripVehicleId)
    }

    @Test
    fun `departureOf folds realtime delay into millis and minutes`() {
        val d = TransitDepartures.departureOf(rawDeparture(delaySecs = 150), clock)
        assertEquals(midnight + 3_600_000L + 150_000L, d.realtimeMillis)
        assertEquals(2, d.delayMinutes)
        assertTrue(d.realTime)
    }

    @Test
    fun `departureOf formats a nonzero route colour as hex`() {
        val d = TransitDepartures.departureOf(rawDeparture(routeColor = 0xFF0000), clock)
        assertEquals("FF0000", d.routeColor)
    }

    @Test
    fun `departureOf leaves absent colour null`() {
        assertEquals(null, TransitDepartures.departureOf(rawDeparture(), clock).routeColor)
    }

    @Test
    fun `departureOf carries the mode and cancellation`() {
        val d = TransitDepartures.departureOf(
            rawDeparture(routeType = 3, cancelled = true), clock,
        )
        assertEquals(GtfsRouteTypes.mode(3), d.mode)
        assertEquals(true, d.cancelled)
    }

    // --- TransitItineraries ---

    @Test
    fun `tripStop converts arrival and departure seconds`() {
        val s = TransitItineraries.tripStop(
            OfflineRouter.RawTripStop(
                name = "Central",
                lat = 1.0,
                lon = 2.0,
                arrSecs = 3600,
                depSecs = 3660,
                motisId = "m1",
                cancelled = false,
            ),
            clock,
        )
        assertEquals("Central", s.name)
        assertEquals(midnight + 3_600_000L, s.arrivesMillis)
        assertEquals(midnight + 3_660_000L, s.departsMillis)
        assertEquals("m1", s.motisId)
    }

    // --- TransitBoards ---

    private fun dep(
        line: String = "38",
        scheduled: Long = midnight + 3_600_000L,
        realtime: Long = midnight + 3_600_000L,
        cancelled: Boolean = false,
    ) = com.vayunmathur.maps.data.transit.Departure(
        line = line,
        headsign = "Downtown",
        scheduledMillis = scheduled,
        realtimeMillis = realtime,
        delayMinutes = 0,
        realTime = false,
        platform = null,
        mode = "BUS",
        routeColor = null,
        cancelled = cancelled,
    )

    @Test
    fun `empty boards build the empty overlay`() {
        val overlay = TransitBoards.buildOverlay(emptyList(), clock)
        assertTrue(overlay.isEmpty)
    }

    @Test
    fun `on-time departures are schedule-only and omitted`() {
        val overlay = TransitBoards.buildOverlay(
            listOf(GeoPoint(1.0, 2.0) to listOf(dep())),
            clock,
        )
        assertTrue(overlay.isEmpty)
    }

    @Test
    fun `delayed departures are flattened for JNI`() {
        val pos = GeoPoint(10.0, 20.0)
        val overlay = TransitBoards.buildOverlay(
            listOf(pos to listOf(dep(realtime = midnight + 3_600_000L + 120_000L))),
            clock,
        )
        assertEquals(listOf(20.0, 10.0), overlay.coords.toList())
        assertEquals(listOf("38"), overlay.routes.toList())
        // schedSecs since midnight, delaySecs, cancelled flag.
        assertEquals(listOf(3600, 120, 0), overlay.times.toList())
    }

    @Test
    fun `cancelled departures ride the overlay even without delay`() {
        val overlay = TransitBoards.buildOverlay(
            listOf(GeoPoint(10.0, 20.0) to listOf(dep(cancelled = true))),
            clock,
        )
        assertEquals(listOf(3600, 0, 1), overlay.times.toList())
    }

    @Test
    fun `blank lines are skipped`() {
        val overlay = TransitBoards.buildOverlay(
            listOf(GeoPoint(10.0, 20.0) to listOf(dep(line = "  "))),
            clock,
        )
        assertTrue(overlay.isEmpty)
    }

    // --- OfflineRouterTransit pure surface ---

    @Test
    fun `overlay empty companion reports empty`() {
        assertTrue(OfflineRouterTransit.Overlay.EMPTY.isEmpty)
        assertEquals(
            false,
            OfflineRouterTransit.Overlay(doubleArrayOf(1.0), arrayOf("x"), intArrayOf(1)).isEmpty,
        )
    }

    @Test
    fun `gtfsRouteTypeToMode delegates to the shared table`() {
        assertEquals(GtfsRouteTypes.mode(3), OfflineRouterTransit.gtfsRouteTypeToMode(3))
        assertEquals(GtfsRouteTypes.mode(109), OfflineRouterTransit.gtfsRouteTypeToMode(109))
    }

    @Test
    fun `onReload clears the feed cache without crashing`() {
        OfflineRouterTransit.onReload()
    }
}
