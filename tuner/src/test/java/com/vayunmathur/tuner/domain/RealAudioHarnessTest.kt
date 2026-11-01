package com.vayunmathur.tuner.domain

import com.vayunmathur.tuner.data.DEFAULT_PARTIALS
import com.vayunmathur.tuner.data.InstrumentCatalog
import com.vayunmathur.tuner.platform.AudioCapture
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Drives NNLS and the constrained sieve over the same real recording and writes both scores out.
 *
 * Not a gate. Everything here either skips (no recording) or asserts something that is true of
 * the harness rather than of the detector, because the whole point of the file is that the
 * numbers are currently bad and hard thresholds would either be vacuous or permanently red.
 * `build/real-audio-report.txt` is the deliverable.
 */
class RealAudioHarnessTest {
    private val catalog = InstrumentCatalog.parse(File("src/main/assets/instruments.json").readText())
    private val ukulele = catalog.byId("ukulele-gcea")

    @Test
    fun theHarnessDecodesAtTheRateTheDeviceCapturesAt() {
        assertEquals(
            AudioCapture.SAMPLE_RATE.toDouble(),
            RealAudioHarness.RATE,
            "the recording is decoded at a rate the phone never captures at",
        )
    }

    @Test
    fun realAudioComparison() {
        val pcm = RealAudioHarness.pcmOrNull()
        if (pcm == null) {
            println("SKIP: no ${RealAudioHarness.PCM_PATH}; see RealAudioHarness for how to make it")
            return
        }

        val fraction = RealAudioHarness.tuningOffsetCents(pcm)
        val report = StringBuilder()
        appendHeader(report, pcm, fraction)

        var best = ""
        var bestCorrect = -1
        for (semitone in RealAudioHarness.SEMITONE_CANDIDATES) {
            val result = scoreCompensatedGrid(pcm, report, fraction, semitone)
            if (result.second > bestCorrect) {
                bestCorrect = result.second
                best = result.first
            }
        }
        report.appendLine("best by frames-named-correctly, oracle-compensated grid: $best ($bestCorrect)")
        report.appendLine()

        appendProductionSection(report, pcm)
        appendPolyphonySweep(report, pcm)
        appendFlatnessSweep(report, pcm)

        File("build").mkdirs()
        File("build/real-audio-report.txt").writeText(report.toString())
        println(report)
    }

    private fun appendHeader(report: StringBuilder, pcm: DoubleArray, fraction: Double) {
        report.appendLine("Record-3.m4a, real ukulele, C/Am/F")
        report.appendLine("  samples            ${pcm.size} (${"%.2f".format(pcm.size / RealAudioHarness.RATE)} s @ ${RealAudioHarness.RATE.toInt()} Hz)")
        report.appendLine("  AudioCapture rate  ${AudioCapture.SAMPLE_RATE}")
        report.appendLine("  sub-semitone tune  ${"%.1f".format(fraction)} cents (measured, wraps every 100)")
        report.appendLine("  uke soundingRange  ${ukulele.soundingRange}")
        report.appendLine("  uke partials       ${ukulele.partials}  (measured)")
        report.appendLine("  fallback partials  ${DEFAULT_PARTIALS.take(3).map { "%.2f".format(it) }}...  (1/n, what shipped)")
        report.appendLine()
    }

    private fun scoreCompensatedGrid(
        pcm: DoubleArray,
        report: StringBuilder,
        fraction: Double,
        semitone: Int,
    ): Pair<String, Int> {
        val cents = fraction + 100.0 * semitone
        val constantQ = RealAudioHarness.compensated(cents)
        val frames = RealAudioHarness.hops(pcm, constantQ.requiredSamples, RealAudioHarness.HOP)
        report.appendLine("=== tuning ${"%.1f".format(cents)} cents, ${frames.size} frames of ${constantQ.requiredSamples} samples, hop ${RealAudioHarness.HOP}")

        val before = nnls(constantQ, frames, DEFAULT_PARTIALS, "NNLS, 1/n dictionary (before)")
        val after = nnls(constantQ, frames, ukulele.partials, "NNLS, measured dictionary (after)")
        var best = ""
        var bestCorrect = -1
        for (score in listOf(before, after)) {
            report.appendLine(score.report())
            if (score.correct > bestCorrect) {
                bestCorrect = score.correct
                best = "${score.label} at ${"%.1f".format(cents)} cents"
            }
        }
        return best to bestCorrect
    }

