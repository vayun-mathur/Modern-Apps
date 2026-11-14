package com.vayunmathur.games.kart.domain

import com.vayunmathur.games.kart.data.CUPS

/**
 * The placeholder race: a software top-down model that unblocks lobby → race → results before the
 * native 3D engine lands.
 *
 * One lap is [TRACK_LENGTH] units; karts advance by throttle × [TOP_SPEED] per second, minus a
 * steering scrub. Positions are fractional progress in laps; [leaderboard] sorts by total progress.
 * AI drivers hold pace × wobble plus a rubber-band boost off the leader's progress (see
 * [effectiveAiPace]), so they are beatable but not statues.
 *
 * Everything here is pure and timestepped ([tick] takes seconds), so the race is a JVM test and
 * the native engine only has to reproduce its shape, not its code.
 */
class PlaceholderRace(
    val playerIds: List<String>,
    val laps: Int = 3,
    private val aiCount: Int = 3,
) {
    /** Shared constants and helpers for PlaceholderRace. */
    companion object {
        /** Units per lap. Arbitrary: only ratios matter until the 3D track defines meters. */
        const val TRACK_LENGTH = 1000f

        /** Units per second at full throttle. A lap takes ~25 s flat out. */
        const val TOP_SPEED = 40f

        /**
         * Fraction of speed kept while drifting through full lock.
         *
         * Above the 0.82 a plain full-lock corner keeps: drift trades grip for keeping boost
         * charge, not for raw pace. Anything at or below that would make drifting the fast way
         * through every corner with no decision in it.
         */
        const val DRIFT_KEEP = 0.88f

        /** AI pace as a fraction of top speed, before the per-driver wobble. */
        const val AI_PACE = 0.78f

        /**
         * Rubber-band gain: pace points earned per [RUBBER_STEP] laps of deficit behind the leader.
         * Mirrors the native engine's `RUBBER_GAIN`.
         */
        const val RUBBER_GAIN = 0.02f

        /**
         * Deficit step (in laps, fractional) that earns one [RUBBER_GAIN].
         * Mirrors the native engine's `RUBBER_STEP`.
         */
        const val RUBBER_STEP = 0.05f

        /**
         * Hard cap on effective AI pace: backmarkers close in, never teleport past.
         * Mirrors the native engine's `AI_PACE_MAX`.
         */
        const val AI_PACE_MAX = 0.95f

        /**
         * Rubber-band boost in pace points for a [deficitLaps]-lap deficit behind the leader.
         *
         * Formula: `floor(deficit / RUBBER_STEP) * RUBBER_GAIN` — +0.02 pace per 5% of a lap
         * down, same steps as the native engine, so mid-pack pace doesn't jitter as gaps breathe.
         * The leader (deficit <= 0) gets nothing; the cap lives in [effectiveAiPace].
         */
        fun rubberBoost(deficitLaps: Float): Float {
            if (deficitLaps <= 0f) return 0f
            return kotlin.math.floor(deficitLaps / RUBBER_STEP) * RUBBER_GAIN
        }

        /**
         * Effective AI pace for a driver with [wobble] sitting [deficitLaps] behind the leader:
         * base pace times wobble, plus the rubber-band boost, capped at [AI_PACE_MAX].
         *
         * The leader always runs bare `AI_PACE * wobble` — no leader slowdown, by design (see the
         * native `effective_pace` rustdoc for why).
         */
        fun effectiveAiPace(wobble: Float, deficitLaps: Float): Float =
            (AI_PACE * wobble + rubberBoost(deficitLaps)).coerceAtMost(AI_PACE_MAX)
    }

    private data class Kart(
        val id: String,
        val ai: Boolean,
        var progress: Float = 0f,
        var wobble: Float = 0f,
    )

    private val karts = (playerIds.map { Kart(it, ai = false) } +
        List(aiCount) { i -> Kart("ai-$i", ai = true, wobble = 0.92f + 0.04f * i) })
        .toMutableList()

    private val controls = mutableMapOf<String, KartControl>()

    /** Last tick's item-button state per player: the rising-edge latch for [useItem]. */
    private val itemLatch = mutableMapOf<String, Boolean>()

    val finished: Boolean get() = karts.any { lapOf(it) >= laps }

    /** Completed laps for [kart]. */
    fun lapOf(id: String): Int = karts.firstOrNull { it.id == id }?.let { lapOf(it) } ?: 0

    private fun lapOf(kart: Kart): Int = (kart.progress / TRACK_LENGTH).toInt()

    /** 0..1 through the current lap, for the progress bar. */
    fun lapProgress(id: String): Float {
        val kart = karts.firstOrNull { it.id == id } ?: return 0f
        return ((kart.progress % TRACK_LENGTH) / TRACK_LENGTH).coerceIn(0f, 1f)
    }

    /** Player ids first, then AI, each by total progress descending. */
    fun leaderboard(): List<String> = karts.sortedByDescending { it.progress }.map { it.id }

    /** The winner: first across the line once [finished]. */
    fun winner(): String? =
        if (finished) karts.maxByOrNull { it.progress }?.id else null

    fun setControl(playerId: String, control: KartControl) {
        controls[playerId] = control
    }

    /**
     * Fire the held item for [playerId]. True only on the rising edge.
     *
     * The loop sets `itemUse` from the button state every tick, so a held button reads true on
     * consecutive ticks; the latch remembers the last tick and fires once, so holding the button
     * does not fire twice. No items are modelled yet — firing spends nothing — but the edge
     * discipline is what the engine will build on.
     */
    fun useItem(playerId: String): Boolean {
        val held = controls[playerId]?.itemUse == true
        val was = itemLatch[playerId] ?: false
        itemLatch[playerId] = held
        return held && !was
    }

    /**
     * Advance the race by [dtSeconds].
     *
     * Steering scrubs speed (full lock costs ~18%, less while drifting); AI runs its pace ×
     * wobble plus a rubber-band boost off the leader's progress (see [effectiveAiPace]).
     * Progress never goes backwards: braking to a standstill holds position.
     */
    fun tick(dtSeconds: Float) {
        val dt = dtSeconds.coerceIn(0f, 1f)
        val leaderProgress = karts.maxOfOrNull { it.progress } ?: 0f
        for (kart in karts) {
            if (lapOf(kart) >= laps) continue
            val speed = if (kart.ai) {
                val deficitLaps = (leaderProgress - kart.progress) / TRACK_LENGTH
                TOP_SPEED * effectiveAiPace(kart.wobble, deficitLaps.coerceAtLeast(0f))
            } else {
                val control = controls[kart.id] ?: KartControl.NEUTRAL
                val scrub = 1f - kotlin.math.abs(control.steering) *
                    (if (control.drift) 1f - DRIFT_KEEP else 0.18f)
                TOP_SPEED * control.throttle * scrub -
                    TOP_SPEED * 0.5f * control.brake
            }
            kart.progress = (kart.progress + speed * dt).coerceAtLeast(0f)
        }
    }

    /** Which cup this race counts for, so results can bank progress. */
    fun cupIndex(cup: Int): Int = cup.mod(CUPS.size)
}
