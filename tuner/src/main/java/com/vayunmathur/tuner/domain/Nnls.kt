package com.vayunmathur.tuner.domain

import com.vayunmathur.tuner.data.DEFAULT_PARTIALS
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Harmonic deconvolution of a CQT spectrum into per-note salience
 * (Mauch & Dixon, ISMIR 2010 - the algorithm behind NNLS-Chroma / Chordino).
 *
 * The reason this exists rather than a plain chromagram: the overtone series of a single note
 * *is itself a chord*. C2's partials land on C, C, G, C, E, G, Bb, C, so a plain chroma of one
 * plucked low C already looks like C7. Solving `min ||E y - c||^2  s.t. y >= 0` against a
 * dictionary whose columns are single notes *including their harmonics* subtracts that out.
 *
 * The other reason, and the one that decides the UI: the output is **per note**, not a chord
 * label. Both the piano keyboard and the fret diagram need the notes.
 *
 * [soundingRange] is the closing half of that argument. A dictionary column is a hypothesis that
 * a note is sounding, and a column an octave below a real note explains that note's fundamental
 * with its own second partial and the fifth above it with its third. On a ukulele - lowest note
 * C4 - a phantom C3 therefore eats the G4 of a C chord while never appearing as a spurious tone,
 * because it shares the root's pitch class. Constraining the dictionary to the notes the chosen
 * instrument can physically sound removes the hypothesis rather than trying to out-weight it.
 */
