package com.vayunmathur.speech.util

import android.content.Context
import com.vayunmathur.library.log.Log
import com.vayunmathur.library.ml.WhisperHandle
import org.json.JSONObject

/**
 * Speech-to-text entry point for [com.vayunmathur.speech.service.WhisperRecognitionService].
 *
 * A thin seam over [WhisperHandle], which runs **whisper-base** on `:library:ml`'s Vulkan compute
 * runtime. It replaced `WhisperOnnxEngine` and with it the last third-party inference runtime in the
 * tree: the two int8 ONNX exports (76.9 MB) became one 70.6 MiB `.maml`, and the reduced
 * onnxruntime AAR's 10,466,856 bytes of arm64 `.so` left the APK with them.
 *
 * The port is also **four times closer** to the fp32 checkpoint than the exports were — mean absolute
 * error 0.029 against 0.110 over the encoder's output — because it quantises per output channel where
 * onnxruntime's dynamic quantiser did it per tensor. See
 * `library/ml/src/main/rust/src/nets/whisper.rs`.
 *
 * # What stayed in Kotlin
 *
 * [WhisperFeatures] (the mel front end) and [WhisperTokenizer] (byte-level decode), both unchanged.
 * The mel is pinned against HuggingFace by `WhisperFeaturesTest` and a wrong one produces confident
 * nonsense rather than an error, so it was not worth reimplementing. The **decode loop** did move:
 * it is `post::whisper` now, host-tested against a scripted stub.
 *
 * # The ids come out of the asset
 *
 * [GenerationConfig] reads `generation_config.json`, as the ONNX engine did, and hands the ids to
 * native. Nothing here hardcodes them — `<|notimestamps|>` especially, because dropping it turns the
 * transcript into timestamped text rather than failing.
 *
 * Not thread-safe: a transcription re-records the network once per token. Call [transcribe] from a
 * single worker thread.
 */
open class WhisperEngine(context: Context) {
    private val app = context.applicationContext
    private val lock = Any()

    private var whisper: WhisperHandle? = null
    private var tokenizer: WhisperTokenizer? = null
    private var config: GenerationConfig? = null
    private var loadFailed = false

    /** Ids and token maps read from `generation_config.json` rather than hardcoded. */
    private class GenerationConfig(json: JSONObject) {
        val startOfTranscript = json.optInt("decoder_start_token_id", 50258)
        val endOfText = json.optInt("eos_token_id", 50257)
        val noTimestamps = json.optInt("no_timestamps_token_id", 50363)
        val maxLength = json.optInt("max_length", 448)

        /** `<|transcribe|>`; `<|translate|>` is the id one lower and unused here. */
        val transcribe: Int =
            json.optJSONObject("task_to_id")?.optInt("transcribe", 50359) ?: 50359

        /** ISO-639-1 code to its `<|xx|>` token id, e.g. `en` to 50259. */
        val langToId: Map<String, Int> = buildMap {
            val obj = json.optJSONObject("lang_to_id") ?: return@buildMap
            for (key in obj.keys()) {
                // Keys arrive as "<|en|>"; store the bare code.
                put(key.removePrefix("<|").removeSuffix("|>"), obj.optInt(key))
            }
        }

        /** Never-emit ids (music notes, formatting artefacts) that upstream masks out. */
        val suppress: IntArray = ids(json, "suppress_tokens")

        /** Additionally suppressed at the very first generated position (leading space, EOT). */
        val suppressAtBegin: IntArray = ids(json, "begin_suppress_tokens")

        /** The five scalars native wants, in [WhisperHandle]'s documented order. */
        val special: IntArray =
            intArrayOf(startOfTranscript, endOfText, transcribe, noTimestamps, maxLength)

        private companion object {
            fun ids(json: JSONObject, key: String): IntArray {
                val array = json.optJSONArray(key) ?: return IntArray(0)
                return IntArray(array.length()) { array.optInt(it) }
            }
        }
    }

    /**
     * Whether the recogniser can run. The model ships in the APK, so this is only false if it fails
     * to load — never because something still needs downloading.
     */
    open fun isModelPresent(): Boolean = ensure()

