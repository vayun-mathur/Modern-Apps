package com.vayunmathur.speech.domain

/**
 * The languages and voices Supertonic 3 can speak, and the name forms the TTS framework asks for.
 *
 * This replaced a 1,118-line registry. Almost all of that was **delivery**: a mirror URL, a
 * SHA-256, a size estimate and an espeak-ng dictionary name per language, plus the code to
 * download, unzip, migrate and garbage-collect 1,834 MB of voices across 42 archives. One bundle
 * ships in the APK now, so none of it is needed and what is left is identity: given whatever
 * spelling of a language the framework hands us, which language is it.
 *
 * # The 16 languages that went, and the 5 that arrived
 *
 * Supertonic covers 31 languages against Piper's 42, and the difference is deliberate rather than
 * accidental. Gone: Chinese, Hebrew, Thai, Bengali, Telugu, Malayalam, Marathi, Urdu, Serbian,
 * Norwegian, Catalan, Georgian, Kurdish, Luxembourgish, Nepali and Swahili. Arrived: Estonian,
 * Croatian, Lithuanian, Latvian and Slovenian.
 *
 * A language that is not here does not appear in [ALL], so it is never advertised and never shown
 * in the setup screen — a user does not get offered a language and then find it silent.
 *
 * # A voice is not a model
 *
 * Piper's voices were separate networks, one download each. Supertonic's are two small style
 * tensors read against the same four networks, so all ten cost ~250 KB together and switching
 * between them re-uploads nothing. Every language can be spoken by every voice, which is why
 * [VOICES] is a flat list rather than a property of a language.
 *
 * # Why the identity layer is still needed
 *
 * `TextToSpeech` asks for a language in at least four spellings — a BCP-47 tag, an ISO-639-1 code,
 * an ISO-639-2 code with an ISO-3166-3 country, and a voice name that may carry a `-x-` extension —
 * and the `CHECK_TTS_DATA` probe insists on the ISO-3 form specifically. Getting any of them wrong
 * makes the Play button in the system settings silently do nothing, which is a failure mode with no
 * error message anywhere, so all four are kept and [resolve] accepts any of them.
 */
data class SupertonicLanguage(
    /** ISO-639-1, the key everything else is looked up by. */
    val code: String,
    /** The BCP-47 tag, which is what [android.speech.tts.Voice] names are built from. */
    val bcp47: String,
    /** ISO-639-2/T, which `CHECK_TTS_DATA` reports in. */
    val iso3: String,
    /** ISO-3166-3, the country half of the `CHECK_TTS_DATA` pair. */
    val iso3Country: String,
    val englishName: String,
    val nativeName: String,
)

/** Singleton holding supertonic voices state and operations. */
object SupertonicVoices {
    /**
     * All 31 languages, English first because it is the default and the rest alphabetically by
     * code so the setup screen and the system voice list are in a stable, reviewable order.
     */
    val ALL: List<SupertonicLanguage> = listOf(
        SupertonicLanguage("en", "en-US", "eng", "USA", "English", "English"),
        SupertonicLanguage("ar", "ar-SA", "ara", "SAU", "Arabic", "العربية"),
        SupertonicLanguage("bg", "bg-BG", "bul", "BGR", "Bulgarian", "Български"),
        SupertonicLanguage("cs", "cs-CZ", "ces", "CZE", "Czech", "Čeština"),
        SupertonicLanguage("da", "da-DK", "dan", "DNK", "Danish", "Dansk"),
        SupertonicLanguage("de", "de-DE", "deu", "DEU", "German", "Deutsch"),
        SupertonicLanguage("el", "el-GR", "ell", "GRC", "Greek", "Ελληνικά"),
        SupertonicLanguage("es", "es-ES", "spa", "ESP", "Spanish", "Español"),
        SupertonicLanguage("et", "et-EE", "est", "EST", "Estonian", "Eesti"),
        SupertonicLanguage("fi", "fi-FI", "fin", "FIN", "Finnish", "Suomi"),
        SupertonicLanguage("fr", "fr-FR", "fra", "FRA", "French", "Français"),
        SupertonicLanguage("hi", "hi-IN", "hin", "IND", "Hindi", "हिन्दी"),
        SupertonicLanguage("hr", "hr-HR", "hrv", "HRV", "Croatian", "Hrvatski"),
        SupertonicLanguage("hu", "hu-HU", "hun", "HUN", "Hungarian", "Magyar"),
        SupertonicLanguage("id", "id-ID", "ind", "IDN", "Indonesian", "Bahasa Indonesia"),
        SupertonicLanguage("it", "it-IT", "ita", "ITA", "Italian", "Italiano"),
        SupertonicLanguage("ja", "ja-JP", "jpn", "JPN", "Japanese", "日本語"),
        SupertonicLanguage("ko", "ko-KR", "kor", "KOR", "Korean", "한국어"),
        SupertonicLanguage("lt", "lt-LT", "lit", "LTU", "Lithuanian", "Lietuvių"),
        SupertonicLanguage("lv", "lv-LV", "lav", "LVA", "Latvian", "Latviešu"),
        SupertonicLanguage("nl", "nl-NL", "nld", "NLD", "Dutch", "Nederlands"),
        SupertonicLanguage("pl", "pl-PL", "pol", "POL", "Polish", "Polski"),
        SupertonicLanguage("pt", "pt-BR", "por", "BRA", "Portuguese", "Português"),
        SupertonicLanguage("ro", "ro-RO", "ron", "ROU", "Romanian", "Română"),
        SupertonicLanguage("ru", "ru-RU", "rus", "RUS", "Russian", "Русский"),
        SupertonicLanguage("sk", "sk-SK", "slk", "SVK", "Slovak", "Slovenčina"),
        SupertonicLanguage("sl", "sl-SI", "slv", "SVN", "Slovenian", "Slovenščina"),
        SupertonicLanguage("sv", "sv-SE", "swe", "SWE", "Swedish", "Svenska"),
        SupertonicLanguage("tr", "tr-TR", "tur", "TUR", "Turkish", "Türkçe"),
        SupertonicLanguage("uk", "uk-UA", "ukr", "UKR", "Ukrainian", "Українська"),
        SupertonicLanguage("vi", "vi-VN", "vie", "VNM", "Vietnamese", "Tiếng Việt"),
    )

