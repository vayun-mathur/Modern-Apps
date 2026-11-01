package com.vayunmathur.tuner.domain

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * Which note is the loudest thing sounding: Klapuri's harmonic-summation salience (Klapuri 2006,
 * "Multiple fundamental frequency estimation by summing harmonic amplitudes"), the standard
 * method for the predominant pitch of a mixture.
 *
 * YIN alone answers a different question - the period of the *whole* signal - and with anything
 * else sounding that period is far below the plucked note: an A4 over a low E ringing at -12 dB
 * read F2 (five A4 periods, where E2 also nearly repeats), and over six open strings it read A0.
 * Here every candidate f0 instead collects whitened spectral peaks at its partials, weighted by
 * `g(f, m) = (f + ALPHA) / (m * f + BETA)`. The weight is what makes the plucked note win: A4's
 * own fundamental counts 3.5 times more towards A4 than towards F2, which can only claim it as
 * its fifth partial.
 *
 * The weighting leans slightly upward, so a low string with no fundamental can look like its own
 * octave. [subharmonicOf] undoes that only when the lower note's *own* partials - those the
 * higher candidate cannot claim - carry real energy: a real E2 has 82, 247 and 412 Hz; the
 * phantom F2 under an A4 has nothing at 87, 175, 262 or 349 Hz.
 *
 * Only the note is taken from here. The displayed frequency still comes from YIN and phase-slope,
 * restricted to this note's neighbourhood, so sub-cent precision is unchanged.
 */
class PredominantPitch(private val sampleRate: Double) {
    private val transforms = HashMap<Int, Workspace>()

    /** Predominant f0 in Hz of the last [length] samples of [x], or null for an empty spectrum. */
    fun estimate(x: DoubleArray, length: Int, minHz: Double, maxHz: Double): Double? {
        val space = transforms.getOrPut(length) { Workspace(length) }
        val spectrum = space.whitened(x, x.size - length) ?: return null
        val raw = space.magnitude
        val binHz = sampleRate / space.fftSize

        val candidates = ArrayList<Double>()
        var f = minHz
        while (f <= maxHz) {
            candidates += f
            f *= GRID_STEP
        }
        var best = -1
        var bestSalience = 0.0
        val saliences = DoubleArray(candidates.size)
        for ((i, candidate) in candidates.withIndex()) {
            saliences[i] = salience(spectrum, binHz, candidate, null)
            if (saliences[i] > bestSalience && supported(raw, binHz, candidate)) {
                bestSalience = saliences[i]
                best = i
            }
        }
        if (best < 0) return null
        val peak = refine(candidates, saliences, best)
        if (isPureLowTone(raw, binHz, peak)) return null
        val f0 = subharmonicOf(spectrum, raw, binHz, peak, bestSalience, minHz)
        return if (isPureLowTone(raw, binHz, f0)) null else f0
    }

    /**
     * A low tone with no overtone series is not a string. On Record-3 the gaps between strums
     * carry a 59-66 Hz tone - mains, an appliance or handling - with ~90% of its partial energy in
     * one partial, where a phone-captured low E has 33% in its strongest. Unchecked it read as
     * B1 and C2, and an octave below that as A#0.
     */
    private fun isPureLowTone(raw: DoubleArray, binHz: Double, f0: Double): Boolean {
        if (f0 >= PURE_TONE_CEILING_HZ) return false
        var total = 0.0
        var strongest = 0.0
        for (m in 1..PURE_TONE_PARTIALS) {
            val p = partialPeak(raw, binHz, m * f0)
            total += p * p
            strongest = maxOf(strongest, p * p)
        }
        return total > 0.0 && strongest / total >= PURE_TONE_SHARE
    }

    /**
     * Whether [f0]'s first partials carry their share of its energy, read off the unwhitened
     * spectrum: whitening lifts quiet low bands, which would let room noise at a phantom root's
     * fundamental and octave count as support.
     */
    private fun supported(raw: DoubleArray, binHz: Double, f0: Double): Boolean =
        salience(raw, binHz, f0, null, LOW_ORDER_PARTIALS) >= LOW_ORDER_SHARE * salience(raw, binHz, f0, null)

    private fun subharmonicOf(
        spectrum: DoubleArray,
        raw: DoubleArray,
        binHz: Double,
        f0: Double,
        salience: Double,
        minHz: Double,
    ): Double {
        for (k in MAX_SUBHARMONIC downTo 2) {
            val lower = f0 / k
            if (lower < minHz) continue
            if (salience(spectrum, binHz, lower, k) >= OWN_PARTIAL_RATIO * salience && supported(raw, binHz, lower)) {
                return lower
            }
        }
        return f0
    }

    /** Parabolic interpolation in log-frequency across the grid. */
    private fun refine(candidates: List<Double>, saliences: DoubleArray, i: Int): Double {
        if (i == 0 || i == candidates.lastIndex) return candidates[i]
        val a = saliences[i - 1]
        val b = saliences[i]
        val c = saliences[i + 1]
        val denominator = a - 2 * b + c
        if (denominator >= 0.0) return candidates[i]
        val shift = (0.5 * (a - c) / denominator).coerceIn(-0.5, 0.5)
        return candidates[i] * GRID_STEP.pow(shift)
    }

