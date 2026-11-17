package com.vayunmathur.speech.platform

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Pins [WhisperFeatures] to HuggingFace `WhisperFeatureExtractor` output.
 *
 * This is the highest-value test in the module: a wrong log-mel does not throw, it makes the
 * decoder emit fluent nonsense, so nothing downstream would catch a regression here. The
 * expectations were produced by running the real extractor over the closed-form signal in
 * [referenceSignal] — see the generator noted in `scripts/ml/fetch_whisper.py`.
 *
 * Tolerances are loose enough to absorb the reference being computed in float32 while this
 * runs in double, and tight enough that any indexing, window, scale or transpose error fails.
 */
class WhisperFeaturesTest {

    /**
     * Closed-form so it is reproducible bit-for-bit outside Kotlin — a seeded PRNG would not
     * be, since numpy's stream differs from `java.util.Random`.
     */
    private fun referenceSignal(): DoubleArray {
        val out = DoubleArray(WhisperFeatures.N_SAMPLES)
        val sr = WhisperFeatures.SAMPLE_RATE.toDouble()
        for (i in 0 until 48000) {
            val t = i / sr
            var v = 0.50 * sin(2.0 * PI * 440.0 * t) +
                0.25 * sin(2.0 * PI * 1000.0 * t + 0.5) +
                0.12 * sin(2.0 * PI * 2500.0 * t + 1.25)
            v *= 0.6 + 0.4 * sin(2.0 * PI * 0.7 * t)
            out[i] = v
        }
        return out
    }

    @Test
    fun melFilterbankMatchesReference() {
        val f = WhisperFeatures.melFilters()
        assertEquals(WhisperFeatures.N_MELS * WhisperFeatures.N_BINS, f.size)

        // Total energy over the whole bank: catches a wrong mel scale or missing slaney norm.
        val sum = f.sum()
        assertTrue(
            abs(sum - 1.999024102917858) < 1e-9,
            "filterbank sum $sum != 1.999024102917858",
        )

        // Spot values, transposed from HuggingFace's [201, 80] into our [80, 201] layout.
        fun at(bin: Int, mel: Int) = f[mel * WhisperFeatures.N_BINS + bin]
        assertTrue(abs(at(1, 0) - 0.024862593984176087) < 1e-12, "bin1/mel0 = ${at(1, 0)}")
        assertTrue(abs(at(199, 79) - 0.00044875902758905584) < 1e-12, "bin199/mel79 = ${at(199, 79)}")
        assertEquals(0.0, at(0, 0), 1e-12)
        assertEquals(0.0, at(2, 0), 1e-12)
        assertEquals(0.0, at(100, 40), 1e-12)

        // Every filter must be non-negative and actually have some support.
        for (m in 0 until WhisperFeatures.N_MELS) {
            var peak = 0.0
            for (b in 0 until WhisperFeatures.N_BINS) {
                val v = at(b, m)
                assertTrue(v >= 0.0, "negative weight at mel $m bin $b")
                if (v > peak) peak = v
            }
            assertTrue(peak > 0.0, "mel filter $m is entirely zero")
        }
    }

    @Test
    fun logMelMatchesReference() {
        val mel = WhisperFeatures.logMel(referenceSignal())
        assertEquals(WhisperFeatures.N_MELS * WhisperFeatures.N_FRAMES, mel.size)

        fun at(m: Int, t: Int) = mel[m * WhisperFeatures.N_FRAMES + t].toDouble()

        // Aggregates first: a transpose or off-by-one frame shows up here even when
        // individual spot values happen to look plausible.
        var sum = 0.0
        var absSum = 0.0
        var lo = Double.MAX_VALUE
        var hi = -Double.MAX_VALUE
        for (v in mel) {
            sum += v
            absSum += abs(v.toDouble())
            if (v < lo) lo = v.toDouble()
            if (v > hi) hi = v.toDouble()
        }
        assertRel(-128690.03744095564, sum, 1e-4, "melSum")
        assertRel(135446.5666707158, absSum, 1e-4, "melAbsSum")
        assertTrue(abs(lo - -0.5618195533752441) < 1e-4, "melMin $lo")
        assertTrue(abs(hi - 1.4381804466247559) < 1e-4, "melMax $hi")

        val spots = listOf(
            Triple(0, 0, 0.9120575785636902),
            Triple(0, 1, 0.39997273683547974),
            Triple(0, 100, -0.5618195533752441),
            Triple(1, 0, 0.9148868918418884),
            Triple(10, 50, 1.3314776420593262),
            Triple(40, 137, -0.5618195533752441),
            Triple(79, 0, 0.00426250696182251),
            Triple(79, 299, -0.5371742248535156),
            Triple(0, 299, 0.3306320309638977),
            // Beyond the 3 s of signal everything sits on the -8-decade floor.
            Triple(0, 2999, -0.5618195533752441),
            Triple(79, 2999, -0.5618195533752441),
            Triple(40, 1500, -0.5618195533752441),
        )
        for ((m, t, want) in spots) {
            val got = at(m, t)
            assertTrue(abs(got - want) < 1e-3, "mel[$m,$t] = $got, expected $want")
        }
    }

    @Test
    fun shortInputIsPaddedAndLongInputTruncated() {
        // Silence must still yield a full-size tensor, and a flat one.
        val silence = WhisperFeatures.logMel(ShortArray(1600))
        assertEquals(WhisperFeatures.N_MELS * WhisperFeatures.N_FRAMES, silence.size)
        assertTrue(silence.all { it == silence[0] }, "silence should be a constant floor")

        // Over-long input must not overflow or resize.
        val long = WhisperFeatures.logMel(ShortArray(WhisperFeatures.N_SAMPLES * 2) {
            (3000.0 * sin(it / 40.0)).toInt().toShort()
        })
        assertEquals(WhisperFeatures.N_MELS * WhisperFeatures.N_FRAMES, long.size)
        assertTrue(long.any { it != long[0] }, "tone should not be flat")
    }

    @Test
    fun fft400MatchesDirectDft() {
        // The 16x25 split is where an index slip would be invisible downstream, so check it
        // against a textbook O(n^2) DFT of the same input.
        val n = WhisperFeatures.N_FFT
        val re = DoubleArray(n) { sin(it * 0.37) + 0.5 * sin(it * 1.9 + 0.2) }
        val im = DoubleArray(n)
        val expectRe = DoubleArray(n)
        val expectIm = DoubleArray(n)
        for (k in 0 until n) {
            var sr = 0.0
            var si = 0.0
            for (t in 0 until n) {
                val a = -2.0 * PI * k * t / n
                sr += re[t] * kotlin.math.cos(a)
                si += re[t] * sin(a)
            }
            expectRe[k] = sr
            expectIm[k] = si
        }
        Fft400().transform(re, im)
        for (k in 0 until n) {
            assertTrue(abs(re[k] - expectRe[k]) < 1e-8, "re[$k] ${re[k]} != ${expectRe[k]}")
            assertTrue(abs(im[k] - expectIm[k]) < 1e-8, "im[$k] ${im[k]} != ${expectIm[k]}")
        }
    }

    private fun assertRel(want: Double, got: Double, tol: Double, label: String) {
        val rel = abs(got - want) / abs(want)
        assertTrue(rel < tol, "$label = $got, expected $want (rel $rel)")
    }
}