    /** English, used whenever a request names no language we recognise. */
    val DEFAULT: SupertonicLanguage = ALL.first()

    /**
     * The ten voice styles in the bundle, five female and five male.
     *
     * These are `style_<name>.bin` filenames, so the list has to match what
     * `scripts/ml/supertonic_bundle.py` wrote into `assets/supertonic/`. A name that is not there
     * fails at load with the asset's own error rather than silently falling back, which is what
     * makes a typo here visible.
     */
    val VOICES: List<String> = listOf("F1", "F2", "F3", "F4", "F5", "M1", "M2", "M3", "M4", "M5")

    /** The voice used when a request names none. */
    const val DEFAULT_VOICE = "F1"

    /**
     * The `-x-` marker separating a BCP-47 tag from a voice style in a
     * [android.speech.tts.Voice] name, as in `en-US-x-F1`.
     *
     * BCP-47's private-use subtag is the only place a name may carry something the standard does
     * not define, and the framework passes voice names through verbatim, so this is how a caller
     * asks for a specific voice rather than just a language.
     */
    private const val VOICE_MARK = "-x-"

    private val byCodeMap = ALL.associateBy { it.code }
    private val byBcp47Map = ALL.associateBy { it.bcp47.lowercase() }
    private val byIso3Map = ALL.associateBy { it.iso3 }

    fun byCode(code: String): SupertonicLanguage? = byCodeMap[code.lowercase()]

    fun byBcp47(tag: String): SupertonicLanguage? = byBcp47Map[tag.lowercase().replace('_', '-')]

    fun byIso3(iso3: String): SupertonicLanguage? = byIso3Map[iso3.lowercase()]

    /** The voice name this engine advertises for `language` spoken by `voice`. */
    fun voiceName(language: SupertonicLanguage, voice: String): String =
        "${language.bcp47}$VOICE_MARK$voice"

    /** Whether `voiceName` carries a `-x-` style at all, whether or not it is one we have. */
    fun namesAStyle(voiceName: String?): Boolean = voiceName?.contains(VOICE_MARK) == true

    /**
     * The voice style named in `voiceName`, or null if it names none or names one we do not have.
     *
     * Case-insensitive, because the framework round-trips voice names through settings storage and
     * has been observed to lower-case them.
     */
    fun styleIn(voiceName: String?): String? {
        val at = voiceName?.indexOf(VOICE_MARK) ?: return null
        if (at < 0) return null
        val named = voiceName.substring(at + VOICE_MARK.length)
        return VOICES.firstOrNull { it.equals(named, ignoreCase = true) }
    }

    /**
     * The language a request is asking for, from whichever of the four spellings it used.
     *
     * `voiceName` wins over `lang`, because a caller that named a voice was more specific than one
     * that named a language. Within each, the order is most specific first: a full tag, then a
     * three-letter code, then a two-letter one, then the language half of a tag we do not have the
     * region of — `pt-PT` resolves to the `pt-BR` voice rather than to nothing, which is the right
     * answer given there is one Portuguese voice.
     *
     * Null only when nothing in the request names a language we have. Callers treat that as
     * "unsupported" rather than falling back to [DEFAULT], so a request for Thai is answered
     * honestly instead of being read aloud in English.
     */
    fun resolve(
        lang: String? = null,
        country: String? = null,
        variant: String? = null,
        voiceName: String? = null,
    ): SupertonicLanguage? {
        voiceName?.let { name ->
            // Strip a voice style first: `en-US-x-F1` is a request for English, and the style is
            // read separately by `styleIn`.
            val tag = name.substringBefore(VOICE_MARK)
            byBcp47(tag)?.let { return it }
            fromParts(tag)?.let { return it }
        }
        if (lang.isNullOrEmpty()) return null
        // The framework passes language, country and variant separately, and a tag is the three
        // joined — so try the joined form before the parts, or `en` + `US` would resolve by the
        // two-letter code and lose the region.
        val joined = listOfNotNull(
            lang,
            country?.takeIf { it.isNotEmpty() },
            variant?.takeIf { it.isNotEmpty() },
        ).joinToString("-")
        byBcp47(joined)?.let { return it }
        return fromParts(lang)
    }

    /** A tag or code in any of the accepted spellings, ignoring any region it carries. */
    private fun fromParts(tag: String): SupertonicLanguage? {
        byBcp47(tag)?.let { return it }
        val head = tag.replace('_', '-').substringBefore('-')
        return when (head.length) {
            2 -> byCode(head)
            3 -> byIso3(head)
            else -> null
        }
    }
}
