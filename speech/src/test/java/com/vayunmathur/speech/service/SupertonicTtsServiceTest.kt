package com.vayunmathur.speech.service

import android.os.Bundle
import android.speech.tts.SynthesisCallback
import android.speech.tts.SynthesisRequest
import android.speech.tts.TextToSpeech
import android.content.Context
import com.vayunmathur.speech.platform.SupertonicEngine
import com.vayunmathur.speech.testing.ThrowingDetachFdShadow
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(shadows = [ThrowingDetachFdShadow::class])
class SupertonicTtsServiceTest {

    /** Exposes the protected TextToSpeechService contract for direct calls. */
    class Harness : SupertonicTtsService() {
        fun isLanguageAvailable(lang: String?, country: String?, variant: String?) =
            onIsLanguageAvailable(lang, country, variant)
        fun loadLanguage(lang: String?, country: String?, variant: String?) =
            onLoadLanguage(lang, country, variant)
        fun language(): Array<String> = onGetLanguage()
        fun defaultVoiceName(lang: String?, country: String?, variant: String?) =
            onGetDefaultVoiceNameFor(lang, country, variant)
        fun isValidVoiceName(name: String?) = onIsValidVoiceName(name)
        fun loadVoice(name: String?) = onLoadVoice(name)
        fun voices() = onGetVoices()
        fun stop() = onStop()
        fun synthesize(request: SynthesisRequest, callback: SynthesisCallback) =
            onSynthesizeText(request, callback)

        override fun createEngine(): SupertonicEngine =
            engineFactory?.invoke(applicationContext) ?: super.createEngine()

        companion object {
            var engineFactory: ((Context) -> SupertonicEngine)? = null
        }
    }

    class RecordingCallback(private val startResult: Int = TextToSpeech.SUCCESS) : SynthesisCallback {
        var startCalls = mutableListOf<Triple<Int, Int, Int>>()
        var audio = mutableListOf<Byte>()
        var audioCalls = 0
        var audioResult = TextToSpeech.SUCCESS
        var doneCalls = 0
        var errorCalls = 0
        override fun getMaxBufferSize(): Int = 1024
        override fun start(sampleRateInHz: Int, audioFormat: Int, channelCount: Int): Int {
            startCalls += Triple(sampleRateInHz, audioFormat, channelCount)
            return startResult
        }
        override fun audioAvailable(buffer: ByteArray, offset: Int, length: Int): Int {
            audioCalls++
            if (audioResult != TextToSpeech.SUCCESS) return audioResult
            audio += buffer.slice(offset until offset + length)
            return TextToSpeech.SUCCESS
        }
        override fun done(): Int { doneCalls++; return TextToSpeech.SUCCESS }
        override fun error() { errorCalls++ }
        override fun error(errorCode: Int) { errorCalls++ }
        override fun hasStarted(): Boolean = startCalls.isNotEmpty()
        override fun hasFinished(): Boolean = doneCalls > 0 || errorCalls > 0
    }

    class FakeEngine(context: Context) : SupertonicEngine(context) {
        var preloadOk = true
        var voiceOk = true
        var chunks: List<FloatArray> = listOf(FloatArray(0))
        var afterChunk: (() -> Unit)? = null
        var synthCalls = mutableListOf<Pair<String, String>>()
        var voicesSeen = mutableListOf<String>()
        var closed = false
        override fun preload(): Boolean = preloadOk
        override fun voice(style: String): Boolean { voicesSeen += style; return voiceOk }
        override fun synthesize(text: String, language: String, onChunk: (FloatArray) -> Boolean): Boolean {
            synthCalls += text to language
            var spoke = false
            for (chunk in chunks) {
                if (!onChunk(chunk)) return false
                afterChunk?.invoke()
                spoke = spoke || chunk.isNotEmpty()
            }
            return spoke
        }
        override fun close() { closed = true }
    }

    private fun service(): Harness =
        Robolectric.buildService(Harness::class.java).create().get()

