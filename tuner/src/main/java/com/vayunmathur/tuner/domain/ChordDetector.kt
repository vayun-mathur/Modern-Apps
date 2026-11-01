package com.vayunmathur.tuner.domain

import com.vayunmathur.tuner.data.DEFAULT_PARTIALS
import com.vayunmathur.tuner.data.Instrument

/** Which extractor the Chord tab runs. See [ChordDetector]. */
enum class ChordExtractor {
    /**
     * Chords scored directly against chroma and playable voicings, smoothed by an HMM - no note
     * set is committed to before naming. The default; see [ChordTemplates] and [ChordHmm].
     */
    CHROMA_HMM,

    /**
     * Greedy harmonic sieve over a closed candidate set, named by [ChordNaming]. Still runs under
     * [CHROMA_HMM] to supply the displayed note set. See [HarmonicSieve] for its numbers.
     */
    SIEVE,

    /**
     * NNLS deconvolution against a dictionary of harmonic combs.
     *
     * Kept because it is the principled method and the comparison has to stay re-runnable, not
     * because it is a fallback: it lost on real audio by 2.2x on frames-named-correctly and
     * nothing selects it at runtime.
     */
    NNLS,
}

/**
 * The Chord tab's pipeline: CQT, a tonality gate, then either direct chord scoring with HMM
 * smoothing ([ChordExtractor.CHROMA_HMM], the default) or note extraction, temporal
 * stabilisation and naming (TUNER_SPEC C.2).
 *
 * The note-first paths lost on real audio because a name needed the exact note set in a single
 * frame: the sieve named 14 of 145 Record-3 frames correctly, NNLS none. They stay reachable
 * through [extractor] so `RealAudioHarnessTest` keeps measuring the comparison.
 *
 * The CQT is still computed on every frame even under the sieve, because two things other than
 * extraction need it: the [Tonality] gate reads spectral flatness off the raw frame, and
 * [TuningOffset] reads the instrument's mistuning off the same bins. Both are one pass over 180
 * bins and neither needs a second transform.
 *
 * The two-tier design is what keeps the tab responsive. The 786 ms kernel applies only to the
 * bottom octave, so a [ChordTier.PROVISIONAL] pass over the top three octaves can name a chord
 * quality about 150 ms after the strum - with no bass note, and therefore no slash chords - and
 * the [ChordTier.CONFIRMED] pass firms it up once the full window is valid.
 *
 * Not thread-safe: it carries frame history. Call it from one analysis coroutine.
 */