class NoteSalience(
    soundingRange: IntRange = FULL_RANGE,
    /**
     * Relative amplitude of partial `n` at index `n - 1`. Per-instrument data rather than a law:
     * see `Instrument.partials`, and `DEFAULT_PARTIALS` for what an unmeasured instrument gets.
     *
     * Measured off Record-3, a real ukulele runs 1.00, 0.18, 0.07 and is then flat at 0.05-0.12 -
     * a noise floor, not a partial series. The `1/n` fallback over-models the octave by 2.8x and
     * Mauch's geometric `0.9^(n-1)` by 5x.
     *
     * That over-modelling is a mechanism, not a cosmetic error. If every atom promises more
     * harmonic energy than the instrument delivers, the solver must shrink the real notes to
     * avoid over-explaining the spectrum, and covering the leftover with one low phantom - whose
     * partials are over-modelled too, so it buys a lot of explanation per unit weight - becomes
     * the cheaper option. The over-modelled dictionary is what makes the phantom economical.
     */
    private val partials: List<Double> = DEFAULT_PARTIALS,
    /**
     * Bins below this are not modelled, because the caller is not going to observe them.
     *
     * `ChordDetector` zeroes the bottom two octaves of the observation in the provisional tier,
     * where the bass has not accumulated a valid window yet. Zeroing one side of a least-squares
     * problem and not the other is not a partial measurement, it is a contradictory one: any
     * weight the solver gives a note whose kernel reaches below the boundary shows up as model
     * energy against an observation of exactly zero, so the gradient drives that note out.
     *
     * Bin 72 is C4 *exactly*, and [SPREAD_BINS] puts C4's fundamental across bins 69..75 - so the
     * note the boundary lands on is the one it silently deletes, and on a ukulele that note is
     * the open C string, which sounds in C, F and Am alike. Masking the dictionary to match makes
     * the provisional pass an honest fit over the bins it can actually see.
     */
    private val firstBin: Int = 0,
) {
    /**
     * `CQT_BINS x NOTE_COUNT`, column-major: `dictionary[note][bin]`. Columns for notes outside
     * [soundingRange] are all-zero, which makes them unreachable: their gradient is identically
     * zero, so FISTA leaves them at the zero they start from.
     */
    private val dictionary: Array<DoubleArray> = Array(NOTE_COUNT) { note ->
        val midi = LOWEST_NOTE_MIDI + note
        if (midi in soundingRange) buildColumn(midi) else DoubleArray(CQT_BINS)
    }

    /**
     * `1 / L` where `L` is the largest eigenvalue of `E^T E` - the largest step FISTA can take
     * and still converge.
     *
     * Estimated by power iteration rather than bounded by the Frobenius norm: the columns are
     * strongly correlated (neighbouring notes share partials), so the Frobenius bound is several
     * times too pessimistic, and a step that small leaves visible residual on the octave above a
     * note after sixty iterations.
     */
    private val stepSize: Double = run {
        var v = DoubleArray(NOTE_COUNT) { 1.0 / NOTE_COUNT }
        var eigenvalue = 1.0
        repeat(POWER_ITERATIONS) {
            val projected = DoubleArray(CQT_BINS)
            for (note in 0 until NOTE_COUNT) {
                val column = dictionary[note]
                val weight = v[note]
                for (bin in 0 until CQT_BINS) projected[bin] += weight * column[bin]
            }
            val next = DoubleArray(NOTE_COUNT)
            for (note in 0 until NOTE_COUNT) {
                val column = dictionary[note]
                var acc = 0.0
                for (bin in 0 until CQT_BINS) acc += column[bin] * projected[bin]
                next[note] = acc
            }
            var norm = 0.0
            for (value in next) norm += value * value
            norm = sqrt(norm)
            if (norm <= 0.0) return@repeat
            eigenvalue = norm
            v = DoubleArray(NOTE_COUNT) { next[it] / norm }
        }
        if (eigenvalue > 0.0) STEP_MARGIN / eigenvalue else 1.0
    }

    /** One dictionary column, exposed so tests can build an observation the solver must invert. */
    internal fun column(midi: Int): DoubleArray = dictionary[midi - LOWEST_NOTE_MIDI]

    /**
     * Removes the instrument's spectral envelope so a bright guitar and a dull piano look alike
     * (Mauch's standardisation step). Without it the fit is dominated by the loudest region of
     * the spectrum rather than by which notes are present.
     */
    fun subtractBackground(magnitudes: DoubleArray): DoubleArray {
        require(magnitudes.size == CQT_BINS) { "expected $CQT_BINS bins" }
        val logMagnitude = DoubleArray(CQT_BINS) { ln(1.0 + magnitudes[it]) }
        val out = DoubleArray(CQT_BINS)
        for (i in 0 until CQT_BINS) {
            val from = (i - BACKGROUND_HALF_WIDTH).coerceAtLeast(0)
            val to = (i + BACKGROUND_HALF_WIDTH).coerceAtMost(CQT_BINS - 1)
            var mean = 0.0
            for (k in from..to) mean += logMagnitude[k]
            mean /= (to - from + 1)
            out[i] = (logMagnitude[i] - mean).coerceAtLeast(0.0)
        }
        return out
    }

    /**
     * Solves the non-negative least squares problem with FISTA and a non-negativity projection.
     *
     * Projected gradient rather than Lawson-Hanson active set: bounded, predictable runtime and
     * no pivoting logic. The solution does not need to be exact - it is thresholded immediately
     * afterwards.
     *
     * Returns [NOTE_COUNT] saliences for MIDI [LOWEST_NOTE_MIDI] upwards, normalised so the
     * largest is 1 (or all-zero if the input was silent).
     */
    fun solve(observation: DoubleArray): DoubleArray = fit(observation).salience

    /**
     * [solve], plus the two quantities normalisation throws away: how much of the observation
     * the fit actually accounts for, and how strong the strongest note was before scaling.
     *
     * Both come free - the residual is already formed on every FISTA iteration - and both are
     * needed by [tonalityOf], because a fit alone says nothing about whether it was worth making.
     */
    fun fit(observation: DoubleArray): SalienceFit {
        require(observation.size == CQT_BINS) { "expected $CQT_BINS bins" }
        val y = runFista(observation)
        return normalizeFit(y, observation)
    }

    private fun runFista(observation: DoubleArray): DoubleArray {
        val y = DoubleArray(NOTE_COUNT)
        val z = DoubleArray(NOTE_COUNT)
        val gradient = DoubleArray(NOTE_COUNT)
        val residual = DoubleArray(CQT_BINS)
        var momentum = 1.0

        repeat(ITERATIONS) {
            formResidual(z, observation, residual)
            accumulateGradient(residual, gradient)
            momentum = advanceWeights(y, z, gradient, momentum)
        }
        return y
    }

    private fun formResidual(weights: DoubleArray, observation: DoubleArray, residual: DoubleArray) {
        residual.fill(0.0)
        for (note in 0 until NOTE_COUNT) {
            val weight = weights[note]
            if (weight == 0.0) continue
            val column = dictionary[note]
            for (bin in 0 until CQT_BINS) residual[bin] += weight * column[bin]
        }
        for (bin in 0 until CQT_BINS) residual[bin] -= observation[bin]
    }

    private fun accumulateGradient(residual: DoubleArray, gradient: DoubleArray) {
        for (note in 0 until NOTE_COUNT) {
            val column = dictionary[note]
            var acc = 0.0
            for (bin in 0 until CQT_BINS) acc += column[bin] * residual[bin]
            gradient[note] = acc
        }
    }

    private fun advanceWeights(
        y: DoubleArray,
        z: DoubleArray,
        gradient: DoubleArray,
        momentum: Double,
    ): Double {
        val nextMomentum = (1.0 + sqrt(1.0 + 4.0 * momentum * momentum)) / 2.0
        val blend = (momentum - 1.0) / nextMomentum
        for (note in 0 until NOTE_COUNT) {
            val updated = (z[note] - stepSize * gradient[note]).coerceAtLeast(0.0)
            z[note] = updated + blend * (updated - y[note])
            y[note] = updated
        }
        return nextMomentum
    }

    private fun normalizeFit(y: DoubleArray, observation: DoubleArray): SalienceFit {
        // The loop above leaves a residual for the extrapolated point z, which is not the
        // returned solution. Re-form it for y so the reported fit describes the answer.
        val residual = DoubleArray(CQT_BINS)
        formModelOnly(y, residual)
        val explained = explainedVariance(residual, observation)

        val peak = y.max()
        if (peak <= 0.0) return SalienceFit(y, 0.0, explained)
        for (note in 0 until NOTE_COUNT) y[note] /= peak
        return SalienceFit(y, peak, explained)
    }

    private fun formModelOnly(weights: DoubleArray, residual: DoubleArray) {
        residual.fill(0.0)
        for (note in 0 until NOTE_COUNT) {
            val weight = weights[note]
            if (weight == 0.0) continue
            val column = dictionary[note]
            for (bin in 0 until CQT_BINS) residual[bin] += weight * column[bin]
        }
    }

    private fun explainedVariance(model: DoubleArray, observation: DoubleArray): Double {
        var residualEnergy = 0.0
        var observedEnergy = 0.0
        for (bin in 0 until CQT_BINS) {
            val error = model[bin] - observation[bin]
            residualEnergy += error * error
            observedEnergy += observation[bin] * observation[bin]
        }
        return when {
            observedEnergy > 0.0 -> (1.0 - residualEnergy / observedEnergy).coerceIn(0.0, 1.0)
            else -> 0.0
        }
    }

    /**
     * One dictionary column: a single note's expected log-frequency pattern, harmonics included.
     *
     * Each partial is spread with a Gaussian standing in for the CQT kernel's own magnitude
     * response, so the dictionary and the observation are smeared alike. That is an
     * approximation of Mauch's exact convolution; it is the parameter most worth revisiting if
     * the measured accuracy comes in low.
     */
    private fun buildColumn(midi: Int): DoubleArray {
        val column = DoubleArray(CQT_BINS)
        val fundamental = CQT_MIN_HZ * 2.0.pow((midi - LOWEST_NOTE_MIDI) / 12.0)
        // A note whose fundamental is below the observed range is not evidence-backed at all: its
        // only support would be partials, which is exactly the octave-below phantom soundingRange
        // exists to prevent.
        if (cqtBinOf(fundamental) < firstBin) return column
        for (index in partials.indices) {
            val amplitude = partials[index]
            if (amplitude <= 0.0) continue
            val bin = cqtBinOf(fundamental * (index + 1))
            if (bin < -SPREAD_BINS || bin > CQT_BINS - 1 + SPREAD_BINS) continue
            val from = (bin - SPREAD_BINS).roundToInt().coerceAtLeast(0)
            val to = (bin + SPREAD_BINS).roundToInt().coerceAtMost(CQT_BINS - 1)
            for (k in from..to) {
                val distance = (k - bin) / SPREAD_SIGMA
                column[k] += amplitude * exp(-0.5 * distance * distance)
            }
        }
        for (k in 0 until firstBin) column[k] = 0.0
        // Normalised after masking, so a column the boundary clips is not quietly weaker than its
        // neighbours and under-picked for it.
        var norm = 0.0
        for (v in column) norm += v * v
        norm = sqrt(norm)
        if (norm > 0.0) for (i in column.indices) column[i] /= norm
        return column
    }

    /** Shared constants and helpers for NoteSalience. */
    companion object {
        /** Every note the CQT covers: C2..B6. */
        val FULL_RANGE: IntRange = LOWEST_NOTE_MIDI until (LOWEST_NOTE_MIDI + NOTE_COUNT)

        /** One octave either side, in bins. */
        private const val BACKGROUND_HALF_WIDTH = CQT_BINS_PER_OCTAVE

        private const val SPREAD_SIGMA = 1.0
        private const val SPREAD_BINS = 3.0

        private const val ITERATIONS = 60

        /** Enough for the dominant eigenvalue; it is computed once at construction. */
        private const val POWER_ITERATIONS = 40

        /** Stay just inside the convergence bound rather than exactly on it. */
        private const val STEP_MARGIN = 0.98
    }
}

