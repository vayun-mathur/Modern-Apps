package com.vayunmathur.flashcards.platform

import android.content.Context
import android.speech.tts.TextToSpeech
import java.util.Locale

/**
 * A thin wrapper over [TextToSpeech] for reading card text aloud. Mirrors the
 * pattern used by `:translate`'s `TtsSpeaker`: the engine initialises
 * asynchronously, so requests made before it is ready are queued and replayed on
 * init. Uses the device's default locale. Call [shutdown] when the owner
 * (ViewModel) is cleared.
 */
class TtsSpeaker(context: Context) {

    private var ready = false
    private var pending: String? = null

    private val tts: TextToSpeech = TextToSpeech(context.applicationContext) { status ->
        ready = status == TextToSpeech.SUCCESS
        if (ready) {
            runCatching { tts.language = Locale.getDefault() }
            pending?.let { text ->
                pending = null
                speakNow(text)
            }
        }
    }

    /** Speaks [text], flushing anything currently playing. Blank input is a no-op. */
    fun speak(text: String) {
        val clean = text.trim()
        if (clean.isEmpty()) return
        if (!ready) {
            pending = clean
            return
        }
        speakNow(clean)
    }

    private fun speakNow(text: String) {
        tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, UTTERANCE_ID)
    }

    fun stop() {
        pending = null
        runCatching { tts.stop() }
    }

    fun shutdown() {
        pending = null
        runCatching { tts.stop() }
        runCatching { tts.shutdown() }
    }

    private companion object {
        const val UTTERANCE_ID = "flashcards_tts"
    }
}
