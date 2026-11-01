package com.vayunmathur.tuner.domain

/**
 * Which of the two naming tiers a quality belongs to (TUNER_SPEC I.5).
 *
 * The split is not a confidence judgement about the detector, it is a structural fact about the
 * vocabulary: nine of the fourteen qualities have a pitch-class set identical to another entry,
 * so no amount of signal processing can separate them. Only the bass can.
 */
enum class QualityTier {
    /** Pitch-class-unique across the vocabulary. If the notes are right, the name is right. */
    A,

    /** Identical to at least one other entry. The name is hard-gated on a confident bass. */
    B,
}

/**
 * The fourteen chord qualities this app is willing to name (TUNER_SPEC D.1, tiered by I.5).
 *
 * The vocabulary is bounded by what the detector can actually support. Ninths, elevenths,
 * thirteenths, add-tone and altered dominants are deliberately absent: a C13 is seven notes and
 * a guitarist plays four of them, so the name is a musical judgement about intent rather than
 * an acoustic fact, and the alteration in an altered dominant is one weak peak away from being
 * a deconvolution residual. Guessing at them would be dishonest, not ambitious.
 *
 * [symbol] is chord notation, not prose - `m7` and `dim` read the same in every locale this
 * ships in, exactly like the pitch letters in [spellPitchClass].
 */
enum class ChordQuality(
    val symbol: String,
    val intervals: List<Int>,
    /** Occam prior: prefer the simpler reading when two scores are near-tied. */
    val complexity: Int,
    val tier: QualityTier,
    /**
     * Interval that must clear [ChordNaming.STRICT_TONE] rather than the usual floor (tier B',
     * TUNER_SPEC I.5). Set only for `aug` and `sus2`: each is one note from a plain major triad,
     * and a false `Caug` on a beginner's C chord is far worse than missing a real one.
     */
    val strictInterval: Int? = null,
) {
    POWER("5", listOf(0, 7), 0, QualityTier.B),
    MAJOR("", listOf(0, 4, 7), 0, QualityTier.A),
    MINOR("m", listOf(0, 3, 7), 0, QualityTier.A),
    SUS2("sus2", listOf(0, 2, 7), 1, QualityTier.B, strictInterval = 2),
    SUS4("sus4", listOf(0, 5, 7), 1, QualityTier.B),
    DIMINISHED("dim", listOf(0, 3, 6), 2, QualityTier.A),
    AUGMENTED("aug", listOf(0, 4, 8), 2, QualityTier.B, strictInterval = 8),
    DOMINANT_SEVENTH("7", listOf(0, 4, 7, 10), 1, QualityTier.A),
    MINOR_SEVENTH("m7", listOf(0, 3, 7, 10), 1, QualityTier.B),
    MAJOR_SEVENTH("maj7", listOf(0, 4, 7, 11), 1, QualityTier.A),
    SIXTH("6", listOf(0, 4, 7, 9), 2, QualityTier.B),
    MINOR_SIXTH("m6", listOf(0, 3, 7, 9), 2, QualityTier.B),
    HALF_DIMINISHED("m7\u266D5", listOf(0, 3, 6, 10), 2, QualityTier.B),
    DIMINISHED_SEVENTH("dim7", listOf(0, 3, 6, 9), 2, QualityTier.B),
}

/** A named chord: a root pitch class, a quality, and an optional bass for a slash chord. */
data class ChordName(val root: Int, val quality: ChordQuality, val bass: Int? = null) {
    /** The pitch classes this chord asks for. */
    val pitchClasses: Set<Int> get() = quality.intervals.map { pitchClassOf(root + it) }.toSet()
}

/** A scored chord hypothesis. */
data class ChordCandidate(val name: ChordName, val confidence: Float)

/** How much of the analysis window was available when a reading was produced. */
enum class ChordTier {
    /** Upper three octaves only, ~150 ms after the strum. No bass note, so no slash chords. */
    PROVISIONAL,

