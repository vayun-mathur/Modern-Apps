package com.vayunmathur.tuner.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The Chord tab against a strum shaped like a real one, rather than a sustained tone.
 *
 * This covers a bug reported from real use on a ukulele: a chord appeared for about 50 ms and
 * vanished, over and over. Nothing in the existing corpus could have caught it, because every
 * signal in it is sustained, noiseless and constant for the whole window - so the tonality gate
 * either passes for all of it or none of it, and a display that tracks the gate frame by frame
 * looks perfectly stable.
 *
 * What the device actually produces (966 frames, real ukulele, Pixel 8):
 *
 * - real strums measure **0.14..0.38** spectral flatness against a gate at 0.40, where the
 *   synthetic corpus that placed the gate measured **0.004..0.142**. The design margin of 0.258
 *   is really 0.018.
 * - real chord frames and real rejected frames **overlap** across 0.20..0.40. On the synthetic
 *   corpus they were 0.567 apart. Flatness does not separate the two on real input, so no
 *   threshold fixes this.
 * - the gate therefore drops in and out through a decay, producing 35 visible episodes with a
 *   mean length of 3.26 frames and 16 of them one or two frames long.
 *
 * So the assertion here is not "the gate never drops out" - it does, and re-tuning it cannot
 * stop it. It is that the *presentation* survives the dropouts.
 */
class StrumStabilityTest {
    private val constantQ = ConstantQ(Signals.SAMPLE_RATE)

    /**
     * Pinned to the note-first gate. The shipping [ChordExtractor.CHROMA_HMM] gate sits at
     * [Tonality.CHROMA_MAX_FLATNESS] and does not drop out on this strum at all (asserted below),
     * so it cannot produce the flickering input the presenter has to be proven against.
     */
    private val detector = ChordDetector(Signals.SAMPLE_RATE, maxFlatness = Tonality.MAX_FLATNESS)
    private val window = constantQ.requiredSamples

    /** 8 note hops of 512 samples: what TunerViewModel.CHORD_EVERY produces, ~85 ms. */
    private val hop = 8 * 512

    /** Sounding pitches of a ukulele C (0003) on re-entrant GCEA: the G string is above C. */
    private val ukeC = listOf(67, 60, 64, 72)

    private fun frames(signal: DoubleArray) = Signals.hops(signal, window, hop)

    /**
     * Drives the pipeline over a strum and returns both traces: what a display that tracked the
     * tonality gate frame by frame would have shown, and what [ChordPresenter] shows.
     *
     * Ukulele, so the bass is suppressed: on a re-entrant instrument the lowest sounding pitch
     * is routinely not the root.
     */
    private fun traces(
        signal: DoubleArray,
        detector: ChordDetector = this.detector,
    ): Pair<List<ChordLabel?>, List<ChordLabel?>> {
        val presenter = ChordPresenter()
        detector.reset()
        val raw = ArrayList<ChordLabel?>()
        val shown = ArrayList<ChordLabel?>()
        for (frame in frames(signal)) {
            val reading = detector.analyse(frame, ChordTier.CONFIRMED, bassInformsRoot = false)
            raw += reading.label.takeIf { it !is ChordLabel.Silent }
            presenter.update(reading)
            shown += presenter.current?.label
        }
        return raw to shown
    }

    /** Number of times a trace went from showing something to showing nothing. */
    private fun dropouts(trace: List<ChordLabel?>): Int =
        trace.zipWithNext().count { (a, b) -> a != null && b == null }

    @Test
    fun aRealisticStrumDoesNotFlicker() {
        val total = window + hop * 20
        // roomSigma 0.055 puts this strum at 0.22..0.50 flatness, which brackets the 0.14..0.38
        // measured off the device. At the levels the old corpus used (effectively noiseless) the
        // gate never drops out at all and this test would pass without the fix under test.
        val (raw, shown) = traces(Signals.strum(ukeC, total, roomSigma = 0.055))

        // First: the frame-by-frame gate really is unstable on a signal shaped like this. If
        // this ever stops holding, the test below has stopped proving anything.
        assertTrue(
            dropouts(raw) >= 2,
            "the gate no longer flickers on this signal, so the test proves nothing: $raw",
        )

        val visible = shown.count { it != null }
        assertTrue(visible > shown.size / 2, "tab was blank for most of a strum: $shown")

        // One trailing clear as the note dies away is correct; more than that is a strobe.
        assertTrue(dropouts(shown) <= 1, "display flickered ${dropouts(shown)} times: $shown")
    }

    @Test
    fun theShippingDetectorHoldsTheSameStrumWithoutThePresenter() {
        val total = window + hop * 20
        val (raw, _) = traces(Signals.strum(ukeC, total, roomSigma = 0.055), ChordDetector(Signals.SAMPLE_RATE))
        assertTrue(dropouts(raw) <= 1, "the shipping gate dropped out ${dropouts(raw)} times: $raw")
    }

    @Test
    fun aGatedFrameInTheMiddleOfAStrumDoesNotBlankTheTab() {
        // The exact failure mode measured on the device: one frame slips over the flatness gate
        // in the middle of an otherwise good stretch. It must not reach the display at all.
        val named = reading(ChordLabel.Chord(ChordName(0, ChordQuality.MAJOR)))
        val silent = reading(ChordLabel.Silent)
        val presenter = ChordPresenter()
        show(presenter, named)

        repeat(ChordPresenter.HOLD_FRAMES - 1) {
            assertEquals(ChordDisplay.UNCHANGED, presenter.update(silent))
            assertEquals(named.label, presenter.current?.label, "cleared after ${it + 1} frames")
        }
        assertEquals(named.label, presenter.current?.label)
    }

    @Test
    fun theHoldIsLongEnoughToBeReadButStillClears() {
        val presenter = ChordPresenter()
        show(presenter, reading(ChordLabel.Chord(ChordName(0, ChordQuality.MAJOR))))

        val holdMs = ChordPresenter.HOLD_FRAMES * hop / Signals.SAMPLE_RATE * 1000
        assertTrue(holdMs >= 300.0, "hold is only $holdMs ms; a tuner UI should not strobe")
        assertTrue(holdMs <= 1000.0, "hold of $holdMs ms would outlast the player stopping")

        // It does eventually clear: a held reading must not become permanent.
        var cleared = false
        repeat(ChordPresenter.HOLD_FRAMES) {
            if (presenter.update(reading(ChordLabel.Silent)) == ChordDisplay.CLEARED) cleared = true
        }
        assertTrue(cleared, "the hold never expired")
        assertEquals(null, presenter.current)
    }

    /** Puts [value] on screen, absorbing the frames hysteresis costs a brand new name. */
    private fun show(presenter: ChordPresenter, value: ChordReading) {
        repeat(ChordPresenter.HYSTERESIS_FRAMES) { presenter.update(value) }
        assertEquals(value.label, presenter.current?.label, "could not get a reading on screen")
    }

    @Test
    fun sustainedAndCleanAudioStillBehaves() {
        // The old corpus shape, kept so the hold cannot mask a regression in the detector.
        val total = window + hop * 8
        val (_, shown) = traces(Signals.chord(ukeC, total))
        assertTrue(shown.last() != null, "a sustained chord ended up showing nothing")
        assertEquals(0, dropouts(shown), "a clean sustained chord should never blank: $shown")
    }

    private fun reading(label: ChordLabel) = ChordReading(
        notes = emptyList(),
        bass = null,
        bassConfidence = 0f,
        label = label,
        alternates = emptyList(),
        confidence = 1f,
        tier = ChordTier.CONFIRMED,
        presentation = ChordPresentation.CONFIDENT,
    )
}
