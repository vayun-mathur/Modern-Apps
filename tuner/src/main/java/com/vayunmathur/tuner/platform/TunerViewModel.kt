package com.vayunmathur.tuner.platform

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.vayunmathur.library.util.DataStoreUtils
import com.vayunmathur.tuner.data.InstrumentCatalog
import com.vayunmathur.tuner.data.InstrumentKind
import com.vayunmathur.tuner.domain.CentsSmoother
import com.vayunmathur.tuner.domain.ChordDetector
import com.vayunmathur.tuner.domain.ChordDisplay
import com.vayunmathur.tuner.domain.ChordLabel
import com.vayunmathur.tuner.domain.ChordName
import com.vayunmathur.tuner.domain.ChordPresenter
import com.vayunmathur.tuner.domain.ChordReading
import com.vayunmathur.tuner.domain.ChordTier
import com.vayunmathur.tuner.domain.DEFAULT_A4_HZ
import com.vayunmathur.tuner.domain.PitchAnalyzer
import com.vayunmathur.tuner.domain.PitchFrame
import com.vayunmathur.tuner.domain.PitchReference
import com.vayunmathur.tuner.domain.PresentationHold
import com.vayunmathur.tuner.domain.VoicingGenerator
import com.vayunmathur.tuner.domain.YinPhaseSlopeAnalyzer
import com.vayunmathur.tuner.domain.bandFor
import com.vayunmathur.tuner.domain.spell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Owns the microphone, the pitch analyser and the state the Note tab renders.
 *
 * Capture is implicit: the tuner listens whenever it is on screen, because there is nothing else
 * for it to be doing. The intent still outlives the microphone - leaving the foreground releases
 * the device (a tuner has no business holding a microphone open in the background) while
 * remembering that the tabs are still up, and coming back reopens it. Without that resume the
 * microphone would die on every interruption and never come back, since nothing but the first
 * composition would ask for it again.
 *
 * Both tabs share one microphone and one coroutine. The chord pipeline is an order of magnitude
 * more expensive than the pitch one - a five-octave CQT against a 128 ms window - so it runs
 * once every [CHORD_EVERY] pitch hops rather than on every frame.
 */
class TunerViewModel(application: Application) : AndroidViewModel(application) {
    private val store = DataStoreUtils.getInstance(application)
    private val capture = AudioCapture(application)
    private val pitch: PitchAnalyzer = YinPhaseSlopeAnalyzer(AudioCapture.SAMPLE_RATE.toDouble())
    private val smoother = CentsSmoother()
    private val chord = ChordDetector(AudioCapture.SAMPLE_RATE.toDouble())

    private val noteBuffer = DoubleArray(pitch.requiredSamples)
    private val chordBuffer = DoubleArray(chord.requiredSamples)

    /**
     * Concert pitch, fixed. The reference is deliberately not user-selectable: see
     * [DEFAULT_A4_HZ]. [PitchReference] still takes it as a parameter, so restoring a picker
     * later is a UI change rather than a rework of the analysis.
     */
    private val reference = PitchReference(DEFAULT_A4_HZ)

    private var captureJob: Job? = null

    /**
     * Whether the tuner is meant to be listening, as opposed to whether the microphone happens to
     * be open right now. Survives the foreground/background cycle so [onForeground] knows whether
     * to reopen; [state]`.listening` tracks the device instead.
     */
    private var wantsCapture = false

    private var hopsSinceChord = 0
    private var chordFrames = 0

    /** Owns the display hysteresis and the hold-and-decay. See [ChordPresenter]. */
    private val presenter = ChordPresenter()

    /** The Note tab's half of the same mechanism, without the hysteresis (TUNER_SPEC I.7). */
    private val noteHold = PresentationHold<NoteReadout>(NOTE_HOLD_FRAMES)

    var state by mutableStateOf(initialState())
        private set

    init {
        // Not foldable into the field initialiser: `chord` is constructed before `state` exists.
        chord.setInstrument(state.instrument)
    }