    private fun appendProductionSection(report: StringBuilder, pcm: DoubleArray) {
        // Everything above hands the pipeline the answer: `compensated` slides the CQT grid onto
        // the recording using an offset chosen by sweeping. Nothing on the phone can do that, so
        // none of it is a number the app can be expected to reproduce. This section is - it runs
        // the shipping `ChordDetector` on an uncompensated grid and lets `TuningOffset` work the
        // mistuning out for itself, which is the only measurement here that predicts the device.
        report.appendLine("=== production path: no oracle, tuning self-estimated")
        // Before/after on the one thing that changed: how many picks the greedy loop is allowed.
        // Six was a global constant; four is the ukulele's string count, read off the instrument.
        val (uncapped, _) = production(
            pcm,
            ChordExtractor.SIEVE,
            polyphony = HarmonicSieve.MAX_NOTES,
            label = "SIEVE, production path, uncapped picks (before)",
        )
        report.appendLine(uncapped.report())
        report.appendLine()
        for (extractor in ChordExtractor.entries) {
            val (score, cents) = production(pcm, extractor)
            report.appendLine(score.report())
            report.appendLine("  tuning self-estimated  ${"%.1f".format(cents)} cents")
            report.appendLine()
        }
        val (chromaOnly, _) = production(
            pcm,
            ChordExtractor.CHROMA_HMM,
            withVoicings = false,
            label = "CHROMA_HMM, production path, chroma templates only (no voicings)",
        )
        report.appendLine(chromaOnly.report())
        report.appendLine()
    }

    private fun appendPolyphonySweep(report: StringBuilder, pcm: DoubleArray) {
        // The two failures are at opposite ends of the pipeline: 21 tonal frames never reach the
        // scorer because they carry fewer than three pitch classes, and 13 carry five or six,
        // which four ukulele strings cannot produce. A single relative floor cannot move both,
        // which is why sweeping it alone has failed four times. Swept here against the
        // instrument's own polyphony, which is the context the floor was missing.
        report.appendLine("=== polyphony x relative floor, SIEVE, production path")
        report.appendLine("  picks  floor   tonal  named  CORRECT   pitch classes")
        for (picks in POLYPHONY_SWEEP) {
            appendPolyphonyRow(report, pcm, picks)
        }
        report.appendLine()
    }

    private fun appendPolyphonyRow(report: StringBuilder, pcm: DoubleArray, picks: Int) {
        for (floor in FLOOR_SWEEP) {
            val (score, _) = production(
                pcm,
                ChordExtractor.SIEVE,
                polyphony = picks,
                relativeFloor = floor,
            )
            val histogram = score.pitchClassHistogram.toSortedMap()
                .entries.joinToString(" ") { "${it.key}pc x${it.value}" }
            report.appendLine(
                "  %5d %6.2f %7d %6d %8d   %s".format(
                    picks, floor, score.tonal, score.named, score.correct, histogram,
                ),
            )
        }
    }

    private fun appendFlatnessSweep(report: StringBuilder, pcm: DoubleArray) {
        // Priority 3: the gate, not the extractor, is now what most frames die on. It was placed
        // to protect an extractor finding 1.89 notes on a tonal frame, where passing a marginal
        // frame bought a note or two and no name. The sieve finds 3.39, so frames that were not
        // worth passing then may be worth passing now. Swept here rather than reasoned about,
        // because the synthetic corpus has nothing to say about where a real decay tail sits.
        report.appendLine("=== tonality gate sweep, production path")
        report.appendLine("  extractor    flatness   tonal   named   CORRECT   notes/tonal")
        for (extractor in listOf(ChordExtractor.SIEVE, ChordExtractor.CHROMA_HMM)) {
            for (threshold in FLATNESS_SWEEP) {
                val (score, _) = production(pcm, extractor, threshold)
                val perTonal = if (score.tonal == 0) 0.0 else score.notes.toDouble() / score.tonal
                report.appendLine(
                    "  %-10s %8.2f %7d %7d %9d %13.2f".format(
                        extractor, threshold, score.tonal, score.named, score.correct, perTonal,
                    ),
                )
            }
        }
        report.appendLine()
    }

    /**
     * The shipping pipeline, end to end, on the untouched recording.
     *
     * Frames are cut to `ConstantQ(RATE).requiredSamples` rather than to a compensated window, so
     * this is exactly the buffer `TunerViewModel` hands the detector. Returns the settled tuning
     * estimate alongside the score, because if that lands on the wrong semitone every name is a
     * semitone out and the score alone would not say why.
     */
    private fun production(
        pcm: DoubleArray,
        extractor: ChordExtractor,
        maxFlatness: Double =
            if (extractor == ChordExtractor.CHROMA_HMM) Tonality.CHROMA_MAX_FLATNESS else Tonality.MAX_FLATNESS,
        polyphony: Int = ukulele.polyphony,
        relativeFloor: Double = HarmonicSieve.RELATIVE_FLOOR,
        withVoicings: Boolean = true,
        label: String? = null,
    ): Pair<Score, Double> {
        val constantQ = ConstantQ(RealAudioHarness.RATE)
        val frames = RealAudioHarness.hops(pcm, constantQ.requiredSamples, RealAudioHarness.HOP)
        val detector = ChordDetector(
            RealAudioHarness.RATE,
            ukulele.soundingRange,
            ukulele.partials,
            extractor,
            maxFlatness,
            polyphony,
            relativeFloor,
            if (withVoicings) ukulele else null,
        )
        detector.reset()
        val score = Score(label ?: "$extractor, production path (${frames.size} frames)")
        for (frame in frames) {
            score.record(
                detector.analyse(frame, ChordTier.CONFIRMED, bassInformsRoot = false),
                RealAudioHarness.levelDb(frame, HarmonicSieve.FFT_SIZE),
            )
        }
        return score to detector.tuningCents
    }

