package com.vayunmathur.tuner.domain

import com.vayunmathur.tuner.data.DEFAULT_PARTIALS
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * The note extractor behind [ChordExtractor.SIEVE], and the source of the displayed note set
 * under [ChordExtractor.CHROMA_HMM]: FFT, score only the pitches the instrument can sound, take
 * the strongest, subtract its measured partials, repeat.
 *
 * It no longer decides chord names by default. Naming from its note set needed every pitch class
 * in a single frame and scored 14/145 on Record-3; scoring chords directly against chroma with
 * HMM smoothing scores 42/145 on the same recording (see `RealAudioHarnessTest`).
 *
 * This replaced NNLS as the primary extractor on measured evidence, not on preference. The
 * argument for the deconvolution is that a note's overtone series is itself a chord, so a plain
 * chromagram of one low C already looks like C7 and something has to undo that. The measurement
 * (`RealAudioHarness` against Record-3, a real ukulele) is that on this instrument the series is
 * 1.00, 0.18, 0.07 and then noise - so the ambiguity the deconvolution exists to resolve is
 * mostly not present, and a greedy sieve over a closed set of candidate pitches does better:
 *
 * ```
 *                                 frames named   named correctly   notes / tonal frame
 * NNLS, 1/n dictionary             2 (1.4%)       1 (0.7%)          1.89
 * NNLS, measured partials          7 (4.8%)       6 (4.1%)          2.34
 * this, six picks                 13 (9.0%)      11 (7.6%)          3.39
 * this, picks = string count      16 (11.0%)     14 (9.7%)          2.80
 * ```
 *
 * Read those with care: **notes per frame is the wrong denominator** and reading it as progress
 * is a mistake this file used to make. `ChordNaming` scores *distinct pitch classes*, and on a
 * re-entrant ukulele C4 and C5 are two notes and one pitch class - so "3.39 notes is essentially
 * a full triad" was never a valid inference, and indeed the best row here has *fewer* notes per
 * frame than the row above it. Measured in pitch classes, six picks on a four-string instrument
 * produced 5 or 6 distinct classes on 13 of 46 tonal frames, which a ukulele cannot physically
 * sound: the extra classes are residue the partial subtraction did not remove, and each one
 * costs `EXTRA_TONE_PENALTY` at the scorer. Capping picks at the instrument's string count
 * removes every 5pc and 6pc frame and names three more.
 *
 * The cap comes from `Instrument.polyphony`, not from a swept constant. Sweeping picks over
 * 3..6 against Record-3 puts the optimum at exactly 4 - the ukulele's string count - which is
 * the instrument model already knowing the answer rather than a number that was tuned.
 *
 * [RELATIVE_FLOOR] was swept over 0.04..0.30 at the same time and, once picks are capped, moves
 * frames between `SingleNote` and `Interval` without changing frames-named at all. It is not
 * where the remaining loss is.
 *
 * The 17 frames still yielding a single pitch class are quiet ones: bucketing frame RMS by
 * pitch-class count on Record-3 gives a median of -24.3 dBFS at 1pc against -14.9 at 5pc, with
 * no overlap at all between the two - every 1pc frame is quieter than every 5pc frame. They are
 * decay tails, and lowering the floor into them produces intervals rather than triads, which is
 * what the sweep shows. Whatever recovers them is not a threshold on this frame.
 *
 * `ChordAccuracyTest` says the opposite of all of this and is not evidence: its corpus is
 * generated from the same harmonic model the NNLS dictionary assumes, so it measures whether
 * the solver can invert a spectrum it effectively constructed. It is a regression guard, and
 * it keeps the uncapped default so that it stays one.
 *
 * Why a *closed candidate set* matters as much as the sieve: scoring only the notes the chosen
 * instrument can physically sound removes the octave-below phantom outright. A hypothesis one
 * octave under a real note explains that note's fundamental with its own second partial, and on
 * a ukulele - lowest note C4 - a phantom C3 eats the G of a C chord while never showing up as a
 * spurious tone, because it shares the root's pitch class.
 *
 * Not thread-safe and not stateless across instruments: call [setInstrument] rather than
 * rebuilding, and drive it from one analysis coroutine.
 */
