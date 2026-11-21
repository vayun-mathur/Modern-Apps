package com.vayunmathur.findfamily.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
/**
 * Pure-JVM tests for [IngestSequencing] (per-sender monotonic acceptance).
 * No Android, no database — every case feeds stamps straight into the policy.
 */
class IngestSequencingTest {

    private val t0 = 1_786_570_000L
    private val t1 = 1_786_570_060L

    private fun stamp(
        reportedAtSec: Long,
        measuredAtSec: Long = reportedAtSec,
        hash: Int = 0,
    ) = IngestSequencing.FixStamp(reportedAtSec, measuredAtSec, hash)

    private fun hash(lat: Double) = IngestSequencing.payloadHash(
        lat = lat, lon = -0.12, speed = 0f, acc = 5f, battery = 90f, sourceName = "LIVE",
    )

    @Test
    fun firstFixFromASenderIsAccepted() {
        assertTrue(IngestSequencing.accepts(stamp(t0), stored = null))
    }

    @Test
    fun newerReportedAtWinsOlderIsDropped() {
        assertTrue(IngestSequencing.accepts(stamp(t1), stamp(t0)))
        assertFalse(IngestSequencing.accepts(stamp(t0), stamp(t1)))
    }

    @Test
    fun duplicateStampIsNotNewer() {
        // A redelivered fix reproduces its stamp exactly: accepting it again
        // would add a second history row and re-fire enrichment.
        assertFalse(IngestSequencing.accepts(stamp(t0, hash = 7), stamp(t0, hash = 7)))
    }

    @Test
    fun equalReportedAtFallsToMeasuredAtThenHash() {
        assertTrue(IngestSequencing.accepts(stamp(t0, measuredAtSec = t1), stamp(t0, measuredAtSec = t0)))
        assertTrue(IngestSequencing.accepts(stamp(t0, hash = 9), stamp(t0, hash = 3)))
        assertFalse(IngestSequencing.accepts(stamp(t0, hash = 3), stamp(t0, hash = 9)))
    }

    @Test
    fun hashTiebreakIsDeterministicInBothArrivalOrders() {
        // Two distinct fixes sharing a millisecond: whichever the payload
        // hash ranks higher must win regardless of the order they are folded.
        val a = stamp(t0, hash = hash(51.5))
        val b = stamp(t0, hash = hash(52.5))
        assertTrue(a.payloadHash != b.payloadHash)
        val winner = maxOf(a, b)
        // Fold in both orders against a running high-water, as ingest does.
        fun fold(first: IngestSequencing.FixStamp, second: IngestSequencing.FixStamp) =
            listOf(first, second).fold<IngestSequencing.FixStamp, IngestSequencing.FixStamp?>(null) { high, fix ->
                if (IngestSequencing.accepts(fix, high)) fix else high
            }
        assertEquals(winner, fold(a, b))
        assertEquals(winner, fold(b, a))
    }

    @Test
    fun payloadHashDependsOnlyOnPayload() {
        assertEquals(hash(51.5), hash(51.5))
        assertTrue(hash(51.5) != hash(51.6))
        assertTrue(
            IngestSequencing.payloadHash(51.5, -0.12, 0f, 5f, 90f, "LIVE") !=
                IngestSequencing.payloadHash(51.5, -0.12, 0f, 5f, 90f, "SHUTDOWN")
        )
    }
}