/**
 * One deconvolution and the evidence for whether it was worth making.
 *
 * NNLS is a fitting procedure, so it returns an answer for any input at all - hand it room hum
 * and it still reports pitch classes. [explained] and [peak] are what distinguish "these notes
 * are sounding" from "this is the closest the dictionary could get to noise".
 */
class SalienceFit(
    /** Saliences for MIDI [LOWEST_NOTE_MIDI] upwards, normalised so the largest is 1. */
    val salience: DoubleArray,
    /** The largest salience before normalisation, in the observation's own units. */
    val peak: Double,
    /** Fraction of the observation's energy the fitted dictionary accounts for, 0..1. */
    val explained: Double,
)

/** A note the chord pipeline believes is sounding, with its octave kept. */
data class DetectedNote(val midi: Int, val salience: Double)

/**
 * Picks notes out of a salience vector (TUNER_SPEC C.2 step 4).
 *
 * Both an absolute floor (rejects noise) and a relative one (rejects weak residuals), capped at
 * six notes. Octaves are kept: the piano display and the bass-note logic both need them.
 */
fun pickNotes(
    salience: DoubleArray,
    absoluteFloor: Double = ABSOLUTE_FLOOR,
    relativeFloor: Double = RELATIVE_FLOOR,
): List<DetectedNote> {
    val peak = salience.max()
    if (peak <= 0.0) return emptyList()
    return salience.indices
        .filter { salience[it] > absoluteFloor && salience[it] > relativeFloor * peak }
        .map { DetectedNote(LOWEST_NOTE_MIDI + it, salience[it]) }
        .sortedByDescending { it.salience }
        .take(MAX_NOTES)
        .sortedBy { it.midi }
}

