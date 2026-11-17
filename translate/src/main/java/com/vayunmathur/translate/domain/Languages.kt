package com.vayunmathur.translate.domain

/**
 * One selectable language. [code] is the BCP-47 tag used for the language picker,
 * text-to-speech (via [java.util.Locale]) and speech recognition; [flores] is the
 * NLLB flores200 code (e.g. `eng_Latn`, `zho_Hans`) that selects the model's
 * source/target language token via
 * [com.vayunmathur.translate.platform.NllbModel.tokenId].
 * [AUTO] is a sentinel meaning "detect the source language automatically" and is
 * only offered as a *source*, never a target.
 */
data class Language(
    val code: String,
    val englishName: String,
    val nativeName: String,
    /** The flores200 code, e.g. `eng_Latn`. Selects the NLLB language token. */
    val flores: String,
)

/** Catalogue of selectable languages for Translate. */
object Languages {
    /**
     * Sentinel source option: let the engine detect the language. NLLB always needs
     * an explicit source token, so [com.vayunmathur.translate.platform.NllbTranslator]
     * falls back to English (`eng_Latn`, mirrored here) when the source is [AUTO].
     */
    val AUTO = Language("auto", "Auto (detect)", "Auto", "eng_Latn")

    /**
     * All 202 NLLB flores200 languages, ordered by English name for the picker.
     * Codes must stay in sync with
     * [com.vayunmathur.translate.platform.NllbModel.tokenId] - a language listed here
     * whose [Language.flores] has no token id silently fails to translate.
     *
     * TTS voice gaps are expected: many flores languages have no system voice, and
     * [com.vayunmathur.translate.platform.TtsSpeaker] reports those through
     * `onMissingVoice` instead of speaking in the wrong language.
     */
    val ALL: List<Language> = listOf(
        Language("ace-Latn", "Acehnese", "Basa Acèh", "ace_Latn"),
        Language("ace-Arab", "Acehnese (Arabic)", "أتجه", "ace_Arab"),
        Language("af", "Afrikaans", "Afrikaans", "afr_Latn"),
        Language("ak", "Akan", "Akan", "aka_Latn"),
        Language("am", "Amharic", "አማርኛ", "amh_Ethi"),
        Language("hy", "Armenian", "Հայերեն", "hye_Armn"),
        Language("as", "Assamese", "অসমীয়া", "asm_Beng"),
        Language("ast", "Asturian", "Asturianu", "ast_Latn"),
        Language("awa", "Awadhi", "अवधी", "awa_Deva"),
        Language("qu", "Ayacucho Quechua", "Runasimi", "quy_Latn"),
        Language("ban", "Balinese", "Basa Bali", "ban_Latn"),
        Language("bm", "Bambara", "Bamanankan", "bam_Latn"),
        Language("bjn-Latn", "Banjar", "Banjar", "bjn_Latn"),
        Language("bjn-Arab", "Banjar (Arabic)", "بنجر", "bjn_Arab"),
        Language("ba", "Bashkir", "Башҡортса", "bak_Cyrl"),
        Language("eu", "Basque", "Euskara", "eus_Latn"),
        Language("be", "Belarusian", "Беларуская", "bel_Cyrl"),
        Language("bem", "Bemba", "Ichibemba", "bem_Latn"),
        Language("bn", "Bengali", "বাংলা", "ben_Beng"),
        Language("bho", "Bhojpuri", "भोजपुरी", "bho_Deva"),
        Language("bs", "Bosnian", "Bosanski", "bos_Latn"),
        Language("bug", "Buginese", "Basa Ugi", "bug_Latn"),
        Language("bg", "Bulgarian", "Български", "bul_Cyrl"),
        Language("my", "Burmese", "မြန်မာ", "mya_Mymr"),
        Language("yue-Hant", "Cantonese", "廣東話", "yue_Hant"),
        Language("kea", "Cape Verdean Creole", "Kriolu", "kea_Latn"),
        Language("ca", "Catalan", "Català", "cat_Latn"),
        Language("ceb", "Cebuano", "Cebuano", "ceb_Latn"),
        Language("tzm", "Central Atlas Tamazight", "ⵜⴰⵎⴰⵣⵉⵖⵜ", "tzm_Tfng"),
        Language("ay", "Central Aymara", "Aymar aru", "ayr_Latn"),
        Language("knc-Latn", "Central Kanuri", "Kanuri", "knc_Latn"),
        Language("knc-Arab", "Central Kanuri (Arabic)", "كانوري", "knc_Arab"),
        Language("ckb", "Central Kurdish", "کوردی", "ckb_Arab"),
        Language("hne", "Chhattisgarhi", "छत्तीसगढ़ी", "hne_Deva"),
        Language("zh-Hans", "Chinese (Simplified)", "简体中文", "zho_Hans"),
        Language("zh-Hant", "Chinese (Traditional)", "繁體中文", "zho_Hant"),
        Language("cjk", "Chokwe", "Ucokwe", "cjk_Latn"),
        Language("crh", "Crimean Tatar", "Qırımtatar", "crh_Latn"),
        Language("hr", "Croatian", "Hrvatski", "hrv_Latn"),
        Language("cs", "Czech", "Čeština", "ces_Latn"),
        Language("da", "Danish", "Dansk", "dan_Latn"),
        Language("fa-AF", "Dari", "دری", "prs_Arab"),
        Language("dik", "Dinka", "Thuɔŋjäŋ", "dik_Latn"),
        Language("nl", "Dutch", "Nederlands", "nld_Latn"),
        Language("dyu", "Dyula", "Julakan", "dyu_Latn"),
        Language("dz", "Dzongkha", "རྫོང་ཁ", "dzo_Tibt"),
        Language("arz", "Egyptian Arabic", "مصري", "arz_Arab"),
        Language("en", "English", "English", "eng_Latn"),
        Language("eo", "Esperanto", "Esperanto", "epo_Latn"),
        Language("et", "Estonian", "Eesti", "est_Latn"),
        Language("ee", "Ewe", "Eʋegbe", "ewe_Latn"),
        Language("fo", "Faroese", "Føroyskt", "fao_Latn"),
        Language("fj", "Fijian", "Na Vosa Vakaviti", "fij_Latn"),
        Language("fi", "Finnish", "Suomi", "fin_Latn"),
        Language("fon", "Fon", "Fɔngbè", "fon_Latn"),
        Language("fr", "French", "Français", "fra_Latn"),
        Language("fur", "Friulian", "Furlan", "fur_Latn"),
        Language("gl", "Galician", "Galego", "glg_Latn"),
        Language("lg", "Ganda", "Luganda", "lug_Latn"),
        Language("ka", "Georgian", "ქართული", "kat_Geor"),
        Language("de", "German", "Deutsch", "deu_Latn"),
        Language("el", "Greek", "Ελληνικά", "ell_Grek"),
        Language("gn", "Guarani", "Avañeẽ", "grn_Latn"),
        Language("gu", "Gujarati", "ગુજરાતી", "guj_Gujr"),
        Language("ht", "Haitian Creole", "Kreyòl ayisyen", "hat_Latn"),
        Language("mn", "Halh Mongolian", "Монгол", "khk_Cyrl"),
        Language("ha", "Hausa", "Hausa", "hau_Latn"),
        Language("he", "Hebrew", "עברית", "heb_Hebr"),
        Language("hi", "Hindi", "हिन्दी", "hin_Deva"),
        Language("hu", "Hungarian", "Magyar", "hun_Latn"),
        Language("is", "Icelandic", "Íslenska", "isl_Latn"),
        Language("ig", "Igbo", "Igbo", "ibo_Latn"),
        Language("ilo", "Ilocano", "Ilokano", "ilo_Latn"),
        Language("id", "Indonesian", "Bahasa Indonesia", "ind_Latn"),
        Language("ga", "Irish", "Gaeilge", "gle_Latn"),
        Language("it", "Italian", "Italiano", "ita_Latn"),
        Language("ja", "Japanese", "日本語", "jpn_Jpan"),
        Language("jv", "Javanese", "Basa Jawa", "jav_Latn"),
        Language("kbp", "Kabiye", "Kabɩyɛ", "kbp_Latn"),
        Language("kab", "Kabyle", "Taqbaylit", "kab_Latn"),
        Language("kac", "Kachin", "Jingpho", "kac_Latn"),
        Language("kam", "Kamba", "Kikamba", "kam_Latn"),
        Language("kn", "Kannada", "ಕನ್ನಡ", "kan_Knda"),
        Language("ks-Arab", "Kashmiri (Arabic)", "کٲشُر", "kas_Arab"),
        Language("ks-Deva", "Kashmiri (Devanagari)", "कॉशुर", "kas_Deva"),
        Language("kk", "Kazakh", "Қазақша", "kaz_Cyrl"),
        Language("km", "Khmer", "ខ្មែរ", "khm_Khmr"),
        Language("ki", "Kikuyu", "Gĩkũyũ", "kik_Latn"),
        Language("kmb", "Kimbundu", "Kimbundu", "kmb_Latn"),
        Language("rw", "Kinyarwanda", "Ikinyarwanda", "kin_Latn"),
        Language("kg", "Kongo", "Kikongo", "kon_Latn"),
        Language("ko", "Korean", "한국어", "kor_Hang"),
        Language("ky", "Kyrgyz", "Кыргызча", "kir_Cyrl"),
        Language("lo", "Lao", "ລາວ", "lao_Laoo"),
        Language("ltg", "Latgalian", "Latgalīšu", "ltg_Latn"),
        Language("lv", "Latvian", "Latviešu", "lvs_Latn"),
        Language("lij", "Ligurian", "Ligure", "lij_Latn"),
        Language("li", "Limburgish", "Limburgs", "lim_Latn"),
        Language("ln", "Lingala", "Lingála", "lin_Latn"),
        Language("lt", "Lithuanian", "Lietuvių", "lit_Latn"),
        Language("lmo", "Lombard", "Lombard", "lmo_Latn"),
        Language("lua", "Luba-Lulua", "Tshiluba", "lua_Latn"),
        Language("luo", "Luo", "Dholuo", "luo_Latn"),
        Language("lb", "Luxembourgish", "Lëtzebuergesch", "ltz_Latn"),
        Language("mk", "Macedonian", "Македонски", "mkd_Cyrl"),
        Language("mag", "Magahi", "मगही", "mag_Deva"),
        Language("mai", "Maithili", "मैथिली", "mai_Deva"),
        Language("ms", "Malay", "Bahasa Melayu", "zsm_Latn"),
        Language("ml", "Malayalam", "മലയാളം", "mal_Mlym"),
        Language("mt", "Maltese", "Malti", "mlt_Latn"),
        Language("mni", "Manipuri", "মৈতৈলোন্", "mni_Beng"),
        Language("mr", "Marathi", "मराठी", "mar_Deva"),
        Language("acm", "Mesopotamian Arabic", "عراقي", "acm_Arab"),
        Language("min", "Minangkabau", "Baso Minang", "min_Latn"),
        Language("lus", "Mizo", "Mizo ṭawng", "lus_Latn"),
        Language("ar", "Modern Standard Arabic", "العربية", "arb_Arab"),
        Language("ary", "Moroccan Arabic", "دارجة", "ary_Arab"),
        Language("mos", "Mossi", "Mooré", "mos_Latn"),
        Language("mi", "Māori", "Te reo Māori", "mri_Latn"),
        Language("ars", "Najdi Arabic", "نجدي", "ars_Arab"),
        Language("ne", "Nepali", "नेपाली", "npi_Deva"),
        Language("ff", "Nigerian Fulfulde", "Fulfulde", "fuv_Latn"),
        Language("az", "North Azerbaijani", "Azərbaycan", "azj_Latn"),
        Language("apc", "North Levantine Arabic", "شامي", "apc_Arab"),
        Language("kmr", "Northern Kurdish", "Kurmancî", "kmr_Latn"),
        Language("nso", "Northern Sotho", "Sesotho sa Leboa", "nso_Latn"),
        Language("nb", "Norwegian Bokmål", "Norsk bokmål", "nob_Latn"),
        Language("nn", "Norwegian Nynorsk", "Norsk nynorsk", "nno_Latn"),
        Language("nus", "Nuer", "Thok Naath", "nus_Latn"),
        Language("ny", "Nyanja", "Chichewa", "nya_Latn"),
        Language("oc", "Occitan", "Occitan", "oci_Latn"),
        Language("or", "Odia", "ଓଡ଼ିଆ", "ory_Orya"),
        Language("om", "Oromo", "Afaan Oromoo", "gaz_Latn"),
        Language("pag", "Pangasinan", "Pangasinan", "pag_Latn"),
        Language("pap", "Papiamento", "Papiamentu", "pap_Latn"),
        Language("mg", "Plateau Malagasy", "Malagasy", "plt_Latn"),
        Language("pl", "Polish", "Polski", "pol_Latn"),
        Language("pt", "Portuguese", "Português", "por_Latn"),
        Language("pa", "Punjabi", "ਪੰਜਾਬੀ", "pan_Guru"),
        Language("ro", "Romanian", "Română", "ron_Latn"),
        Language("rn", "Rundi", "Kirundi", "run_Latn"),
        Language("ru", "Russian", "Русский", "rus_Cyrl"),
        Language("sm", "Samoan", "Gagana Samoa", "smo_Latn"),
        Language("sg", "Sango", "Sängö", "sag_Latn"),
        Language("sa", "Sanskrit", "संस्कृतम्", "san_Deva"),
        Language("sat", "Santali", "ᱥᱟᱱᱛᱟᱲᱤ", "sat_Beng"),
        Language("sc", "Sardinian", "Sardu", "srd_Latn"),
        Language("gd", "Scottish Gaelic", "Gàidhlig", "gla_Latn"),
        Language("sr", "Serbian", "Српски", "srp_Cyrl"),
        Language("shn", "Shan", "လိၵ်ႈတႆး", "shn_Mymr"),
        Language("sn", "Shona", "ChiShona", "sna_Latn"),
        Language("scn", "Sicilian", "Sicilianu", "scn_Latn"),
        Language("szl", "Silesian", "Ślōnski", "szl_Latn"),
        Language("sd", "Sindhi", "سنڌي", "snd_Arab"),
        Language("si", "Sinhala", "සිංහල", "sin_Sinh"),
        Language("sk", "Slovak", "Slovenčina", "slk_Latn"),
        Language("sl", "Slovenian", "Slovenščina", "slv_Latn"),
        Language("so", "Somali", "Soomaali", "som_Latn"),
        Language("azb", "South Azerbaijani", "تۆرکجه", "azb_Arab"),
        Language("ajp", "South Levantine Arabic", "شامي", "ajp_Arab"),
        Language("ps", "Southern Pashto", "پښتو", "pbt_Arab"),
        Language("st", "Southern Sotho", "Sesotho", "sot_Latn"),
        Language("es", "Spanish", "Español", "spa_Latn"),
        Language("su", "Sundanese", "Basa Sunda", "sun_Latn"),
        Language("sw", "Swahili", "Kiswahili", "swh_Latn"),
        Language("ss", "Swati", "SiSwati", "ssw_Latn"),
        Language("sv", "Swedish", "Svenska", "swe_Latn"),
        Language("tl", "Tagalog", "Tagalog", "tgl_Latn"),
        Language("acq", "Taizzi-Adeni Arabic", "تعزي-عدني", "acq_Arab"),
        Language("tg", "Tajik", "Тоҷикӣ", "tgk_Cyrl"),
        Language("taq-Latn", "Tamasheq (Latin)", "Tamajeq", "taq_Latn"),
        Language("taq-Tfng", "Tamasheq (Tifinagh)", "ⵜⴰⵎⴰⵌⴰⵆ", "taq_Tfng"),
        Language("ta", "Tamil", "தமிழ்", "tam_Taml"),
        Language("tt", "Tatar", "Татарча", "tat_Cyrl"),
        Language("te", "Telugu", "తెలుగు", "tel_Telu"),
        Language("th", "Thai", "ไทย", "tha_Thai"),
        Language("bo", "Tibetan", "བོད་སྐད", "bod_Tibt"),
        Language("ti", "Tigrinya", "ትግርኛ", "tir_Ethi"),
        Language("tpi", "Tok Pisin", "Tok Pisin", "tpi_Latn"),
        Language("sq", "Tosk Albanian", "Shqip", "als_Latn"),
        Language("ts", "Tsonga", "Xitsonga", "tso_Latn"),
        Language("tn", "Tswana", "Setswana", "tsn_Latn"),
        Language("tum", "Tumbuka", "ChiTumbuka", "tum_Latn"),
        Language("aeb", "Tunisian Arabic", "تونسي", "aeb_Arab"),
        Language("tr", "Turkish", "Türkçe", "tur_Latn"),
        Language("tk", "Turkmen", "Türkmençe", "tuk_Latn"),
        Language("tw", "Twi", "Twi", "twi_Latn"),
        Language("uk", "Ukrainian", "Українська", "ukr_Cyrl"),
        Language("umb", "Umbundu", "Umbundu", "umb_Latn"),
        Language("ur", "Urdu", "اردو", "urd_Arab"),
        Language("ug", "Uyghur", "ئۇيغۇرچە", "uig_Arab"),
        Language("uz", "Uzbek", "Oʻzbekcha", "uzn_Latn"),
        Language("vec", "Venetian", "Vèneto", "vec_Latn"),
        Language("vi", "Vietnamese", "Tiếng Việt", "vie_Latn"),
        Language("war", "Waray", "Winaray", "war_Latn"),
        Language("cy", "Welsh", "Cymraeg", "cym_Latn"),
        Language("fa", "Western Persian", "فارسی", "pes_Arab"),
        Language("wo", "Wolof", "Wolof", "wol_Latn"),
        Language("xh", "Xhosa", "isiXhosa", "xho_Latn"),
        Language("yi", "Yiddish", "ייִדיש", "ydd_Hebr"),
        Language("yo", "Yoruba", "Yorùbá", "yor_Latn"),
        Language("zu", "Zulu", "isiZulu", "zul_Latn"),
    )

    /** Sources include the [AUTO] sentinel; targets are the concrete languages. */
    val SOURCES: List<Language> = listOf(AUTO) + ALL
    val TARGETS: List<Language> = ALL

    private val index: Map<String, Language> = ALL.associateBy { it.code }

    /**
     * Codes stored by older versions that no longer name an entry: `zh` split into
     * Simplified/Traditional, `ns` is now the correct ISO-639 `nso`, and `no` is now
     * explicit Bokmal `nb`. `br`/`fy` (Breton/Frisian) have no NLLB equivalent and
     * fall through to English below.
     */
    private val LEGACY = mapOf("zh" to "zh-Hans", "ns" to "nso", "no" to "nb")

    /** Look up a language by [code], following [LEGACY], falling back to [AUTO] then English. */
    fun byCode(code: String): Language =
        if (code == AUTO.code) {
            AUTO
        } else {
            index[code] ?: LEGACY[code]?.let(index::get) ?: index.getValue("en")
        }

    fun displayName(code: String): String = byCode(code).nativeName
}