    /**
     * `sum g(f, m) * max |Y(k)|` over each partial's bin range. With [skipMultiplesOf] set, only
     * partials whose index is not a multiple of it count - the ones a candidate that many times
     * higher could not have produced.
     */
    private fun salience(
        spectrum: DoubleArray,
        binHz: Double,
        f0: Double,
        skipMultiplesOf: Int?,
        partials: Int = MAX_PARTIALS,
    ): Double {
        var total = 0.0
        var m = 1
        while (true) {
            val centre = m * f0
            if (centre > MAX_PARTIAL_HZ || m > partials) break
            if (skipMultiplesOf == null || m % skipMultiplesOf != 0) {
                total += (f0 + ALPHA) / (m * f0 + BETA) * partialPeak(spectrum, binHz, centre)
            }
            m++
        }
        return total
    }

    private fun partialPeak(spectrum: DoubleArray, binHz: Double, centre: Double): Double {
        val half = maxOf(1.0, centre * PARTIAL_TOLERANCE / binHz)
        val from = ((centre / binHz) - half).toInt().coerceAtLeast(1)
        val to = ((centre / binHz) + half + 0.5).toInt().coerceAtMost(spectrum.size - 1)
        var peak = 0.0
        for (k in from..to) if (spectrum[k] > peak) peak = spectrum[k]
        return peak
    }

    /**
     * Hann-windowed, zero-padded to twice the frame, then Klapuri's spectral whitening: each
     * auditory band is scaled by `sigma^(NU - 1)`, which flattens the instrument's envelope so a
     * bright partial cannot outvote a whole harmonic series.
     */
    private inner class Workspace(val length: Int) {
        val fftSize = 2 * length
        private val fft = Fft(fftSize)
        private val window = DoubleArray(length) { 0.5 - 0.5 * cos(2.0 * PI * it / length) }
        private val re = DoubleArray(fftSize)
        private val im = DoubleArray(fftSize)
        val magnitude = DoubleArray(fftSize / 2)
        private val binHz = sampleRate / fftSize
        private val centres = DoubleArray(BANDS + 2) { 229.0 * (10.0.pow((it + 1) / 21.4) - 1.0) }

        fun whitened(x: DoubleArray, offset: Int): DoubleArray? {
            re.fill(0.0)
            im.fill(0.0)
            for (i in 0 until length) re[i] = x[offset + i] * window[i]
            fft.forward(re, im)
            var energy = 0.0
            for (k in magnitude.indices) {
                magnitude[k] = sqrt(re[k] * re[k] + im[k] * im[k])
                energy += magnitude[k]
            }
            if (energy <= 0.0) return null

            val gain = DoubleArray(BANDS + 2)
            for (b in 1..BANDS) {
                var sum = 0.0
                var weight = 0.0
                val from = (centres[b - 1] / binHz).toInt().coerceAtLeast(1)
                val to = (centres[b + 1] / binHz).toInt().coerceAtMost(magnitude.size - 1)
                for (k in from..to) {
                    val hz = k * binHz
                    val h = if (hz <= centres[b]) {
                        (hz - centres[b - 1]) / (centres[b] - centres[b - 1])
                    } else {
                        (centres[b + 1] - hz) / (centres[b + 1] - centres[b])
                    }
                    if (h <= 0.0) continue
                    sum += h * magnitude[k] * magnitude[k]
                    weight += h
                }
                val sigma = if (weight > 0.0) sqrt(sum / weight) else 0.0
                gain[b] = if (sigma > 0.0) sigma.pow(NU - 1.0) else 0.0
            }
            gain[0] = gain[1]
            gain[BANDS + 1] = gain[BANDS]

            val out = DoubleArray(magnitude.size)
            var band = 0
            for (k in out.indices) {
                val hz = k * binHz
                while (band < BANDS && hz > centres[band + 1]) band++
                val g = if (hz <= centres[0]) {
                    gain[0]
                } else if (band >= BANDS) {
                    gain[BANDS]
                } else {
                    val t = (hz - centres[band]) / (centres[band + 1] - centres[band])
                    gain[band] * (1 - t) + gain[band + 1] * t
                }
                out[k] = g * magnitude[k]
            }
            return out
        }
    }

    private companion object {
        /** Klapuri's weighting constants for frames of this length. */
        const val ALPHA = 52.0
        const val BETA = 320.0

        /** Whitening exponent and the number of auditory bands (Klapuri 2006). */
        const val NU = 0.33
        const val BANDS = 30

        /** 10 cents. Fine enough that a partial is never missed between grid points. */
        val GRID_STEP: Double = 2.0.pow(10.0 / 1200.0)

        /** Bin range searched around each partial, relative: covers the grid and string stiffness. */
        const val PARTIAL_TOLERANCE = 0.006

        const val MAX_PARTIALS = 20
        const val MAX_PARTIAL_HZ = 6000.0

        const val MAX_SUBHARMONIC = 3

        /** A lower note is real when its own partials carry this share of the winner's salience. */
        const val OWN_PARTIAL_RATIO = 0.5

        /**
         * A candidate must earn this share of its salience from its first [LOW_ORDER_PARTIALS]
         * partials. A real low string always does - even with no fundamental, its octave and
         * twelfth are strong - while the common root under a strummed C chord collects C4, E4, G4
         * and C5 as partials 4, 5, 6 and 8 and has nothing at C2, C3 or G3.
         */
        const val LOW_ORDER_PARTIALS = 3
        const val LOW_ORDER_SHARE = 0.25

        const val PURE_TONE_CEILING_HZ = 100.0
        const val PURE_TONE_PARTIALS = 8
        const val PURE_TONE_SHARE = 0.65
    }
}
