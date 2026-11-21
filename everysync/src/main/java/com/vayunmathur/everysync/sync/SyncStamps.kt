package com.vayunmathur.everysync.sync

import android.content.Context
import com.vayunmathur.library.util.DataStoreUtils
import com.vayunmathur.library.util.HybridStampGenerator

/**
 * EverySync's hybrid stamp source for the stamps it mints itself.
 *
 * The `updatedAt` values that order [RelayMerge] mostly originate in the
 * sink apps (todo/notes mint hybrid stamps in their own stores), but
 * EverySync also mints stamps: the merge `now` (relay tombstones for
 * hard-delete stores are stamped `max(remote, now)`) and blob envelopes
 * ([RelayBlobStore], LWW by `updatedAt`). Minting those from the raw wall
 * clock would reintroduce clock-skew wins/regressions at this layer, so
 * they go through one [HybridStampGenerator]:
 *
 *  - [observe] every remote stamp a merge fold sees, so the next local
 *    stamp is past it;
 *  - [next] is `max(wall, last + 1)`, so a backwards clock jump never
 *    regresses a stamp this process mints.
 *
 * The generator's high-water mark is persisted in the shared DataStore
 * under [KEY] (seeded in [seedFromStore], written back by [persist]) so
 * monotonicity survives process death. In-process callers that cannot
 * suspend (blob envelope encoding) use [next]/[observe] directly; the
 * persisted seed is loaded once per process by the merge driver, which
 * also persists after each cycle.
 */
object SyncStamps {
    /** DataStore key for the persisted hybrid high-water mark. */
    const val KEY = "everysync_hybrid_stamp"

    private val generator = HybridStampGenerator()
    @Volatile private var seeded = false

    /** Mints the next hybrid stamp (see [HybridStampGenerator.next]). */
    fun next(): Long = generator.next()

    /** Advances the local counter past a remote stamp (hybrid receive rule). */
    fun observe(remote: Long) {
        generator.observe(remote)
    }

    /** The highest stamp minted or observed so far in this process. */
    val last: Long get() = generator.last

    /** Seeds the in-process generator from the persisted high-water mark. */
    suspend fun seedFromStore(context: Context) {
        if (seeded) return
        seeded = true
        val persisted = DataStoreUtils.getInstance(context).getLongAwait(KEY) ?: 0L
        generator.observe(persisted)
    }

    /** Persists the current high-water mark if it advanced past the stored one. */
    suspend fun persist(context: Context) {
        DataStoreUtils.getInstance(context).setLongIfGreater(KEY, generator.last)
    }
}
