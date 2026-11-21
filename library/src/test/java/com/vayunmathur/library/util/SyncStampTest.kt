package com.vayunmathur.library.util

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Pure hybrid-stamp rules: monotonic per store, advanced by remote stamps. */
class SyncStampTest {

    @Test
    fun nextFollowsWallWhenWallIsAhead() {
        assertEquals(1_000L, SyncStamp.next(last = 10L, wallNow = 1_000L))
    }

    @Test
    fun nextStillIncreasesWhenWallJumpsBackwards() {
        var wall = 1_000L
        val gen = HybridStampGenerator(initialLast = 0L, wallNow = { wall })
        val first = gen.next()
        wall = 10L // clock jumps backwards
        val second = gen.next()
        val third = gen.next()
        assertEquals(1_000L, first)
        assertTrue(second > first, "stamp regressed after backwards clock: $first -> $second")
        assertTrue(third > second, "stamps must keep increasing: $second -> $third")
    }

    @Test
    fun observeFutureRemoteAdvancesSubsequentLocalStamps() {
        var wall = 1_000L
        val gen = HybridStampGenerator(initialLast = 0L, wallNow = { wall })
        gen.next()
        gen.observe(remote = 999_999L)
        val after = gen.next()
        assertTrue(after > 999_999L, "local stamp $after not past observed remote 999999")
        // An older remote stamp must not pull the counter back.
        gen.observe(remote = 5L)
        assertTrue(gen.next() > after)
    }

    @Test
    fun nextSaturatesAtMaxInsteadOfOverflowing() {
        assertEquals(Long.MAX_VALUE, SyncStamp.next(last = Long.MAX_VALUE, wallNow = 1L))
        assertEquals(Long.MAX_VALUE, SyncStamp.observe(last = Long.MAX_VALUE, remote = 1L))
    }
}
