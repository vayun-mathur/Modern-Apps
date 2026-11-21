package com.vayunmathur.findfamily.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/**
 * Pure-JVM tests for [PairAuth] (GHSA-x94x-v6f7-wwpp). No Android, no database —
 * every case feeds strings and instants straight into the policy.
 */
class PairAuthTest {

    private val now = Instant.fromEpochSeconds(1_786_570_000L)

    // --- generatePairId ---

    @Test
    fun generatedIdsAreUniqueAndUrlSafe() {
        val a = PairAuth.generatePairId()
        val b = PairAuth.generatePairId()
        assertNotEquals(a, b)
        for (id in listOf(a, b)) {
            assertTrue(id.length in 20..24, "unexpected length: $id")
            assertTrue(id.all { it.isLetterOrDigit() || it == '-' || it == '_' }, "not url-safe: $id")
        }
    }

    // --- checkInbound ---

    @Test
    fun unpinnedAcceptsFirstIdForPinning() {
        assertEquals(
            PairAuth.InboundVerdict.ACCEPT_PIN,
            PairAuth.checkInbound(null, "some-id"),
        )
    }

    @Test
    fun unpinnedWithoutIdIsLegacyAccept() {
        assertEquals(
            PairAuth.InboundVerdict.ACCEPT_LEGACY,
            PairAuth.checkInbound(null, null),
        )
    }

    @Test
    fun pinnedMatchingAccepts() {
        assertEquals(
            PairAuth.InboundVerdict.ACCEPT_MATCH,
            PairAuth.checkInbound("abc", "abc"),
        )
    }

    @Test
    fun pinnedMismatchDrops() {
        assertEquals(
            PairAuth.InboundVerdict.DROP_MISMATCH,
            PairAuth.checkInbound("abc", "attacker-guess"),
        )
    }

    @Test
    fun pinnedMissingDrops() {
        assertEquals(
            PairAuth.InboundVerdict.DROP_MISSING,
            PairAuth.checkInbound("abc", null),
        )
    }

    // --- isFromTheFuture ---

    @Test
    fun timelyFixIsNotFuture() {
        assertFalse(PairAuth.isFromTheFuture(now, now, now))
        assertFalse(PairAuth.isFromTheFuture(now - 1.seconds, now, now))
        assertFalse(PairAuth.isFromTheFuture(now, now - 1.seconds, now))
    }

    @Test
    fun smallSkewIsTolerated() {
        assertFalse(PairAuth.isFromTheFuture(now + 5.minutes, now, now))
        assertFalse(PairAuth.isFromTheFuture(now, now + 5.minutes, now))
    }

    @Test
    fun farFutureReportedAtIsRejected() {
        assertTrue(PairAuth.isFromTheFuture(now + 5.minutes + 1.seconds, now, now))
    }

    @Test
    fun farFutureTimestampIsRejected() {
        assertTrue(PairAuth.isFromTheFuture(now, now + 5.minutes + 1.seconds, now))
    }

    @Test
    fun farPastIsNotFuture() {
        // Stale is a different problem (handled by getLatest ranking); the clamp only kills pins.
        assertFalse(PairAuth.isFromTheFuture(now - 30.days, now, now))
    }

    // --- clampReportedAt ---

    @Test
    fun clampCapsFutureReportedAtAtNowPlusSlack() {
        assertEquals(now + 5.minutes, PairAuth.clampReportedAt(now + 2.days, now))
        assertEquals(now + 5.minutes, PairAuth.clampReportedAt(now + 5.minutes, now))
    }

    @Test
    fun clampLeavesInWindowAndPastStampsUntouched() {
        assertEquals(now, PairAuth.clampReportedAt(now, now))
        assertEquals(now + 1.minutes, PairAuth.clampReportedAt(now + 1.minutes, now))
        assertEquals(now - 30.days, PairAuth.clampReportedAt(now - 30.days, now))
    }

    // --- logPreview ---

    @Test
    fun logPreviewNeverLeaksFullValue() {
        val id = PairAuth.generatePairId()
        val preview = PairAuth.logPreview(id)
        assertFalse(preview.contains(id))
        assertTrue(preview.length < id.length)
        assertEquals("absent", PairAuth.logPreview(null))
    }
}
