package com.vayunmathur.tuner.domain

import kotlin.math.abs
import kotlin.math.pow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * End-to-end test of the Note tab's analyser against signals whose frequency is known exactly.
 *
 * The stage tests (YinTest, PhaseSlopeTest) each prove one half in isolation. This one runs the
 * assembled [YinPhaseSlopeAnalyzer] - band selection, noise gate, cross-check and all - because
 * that composition is what the tuner actually reports, and a stage can be accurate while the
 * pipeline around it throws the frame away or picks the wrong octave.
 *
 * Every assertion is in cents against the generator's exact frequency, and the measured errors
 * are printed so the numbers in a report are read off a run rather than estimated.
 */
class PitchDetectorTest {

    private val reference = PitchReference()

    /**
     * Runs enough frames for the band hysteresis to settle and returns the last accepted one.
     *
     * A single call is not representative: the analyser cold-starts in the low band and needs
     * three consenting frames before it moves, which is exactly the behaviour a one-shot test
     * would miss.
     */
    private fun settle(
        analyzer: YinPhaseSlopeAnalyzer,
        signal: DoubleArray,
        minHz: Double,
        maxHz: Double,
        frames: Int = 8,
    ): PitchFrame {
        var last: PitchFrame = PitchFrame.Silent(PitchRejection.TOO_QUIET)
        repeat(frames) { last = analyzer.analyse(signal, minHz, maxHz) }
        return last
    }

    private fun detected(
        signal: DoubleArray,
        minHz: Double,
        maxHz: Double,
    ): PitchEstimate {
        val analyzer = YinPhaseSlopeAnalyzer(Signals.SAMPLE_RATE)
        val frame = settle(analyzer, signal, minHz, maxHz)
        assertTrue(frame is PitchFrame.Detected, "no reading, got $frame")
        return frame.estimate
    }

    /** A plucked-string-ish stack: geometric decay over eight partials. */
    private fun pluck(hz: Double, count: Int, stiffness: Double = 0.0) =
        Signals.harmonic(hz, count, List(8) { 0.45 * 0.78.pow(it) }, stiffness = stiffness)

    private fun centsError(measured: Double, trueHz: Double) = 1200.0 * log2(measured / trueHz)

    @Test
    fun everyOpenGuitarStringIsMeasuredToWithinACent() {
        // Standard tuning, E2 to E4 - the span the fast/low band switch has to cross.
        val midis = listOf(40, 45, 50, 55, 59, 64)
        val samples = PitchBand.LOW.windowSize
        var worst = 0.0
        for (midi in midis) {
            val hz = reference.referenceHz(midi)
            val estimate = detected(pluck(hz, samples), 70.0, 1400.0)
            val cents = centsError(estimate.frequencyHz, hz)
            println(
                "guitar ${spell(midi)} true=$hz measured=${estimate.frequencyHz} " +
                    "error=$cents cents band=${estimate.band}",
            )
            assertEquals(midi, reference.nearestMidi(estimate.frequencyHz), "wrong note at $hz Hz")
            assertTrue(abs(cents) < 1.0, "at ${spell(midi)} the error was $cents cents")
            worst = maxOf(worst, abs(cents))
        }
        println("worst open-string error: $worst cents")
    }

    @Test
    fun theBassRangeIsMeasuredToWithinACent() {
        // B0 is 30.87 Hz. This is the case a MIC-sourced capture would silently destroy, and the
        // case a short window cannot resolve - so it is the one worth asserting hardest.
        val midis = listOf(23, 28, 33, 38, 43)
        val samples = PitchBand.LOW.windowSize
        var worst = 0.0
        for (midi in midis) {
            val hz = reference.referenceHz(midi)
            val estimate = detected(pluck(hz, samples), 27.0, 400.0)
            val cents = centsError(estimate.frequencyHz, hz)
            println(
                "bass ${spell(midi)} true=$hz measured=${estimate.frequencyHz} " +
                    "error=$cents cents band=${estimate.band}",
            )
            assertEquals(midi, reference.nearestMidi(estimate.frequencyHz), "wrong note at $hz Hz")
            assertTrue(abs(cents) < 1.0, "at ${spell(midi)} the error was $cents cents")
            worst = maxOf(worst, abs(cents))
        }
        println("worst bass error: $worst cents")
        assertTrue(worst < 1.0, "worst bass error was $worst cents")
    }

