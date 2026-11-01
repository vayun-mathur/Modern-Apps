package com.vayunmathur.tuner.domain

import com.vayunmathur.tuner.data.Instrument
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * Labelled chord progressions strummed on a modelled fretted instrument.
 *
 * Built to be unlike the detector's own model wherever that is cheap to arrange, because a corpus
 * generated from the templates it is scored against measures nothing (see `HarmonicSieve`). Each
 * take draws its own partial law, inharmonicity, detuning, strum timing, per-string level and
 * decay, room noise level, and picks among every shape [VoicingGenerator] offers rather than the
 * recommended one.
 *
 * Still synthetic: no body resonance, sympathetic strings, fret buzz or microphone colouring. It
 * is a regression and comparison tool, not a prediction of real accuracy - Record-3 is that.
 */
internal object StrumSynth {
    data class Segment(val chord: ChordName, val start: Int, val end: Int)

    class Take(val audio: DoubleArray, val segments: List<Segment>) {
        /** The chord sounding at [sample], or null before the first strum. */
        fun chordAt(sample: Int): ChordName? = segments.firstOrNull { sample >= it.start && sample < it.end }?.chord
    }

    fun take(
        instrument: Instrument,
        progression: List<ChordName>,
        seed: Int,
        secondsPerChord: Double = 2.0,
    ): Take {
        val random = Random(seed)
        val rate = Signals.SAMPLE_RATE
        val chordSamples = (secondsPerChord * rate).toInt()
        val lead = (0.3 * rate).toInt()
        val total = lead + chordSamples * progression.size
        val out = DoubleArray(total)

        val rolloff = 0.8 + 1.4 * random.nextDouble()
        val partialJitter = List(PARTIALS) { 0.6 + 0.8 * random.nextDouble() }
        val stiffness = 3e-4 * random.nextDouble()
        // Biased flat to match TuningOffset, which reads more than 25 cents sharp as flat of the
        // semitone above - an ambiguity in the audio, not something either extractor can resolve.
        val globalCents = -45.0 + 65.0 * random.nextDouble()

        val segments = ArrayList<Segment>()
        val notes = ArrayList<Note>()
        val lastOnString = HashMap<Int, Note>()
        for ((index, chord) in progression.withIndex()) {
            val start = lead + index * chordSamples
            val end = start + chordSamples
            segments += Segment(chord, start, end)
            val shapes = VoicingGenerator.generate(instrument, chord)
            require(shapes.isNotEmpty()) { "no voicing for $chord on ${instrument.id}" }
            val shape = shapes[random.nextInt(shapes.size)]

            // Refretting damps whatever was ringing.
            for (note in lastOnString.values) note.dampAt = minOf(note.dampAt, start)

            val strums = if (random.nextBoolean()) listOf(start) else listOf(start, start + chordSamples / 2)
            for ((k, strumStart) in strums.withIndex()) {
                val downward = k % 2 == 0
                val order = instrument.strings.indices
                    .filter { shape.frets[it] >= 0 }
                    .sortedBy { instrument.strings[it].openMidi.let { open -> if (downward) open else -open } }
                val stagger = (0.005 + 0.020 * random.nextDouble()) * rate
                for ((position, string) in order.withIndex()) {
                    val midi = instrument.strings[string].openMidi + shape.frets[string]
                    val cents = globalCents - 4.0 + 8.0 * random.nextDouble()
                    val note = Note(
                        hz = 440.0 * 2.0.pow((midi - 69) / 12.0 + cents / 1200.0),
                        onset = strumStart + (position * stagger).toInt(),
                        level = 0.12 * (0.5 + 0.5 * random.nextDouble()),
                        decay = 0.6 + 1.9 * random.nextDouble(),
                        dampAt = total,
                    )
                    // A restrike damps the same string's previous note.
                    lastOnString[string]?.let { it.dampAt = minOf(it.dampAt, note.onset) }
                    lastOnString[string] = note
                    notes += note
                }
            }
        }
        for (note in notes) render(out, note, rolloff, partialJitter, stiffness)

        addRoom(out, snrDb = 15.0 + 20.0 * random.nextDouble(), seed = seed)
        return Take(out, segments)
    }

    private class Note(val hz: Double, val onset: Int, val level: Double, val decay: Double, var dampAt: Int)

    private fun render(out: DoubleArray, note: Note, rolloff: Double, jitter: List<Double>, stiffness: Double) {
        val rate = Signals.SAMPLE_RATE
        val stop = minOf(out.size, note.dampAt + (DAMP_SECONDS * 6 * rate).toInt())
        for (n in 1..PARTIALS) {
            val hz = n * note.hz * sqrt(1.0 + stiffness * n * n)
            if (hz >= rate / 2) break
            val amplitude = note.level * jitter[n - 1] / n.toDouble().pow(rolloff)
            // Upper partials die faster, as on a real string.
            val decay = note.decay / (1.0 + 0.3 * (n - 1))
            val step = 2.0 * PI * hz / rate
            for (i in note.onset until stop) {
                val t = (i - note.onset) / rate
                var envelope = exp(-t / decay)
                if (i > note.dampAt) envelope *= exp(-(i - note.dampAt) / rate / DAMP_SECONDS)
                out[i] += amplitude * envelope * sin(step * (i - note.onset) + n)
            }
        }
        // Pick noise.
        val random = Random(note.onset)
        val burst = (0.004 * rate).toInt()
        for (i in note.onset until minOf(out.size, note.onset + burst)) {
            out[i] += note.level * 0.5 * (2 * random.nextDouble() - 1) * exp(-(i - note.onset) / (burst / 4.0))
        }
    }

    private fun addRoom(out: DoubleArray, snrDb: Double, seed: Int) {
        var power = 0.0
        for (v in out) power += v * v
        power /= out.size
        val sigma = sqrt(power / 10.0.pow(snrDb / 10.0))
        val room = Signals.pinkNoise(out.size, sigma = sigma, seed = seed + 1)
        for (i in out.indices) out[i] += room[i]
    }

    private const val PARTIALS = 10
    private const val DAMP_SECONDS = 0.04
}
