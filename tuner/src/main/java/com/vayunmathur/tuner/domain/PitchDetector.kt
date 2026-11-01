package com.vayunmathur.tuner.domain

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.pow

/** Which of the two operating bands produced a reading (TUNER_SPEC A.4). */
enum class PitchBand(
    val windowSize: Int,
    val hopSize: Int,
    val subWindow: Int,
    val separation: Int,
    /** Samples [PredominantPitch] reads; longer than the YIN window in the fast band, to resolve semitones. */
    val salienceWindow: Int,
) {
    /** Guitar D3 and up, ukulele, piano E3+. ~32 ms latency. */
    FAST(windowSize = 2048, hopSize = 512, subWindow = 1024, separation = 1024, salienceWindow = 4096),

    /** Bass, guitar low E/A, piano bass. ~107 ms latency, and worth every millisecond. */
    LOW(windowSize = 8192, hopSize = 1024, subWindow = 4096, separation = 4096, salienceWindow = 8192),
}

/** One accepted frame of the Note tab's pipeline. */
data class PitchEstimate(
    val frequencyHz: Double,
    val coarseHz: Double,
    val aperiodicity: Double,
    val stiffness: Double,
    val harmonicsUsed: Int,
    val band: PitchBand,
    val rms: Double,
)

/** Why a frame produced no reading, so the UI can say "listening" rather than show a stale number. */
enum class PitchRejection { TOO_QUIET, NOT_PERIODIC, NO_HARMONICS, INCONSISTENT, OUT_OF_RANGE }

/** Either an accepted [PitchEstimate] or the reason there is not one. */
sealed interface PitchFrame {
    /** An accepted reading. */
    data class Detected(val estimate: PitchEstimate) : PitchFrame

    /** No reading this frame. */
    data class Silent(val reason: PitchRejection) : PitchFrame
}

/**
 * The monophonic pitch pipeline, behind an interface so the Kotlin implementation can be
 * swapped for a native one if profiling ever justifies it (TUNER_SPEC G.3).
 */
interface PitchAnalyzer {
    /** Samples the analyser needs to be handed on each call. */
    val requiredSamples: Int

    /** Analyses the most recent [requiredSamples] of [samples]. */
    fun analyse(samples: DoubleArray, minHz: Double, maxHz: Double): PitchFrame

    /** Forgets band state and the noise floor. Call when capture restarts. */
    fun reset()
}

/**
 * Predominant-note selection, then two-stage YIN and harmonic phase-slope, with the operating band
 * selected by the detected octave rather than by compromising on either window length.
 *
 * [PredominantPitch] decides which note is sounding; YIN only measures it, searching within
 * [NOTE_SPAN_SEMITONES] of that note. Unrestricted, YIN reports the period of the whole mixture,
 * which with another string ringing is a note far below the one plucked.
 *
 * Cold start is the low band, so a bass note is caught on the first frame instead of after a
 * failed fast-band attempt.
 */
class YinPhaseSlopeAnalyzer(val sampleRate: Double) : PitchAnalyzer {
    private val yin = PitchBand.entries.associateWith { Yin(it.windowSize, sampleRate) }
    private val refiner = PitchBand.entries.associateWith {
        PhaseSlope(sampleRate, it.subWindow, it.separation)
    }
    private val predominant = PredominantPitch(sampleRate)

    private var band = PitchBand.LOW
    private var lowVotes = 0
    private var highVotes = 0
    private var noiseFloor = 1e-4

    override val requiredSamples: Int = PitchBand.LOW.windowSize

    override fun reset() {
        band = PitchBand.LOW
        lowVotes = 0
        highVotes = 0
        noiseFloor = 1e-4
    }

    override fun analyse(samples: DoubleArray, minHz: Double, maxHz: Double): PitchFrame {
        require(samples.size >= requiredSamples) { "need at least $requiredSamples samples" }
        val active = band
        val offset = samples.size - active.windowSize
        val note = predominant.estimate(samples, active.salienceWindow, minHz, maxHz)
            ?: return reject(PitchRejection.NOT_PERIODIC, 0.0)
        val span = 2.0.pow(NOTE_SPAN_SEMITONES / 12.0)
        val coarse = yin.getValue(active).analyse(
            samples,
            offset,
            maxOf(minHz, note / span),
            minOf(maxHz, note * span),
        ) ?: return reject(PitchRejection.OUT_OF_RANGE, 0.0)

        if (coarse.rms < noiseFloor * NOISE_MARGIN) {
            return reject(PitchRejection.TOO_QUIET, coarse.rms)
        }
        if (coarse.aperiodicity > APERIODICITY_GATE) {
            return reject(PitchRejection.NOT_PERIODIC, coarse.rms)
        }

        updateBand(coarse.frequencyHz)

        val estimator = refiner.getValue(active)
        val refineOffset = samples.size - estimator.requiredSamples
        val fine = estimator.refine(samples, refineOffset, coarse.frequencyHz)
            ?: return reject(PitchRejection.NO_HARMONICS, coarse.rms)

        // If the refined answer has moved more than a quarter-tone, the harmonic set was
        // misassigned: throw the frame away rather than report a confident wrong octave.
        if (abs(1200.0 * log2(fine.frequencyHz / coarse.frequencyHz)) > CROSS_CHECK_CENTS) {
            return reject(PitchRejection.INCONSISTENT, coarse.rms)
        }

        return PitchFrame.Detected(
            PitchEstimate(
                frequencyHz = fine.frequencyHz,
                coarseHz = coarse.frequencyHz,
                aperiodicity = coarse.aperiodicity,
                stiffness = fine.stiffness,
                harmonicsUsed = fine.harmonicsUsed,
                band = active,
                rms = coarse.rms,
            ),
        )
    }

