package com.vayunmathur.education.util

import android.content.Context
import android.media.MediaPlayer
import android.speech.tts.TextToSpeech
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalContext
import java.util.Locale

/**
 * Thin wrapper over Android [TextToSpeech] for the audio-first K-2 shell:
 * prompts and labels are narrated (tap-to-hear). Init is async; calls before
 * the engine is ready are no-ops.
 *
 * [narrate] prefers a bundled narration clip (`assets/audio/<audioRef>`) when
 * one is provided and present, falling back to TTS of the text otherwise — so
 * content authors can drop in recorded audio without any code change.
 */
class Narrator(context: Context) {
    private val appContext = context.applicationContext
    private var ready = false
    private var player: MediaPlayer? = null
    private val tts: TextToSpeech = TextToSpeech(appContext) { status ->
        if (status == TextToSpeech.SUCCESS) {
            tts.language = Locale.getDefault()
            ready = true
        }
    }

    /** Narrate [text], preferring a bundled clip at `assets/audio/<audioRef>`. */
    fun narrate(text: String, audioRef: String? = null) {
        stop()
        if (audioRef != null && playAsset("audio/$audioRef")) return
        speak(text)
    }

    fun speak(text: String) {
        if (ready && text.isNotBlank()) {
            tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, text.hashCode().toString())
        }
    }

    private fun playAsset(path: String): Boolean = try {
        appContext.assets.openFd(path).use { afd ->
            val mp = MediaPlayer()
            mp.setDataSource(afd.fileDescriptor, afd.startOffset, afd.length)
            mp.setOnCompletionListener { it.release() }
            mp.prepare()
            mp.start()
            player?.release()
            player = mp
        }
        true
    } catch (e: Exception) {
        false
    }

    fun stop() {
        if (ready) tts.stop()
        player?.let { runCatching { it.stop() }; it.release() }
        player = null
    }

    fun shutdown() {
        stop()
        tts.shutdown()
    }
}

val LocalNarrator = staticCompositionLocalOf<Narrator?> { null }

/** Creates a [Narrator] scoped to the composition, shutting it down on dispose. */
@Composable
fun rememberNarrator(): Narrator {
    val context = LocalContext.current
    val narrator = remember { Narrator(context) }
    DisposableEffect(Unit) {
        onDispose { narrator.shutdown() }
    }
    return narrator
}