    /** The full five-octave window, ~800 ms in. Bass note available. */
    CONFIRMED,
}

/**
 * What the naming layer produces.
 *
 * [notes] is always populated and is the *measurement*; [primary] is an interpretation layered
 * on top and may legitimately be `null`. The UI must treat a null primary as a first-class
 * state rather than an error - that is what lets the feature degrade honestly instead of
 * guessing.
 */
data class ChordReading(
    val notes: List<DetectedNote>,
    val bass: Int?,
    /** Salience of [bass] relative to the loudest note, or 0 when no bass was confident. */
    val bassConfidence: Float,
    val label: ChordLabel,
    val alternates: List<ChordCandidate>,
    val confidence: Float,
    val tier: ChordTier,
    val presentation: ChordPresentation,
) {
    /** Pitch classes present, octave folded away. */
    val pitchClasses: Set<Int> get() = notes.map { pitchClassOf(it.midi) }.toSet()
}

/**
 * How much of a claim the UI is allowed to make (TUNER_SPEC I.7).
 *
 * The notes are the measurement and are shown in every state above [LISTENING]; the name is an
 * interpretation and is withheld, hedged or doubled up according to this.
 */
enum class ChordPresentation {
    /** Nothing is sounding. Prompt to play, nothing else. */
    LISTENING,

    /** Notes and keyboard only. No name, and therefore no fret diagram. */
    NOTES_ONLY,

    /** Two readings are equally defensible. Show both, never a silent pick. */
    AMBIGUOUS,

    /** Named, but in a lower-emphasis style with a visible caveat. */
    UNCERTAIN,

    /** Named outright. */
    CONFIDENT,
}

/** What the detected note set amounts to musically. */
sealed interface ChordLabel {
    /** Nothing is sounding. */
    data object Silent : ChordLabel

    /** One note. The degenerate case, and the one the pipeline is best at. */
    data class SingleNote(val midi: Int) : ChordLabel

    /** Two notes that are not a perfect fifth: an interval, named by its size. */
    data class Interval(val semitones: Int, val lowMidi: Int) : ChordLabel

    /** A named chord. */
    data class Chord(val name: ChordName) : ChordLabel

    /**
     * Two readings of the same notes, both shown.
     *
     * Reached either when the top two scores are within [ChordNaming.ALTERNATE_MARGIN] of each
     * other, or when the winner is a [QualityTier.B] quality and no confident bass backs it.
     * `Am7` and `C6` are the same four notes; picking one would be a coin flip presented as an
     * answer.
     */
    data class Ambiguous(val primary: ChordName, val secondary: ChordName) : ChordLabel

    /**
     * Notes are sounding but no name scored well enough. The set is still shown, so the
     * keyboard has something true to draw.
     */
    data object Unnamed : ChordLabel
}

/**
 * Scores every root-and-quality hypothesis against a pitch-class salience vector (TUNER_SPEC
 * D.2) and resolves the genuinely ambiguous cases (D.3) rather than picking silently.
 *
 * Weights are starting points derived from the reasoning in the spec, not values tuned against
 * a corpus - none of them will survive contact with a real microphone unchanged.
 */
object ChordNaming {
    /** Below this confidence only the notes are shown - no name, and so no diagram. */
    const val NAMING_THRESHOLD: Float = 0.45f

    /** At or above this the name is shown without a caveat. */
    const val CONFIDENT_THRESHOLD: Float = 0.70f

    /** Alternates within this fraction of the primary score are presented alongside it. */
    const val ALTERNATE_MARGIN: Double = 0.10

    /** Salience a tier-B' distinguishing tone must clear (TUNER_SPEC I.5). */
    const val STRICT_TONE: Double = 0.45

