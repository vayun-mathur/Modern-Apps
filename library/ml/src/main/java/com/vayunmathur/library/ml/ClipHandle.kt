package com.vayunmathur.library.ml

import android.content.res.AssetManager
import android.os.ParcelFileDescriptor
import com.vayunmathur.library.log.Log

/**
 * On-device image and text embedding in a shared 512-d space: TinyCLIP on the Vulkan compute
 * runtime.
 *
 * Two transformers of width 256 with 4 heads of 64 — a 10-layer vision tower over 16x16 patches of a
 * 224x224 image, and a 3-layer causal text tower over up to 77 tokens — at 23.4 million parameters.
 * See `library/ml/src/main/rust/src/nets/tinyclip.rs`.
 *
 * # One bundled asset, 22.6 MiB
 *
 * `tinyclip.maml` ships inside the APK, so this has an [inAssets] and no download. Everything but
 * the layer norms, the biases, the class token and the two position tables is int8, quantised per
 * output channel from the fp32 export; the worst per-tensor correlation against fp32 is 0.99986.
 *
 * It replaces `onnxruntime-android` running a 24 MB int8 ONNX export of the same model, and is
 * *closer* to the fp32 reference than that was — 0.9994 against 0.9829 on a text query, because the
 * export quantised the 49,408-row token table per tensor with a zero point. The APK loses
 * 10,466,856 bytes of arm64 `.so` with the runtime, not weights.
 *
 * An asset must be stored **uncompressed** for this to work at all: `AssetManager.openFd` throws for
 * a deflated entry. That is what `noCompress += "maml"` in `photos/build.gradle.kts` is for, and it
 * costs nothing on download size since int8 weights barely compress.
 *
 * # The two towers share one net
 *
 * They share no weights, but they do share the file, so both run through one handle and native
 * re-records between them. Switching costs a `device_wait_idle`; an indexing run is [imageEmbedding]
 * throughout and pays for one.
 *
 * # Neither vector is normalised
 *
 * [imageEmbedding] and [textEmbedding] return the raw projection. The caller normalises — in
 * `:photos` that is `ClipEmbedder.l2Normalize`, so the stored BLOB format is decided in one place.
 *
 * # Availability
 *
 * Construction never throws. [isAvailable] is false when `libmodelrunner.so` is missing for this
 * ABI, when the asset is absent, compressed or malformed, or when the device cannot give us a Vulkan
 * device with fp16 compute — and then both embedding calls return null.
 *
 * # Threading
 *
 * Not thread-safe. A text query re-records the network, so two concurrent calls would interleave
 * recordings of the same command buffer. A caller must hold a lock across the embedding calls and
 * [close].
 */
class ClipHandle private constructor(private val source: String) : AutoCloseable {
    private var handle: Long = 0L

    /** True if the graph came up and is the file this runtime was built against. */
    val isAvailable: Boolean get() = handle != 0L

    /**
     * The 512-d embedding of an already-preprocessed image, or null on failure.
     *
     * [pixels] is `3 * 224 * 224` floats, NCHW and RGB, resized to the shortest edge, centre-cropped
     * and normalised by CLIP's mean and standard deviation. Not L2-normalised on return.
     */
    fun imageEmbedding(pixels: FloatArray): FloatArray? {
        if (handle == 0L) return null
        return MlNative.tinyclipImage(handle, pixels)
    }

    /**
     * The 512-d embedding of a tokenised query, in the same space as [imageEmbedding], or null.
     *
     * [ids] must end at `<|endoftext|>` with the tokenizer's padding trimmed off, because that is
     * the position CLIP pools. Not L2-normalised on return.
     */
    fun textEmbedding(ids: IntArray): FloatArray? {
        if (handle == 0L) return null
        if (ids.isEmpty()) return null
        return MlNative.tinyclipText(handle, ids)
    }

    /** Free the network and close the weights file. Idempotent. */
    override fun close() {
        val live = handle
        handle = 0L
        if (live != 0L) MlNative.destroyTinyclip(live)
    }

    override fun toString(): String = "TinyCLIP from $source"

    /** Model constants and the asset factory for [ClipHandle]. */
    companion object {
        private const val TAG = "ClipHandle"

        /** The embedding dimension both towers project into. */
        const val DIMENSION = 512

        /** The square RGB input side the vision tower expects. */
        const val IMAGE_SIZE = 224

        /** The longest tokenised query, including both special tokens. */
        const val CONTEXT_LENGTH = 77

        /** The one graph. Native checks its graph id, so a wrong file fails at load. */
        const val GRAPH = "clip/tinyclip.maml"

        /**
         * The model from the APK's assets, which is the only place it lives.
         *
         * No `inDirectory` counterpart: at 22.6 MiB this is bundled, so
         * there is no download directory to look in.
         */
        fun inAssets(assets: AssetManager, path: String = GRAPH): ClipHandle {
            val instance = ClipHandle("the APK's $path")
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
         * `use` rather than a bare `close`, and it is load-bearing in both directions — the same
         * argument [SupertonicSynthesizer]'s asset path makes. On the happy path the descriptor has
         * already been detached and `AssetFileDescriptor.close` only releases the wrapper; if
         * `detachFd` throws instead, the close is the real one, and a leaked descriptor onto the APK
         * would last the life of the process.
         *
         * The inner `finally` covers the remaining window: after the descriptor has been given up
         * but before native has adopted it.
         */
        private fun create(assets: AssetManager, path: String): Long =
            assets.openFd(path).use { afd ->
                val fd = afd.parcelFileDescriptor.detachFd()
                var handed = false
                try {
                    val handle = MlNative.createTinyclip(fd, afd.startOffset, afd.length)
                    handed = true
                    handle
                } finally {
                    if (!handed) closeFd(fd)
                }
            }

        /**
         * Close a bare descriptor.
         *
         * Adopting it into a [ParcelFileDescriptor] is the only way to reach `close(2)` from Kotlin.
         * Failures are swallowed because the caller is already on an error path.
         */
        private fun closeFd(fd: Int) {
            runCatching { ParcelFileDescriptor.adoptFd(fd).close() }
        }
    }
}
