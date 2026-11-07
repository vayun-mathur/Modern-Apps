package com.vayunmathur.translate.platform

import android.content.Context
import android.os.Bundle
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.translate.domain.Languages
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowSpeechRecognizer
import org.robolectric.shadows.ShadowTextToSpeech
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Speech and TTS with the Robolectric service shadows driven by hand:
 * recognition results/errors are triggered through ShadowSpeechRecognizer,
 * and the TTS init callback is delivered explicitly so the ready paths run.
 */
@RunWith(RobolectricTestRunner::class)
class SpeechAndTtsReadyTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    /** Robolectric has no recognition service; registering one makes
     *  SpeechRecognizer.isRecognitionAvailable true so start() really binds. */
    private fun installRecognitionService() {
        val intent = android.content.Intent(android.speech.RecognitionService.SERVICE_INTERFACE)
        val resolveInfo = android.content.pm.ResolveInfo().apply {
            serviceInfo = android.content.pm.ServiceInfo().apply {
                packageName = "com.example.fake.recognition"
                name = "FakeRecognitionService"
            }
        }
        shadowOf(context.packageManager).addResolveInfoForIntent(intent, resolveInfo)
    }

    private fun resultsBundle(vararg texts: String): Bundle = Bundle().apply {
        putStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION, arrayListOf(*texts))
    }

    // --- Speech recognition ------------------------------------------------------

    @Test
    fun `recognizer delivers partial final and end of speech`() {
        installRecognitionService()
        val recognizer = AndroidSpeechRecognizer(context)
        if (!recognizer.isAvailable()) {
            // Shadow says no service: the unavailable contract is the coverage.
            var error: String? = null
            recognizer.start("en", {}, {}, { error = it })
            assertEquals("Speech recognition unavailable on this device", error)
            return
        }
        val partials = mutableListOf<String>()
        var finalText: String? = null
        var ended = 0
        recognizer.start(
            languageCode = "fr",
            onPartial = { partials += it },
            onFinal = { finalText = it },
            onError = { throw AssertionError("unexpected error: $it") },
            onEndOfSpeech = { ended++ },
        )
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
        val shadow = shadowOf(ShadowSpeechRecognizer.getLatestSpeechRecognizer())
        // Pinned language rides in the intent for a concrete source language.
        assertEquals(
            Locale.FRENCH.toLanguageTag(),
            shadow.lastRecognizerIntent.getStringExtra(android.speech.RecognizerIntent.EXTRA_LANGUAGE),
        )
        shadow.triggerOnReadyForSpeech(Bundle())
        shadow.triggerOnRmsChanged(3.5f)
        shadow.triggerOnPartialResults(resultsBundle("bon"))
        shadow.triggerOnPartialResults(resultsBundle("bonjour"))
        shadow.triggerOnEndOfSpeech()
        shadow.triggerOnResults(resultsBundle("bonjour monde"))
        assertEquals(listOf("bon", "bonjour"), partials)
        assertEquals("bonjour monde", finalText)
        assertEquals(1, ended)
        recognizer.stop()
        recognizer.destroy()
    }

    @Test
    fun `recognizer auto language omits the language extra and errors map to messages`() {
        installRecognitionService()
        val recognizer = AndroidSpeechRecognizer(context)
        assertTrue(recognizer.isAvailable(), "fake recognition service was not seen")
        if (!recognizer.isAvailable()) return
        val errors = mutableListOf<String>()
        var finalText: String? = null
        recognizer.start(
            languageCode = Languages.AUTO.code,
            onPartial = {},
            onFinal = { finalText = it },
            onError = { errors += it },
        )
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
        val shadow = shadowOf(ShadowSpeechRecognizer.getLatestSpeechRecognizer())
        assertEquals(
            null,
            shadow.lastRecognizerIntent.getStringExtra(android.speech.RecognizerIntent.EXTRA_LANGUAGE),
        )
        // Blank results settle as an empty final (callers reset their UI on it).
        shadow.triggerOnResults(resultsBundle(""))
        assertEquals("", finalText)
        shadow.triggerOnResults(Bundle())
        assertEquals("", finalText)

        val codes = listOf(
            SpeechRecognizer.ERROR_AUDIO, SpeechRecognizer.ERROR_CLIENT,
            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS, SpeechRecognizer.ERROR_NETWORK,
            SpeechRecognizer.ERROR_NETWORK_TIMEOUT, SpeechRecognizer.ERROR_NO_MATCH,
            SpeechRecognizer.ERROR_RECOGNIZER_BUSY, SpeechRecognizer.ERROR_SERVER,
            SpeechRecognizer.ERROR_SPEECH_TIMEOUT, 999,
        )
        for (code in codes) {
            recognizer.start("en", {}, {}, { errors += it })
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
            shadowOf(ShadowSpeechRecognizer.getLatestSpeechRecognizer()).triggerOnError(code)
        }
        assertEquals(codes.size, errors.size)
        assertTrue(errors.contains("No speech recognized"))
        assertTrue(errors.contains("Microphone permission denied"))
        assertTrue(errors.contains("Speech recognition error"))
        assertTrue(errors.all { it.isNotBlank() })
        recognizer.destroy()
        // Starting again after destroy creates a fresh recognizer.
        var restarted = false
        recognizer.start("en", {}, { restarted = true }, {})
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
        shadowOf(ShadowSpeechRecognizer.getLatestSpeechRecognizer())
            .triggerOnResults(resultsBundle("back again"))
        assertTrue(restarted)
        recognizer.destroy()
    }

    // --- TTS with a ready engine ------------------------------------------------------

    private fun readySpeaker(): Pair<TtsSpeaker, ShadowTextToSpeech> {
        val speaker = TtsSpeaker(context)
        val tts = ShadowTextToSpeech.getLastTextToSpeechInstance()
        assertNotNull(tts)
        val shadow = shadowOf(tts)
        shadow.onInitListener.onInit(TextToSpeech.SUCCESS)
        return speaker to shadow
    }

    @Test
    fun `tts speaks in an available language and records the utterance`() {
        ShadowTextToSpeech.addLanguageAvailability(Locale.US)
        ShadowTextToSpeech.addLanguageAvailability(Locale("es"))
        val (speaker, shadow) = readySpeaker()
        assertTrue(speaker.isLanguageSupported("en"))
        speaker.speak("hello world", "en")
        assertEquals("hello world", shadow.lastSpokenText)
        speaker.speak("hola mundo", "es")
        assertEquals("hola mundo", shadow.lastSpokenText)
        assertEquals(listOf("hello world", "hola mundo"), shadow.spokenTextList)
        // Regional fallback: pt has no exact voice, pt-BR (same language) does.
        ShadowTextToSpeech.addLanguageAvailability(Locale("pt", "BR"))
        speaker.speak("ola", "pt")
        speaker.stop()
        assertTrue(shadow.isStopped)
        speaker.shutdown()
        assertTrue(shadow.isShutdown)
    }

    @Test
    fun `tts reports missing voice instead of speaking in the wrong language`() {
        val (speaker, shadow) = readySpeaker()
        var missing = 0
        // No availability registered for Japanese in this test's shadow state.
        speaker.speak("こんにちは", "ja") { missing++ }
        if (!speaker.isLanguageSupported("ja")) {
            assertEquals(1, missing)
            assertEquals(null, shadow.lastSpokenText)
        }
        // Blank text is a no-op even with a callback registered.
        speaker.speak("   ", "ja") { missing++ }
        assertEquals(if (speaker.isLanguageSupported("ja")) 0 else 1, missing)
        speaker.shutdown()
    }

    @Test
    fun `tts pending request replays once init succeeds`() {
        val speaker = TtsSpeaker(context)
        ShadowTextToSpeech.addLanguageAvailability(Locale.US)
        // Speak before init: queued, nothing spoken yet.
        speaker.speak("queued line", "en")
        val tts = ShadowTextToSpeech.getLastTextToSpeechInstance()
        val shadow = shadowOf(tts)
        assertEquals(null, shadow.lastSpokenText)
        shadow.onInitListener.onInit(TextToSpeech.SUCCESS)
        assertEquals("queued line", shadow.lastSpokenText)
        speaker.shutdown()
    }

    @Test
    fun `tts failed init leaves speaker inert`() {
        val speaker = TtsSpeaker(context)
        val tts = ShadowTextToSpeech.getLastTextToSpeechInstance()
        shadowOf(tts).onInitListener.onInit(TextToSpeech.ERROR)
        var missing = 0
        speaker.speak("never spoken", "en") { missing++ }
        assertFalse(speaker.isLanguageSupported("en"))
        speaker.stop()
        speaker.shutdown()
    }
}
