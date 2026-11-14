package com.vayunmathur.library.ml

import android.content.res.AssetManager
import android.os.ParcelFileDescriptor
import com.vayunmathur.library.log.Log

/**
 * On-device speech recognition in ~99 languages: whisper-base on the Vulkan compute runtime.
 *
 * A two-convolution stem that turns `[80, 3000]` log-mel frames into 1500 positions, 6 encoder
 * layers, and 6 decoder layers with self and cross attention; `d_model` 512, 8 heads of 64, and a
 * 51,865-entry vocabulary tied between the input embedding and the logits projection. 72.6 million
 * parameters. See `library/ml/src/main/rust/src/nets/whisper.rs`.
 *
 * # One bundled asset, 70.6 MiB
 *
 * `whisper_base.maml` ships inside the APK, so this has an [inAssets] and no download. Everything
 * but the layer norms, the biases and the two position tables is int8, quantised per output channel
 * from `openai/whisper-base`'s fp32 checkpoint.
 *
 * It replaces `onnxruntime-reduced-android` running two int8 ONNX exports totalling 76.9 MB, and is
 * **four times closer** to the fp32 checkpoint than they were: over the encoder's output, mean
 * absolute error 0.029 against their 0.110, on a tensor whose largest value is 22.8. The APK loses
 * 10,466,856 bytes of arm64 `.so` with the runtime — about 3.6 MiB of the packaged size.
 *
 * An asset must be stored **uncompressed** for this to work at all: `AssetManager.openFd` throws for
 * a deflated entry. That is what `noCompress += "maml"` in `speech/build.gradle.kts` is for.
 *
 * # The special ids come from the asset, not from here
 *
 * [inAssets] takes them as arguments rather than hardcoding them, because they live in the model's
 * own `generation_config.json` and a second copy would be a second thing to get wrong. The
 * `<|notimestamps|>` id in particular is part of the decoder prompt, and dropping it turns the
 * output into timestamped text rather than failing.
 *
 * # Latency
 *
 * The encoder is ~43.7 GMAC per 30-second window regardless of how much speech is in it, and every
 * decode step re-records the network because the self-attention cache grows. This is not a real-time
 * recogniser; call it from a worker.
 *
 * # Availability
 *
 * Construction never throws. [isAvailable] is false when `libmodelrunner.so` is missing for this
 * ABI, when the asset is absent, compressed or malformed, when the ids do not describe this model,
 * or when the device cannot give us a Vulkan device with fp16 compute — and then [transcribe]
 * returns null.
 *
 * # Threading
 *
 * Not thread-safe, and more sharply than most handles here: a transcription re-records the network
 * once per decoded token, so two concurrent calls would interleave recordings of the same command
 * buffer. A caller must hold a lock across [transcribe] and [close].
 */
class WhisperHandle private constructor(private val source: String) : AutoCloseable {
    private var handle: Long = 0L

    /** True if the graph came up and the ids describe the model it was built from. */
    val isAvailable: Boolean get() = handle != 0L

    /**
     * Transcribe one 30-second log-mel window into token ids, or null on failure.
     *
     * [mel] is `MELS * FRAMES` floats row-major. [languageToken] is a `<|xx|>` id, or negative to let
     * the model detect the language. The ids are raw — the caller's tokenizer skips the special and
     * timestamp ones.
     */
    fun transcribe(mel: FloatArray, languageToken: Int): IntArray? {
        if (handle == 0L) return null
        if (mel.size != MELS * FRAMES) return null
        return MlNative.transcribeWhisper(handle, mel, languageToken)
    }

    /** Free the network and close the weights file. Idempotent. */
    override fun close() {
        val live = handle
        handle = 0L
        if (live != 0L) MlNative.destroyWhisper(live)
    }

    override fun toString(): String = "whisper-base from $source"

    /** Front-end dimensions, model constants and the asset factory for [WhisperHandle]. */
    companion object {
        private const val TAG = "WhisperHandle"

        /** Mel bins the front end produces. */
        const val MELS = 80

        /** Mel frames in one 30-second window at a 160-sample hop. */
        const val FRAMES = 3000

        /** The one graph. Native checks its graph id, so a wrong file fails at load. */
        const val GRAPH = "whisper-base/whisper_base.maml"

        /**
         * The five values [inAssets] wants in `special`, in order:
         * `decoder_start_token_id`, `eos_token_id`, `transcribe`, `no_timestamps_token_id`,
         * `max_length`.
         */
        const val SPECIAL_IDS = 5

        /**
         * The model from the APK's assets, which is the only place it lives.
         *
         * `special`, `languages`, `suppress` and `suppressAtBegin` all come from the bundled
         * `generation_config.json`; see the class docs for why they are arguments.
         */
        fun inAssets(
            assets: AssetManager,
            special: IntArray,
            languages: IntArray,
            suppress: IntArray,
            suppressAtBegin: IntArray,
            path: String = GRAPH,
        ): WhisperHandle {
            val instance = WhisperHandle("the APK's $path")
            instance.handle = if (!MlNative.isAvailable || special.size != SPECIAL_IDS) {
                if (special.size != SPECIAL_IDS) {
                    Log.error(TAG, "${special.size} special ids, not $SPECIAL_IDS")
                }
                0L
            } else {
                try {
                    create(assets, path, special, languages, suppress, suppressAtBegin)
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
        private fun create(
            assets: AssetManager,
            path: String,
            special: IntArray,
            languages: IntArray,
            suppress: IntArray,
            suppressAtBegin: IntArray,
        ): Long = assets.openFd(path).use { afd ->
            val fd = afd.parcelFileDescriptor.detachFd()
            var handed = false
            try {
                val handle = MlNative.createWhisper(
                    fd,
                    afd.startOffset,
                    afd.length,
                    special,
                    languages,
                    suppress,
                    suppressAtBegin,
                )
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
