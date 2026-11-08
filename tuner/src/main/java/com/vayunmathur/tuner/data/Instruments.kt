package com.vayunmathur.tuner.data

import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.log2
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** What kind of thing an [Instrument] is, which decides how the Chord tab draws it. */
@Serializable
enum class InstrumentKind {
    /** Strings over frets: drawn as a chord diagram. */
    FRETTED,

    /** Drawn as a piano keyboard. */
    KEYBOARD,

    /** Strings without frets: tunable, but no shape to draw. */
    UNFRETTED,
}

/** One string of a fretted or unfretted instrument. */
@Serializable
data class StringSpec(
    /** Open pitch as a MIDI number. 40 is E2, the guitar's low E. */
    val openMidi: Int,
    /** A pitch letter, not translatable text. */
    val label: String,
    /** 2 for a twelve-string or mandolin course, drawn as one line and treated as one voice. */
    val courses: Int = 1,
)

/** The compass of a keyboard instrument. An 88-key piano is 21..108. */
@Serializable
data class KeyboardSpec(val lowestMidi: Int, val highestMidi: Int)

/** Narrows the YIN lag search, which removes a class of octave errors for free. */
@Serializable
data class AnalysisRange(val minHz: Double, val maxHz: Double) {
    /** The same span as MIDI numbers, rounded inwards so the bounds stay sounding. */
    val midiRange: IntRange
        get() = ceil(hzToMidi(minHz)).toInt()..floor(hzToMidi(maxHz)).toInt()
    private fun hzToMidi(hz: Double): Double =
        MIDI_A4 + SEMITONES_PER_OCTAVE * log2(hz / A4_HZ)

    private companion object {
        /** MIDI number of concert A (A4). */
        const val MIDI_A4 = 69.0

        /** Semitones per octave. */
        const val SEMITONES_PER_OCTAVE = 12.0

        /** Concert pitch in Hz. */
        const val A4_HZ = 440.0
    }
}

/**
 * An instrument and its tuning, loaded from `assets/instruments.json`.
 *
 * Data-driven so a new tuning is a data change. The honest limit on that (TUNER_SPEC F.4): a new
 * *tuning* of an existing family needs no code, because the UI labels it from the string letters.
 * A whole new named *family* still needs one `strings.xml` entry and one line in the id-to-
 * resource map, because JSON cannot add a string resource.
 *
 * v1 ships guitar, ukulele and piano only. The model carries `courses`, `fretCount` and
 * `keyboard` so the others in TUNER_SPEC F.3 are additions to this file rather than to the code.
 */
@Serializable
data class Instrument(
    val id: String,
    val familyId: String,
    val kind: InstrumentKind,
    val strings: List<StringSpec> = emptyList(),
    val fretCount: Int = 0,
    val keyboard: KeyboardSpec? = null,
    val analysis: AnalysisRange,
    /**
     * Relative amplitude of partial `n` at index `n - 1`, measured off this instrument rather
     * than assumed from a law. Absent means unmeasured, and unmeasured falls back to
     * [DEFAULT_PARTIALS].
     *
     * This is data and not a constant because the instruments differ by more than a tuning
     * parameter's worth. A nylon ukulele measures 0.18 at the octave; a steel-strung guitar and a
     * struck piano string are both far brighter, and a single global series has to be wrong for
     * at least two of the three.
     */
    val partials: List<Double> = DEFAULT_PARTIALS,
    val capoSupported: Boolean = true,
    val isDefaultForFamily: Boolean = false,
    /**
     * True when the strings are not ordered by pitch, so the lowest *sounding* note carries no
     * information about which note is the root.
     *
     * Standard ukulele GCEA is the case that matters: the G string is above C and E, so a uke F
     * sounds its lowest note as C - the fifth - and a uke G sounds D. Anything that infers a
     * root, a bass note or a slash chord from the lowest pitch is wrong by construction on such
     * an instrument, and wrong *confidently*, which is worse than declining to answer.
     */
    val reentrant: Boolean = false,
) {
    /** e.g. `"E A D G B E"`. Generated, so a new tuning needs no new string resource. */
    val tuningLabel: String get() = strings.joinToString(" ") { it.label }

    /**
     * String indices ordered by sounding pitch, lowest first.
     *
     * Pitch order is not string order. A re-entrant ukulele's first string is its *fourth*
     * lowest, so anything that talks about "the bass string" must go through this - deriving it
     * from index 0 is a genuine correctness trap.
     */
    fun stringsByPitch(fretted: List<Int>): List<Int> =
        strings.indices
            .filter { fretted.getOrElse(it) { MUTED_FRET } != MUTED_FRET }
            .sortedBy { strings[it].openMidi + fretted[it] }

    /**
     * The most notes this instrument can sound at once.
     *
     * One per string, because a string sounds one pitch. This is the same kind of statement as
     * [soundingRange] - a fact about the instrument, not a tuning parameter - and the chord
     * pipeline uses it the same way, to discard note hypotheses outright. A ukulele producing
     * five distinct pitch classes in one frame is not a difficult chord, it is extraction
     * residue. A keyboard is bounded by the player's hands rather than by the model.
     */
    val polyphony: Int
        get() = if (strings.isEmpty()) KEYBOARD_POLYPHONY else strings.size

    /**
     * Every MIDI note this instrument can physically sound: open strings up to the highest
     * fretted note, or the keyboard compass.
     *
     * Unlike [analysis], which only narrows a search, this is a statement about what is
     * *possible* - a ukulele cannot produce a C3 at any fret - so the chord pipeline uses it to
     * discard note hypotheses outright rather than merely to rank them.
     *
     * The top is [fretCount], which is a modelling choice rather than a physical limit, so a
     * note above the highest modelled fret is not reportable as part of a chord. That is the
     * same bound `VoicingGenerator` already draws shapes within. The bound that matters is the
     * bottom one: absorption is a low-end effect, because only a column *below* a real note can
     * explain it with its own partials. An [InstrumentKind.UNFRETTED] instrument has no fret
     * span to bound the top, so it falls back to [analysis].
     */
    val soundingRange: IntRange
        get() = keyboard?.let { it.lowestMidi..it.highestMidi }
            ?: when {
                strings.isEmpty() -> analysis.midiRange
                kind == InstrumentKind.FRETTED ->
                    strings.minOf { it.openMidi }..(strings.maxOf { it.openMidi } + fretCount)
                else -> strings.minOf { it.openMidi }..analysis.midiRange.last
            }
}

/** The whole bundled catalogue. */
@Serializable
data class InstrumentCatalog(@SerialName("instruments") val instruments: List<Instrument>) {
    /** Looks an instrument up, falling back to the first entry so the UI always has one. */
    fun byId(id: String): Instrument = instruments.firstOrNull { it.id == id } ?: instruments.first()

    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        /** Parses the bundled JSON. Pure, so the catalogue is unit-testable off-device. */
        fun parse(text: String): InstrumentCatalog = json.decodeFromString(text)
    }
}

/** Sentinel fret value meaning "this string is not played". */
const val MUTED_FRET: Int = -1

/** Notes a keyboard player's two hands can hold down at once; the chord pipeline caps below it. */
const val KEYBOARD_POLYPHONY: Int = 10

/**
 * `1/n` over twelve partials - the ideal plucked string, and what every instrument used before
 * any of them were measured.
 *
 * Kept as the fallback rather than replaced by the ukulele's measurement: the ukulele is the only
 * instrument there is a recording of, and copying its profile onto a piano would be inventing
 * data rather than defaulting to a stated assumption.
 */
val DEFAULT_PARTIALS: List<Double> = (1..12).map { 1.0 / it }
