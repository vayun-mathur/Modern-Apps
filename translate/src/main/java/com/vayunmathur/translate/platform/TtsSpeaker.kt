package com.vayunmathur.translate.platform

import com.vayunmathur.translate.domain.Languages

import android.content.Context
import android.speech.tts.TextToSpeech
import com.vayunmathur.library.log.Log
import java.util.Locale
import java.util.MissingResourceException

/**
 * Small wrapper over the system default [TextToSpeech] engine that speaks translated
 * text in the *target* language.
 *
 * Two things this has to get right that a bare `engine.language = locale` does not:
 *
 *  - **Locale matching.** The [Languages] codes are BCP-47 tags qualified with the
 *    NLLB script where the language needs it ("zh-Hans", "yue-Hant", "taq-Tfng"),
 *    while the voices an engine actually has installed are usually
 *    region-qualified ("pt-BR", "zh-CN"). Asking for the bare tag can come back
 *    unsupported even though the engine plainly speaks the language, so we search
 *    the engine's own voice list for a locale with the same language and prefer
 *    the region the device is in.
 *  - **Failing loudly.** `setLanguage` returns an error code and leaves the *previous*
 *    locale in place when a language isn't installed — engines ship voice data per
 *    language, so anything the user hasn't downloaded lands here. Ignoring that return
 *    value is what made the translation get read aloud in the device language: English
 *    phonetics over Japanese text. We check it and skip the utterance instead, telling
 *    the caller via `onMissingVoice` so it can say so rather than play the wrong thing.
 *
 * Owns the engine lifecycle: create once, [shutdown] when the owner is cleared. A [speak]
 * issued before init completes is queued and replayed, not dropped.
 */
class TtsSpeaker(context: Context) {

    private var tts: TextToSpeech? = null
    private var ready = false

    /** Set when [speak] is called before init lands; replayed once the engine is up. */
    private var pending: Request? = null

    /** Locales the engine can actually speak, per language code. Only hits are cached, so
     *  voice data installed later in the session is picked up on the next tap. */
    private val resolved = HashMap<String, Locale>()

    private class Request(
        val text: String,
        val languageCode: String,
        val onMissingVoice: (() -> Unit)?,
    )

    init {
        tts = TextToSpeech(context.applicationContext) { status ->
            val queued = synchronized(this) {
                ready = status == TextToSpeech.SUCCESS
                if (!ready) Log.status(TAG, "TextToSpeech init failed: $status")
                pending.also { pending = null }
            }
            if (queued != null) utter(queued)
        }
    }

    /**
     * Whether the engine has a voice for [languageCode]. False until the engine finishes
     * initialising, so treat it as "not yet" rather than "never".
     */
    fun isLanguageSupported(languageCode: String): Boolean = localeFor(languageCode) != null

    /**
     * Speak [text] in [languageCode], flushing any in-progress utterance. When the engine
     * has no voice for that language nothing is spoken and [onMissingVoice] is invoked —
     * possibly on a binder thread, so post to the main thread before touching the UI.
     */
    fun speak(text: String, languageCode: String, onMissingVoice: (() -> Unit)? = null) {
        if (text.isBlank()) return
        val request = Request(text, languageCode, onMissingVoice)
        synchronized(this) {
            if (tts == null) return
            if (!ready) {
                pending = request
                return
            }
        }
        utter(request)
    }

    fun stop() {
        synchronized(this) { pending = null }
        try {
            tts?.stop()
        } catch (ignored: Exception) {
            // Already torn down; nothing to stop.
        }
    }
    fun shutdown() {
        val engine = synchronized(this) {
            pending = null
            ready = false
            resolved.clear()
            tts.also { tts = null }
        }
        try {
            engine?.stop()
            engine?.shutdown()
        } catch (ignored: Exception) {
            // Already torn down; nothing to shut down.
        }
    }

