package com.vayunmathur.speech.service

import android.media.AudioFormat
import android.speech.tts.SynthesisCallback
import android.speech.tts.SynthesisRequest
import android.speech.tts.TextToSpeech
import android.speech.tts.TextToSpeechService
import android.speech.tts.Voice
import com.vayunmathur.speech.domain.SupertonicLanguage
import com.vayunmathur.speech.domain.SupertonicVoices
import com.vayunmathur.speech.platform.SupertonicBundle
import com.vayunmathur.speech.platform.SupertonicEngine
import java.util.Locale
import kotlin.math.roundToInt

/**
 * System text-to-speech engine backed by offline **Supertonic 3** running on the Vulkan compute
 * runtime in `:library:ml`. Once selected as the device's TTS engine, any app that uses
 * [android.speech.tts.TextToSpeech] — Translate's read-aloud, TalkBack, ebook readers — synthesises
 * fully on-device.
 *
 * # What bundling removed from this service
 *
 * The Piper version of this file asked "is this language installed?" in six of its eleven overrides,
 * because a Piper voice was an 80 MB download that might or might not be on disk. The answer varied
 * per language, per install and over time, so every override had to cope: `onGetVoices` published
 * only extracted voices, `onIsLanguageAvailable` hid the rest, and a legacy English path was
 * threaded through all of them for installs predating the multilingual registry.
 *
 * One bundle in the APK serves all 31 languages, so that question has one answer for the whole
 * install — [SupertonicBundle.isPresent] — and everything downstream of it became unconditional.
 *
 * # Two things a caller will notice
 *
 * * **44,100 Hz, always.** Piper's rate was per-voice, read from each `config.json`; Supertonic's is
 *   fixed by the vocoder. That doubles the PCM byte volume against Piper's 22,050, which is why
 *   [onSynthesizeText] splits every chunk against [SynthesisCallback.maxBufferSize] rather than
 *   trusting one to fit.
 * * **The speech rate is ignored.** See [SupertonicEngine.synthesize] for why.
 */
open class SupertonicTtsService : TextToSpeechService() {

    private val engine by lazy { createEngine() }

    /** Factory so tests can substitute a fake engine; production uses the real one. */
    protected open fun createEngine(): SupertonicEngine = SupertonicEngine(applicationContext)

    @Volatile private var stopped = false
    @Volatile private var language: SupertonicLanguage = SupertonicVoices.DEFAULT
    @Volatile private var voice: String = SupertonicVoices.DEFAULT_VOICE

    override fun onCreate() {
        super.onCreate()
        // ~105 MB of weights to stream into GPU memory and four command buffers to record, so the
        // first utterance would otherwise stall for seconds. The same thread reclaims whatever
        // Piper left behind, which on an upgraded install is up to 1.8 GB of dead voices.
        Thread {
            SupertonicBundle.deleteOrphanedPiperVoices(applicationContext)
            engine.preload()
        }.start()
    }

    override fun onDestroy() {
        engine.close()
        super.onDestroy()
    }

    /** Whether this install can speak at all. Constant for the life of the process. */
    private fun usable(): Boolean = SupertonicBundle.isPresent(applicationContext)

    override fun onIsLanguageAvailable(lang: String?, country: String?, variant: String?): Int {
        if (!usable()) return TextToSpeech.LANG_NOT_SUPPORTED
        SupertonicVoices.resolve(lang, country, variant) ?: return TextToSpeech.LANG_NOT_SUPPORTED
        // The framework distinguishes how much of the request was honoured, and it decides whether
        // Settings offers a region picker. There is one voice per language here, so a request that
        // named a country got its country in the sense that matters.
        return when {
            !country.isNullOrEmpty() && !variant.isNullOrEmpty() ->
                TextToSpeech.LANG_COUNTRY_VAR_AVAILABLE
            !country.isNullOrEmpty() -> TextToSpeech.LANG_COUNTRY_AVAILABLE
            else -> TextToSpeech.LANG_AVAILABLE
        }
    }

    override fun onLoadLanguage(lang: String?, country: String?, variant: String?): Int {
        val availability = onIsLanguageAvailable(lang, country, variant)
        if (availability != TextToSpeech.LANG_NOT_SUPPORTED) {
            SupertonicVoices.resolve(lang, country, variant)?.let { language = it }
        }
        return availability
    }

    override fun onGetLanguage(): Array<String> =
        arrayOf(language.iso3, language.iso3Country, "")

    override fun onGetDefaultVoiceNameFor(
        lang: String?,
        country: String?,
        variant: String?,
    ): String? {
        if (!usable()) return null
        // The framework probes with all three null to ask "what would you speak by default". It
        // then looks the answer up in `onGetVoices`, and a name that is not there leaves the Play
        // button in Settings doing nothing with no error anywhere, so both sides build the name
        // through `SupertonicVoices.voiceName`.
        if (lang == null && country == null && variant == null) {
            return SupertonicVoices.voiceName(SupertonicVoices.DEFAULT, SupertonicVoices.DEFAULT_VOICE)
        }
        val found = SupertonicVoices.resolve(lang, country, variant) ?: return null
        return SupertonicVoices.voiceName(found, SupertonicVoices.DEFAULT_VOICE)
    }