    private fun initialState(): TunerUiState {
        val catalog = InstrumentCatalog.parse(
            getApplication<Application>().assets.open(ASSET).bufferedReader().use { it.readText() },
        )
        val savedId = store.getString(KEY_INSTRUMENT)
        val instrument = savedId?.let { catalog.byId(it) } ?: catalog.instruments.first()
        return TunerUiState(
            instrument = instrument,
            instruments = catalog.instruments,
        )
    }

    /**
     * The tuner came on screen, after the microphone permission was granted. Called from the
     * composition rather than the activity so it lands on the far side of the permission gate.
     */
    fun start() {
        wantsCapture = true
        openCapture()
    }

    /** The tuner left the screen for good. Releases the microphone and stays released. */
    fun stop() {
        wantsCapture = false
        closeCapture()
    }

    /**
     * The app came back to the foreground. Reopens the microphone only if the tuner was on screen
     * when the app left, so returning from a notification costs the user nothing - and a capture
     * that failed is not silently retried behind their back.
     */
    fun onForeground() {
        if (wantsCapture) openCapture()
    }

    /**
     * The app left the foreground. Releases the microphone but remembers the intent, so
     * [onForeground] can restore it.
     */
    fun onBackground() = closeCapture()

    /** Idempotent, so a tab switch or a spurious resume does not reopen the microphone. */
    private fun openCapture() {
        if (captureJob?.isActive == true) return
        pitch.reset()
        smoother.reset()
        noteHold.reset()
        resetChord()
        // Flipped before the first frame arrives so a capture that never produces audio still
        // looks different from one that was never asked for. The failure branch below puts it
        // back.
        state = state.copy(listening = true, failure = null)
        captureJob = viewModelScope.launch {
            capture.frames(NOTE_HOP).conflate().collect { result ->
                val failure = result.exceptionOrNull()
                if (failure is CaptureException) {
                    // A failure is the user's problem to act on, so it must not be silently
                    // retried on the next resume.
                    wantsCapture = false
                    state = state.copy(listening = false, failure = failure.failure)
                    return@collect
                }
                withContext(Dispatchers.Default) {
                    analyseNote()
                    if (++hopsSinceChord >= CHORD_EVERY) {
                        hopsSinceChord = 0
                        analyseChord()
                    }
                }
            }
        }
    }

    private fun closeCapture() {
        captureJob?.cancel()
        captureJob = null
        noteHold.reset()
        resetChord()
        state = state.copy(
            listening = false,
            note = state.note.copy(readout = null),
            chord = ChordUiState(),
        )
    }

    /** Selects an instrument, which changes the analysis range and the diagram that is drawn. */
    fun selectInstrument(id: String) {
        val instrument = state.instruments.firstOrNull { it.id == id } ?: return
        smoother.reset()
        noteHold.reset()
        chord.setInstrument(instrument)
        resetChord()
        state = state.copy(
            instrument = instrument,
            note = state.note.copy(readout = null),
            chord = ChordUiState(),
        )
        viewModelScope.launch { store.setString(KEY_INSTRUMENT, id) }
    }

    private fun analyseNote() {
        if (!capture.copyLatest(noteBuffer)) return
        val frame = pitch.analyse(noteBuffer, NOTE_MIN_HZ, NOTE_MAX_HZ)
        if (frame !is PitchFrame.Detected) {
            holdOrClearNote()
            return
        }
        val estimate = frame.estimate
        val midi = reference.nearestMidi(estimate.frequencyHz)
        val raw = reference.centsFrom(estimate.frequencyHz, midi)
        val smoothed = smoother.update(raw, NOTE_HOP.toDouble() / AudioCapture.SAMPLE_RATE)
        val spelling = spell(midi)
        val readout = NoteReadout(
            midi = midi,
            letter = spelling.letter,
            octave = spelling.octave,
            frequencyHz = estimate.frequencyHz,
            cents = smoothed,
            band = bandFor(smoothed),
        )
        // Straight through, with no hysteresis: a genuine new note must never wait behind the
        // hold, or the player would be tuning against a note they have stopped playing.
        noteHold.present(readout)
        state = state.copy(note = state.note.copy(readout = readout))
    }