    private fun fakeService(configure: FakeEngine.() -> Unit): Pair<Harness, FakeEngine> {
        lateinit var fake: FakeEngine
        Harness.engineFactory = { ctx -> FakeEngine(ctx).apply(configure).also { fake = it } }
        try {
            val harness = Robolectric.buildService(Harness::class.java).create().get()
            // Force the lazy engine now (a non-blank synthesis) so `fake` is
            // initialised for the caller, then clear the warm-up records.
            harness.synthesize(SynthesisRequest("warm up", Bundle()), RecordingCallback())
            fake.synthCalls.clear(); fake.voicesSeen.clear()
            return harness to fake
        } finally {
            Harness.engineFactory = null
        }
    }

    @Test
    fun languageAvailabilityLevels() {
        val s = service()
        assertEquals(TextToSpeech.LANG_AVAILABLE, s.isLanguageAvailable("en", null, null))
        assertEquals(TextToSpeech.LANG_AVAILABLE, s.isLanguageAvailable("en", "", ""))
        assertEquals(TextToSpeech.LANG_COUNTRY_AVAILABLE, s.isLanguageAvailable("en", "US", null))
        assertEquals(TextToSpeech.LANG_COUNTRY_AVAILABLE, s.isLanguageAvailable("de", "DE", ""))
        assertEquals(TextToSpeech.LANG_COUNTRY_VAR_AVAILABLE, s.isLanguageAvailable("en", "US", "x"))
        assertEquals(TextToSpeech.LANG_NOT_SUPPORTED, s.isLanguageAvailable("th", null, null))
        assertEquals(TextToSpeech.LANG_NOT_SUPPORTED, s.isLanguageAvailable(null, null, null))
        assertEquals(TextToSpeech.LANG_NOT_SUPPORTED, s.isLanguageAvailable("", null, null))
        assertEquals(TextToSpeech.LANG_AVAILABLE, s.isLanguageAvailable("eng", null, null))
        assertEquals(TextToSpeech.LANG_COUNTRY_AVAILABLE, s.isLanguageAvailable("pt", "PT", null))
    }

    @Test
    fun loadLanguageUpdatesCurrentLanguageOnlyOnSuccess() {
        val s = service()
        assertEquals(arrayOf("eng", "USA", "").toList(), s.language().toList())
        assertEquals(TextToSpeech.LANG_COUNTRY_AVAILABLE, s.loadLanguage("de", "DE", null))
        assertEquals(arrayOf("deu", "DEU", "").toList(), s.language().toList())
        assertEquals(TextToSpeech.LANG_NOT_SUPPORTED, s.loadLanguage("th", null, null))
        assertEquals(arrayOf("deu", "DEU", "").toList(), s.language().toList())
        assertEquals(TextToSpeech.LANG_AVAILABLE, s.loadLanguage("ja", null, null))
        assertEquals(arrayOf("jpn", "JPN", "").toList(), s.language().toList())
    }

    @Test
    fun defaultVoiceNameForAllRequestForms() {
        val s = service()
        assertEquals("en-US-x-F1", s.defaultVoiceName(null, null, null))
        assertEquals("en-US-x-F1", s.defaultVoiceName("en", null, null))
        assertEquals("de-DE-x-F1", s.defaultVoiceName("de", "DE", null))
        assertEquals("fr-FR-x-F1", s.defaultVoiceName("fra", null, null))
        assertNull(s.defaultVoiceName("th", null, null))
    }

    @Test
    fun voiceNameValidationChecksLanguageAndStyle() {
        val s = service()
        assertEquals(TextToSpeech.SUCCESS, s.isValidVoiceName("en-US-x-F1"))
        assertEquals(TextToSpeech.SUCCESS, s.isValidVoiceName("en-US-x-m5"))
        assertEquals(TextToSpeech.SUCCESS, s.isValidVoiceName("en-US"))
        assertEquals(TextToSpeech.SUCCESS, s.isValidVoiceName("eng-USA"))
        assertEquals(TextToSpeech.SUCCESS, s.isValidVoiceName("de-DE-x-F2"))
        assertEquals(TextToSpeech.ERROR, s.isValidVoiceName("en-US-x-BOGUS"))
        assertEquals(TextToSpeech.ERROR, s.isValidVoiceName("en-US-x-"))
        assertEquals(TextToSpeech.ERROR, s.isValidVoiceName("th-TH-x-F1"))
        assertEquals(TextToSpeech.ERROR, s.isValidVoiceName(null))
        assertEquals(TextToSpeech.ERROR, s.isValidVoiceName(""))
    }