    /**
     * Whether `voiceName` is one this engine published.
     *
     * The style half is checked as well as the language, because [onGetVoices] publishes an explicit
     * name per style: accepting `en-US-x-BOGUS` here would be a success for a voice the caller will
     * never hear, since [onLoadVoice] would then leave whatever style was already selected.
     */
    override fun onIsValidVoiceName(voiceName: String?): Int {
        if (voiceName == null || !usable()) return TextToSpeech.ERROR
        if (SupertonicVoices.resolve(voiceName = voiceName) == null) return TextToSpeech.ERROR
        if (SupertonicVoices.namesAStyle(voiceName) && SupertonicVoices.styleIn(voiceName) == null) {
            return TextToSpeech.ERROR
        }
        return TextToSpeech.SUCCESS
    }

    override fun onLoadVoice(voiceName: String?): Int {
        if (onIsValidVoiceName(voiceName) != TextToSpeech.SUCCESS) return TextToSpeech.ERROR
        val found = SupertonicVoices.resolve(voiceName = voiceName) ?: return TextToSpeech.ERROR
        language = found
        SupertonicVoices.styleIn(voiceName)?.let { voice = it }
        return TextToSpeech.SUCCESS
    }

    /**
     * Every language in all three name forms, plus one per voice style.
     *
     * Three forms because callers ask in three: the plain BCP-47 tag, the `iso3-ISO3COUNTRY` pair
     * that `CHECK_TTS_DATA` deals in, and the `-x-` form that names a specific voice. Publishing
     * fewer is what breaks Settings' Play button, since it enables it only when
     * [onGetDefaultVoiceNameFor]'s answer appears in this list.
     */
    override fun onGetVoices(): MutableList<Voice> {
        if (!usable()) return mutableListOf()
        val voices = mutableListOf<Voice>()
        for (found in SupertonicVoices.ALL) {
            val locale = localeFor(found)
            voices += voiceEntry(found.bcp47, locale)
            voices += voiceEntry("${found.iso3}-${found.iso3Country}", locale)
            for (style in SupertonicVoices.VOICES) {
                voices += voiceEntry(SupertonicVoices.voiceName(found, style), locale)
            }
        }
        return voices
    }

    override fun onStop() {
        stopped = true
    }

    override fun onSynthesizeText(request: SynthesisRequest, callback: SynthesisCallback) {
        stopped = false
        if (!usable()) {
            callback.error()
            return
        }
        SupertonicVoices.resolve(
            lang = request.language,
            country = request.country,
            variant = request.variant,
            voiceName = request.voiceName,
        )?.let { language = it }
        SupertonicVoices.styleIn(request.voiceName)?.let { voice = it }

        val rate = engine.sampleRate
        val text = request.charSequenceText?.toString().orEmpty()
        if (text.isBlank()) {
            // Still start and finish: a caller that queued an empty utterance is waiting for its
            // completion callback, and erroring would surface as a failure it did not cause.
            callback.start(rate, AudioFormat.ENCODING_PCM_16BIT, 1)
            callback.done()
            return
        }
        if (!engine.preload() || !engine.voice(voice)) {
            callback.error()
            return
        }
        if (callback.start(rate, AudioFormat.ENCODING_PCM_16BIT, 1) != TextToSpeech.SUCCESS) {
            return
        }

        val maxBytes = callback.maxBufferSize
        val ok = engine.synthesize(text, language.code) { samples ->
            if (stopped) return@synthesize false
            val pcm = floatsToPcm16(samples)
            var offset = 0
            while (offset < pcm.size) {
                if (stopped) return@synthesize false
                val n = minOf(maxBytes, pcm.size - offset)
                if (callback.audioAvailable(pcm, offset, n) != TextToSpeech.SUCCESS) {
                    return@synthesize false
                }
                offset += n
            }
            true
        }

        if (ok || stopped) callback.done() else callback.error()
    }

    private fun voiceEntry(name: String, locale: Locale): Voice = Voice(
        name,
        locale,
        Voice.QUALITY_VERY_HIGH,
        Voice.LATENCY_NORMAL,
        false,
        emptySet(),
    )

    /**
     * The locale for a language, from its tag.
     *
     * `forLanguageTag` never throws — a tag it cannot parse gives `Locale.ROOT` — and all 31 tags in
     * [SupertonicVoices.ALL] are well-formed, so there is nothing to fall back to.
     */
    private fun localeFor(found: SupertonicLanguage): Locale =
        Locale.forLanguageTag(found.bcp47)

    /** Supertonic's float samples in `-1..1` as little-endian 16-bit PCM. */
    private fun floatsToPcm16(samples: FloatArray): ByteArray {
        val out = ByteArray(samples.size * BYTES_PER_SAMPLE)
        var i = 0
        for (sample in samples) {
            val value = (sample.coerceIn(-1f, 1f) * PCM_PEAK).roundToInt()
            out[i++] = (value and BYTE_MASK).toByte()
            out[i++] = ((value shr BYTE_BITS) and BYTE_MASK).toByte()
        }
        return out
    }

    private companion object {
        /** Bytes per 16-bit PCM sample. */
        const val BYTES_PER_SAMPLE = 2
        /** Full-scale peak for float-to-int16 conversion. */
        const val PCM_PEAK = 32767f
        /** Low byte mask for little-endian packing. */
        const val BYTE_MASK = 0xFF
        /** Bits per byte, for the high-byte shift. */
        const val BYTE_BITS = 8
    }
}
