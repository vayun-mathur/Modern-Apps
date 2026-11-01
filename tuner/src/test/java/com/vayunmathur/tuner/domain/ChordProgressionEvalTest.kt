package com.vayunmathur.tuner.domain

import com.vayunmathur.tuner.data.Instrument
import com.vayunmathur.tuner.data.InstrumentCatalog
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Streams labelled synthetic progressions through the detector exactly as `TunerViewModel` does -
 * same hop, same tier schedule, same [ChordPresenter] - and scores what the user would see.
 *
 * `build/chord-eval-report.txt` is the full comparison. The assertions are relative (the shipping
 * extractor must beat the sieve it replaced) plus floors set below the measured numbers, because
 * absolute accuracy on a synthetic corpus is a ceiling, not a prediction. See [StrumSynth].
 */
class ChordProgressionEvalTest {
    private val catalog = InstrumentCatalog.parse(File("src/main/assets/instruments.json").readText())
    private val guitar = catalog.byId("guitar-standard")
    private val ukulele = catalog.byId("ukulele-gcea")

    private fun c(root: Int, quality: ChordQuality = ChordQuality.MAJOR) = ChordName(root, quality)
    private val minor = ChordQuality.MINOR

    private val guitarProgressions = listOf(
        listOf(c(7), c(2), c(4, minor), c(0)),
        listOf(c(0), c(9, minor), c(5), c(7)),
        listOf(c(9, minor), c(2, minor), c(4, ChordQuality.DOMINANT_SEVENTH), c(9, minor)),
        listOf(c(2, ChordQuality.MINOR_SEVENTH), c(7, ChordQuality.DOMINANT_SEVENTH), c(0, ChordQuality.MAJOR_SEVENTH), c(9, minor)),
        listOf(c(9), c(4), c(6, minor), c(2)),
        listOf(c(4), c(9), c(11, ChordQuality.DOMINANT_SEVENTH), c(4)),
    )

    private val ukuleleProgressions = listOf(
        listOf(c(0), c(7), c(9, minor), c(5)),
        listOf(c(5), c(0), c(2, minor), c(10)),
        listOf(c(7, ChordQuality.DOMINANT_SEVENTH), c(0), c(9, minor), c(2, minor)),
        listOf(c(9), c(2), c(4, ChordQuality.DOMINANT_SEVENTH), c(9)),
    )

    private class Metrics {
        var frames = 0
        var shownCorrect = 0
        var shownOffered = 0
        var rawCorrect = 0
        var shownChanges = 0
        var chordChanges = 0
        val latenciesMs = ArrayList<Double>()
        var missed = 0
        var tierBAlone = 0
        val confusions = HashMap<String, Int>()

        fun add(other: Metrics) {
            frames += other.frames
            shownCorrect += other.shownCorrect
            shownOffered += other.shownOffered
            rawCorrect += other.rawCorrect
            shownChanges += other.shownChanges
            chordChanges += other.chordChanges
            latenciesMs += other.latenciesMs
            missed += other.missed
            tierBAlone += other.tierBAlone
            for ((key, count) in other.confusions) confusions[key] = (confusions[key] ?: 0) + count
        }

        fun rate(part: Int) = if (frames == 0) 0.0 else part.toDouble() / frames
        val medianLatency: Double get() = latenciesMs.sorted().let { if (it.isEmpty()) Double.NaN else it[it.size / 2] }

        fun row(label: String) = "  %-28s frames %4d  shown-correct %5.1f%%  shown-offered %5.1f%%  raw-correct %5.1f%%  median-latency %5.0f ms  missed %2d/%2d  label-changes %3d (chord changes %d)".format(
            label, frames, 100 * rate(shownCorrect), 100 * rate(shownOffered), 100 * rate(rawCorrect),
            medianLatency, missed, chordChanges, shownChanges, chordChanges,
        )
    }

    private fun namesIn(label: ChordLabel?): List<ChordName> = when (label) {
        is ChordLabel.Chord -> listOf(label.name)
        is ChordLabel.Ambiguous -> listOf(label.primary, label.secondary)
        else -> emptyList()
    }

    private fun same(a: ChordName, b: ChordName) = a.root == b.root && a.quality == b.quality

    private fun symbol(name: ChordName) = spellPitchClass(name.root) + name.quality.symbol

    private fun describe(label: ChordLabel?): String = when (label) {
        null -> "blank"
        is ChordLabel.Chord -> symbol(label.name)
        is ChordLabel.Ambiguous -> "${symbol(label.primary)}|${symbol(label.secondary)}"
        else -> label.javaClass.simpleName
    }

