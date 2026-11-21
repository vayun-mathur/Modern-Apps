package com.vayunmathur.findfamily.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Instant

/**
 * Pure-JVM tests for how a received payload's `source` and `reportedAt` are decoded.
 *
 * These exist because the decode rules are safety-relevant rather than merely correct, and
 * because the original version of this logic shipped a bug that no compiler or lint check
 * could have caught: unrecognised sources fell back to [LocationSource.LIVE], which would have
 * drawn a [LocationSource.NETWORK_SIGHTING]'s coordinate — someone *else's* position — as a
 * precise current fix of the person being looked for.
 *
 * The two directions are deliberately asymmetric and both are pinned here, because collapsing
 * them back into one rule is the obvious "simplification" that would reintroduce the bug.
 */
class LocationSourceDecodeTest {

    private fun payload(source: String?, reportedAt: Long? = null) = LocationValueCompatible(
        userid = 42uL,
        coord = Coord(1.0, 2.0),
        speed = 0f,
        acc = 5f,
        timestamp = 1_000_000L,
        battery = 50f,
        source = source,
        reportedAt = reportedAt,
    )

    @Test
    fun absentSourceMeansAnOlderPeerAndDecodesToLive() {
        // Backwards-permissive: a peer predating the field only ever sent live reports, so
        // treating its fixes as LIVE is correct rather than merely convenient. If this ever
        // starts returning UNKNOWN, every pre-field peer goes dark.
        assertEquals(LocationSource.LIVE, payload(source = null).toLocationValue().source)
    }

    @Test
    fun unrecognisedSourceFailsClosedRatherThanGuessingLive() {
        // Forwards-conservative: a value we do not know came from a NEWER peer, so we cannot
        // know whether its coordinate even belongs to the person it is attached to.
        assertEquals(
            LocationSource.UNKNOWN,
            payload(source = "SOME_FUTURE_CATEGORY").toLocationValue().source,
        )
    }

    @Test
    fun knownSourcesRoundTripByName() {
        for (expected in LocationSource.entries) {
            assertEquals(expected, payload(source = expected.name).toLocationValue().source)
        }
    }

    @Test
    fun networkSightingIsNeverSilentlyDowngraded() {
        // The specific case the fail-closed rule exists for: this coordinate is the finder's,
        // not the tracked person's, so decoding it as LIVE would send a searcher to the wrong
        // place while looking authoritative.
        assertEquals(
            LocationSource.NETWORK_SIGHTING,
            payload(source = "NETWORK_SIGHTING").toLocationValue().source,
        )
    }

    @Test
    fun absentReportedAtFallsBackToTheMeasurementTime() {
        val decoded = payload(source = null).toLocationValue()
        assertEquals(decoded.timestamp, decoded.reportedAt)
    }

    @Test
    fun reportedAtSurvivesAndStaysDistinctFromTheMeasurementTime() {
        // A parting report is the whole reason these are two fields: the fix is older than the
        // moment it was sent, and flattening them is what made shutdown reports look current.
        val decoded = payload(source = "SHUTDOWN", reportedAt = 1_300_000L).toLocationValue()
        assertEquals(Instant.fromEpochMilliseconds(1_000_000L), decoded.timestamp)
        assertEquals(Instant.fromEpochMilliseconds(1_300_000L), decoded.reportedAt)
    }

    @Test
    fun futureStampedReportIsClampedToReceiverNowPlusSlackAtIngest() {
        // A peer with a fast clock stamps reportedAt 2h ahead. Without the
        // ingest clamp that row would win MAX(reportedAt) until real time
        // caught up — effectively forever. Clamped, it ranks as "just sent"
        // (receiver-now + 5 min slack) and the next on-time report supersedes it.
        val receiverNow = Instant.fromEpochMilliseconds(2_000_000_000L)
        val futureMs = receiverNow.toEpochMilliseconds() + 2 * 60 * 60 * 1000L
        val decoded = payload(source = "LIVE", reportedAt = futureMs)
            .toLocationValue(receiverNow = receiverNow)
        assertEquals(
            Instant.fromEpochMilliseconds(receiverNow.toEpochMilliseconds() + 5 * 60 * 1000L),
            decoded.reportedAt,
        )
        // The measurement time is not rewritten — only the ranking stamp is.
        assertEquals(Instant.fromEpochMilliseconds(1_000_000L), decoded.timestamp)
    }

    @Test
    fun inWindowAndPastReportsKeepTheirStampAtIngest() {
        val receiverNow = Instant.fromEpochMilliseconds(2_000_000_000L)
        val inWindowMs = receiverNow.toEpochMilliseconds() + 60_000L
        assertEquals(
            Instant.fromEpochMilliseconds(inWindowMs),
            payload(source = "LIVE", reportedAt = inWindowMs)
                .toLocationValue(receiverNow = receiverNow).reportedAt,
        )
        val pastMs = receiverNow.toEpochMilliseconds() - 60_000L
        assertEquals(
            Instant.fromEpochMilliseconds(pastMs),
            payload(source = "LIVE", reportedAt = pastMs)
                .toLocationValue(receiverNow = receiverNow).reportedAt,
        )
    }

    @Test
    fun encodeThenDecodePreservesSourceAndBothTimes() {
        val original = LocationValue(
            userid = 7L,
            coord = Coord(51.5, -0.1),
            speed = 0f,
            acc = 12f,
            timestamp = Instant.fromEpochMilliseconds(1_000_000L),
            battery = 30f,
            reportedAt = Instant.fromEpochMilliseconds(1_300_000L),
            source = LocationSource.BATTERY_LOW,
        )
        val decoded = original.toCompatible().toLocationValue()
        assertEquals(original.source, decoded.source)
        assertEquals(original.timestamp, decoded.timestamp)
        assertEquals(original.reportedAt, decoded.reportedAt)
    }
}
