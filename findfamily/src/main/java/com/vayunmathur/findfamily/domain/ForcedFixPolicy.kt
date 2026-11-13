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
 * On top of that sits an unconditional ceiling (owner requirement, #895
 * follow-up): while the phone is sharing, an ACTIVE current-location request
 * (never a last-known read) goes out at least every [ACTIVE_CHECK_MAX_GAP_MS],
 * however fresh the held fix is and whatever the movement detector says. A held
 * fix kept fresh by PASSIVE or low-power network deliveries no longer counts as
 * a check.
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

    /**
     * The ceiling: the longest a sharing phone may go without an active
     * current-location request, in any power state, moving or still.
     */
    const val ACTIVE_CHECK_MAX_GAP_MS = 15 * 60_000L

    /**
     * When the ceiling check becomes due: one minute ahead of the ceiling, so
     * alarm delivery latency and the 30 s heartbeat granularity still land the
     * request inside [ACTIVE_CHECK_MAX_GAP_MS].
     */
    const val ACTIVE_CHECK_DUE_MS = ACTIVE_CHECK_MAX_GAP_MS - 60_000L

    /**
     * AlarmManager's inexact batching window: an inexact alarm set `d` ms ahead
     * may fire as late as `1.75 * d` after it was set (a 75 % window). The
     * inexact fallback arms early enough that even the end of that window is
     * inside the ceiling.
     */
    private const val INEXACT_WINDOW_FACTOR = 1.75

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
     * True when the last active current-location request is old enough for the
     * ceiling check. Every forced-fix attempt is such a request (it calls
     * getCurrentLocation), so [lastAttemptMs] is the same timestamp [isDue] backs
     * off on; passive and low-power deliveries never move it.
     */
    fun isCeilingDue(nowMs: Long, lastAttemptMs: Long?): Boolean =
        lastAttemptMs == null || nowMs - lastAttemptMs >= ACTIVE_CHECK_DUE_MS

    /**
     * Why a forced fix is due now, or null when none is.
     *
     * The ceiling wins over the held-fix staleness check when both apply, because
     * only the ceiling may use GPS in power-save (see [shouldFallBackToGps]).
     * [ceilingEnabled] is false while the phone shares with nobody: there is no
     * one to show the check to, so it is not worth the radio time.
     */
    fun dueReason(
        nowMs: Long,
        heldFixElapsedMs: Long?,
        lastAttemptMs: Long?,
        intervalMs: Long,
        ceilingEnabled: Boolean,
    ): ForcedFixReason? = when {
        ceilingEnabled && isCeilingDue(nowMs, lastAttemptMs) -> ForcedFixReason.ACTIVE_CHECK_CEILING
        isDue(nowMs, heldFixElapsedMs, lastAttemptMs, intervalMs) -> ForcedFixReason.STALE_HELD_FIX
        else -> null
    }

    /**
     * How far ahead to arm the Doze-proof ceiling alarm.
     *
     * Exact alarms fire on time, so they are set for the moment the check becomes
     * due. An inexact alarm may fire anywhere in its window, so it is set at
     * (time left before the ceiling) / [INEXACT_WINDOW_FACTOR]: even its latest
     * delivery is inside the ceiling. When it fires before the check is due the
     * caller just re-arms; the remaining time shrinks geometrically, so the chain
     * reaches a due check in a handful of cheap wake-ups.
     */
    fun ceilingAlarmDelayMs(nowMs: Long, lastAttemptMs: Long?, exact: Boolean): Long {
        if (lastAttemptMs == null) return 0L
        val untilDue = lastAttemptMs + ACTIVE_CHECK_DUE_MS - nowMs
        if (untilDue <= 0L) return 0L
        if (exact) return untilDue
        val untilCeiling = lastAttemptMs + ACTIVE_CHECK_MAX_GAP_MS - nowMs
        return (untilCeiling / INEXACT_WINDOW_FACTOR).toLong()
    }

    /**
     * Whether to follow the network attempt with GPS.
     *
     * GPS runs when there is no network provider at all, or when network gave
     * nothing usable for display (no fix, or coarser than the display gate).
     *
     * Power-save keeps GPS off for the held-fix staleness check (the moving path
     * still has it), but not for the [ceiling] check: the owner's requirement is a
     * current location at least every 15 minutes, and a power-save phone whose
     * network answer is unusable has no other way to produce one. The GPS attempt
     * is bounded by [GPS_TIMEOUT_MS], at most once per ceiling. A coarse network
     * fix still reaches the movement detector either way.
     */
    fun shouldFallBackToGps(
        hasGps: Boolean,
        hasNetwork: Boolean,
        networkAccuracyMeters: Double?,
        powerSave: Boolean,
        ceiling: Boolean = false,
    ): Boolean {
        if (!hasGps) return false
        if (!hasNetwork) return true
        if (powerSave && !ceiling) return false
        return networkAccuracyMeters == null || !NoShowPolicy.isAccurateEnough(networkAccuracyMeters)
    }
}

/** Why the service asks for a one-off current location. */
enum class ForcedFixReason {
    /** The held fix is older than the power-state interval ([ForcedFixPolicy.intervalMs]). */
    STALE_HELD_FIX,

    /** No active request for [ForcedFixPolicy.ACTIVE_CHECK_DUE_MS], however fresh the held fix. */
    ACTIVE_CHECK_CEILING,
}
