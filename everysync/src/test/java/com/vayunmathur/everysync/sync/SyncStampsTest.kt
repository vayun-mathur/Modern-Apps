package com.vayunmathur.everysync.sync

import com.vayunmathur.everysync.data.RemotePassword
import com.vayunmathur.everysync.sync.adapters.PasswordKind
import com.vayunmathur.library.util.HybridStampGenerator
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Hybrid stamps at the EverySync layer: the merge `now` minted by the
 * driver (and blob/password factory stamps via [SyncStamps]) must stay
 * monotonic when the wall clock jumps backwards, and observing a future
 * remote stamp must advance subsequent local stamps past it.
 */
class SyncStampsTest {

    @Test
    fun stampsIncreaseWhenWallClockJumpsBackwards() {
        var wall = 1_000_000L
        val gen = HybridStampGenerator(initialLast = 0L, wallNow = { wall })
        val first = gen.next()
        wall = 1L // wall clock jumps far backwards
        val second = gen.next()
        val third = gen.next()
        assertTrue(second > first, "stamp regressed after backwards clock: $first -> $second")
        assertTrue(third > second, "stamps must keep increasing: $second -> $third")
    }

    @Test
    fun futureRemoteStampAdvancesSubsequentLocalStamps() {
        var wall = 1_000L
        val gen = HybridStampGenerator(initialLast = 0L, wallNow = { wall })
        gen.next()
        gen.observe(remote = 999_999L)
        assertTrue(gen.next() > 999_999L, "local stamp did not advance past remote")
    }

    @Test
    fun mergeTombstoneStampIsPastFutureRemoteAfterObserve() {
        // Passwords hard-delete: a baseline-present record absent locally
        // propagates a tombstone stamped max(remote, now). With `now` from
        // a generator that observed the remote stamp first, the tombstone
        // sorts strictly after the future remote record it replaces.
        val future = 999_999L
        var wall = 1_000L
        val gen = HybridStampGenerator(initialLast = 0L, wallNow = { wall })
        val remote = RemotePassword(uid = "p1", name = "Bank", updatedAt = future)
        gen.observe(remote.updatedAt)
        val now = gen.next()
        val baseline = mapOf(
            remote.uid to RelaySnapshot(
                remote.uid, PasswordKind.kind, PasswordKind.contentHash(remote),
                remote.updatedAt, remote.updatedAt, now,
            ),
        )
        val plan = RelayMerge.merge(PasswordKind, emptyList(), listOf(remote), baseline, now)
        assertEquals(1, plan.remoteOut.size)
        assertTrue(plan.remoteOut[0].deleted)
        assertTrue(
            plan.remoteOut[0].updatedAt > future,
            "tombstone stamp ${plan.remoteOut[0].updatedAt} not past remote $future",
        )
    }

    @Test
    fun syncStampsObjectIsMonotonicAcrossCalls() {
        val a = SyncStamps.next()
        SyncStamps.observe(a + 10_000L)
        val b = SyncStamps.next()
        assertTrue(b > a + 10_000L, "SyncStamps did not advance past observed stamp: $a -> $b")
    }
}
