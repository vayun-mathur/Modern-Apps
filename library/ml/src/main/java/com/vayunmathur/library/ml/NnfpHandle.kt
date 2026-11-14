package com.vayunmathur.library.ml

import android.content.res.AssetManager
import android.os.ParcelFileDescriptor
import com.vayunmathur.library.log.Log

/**
 * On-device audio fingerprinting: the Now Playing embedding network on the Vulkan compute
 * runtime.
 *
 * Ten alternating frequency and time convolutions over a 32-bin log-mel spectrogram, then a
 * depthwise head, at 172,576 parameters. [embed] turns 4.8 seconds of microphone audio into 64
 * numbers that identify what is playing. See `library/ml/src/main/rust/src/nets/nnfp.rs`.
 *
 * # It describes, it does not decide
 *
 * The embedding is a *descriptor*, not an answer. Matching it against a song database, deciding
 * whether the match is good enough, and pooling several windows into one query are all the
 * caller's, because none of them are in the model. In particular the vector is **not
 * normalised**: whether to compare by cosine, by L2 or through a product quantiser could not be
 * recovered from the model, so this does not invent a convention.
 *
 * # The front end is native too
 *
 * [embed] takes raw PCM, not features. The log-mel front end — a periodic Hann window, a
 * 512-point transform, a 32-channel mel bank over 60–3800 Hz — runs inside the same call, so
 * there is no feature-extraction step in Kotlin to keep in sync with the network it feeds. See
 * `library/ml/src/main/rust/src/microfrontend.rs`, which is written against a config rather
 * than against this model, so another audio net can reuse it.
 *
 * # No state, so no warm-up
 *
 * The model was exported as a *streaming* graph, evaluated once per 10 ms with six circular
 * buffers carrying state between calls. That is unrolled here into a fixed [WINDOW_SAMPLES]
 * window, which is the whole receptive field. So [embed] is a pure function: two calls with the
 * same samples give the same 64 numbers, there is nothing to prime, and dropping a window
 * corrupts nothing.
 *
 * Callers step a ring buffer forward by whatever hop they like, but note the window is 4.8 s:
 * advancing by one 10 ms frame gives a 99.8% overlapping window and almost the same embedding.
 * Something on the order of half a second to a second is the useful range.
 *
 * # Fidelity
 *
 * The weights are dequantised from the export's int8 to fp16, because at this size int8 would
 * save nine kilobytes and cost accuracy. The consequence worth knowing: this is a float
 * evaluation of a network trained quantisation-aware, and it has **not** been compared against
 * the original runtime — only against an independently computed reference for the front end.
 * Treat embeddings as self-consistent within this implementation rather than interchangeable
 * with any produced elsewhere.
 *
 * # One bundled asset, 340 KiB
 *
 * `nnfp.maml` ships inside the APK, so this has an [inAssets] and no download. An asset must be
 * stored **uncompressed** for this to work at all: `AssetManager.openFd` throws for a deflated
 * entry. That is what `noCompress += "maml"` in the app's `build.gradle.kts` is for.
 *
 * # Availability
 *
 * Construction never throws. [isAvailable] is false when `libmodelrunner.so` is missing for
 * this ABI, when the asset is absent, compressed or malformed, or when the device cannot give
 * us a Vulkan device with fp16 compute — and then [embed] returns null. Gate the feature on it
 * rather than offering recognition that cannot run.
 *
 * # Threading
 *
 * Not thread-safe. A caller must hold a lock across [embed] and [close].
 */
class NnfpHandle private constructor(private val source: String) : AutoCloseable {
    private var handle: Long = 0L

    /** True if the graph came up and is the file this runtime was built against. */
    val isAvailable: Boolean get() = handle != 0L

