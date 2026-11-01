package com.vayunmathur.tuner.domain

import kotlin.math.exp
import kotlin.math.ln

/**
 * Whether a CQT frame contains tonal content at all - the gate the Chord tab needs and the Note
 * tab gets for free.
 *
 * YIN computes an aperiodicity measure as part of finding a period, so the Note tab rejects
 * background noise using a number the algorithm already had. NNLS has no equivalent: it is a
 * fitting procedure, so given room hum it returns the non-negative combination of dictionary
 * atoms that best explains room hum, and the namer downstream has no way to tell that from a
 * chord. Nothing in the deconvolution pipeline ever asks whether the input was music.
 *
 * This asks. Two signals do the work - the flatness of the CQT frame, and an absolute floor
 * under the strongest salience - and both are read off stages that have already run, so the
 * gate costs one pass over 180 bins and no extra transform. A third, the NNLS residual, is
 * measured alongside them and deliberately not used: see [Tonality.explained].
 */
data class Tonality(
    /**
     * Fraction of the observation's energy the fitted dictionary accounts for.
     *
     * Measured but deliberately **not** gated on, which is worth recording because the argument
     * for using it is a good one and someone will make it again. Residual energy is the
     * principled choice: it asks directly whether a *harmonic* model explains the input rather
     * than asking after a correlate of that.
     *
     * It does not work here. Over the tier-A corpus under four partial laws, real chords explain
     * 0.741..0.951 of the frame and white and pink noise explain 0.499..0.727. Those
     * distributions are 0.014 apart and no threshold separates them: gating at 0.75 rejects
     * three of the fourteen tier-A voicings on a fast-decaying partial law, and every threshold
     * low enough to keep them also passes every noise frame tested.
     *
     * The reason is that the dictionary is expressive enough to absorb noise. Sixty non-negative
     * columns over 180 bins, each a comb of twelve Gaussians, can between them account for most
     * of any positive spectrum - the same property that makes the deconvolution work. So the
     * residual measures how flexible the dictionary is at least as much as how harmonic the
     * input was. TonalityTest asserts the overlap so this cannot be reinstated unnoticed.
     */
    val explained: Double,
    /**
     * Wiener entropy of the raw CQT frame: geometric mean over arithmetic mean.
     *
     * The criterion doing the work. It is also the only one invariant to level - a ratio of two
     * means, so a chord played quietly measures as peaky as a loud one - which matters because
     * a tuner is used at whatever volume the room allows.
     */
    val flatness: Double,
    /**
     * The strongest note's salience *before* normalisation.
     *
     * Every other criterion in the pipeline is relative, and a relative criterion always crowns
     * a winner: normalise a frame of near-silence and its loudest accident becomes a salience
     * of 1. This is the only test that can fail for a whole frame at once.
     */
    val peakSalience: Double,
) {
    /** True when there is something to name. */
    val isTonal: Boolean
        get() = flatness <= MAX_FLATNESS && peakSalience >= MIN_PEAK_SALIENCE

    companion object {
        /**
         * Measured (TonalityTest): chords 0.004..0.142, noise 0.709..1.000 - a factor of five
         * between the two, and the reason this is the criterion the gate rests on.
         *
         * Placed at 0.40 rather than midway, because flatness is the one signal that degrades
         * with room noise on a genuine chord: a C major measures 0.28 at 10 dB SNR and 0.38 at
         * 5 dB. Below about 5 dB the frame is more noise than chord and refusing to name it is
         * the right answer anyway.
         */
        const val MAX_FLATNESS: Double = 0.40

        /**
         * The gate for `ChordExtractor.CHROMA_HMM`. The note-first extractors name every frame they
         * pass on its own; the HMM weighs a marginal frame as weak evidence instead. On Record-3,
         * loosening its gate from 0.40 to 0.60 took correct frames from 33 to 42 with wrong ones
         * unchanged at 2. Still below the 0.709 of the most tonal noise measured here.
         */
        const val CHROMA_MAX_FLATNESS: Double = 0.60

        /**
         * In the units [NoteSalience.subtractBackground] produces - log-magnitude above a local
         * mean - so this is an absolute floor on how far a note stands out of its own
         * neighbourhood, not a fraction of anything in the frame.
         *
         * Measured: chords 0.15..0.28 at normal level, 0.028 at a tenth of it and 0.014 at a
         * twentieth, against 0.011 for the loudest noise tested. Unlike [flatness] this scales
         * with level, so at the level of a soft strum it has no headroom over noise at all and
         * would reject genuine quiet playing if it were set to separate the two.
         *
         * So it is not asked to. It sits an order of magnitude below the quietest chord worth
         * naming and does one job: making sure some criterion in the pipeline is absolute, so
         * that a frame of near-silence cannot be normalised into a salience of 1 and named.
         */
        const val MIN_PEAK_SALIENCE: Double = 0.005
    }
}

/**
 * Wiener entropy of [magnitudes]: `exp(mean(ln x)) / mean(x)`, 0 for a single peak and 1 for a
 * perfectly flat spectrum.
 *
 * Taken on the raw CQT rather than the background-subtracted observation on purpose. Background
 * subtraction is a whitening step - it removes exactly the spectral shape flatness measures - so
 * running this after it would report every frame as noise.
 */
fun spectralFlatness(magnitudes: DoubleArray): Double {
    var logSum = 0.0
    var sum = 0.0
    for (magnitude in magnitudes) {
        val value = magnitude + FLATNESS_FLOOR
        logSum += ln(value)
        sum += value
    }
    val arithmetic = sum / magnitudes.size
    if (arithmetic <= 0.0) return 1.0
    return (exp(logSum / magnitudes.size) / arithmetic).coerceIn(0.0, 1.0)
}

/** Gathers the three signals for one frame. */
fun tonalityOf(magnitudes: DoubleArray, fit: SalienceFit): Tonality =
    Tonality(fit.explained, spectralFlatness(magnitudes), fit.peak)

/** Keeps a silent frame's zeros out of the logarithm without biasing a loud one. */
private const val FLATNESS_FLOOR = 1e-9