    private const val MISSING_TONE_PENALTY = 0.60
    private const val EXTRA_TONE_PENALTY = 0.45
    private const val COMPLEXITY_PENALTY = 0.08
    private const val BASS_MATCH_BONUS = 0.35
    private const val ABSENT_ROOT_PENALTY = 0.50
    private const val WEAK_TONE = 0.20

    /**
     * A one-pitch-class reading is a degraded observation of an unknown number of strings, so it
     * sits above [NAMING_THRESHOLD] - the note is real - but below [CONFIDENT_THRESHOLD].
     */
    private const val SINGLE_NOTE_CONFIDENCE = 0.5f

    private const val MAX_ALTERNATES = 2

    /**
     * Reads [notes] as a chord, using [bass] when the caller is confident about it.
     *
     * A [QualityTier.B] quality is never returned as a bare [ChordLabel.Chord] without a
     * confident [bass] whose pitch class is the quality's root. That is the single hardest rule
     * in the chord tab (TUNER_SPEC I.5) and it is enforced here rather than in the UI, so no
     * caller can route around it.
     */
    fun name(
        notes: List<DetectedNote>,
        bass: DetectedNote?,
        tier: ChordTier,
    ): ChordReading {
        val bassPitchClass = bass?.let { pitchClassOf(it.midi) }
        val bassConfidence = bassConfidenceOf(bass, notes)
        val distinct = notes.map { pitchClassOf(it.midi) }.toSet()

        // Cardinality shortcuts: a triad name cannot honestly be inferred from two notes.
        val label: ChordReading = when (distinct.size) {
            0 -> buildReading(
                notes, bassPitchClass, bassConfidence, tier,
                ChordLabel.Silent, emptyList(), 0f, ChordPresentation.LISTENING,
            )
            // One pitch class is far more often a strum whose other tones fell under the picking
            // floor than a deliberately plucked single string, and the two are not separable from
            // the audio. The note itself is a measurement and is still shown; what is withheld is
            // the claim that it is the whole of what was played.
            1 -> buildReading(
                notes, bassPitchClass, bassConfidence, tier,
                ChordLabel.SingleNote(notes.minOf { it.midi }),
                emptyList(),
                SINGLE_NOTE_CONFIDENCE,
                ChordPresentation.UNCERTAIN,
            )
            2 -> twoNoteReading(notes, bassPitchClass, bassConfidence, tier)
            else -> scoredReading(notes, bassPitchClass, bassConfidence, tier)
        }
        return label
    }

    private fun bassConfidenceOf(bass: DetectedNote?, notes: List<DetectedNote>): Float {
        val loudest = notes.maxOfOrNull { it.salience } ?: 0.0
        return when {
            bass == null || loudest <= 0.0 -> 0f
            else -> (bass.salience / loudest).coerceIn(0.0, 1.0).toFloat()
        }
    }

    private fun buildReading(
        notes: List<DetectedNote>,
        bassPitchClass: Int?,
        bassConfidence: Float,
        tier: ChordTier,
        label: ChordLabel,
        alternates: List<ChordCandidate>,
        confidence: Float,
        presentation: ChordPresentation,
    ) = ChordReading(
        notes, bassPitchClass, bassConfidence, label, alternates, confidence, tier, presentation,
    )

    private fun buildNormalizedSalience(notes: List<DetectedNote>): DoubleArray {
        val salience = DoubleArray(12)
        for (note in notes) salience[pitchClassOf(note.midi)] += note.salience
        val peak = salience.max()
        if (peak > 0.0) for (i in 0..11) salience[i] /= peak
        return salience
    }

    private fun scoreAllCandidates(
        salience: DoubleArray,
        bassPitchClass: Int?,
    ): ArrayList<Pair<ChordName, Double>> {
        val scored = ArrayList<Pair<ChordName, Double>>(12 * ChordQuality.entries.size)
        for (root in 0..11) {
            for (quality in ChordQuality.entries) {
                if (isRuledOut(root, quality, salience, bassPitchClass)) continue
                scored += ChordName(root, quality) to score(root, quality, salience, bassPitchClass)
            }
        }
        return scored
    }