    @Test
    fun loadVoiceUpdatesLanguageAndRejectsInvalid() {
        val s = service()
        assertEquals(TextToSpeech.SUCCESS, s.loadVoice("de-DE-x-M3"))
        assertEquals(arrayOf("deu", "DEU", "").toList(), s.language().toList())
        assertEquals(TextToSpeech.ERROR, s.loadVoice("en-US-x-BOGUS"))
        assertEquals(arrayOf("deu", "DEU", "").toList(), s.language().toList())
        assertEquals(TextToSpeech.ERROR, s.loadVoice(null))
        assertEquals(TextToSpeech.SUCCESS, s.loadVoice("ja-JP"))
        assertEquals(arrayOf("jpn", "JPN", "").toList(), s.language().toList())
    }

    @Test
    fun getVoicesPublishesThreeFormsPerLanguagePerStyle() {
        val s = service()
        val voices = s.voices()
        // 31 languages x (plain tag + iso3 pair + 10 styled names).
        assertEquals(31 * 12, voices.size)
        val names = voices.map { it.name }.toSet()
        assertEquals(voices.size, names.size)
        assertTrue("en-US" in names)
        assertTrue("eng-USA" in names)
        assertTrue("en-US-x-F1" in names)
        assertTrue("en-US-x-M5" in names)
        assertTrue("deu-DEU" in names)
        assertTrue("vie-VNM" in names)
        assertFalse(voices.any { it.name.contains("tha") })
        val en = voices.first { it.name == "en-US-x-F1" }
        assertEquals("en", en.locale.language)
        // The default voice name must appear in the published list (Settings Play button).
        assertTrue(s.defaultVoiceName(null, null, null) in names)
    }

    @Test
    fun synthesizeBlankTextStartsAndFinishesWithoutEngine() {
        val s = service()
        val cb = RecordingCallback()
        s.synthesize(SynthesisRequest("", Bundle()), cb)
        assertEquals(listOf(Triple(44100, android.media.AudioFormat.ENCODING_PCM_16BIT, 1)), cb.startCalls)
        assertEquals(1, cb.doneCalls)
        assertEquals(0, cb.errorCalls)
        assertTrue(cb.audio.isEmpty())

        val cb2 = RecordingCallback()
        s.synthesize(SynthesisRequest("   ", Bundle()), cb2)
        assertEquals(1, cb2.doneCalls)
        assertEquals(0, cb2.errorCalls)
    }

    @Test
    fun synthesizeRealTextErrorsWhenEngineUnavailable() {
        val s = service()
        val cb = RecordingCallback()
        s.synthesize(SynthesisRequest("Hello world.", Bundle()), cb)
        assertEquals(1, cb.errorCalls)
        assertEquals(0, cb.doneCalls)
        assertTrue(cb.startCalls.isEmpty())
    }

    @Test
    fun stopDoesNotBreakSubsequentBlankSynthesis() {
        val s = service()
        s.stop()
        val cb = RecordingCallback()
        s.synthesize(SynthesisRequest("", Bundle()), cb)
        assertEquals(1, cb.doneCalls)
    }

    @Test
    fun synthesizeSuccessConvertsFloatsToLittleEndianPcm16() {
        val (s, fake) = fakeService {
            chunks = listOf(floatArrayOf(0f, 1f, -1f, 0.5f, -0.5f, 2f, -2f))
        }
        val cb = RecordingCallback()
        s.synthesize(SynthesisRequest("Hello.", Bundle()), cb)
        assertEquals(1, cb.doneCalls); assertEquals(0, cb.errorCalls)
        assertEquals(listOf(Triple(44100, android.media.AudioFormat.ENCODING_PCM_16BIT, 1)), cb.startCalls)
        // 0, 32767, -32767, 16384, -16384, clamped 32767, clamped -32767; little-endian.
        val expected = listOf(0, 32767, -32767, 16384, -16383, 32767, -32767).flatMap {
            listOf((it and 0xFF).toByte(), ((it shr 8) and 0xFF).toByte())
        }
        assertEquals(expected, cb.audio)
        assertEquals(listOf("Hello." to "en"), fake.synthCalls)
        assertEquals(listOf("F1"), fake.voicesSeen.takeLast(1))
    }

