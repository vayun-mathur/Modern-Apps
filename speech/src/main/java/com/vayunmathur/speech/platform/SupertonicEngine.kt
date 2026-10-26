package com.vayunmathur.speech.platform

import android.content.Context
import com.vayunmathur.library.log.Log
import com.vayunmathur.library.ml.SupertonicSynthesizer
import com.vayunmathur.speech.domain.SupertonicVoices
import java.io.File
import java.text.BreakIterator
import java.util.Locale

/**
 * The one Supertonic handle the TTS service speaks through.
 *
 * Replaced `PiperEngine`, which kept an access-ordered LRU of two `PiperSynthesizer`s because a
 * Piper voice *was* a set of networks — changing language meant closing one 80 MB model and
 * opening another. Supertonic has one set of networks for all 31 languages, and a voice is two
 * small style tensors, so there is one handle for the life of the service and switching voices
 * uploads nothing. That is what deleted the cache, the eviction, the per-voice sample rates and
 * the directory resolution along with it.
 *
 * # Threading
 *
 * [SupertonicSynthesizer] is not thread-safe, so every call here is synchronised on this object.
 * `TextToSpeechService` serialises `onSynthesizeText` itself, but [preload] runs on a background
 * thread from `onCreate` and [close] arrives from `onDestroy`, so the three can overlap.
 */
class SupertonicEngine(private val context: Context) {
    private val lock = Any()
    private var synthesizer: SupertonicSynthesizer? = null
    private var voice: String = SupertonicVoices.DEFAULT_VOICE
    private var attempts = 0
    private var closed = false

    /** Supertonic's output rate, fixed by the vocoder rather than by the voice. */
    val sampleRate: Int get() = SupertonicSynthesizer.SAMPLE_RATE

    /**
     * Bring the four networks up, returning whether they came up. Idempotent and cheap after the
     * first call, so the service can call it on every utterance as well as warming it at startup.
     *
     * Slow the first time: ~105 MB of weights are streamed out of the APK into GPU memory, and four
     * command buffers are recorded. That is why `onCreate` warms it off the main thread.
     */
    fun preload(): Boolean = synchronized(lock) { ensure() != null }

    /**
     * Switch to `style`, returning whether it took.
     *
     * A 25 KB read and an input swap, not a reload, so this is safe to call per utterance. An
     * unknown style is refused rather than ignored: silently reading every voice in `F1` would be
     * indistinguishable from the voice picker not working.
     */
    fun voice(style: String): Boolean = synchronized(lock) {
        if (style !in SupertonicVoices.VOICES) return false
        val engine = ensure() ?: return false
        if (style == voice) return true
        if (!engine.voice(style)) return false
        voice = style
        true
    }

    /**
     * Synthesise `text` and hand the samples to `onChunk` in pieces, stopping early if it returns
     * false.
     *
     * Chunked rather than returned whole because the framework wants audio as it is produced and a
     * paragraph of 44.1 kHz mono is megabytes. Supertonic's sampler is one pass over a whole
     * sequence, so there is no streaming inside a sentence; instead the text is split into sentences
     * (`BreakIterator`) and each one's audio is delivered as soon as it is synthesised, so a
     * paragraph starts playing after its first sentence rather than after all of them. Sampler cost
     * is linear in frames, so the total is about the same.
     *
     * The lock covers the synthesis and **not** the delivery. `onChunk` writes into the framework's
     * audio track and blocks while it plays, so holding the handle across it would make [close] —
     * which arrives on the main thread from `onDestroy` — wait out a whole utterance.
     *
     * [language] is the ISO-639-1 code the model reads in; see [SupertonicSynthesizer.synthesize].
     *
     * There is no `speed`: [SupertonicSynthesizer.synthesize] takes none, and the only ways to fake
     * one on the host are to resample the PCM, which shifts the pitch, or to time-stretch it, which
     * is a signal-processing feature rather than part of a port. Rate control therefore does not
     * work in this engine; changing it in the system settings has no effect. Doing it properly means
     * scaling the duration predictor's frame count natively, which is where Piper's `length_scale`
     * did it too.
     */
    fun synthesize(text: String, language: String, onChunk: (FloatArray) -> Boolean): Boolean {
        var spoke = false
        for (sentence in sentences(text, language)) {
            val samples = synchronized(lock) {
                val engine = ensure() ?: return false
                // Broad catch is deliberate: the native synthesizer throws undocumented
                // RuntimeExceptions (not just IllegalStateException) on bad input.
                @Suppress("TooGenericExceptionCaught")
                try {
                    engine.synthesize(sentence, language)
                } catch (e: Exception) {
                    Log.error(TAG, "synthesis failed", e)
                    return false
                }
            }
            var offset = 0
            while (offset < samples.size) {
                val n = minOf(CHUNK_SAMPLES, samples.size - offset)
                if (!onChunk(samples.copyOfRange(offset, offset + n))) return false
                offset += n
            }
            spoke = spoke || samples.isNotEmpty()
        }
        return spoke
    }

    /** `text` as trimmed, non-blank sentences in `language`'s rules. */
    private fun sentences(text: String, language: String): List<String> {
        val breaks = BreakIterator.getSentenceInstance(Locale.forLanguageTag(language))
        breaks.setText(text)
        val out = mutableListOf<String>()
        var start = breaks.first()
        var end = breaks.next()
        while (end != BreakIterator.DONE) {
            text.substring(start, end).trim().takeIf { it.isNotEmpty() }?.let(out::add)
            start = end
            end = breaks.next()
        }
        return out
    }