    private fun isRuledOut(
        root: Int,
        quality: ChordQuality,
        salience: DoubleArray,
        bassPitchClass: Int?,
    ): Boolean {
        if (!passesStrictTone(root, quality, salience)) return true
        // With a confident bass, a tier-B reading rooted anywhere else is ruled out by
        // evidence rather than by preference - that is what the bass is for.
        return quality.tier == QualityTier.B &&
            bassPitchClass != null &&
            bassPitchClass != root
    }

    private fun scoredReading(
        notes: List<DetectedNote>,
        bassPitchClass: Int?,
        bassConfidence: Float,
        tier: ChordTier,
    ): ChordReading {
        val salience = buildNormalizedSalience(notes)
        val scored = scoreAllCandidates(salience, bassPitchClass)
        if (scored.isEmpty()) {
            return buildReading(
                notes, bassPitchClass, bassConfidence, tier,
                ChordLabel.Unnamed, emptyList(), 0f, ChordPresentation.NOTES_ONLY,
            )
        }
        scored.sortByDescending { it.second }

        val (bestName, bestScore) = scored[0]
        val confidence = (bestScore / bestName.quality.intervals.size).coerceIn(0.0, 1.0).toFloat()
        if (confidence < NAMING_THRESHOLD) {
            return buildReading(
                notes, bassPitchClass, bassConfidence, tier,
                ChordLabel.Unnamed, emptyList(), confidence, ChordPresentation.NOTES_ONLY,
            )
        }

        val alternates = buildAlternates(scored, bestName, bestScore)
        val named = withSlash(bestName, bassPitchClass)
        return resolveFinalReading(notes, bassPitchClass, bassConfidence, tier, scored, named, alternates, confidence)
    }

    private fun buildAlternates(
        scored: List<Pair<ChordName, Double>>,
        bestName: ChordName,
        bestScore: Double,
    ): List<ChordCandidate> = scored.asSequence()
        .drop(1)
        .filter { bestScore > 0.0 && (bestScore - it.second) / bestScore < ALTERNATE_MARGIN }
        .filter { it.first.root != bestName.root || it.first.quality != bestName.quality }
        .take(MAX_ALTERNATES)
        .map { (name, value) ->
            ChordCandidate(
                name,
                (value / name.quality.intervals.size).coerceIn(0.0, 1.0).toFloat(),
            )
        }
        .toList()

    internal fun withSlash(bestName: ChordName, bassPitchClass: Int?): ChordName {
        // A slash chord only when the bass is confident and is a chord tone other than the
        // root. When it is not confident, omit the slash rather than guess an inversion.
        val slash = bassPitchClass
            ?.takeIf { it != bestName.root && it in bestName.pitchClasses }
        return if (slash != null) bestName.copy(bass = slash) else bestName
    }

    private fun resolveFinalReading(
        notes: List<DetectedNote>,
        bassPitchClass: Int?,
        bassConfidence: Float,
        tier: ChordTier,
        scored: List<Pair<ChordName, Double>>,
        named: ChordName,
        alternates: List<ChordCandidate>,
        confidence: Float,
    ): ChordReading {
        val bestName = scored[0].first
        val bassBacked = bassPitchClass != null && bassPitchClass == bestName.root
        val gated = bestName.quality.tier == QualityTier.B && !bassBacked
        val contested = alternates.isNotEmpty() && !bassBacked
        val ambiguous: ChordReading? = when {
            gated || contested -> ambiguousReading(
                notes, bassPitchClass, bassConfidence, tier, scored, named, alternates, confidence, gated,
            )
            else -> null
        }
        return ambiguous ?: buildReading(
            notes, bassPitchClass, bassConfidence, tier,
            ChordLabel.Chord(named),
            alternates,
            confidence,
            when {
                confidence >= CONFIDENT_THRESHOLD -> ChordPresentation.CONFIDENT
                else -> ChordPresentation.UNCERTAIN
            },
        )
    }

