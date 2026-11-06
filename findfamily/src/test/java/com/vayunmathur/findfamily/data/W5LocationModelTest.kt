package com.vayunmathur.findfamily.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

/**
 * Pure-JVM tests for the location-model conversions: [LocationValue.toCompatible]
 * and [LocationValueCompatible.toLocationValue].
 *
 * These pin the wire-compat rules (id is never reused across devices; absent
 * fields mean an older peer) alongside the model mapping, because the mapping
 * is where those rules live.
 */
class W5LocationModelTest {

    private fun live(
        userid: Long = 7L,
        id: Long = 3L,
    ) = LocationValue(
        userid = userid,
        coord = Coord(1.5, 2.5),
        speed = 1.2f,
        acc = 5f,
        timestamp = Instant.fromEpochMilliseconds(1_000_000L),
        battery = 80f,
        id = id,
    )

    @Test
    fun toCompatibleCarriesFieldsByName() {
        val c = live().toCompatible(senderPlatform = "android", pairId = "pair-1")
        assertEquals(7uL, c.userid)
        assertEquals(Coord(1.5, 2.5), c.coord)
        assertEquals(1_000_000L, c.timestamp)
        assertEquals(1_000_000L, c.reportedAt)
        assertEquals("android", c.senderPlatform)
        assertEquals("pair-1", c.pairId)
        assertEquals("LIVE", c.source)
        assertEquals(3uL, c.id)
    }

    @Test
    fun toCompatibleDefaultsToLiveWhenUnset() {
        assertEquals("LIVE", live().toCompatible().source)
        assertNull(live().toCompatible().pairId)
    }

    @Test
    fun shutdownSourceSurvivesRoundTrip() {
        val back = live().copy(source = LocationSource.SHUTDOWN).toCompatible().toLocationValue()
        assertEquals(LocationSource.SHUTDOWN, back.source)
    }

    @Test
    fun receivedFixNeverReusesSenderRowId() {
        // The sender's autogenerate PK must not become ours, or peers overwrite each other.
        val incoming = live(userid = 9L, id = 1L).toCompatible()
        assertEquals(0L, incoming.toLocationValue().id)
    }

    @Test
    fun reportedAtFallsBackToTimestampForOldPeers() {
        val compat = live().toCompatible().copy(reportedAt = null)
        val back = compat.toLocationValue()
        assertEquals(back.timestamp, back.reportedAt)
    }

    @Test
    fun staleShutdownFixKeepsBothTimes() {
        val sent = live().copy(
            source = LocationSource.SHUTDOWN,
            timestamp = Instant.fromEpochMilliseconds(900_000L),
            reportedAt = Instant.fromEpochMilliseconds(1_000_000L),
        ).toCompatible().toLocationValue()
        assertTrue(sent.reportedAt > sent.timestamp)
    }

    @Test
    fun temporaryLinkIdsArePositive() {
        repeat(50) { assertTrue(newTemporaryLinkId() > 0L) }
    }

    @Test
    fun noShowAlertDefaultsAreOneShotUnfired() {
        val alert = NoShowAlert(
            watchedUserId = 1L,
            waypointId = null,
            expectedAt = Instant.fromEpochSeconds(1_786_570_000L),
            grace = 15.minutes,
        )
        assertEquals(false, alert.fired)
        assertEquals(true, alert.oneShot)
        assertEquals(0L, alert.id)
    }

    @Test
    fun waypointNewWaypointSentinel() {
        assertEquals("", Waypoint.NEW_WAYPOINT.name)
        assertEquals(100.0, Waypoint.NEW_WAYPOINT.range)
        assertEquals(Coord(0.0, 0.0), Waypoint.NEW_WAYPOINT.coord)
    }

    @Test
    fun userEmptySentinel() {
        assertEquals(RequestStatus.MUTUAL_CONNECTION, User.EMPTY.requestStatus)
        assertEquals(UserKind.PERSON, User.EMPTY.kind)
    }
}
