package com.vayunmathur.tuner.domain

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * How far the instrument being heard sits from equal temperament, in cents.
 *
 * [HarmonicSieve] scores at exact semitone centres, so an instrument that is a long way flat has
 * every fundamental between the frequencies the sieve looks at and scores badly for a reason
 * that is not its fault. Record-3 is 56.9 cents flat and is the case that motivated this: with
 * no compensation the true peaks fall outside the sieve's search window entirely.
 *
 * Method: parabolic interpolation of every strong local maximum in the CQT, then the circular
 * mean deviation from the nearest semitone, accumulated over frames. Circular because the
 * quantity wraps, and accumulated because one frame of one plucked note is a sample of one.
 */
class TuningOffset {
    private var sumSin = 0.0
    private var sumCos = 0.0
    private var peaks = 0

    /** Drops the estimate. Call when capture restarts or the instrument changes. */
    fun reset() {
        sumSin = 0.0
        sumCos = 0.0
        peaks = 0
    }

    /** True once enough peaks have been seen for [cents] to mean anything. */
    val settled: Boolean get() = peaks >= MIN_PEAKS

    /**
     * The current estimate, or 0 until [settled].
     *
     * **The sign is a judgement call, and this is the whole subtlety of the class.** Only the
     * fraction of a semitone is measurable: peaks 43 cents above the nearest semitone and peaks
     * 57 cents below the next one up are the same spectrum, and nothing in the audio
     * distinguishes them. Nearest-semitone would call Record-3 B major; the player was playing C
     * on an instrument nobody had tuned.
     *
     * So the tie is broken by a prior about instruments rather than by the signal: strings relax
     * and go flat, they do not go sharp on their own. An apparent sharpness beyond
     * [MAX_SHARP_CENTS] is read as the flat branch instead. Below that a genuinely slightly-sharp
     * instrument is taken at face value, because folding *everything* to flat would put a
     * freshly-tuned instrument a semitone out.
     *
     * This is the one number here that is a choice and not a measurement, and it is a choice
     * about which whole semitone the answer lands in - so getting it wrong renames every chord
     * by a semitone rather than degrading gracefully.
     */
    val cents: Double
        get() {
            if (!settled) return 0.0
            val fraction = atan2(sumSin, sumCos) * 100.0 / (2.0 * PI)
            return if (fraction > MAX_SHARP_CENTS) fraction - 100.0 else fraction
        }

    /** Folds one CQT frame into the estimate. Cheap: one pass over the bins, no extra transform. */
    fun observe(magnitudes: DoubleArray) {
        val ceiling = magnitudes.max()
        if (ceiling <= 0.0) return
        for (bin in 1 until magnitudes.size - 1) {
            val here = magnitudes[bin]
            if (here < PEAK_FRACTION * ceiling) continue
            if (here <= magnitudes[bin - 1] || here <= magnitudes[bin + 1]) continue
            val a = ln(magnitudes[bin - 1] + LOG_FLOOR)
            val b = ln(here + LOG_FLOOR)
            val c = ln(magnitudes[bin + 1] + LOG_FLOOR)
            val denominator = a - 2.0 * b + c
            if (abs(denominator) < 1e-12) continue
            val shift = 0.5 * (a - c) / denominator
            if (abs(shift) > 0.5) continue
            val semitones = (bin + shift) / (CQT_BINS_PER_OCTAVE / 12.0)
            val deviation = (semitones - semitones.roundToInt()) * 100.0
            val angle = 2.0 * PI * deviation / 100.0
            sumSin += sin(angle)
            sumCos += cos(angle)
            peaks++
        }
    }

    /** Shared constants and helpers for TuningOffset. */
    companion object {
        /** A local maximum this far below the frame's strongest is a sidelobe, not a partial. */
        const val PEAK_FRACTION: Double = 0.25

        /**
         * Peaks needed before the estimate is used. A few frames of a strum, so the offset is
         * settled long before the 8 frames the confirmed tier needs anyway.
         */
        const val MIN_PEAKS: Int = 40

        /**
         * Apparent sharpness above which the flat branch is taken instead. See [cents].
         *
         * A quarter-tone. Nobody tunes an instrument a quarter-tone sharp by accident, and
         * everybody owns one that has gone that far flat by neglect.
         */
        const val MAX_SHARP_CENTS: Double = 25.0

        private const val LOG_FLOOR = 1e-12
    }
}
