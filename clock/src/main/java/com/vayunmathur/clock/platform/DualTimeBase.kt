package com.vayunmathur.clock.platform

import android.os.SystemClock

/**
 * Dual wall-clock / monotonic anchors for stopwatch and countdown timers.
 *
 * A running interval is persisted with BOTH anchors taken at the same
 * moment: the wall-clock instant (`Clock.System.now()`, epoch millis) and
 * the monotonic instant ([SystemClock.elapsedRealtime], millis since boot
 * including deep sleep). Elapsed time is then measured on the monotonic
 * base whenever that anchor is still usable, so a timezone change, an NTP
 * correction or a manual wall-clock jump cannot stretch or shrink the
 * interval. The wall anchor is the fallback for the one case the monotonic
 * base cannot survive: a reboot resets `elapsedRealtime` to (near) zero,
 * detected as `nowMono < storedMono`; measuring on the wall base then
 * includes the powered-off span, which is the sane reading for both a
 * stopwatch (it kept "running" while off) and a timer (it may have expired
 * while off, and its caller clamps/completes it).
 *
 * All functions are pure over explicit millisecond values so host tests can
 * simulate wall jumps and reboots without a device; only [elapsedRealtimeMs]
 * touches Android.
 */
object DualTimeBase {

    /** Current monotonic time: millis since boot, including deep sleep. */
    fun elapsedRealtimeMs(): Long = SystemClock.elapsedRealtime()

    /** Whether a stored monotonic anchor can still measure against [nowMonoMs]. */
    fun isMonotonicUsable(storedMonoMs: Long?, nowMonoMs: Long?): Boolean =
        storedMonoMs != null && nowMonoMs != null && nowMonoMs >= storedMonoMs

    /**
     * Elapsed millis between the dual anchors and now. Monotonic when
     * usable, else the wall delta clamped at zero (a backwards wall jump
     * with no usable monotonic anchor must not rewind a display).
     */
    fun elapsedMs(
        wallStartMs: Long,
        monoStartMs: Long?,
        wallNowMs: Long,
        monoNowMs: Long?,
    ): Long = if (isMonotonicUsable(monoStartMs, monoNowMs)) {
        monoNowMs!! - monoStartMs!!
    } else {
        (wallNowMs - wallStartMs).coerceAtLeast(0L)
    }

    /** Total stopwatch elapsed: banked [totalMs] plus the live dual-base delta. */
    fun stopwatchElapsedMs(
        totalMs: Long,
        wallStartMs: Long,
        monoStartMs: Long?,
        wallNowMs: Long,
        monoNowMs: Long?,
    ): Long = totalMs + elapsedMs(wallStartMs, monoStartMs, wallNowMs, monoNowMs)

    /** Countdown remaining: [remainingMs] minus the live dual-base delta, clamped at zero. */
    fun timerRemainingMs(
        remainingMs: Long,
        wallStartMs: Long,
        monoStartMs: Long?,
        wallNowMs: Long,
        monoNowMs: Long?,
    ): Long = (remainingMs - elapsedMs(wallStartMs, monoStartMs, wallNowMs, monoNowMs))
        .coerceAtLeast(0L)

    /**
     * Monotonic trigger instant (on the `elapsedRealtime` base, for
     * `AlarmManager.ELAPSED_REALTIME_WAKEUP`) for a countdown with
     * [remainingMs] left, or null when the monotonic base is unusable and
     * the caller must fall back to a wall-clock (`RTC_WAKEUP`) trigger.
     */
    fun timerTriggerElapsedMs(nowMonoMs: Long?, remainingMs: Long): Long? =
        nowMonoMs?.let { it + remainingMs.coerceAtLeast(0L) }
}