    /**
     * The [EMBEDDING] fingerprint values for one window, or null on failure.
     *
     * [pcm] must be exactly [WINDOW_SAMPLES] mono 16-bit samples at [SAMPLE_RATE] — 4.8
     * seconds. A wrong length returns null rather than padding or truncating, because either
     * would silently describe audio that was not recorded.
     *
     * `ShortArray` because that is what `AudioRecord.read` produces and what the fixed-point
     * front end wants. There is no `FloatArray` overload: the conversion would be lossy in one
     * direction and pointless in the other.
     */
    fun embed(pcm: ShortArray): FloatArray? {
        if (handle == 0L) return null
        if (pcm.size != WINDOW_SAMPLES) return null
        return MlNative.nnfpEmbed(handle, pcm)
    }

    /** Free the network. Idempotent. */
    override fun close() {
        val live = handle
        handle = 0L
        if (live != 0L) MlNative.destroyNnfp(live)
    }

    override fun toString(): String = "Now Playing fingerprinter from $source"

    /** Front-end window constants for the Now Playing fingerprint network. */
    companion object {
        private const val TAG = "NnfpHandle"

        /** Samples per second the model was trained at. Resample anything else before [embed]. */
        const val SAMPLE_RATE = 16_000

        /** Samples in one analysis window: 25 ms. */
        const val FRAME_SAMPLES = 400

        /** Samples between windows: 10 ms, so frames arrive at 100 Hz. */
        const val HOP_SAMPLES = 160

        /** Log-mel frames behind one embedding — the network's receptive field. */
        const val WINDOW_FRAMES = 478

        /**
         * Samples [embed] requires: `FRAME_SAMPLES + HOP_SAMPLES * (WINDOW_FRAMES - 1)`, or
         * 4.795 s. Longer than [WINDOW_FRAMES] hops because the first frame needs a whole
         * window before the hops start.
         *
         * As `ShortArray` that is 153 KiB, which is the size a caller's ring buffer has to be.
         */
        const val WINDOW_SAMPLES = FRAME_SAMPLES + HOP_SAMPLES * (WINDOW_FRAMES - 1)

        /** Values in one fingerprint. */
        const val EMBEDDING = 64

        /** The one graph. Native checks its graph id, so a wrong file fails at load. */
        const val GRAPH = "nnfp.maml"

        /**
         * The model from the APK's assets, which is the only place it lives.
         *
         * No `inDirectory` counterpart: at 340 KiB this is bundled, so there is no download
         * directory to look in.
         */
        fun inAssets(assets: AssetManager, path: String = GRAPH): NnfpHandle {
            val instance = NnfpHandle("the APK's $path")
            instance.handle = if (!MlNative.isAvailable) {
                0L
            } else {
                try {
                    create(assets, path)
                } catch (expected: Exception) {
                    Log.error(TAG, "cannot open $path", expected)
                    0L
                }
            }
            return instance
        }

        /**
         * Open the asset and hand the descriptor over.
         *
         * `use` rather than a bare `close`, and it is load-bearing in both directions — the
         * same argument [MaiaHandle]'s asset path makes. On the happy path the descriptor has
         * already been detached and `AssetFileDescriptor.close` only releases the wrapper; if
         * `detachFd` throws instead, the close is the real one, and a leaked descriptor onto
         * the APK would last the life of the process.
         *
         * The inner `finally` covers the remaining window: after the descriptor has been given
         * up but before native has adopted it.
         */
        private fun create(assets: AssetManager, path: String): Long =
            assets.openFd(path).use { afd ->
                val fd = afd.parcelFileDescriptor.detachFd()
                var handed = false
                try {
                    val handle = MlNative.createNnfp(fd, afd.startOffset, afd.length)
                    handed = true
                    handle
                } finally {
                    if (!handed) closeFd(fd)
                }
            }

        /**
         * Close a bare descriptor.
         *
         * Adopting it into a [ParcelFileDescriptor] is the only way to reach `close(2)` from
         * Kotlin. Failures are swallowed because the caller is already on an error path.
         */
        private fun closeFd(fd: Int) {
            runCatching { ParcelFileDescriptor.adoptFd(fd).close() }
        }
    }
}
