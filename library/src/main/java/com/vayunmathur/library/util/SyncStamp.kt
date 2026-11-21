package com.vayunmathur.library.util

/**
 * Hybrid logical stamps for last-writer-wins sync ordering.
 *
 * Plain wall-clock stamps (`System.currentTimeMillis()`) break under clock
 * skew: a device whose clock runs fast mints stamps ahead of every other
 * device and then wins every conflict forever, and a device whose clock
 * jumps backwards can mint a stamp older than its own previous edit, so
 * that edit silently loses.
 *
 * A hybrid stamp stays in the same epoch-ms [Long] space as the wall stamps
 * it replaces (no schema or wire change: `updatedAt` columns and relay
 * envelopes keep carrying a Long), but is monotonic per store:
 *
 *  - local mint: `next = max(wallNow, lastStamp + 1)` — even if the wall
 *    clock jumps backwards, stamps from one store never go backwards;
 *  - on receiving a remote stamp: `lastStamp = max(lastStamp, remote)` —
 *    the next local stamp is then strictly past anything this store has
 *    seen, which is the hybrid-logical-clock receive rule.
 *
 * Cross-device ordering is still wall-based (two devices with skewed clocks
 * order by their stamps as before); this only removes the pathological
 * cases: same-device regression, and a fast device permanently winning
 * against a device that has actually seen (and advanced past) its stamps.
 *
 * [lastStamp] must be persisted per store by the caller (the highest stamp
 * the store has minted or observed — e.g. `MAX(updatedAt)` over its rows,
 * or a persisted high-water key) so monotonicity survives process death.
 * Overflow saturates at [Long.MAX_VALUE] instead of wrapping negative.
 */
object SyncStamp {
    /** The next local stamp given the persisted [last] stamp and a wall reading. */
    fun next(last: Long, wallNow: Long): Long {
        if (last == Long.MAX_VALUE) return Long.MAX_VALUE
        return maxOf(wallNow, last + 1)
    }

    /** The persisted stamp after observing a [remote] stamp. */
    fun observe(last: Long, remote: Long): Long = maxOf(last, remote)
}

/**
 * Stateful [SyncStamp] generator for one store.
 *
 * Holds the store's last stamp in memory; the caller seeds [initialLast]
 * from (and persists [last] back to) the store's durable high-water mark.
 * [next] and [observe] are synchronized, so one generator may be shared by
 * every writer of a store. [wallNow] is injectable for tests.
 */
class HybridStampGenerator(
    initialLast: Long = 0L,
    private val wallNow: () -> Long = { System.currentTimeMillis() },
) {
    private var lastStamp: Long = initialLast

    /** The highest stamp minted or observed so far; persist this per store. */
    val last: Long
        @Synchronized get() = lastStamp

    /** Mints a stamp strictly greater than every stamp minted/observed before. */
    @Synchronized
    fun next(): Long {
        lastStamp = SyncStamp.next(lastStamp, wallNow())
        return lastStamp
    }

    /**
     * Advances the local counter past a [remote] stamp received from a peer,
     * so subsequent [next] stamps sort after it. Returns the new [last].
     */
    @Synchronized
    fun observe(remote: Long): Long {
        lastStamp = SyncStamp.observe(lastStamp, remote)
        return lastStamp
    }
}