    /** Load the model now (e.g. to warm up off the main thread). Returns true if ready. */
    open fun preload(): Boolean = ensure()

    @Synchronized
    private fun ensure(): Boolean {
        whisper?.let { return true }
        if (loadFailed) return false
        loadFailed = true
        val cfg = loadConfig() ?: return false
        val tok = loadTokenizer() ?: return false
        // Construction never throws: an absent, compressed or malformed asset, a missing
        // `libmodelrunner.so`, ids that do not describe this model, and a device without fp16
        // compute all come back unavailable.
        val handle = WhisperHandle.inAssets(
            app.assets,
            cfg.special,
            cfg.langToId.values.toIntArray(),
            cfg.suppress,
            cfg.suppressAtBegin,
        )
        if (!handle.isAvailable) {
            Log.error(TAG, "cannot bring up $handle")
            handle.close()
            return false
        }
        whisper = handle
        tokenizer = tok
        config = cfg
        loadFailed = false
        Log.status(TAG, "whisper-base ready (${cfg.langToId.size} languages)")
        return true
    }

    // Broad catches are deliberate: asset reads and JSON parsing throw undocumented
    // RuntimeExceptions (not just IOException), and a bad bundle must read as
    // "model unavailable" rather than a crash.
    @Suppress("TooGenericExceptionCaught")
    private fun loadConfig(): GenerationConfig? = try {
        GenerationConfig(
            JSONObject(
                app.assets.open("${WhisperModel.DIR}/$GEN_CONFIG").use {
                    it.bufferedReader().readText()
                },
            ),
        )
    } catch (e: Exception) {
        Log.error(TAG, "cannot read $GEN_CONFIG", e)
        null
    }

    @Suppress("TooGenericExceptionCaught")
    private fun loadTokenizer(): WhisperTokenizer? = try {
        app.assets.open("${WhisperModel.DIR}/$VOCAB").use { WhisperTokenizer.load(it) }
    } catch (e: Exception) {
        Log.error(TAG, "cannot read $VOCAB", e)
        null
    }

    /**
     * Transcribe [pcm16k] (16 kHz mono). [language] is ISO-639-1 or null/"auto" for automatic
     * detection. Returns the text, or null if the model isn't ready or inference failed.
     */
    // Broad catch is deliberate: native inference throws undocumented
    // RuntimeExceptions on bad input, which must read as "no transcription".
    @Suppress("TooGenericExceptionCaught")
    open fun transcribe(pcm16k: ShortArray, language: String?): String? {
        if (!ensure()) return null
        val cfg = config ?: return null
        val tok = tokenizer ?: return null
        return try {
            val mel = WhisperFeatures.logMel(pcm16k)
            val ids = synchronized(lock) { whisper?.transcribe(mel, languageToken(cfg, language)) }
                ?: return null
            tok.decode(ids.toList())
        } catch (e: Exception) {
            Log.error(TAG, "transcribe failed", e)
            null
        }
    }

    /**
     * The `<|xx|>` id for [requested], or **-1** to let the model detect the language.
     *
     * An unrecognised code detects rather than falling back to English: whisper's own detection is
     * what it does by default, and it beats confidently transcribing Japanese as English.
     */
    private fun languageToken(cfg: GenerationConfig, requested: String?): Int {
        val code = requested?.substringBefore('-')?.lowercase()
        if (code == null || code == "auto") return DETECT
        cfg.langToId[code]?.let { return it }
        Log.status(TAG, "no Whisper language token for '$requested', detecting instead")
        return DETECT
    }

    @Synchronized
    open fun close() {
        synchronized(lock) {
            runCatching { whisper?.close() }
            whisper = null
        }
        tokenizer = null
        config = null
        loadFailed = false
    }

    private companion object {
        const val TAG = "WhisperEngine"
        const val VOCAB = "vocab.json"
        const val GEN_CONFIG = "generation_config.json"

        /** What native reads as "detect the language yourself". */
        const val DETECT = -1
    }
}
