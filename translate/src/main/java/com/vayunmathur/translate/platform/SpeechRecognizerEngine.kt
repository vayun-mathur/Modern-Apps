package com.vayunmathur.translate.platform

import com.vayunmathur.translate.domain.Languages

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import com.vayunmathur.library.log.Log
import java.util.Locale

/**
 * Live speech-to-text, behind a clean interface. Implementations stream partial
 * transcripts via [onPartial] as words arrive and a final transcript via
 * [onFinal]. All callbacks are delivered on the main thread.
 */
interface SpeechRecognizerEngine {
    /** True if this engine can actually transcribe on this device. */
    fun isAvailable(): Boolean

    /**
     * Begin listening for speech in [languageCode] (ISO-639-1, "auto" allowed for
     * on-device auto-detect). [onPartial] fires repeatedly with the best in-progress
     * hypothesis; [onEndOfSpeech] fires when capture ends and transcription begins (so
     * the UI can show a "transcribing" state — offline models finish a beat after the
     * mic stops); [onFinal] fires exactly once with the settled text (possibly blank);
     * [onError] reports a human-readable failure. Exactly one of [onFinal]/[onError]
     * is the terminal callback, so a caller can always reset its UI on either.
     */
    fun start(
        languageCode: String,
        onPartial: (String) -> Unit,
        onFinal: (String) -> Unit,
        onError: (String) -> Unit,
        onEndOfSpeech: () -> Unit = {},
    )

    /** Stop listening (keeps the engine usable for a later [start]). */
    fun stop()

    /** Release all resources. */
    fun destroy()
}

/**
 * Speech recognition using the platform [SpeechRecognizer] with partial results
 * (`EXTRA_PARTIAL_RESULTS`). Works on devices that ship a recognizer (most, via
 * Google/Samsung). Must be created and used on the main thread.
 */
class AndroidSpeechRecognizer(private val context: Context) : SpeechRecognizerEngine {

    private var recognizer: SpeechRecognizer? = null

    override fun isAvailable(): Boolean = SpeechRecognizer.isRecognitionAvailable(context)

    // Broad catch is deliberate: startListening throws undocumented
    // RuntimeExceptions (not just SecurityException) on some builds.
    @Suppress("TooGenericExceptionCaught")
    override fun start(
        languageCode: String,
        onPartial: (String) -> Unit,
        onFinal: (String) -> Unit,
        onError: (String) -> Unit,
        onEndOfSpeech: () -> Unit,
    ) {
        if (!isAvailable()) {
            onError("Speech recognition unavailable on this device")
            return
        }
        // Reuse a single recognizer across sessions and cancel any prior/finishing one
        // first. Destroying + recreating per session could rebind the service before its
        // last session was cleared, which surfaced as "recognizer busy" on rapid taps;
        // cancel() resets the service's session synchronously before we start a new one.
        val sr = recognizer ?: SpeechRecognizer.createSpeechRecognizer(context).also { recognizer = it }
        try { sr.cancel() } catch (ignored: Exception) {
            // Best-effort session reset; a torn-down recognizer needs no cancel.
        }

        // Captured so the listener's onEndOfSpeech() override can call the callback: an
        // unqualified onEndOfSpeech() inside the override would resolve to the override
        // itself (same name + signature) and recurse.
        val notifyEndOfSpeech = onEndOfSpeech

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(
                RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM,
            )
            // For "auto" source, DON'T pin a language: omitting EXTRA_LANGUAGE lets the
            // recognizer auto-detect (the offline Whisper service supports ~99-language
            // auto-detect, and this mirrors the Speech app's own working demo). Forcing
            // the device locale here was the bug — in a translate app you usually speak a
            // language other than your device locale, so Whisper was pinned to the wrong
            // language and returned nothing → "No speech recognized". Only pin when the
            // user explicitly picked a source language.
            if (languageCode != Languages.AUTO.code) {
                val tag = Locale.forLanguageTag(languageCode).toLanguageTag()
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, tag)
            }
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        }

        sr.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}

            // Capture has ended; an offline model now transcribes before onResults.
            override fun onEndOfSpeech() {
                notifyEndOfSpeech()
            }

            override fun onError(error: Int) {
                onError(describeError(error))
            }

            override fun onPartialResults(partialResults: Bundle?) {
                firstResult(partialResults)?.let(onPartial)
            }

            // Always terminal: deliver the settled text (blank if the model returned
            // nothing usable) so the caller can reliably reset its listening state.
            override fun onResults(results: Bundle?) {
                onFinal(firstResult(results).orEmpty())
            }

            override fun onEvent(eventType: Int, params: Bundle?) {}
        })

        try {
            sr.startListening(intent)
        } catch (e: SecurityException) {
            Log.error(TAG, "startListening failed", e)
            onError("Could not start speech recognition")
        } catch (e: Exception) {
            Log.error(TAG, "startListening failed", e)
            onError("Could not start speech recognition")
        }
    }

    override fun stop() {
        try {
            recognizer?.stopListening()
        } catch (ignored: Exception) {
            // Already torn down; nothing to stop.
        }
    }
    override fun destroy() {
        try {
            recognizer?.destroy()
        } catch (ignored: Exception) {
            // Already torn down; nothing to destroy.
        }
        recognizer = null
    }

    private fun firstResult(bundle: Bundle?): String? =
        bundle?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            ?.firstOrNull()
            ?.takeIf { it.isNotBlank() }

    private fun describeError(error: Int): String = when (error) {
        SpeechRecognizer.ERROR_AUDIO -> "Audio recording error"
        SpeechRecognizer.ERROR_CLIENT -> "Client error"
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Microphone permission denied"
        SpeechRecognizer.ERROR_NETWORK -> "Network error"
        SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Network timeout"
        SpeechRecognizer.ERROR_NO_MATCH -> "No speech recognized"
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "Recognizer busy"
        SpeechRecognizer.ERROR_SERVER -> "Server error"
        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "No speech input"
        else -> "Speech recognition error"
    }

    /** Logging constants for the Android speech recognizer. */
    companion object {
        private const val TAG = "AndroidSpeech"
    }
}

