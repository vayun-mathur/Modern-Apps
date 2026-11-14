package com.vayunmathur.sdk.controller

/** Length of the rolling rate-limit window, in milliseconds. */
private const val RATE_WINDOW_MS = 1_000L

/**
 * The per-player inbound policy: replay window plus rate limit.
 *
 * Extracted from `ControllerHost` so the whole rule is a JVM test: the host owns one gate per
 * player and publishes only what the gate accepts. Both checks are per-player, so one faulty or
 * hostile client cannot crowd out the rest of the grid.
 *
 * The sequence rule comes first: anything at or below the high-water mark is a replay (or a
 * duplicate of an already-applied sample) and is ignored without touching the rate window, so
 * replays can never consume budget. Over the rate limit, samples are dropped rather than applied.
 *
 * UDP later changes nothing here: [seq], the window, and the idempotent per-sample apply are the
 * reason the swap is transport-only.
 */
class ControllerInputGate(
    private val maxPerSecond: Int = ControllerContract.MAX_INPUT_PER_SECOND,
) {

    private var lastSeq: Long = 0L
    private val window = ArrayDeque<Long>()

    /**
     * Whether the sample with [seq] arriving at [nowMs] (either end's `uptimeMillis`) is accepted.
     *
     * Accepting advances the high-water mark and records the arrival; rejecting changes nothing.
     * [nowMs] is a parameter rather than a clock read so tests drive time by hand.
     */
    fun offer(seq: Long, nowMs: Long): Boolean {
        if (seq <= lastSeq) return false
        while (window.isNotEmpty() && nowMs - window.first() > RATE_WINDOW_MS) window.removeFirst()
        if (window.size >= maxPerSecond) return false
        window.addLast(nowMs)
        lastSeq = seq
        return true
    }
}