    private fun stream(instrument: Instrument, extractor: ChordExtractor, take: StrumSynth.Take): Metrics {
        val detector = ChordDetector(Signals.SAMPLE_RATE, extractor = extractor)
        detector.setInstrument(instrument)
        val presenter = ChordPresenter()
        val window = detector.requiredSamples
        val metrics = Metrics()
        var chordFrames = 0
        var previousShown: ChordLabel? = null
        val firstHit = HashMap<StrumSynth.Segment, Int>()

        var end = window
        while (end <= take.audio.size) {
            val frame = take.audio.copyOfRange(end - window, end)
            val tier = if (chordFrames < CONFIRM_FRAMES) ChordTier.PROVISIONAL else ChordTier.CONFIRMED
            chordFrames++
            val reading = detector.analyse(frame, tier, !instrument.reentrant)
            val alone = (reading.label as? ChordLabel.Chord)?.name
            if (alone != null && alone.quality.tier == QualityTier.B && reading.bass != alone.root) metrics.tierBAlone++
            if (presenter.update(reading) == ChordDisplay.CLEARED) {
                detector.reset()
                presenter.reset()
                chordFrames = 0
            }
            val shown = presenter.current?.label
            if (shown != previousShown) metrics.shownChanges++
            previousShown = shown

            val segment = take.segments.firstOrNull { end - 1 >= it.start && end - 1 < it.end }
            if (segment != null) {
                metrics.frames++
                val truth = segment.chord
                val shownChord = (shown as? ChordLabel.Chord)?.name
                if (shownChord != null && same(shownChord, truth)) metrics.shownCorrect++
                val rawChord = (reading.label as? ChordLabel.Chord)?.name
                if (rawChord != null && same(rawChord, truth)) metrics.rawCorrect++
                if (namesIn(shown).any { same(it, truth) }) {
                    metrics.shownOffered++
                    if (segment !in firstHit) firstHit[segment] = end
                } else {
                    val key = "${symbol(truth)} -> ${describe(shown)}"
                    metrics.confusions[key] = (metrics.confusions[key] ?: 0) + 1
                }
            }
            end += HOP
        }
        for (segment in take.segments) {
            metrics.chordChanges++
            val hit = firstHit[segment]
            if (hit == null) metrics.missed++ else metrics.latenciesMs += (hit - segment.start) / Signals.SAMPLE_RATE * 1000
        }
        return metrics
    }

    private fun evaluate(instrument: Instrument, progressions: List<List<ChordName>>, extractor: ChordExtractor): Metrics {
        val total = Metrics()
        for ((index, progression) in progressions.withIndex()) {
            for (seed in SEEDS) {
                total.add(stream(instrument, extractor, takes.getOrPut(Triple(instrument.id, index, seed)) {
                    StrumSynth.take(instrument, progression, seed = seed * 101 + index)
                }))
            }
        }
        return total
    }

    private val takes = HashMap<Triple<String, Int, Int>, StrumSynth.Take>()

    @Test
    fun chromaHmmBeatsTheSieveOnStrummedProgressions() {
        val report = StringBuilder("Synthetic strummed progressions, streamed as TunerViewModel does\n")
        val results = HashMap<Pair<String, ChordExtractor>, Metrics>()
        for ((instrument, progressions) in listOf(guitar to guitarProgressions, ukulele to ukuleleProgressions)) {
            report.appendLine("=== ${instrument.id}: ${progressions.size} progressions x ${SEEDS.size} takes")
            for (extractor in listOf(ChordExtractor.SIEVE, ChordExtractor.CHROMA_HMM)) {
                val metrics = evaluate(instrument, progressions, extractor)
                results[instrument.id to extractor] = metrics
                report.appendLine(metrics.row(extractor.name))
                val top = metrics.confusions.entries.sortedByDescending { it.value }.take(6)
                report.appendLine("      misses: " + top.joinToString { "${it.key} x${it.value}" })
            }
        }
        File("build").mkdirs()
        File("build/chord-eval-report.txt").writeText(report.toString())
        println(report)

        for ((instrument, floor) in listOf(guitar to GUITAR_FLOOR, ukulele to UKULELE_FLOOR)) {
            val hmm = results.getValue(instrument.id to ChordExtractor.CHROMA_HMM)
            assertTrue(hmm.rate(hmm.shownOffered) >= floor, "${instrument.id}: offered fell below $floor\n$report")
            assertEquals(0, hmm.tierBAlone, "${instrument.id}: a tier-B quality was named without its bass")
        }
        for (instrument in listOf(guitar, ukulele)) {
            val sieve = results.getValue(instrument.id to ChordExtractor.SIEVE)
            val hmm = results.getValue(instrument.id to ChordExtractor.CHROMA_HMM)
            assertTrue(
                hmm.rate(hmm.shownOffered) > sieve.rate(sieve.shownOffered),
                "${instrument.id}: CHROMA_HMM offered the right chord less often than the sieve\n$report",
            )
            assertTrue(
                hmm.rate(hmm.shownCorrect) > sieve.rate(sieve.shownCorrect),
                "${instrument.id}: CHROMA_HMM named the right chord less often than the sieve\n$report",
            )
        }
    }

    private companion object {
        /** Matches `TunerViewModel.CHORD_EVERY` x `NOTE_HOP`. */
        const val HOP = 8 * 512

        /** Matches `TunerViewModel.CONFIRM_FRAMES`. */
        const val CONFIRM_FRAMES = 8

        val SEEDS = listOf(1, 2)

        /** Measured 83% (guitar) and 88% (ukulele) on 2026-11-01; the sieve scored 73% and 83%. */
        const val GUITAR_FLOOR = 0.75
        const val UKULELE_FLOOR = 0.80
    }

    @Test
    fun analysisFitsInsideTheHop() {
        val detector = ChordDetector(Signals.SAMPLE_RATE)
        detector.setInstrument(guitar)
        val take = StrumSynth.take(guitar, guitarProgressions[0], seed = 5)
        val window = detector.requiredSamples
        val first = System.nanoTime()
        detector.analyse(take.audio.copyOfRange(0, window), ChordTier.CONFIRMED)
        val buildMs = (System.nanoTime() - first) / 1e6

        var frames = 0
        val start = System.nanoTime()
        var end = window + HOP
        while (end <= take.audio.size) {
            detector.analyse(take.audio.copyOfRange(end - window, end), ChordTier.CONFIRMED)
            frames++
            end += HOP
        }
        val perFrameMs = (System.nanoTime() - start) / 1e6 / frames
        println("MEASURED first frame incl. template bank %.1f ms, then %.2f ms per frame".format(buildMs, perFrameMs))
        // JVM on a laptop, so loose: a phone is slower, but the hop is 85 ms.
        assertTrue(perFrameMs < 20.0, "analysis takes $perFrameMs ms a frame")
    }
}
