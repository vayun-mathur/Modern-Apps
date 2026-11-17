package com.vayunmathur.speech.platform

import android.content.Context

/**
 * Asset location of the offline **whisper-base** (multilingual, ~99 languages) recogniser.
 *
 * These files used to be a 117 MB runtime download of an ncnn conversion, then two int8 ONNX exports
 * bundled in the APK. They are now one 70.6 MiB `whisper_base.maml`, read by [WhisperEngine] through
 * `:library:ml` — see `scripts/ml/fetch_whisper.py` for how it is rebuilt from
 * `openai/whisper-base`'s pinned checkpoint.
 */
object WhisperModel {
    const val DIR = "whisper-base"

    /** The graph, relative to [DIR]. The two `generation_config.json`/`vocab.json` sit beside it. */
    const val GRAPH = "whisper_base.maml"

    /**
     * True if the bundled model is readable. Barring a corrupt install this is always true; it
     * exists so the setup screen can report a broken build rather than silently failing to
     * transcribe.
     */
    fun isReady(context: Context): Boolean = try {
        context.assets.list(DIR)?.contains(GRAPH) == true
    } catch (_: Throwable) {
        false
    }
}