    private fun nnls(
        constantQ: ConstantQ,
        frames: List<DoubleArray>,
        partials: List<Double>,
        label: String,
    ): Score {
        val score = Score(label)
        val detector = ChordDetector(
            constantQ.sampleRate,
            ukulele.soundingRange,
            partials,
            ChordExtractor.NNLS,
        )
        detector.reset()
        for (frame in frames) {
            score.record(detector.analyse(frame, ChordTier.CONFIRMED, bassInformsRoot = false))
        }
        return score
    }

    /**
     * How often C4 - the ukulele's open C string, which sounds in C, F *and* Am - survives the
     * provisional tier, with the dictionary masked to the observed bins and without.
     *
     * Bin 72 is C4 exactly and `SPREAD_BINS` is 3, so C4's fundamental occupies bins 69..75 while
     * the provisional tier zeroes the observation below 72. Unmasked, C4 weight can only add
     * model energy where the observation is definitionally zero, so it gets driven out.
     */
    @Test
    fun c4SurvivesTheProvisionalBoundary() {
        val pcm = RealAudioHarness.pcmOrNull()
        if (pcm == null) {
            println("SKIP: no ${RealAudioHarness.PCM_PATH}")
            return
        }
        val cents = RealAudioHarness.tuningOffsetCents(pcm) - 100.0
        val constantQ = RealAudioHarness.compensated(cents)
        val frames = RealAudioHarness.hops(pcm, constantQ.requiredSamples, RealAudioHarness.HOP)
        val boundary = 2 * CQT_BINS_PER_OCTAVE

        val unmasked = NoteSalience(ukulele.soundingRange, ukulele.partials)
        val masked = NoteSalience(ukulele.soundingRange, ukulele.partials, boundary)
        var unmaskedHits = 0
        var maskedHits = 0
        var unmaskedSalience = 0.0
        var maskedSalience = 0.0
        var gated = 0
        for (frame in frames) {
            val magnitudes = constantQ.magnitudes(frame)
            if (spectralFlatness(magnitudes) > Tonality.MAX_FLATNESS) continue
            gated++
            val observation = unmasked.subtractBackground(magnitudes)
            for (bin in 0 until boundary) observation[bin] = 0.0
            val c4 = 60 - LOWEST_NOTE_MIDI
            val before = unmasked.fit(observation)
            val after = masked.fit(observation)
            unmaskedSalience += before.salience[c4]
            maskedSalience += after.salience[c4]
            if (pickNotes(before.salience).any { it.midi == 60 }) unmaskedHits++
            if (pickNotes(after.salience).any { it.midi == 60 }) maskedHits++
        }

        val report = buildString {
            appendLine("C4 in the PROVISIONAL tier, real audio at ${"%.1f".format(cents)} cents")
            appendLine("  frames                        ${frames.size}, of which $gated pass the tonality gate")
            appendLine("  unmasked dictionary (before)  C4 picked in $unmaskedHits/$gated, mean salience ${"%.4f".format(unmaskedSalience / gated)}")
            appendLine("  masked dictionary (after)     C4 picked in $maskedHits/$gated, mean salience ${"%.4f".format(maskedSalience / gated)}")
        }
        File("build").mkdirs()
        File("build/c4-boundary-report.txt").writeText(report)
        println(report)

        assertTrue(
            maskedHits >= unmaskedHits,
            "masking the dictionary to the observed bins lost C4 detections: $report",
        )
    }

    private companion object {
        /**
         * Flatness thresholds to try. Topped out below the 0.709 that `TonalityTest` measures for
         * the closest noise case - above that the gate stops being a gate.
         */
        val FLATNESS_SWEEP = listOf(0.40, 0.45, 0.50, 0.55, 0.60, 0.65, 0.70)

        /** A ukulele has four strings, so 5 and 6 are there to show what they cost. */
        val POLYPHONY_SWEEP = listOf(3, 4, 5, 6)

        /** Straddles the shipping 0.14 in both directions. */
        val FLOOR_SWEEP = listOf(0.04, 0.08, 0.14, 0.22, 0.30)
    }
}
