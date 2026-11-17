package com.vayunmathur.speech.platform

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Whisper log-mel feature extraction: 16 kHz mono PCM to the `[80, 3000]` `input_features`
 * tensor the encoder expects. This replaces the `fbank` ncnn net that the old AAR path
 * used, and is a port of HuggingFace `WhisperFeatureExtractor` (slaney mel scale, slaney
 * normalisation) verified against it to ~1e-7.
 *
 * A wrong mel does not throw — it produces fluent, confident nonsense — so the constants here
 * are asserted against upstream `preprocessor_config.json` by
 * `scripts/ml/fetch_whisper.py`, and the output is pinned in `WhisperFeaturesTest`.
 */
object WhisperFeatures {

    const val SAMPLE_RATE = 16000

    /** Window size. Deliberately not a power of two — see [Fft400]. */
    const val N_FFT = 400
    const val HOP_LENGTH = 160
    const val N_MELS = 80

    /** Whisper always consumes exactly 30 s, padded or truncated. */
    const val N_SAMPLES = SAMPLE_RATE * 30
    const val N_FRAMES = 3000

    /** Real-FFT bin count: `N_FFT / 2 + 1`. */
    const val N_BINS = N_FFT / 2 + 1
    /** int16 full-scale peak for normalising PCM to [-1, 1]. */
    private const val PCM_PEAK = 32768.0
    /** Log floor below which mel energy is treated as silence. */
    private const val LOG_FLOOR = 1e-10
    /** Decades of dynamic range kept above the loudest bin. */
    private const val DECADES_RANGE = 8.0
    /** Offset/scale mapping the clamped log range onto roughly [-1, 1]. */
    private const val LOG_OFFSET = 4.0
    /** Hann window scale (0.5 - 0.5 * cos). */
    private const val HANN_SCALE = 0.5
    /** Two pi, for the Hann window phase. */
    private const val TWO_PI = 2.0 * PI
    /** Slaney normalisation numerator (unit area per filter). */
    private const val NORM_NUM = 2.0

    /**
     * `[80, 3000]` row-major (`mel * N_FRAMES + frame`) log-mel for [pcm16k], 16 kHz mono.
     * Short input is zero-padded to 30 s, long input truncated, matching the reference.
     */
    fun logMel(pcm16k: ShortArray): FloatArray {
        val samples = DoubleArray(N_SAMPLES)
        val count = min(pcm16k.size, N_SAMPLES)
        for (i in 0 until count) samples[i] = pcm16k[i] / PCM_PEAK
        return logMel(samples)
    }

    /** [samples] is exactly [N_SAMPLES] of mono audio in `[-1, 1]`. */
    internal fun logMel(samples: DoubleArray): FloatArray {
        require(samples.size == N_SAMPLES) { "expected $N_SAMPLES samples, got ${samples.size}" }

        val fft = Fft400()
        val window = hannPeriodic(N_FFT)
        val filters = melFilters()

        val frameRe = DoubleArray(N_FFT)
        val frameIm = DoubleArray(N_FFT)
        val power = DoubleArray(N_BINS)

        // Mel energies first, so the global-max clamp below can see every frame.
        val mel = DoubleArray(N_MELS * N_FRAMES)

        val pad = N_FFT / 2
        for (t in 0 until N_FRAMES) {
            // `center=True`: each frame is centred on t*hop, with reflected edges.
            val start = t * HOP_LENGTH - pad
            for (i in 0 until N_FFT) {
                frameRe[i] = samples[reflect(start + i, N_SAMPLES)] * window[i]
                frameIm[i] = 0.0
            }
            fft.transform(frameRe, frameIm)
            for (b in 0 until N_BINS) {
                power[b] = frameRe[b] * frameRe[b] + frameIm[b] * frameIm[b]
            }
            for (m in 0 until N_MELS) {
                var acc = 0.0
                val base = m * N_BINS
                for (b in 0 until N_BINS) acc += filters[base + b] * power[b]
                mel[m * N_FRAMES + t] = acc
            }
        }

        // log10 with a floor, then clamp to within 8 decades of the loudest bin and rescale
        // to roughly [-1, 1] - the exact reference post-processing.
        var maxLog = -Double.MAX_VALUE
        for (i in mel.indices) {
            val v = log10(max(mel[i], LOG_FLOOR))
            mel[i] = v
            if (v > maxLog) maxLog = v
        }
        val floor = maxLog - DECADES_RANGE
        val out = FloatArray(mel.size)
        for (i in mel.indices) out[i] = ((max(mel[i], floor) + LOG_OFFSET) / LOG_OFFSET).toFloat()
        return out
    }

