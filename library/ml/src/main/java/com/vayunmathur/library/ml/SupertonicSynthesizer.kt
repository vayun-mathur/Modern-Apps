package com.vayunmathur.library.ml

import android.content.res.AssetManager
import android.os.ParcelFileDescriptor
import com.vayunmathur.library.log.Log
import java.io.Closeable
import java.io.File
import java.text.Normalizer

/**
 * On-device text-to-speech: Supertonic 3 on the Vulkan compute runtime.
 *
 * One bundle covers 31 languages and 10 voices, and every one of the four networks runs on the GPU.
 * All four are checked numerically against onnxruntime — see
 * `library/ml/src/main/rust/src/nets/supertonic_*.rs`:
 *
 * - **Duration predictor** returns one number, the utterance's length in seconds, which fixes
 *   every later shape.
 * - **Text encoder** turns the characters into a `[256, chars]` conditioning.
 * - **Flow-matching sampler** is the expensive one: 16 steps, each running two guidance branches,
 *   so 32 passes of 64 million parameters for one sentence.
 * - **ConvNeXt vocoder** turns the latent into 44,100 Hz samples.
 *
 * # There is no phonemiser
 *
 * The front end is a flat 65,536-entry codepoint table, not espeak-ng, so nothing has to be
 * generated per language on the build machine. The model does expect **NFD**-decomposed text,
 * which [synthesize] does itself through `java.text.Normalizer`: precomposed accents are unmapped
 * while combining marks are first-class tokens, so skipping it would quietly drop characters.
 *
 * # Two places the bundle can live
 *
 * [inAssets] reads it out of the APK and [inDirectory] out of a folder on disk. Both go to native
 * as **file descriptors** rather than byte arrays, which is not an optimisation: reading the weights
 * into a `ByteArray` allocates them three times over — the Java array, the copy JNI hands Rust, and
 * the reader's own — so a bundle of this size would need several hundred megabytes of transient
 * heap and be killed for it on a low-RAM device. Native reads each file's header and tensor table,
 * a few kilobytes, then streams the weights into the GPU a chunk at a time.
 *
 * An asset must be stored **uncompressed** for this to work at all: `AssetManager.openFd` throws
 * for a deflated entry. That is what `noCompress += "maml"` in the app's Gradle configuration is
 * for, and it costs nothing on download size since fp16 and int8 weights barely compress.
 *
 * # Voices
 *
 * A voice is two small style tensors, not a model, so [voice] switches without re-uploading
 * anything: all ten come to ~250 KB together.
 *
 * # Availability
 *
 * Construction never throws. [isAvailable] is false when `libmodelrunner.so` is missing for
 * this ABI, when a file is absent, or when the device cannot give us a Vulkan device with fp16
 * compute — and then [synthesize] returns an empty array.
 *
 * # Threading
 *
 * Not thread-safe. A caller must hold a lock across [synthesize], [voice] and [close]: the handle
 * is freed by [close] and reading it afterwards is a use-after-free.
 */