class ChordDetector(
    sampleRate: Double,
    soundingRange: IntRange = NoteSalience.FULL_RANGE,
    partials: List<Double> = DEFAULT_PARTIALS,
    private val extractor: ChordExtractor = ChordExtractor.CHROMA_HMM,
    /**
     * The tonality gate's flatness threshold. Exposed so `RealAudioHarnessTest` can sweep it
     * against real audio rather than against the synthetic corpus, which cannot say anything
     * useful about where it belongs.
     */
    private val maxFlatness: Double =
        if (extractor == ChordExtractor.CHROMA_HMM) Tonality.CHROMA_MAX_FLATNESS else Tonality.MAX_FLATNESS,
    polyphony: Int = HarmonicSieve.MAX_NOTES,
    relativeFloor: Double = HarmonicSieve.RELATIVE_FLOOR,
    /** Supplies the playable voicings [ChordExtractor.CHROMA_HMM] scores against; null for none. */
    instrument: Instrument? = null,
) {
    private val constantQ = ConstantQ(sampleRate)
    private val sieve = HarmonicSieve(sampleRate, soundingRange, partials, polyphony, relativeFloor)
    private val tuning = TuningOffset()
    private var confirmed = NoteSalience(soundingRange, partials)
    private var provisional = NoteSalience(soundingRange, partials, PROVISIONAL_FIRST_BIN)
    private var profile = partials
    private var voicingSource = instrument
    private val history = ArrayDeque<Set<Int>>()

    /** Built on first use rather than in [setInstrument], which the UI calls on the main thread. */
    private var templates: ChordTemplates? = null
    private var hmm: ChordHmm? = null
    private var evidence = DoubleArray(0)

    /**
     * Narrows the analysis to what [instrument] can sound and rebuilds everything derived from its
     * measured partials and voicings.
     *
     * A setter rather than a new detector: the CQT kernel bank and [requiredSamples] do not depend
     * on the instrument, and the caller's capture buffer is sized from the latter.
     */
    fun setInstrument(instrument: Instrument) {
        if (instrument == voicingSource) return
        val range = instrument.soundingRange
        val partials = instrument.partials
        val polyphony = instrument.polyphony
        profile = partials
        voicingSource = instrument
        sieve.setInstrument(range, partials, polyphony)
        confirmed = NoteSalience(range, partials)
        provisional = NoteSalience(range, partials, PROVISIONAL_FIRST_BIN)
        templates = null
        hmm = null
        reset()
    }

    /** Samples of 48 kHz audio one hop consumes. */
    val requiredSamples: Int get() = constantQ.requiredSamples

    /** The mistuning the sieve is currently compensating for, in cents. See [TuningOffset]. */
    val tuningCents: Double get() = tuning.cents

    /** Drops frame history and the tuning estimate. Call when capture restarts. */
    fun reset() {
        history.clear()
        tuning.reset()
        hmm?.reset()
    }

    /**
     * Runs one hop and names what it found.
     *
     * [bassInformsRoot] is false for a re-entrant instrument, where the lowest sounding pitch is
     * routinely not the root (see `Instrument.reentrant`). It suppresses the bass rather than
     * letting a wrong one through: on a ukulele the lowest note of an F chord is C, so a bass
     * taken from pitch order would hard-gate the tier-B vocabulary onto the wrong root and
     * produce confidently wrong slash chords.
     *
     * Note that the bass search stops at G3 (F3 on the note-first paths), so on a ukulele - whose
     * lowest possible note is C4 - this changes nothing yet. It is here so that raising that
     * ceiling cannot silently break the re-entrant case.
     */
    fun analyse(
        samples: DoubleArray,
        tier: ChordTier,
        bassInformsRoot: Boolean = true,
    ): ChordReading {
        val magnitudes = constantQ.magnitudes(samples)
        tuning.observe(magnitudes)
        val firstBin = if (tier == ChordTier.PROVISIONAL) PROVISIONAL_FIRST_BIN else 0
        if (extractor == ChordExtractor.CHROMA_HMM) {
            return chromaReading(magnitudes, samples, tier, firstBin, bassInformsRoot)
        }
        val picked = when (extractor) {
            ChordExtractor.SIEVE, ChordExtractor.CHROMA_HMM -> sieveNotes(magnitudes, samples, firstBin)
            ChordExtractor.NNLS -> nnlsNotes(magnitudes, tier, firstBin)
        } ?: run {
            history.clear()
            return silence(tier)
        }

        val stable = stabilise(picked)
        if (stable.isEmpty()) return silence(tier)

        val bass = if (tier == ChordTier.CONFIRMED && bassInformsRoot) bassNoteOf(stable) else null
        return ChordNaming.name(stable, bass, tier)
    }

    /**
     * Scores every chord against this frame's chroma, folds that into the HMM, and reads the
     * posterior back out under the same honesty rules [ChordNaming] enforces.
     *
     * The sieve still runs, but only for [ChordReading.notes]: the display has to show what was
     * heard, and a voicing is a suggestion, never a measurement. A gated frame returns silence as
     * before but does not wipe the HMM - it pushes it towards no-chord, so a single gate dropout
     * in a decay costs one frame of evidence rather than the whole strum.
     */
    private fun chromaReading(
        magnitudes: DoubleArray,
        samples: DoubleArray,
        tier: ChordTier,
        firstBin: Int,
        bassInformsRoot: Boolean,
    ): ChordReading {
        val bank = templates ?: ChordTemplates(profile, voicingSource).also {
            templates = it
            evidence = DoubleArray(it.states.size)
        }
        val model = hmm ?: ChordHmm(bank.states.size).also { hmm = it }

        val picked = sieveNotes(magnitudes, samples, firstBin)
        if (picked == null) {
            history.clear()
            evidence.fill(-SILENCE_EVIDENCE)
            evidence[bank.noChord] = 0.0
            model.update(evidence)
            return silence(tier)
        }
        val notes = stabilise(picked).ifEmpty { picked }

        val withBass = tier == ChordTier.CONFIRMED && bassInformsRoot
        val frame = Chroma.of(magnitudes, tuning.cents, firstBin, withBass)
        val bass = frame.bassMidi?.let { pitchClassOf(it) }
        bank.logLikelihoods(frame, firstBin / BINS_PER_SEMITONE, bass, evidence)
        model.update(evidence)
        return interpret(bank, model.posterior, notes, frame, bass, tier)
    }

    private fun interpret(
        bank: ChordTemplates,
        posterior: DoubleArray,
        notes: List<DetectedNote>,
        frame: ChromaFrame,
        bass: Int?,
        tier: ChordTier,
    ): ChordReading {
        var best = 0
        for (s in posterior.indices) if (posterior[s] > posterior[best]) best = s
        val bassConfidence = frame.bassConfidence.toFloat()
        fun reading(label: ChordLabel, alternates: List<ChordCandidate>, confidence: Double, presentation: ChordPresentation) =
            ChordReading(notes, bass, bassConfidence, label, alternates, confidence.toFloat(), tier, presentation)

        return when (val state = bank.states[best]) {
            ChordState.NoChord -> notesOnly(notes, frame, tier)
            is ChordState.Note -> reading(
                ChordLabel.SingleNote(
                    notes.filter { pitchClassOf(it.midi) == state.pitchClass }.minOfOrNull { it.midi }
                        ?: strongestMidiOf(frame, state.pitchClass),
                ),
                emptyList(),
                posterior[best],
                ChordPresentation.UNCERTAIN,
            )
            is ChordState.Named -> namedReading(bank, posterior, best, state.name, bass, ::reading)
        }
    }

    private fun namedReading(
        bank: ChordTemplates,
        posterior: DoubleArray,
        best: Int,
        name: ChordName,
        bass: Int?,
        reading: (ChordLabel, List<ChordCandidate>, Double, ChordPresentation) -> ChordReading,
    ): ChordReading {
        val alternates = posterior.indices
            .filter { it != best && bank.states[it] is ChordState.Named }
            .filter { posterior[it] >= ALTERNATE_RATIO * posterior[best] }
            .sortedByDescending { posterior[it] }
            .take(MAX_ALTERNATES)
            .map { ChordCandidate((bank.states[it] as ChordState.Named).name, posterior[it].toFloat()) }

        val named = ChordNaming.withSlash(name, bass)
        val bassBacked = bass != null && bass == name.root
        val gated = name.quality.tier == QualityTier.B && !bassBacked
        // A bass only settles rivals on another root: it cannot tell C from Cmaj7.
        val sameRoot = alternates.firstOrNull { it.name.root == name.root }
        val contested = if (bassBacked) sameRoot != null else alternates.isNotEmpty()
        if (gated || contested) {
            // A tier-B winner's rival is its pitch-class twin - Am7 for C6 - even when the twin
            // scored below the alternate ratio, because only the bass could ever separate them.
            val rival = (if (bassBacked) sameRoot else alternates.firstOrNull())
                ?: twinOf(bank, posterior, name)?.let { ChordCandidate(it.first, it.second.toFloat()) }
            if (rival != null) {
                val confidence = posterior[best] + rival.confidence
                if (confidence < ChordNaming.NAMING_THRESHOLD) {
                    return reading(ChordLabel.Unnamed, emptyList(), confidence, ChordPresentation.NOTES_ONLY)
                }
                return reading(ChordLabel.Ambiguous(named, rival.name), alternates, confidence, ChordPresentation.AMBIGUOUS)
            }
            if (gated) return reading(ChordLabel.Unnamed, emptyList(), posterior[best], ChordPresentation.NOTES_ONLY)
        }

        val confidence = posterior[best]
        return when {
            confidence < ChordNaming.NAMING_THRESHOLD ->
                reading(ChordLabel.Unnamed, emptyList(), confidence, ChordPresentation.NOTES_ONLY)
            confidence >= ChordNaming.CONFIDENT_THRESHOLD ->
                reading(ChordLabel.Chord(named), alternates, confidence, ChordPresentation.CONFIDENT)
            else -> reading(ChordLabel.Chord(named), alternates, confidence, ChordPresentation.UNCERTAIN)
        }
    }

    private fun twinOf(bank: ChordTemplates, posterior: DoubleArray, name: ChordName): Pair<ChordName, Double>? {
        var twin: Pair<ChordName, Double>? = null
        for (s in posterior.indices) {
            val other = (bank.states[s] as? ChordState.Named)?.name ?: continue
            if (other.root == name.root || other.pitchClasses != name.pitchClasses) continue
            if (twin == null || posterior[s] > twin.second) twin = other to posterior[s]
        }
        return twin
    }

    /** No chord fits: the note set is still the measurement, so it is shown without a name. */
    private fun notesOnly(notes: List<DetectedNote>, frame: ChromaFrame, tier: ChordTier): ChordReading {
        if (notes.isEmpty()) return silence(tier)
        val distinct = notes.map { pitchClassOf(it.midi) }.toSet()
        if (distinct.size <= 2) {
            return ChordNaming.name(notes, frame.bassMidi?.let { DetectedNote(it, frame.bassConfidence) }, tier)
        }
        return ChordReading(
            notes, null, 0f, ChordLabel.Unnamed, emptyList(), 0f, tier, ChordPresentation.NOTES_ONLY,
        )
    }

    private fun strongestMidiOf(frame: ChromaFrame, pitchClass: Int): Int {
        var best = LOWEST_NOTE_MIDI + pitchClass
        for (n in frame.semitones.indices) {
            val midi = LOWEST_NOTE_MIDI + n
            if (pitchClassOf(midi) == pitchClass && frame.semitones[n] > frame.semitones[best - LOWEST_NOTE_MIDI]) best = midi
        }
        return best
    }

    /**
     * Sieve extraction, gated on flatness and on the sieve's own absolute peak.
     *
     * Null means the frame was not worth extracting from, which is a different answer from "no
     * notes found" - the caller clears the history for it. The gate cannot reuse
     * [Tonality.MIN_PEAK_SALIENCE], whose units are log-magnitude above a local mean and belong
     * to `subtractBackground`; [HarmonicSieve.SILENCE_FLOOR] is the same idea in the sieve's own
     * signal-amplitude units.
     */
    private fun sieveNotes(
        magnitudes: DoubleArray,
        samples: DoubleArray,
        firstBin: Int,
    ): List<DetectedNote>? {
        if (spectralFlatness(magnitudes.copyOfRange(firstBin, CQT_BINS)) > maxFlatness) {
            return null
        }
        val notes = sieve.notes(samples, tuning.cents)
        if (sieve.peakAmplitude < HarmonicSieve.SILENCE_FLOOR) return null
        return notes
    }

    /**
     * NNLS extraction. Retained so [ChordExtractor.NNLS] stays runnable and the comparison in
     * `RealAudioHarnessTest` keeps measuring the code that actually shipped, rather than a copy
     * of it that can drift.
     */
    private fun nnlsNotes(
        magnitudes: DoubleArray,
        tier: ChordTier,
        firstBin: Int,
    ): List<DetectedNote>? {
        val salience = if (tier == ChordTier.PROVISIONAL) provisional else confirmed
        val observation = salience.subtractBackground(magnitudes)
        if (tier == ChordTier.PROVISIONAL) {
            // The bottom two octaves have not accumulated a valid window yet; leaving them in
            // would let the transient at the start of the kernel masquerade as a bass note. The
            // provisional dictionary is masked to the same bins, so the fit stays consistent -
            // clipping only the observation deletes C4, whose kernel straddles the boundary.
            for (bin in 0 until PROVISIONAL_FIRST_BIN) observation[bin] = 0.0
        }
        if (observation.max() <= 0.0) return null

        // NNLS fits whatever it is handed, so this is the only place that asks whether the frame
        // was worth fitting. Without it the namer receives a pitch-class set built from room
        // noise and names it - the Note tab never does, because YIN rejects aperiodic input
        // using a number it had to compute anyway.
        val fit = salience.fit(observation)
        val tonality = tonalityOf(magnitudes.copyOfRange(firstBin, CQT_BINS), fit)
        if (tonality.flatness > maxFlatness) return null
        if (tonality.peakSalience < Tonality.MIN_PEAK_SALIENCE) return null
        return pickNotes(fit.salience)
    }

    /**
     * Requires a note in two of the last three frames before it enters the reported set. Costs
     * one hop of latency and removes most single-frame flicker.
     */
    private fun stabilise(picked: List<DetectedNote>): List<DetectedNote> {
        history.addLast(picked.map { it.midi }.toSet())
        while (history.size > HISTORY_FRAMES) history.removeFirst()
        if (history.size < HISTORY_FRAMES) return picked
        return picked.filter { note -> history.count { note.midi in it } >= REQUIRED_FRAMES }
    }

    private fun silence(tier: ChordTier) = ChordReading(
        notes = emptyList(),
        bass = null,
        bassConfidence = 0f,
        label = ChordLabel.Silent,
        alternates = emptyList(),
        confidence = 0f,
        tier = tier,
        presentation = ChordPresentation.LISTENING,
    )

    private companion object {
        /** Bin 72 is C4: the bottom of the top three octaves. */
        const val PROVISIONAL_FIRST_BIN = 2 * CQT_BINS_PER_OCTAVE
        const val HISTORY_FRAMES = 3
        const val REQUIRED_FRAMES = 2
        const val BINS_PER_SEMITONE = CQT_BINS_PER_OCTAVE / 12

        /** Log-likelihood a gated frame takes off every state but no-chord. */
        const val SILENCE_EVIDENCE = 4.0

        /** A chord within this posterior ratio of the winner is shown alongside it. */
        const val ALTERNATE_RATIO = 0.6
        const val MAX_ALTERNATES = 2
    }
}