    /**
     * Mirror index without repeating the edge sample, matching `numpy.pad(mode="reflect")`:
     * for `[1,2,3,4,5]` the left pad is `3,2`, not `2,1`.
     */
    private fun reflect(index: Int, length: Int): Int {
        var i = index
        if (i < 0) i = -i
        if (i >= length) i = 2 * length - 2 - i
        return i
    }

    /** Periodic (not symmetric) Hann, i.e. `numpy.hanning(n + 1)[:-1]`. */
    private fun hannPeriodic(n: Int): DoubleArray =
        DoubleArray(n) { HANN_SCALE - HANN_SCALE * cos(TWO_PI * it / n) }

    // ---- slaney mel filterbank ----
    private const val MEL_MIN_LOG_HZ = 1000.0
    private const val MEL_MIN_LOG_MEL = 15.0
    /** Slaney log denominator: mel above 15 uses natural-log scaling. */
    private const val MEL_LOG_BASE = 6.4
    /** Slaney log slope factor. */
    private const val MEL_LOG_SLOPE = 27.0
    /** Linear-region slope numerator (3 Hz per 200 mel below the log knee). */
    private const val MEL_LINEAR_NUM = 3.0
    /** Linear-region scale denominator. */
    private const val MEL_LINEAR_DEN = 200.0
    private fun hzToMel(hz: Double): Double {
        if (hz >= MEL_MIN_LOG_HZ) {
            return MEL_MIN_LOG_MEL + ln(hz / MEL_MIN_LOG_HZ) * (MEL_LOG_SLOPE / ln(MEL_LOG_BASE))
        }
        return MEL_LINEAR_NUM * hz / MEL_LINEAR_DEN
    }
    private fun melToHz(mel: Double): Double {
        if (mel >= MEL_MIN_LOG_MEL) {
            return MEL_MIN_LOG_HZ * exp((ln(MEL_LOG_BASE) / MEL_LOG_SLOPE) * (mel - MEL_MIN_LOG_MEL))
        }
        return MEL_LINEAR_DEN * mel / MEL_LINEAR_NUM
    }

    /**
     * `[80, 201]` row-major (`mel * N_BINS + bin`) triangular filterbank, slaney-normalised.
     *
     * Note the layout is the transpose of HuggingFace's `mel_filters` (`[201, 80]`); it is
     * stored mel-major here so the inner product above walks contiguous memory.
     */
    internal fun melFilters(): DoubleArray {
        val nyquist = SAMPLE_RATE / 2.0
        // N_MELS + 2 mel-spaced points give N_MELS triangles, each spanning its neighbours.
        val edges = DoubleArray(N_MELS + 2).also { e ->
            val lo = hzToMel(0.0)
            val hi = hzToMel(nyquist)
            for (i in e.indices) e[i] = melToHz(lo + (hi - lo) * i / (N_MELS + 1))
        }
        val binHz = DoubleArray(N_BINS) { nyquist * it / (N_BINS - 1) }

        val out = DoubleArray(N_MELS * N_BINS)
        for (m in 0 until N_MELS) {
            val left = edges[m]
            val center = edges[m + 1]
            val right = edges[m + 2]
            // Slaney normalisation: unit area per filter rather than unit peak.
            val enorm = NORM_NUM / (right - left)
            for (b in 0 until N_BINS) {
                val f = binHz[b]
                val up = (f - left) / (center - left)
                val down = (right - f) / (right - center)
                out[m * N_BINS + b] = max(0.0, min(up, down)) * enorm
            }
        }
        return out
    }
}