    private fun ambiguousReading(
        notes: List<DetectedNote>,
        bassPitchClass: Int?,
        bassConfidence: Float,
        tier: ChordTier,
        scored: List<Pair<ChordName, Double>>,
        named: ChordName,
        alternates: List<ChordCandidate>,
        confidence: Float,
        gated: Boolean,
    ): ChordReading? {
        val other = alternates.firstOrNull()?.name
            ?: scored.drop(1).firstOrNull { it.first.root != scored[0].first.root }?.first
        if (other != null) {
            return buildReading(
                notes, bassPitchClass, bassConfidence, tier,
                ChordLabel.Ambiguous(named, other),
                alternates,
                confidence,
                ChordPresentation.AMBIGUOUS,
            )
        }
        // A tier-B quality with no rival reading and no bass still cannot be named alone.
        return when {
            gated -> buildReading(
                notes, bassPitchClass, bassConfidence, tier,
                ChordLabel.Unnamed,
                emptyList(),
                confidence,
                ChordPresentation.NOTES_ONLY,
            )
            else -> null
        }
    }

    /** Tier B': `aug` and `sus2` need their distinguishing tone to be genuinely present. */
    private fun passesStrictTone(
        root: Int,
        quality: ChordQuality,
        salience: DoubleArray,
    ): Boolean {
        val interval = quality.strictInterval ?: return true
        return salience[pitchClassOf(root + interval)] > STRICT_TONE
    }

    private fun twoNoteReading(
        notes: List<DetectedNote>,
        bassPitchClass: Int?,
        bassConfidence: Float,
        tier: ChordTier,
    ): ChordReading {
        val low = notes.minBy { it.midi }
        val high = notes.maxBy { it.midi }
        val interval = pitchClassOf(high.midi - low.midi)
        if (interval != 7) {
            return ChordReading(
                notes, bassPitchClass, bassConfidence, ChordLabel.Interval(interval, low.midi),
                emptyList(), 0.9f, tier, ChordPresentation.CONFIDENT,
            )
        }
        // `5` is tier B: {E, B} is an E power chord or a B one voiced with its fifth underneath.
        // Only the bass says which, so without one both readings are shown.
        val lower = ChordName(pitchClassOf(low.midi), ChordQuality.POWER)
        val upper = ChordName(pitchClassOf(high.midi), ChordQuality.POWER)
        val label = when (bassPitchClass) {
            lower.root -> ChordLabel.Chord(lower)
            upper.root -> ChordLabel.Chord(upper)
            else -> ChordLabel.Ambiguous(lower, upper)
        }
        return ChordReading(
            notes,
            bassPitchClass,
            bassConfidence,
            label,
            emptyList(),
            0.9f,
            tier,
            if (label is ChordLabel.Ambiguous) {
                ChordPresentation.AMBIGUOUS
            } else {
                ChordPresentation.CONFIDENT
            },
        )
    }

    private fun score(
        root: Int,
        quality: ChordQuality,
        salience: DoubleArray,
        bassPitchClass: Int?,
    ): Double {
        val tones = quality.intervals.map { pitchClassOf(root + it) }.toSet()
        var score = 0.0
        var missing = 0
        for (pitchClass in tones) {
            score += salience[pitchClass]
            if (salience[pitchClass] < WEAK_TONE) missing++
        }
        var extra = 0.0
        for (pitchClass in 0..11) if (pitchClass !in tones) extra += salience[pitchClass]

        score -= MISSING_TONE_PENALTY * missing
        score -= EXTRA_TONE_PENALTY * extra
        score -= COMPLEXITY_PENALTY * quality.complexity
        if (bassPitchClass == root) score += BASS_MATCH_BONUS
        if (salience[root] < WEAK_TONE) score -= ABSENT_ROOT_PENALTY
        return score
    }
}