class HarmonicSieve(
    private val sampleRate: Double,
    soundingRange: IntRange = NoteSalience.FULL_RANGE,
    partials: List<Double> = DEFAULT_PARTIALS,
    polyphony: Int = MAX_NOTES,
    /**
     * Fraction of the frame's own strongest pick below which a pick is dropped.
     *
     * A constructor parameter rather than a constant so `RealAudioHarnessTest` can sweep it
     * against the recording. The synthetic corpus cannot say where it belongs, because it is
     * generated with every note at the same amplitude and a real strum is not.
     */
    private val relativeFloor: Double = RELATIVE_FLOOR,
) {
    private val fft = Fft(FFT_SIZE)
    private val window = DoubleArray(FFT_SIZE) { 0.5 - 0.5 * cos(2.0 * PI * it / FFT_SIZE) }

    /**
     * Scales the raw transform so a sinusoid of amplitude `A` peaks at about `A`.
     *
     * Without it every magnitude here is proportional to [FFT_SIZE] and to the window's sum, so
     * [SILENCE_FLOOR] would be a number with no physical meaning that silently changes if either
     * is touched.
     */
    private val gain = 4.0 / FFT_SIZE

    private var candidates: List<Int> = soundingRange.toList()
    private var profile: List<Double> = partials
    private var range: IntRange = soundingRange

    /**
     * How many notes the greedy loop is allowed to take, capped at what the instrument can sound.
     *
     * A ukulele has four strings, so a fifth or sixth pick is provably not a note - it is
     * residue the partial subtraction failed to remove, and it costs a name because
     * `ChordNaming` charges `EXTRA_TONE_PENALTY` for every pitch class outside the chord. This
     * is the one bound here that comes from the instrument rather than from the signal.
     */
    private var picks: Int = polyphony.coerceIn(1, MAX_NOTES)

    /** Samples of audio one call to [notes] consumes. */
    val requiredSamples: Int = FFT_SIZE

    /** The strongest peak in the last analysed frame, in signal-amplitude units. */
    var peakAmplitude: Double = 0.0
        private set

    /** Narrows the candidate set and re-reads the partial profile. See `Instrument.partials`. */
    fun setInstrument(soundingRange: IntRange, partials: List<Double>, polyphony: Int = MAX_NOTES) {
        val capped = polyphony.coerceIn(1, MAX_NOTES)
        if (soundingRange == range && partials == profile && capped == picks) return
        range = soundingRange
        profile = partials
        picks = capped
        candidates = soundingRange.toList()
    }

    /**
     * The notes sounding in the last [requiredSamples] of [samples].
     *
     * [tuningCents] shifts the whole candidate grid, so an instrument that is flat is measured
     * where its notes actually are rather than penalised for being between bins. See
     * [TuningOffset] for where the number comes from and why its sign is a judgement call.
     *
     * Saliences are normalised so the strongest is 1, matching what `pickNotes` returns from the
     * NNLS path - everything downstream is written against that convention.
     */
    fun notes(samples: DoubleArray, tuningCents: Double): List<DetectedNote> {
        val magnitudes = spectrum(samples)
        val ceiling = magnitudes.max()
        peakAmplitude = ceiling
        if (ceiling <= 0.0) return emptyList()

        val picked = ArrayList<DetectedNote>(picks)
        repeat(picks) {
            var bestMidi = -1
            var bestScore = 0.0
            for (midi in candidates) {
                val value = score(magnitudes, midi, tuningCents)
                if (value > bestScore) {
                    bestScore = value
                    bestMidi = midi
                }
            }
            if (bestMidi < 0) return@repeat
            val fundamental = frequencyOf(bestMidi, tuningCents)
            val amplitude = peakNear(magnitudes, fundamental)
            if (amplitude <= 0.0) return@repeat
            picked += DetectedNote(bestMidi, amplitude)
            subtractPartials(magnitudes, fundamental, amplitude)
        }
        if (picked.isEmpty()) return emptyList()

        val peak = picked.maxOf { it.salience }
        return picked
            .filter { it.salience > relativeFloor * peak }
            .map { DetectedNote(it.midi, it.salience / peak) }
            .sortedBy { it.midi }
    }

    private fun frequencyOf(midi: Int, tuningCents: Double) =
        440.0 * 2.0.pow((midi - 69) / 12.0) * 2.0.pow(tuningCents / 1200.0)

    private fun spectrum(samples: DoubleArray): DoubleArray {
        require(samples.size >= FFT_SIZE) { "need at least $FFT_SIZE samples" }
        val re = DoubleArray(FFT_SIZE)
        val im = DoubleArray(FFT_SIZE)
        val start = samples.size - FFT_SIZE
        for (i in 0 until FFT_SIZE) re[i] = samples[start + i] * window[i]
        fft.forward(re, im)
        return DoubleArray(FFT_SIZE / 2) { gain * sqrt(re[it] * re[it] + im[it] * im[it]) }
    }

    /** Strongest bin within [TOLERANCE_SEMITONES] of [hz]. */
    private fun peakNear(magnitudes: DoubleArray, hz: Double): Double {
        val (from, to) = binsAround(magnitudes.size, hz)
        if (from > to) return 0.0
        var best = 0.0
        for (k in from..to) if (magnitudes[k] > best) best = magnitudes[k]
        return best
    }

    private fun binsAround(binCount: Int, hz: Double): Pair<Int, Int> {
        val spread = 2.0.pow(TOLERANCE_SEMITONES / 12.0)
        val binHz = sampleRate / FFT_SIZE
        val from = (hz / spread / binHz).toInt().coerceAtLeast(1)
        val to = (hz * spread / binHz).roundToInt().coerceAtMost(binCount - 1)
        return from to to
    }

    private fun score(magnitudes: DoubleArray, midi: Int, tuningCents: Double): Double {
        val fundamental = frequencyOf(midi, tuningCents)
        var total = 0.0
        for (n in profile.indices) {
            val weight = profile[n]
            if (weight <= 0.0) continue
            total += weight * peakNear(magnitudes, fundamental * (n + 1))
        }
        return total
    }

    /** Removes what a picked note is claiming, at the strength it was actually measured at. */
    private fun subtractPartials(magnitudes: DoubleArray, fundamental: Double, amplitude: Double) {
        for (n in profile.indices) {
            val (from, to) = binsAround(magnitudes.size, fundamental * (n + 1))
            for (k in from..to) {
                magnitudes[k] = (magnitudes[k] - profile[n] * amplitude).coerceAtLeast(0.0)
            }
        }
    }

    companion object {
        /** 341 ms at 48 kHz: 2.93 Hz a bin, against 15.5 Hz between semitones at C4. */
        const val FFT_SIZE: Int = 16384

        /**
         * Half-width of the peak search, in semitones.
         *
         * Wide enough to absorb the residual mistuning [TuningOffset] does not remove and the
         * inharmonicity of a real string, narrow enough that two adjacent semitones cannot claim
         * the same bin.
         */
        const val TOLERANCE_SEMITONES: Double = 0.35

        /** Drops notes far weaker than the strongest, mirroring `pickNotes`'s relative floor. */
        const val RELATIVE_FLOOR: Double = 0.14

        /** Hard ceiling on picks, regardless of instrument; more than this is residue. */
        const val MAX_NOTES: Int = 6

        /**
         * Absolute floor on the strongest peak, in signal-amplitude units - about -80 dBFS.
         *
         * The one criterion here that is not a ratio. Everything else normalises by the frame's
         * own peak, and a relative criterion always crowns a winner: normalise a frame of
         * near-silence and its loudest accident becomes a salience of 1. Set an order of
         * magnitude below the quietest strum on Record-3 rather than at the noise floor, so it
         * rejects silence without ever being the reason a quiet chord went unnamed.
         */
        const val SILENCE_FLOOR: Double = 1e-4
    }
}