    /**
     * A frame with no detected pitch.
     *
     * The reading is kept for [NOTE_HOLD_FRAMES] and marked [NoteReadout.stale] rather than
     * cleared. Tuning is a loop of pluck, listen, turn the peg, pluck again - the note decays
     * below YIN's confidence during the part where the player is actually adjusting, which is
     * precisely when blanking the readout is least helpful. The smoother is deliberately left
     * alone while holding, so a re-pluck of the same string continues rather than restarts.
     */
    private fun holdOrClearNote() {
        if (noteHold.idle()) {
            smoother.reset()
            state = state.copy(note = state.note.copy(readout = null))
            return
        }
        val held = noteHold.current ?: return
        if (!held.stale) {
            state = state.copy(note = state.note.copy(readout = held.copy(stale = true)))
        }
    }

    /**
     * One polyphonic frame.
     *
     * The reading is published even when it has no name - the note set is the measurement, and
     * §I.7 requires it to stay on screen in every state above "listening". Only the *name* is
     * withheld, and only the name is put through hysteresis.
     *
     * The detector is deliberately not reset while [ChordPresenter] is holding a reading.
     * Resetting zeroes [chordFrames], and with the dropouts a real strum produces the tier
     * never survived the [CONFIRM_FRAMES] frames needed to reach [ChordTier.CONFIRMED] -
     * measured at 3 frames in 966 on a real ukulele, which is why no bass note was ever
     * computed.
     */
    private fun analyseChord() {
        if (!capture.copyLatest(chordBuffer)) return
        val tier = if (chordFrames < CONFIRM_FRAMES) ChordTier.PROVISIONAL else ChordTier.CONFIRMED
        chordFrames++
        val fresh = chord.analyse(chordBuffer, tier, !state.instrument.reentrant)

        when (presenter.update(fresh)) {
            ChordDisplay.UNCHANGED -> return
            ChordDisplay.CLEARED -> {
                resetChord()
                state = state.copy(chord = ChordUiState())
                return
            }
            ChordDisplay.UPDATED -> Unit
        }

        val fretted = state.instrument.kind == InstrumentKind.FRETTED
        val (primary, secondary) = when (val label = fresh.label) {
            is ChordLabel.Chord -> label.name to null
            is ChordLabel.Ambiguous -> label.primary to label.secondary
            else -> null to null
        }
        state = state.copy(
            chord = ChordUiState(
                reading = fresh,
                voicings = shapesFor(fretted, primary),
                alternateVoicings = shapesFor(fretted, secondary),
            ),
        )
    }

    private fun shapesFor(fretted: Boolean, name: ChordName?) =
        if (fretted && name != null) VoicingGenerator.generate(state.instrument, name) else emptyList()

    private fun resetChord() {
        chord.reset()
        presenter.reset()
        chordFrames = 0
        hopsSinceChord = 0
    }

    private companion object {
        const val ASSET = "instruments.json"
        const val KEY_INSTRUMENT = "tuner_instrument"

        /** 512 samples at 48 kHz - the fast band's hop, ~94 updates a second. */
        const val NOTE_HOP = 512

        /**
         * The YIN lag search bounds for the Note tab: A0 to C8, the piano compass.
         *
         * Deliberately not the selected instrument's `analysis` range. The Note tab reports the
         * nearest note to whatever it hears, so narrowing the search by a selection made on the
         * Chord tab would make the same played note detectable or not depending on a control the
         * reading has nothing to do with.
         */
        const val NOTE_MIN_HZ = 27.5
        const val NOTE_MAX_HZ = 4186.0

        /** 8 note hops = 4096 samples = ~85 ms between chord frames. */
        const val CHORD_EVERY = 8

        /**
         * Chord frames before the bottom octaves of the CQT hold a valid window. Until then the
         * reading is provisional and carries no bass, so no tier-B quality can be named.
         */
        const val CONFIRM_FRAMES = 8

        /**
         * How long the Note tab keeps a reading after detection stops: ~1.2 s.
         *
         * Sized to the gap in the tuning loop - pluck, hear it, turn the peg, pluck again -
         * rather than to the decay of the string, because the reading is wanted most during the
         * part where nothing is sounding.
         */
        const val NOTE_HOLD_MS = 1_200
        const val NOTE_HOLD_FRAMES = NOTE_HOLD_MS * AudioCapture.SAMPLE_RATE / 1_000 / NOTE_HOP
    }
}