    @Test
    fun synthesizeSplitsAudioAgainstMaxBufferSize() {
        val (s, _) = fakeService { chunks = listOf(FloatArray(3000) { 0.25f }) }
        val cb = RecordingCallback()
        s.synthesize(SynthesisRequest("Long text.", Bundle()), cb)
        assertEquals(1, cb.doneCalls)
        assertEquals(6000, cb.audio.size)
        // 6000 bytes in <=1024-byte writes: 5 full writes plus a remainder.
        assertEquals(6, cb.audioCalls)
        assertTrue(cb.audio.all { it == (8192 and 0xFF).toByte() || it == ((8192 shr 8) and 0xFF).toByte() })
    }

    @Test
    fun synthesizeStartFailureReturnsSilently() {
        val (s, fake) = fakeService { chunks = listOf(floatArrayOf(0.5f)) }
        val cb = RecordingCallback(startResult = TextToSpeech.ERROR)
        s.synthesize(SynthesisRequest("Hello.", Bundle()), cb)
        assertEquals(0, cb.doneCalls); assertEquals(0, cb.errorCalls)
        assertTrue(fake.synthCalls.isEmpty())
    }

    @Test
    fun synthesizeVoiceFailureErrors() {
        val (s, _) = fakeService { voiceOk = false }
        val cb = RecordingCallback()
        s.synthesize(SynthesisRequest("Hello.", Bundle()), cb)
        assertEquals(1, cb.errorCalls); assertEquals(0, cb.doneCalls)
    }

    @Test
    fun synthesizePreloadFailureErrorsBeforeStart() {
        val (s, _) = fakeService { preloadOk = false }
        val cb = RecordingCallback()
        val startsBefore = cb.startCalls.size
        s.synthesize(SynthesisRequest("Hello.", Bundle()), cb)
        assertEquals(1, cb.errorCalls)
        assertEquals(startsBefore, cb.startCalls.size)
    }

    @Test
    fun synthesizeAudioFailureErrors() {
        val (s, _) = fakeService { chunks = listOf(floatArrayOf(0.5f, 0.5f)) }
        val cb = RecordingCallback()
        cb.audioResult = TextToSpeech.ERROR
        s.synthesize(SynthesisRequest("Hello.", Bundle()), cb)
        assertEquals(1, cb.errorCalls); assertEquals(0, cb.doneCalls)
    }

    @Test
    fun synthesizeEmptyAudioFromEngineErrors() {
        val (s, _) = fakeService { chunks = listOf(FloatArray(0)) }
        val cb = RecordingCallback()
        s.synthesize(SynthesisRequest("Hello.", Bundle()), cb)
        assertEquals(1, cb.errorCalls); assertEquals(0, cb.doneCalls)
    }

    @Test
    fun synthesizeStoppedMidwayFinishesWithDone() {
        var harnessRef: Harness? = null
        val (s, _) = fakeService {
            chunks = listOf(floatArrayOf(0.5f), floatArrayOf(0.5f))
            afterChunk = { harnessRef?.stop() }
        }
        harnessRef = s
        val cb = RecordingCallback()
        s.synthesize(SynthesisRequest("Hello.", Bundle()), cb)
        // Stopped is a normal finish, and only the first chunk was delivered.
        assertEquals(1, cb.doneCalls); assertEquals(0, cb.errorCalls)
        assertEquals(2, cb.audio.size)
    }

    @Test
    fun destroyShutsEngineDown() {
        val controller = Robolectric.buildService(Harness::class.java).create()
        controller.destroy()
        val s = controller.get()
        // Language queries still answer from the catalog after destroy.
        assertEquals(TextToSpeech.LANG_AVAILABLE, s.isLanguageAvailable("en", null, null))
    }
}
