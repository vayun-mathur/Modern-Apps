package com.vayunmathur.library.ml

import android.os.ParcelFileDescriptor
import com.vayunmathur.library.log.Log
import java.io.File
import java.text.Normalizer

/**
 * On-device translation between any two of 202 languages: NLLB-200-distilled-600M on the
 * Vulkan compute runtime.
 *
 * NLLB distilled to 600M parameters: 12 encoder layers, 12 decoder layers, `d_model` 1024,
 * 16 heads, a 4096-wide ReLU feed-forward - and a 256,206-entry vocabulary shared between
 * the input embedding and the output projection. **Direct** translation, with no English
 * pivot, so every pair is a single hop. See `library/ml/src/main/rust/src/nets/nllb.rs`.
 *
 * # Two files, downloaded rather than bundled
 *
 * `nllb600.maml` and `tokenizer.bin`, which are far too much to ship inside an APK - hence
 * [inDirectory] and no asset path. The tied embedding is emitted as four head-split tensors
 * over disjoint class ranges (256,206 classes at 1024 channels exceeds
 * `maxStorageBufferRange` in one buffer), and the tokenizer table holds the 256,000
 * SentencePiece pieces plus the 202 flores language tokens.
 *
 * This replaces the old SMaLL-100 build (12 enc + 3 dec, 128,112 vocab).
 *
 * # Both language tokens are required
 *
 * NLLB puts the **source** language token on the encoder source and forces the **target**
 * language token as the decoder's first token (forced-BOS), rather than SMaLL-100's
 * target-token-on-source + EOS-start. Backwards, it produces fluent output in the wrong
 * language rather than an error - which is why [translate] takes flores codes rather than
 * bare ids and resolves both through
 * [com.vayunmathur.translate.platform.NllbModel.tokenId].
 *
 * # Normalisation
 *
 * The model's normaliser is `nmt_nfkc` with a precompiled charsmap, so [translate] applies
 * `java.text.Normalizer` **NFKC** first and native does the rest - the same split
 * [SupertonicSynthesizer] uses for NFD.
 *
 * # Availability
 *
 * Construction never throws. [isAvailable] is false when `libmodelrunner.so` is missing
 * for this ABI, when either file is absent or malformed, or when the device cannot give
 * us a Vulkan device with fp16 compute - and then [translate] returns null.
 *
 * # Threading
 *
 * Not thread-safe: the self-attention KV cache and the cross-attention K and V live on the
 * device between calls, and a translation re-records the network on the way in, so two
 * concurrent calls would clobber each other's state. A caller must hold a lock across
 * [translate] and [close].
 */
class NllbHandle private constructor(private val directory: File) : AutoCloseable {
    private var handle: Long = 0L

    init {
        handle = if (!MlNative.isAvailable) {
            0L
        } else {
            try {
                create(directory)
            } catch (expected: Exception) {
                Log.error(TAG, "cannot open the NLLB model in $directory", expected)
                0L
            }
        }
    }

    /** True if the graph came up and the tokenizer table is the vocabulary the model was built on. */
    val isAvailable: Boolean get() = handle != 0L

    /**
     * Translate [text] from the language [sourceToken] names into the language [targetToken]
     * names, or null on failure.
     *
     * An empty string means there was nothing to translate, which is not a failure - blank
     * input and input the tokenizer maps to nothing both come back empty. Null means the
     * engine is unavailable or the pass failed, and the reason is in logcat under
     * `ModelRunner`.
     *
     * Long text should be split into sentences first. Nothing refuses a paragraph, but
     * decoding is capped at 128 tokens and every step attends over the whole source.
     */
    fun translate(text: String, sourceToken: Int, targetToken: Int): String? {
        if (handle == 0L) return null
        if (text.isBlank()) return ""
        val normalised = Normalizer.normalize(text, Normalizer.Form.NFKC)
        return MlNative.translateNllb(handle, normalised, sourceToken, targetToken)
    }

    /** Free the network and close the weights file. Idempotent. */
    override fun close() {
        val live = handle
        handle = 0L
        if (live != 0L) MlNative.destroyNllb(live)
    }

    override fun toString(): String = "NLLB-200 in $directory"

    /** Model file names and the on-disk factory for [NllbHandle]. */
    companion object {
        private const val TAG = "NllbHandle"

        /** The one graph. Native checks its graph id, so a wrong file fails at load. */
        const val GRAPH = "nllb600.maml"

        /** model-eng's `nllb_tokenizer.py` output: SentencePiece pieces plus the language tokens. */
        const val TOKENIZER = "tokenizer.bin"

        /** The files [inDirectory] needs, for a caller checking a download is complete. */
        val FILES: List<String> = listOf(GRAPH, TOKENIZER)

        /**
         * The model in a folder on disk, which is the only place it lives.
         *
         * No `inAssets` counterpart, unlike [SupertonicSynthesizer]: the distilled-600M
         * weights are a runtime download to `getExternalFilesDir`, so there is no APK
         * entry to open.
         */
        fun inDirectory(directory: File): NllbHandle = NllbHandle(directory)

        /**
         * Open the graph, read the tokenizer table, and hand the descriptor over.
         *
         * The two `finally`s are what make the descriptor safe rather than usually safe. A raw
         * descriptor has no destructor, so every path out of here has to close what it opened:
         * the outer one covers a missing or unreadable file, and the inner one covers the
         * window after the descriptor has been given up but before native has adopted it.
         */
        private fun create(directory: File): Long {
            val graph = File(directory, GRAPH)
            require(graph.isFile) { "$GRAPH is missing from $directory" }
            val tokenizer = File(directory, TOKENIZER)
            require(tokenizer.isFile) { "$TOKENIZER is missing from $directory" }

            // Offset 0 and the whole file: only an asset needs a range, because only an asset
            // shares its descriptor with the rest of the APK.
            val fd = ParcelFileDescriptor.open(graph, ParcelFileDescriptor.MODE_READ_ONLY)
                .use { it.detachFd() }
            var handed = false
            try {
                val handle =
                    MlNative.createNllb(fd, 0L, graph.length(), tokenizer.readBytes())
                handed = true
                return handle
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
