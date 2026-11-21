package com.vayunmathur.findfamily.data

import android.content.Context
import com.vayunmathur.library.util.HybridStampGenerator
import kotlin.time.Instant

/**
 * Hybrid `reportedAt` stamps for this device's own location fixes.
 *
 * Receivers drop a fix whose stamp is not strictly newer than the sender's
 * stored high-water (see `domain/IngestSequencing`). Minting that stamp from
 * the raw wall clock therefore lets a device lock *itself* out: when its
 * clock is corrected backwards, every fix it sends arrives "stale" against
 * its own earlier high-water at each receiver until wall time catches up.
 * Minting through the library's hybrid rule — `max(wallNow, last + 1)` via
 * [HybridStampGenerator] — means stamps from this device never go backwards,
 * so the correction is invisible to receivers.
 *
 * One generator for the whole device (not per recipient): every peer tracks
 * the same sender, so one monotonic sequence is what they must all see. The
 * high-water is persisted per device under [KEY] in the device-protected
 * store ([DirectBootStore.store]) — not the credential-encrypted store —
 * because the pre-unlock publish path mints too, and a high-water it could
 * not read would restart the sequence below stamps already sent. Seeded on
 * start ([seedFromStore], from the tracking service) and lazily on first
 * mint; written back on every mint ([com.vayunmathur.library.util.DataStoreUtils.setLongIfGreater]),
 * so monotonicity survives process death and reboots.
 *
 * Only `reportedAt` is minted here. A fix's measured time (`timestamp`) is
 * the GPS fix's own time and is never rewritten: sequencing compares
 * `reportedAt` first, and parting reports deliberately carry a stale
 * measurement under a fresh send stamp.
 */
object OwnFixStamps {

    /** Device-protected DataStore key for the persisted own-fix high-water (epoch ms). */
    const val KEY = "ff_own_fix_reported_stamp"

    private var generator = HybridStampGenerator()

    @Volatile
    private var seeded = false

    /** The highest stamp minted so far in this process. */
    val last: Long
        get() = generator.last

    /** Seeds the in-process generator from the persisted high-water mark. */
    suspend fun seedFromStore(context: Context) {
        if (seeded) return
        seeded = true
        val persisted = DirectBootStore.store(context).getLongAwait(KEY) ?: 0L
        generator.observe(persisted)
    }

    /**
     * Mints the next own-fix `reportedAt`: strictly greater than every stamp
     * this device has minted before, even if the wall clock jumped backwards.
     * Persists the new high-water before returning.
     */
    suspend fun next(context: Context): Instant {
        seedFromStore(context)
        val stamp = generator.next()
        DirectBootStore.store(context).setLongIfGreater(KEY, stamp)
        return Instant.fromEpochMilliseconds(stamp)
    }

    /** Resets the in-process generator (tests only; the persisted mark is untouched). */
    internal fun resetForTesting(wallNow: () -> Long = { System.currentTimeMillis() }) {
        generator = HybridStampGenerator(wallNow = wallNow)
        seeded = false
    }
}