/**
 * The lowest picked note in the bottom octave and a half, or `null` when two candidates are too
 * close to call.
 *
 * A wrong bass note produces a confidently wrong slash chord, which is worse than no slash
 * chord at all - so ambiguity here reports nothing rather than a guess.
 */
fun bassNoteOf(notes: List<DetectedNote>): DetectedNote? {
    val candidates = notes.filter {
        it.midi <= BASS_CEILING_MIDI && it.salience > BASS_FLOOR
    }.sortedBy { it.midi }
    val lowest = candidates.firstOrNull() ?: return null
    val next = candidates.getOrNull(1) ?: return lowest
    val relative = abs(lowest.salience - next.salience) / maxOf(lowest.salience, next.salience)
    return if (relative < BASS_AMBIGUITY) null else lowest
}

private const val ABSOLUTE_FLOOR = 0.12

/**
 * Measured against Record-3 (real ukulele, Pixel 8 mic, tuning offset compensated): at the
 * original 0.25 a strum yielded 1.44 notes a frame, 84% of passing frames were down to one or
 * two notes, and only 43% of the chord's pitch classes survived. The absolute floor turned out
 * not to bind at all - sweeping it from 0.12 to 0.04 changed nothing until the relative floor
 * was already loose - so this is the one that was throwing the strum away.
 *
 * 0.14 is the knee: 1.95 notes a frame, 70% degraded, 53% recall, for 0.41 spurious tones a
 * frame against 0.18. Below it recall buys ~3 points per 0.2 extra spurious tones, which
 * `EXTRA_TONE_PENALTY` then spends on wrong names.
 */
private const val RELATIVE_FLOOR = 0.14
private const val MAX_NOTES = 6
private const val BASS_FLOOR = 0.20
private const val BASS_AMBIGUITY = 0.20

/** F3 - the top of the bottom octave and a half of the CQT range. */
private const val BASS_CEILING_MIDI = 53
