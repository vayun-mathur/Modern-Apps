package com.vayunmathur.auto.protocol

/**
 * Reconnect backoff: full-jitter exponential delay between redials.
 *
 * Pure policy, no threads or clocks: the service loop calls [onFailure] when a session
 * ends abnormally and sleeps the returned delay, and [onSuccess] when a session
 * establishes (or ends cleanly) to reset the streak. Delay for consecutive failure `n`
 * is uniform in `[0, min(maxDelayMs, baseDelayMs * 2^n)]` — full jitter, so a fleet of
 * phones hammering one head unit spreads out instead of marching in lockstep.
 *
 * @param jitter draws the actual delay within `[0, bound)`. Defaults to `Math.random`;
 *   tests inject a fixed draw to assert exact caps.
 */
class ReconnectBackoff(
    private val baseDelayMs: Long = DEFAULT_BASE_DELAY_MS,
    private val maxDelayMs: Long = DEFAULT_MAX_DELAY_MS,
    private val jitter: (bound: Long) -> Long = { bound -> (Math.random() * bound).toLong() },
) {
    /** Consecutive failures since the last success. */
    var failures: Int = 0
        private set

    /**
     * Records a failed attempt and returns how long to wait before the next one.
     * Capped at [maxDelayMs]; the shift saturates so long streaks cannot overflow.
     */
    fun onFailure(): Long {
        val shift = failures.coerceAtMost(MAX_SHIFT)
        val cap = (baseDelayMs shl shift).coerceAtMost(maxDelayMs)
        failures++
        // Bound is exclusive and at least 1; a zero cap still yields a (zero) draw.
        return jitter(cap + 1).coerceIn(0, maxDelayMs)
    }

    /** Records a success: the next failure backs off from the base again. */
    fun onSuccess() {
        failures = 0
    }

    /** Default reconnect delays and the shift cap that keeps long failure streaks from overflowing. */
    companion object {
        /** First retry waits up to half a second. */
        const val DEFAULT_BASE_DELAY_MS = 500L

        /** Retries never wait longer than half a minute. */
        const val DEFAULT_MAX_DELAY_MS = 30_000L

        /** `base << 20` already overflows any sane cap; saturate the shift here. */
        private const val MAX_SHIFT = 20
    }
}