    private fun reject(reason: PitchRejection, rms: Double): PitchFrame {
        // Asymmetric on purpose. Averaging rejected frames symmetrically latches the gate shut: a
        // strum or an attack transient is loud *and* aperiodic, so it is rejected at full signal
        // level and drags the floor up to that level, after which every frame fails the level
        // gate - which is itself a rejection, so it keeps feeding the floor and it never reopens.
        if (rms > 0.0) {
            noiseFloor = if (rms < noiseFloor) {
                FLOOR_FALL * noiseFloor + (1.0 - FLOOR_FALL) * rms
            } else {
                // Creep by a fixed small factor rather than towards rms, so a loud rejected
                // frame cannot pull the floor up to its own level however long it lasts.
                (noiseFloor * FLOOR_CREEP).coerceAtMost(rms)
            }.coerceAtLeast(MIN_NOISE_FLOOR)
        }
        return PitchFrame.Silent(reason)
    }

    /** Hysteresis with a 25 Hz dead zone, so a note sitting on the boundary does not flap. */
    private fun updateBand(frequencyHz: Double) {
        if (frequencyHz < LOW_BAND_ENTRY_HZ) {
            lowVotes++
            highVotes = 0
            if (lowVotes >= BAND_VOTES) band = PitchBand.LOW
        } else if (frequencyHz > FAST_BAND_ENTRY_HZ) {
            highVotes++
            lowVotes = 0
            if (highVotes >= BAND_VOTES) band = PitchBand.FAST
        } else {
            lowVotes = 0
            highVotes = 0
        }
    }

    private companion object {
        /**
         * Measured at the predominant note: noise never scores below 0.89, an A4 over six open
         * strings at -12 dB scores 0.35, and an equal-level chord - not one note - 0.79. The old
         * 0.20 was set for unrestricted YIN, which got its low numbers by settling on the
         * mixture's period instead of the plucked note.
         */
        const val APERIODICITY_GATE = 0.45
        const val CROSS_CHECK_CENTS = 25.0
        const val LOW_BAND_ENTRY_HZ = 150.0
        const val FAST_BAND_ENTRY_HZ = 175.0
        const val BAND_VOTES = 3

        /**
         * How far from the predominant note YIN may look. The salience grid is 10 cents and the
         * displayed range is +/-50, so this only has to hold one dip of the difference function.
         */
        const val NOTE_SPAN_SEMITONES = 1.0

        /** +12 dB above the tracked floor. */
        const val NOISE_MARGIN = 3.98

        /** Weight kept when the frame is quieter than the floor: ~10 frames, so a room that
         *  goes quiet is followed almost immediately. */
        const val FLOOR_FALL = 0.90

        /** Per-frame rise when the frame is louder: doubles in ~15 s, so the floor can still
         *  climb out of a stale low estimate without a strum dragging it up. */
        const val FLOOR_CREEP = 1.0005

        const val MIN_NOISE_FLOOR = 1e-6
    }
}

/**
 * One-pole smoothing of the *cents* value (TUNER_SPEC A.5).
 *
 * Raw frames jitter by a couple of tenths of a cent, which makes a needle look nervous. Large
 * jumps snap instead of gliding, because a jump means a new note rather than a drifting one.
 */
class CentsSmoother(private val timeConstantSeconds: Double = 0.080) {
    private var value: Double? = null

    /** Feeds a raw measurement and returns the value to display. */
    fun update(cents: Double, hopSeconds: Double): Double {
        val previous = value
        val next = if (previous == null || abs(cents - previous) > SNAP_CENTS) {
            cents
        } else {
            val alpha = 1.0 - exp(-hopSeconds / timeConstantSeconds)
            previous + alpha * (cents - previous)
        }
        value = next
        return next
    }

    /** Drops the smoothed value, so the next reading starts clean. */
    fun reset() {
        value = null
    }

    private companion object {
        const val SNAP_CENTS = 20.0
    }
}