    // Broad catches are deliberate: the TTS engine throws undocumented
    // RuntimeExceptions (not just IllegalArgumentException) on bad state.
    @Suppress("TooGenericExceptionCaught")
    private fun utter(req: Request) {
        val engine = synchronized(this) { tts.takeIf { ready } } ?: return
        val locale = localeFor(req.languageCode)
        if (locale == null) {
            Log.status(TAG, "engine has no voice for ${req.languageCode}; not speaking")
            req.onMissingVoice?.invoke()
            return
        }
        try {
            // Re-check the result: voice data can be uninstalled between resolution and
            // now, and a failed switch would otherwise speak in the previous locale.
            if (engine.setLanguage(locale) < TextToSpeech.LANG_AVAILABLE) {
                Log.status(TAG, "engine refused $locale; not speaking")
                synchronized(this) { resolved.remove(req.languageCode) }
                req.onMissingVoice?.invoke()
                return
            }
            engine.speak(req.text, TextToSpeech.QUEUE_FLUSH, null, UTTERANCE_ID)
        } catch (e: Exception) {
            Log.error(TAG, "speak failed", e)
        }
    }

    /** The best locale the engine can speak for [languageCode], or null if it has none.
     *
     * Many of the 202 NLLB languages have no system voice at all - nothing is cached for
     * them, and the caller reports the gap through `onMissingVoice` instead of speaking
     * in the wrong language. */
    private fun localeFor(languageCode: String): Locale? {
        val engine = synchronized(this) {
            resolved[languageCode]?.let { return it }
            tts.takeIf { ready }
        } ?: return null

        val wanted = Locale.forLanguageTag(languageCode)
        val match = wanted.takeIf { isInstalled(engine, it) } ?: bestRegional(engine, wanted)
        if (match != null) synchronized(this) { resolved[languageCode] = match }
        return match
    }

    /**
     * A region-qualified locale for the same language — "pt" → "pt-BR". Prefers the
     * device's own country when the engine offers it, so a Brazilian user hears pt-BR
     * rather than whichever variant happens to sort first.
     */
    // Broad catch is deliberate: availableLanguages throws undocumented
    // RuntimeExceptions on some engines.
    @Suppress("TooGenericExceptionCaught")
    private fun bestRegional(engine: TextToSpeech, wanted: Locale): Locale? {
        val installed = try {
            engine.availableLanguages.orEmpty()
        } catch (e: Exception) {
            Log.status(TAG, "could not list available languages", e)
            return null
        }
        val sameLanguage = installed
            .filter { sameLanguage(it, wanted) && isInstalled(engine, it) }
            .sortedBy { it.toLanguageTag() }
        val here = Locale.getDefault().country
        return sameLanguage.firstOrNull { it.country.equals(here, ignoreCase = true) }
            ?: sameLanguage.firstOrNull()
    }

    /** True for a language the engine both knows and has voice data for. */
    @Suppress("TooGenericExceptionCaught")
    private fun isInstalled(engine: TextToSpeech, locale: Locale): Boolean = try {
        // LANG_MISSING_DATA and LANG_NOT_SUPPORTED are the negative codes.
        engine.isLanguageAvailable(locale) >= TextToSpeech.LANG_AVAILABLE
    } catch (e: Exception) {
        Log.status(TAG, "availability check failed for $locale", e)
        false
    }

    /** Same language ignoring region, tolerating ISO-639-1 vs -2 spellings ("he"/"heb"). */
    private fun sameLanguage(a: Locale, b: Locale): Boolean {
        if (a.language.equals(b.language, ignoreCase = true)) return true
        return try {
            a.isO3Language.equals(b.isO3Language, ignoreCase = true)
        } catch (_: MissingResourceException) {
            false
        }
    }

    /** Logging and utterance constants for [TtsSpeaker]. */
    companion object {
        private const val TAG = "TtsSpeaker"
        private const val UTTERANCE_ID = "translate_output"
    }
}

