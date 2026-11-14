package com.vayunmathur.library.ml

import android.os.ParcelFileDescriptor
import com.vayunmathur.library.log.Log
import java.io.File

/**
 * On-device translation into 400+ languages: MADLAD400-3B-MT on the
 * Vulkan compute runtime.
 *
 * MADLAD-400 trained for MT: 32 encoder layers, 32 decoder layers, `d_model` 1024,
 * 16 heads of `d_kv` 128, an 8192-wide gated-GELU feed-forward — and a 256,000-entry
 * vocabulary with **untied** embeddings. T5 relative position biases are computed on
 * the host per shape and uploaded as plan inputs. See
 * `library/ml/src/main/rust/src/nets/madlad.rs`.
 *
 * # Two files, downloaded rather than bundled
 *
 * `madlad400.maml` and `tokenizer.bin`, far too much to ship inside an APK — hence
 * [inDirectory] and no asset path. Each untied table is emitted as four head-split
 * tensors over disjoint 64,000-class ranges (256,000 classes at 1024 channels exceeds
 * `maxStorageBufferRange` in one buffer), and the tokenizer table holds the 256,000
 * Unigram pieces.
 *
 * # One target tag, not two language tokens
 *
 * MADLAD prefixes the **target** tag (`<2en>`) to the encoder source and starts the
 * decoder unforced from `<unk>` — unlike NLLB's source-token + forced-BOS-target pair.
 * Backwards, it produces fluent output in the wrong language rather than an error —
 * which is why [translate] takes a target tag rather than bare ids and resolves it
 * through [com.vayunmathur.translate.platform.MadladModel.targetTag].
 *
 * # Normalisation
 *
 * The model's normaliser is the identity, so [translate] does NOT NFKC-normalise —
 * normalising would retokenise text the model was trained to see raw. It only
 * collapses runs of spaces (HuggingFace's T5 prefix normaliser), which native does
 * not do.
 *
 * # Availability
 *
 * Construction never throws. [isAvailable] is false when `libmodelrunner.so` is missing
 * for this ABI, when either file is absent or malformed, or when the device cannot give
 * us a Vulkan device with fp16 compute — and then [translate] returns null.
 *
 * # Threading
 *
 * Not thread-safe, and more sharply than the other handles here: a translation re-records
 * the network once per decoded token, so two concurrent calls would interleave recordings
 * of the same command buffer. A caller must hold a lock across [translate] and [close].
 */
class MadladHandle private constructor(private val directory: File) : AutoCloseable {
    private var handle: Long = 0L

    init {
        handle = if (!MlNative.isAvailable) {
            0L
        } else {
            try {
                create(directory)
            } catch (expected: Exception) {
                Log.error(TAG, "cannot open the MADLAD model in $directory", expected)
                0L
            }
        }
    }

    /** True if the graph came up and the tokenizer table is the vocabulary the model was built on. */
    val isAvailable: Boolean get() = handle != 0L

    /**
     * Translate [text] into the language [targetTag] names (e.g. `"<2en>"`),
     * or null on failure.
     *
     * An empty string means there was nothing to translate, which is not a failure — blank
     * input and input the tokenizer maps to nothing both come back empty. Null means the
     * engine is unavailable or the pass failed, and the reason is in logcat under
     * `ModelRunner`.
     *
     * Long text should be split into sentences first. Nothing refuses a paragraph, but
     * decoding is capped at 128 tokens and every step attends over the whole source.
     */
    fun translate(text: String, targetTag: String): String? {
        if (handle == 0L) return null
        if (text.isBlank()) return ""
        // Identity normaliser: no NFKC. Only collapse space runs, as HF's T5 tokeniser does.
        val normalised = text.replace(Regex(" {2,}"), " ")
        return MlNative.translateMadlad(handle, normalised, targetTag)
    }

    /** Free the network and close the weights file. Idempotent. */
    override fun close() {
        val live = handle
        handle = 0L
        if (live != 0L) MlNative.destroyMadlad(live)
    }

    override fun toString(): String = "MADLAD400-3B-MT in $directory"

    /** Model file names and the on-disk factory for [MadladHandle]. */
    companion object {
        private const val TAG = "MadladHandle"

        /** The one graph. Native checks its graph id, so a wrong file fails at load. */
        const val GRAPH = "madlad400.maml"

        /** model-eng's `fetch_madlad400.py` output: the 256,000 Unigram pieces. */
        const val TOKENIZER = "tokenizer.bin"

        /** The files [inDirectory] needs, for a caller checking a download is complete. */
        val FILES: List<String> = listOf(GRAPH, TOKENIZER)

        /**
         * The model in a folder on disk, which is the only place it lives.
         *
         * No `inAssets` counterpart, unlike [SupertonicSynthesizer]: the weights are a
         * runtime download to `getExternalFilesDir`, so there is no APK entry to open.
         */
        fun inDirectory(directory: File): MadladHandle = MadladHandle(directory)

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
                    MlNative.createMadlad(fd, 0L, graph.length(), tokenizer.readBytes())
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
