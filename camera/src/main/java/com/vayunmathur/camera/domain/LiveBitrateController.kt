package com.vayunmathur.camera.domain

import kotlin.math.roundToInt

/**
 * During-recording bitrate control, driven by the same policy numbers as the
 * pre-recording decision: the recording starts at the decision's bitrate, and
 * live motion re-evaluations move within [floorBps]..[ceilingBps].
 *
 * The ceiling never exceeds the pre-recording decision's intent: when storage
 * or thermal forced the rung down, live motion may not spend that back; when
 * nothing was forced, the ceiling is the (possibly boosted) base rung.
 *
 * Anti-oscillation: a candidate must (a) differ from the current bitrate by at
 * least [MIN_RELATIVE_CHANGE], (b) win [REQUIRED_CONSECUTIVE_WINS] consecutive
 * evaluations, and (c) arrive at least [evaluateIntervalMs] after the last
 * applied change. Applied changes are slew-limited to [MAX_SLEW_FRACTION] of
 * the current bitrate per step, so the encoder ramps instead of jumping.
 *
 * Pure and clock-injected: tests drive it with synthetic motion/time.
 */
class LiveBitrateController(
    private val decision: AdaptiveBitrateDecision,
    private val evaluateIntervalMs: Long = DEFAULT_EVALUATE_INTERVAL_MS,
) {
    /** Bitrate the encoder should be at now (starts at the pre-record decision). */
    var currentBitrateBps: Int = decision.bitrateBps
        private set

    private var lastChangeAtMs: Long? = null
    private var pendingCandidateBps: Int? = null
    private var pendingWins: Int = 0

    val floorBps: Int = (decision.effectiveTier.baseBitrateBps *
        AdaptiveVideoBitratePolicy.QUALITY_FLOOR_FRACTION).roundToInt()

    val ceilingBps: Int = if (decision.reductions.isEmpty()) {
        maxOf(decision.baseTier.baseBitrateBps, decision.bitrateBps)
    } else {
        decision.bitrateBps
    }

    /** Candidate bitrate for [motion] under the recording's floor/ceiling. */
    fun candidateFor(motion: SceneMotion?): Int {
        val factor = when (motion) {
            SceneMotion.STATIC -> AdaptiveVideoBitratePolicy.STATIC_FACTOR
            SceneMotion.MODERATE -> AdaptiveVideoBitratePolicy.MODERATE_FACTOR
            else -> AdaptiveVideoBitratePolicy.HIGH_MOTION_FACTOR
        }
        val raw = (decision.effectiveTier.baseBitrateBps * factor).roundToInt()
        return raw.coerceIn(floorBps, ceilingBps)
    }

    /**
     * One evaluation tick (the recorder calls this every ~[evaluateIntervalMs]).
     * Returns the new target bitrate to apply to the encoder, or null to hold.
     */
    fun evaluate(motion: SceneMotion?, nowMs: Long): Int? {
        val candidate = candidateFor(motion)
        if (candidate == pendingCandidateBps) {
            pendingWins++
        } else {
            pendingCandidateBps = candidate
            pendingWins = 1
        }
        val lastChange = lastChangeAtMs
        if (lastChange != null && nowMs - lastChange < evaluateIntervalMs) return null
        if (pendingWins < REQUIRED_CONSECUTIVE_WINS) return null
        val delta = kotlin.math.abs(candidate - currentBitrateBps).toDouble()
        if (delta < currentBitrateBps * MIN_RELATIVE_CHANGE) return null

        val maxStep = (currentBitrateBps * MAX_SLEW_FRACTION).roundToInt()
        val next = if (candidate > currentBitrateBps) {
            minOf(candidate, currentBitrateBps + maxStep)
        } else {
            maxOf(candidate, currentBitrateBps - maxStep)
        }
        if (next == currentBitrateBps) return null
        currentBitrateBps = next
        lastChangeAtMs = nowMs
        pendingCandidateBps = null
        pendingWins = 0
        return next
    }

    companion object {
        const val DEFAULT_EVALUATE_INTERVAL_MS: Long = 2_000L
        const val REQUIRED_CONSECUTIVE_WINS: Int = 2
        const val MIN_RELATIVE_CHANGE: Double = 0.10
        const val MAX_SLEW_FRACTION: Double = 0.25
    }
}
