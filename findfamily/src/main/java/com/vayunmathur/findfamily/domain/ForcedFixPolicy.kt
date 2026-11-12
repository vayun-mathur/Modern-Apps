package com.vayunmathur.findfamily.domain

/**
 * When the sending phone should ask for a one-off current location even
 * though nothing says it is moving (GitHub #895).
 *
 * Before #895 a "still" phone requested no location at all and the heartbeat
 * kept re-publishing the last held fix, so a missed departure froze the pin
 * for hours. Now the held fix may only get [intervalMs] old before the
 * service asks for a current location: network first (cheap), GPS only when
 * network cannot produce a fix fine enough to display.
 *
 * Pure: monotonic millisecond inputs only, no Android types.
 */
object ForcedFixPolicy {

    /** Held-fix age that triggers a forced fix while charging: power is not the constraint. */
    const val INTERVAL_CHARGING_MS = 5 * 60_000L

    /** Held-fix age that triggers a forced fix on battery. */
    const val INTERVAL_DEFAULT_MS = 10 * 60_000L

    /** Held-fix age that triggers a forced fix in power-save mode. */
    const val INTERVAL_POWER_SAVE_MS = 15 * 60_000L

    /** How long a forced network fix may take before giving up. */
    const val NETWORK_TIMEOUT_MS = 20_000L

    /** How long a forced GPS fix may take before giving up (bounds the GPS-on time). */
    const val GPS_TIMEOUT_MS = 30_000L

    /** The forced-fix interval for the current power state. Charging wins over power-save. */
    fun intervalMs(charging: Boolean, powerSave: Boolean): Long = when {
        charging -> INTERVAL_CHARGING_MS
        powerSave -> INTERVAL_POWER_SAVE_MS
        else -> INTERVAL_DEFAULT_MS
    }

    /**
     * True when the held fix (or the lack of one) is stale enough to force a
     * fix, and the previous attempt was at least one interval ago — a phone
     * in a basement must not retry GPS every heartbeat tick.
     */
    fun isDue(nowMs: Long, heldFixElapsedMs: Long?, lastAttemptMs: Long?, intervalMs: Long): Boolean {
        val heldStale = heldFixElapsedMs == null || nowMs - heldFixElapsedMs >= intervalMs
        val attemptGapOk = lastAttemptMs == null || nowMs - lastAttemptMs >= intervalMs
        return heldStale && attemptGapOk
    }

    /**
     * Whether to follow the network attempt with GPS.
     *
     * GPS runs when there is no network provider at all, or when network gave
     * nothing usable for display (no fix, or coarser than the display gate) —
     * except in power-save, where the existing policy keeps GPS to the moving
     * path only. A coarse network fix still reaches the movement detector
     * either way.
     */
    fun shouldFallBackToGps(
        hasGps: Boolean,
        hasNetwork: Boolean,
        networkAccuracyMeters: Double?,
        powerSave: Boolean,
    ): Boolean {
        if (!hasGps) return false
        if (!hasNetwork) return true
        if (powerSave) return false
        return networkAccuracyMeters == null || !NoShowPolicy.isAccurateEnough(networkAccuracyMeters)
    }
}
