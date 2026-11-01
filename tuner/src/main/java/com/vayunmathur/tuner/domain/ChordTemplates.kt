package com.vayunmathur.tuner.domain

import com.vayunmathur.tuner.data.Instrument
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** What one HMM state stands for. Chords first, then bare pitch classes, then silence. */
internal sealed interface ChordState {
    data class Named(val name: ChordName) : ChordState
    data class Note(val pitchClass: Int) : ChordState
    data object NoChord : ChordState
}

/**
 * Per-frame evidence for every [ChordState], scored directly against [ChromaFrame] rather than
 * against a picked note set.
 *
 * Two views of the same frame, summed:
 * - a pitch-class template per chord with the instrument's partials folded in, so the octave and
 *   twelfth of each tone are expected rather than read as extra notes;
 * - on a fretted instrument, the best fit over the shapes [VoicingGenerator] says are playable,
 *   rendered at their real pitches. That brings in register and re-entrancy - a ukulele F sounds
 *   C lowest - without trusting pitch order, and gives no credit to voicings no hand can make.
 *
 * Bare-note states exist so a single plucked string is not read as the major triad its own
 * overtones resemble.
 */
internal class ChordTemplates(partials: List<Double>, instrument: Instrument?) {
    val states: List<ChordState> = buildList {
        for (root in 0..11) for (quality in ChordQuality.entries) add(ChordState.Named(ChordName(root, quality)))
        for (pitchClass in 0..11) add(ChordState.Note(pitchClass))
        add(ChordState.NoChord)
    }
    val noChord: Int = states.lastIndex

    private val harmonics: List<Pair<Int, Double>> = partials.take(MAX_HARMONICS).mapIndexedNotNull { index, amplitude ->
        if (amplitude <= 0.0) return@mapIndexedNotNull null
        val h = index + 1
        (12.0 * ln(h.toDouble()) / ln(2.0)).roundToInt() to (amplitude / partials[0]).pow(HARMONIC_EXPONENT)
    }

    private val chroma: Array<DoubleArray> = Array(noChord) { pitchChroma(pitchClassesOf(states[it])) }

    private val shapes: Array<List<DoubleArray>> = Array(noChord) { shapesFor(states[it], instrument) }
    private val useShapes = shapes.any { it.isNotEmpty() }

    /** Fills [out] with one log-likelihood per state. */
    fun logLikelihoods(frame: ChromaFrame, firstNote: Int, bassPitchClass: Int?, out: DoubleArray) {
        val frameNorm = norm(frame.semitones, firstNote)
        val trebleMax = frame.treble.max()
        for (s in 0 until noChord) {
            var score = dot(frame.treble, chroma[s])
            if (useShapes) {
                var best = 0.0
                if (frameNorm > 0.0) {
                    for (shape in shapes[s]) {
                        val shapeNorm = norm(shape, firstNote)
                        if (shapeNorm <= 0.0) continue
                        val fit = dot(frame.semitones, shape) / (frameNorm * shapeNorm)
                        if (fit > best) best = fit
                    }
                }
                score = CHROMA_WEIGHT * score + (1 - CHROMA_WEIGHT) * best
            }
            when (val state = states[s]) {
                is ChordState.Named -> {
                    // Tier B': a false Caug on a beginner's C is far worse than a missed real one.
                    val strict = state.name.quality.strictInterval
                    if (strict != null &&
                        frame.treble[pitchClassOf(state.name.root + strict)] <= ChordNaming.STRICT_TONE * trebleMax
                    ) {
                        score = 0.0
                    }
                    score -= COMPLEXITY_COST * state.name.quality.complexity
                    if (bassPitchClass == state.name.root) score += BASS_BONUS
                }
                is ChordState.Note -> if (bassPitchClass == state.pitchClass) score += BASS_BONUS
                ChordState.NoChord -> Unit
            }
            out[s] = SHARPNESS * score
        }
        out[noChord] = SHARPNESS * NO_CHORD_SCORE
    }

    private fun pitchClassesOf(state: ChordState): Set<Int> = when (state) {
        is ChordState.Named -> state.name.pitchClasses
        is ChordState.Note -> setOf(state.pitchClass)
        ChordState.NoChord -> emptySet()
    }

    private fun pitchChroma(pitchClasses: Set<Int>): DoubleArray {
        val out = DoubleArray(12)
        for (pc in pitchClasses) for ((offset, weight) in harmonics) out[pitchClassOf(pc + offset)] += weight
        Chroma.normalise(out)
        return out
    }

    private fun shapesFor(state: ChordState, instrument: Instrument?): List<DoubleArray> {
        if (instrument == null || instrument.strings.isEmpty()) return emptyList()
        return when (state) {
            is ChordState.Named -> VoicingGenerator.generate(instrument, state.name).map { render(it.soundingMidi) }
            is ChordState.Note -> instrument.soundingRange.filter { pitchClassOf(it) == state.pitchClass }.map { render(listOf(it)) }
            ChordState.NoChord -> emptyList()
        }
    }

    private fun render(midis: List<Int>): DoubleArray {
        val out = DoubleArray(NOTE_COUNT)
        for (midi in midis) {
            for ((offset, weight) in harmonics) {
                val n = midi + offset - LOWEST_NOTE_MIDI
                if (n in 0 until NOTE_COUNT) out[n] += weight
            }
        }
        return out
    }

    private fun dot(a: DoubleArray, b: DoubleArray): Double {
        var sum = 0.0
        for (i in a.indices) sum += a[i] * b[i]
        return sum
    }

    private fun norm(values: DoubleArray, from: Int): Double {
        var sum = 0.0
        for (i in from until values.size) sum += values[i] * values[i]
        return sqrt(sum)
    }

    private companion object {
        /** Past the eighth partial the folded pitch classes are mostly noise. */
        private const val MAX_HARMONICS = 8

        /**
         * Partial weights are flattened because the chroma is log-compressed: a partial at 0.18 of
         * the fundamental reads as far more than 0.18 of it after compression.
         *
         * This and the constants below were swept together against Record-3 and the synthetic
         * progressions in `ChordProgressionEvalTest`; every neighbouring value moved either by
         * about two points, so none of them is finely balanced.
         */
        private const val HARMONIC_EXPONENT = 0.75

        private const val CHROMA_WEIGHT = 0.5
        private const val COMPLEXITY_COST = 0.03
        private const val BASS_BONUS = 0.06

        /** A tonal frame whose best template fits worse than this is better explained as no chord. */
        private const val NO_CHORD_SCORE = 0.55

        /** Converts a cosine score to a log-likelihood; higher makes each frame more decisive. */
        private const val SHARPNESS = 30.0
    }
}
