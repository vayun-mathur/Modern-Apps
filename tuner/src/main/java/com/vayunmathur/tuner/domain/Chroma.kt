package com.vayunmathur.tuner.domain

import kotlin.math.ln
import kotlin.math.sqrt

/**
 * One frame of chord evidence read off the CQT, with no note decisions made.
 *
 * The sieve has to commit to an exact note set before anything is named, so one missed or one
 * extra pitch class costs the name. These are soft saliences instead: the chord scorer weighs
 * every hypothesis against them and the HMM accumulates them across a strum.
 */
internal class ChromaFrame(
    /** Whitened log salience per semitone for MIDI [LOWEST_NOTE_MIDI] upwards; zero below the tier. */
    val semitones: DoubleArray,
    /** [semitones] folded to pitch classes, L2-normalised (all zero for an empty frame). */
    val treble: DoubleArray,
    /** Lowest strong note in the bass register, or null when there is none or it was not asked for. */
    val bassMidi: Int?,
    /** Salience of [bassMidi] relative to the frame's strongest semitone, 0 when null. */
    val bassConfidence: Double,
)

internal object Chroma {
    /**
     * [firstBin] masks the bins the provisional tier has not filled yet. [tuningCents] slides the
     * semitone grid onto the instrument rather than penalising a flat one for sitting between bins.
     */
    fun of(magnitudes: DoubleArray, tuningCents: Double, firstBin: Int, withBass: Boolean): ChromaFrame {
        require(magnitudes.size == CQT_BINS) { "expected $CQT_BINS bins" }
        val semitones = semitoneSalience(whiten(magnitudes, firstBin), tuningCents, firstBin)

        val treble = DoubleArray(12)
        for (n in semitones.indices) treble[pitchClassOf(LOWEST_NOTE_MIDI + n)] += semitones[n]
        normalise(treble)

        val bass = if (withBass) lowestStrongNote(semitones) else null
        return ChromaFrame(semitones, treble, bass?.first, bass?.second ?: 0.0)
    }

    /**
     * Level-normalised log compression, then the local mean subtracted.
     *
     * Normalising by the frame's own peak first is what makes the compression actually compress:
     * a quiet decay tail at -24 dBFS has CQT magnitudes around 0.03, where `ln(1 + m)` is linear.
     */
    private fun whiten(magnitudes: DoubleArray, firstBin: Int): DoubleArray {
        val out = DoubleArray(CQT_BINS)
        var peak = 0.0
        for (i in firstBin until CQT_BINS) if (magnitudes[i] > peak) peak = magnitudes[i]
        if (peak <= 0.0) return out
        val log = DoubleArray(CQT_BINS) { if (it < firstBin) 0.0 else ln(1.0 + COMPRESSION * magnitudes[it] / peak) }
        for (i in firstBin until CQT_BINS) {
            val from = maxOf(firstBin, i - WHITENING_HALF_WIDTH)
            val to = minOf(CQT_BINS - 1, i + WHITENING_HALF_WIDTH)
            var mean = 0.0
            for (k in from..to) mean += log[k]
            mean /= (to - from + 1)
            out[i] = (log[i] - mean).coerceAtLeast(0.0)
        }
        return out
    }

    /**
     * The strongest of the three bins around each tuned semitone centre.
     *
     * Three bins a semitone, so the windows `[3n - 1, 3n + 1]` partition the axis and two adjacent
     * notes can never claim the same bin, while still absorbing ±33 cents of residual mistuning and
     * string inharmonicity.
     */
    private fun semitoneSalience(white: DoubleArray, tuningCents: Double, firstBin: Int): DoubleArray {
        val shift = tuningCents * CQT_BINS_PER_OCTAVE / 1200.0
        return DoubleArray(NOTE_COUNT) { n ->
            val centre = n * BINS_PER_SEMITONE + shift
            if (centre < firstBin) {
                0.0
            } else {
                maxOf(sample(white, centre - 1), sample(white, centre), sample(white, centre + 1))
            }
        }
    }

    private fun sample(values: DoubleArray, position: Double): Double {
        if (position < 0.0 || position > values.size - 1) return 0.0
        val low = position.toInt()
        val high = minOf(low + 1, values.size - 1)
        val t = position - low
        return values[low] * (1 - t) + values[high] * t
    }

    /**
     * The bass is the lowest *strong* note, not the strongest low one: in a C chord voiced C3 E3 G3
     * all three are equally strong, and only pitch order says which is underneath.
     */
    private fun lowestStrongNote(semitones: DoubleArray): Pair<Int, Double>? {
        val peak = semitones.max()
        if (peak <= 0.0) return null
        for (n in 0..(BASS_CEILING_MIDI - LOWEST_NOTE_MIDI)) {
            val value = semitones[n]
            if (value < BASS_FRACTION * peak) continue
            if (n > 0 && semitones[n - 1] > value) continue
            if (semitones[n + 1] > value) continue
            return (LOWEST_NOTE_MIDI + n) to value / peak
        }
        return null
    }

    fun normalise(values: DoubleArray) {
        var norm = 0.0
        for (v in values) norm += v * v
        norm = sqrt(norm)
        if (norm > 0.0) for (i in values.indices) values[i] /= norm
    }

    private const val BINS_PER_SEMITONE = CQT_BINS_PER_OCTAVE / 12

    /** Dynamic range of the log stage: 40 dB below the frame's peak maps to about 0. */
    private const val COMPRESSION = 100.0

    private const val WHITENING_HALF_WIDTH = CQT_BINS_PER_OCTAVE

    /** G3. A guitar's bass sits at or below this in every common shape, barre chords included. */
    private const val BASS_CEILING_MIDI = 55

    private const val BASS_FRACTION = 0.5
}