class SupertonicSynthesizer private constructor(
    private val bundle: Bundle,
    voice: String,
) : AutoCloseable {
    private var handle: Long = 0L

    /** Supertonic's output rate, fixed by the vocoder rather than by the voice. */
    val sampleRate: Int = SAMPLE_RATE

    init {
        handle = if (!MlNative.isAvailable) {
            0L
        } else {
            try {
                create(bundle, voice)
            } catch (expected: Exception) {
                Log.error(TAG, "cannot open the Supertonic bundle in $bundle", expected)
                0L
            }
        }
    }

    /** True if all four networks came up, the codepoint table is the right size and a voice read. */
    val isAvailable: Boolean get() = handle != 0L

    /**
     * Switch to another voice, returning false if it could not be read.
     *
     * Cheap: the four networks stay on the GPU and only the two style tensors are replaced, so
     * this is a 25 KB read rather than a whole-bundle one.
     */
    fun voice(name: String): Boolean {
        if (handle == 0L) return false
        return try {
            MlNative.setSupertonicVoice(handle, bundle.read(styleName(name)))
        } catch (expected: Exception) {
            Log.error(TAG, "cannot read the voice $name in $bundle", expected)
            false
        }
    }

    /**
     * Synthesise [text] in [language] and return mono samples in `-1..1` at [sampleRate].
     *
     * [language] is the ISO-639-1 code the model should read in, or `na` for one it does not
     * list. Supertonic 3 is the multilingual model and was trained with the language tag always
     * present; getting it wrong is silent, so this has no default.
     *
     * Returns an empty array when there is nothing in the model's vocabulary, when the engine is
     * unavailable, or when the pass failed — all three are "no audio" to a caller, and the reason
     * is in logcat under `ModelRunner`.
     *
     * Long text should be split into sentences first. Nothing refuses a paragraph, but every
     * shape here scales with the utterance and the sampler runs 32 passes over all of it.
     *
     * Two calls with the same text differ, as flow matching starts from a sampled latent.
     */
    fun synthesize(text: String, language: String): FloatArray {
        if (handle == 0L || text.isBlank()) return FloatArray(0)
        val decomposed = Normalizer.normalize(text, Normalizer.Form.NFKD)
        return MlNative.synthesizeSupertonic(handle, decomposed, language) ?: FloatArray(0)
    }

    /** Free all four networks. Idempotent. */
    override fun close() {
        val live = handle
        handle = 0L
        if (live != 0L) MlNative.destroySupertonic(live)
    }

    /**
     * Where a bundle's six files are, abstracted over the APK and the filesystem.
     *
     * Both answer the same two questions — open a descriptor for a `.maml`, and read a small file
     * whole — and they differ only in that an asset is a range of the APK rather than a file, which
     * is why [open] returns an offset and a length alongside the descriptor.
     */
    internal interface Bundle {
        /** An open descriptor for [name] and the byte range within it that the file occupies. */
        fun open(name: String): Piece

        /** All of [name], for the two files small enough that streaming buys nothing. */
        fun read(name: String): ByteArray
    }

    /**
     * One file's descriptor and its range, owned until [release] hands it to native.
     *
     * The ownership matters because a raw descriptor has no destructor. If any of the six files
     * fails to open, the ones already opened have to be closed here or they are leaked for the life
     * of the process; if all six succeed, native takes them and closing here would shut the
     * descriptors it is about to read.
     */
    internal class Piece(private val fd: Int, val at: Long, val length: Long) : Closeable {
        private var owned = true

        /** Give the descriptor up to native, which closes it. */
        fun release(): Int {
            owned = false
            return fd
        }

        /** Close the descriptor, unless [release] already gave it away. */
        override fun close() {
            if (!owned) return
            owned = false
            closeFd(fd)
        }
    }

    private class Assets(private val assets: AssetManager, private val path: String) : Bundle {
        // `use` rather than a bare `close`, and it is load-bearing in both directions. On the happy
        // path the descriptor has already been detached, and `AssetFileDescriptor.close` is then a
        // no-op on it - it only releases the wrapper. If `detachFd` throws instead, the close is the
        // real one, and a leaked descriptor onto the APK would last the life of the process.
        override fun open(name: String): Piece = assets.openFd("$path/$name").use { afd ->
            Piece(afd.parcelFileDescriptor.detachFd(), afd.startOffset, afd.length)
        }

        override fun read(name: String): ByteArray =
            assets.open("$path/$name").use { it.readBytes() }

        override fun toString(): String = "the APK's $path/"
    }

    private class Directory(private val directory: File) : Bundle {
        // Offset 0 and the whole file: only an asset needs a range, because only an asset shares
        // its descriptor with the rest of the APK. `use` for the same reason as in [Assets].
        override fun open(name: String): Piece {
            val file = file(name)
            return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use {
                Piece(it.detachFd(), 0L, file.length())
            }
        }

        override fun read(name: String): ByteArray = file(name).readBytes()

        private fun file(name: String): File {
            val file = File(directory, name)
            require(file.isFile) { "$name is missing from $directory" }
            return file
        }

        override fun toString(): String = directory.toString()
    }

    /** Vocoder constants, voices and asset/directory factories for [SupertonicSynthesizer]. */
    companion object {
        private const val TAG = "SupertonicSynthesizer"

        /** The vocoder's rate: 44,100 Hz, at 3,072 samples per latent frame. */
        const val SAMPLE_RATE = 44_100

        /** The voice used when a caller does not name one. */
        const val DEFAULT_VOICE = "F1"

        /** Where [inAssets] looks unless told otherwise. */
        const val ASSET_PATH = "supertonic"

        /**
         * The four plans, in the order [MlNative.createSupertonic]'s parallel arrays want them.
         *
         * Native checks each file's graph id against the slot it arrived in, so a reordering here
         * fails at load rather than running the vocoder's weights through the text encoder.
         */
        private val GRAPHS = listOf(
            "supertonic_dp.maml",
            "supertonic_ttl.maml",
            "supertonic_ve.maml",
            "supertonic_voc.maml",
        )

        private const val INDEXER = "unicode_indexer.bin"

        /** The bundle shipped inside the APK. */
        fun inAssets(
            assets: AssetManager,
            path: String = ASSET_PATH,
            voice: String = DEFAULT_VOICE,
        ): SupertonicSynthesizer = SupertonicSynthesizer(Assets(assets, path), voice)

        /** The bundle in a folder on disk, as a downloaded one is. */
        fun inDirectory(
            directory: File,
            voice: String = DEFAULT_VOICE,
        ): SupertonicSynthesizer = SupertonicSynthesizer(Directory(directory), voice)

        fun styleName(voice: String): String = "style_$voice.bin"

        /**
         * Open all four plans, read the two small files, and hand the descriptors over.
         *
         * The two `finally`s are what make the descriptors safe rather than merely usually safe. A
         * raw descriptor has no destructor, so every path out of here has to close what it opened:
         * the outer one covers a file that is missing, an asset that turned out to be compressed or
         * a voice that does not exist, and the inner one covers the window after [Piece.release] has
         * given the descriptors up but before native has adopted them.
         */
        private fun create(bundle: Bundle, voice: String): Long {
            val pieces = ArrayList<Piece>(GRAPHS.size)
            try {
                GRAPHS.forEach { pieces.add(bundle.open(it)) }
                val indexer = bundle.read(INDEXER)
                val style = bundle.read(styleName(voice))
                val offsets = LongArray(pieces.size) { pieces[it].at }
                val lengths = LongArray(pieces.size) { pieces[it].length }
                val fds = IntArray(pieces.size) { pieces[it].release() }
                var handed = false
                try {
                    val handle =
                        MlNative.createSupertonic(fds, offsets, lengths, indexer, style)
                    handed = true
                    return handle
                } finally {
                    if (!handed) fds.forEach { closeFd(it) }
                }
            } finally {
                pieces.forEach { it.close() }
            }
        }

        /**
         * Close a bare descriptor.
         *
         * Adopting it into a [ParcelFileDescriptor] is the only way to reach `close(2)` from Kotlin.
         * Failures are swallowed because every caller is already on an error path and there is
         * nothing useful to do about a descriptor that will not close.
         */
        private fun closeFd(fd: Int) {
            runCatching { ParcelFileDescriptor.adoptFd(fd).close() }
        }
    }
}