/**
 * Complex FFT of exactly 400 points, as `n_fft` requires.
 *
 * 400 is not a power of two, so radix-2 alone does not apply, and zero-padding to 512 would
 * silently change the bin spacing (201 bins at 40 Hz becomes 257 at 31.25 Hz) and so the mel
 * projection. 400 factors as 16 x 25, so this uses the four-step Cooley-Tukey split with
 * `n = n1 + 16*n2` and `k = k2 + 25*k1`:
 *
 *     X[k2 + 25*k1] = sum(n1) W_16^(n1*k1) * W_400^(n1*k2) * ( sum(n2) x[n1 + 16*n2] * W_25^(n2*k2) )
 *
 * i.e. sixteen naive 25-point DFTs, a twiddle multiply, then twenty-five radix-2 16-point
 * FFTs. Buffers are preallocated because this runs 3000 times per utterance.
 */
internal class Fft400 {

    private val twRe = DoubleArray(N) { cos(-2.0 * PI * it / N) }
    private val twIm = DoubleArray(N) { sin(-2.0 * PI * it / N) }
    private val t2Re = DoubleArray(N2) { cos(-2.0 * PI * it / N2) }
    private val t2Im = DoubleArray(N2) { sin(-2.0 * PI * it / N2) }

    private val stageRe = DoubleArray(N)
    private val stageIm = DoubleArray(N)
    private val colRe = DoubleArray(N1)
    private val colIm = DoubleArray(N1)

    /** In-place: [re]/[im] are length-400 and hold the transform on return. */
    fun transform(re: DoubleArray, im: DoubleArray) {
        // Stage 1 — a 25-point DFT for each of the 16 decimated sub-sequences, then twiddle.
        // Input is real here (im is all zero), so the inner loop only reads `re`.
        for (n1 in 0 until N1) {
            for (k2 in 0 until N2) {
                var sr = 0.0
                var si = 0.0
                for (n2 in 0 until N2) {
                    val v = re[n1 + N1 * n2]
                    val idx = (n2 * k2) % N2
                    sr += v * t2Re[idx]
                    si += v * t2Im[idx]
                }
                val idx = (n1 * k2) % N
                val wr = twRe[idx]
                val wi = twIm[idx]
                stageRe[n1 * N2 + k2] = sr * wr - si * wi
                stageIm[n1 * N2 + k2] = sr * wi + si * wr
            }
        }

        // Stage 2 — a radix-2 FFT across n1 for each k2, scattered to k2 + 25*k1.
        for (k2 in 0 until N2) {
            for (n1 in 0 until N1) {
                colRe[n1] = stageRe[n1 * N2 + k2]
                colIm[n1] = stageIm[n1 * N2 + k2]
            }
            fft16(colRe, colIm)
            for (k1 in 0 until N1) {
                re[k2 + N2 * k1] = colRe[k1]
                im[k2 + N2 * k1] = colIm[k1]
            }
        }
    }

    /** In-place radix-2 decimation-in-time FFT of length [N1] (16). */
    private fun fft16(re: DoubleArray, im: DoubleArray) {
        var j = 0
        for (i in 1 until N1) {
            var bit = N1 shr 1
            while (j and bit != 0) {
                j = j xor bit
                bit = bit shr 1
            }
            j = j or bit
            if (i < j) {
                var t = re[i]; re[i] = re[j]; re[j] = t
                t = im[i]; im[i] = im[j]; im[j] = t
            }
        }
        var len = 2
        while (len <= N1) {
            val half = len shr 1
            val ang = -2.0 * PI / len
            val wr = cos(ang)
            val wi = sin(ang)
            var i = 0
            while (i < N1) {
                var cr = 1.0
                var ci = 0.0
                for (k in 0 until half) {
                    val ur = re[i + k]
                    val ui = im[i + k]
                    val lr = re[i + k + half]
                    val li = im[i + k + half]
                    val vr = lr * cr - li * ci
                    val vi = lr * ci + li * cr
                    re[i + k] = ur + vr
                    im[i + k] = ui + vi
                    re[i + k + half] = ur - vr
                    im[i + k + half] = ui - vi
                    val ncr = cr * wr - ci * wi
                    ci = cr * wi + ci * wr
                    cr = ncr
                }
                i += len
            }
            len = len shl 1
        }
    }

    private companion object {
        const val N = WhisperFeatures.N_FFT
        const val N1 = 16
        const val N2 = 25
    }
}