    @Test
    fun aDeliberatelyDetunedStringReportsTheOffsetItWasGiven() {
        // The number the user actually reads. A tuner that finds the right note but misreports
        // how far out it is would pass a note-name test and still be useless.
        val samples = PitchBand.LOW.windowSize
        for (offset in listOf(-30.0, -12.5, -3.0, 3.0, 12.5, 30.0)) {
            val target = reference.referenceHz(45)
            val hz = target * 2.0.pow(offset / 1200.0)
            val estimate = detected(pluck(hz, samples), 70.0, 1400.0)
            val midi = reference.nearestMidi(estimate.frequencyHz)
            val reported = reference.centsFrom(estimate.frequencyHz, midi)
            println("detune wanted=$offset reported=$reported error=${reported - offset} cents")
            assertEquals(45, midi, "wrong note for a $offset cent detune")
            assertTrue(
                abs(reported - offset) < 1.0,
                "a $offset cent detune was reported as $reported cents",
            )
        }
    }

    @Test
    fun aStiffLowStringIsNotReportedSharp() {
        // Inharmonicity is the systematic error that survives averaging, so it is the one that
        // would ship as a confident wrong answer rather than as visible jitter.
        val hz = reference.referenceHz(40)
        val estimate = detected(pluck(hz, PitchBand.LOW.windowSize, stiffness = 8e-5), 70.0, 1400.0)
        val cents = centsError(estimate.frequencyHz, hz)
        println("stiff low E error=$cents cents (stiffness fit ${estimate.stiffness})")
        assertTrue(abs(cents) < 1.0, "the stiff low E read $cents cents out")
    }

    @Test
    fun aNoisyRoomStillReadsUnderTwoCents() {
        val samples = PitchBand.LOW.windowSize
        var worst = 0.0
        for (snr in listOf(30.0, 20.0)) {
            for (midi in listOf(40, 55, 64)) {
                val hz = reference.referenceHz(midi)
                val signal = Signals.withNoise(pluck(hz, samples), snr, seed = midi + snr.toInt())
                val estimate = detected(signal, 70.0, 1400.0)
                val cents = centsError(estimate.frequencyHz, hz)
                println("noisy ${spell(midi)} snr=${snr}dB error=$cents cents")
                assertEquals(midi, reference.nearestMidi(estimate.frequencyHz), "wrong note, $snr dB")
                assertTrue(abs(cents) < 2.0, "at ${spell(midi)}, $snr dB, the error was $cents cents")
                worst = maxOf(worst, abs(cents))
            }
        }
        println("worst noisy error: $worst cents")
    }

    @Test
    fun aMissingFundamentalDoesNotProduceAnOctaveError() {
        // A phone capsule rolls off the bottom, so the low E's fundamental is largely gone. The
        // classic failure is to lock onto the second partial and report E3.
        val hz = reference.referenceHz(40)
        val signal = Signals.harmonic(
            hz,
            PitchBand.LOW.windowSize,
            listOf(0.02, 0.35, 0.30, 0.25, 0.20, 0.16, 0.13, 0.10),
        )
        val estimate = detected(signal, 70.0, 1400.0)
        val cents = centsError(estimate.frequencyHz, hz)
        println("missing-fundamental low E error=$cents cents")
        assertEquals(40, reference.nearestMidi(estimate.frequencyHz), "octave error")
        assertTrue(abs(cents) < 1.0, "the error was $cents cents")
    }

    @Test
    fun silenceAndNoiseProduceNoReading() {
        // The readout must go blank rather than hold a stale number - a frozen needle reads as a
        // stable note, which is the most misleading thing a tuner can do.
        val analyzer = YinPhaseSlopeAnalyzer(Signals.SAMPLE_RATE)
        val quiet = DoubleArray(PitchBand.LOW.windowSize)
        assertTrue(
            analyzer.analyse(quiet, 70.0, 1400.0) is PitchFrame.Silent,
            "silence produced a reading",
        )

        val noisy = YinPhaseSlopeAnalyzer(Signals.SAMPLE_RATE)
        val hiss = Signals.noise(PitchBand.LOW.windowSize, sigma = 0.05)
        val frame = settle(noisy, hiss, 70.0, 1400.0)
        assertTrue(frame is PitchFrame.Silent, "white noise was reported as $frame")
    }