    /**
     * Free the four networks. Idempotent, and **terminal**.
     *
     * Terminal because `onCreate` warms the engine on a detached thread while `onDestroy` calls
     * this on the main one: without the flag, a `preload` that lost the race to the lock would
     * rebuild ~105 MB of GPU state immediately after it was freed, with nothing left to free it
     * again.
     */
    fun close() = synchronized(lock) {
        closed = true
        synthesizer?.close()
        synthesizer = null
    }

    /**
     * The handle, built on first use.
     *
     * # Why the attempt is counted
     *
     * `SupertonicSynthesizer`'s constructor never throws; it comes up unavailable instead. Most of
     * the reasons are properties of the install and will never change — no `libmodelrunner.so` for
     * this ABI, no Vulkan device with fp16 compute, a missing asset — and retrying those re-reads
     * the bundle to fail identically. But not all of them are: a lost device or a failed allocation
     * under memory pressure is transient, and latching on the first one would leave the engine
     * advertising 31 languages and erroring on every utterance until the process died.
     *
     * So it retries, and a small bound is what stops a permanently broken device paying for a
     * ~105 MB load attempt on every sentence.
     */
    private fun ensure(): SupertonicSynthesizer? {
        synthesizer?.let { return it }
        if (closed || attempts >= MAX_ATTEMPTS) return null
        attempts++
        val built = SupertonicSynthesizer.inAssets(context.assets, voice = voice)
        if (!built.isAvailable) {
            Log.error(TAG, "the Supertonic bundle is not usable on this device (attempt $attempts)")
            built.close()
            return null
        }
        synthesizer = built
        return built
    }

    private companion object {
        const val TAG = "SupertonicEngine"

        /** Loads to try before giving up for the life of the process. See [ensure]. */
        const val MAX_ATTEMPTS = 2

        /**
         * Samples per chunk handed to the callback.
         *
         * The service splits these again against `SynthesisCallback.maxBufferSize`, which is what
         * actually bounds a write, so this only decides how much PCM is converted at a time. 4,096
         * floats is 8 KB of PCM16 and 93 ms of audio at 44.1 kHz.
         */
        const val CHUNK_SAMPLES = 4096
    }
}

/**
 * Whether the Supertonic bundle is in the APK, without bringing up a GPU or reading a weight.
 *
 * This is what became of `PiperVoiceRegistry.isExtracted`. That answered a *per-language* question
 * — is this voice's 80 MB on disk — and six of the eleven `TextToSpeechService` overrides consulted
 * it, which is most of what made that service as long as it was. One bundle serves all 31 languages
 * now, so the question has one answer for the whole install, and it collapses to this.
 *
 * It is still worth asking rather than assuming true. The bundle is ~105 MB of assets, and an
 * install that somehow lacks it should advertise no languages at all rather than advertise 31 and
 * fail on the first utterance — a TTS engine that is selectable but silent has no error surface
 * anywhere the user can see.
 *
 * Cheap: `AssetManager.list` on one directory, no asset opened. Cached, because the answer cannot
 * change while the process lives.
 */
object SupertonicBundle {
    @Volatile private var present: Boolean? = null

    fun isPresent(context: Context): Boolean {
        present?.let { return it }
        // Broad catch is deliberate: AssetManager throws undocumented RuntimeExceptions
        // (not just IOException) on corrupt installs.
        @Suppress("TooGenericExceptionCaught")
        val entries = try {
            context.assets.list(SupertonicSynthesizer.ASSET_PATH)?.toSet().orEmpty()
        } catch (e: Exception) {
            // Deliberately not cached. A throw here is a failure to *read* the assets rather than
            // an answer about them, and pinning `false` on it would unadvertise the engine for the
            // life of the process over something that may not recur.
            Log.error(TAG, "cannot list the Supertonic assets", e)
            return false
        }
        val found = REQUIRED.all { it in entries }
        present = found
        return found
    }

    /**
     * Delete the `piper/` tree an upgraded install still has under `getExternalFilesDir`.
     *
     * Up to **1.8 GB** of voices that nothing reads any more: Piper downloaded and unzipped one
     * model per language there, and removing the code that managed them does not remove them. An
     * upgrade would otherwise keep that space allocated until the user found it themselves.
     *
     * Best effort and silent. It runs off the main thread from the TTS service's `onCreate`, once
     * per process, and a failure means the files stay — there is nothing useful to tell the user
     * about a directory they did not know existed.
     */
    fun deleteOrphanedPiperVoices(context: Context) {
        val root = File(context.getExternalFilesDir(null), "piper")
        if (!root.isDirectory) return
        val freed = runCatching {
            val bytes = root.walkBottomUp().filter { it.isFile }.sumOf { it.length() }
            root.deleteRecursively()
            bytes
        }.getOrNull() ?: return
        Log.status(TAG, "removed ${freed / BYTES_PER_MB} MB of Piper voices that nothing reads")
    }

    private const val TAG = "SupertonicBundle"
    /** Bytes in a megabyte, for logging reclaimed voice data. */
    private const val BYTES_PER_MB = 1024 * 1024

    /**
     * The four plans, the codepoint table and the default voice.
     *
     * The other nine voices are not required: a bundle missing one of those loses a voice, while a
     * bundle missing a plan cannot speak at all, and only the second should stop the engine being
     * advertised.
     */
    private val REQUIRED = listOf(
        "supertonic_dp.maml",
        "supertonic_ttl.maml",
        "supertonic_ve.maml",
        "supertonic_voc.maml",
        "unicode_indexer.bin",
        SupertonicSynthesizer.styleName(SupertonicVoices.DEFAULT_VOICE),
    )
}
