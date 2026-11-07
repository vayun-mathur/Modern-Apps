package com.vayunmathur.speech.util

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class WhisperFeaturesExtraTest {

    @Test
    fun constantsMatchWhisperBaseContract() {
        assertEquals(16000, WhisperFeatures.SAMPLE_RATE)
        assertEquals(400, WhisperFeatures.N_FFT)
        assertEquals(160, WhisperFeatures.HOP_LENGTH)
        assertEquals(80, WhisperFeatures.N_MELS)
        assertEquals(480000, WhisperFeatures.N_SAMPLES)
        assertEquals(3000, WhisperFeatures.N_FRAMES)
        assertEquals(201, WhisperFeatures.N_BINS)
    }

    @Test
    fun wrongSizedDoubleInputIsRejected() {
        assertFailsWith<IllegalArgumentException> { WhisperFeatures.logMel(DoubleArray(100)) }
        assertFailsWith<IllegalArgumentException> { WhisperFeatures.logMel(DoubleArray(0)) }
        assertFailsWith<IllegalArgumentException> {
            WhisperFeatures.logMel(DoubleArray(WhisperFeatures.N_SAMPLES + 1))
        }
    }

    @Test
    fun emptyShortInputYieldsConstantFloorTensor() {
        val out = WhisperFeatures.logMel(ShortArray(0))
        assertEquals(WhisperFeatures.N_MELS * WhisperFeatures.N_FRAMES, out.size)
        assertTrue(out.all { it == out[0] })
    }

    @Test
    fun pcmPeakNormalisationScalesExtremes() {
        // A full-scale square wave must produce a non-flat tensor bounded by the log rescale.
        val pcm = ShortArray(16000) { if (it % 32 < 16) Short.MAX_VALUE else Short.MIN_VALUE }
        val out = WhisperFeatures.logMel(pcm)
        assertTrue(out.any { it != out[0] })
        assertTrue(out.all { it.isFinite() })
    }

    @Test
    fun fftOfImpulseIsFlat() {
        val n = WhisperFeatures.N_FFT
        val re = DoubleArray(n); re[0] = 1.0
        val im = DoubleArray(n)
        Fft400().transform(re, im)
        for (k in 0 until n) {
            assertEquals(1.0, re[k], 1e-9, "re[$k]")
            assertEquals(0.0, im[k], 1e-9, "im[$k]")
        }
    }

    @Test
    fun fftOfConstantIsDcOnly() {
        val n = WhisperFeatures.N_FFT
        val re = DoubleArray(n) { 1.0 }
        val im = DoubleArray(n)
        Fft400().transform(re, im)
        assertEquals(n.toDouble(), re[0], 1e-6)
        for (k in 1 until n) {
            assertEquals(0.0, re[k], 1e-6, "re[$k]")
            assertEquals(0.0, im[k], 1e-6, "im[$k]")
        }
    }
}
