package com.vayunmathur.tuner.platform

import androidx.compose.runtime.Immutable
import com.vayunmathur.tuner.data.Instrument
import com.vayunmathur.tuner.domain.ChordReading
import com.vayunmathur.tuner.domain.TuningBand
import com.vayunmathur.tuner.domain.Voicing

/** What the Note tab shows when a pitch is being tracked. */
@Immutable
data class NoteReadout(
    val midi: Int,
    val letter: String,
    val octave: Int,
    val frequencyHz: Double,
    /** Smoothed deviation from the reference, in cents. */
    val cents: Double,
    val band: TuningBand,
    /**
     * True when this reading is being held after detection stopped, rather than measured now.
     *
     * A held reading is still the most useful thing to show - the player plucks, the note
     * decays below YIN's confidence while they turn the peg, and blanking the readout at that
     * moment is exactly when they need it. But it is no longer live, and drawing it identically
     * to a live one would invite tuning against a note that stopped sounding. The UI must make
     * the difference visible.
     */
    val stale: Boolean = false,
)

/** State of the Note tab. A pure pitch readout - nothing on it depends on the instrument. */
@Immutable
data class NoteUiState(
    val readout: NoteReadout? = null,
)

/** State of the Chord tab. */
@Immutable
data class ChordUiState(
    val reading: ChordReading? = null,
    /** Ranked shapes for the named chord. Empty for piano, or when no name was confident. */
    val voicings: List<Voicing> = emptyList(),
    /** Shapes for the second reading of an ambiguous chord, so the tap-to-switch is instant. */
    val alternateVoicings: List<Voicing> = emptyList(),
)

/** Everything the two screens render from. */
@Immutable
data class TunerUiState(
    val instrument: Instrument,
    val instruments: List<Instrument>,
    val listening: Boolean = false,
    val failure: CaptureFailure? = null,
    val note: NoteUiState = NoteUiState(),
    val chord: ChordUiState = ChordUiState(),
)