    /**
     * The plucked note, not the period of everything sounding.
     *
     * Reported from real use: an A4 read as E2. Unrestricted YIN finds the mixture's period, so
     * with another string ringing it answered F2 (five A4 periods, where E2 also nearly repeats)
     * and with all six open strings it answered A0, on every frame.
     */
    @Test
    fun theLoudestNoteWinsOverOtherStringsRinging() {
        val samples = PitchBand.LOW.windowSize
        val a4 = reference.referenceHz(69)
        val openStrings = listOf(40, 45, 50, 55, 59, 64).map { reference.referenceHz(it) }
        val hum = Signals.harmonic(60.0, samples, listOf(1.0, 0.5, 0.3))
        val cases = listOf(
            "low E ringing at -12 dB" to listOf(reference.referenceHz(40) to 0.25),
            "six open strings at -12 dB" to openStrings.map { it to 0.25 },
            "six open strings at -20 dB" to openStrings.map { it to 0.1 },
        )
        for ((label, background) in cases) {
            val signal = pluck(a4, samples)
            for ((hz, level) in background) {
                val part = pluck(hz, samples)
                for (i in signal.indices) signal[i] += level * part[i]
            }
            assertA4(label, signal, a4)
        }
        val withHum = pluck(a4, samples)
        for (i in withHum.indices) withHum[i] += 0.25 * 0.45 * hum[i]
        assertA4("60 Hz hum at -12 dB", withHum, a4)
    }

    @Test
    fun aLowHumWithNoOvertonesIsNotANote() {
        // Measured on Record-3: a 59-66 Hz tone in the gaps between strums read as B1 and C2.
        val hum = Signals.harmonic(60.0, PitchBand.LOW.windowSize, listOf(0.3, 0.02, 0.01))
        val frame = settle(YinPhaseSlopeAnalyzer(Signals.SAMPLE_RATE), hum, 27.5, 4186.0)
        assertTrue(frame is PitchFrame.Silent, "a 60 Hz hum was reported as $frame")
    }

    /**
     * A strum on the Note tab is not one note. "Not periodic" is an honest answer to it; a note
     * that is not being played is not. Every chord tone is a partial of the chord's common root
     * two octaves down, so harmonic summation without a low-partial check read a ukulele C as C2.
     */
    @Test
    fun aStrummedChordNeverReadsAsANoteThatIsNotPlayed() {
        for (midis in listOf(listOf(67, 60, 64, 72), listOf(69, 60, 65, 69), listOf(48, 52, 55, 60, 64))) {
            val analyzer = YinPhaseSlopeAnalyzer(Signals.SAMPLE_RATE)
            val frame = settle(analyzer, Signals.chord(midis, PitchBand.LOW.windowSize), 27.5, 4186.0)
            if (frame !is PitchFrame.Detected) {
                println("strum $midis -> $frame")
                continue
            }
            val midi = reference.nearestMidi(frame.estimate.frequencyHz)
            println("strum $midis read as ${spell(midi)}")
            assertTrue(midi in midis, "strum $midis read as ${spell(midi)}, which is not being played")
        }
    }

    private fun assertA4(label: String, signal: DoubleArray, a4: Double) {
        val estimate = detected(signal, 27.5, 4186.0)
        val cents = centsError(estimate.frequencyHz, a4)
        println("mixture $label: ${spell(reference.nearestMidi(estimate.frequencyHz))} error=$cents cents")
        assertEquals(69, reference.nearestMidi(estimate.frequencyHz), "$label read the wrong note")
        assertTrue(abs(cents) < 2.0, "$label: the error was $cents cents")
    }

    @Test
    fun theReportedFrequencyAndCentsAgreeWithEachOther() {
        // The two numbers on screen come from the same estimate, so they must be consistent:
        // recomputing cents from the displayed Hz has to reproduce the displayed cents.
        val hz = reference.referenceHz(50) * 2.0.pow(7.0 / 1200.0)
        val estimate = detected(pluck(hz, PitchBand.LOW.windowSize), 70.0, 1400.0)
        val midi = reference.nearestMidi(estimate.frequencyHz)
        val cents = reference.centsFrom(estimate.frequencyHz, midi)
        val recomputed = 1200.0 * log2(estimate.frequencyHz / reference.referenceHz(midi))
        assertEquals(cents, recomputed, 1e-9)
        assertTrue(abs(cents - 7.0) < 1.0, "a 7 cent sharp D3 was reported as $cents cents")
    }
}
