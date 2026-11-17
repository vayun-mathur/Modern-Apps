package com.vayunmathur.keyboard.domain

/** Latin-family layouts: Latin first in picker order. Includes the "More Latin" tail. */
internal val latinKeyboardLayouts: List<KeyboardLayout> by lazy {
    listOf(
        KeyboardLayout(
            id = "en_qwerty",
            name = "English",
            description = "English · QWERTY",
            rows = Layouts.LETTER_ROWS,
            alternates = Layouts.LATIN_ALTERNATES,
            englishDictionary = true,
        ),
        KeyboardLayout(
            id = "en_dvorak",
            name = "English (Dvorak)",
            description = "English · Dvorak",
            rows = listOf("',.pyfgcrl", "aoeuidhtns", ";qjkxbmwvz"),
            alternates = Layouts.LATIN_ALTERNATES,
            englishDictionary = true,
        ),
        KeyboardLayout(
            id = "en_colemak",
            name = "English (Colemak)",
            description = "English · Colemak",
            rows = listOf("qwfpgjluy;", "arstdhneio", "zxcvbkm,./"),
            alternates = Layouts.LATIN_ALTERNATES,
            englishDictionary = true,
        ),
        KeyboardLayout(
            id = "en_workman",
            name = "English (Workman)",
            description = "English · Workman",
            rows = listOf("qdrwbjfup;", "ashtgyneoi", "zxmcvkl,./"),
            alternates = Layouts.LATIN_ALTERNATES,
            englishDictionary = true,
        ),
        KeyboardLayout(
            id = "de_qwertz",
            name = "Deutsch",
            description = "German · QWERTZ",
            rows = listOf("qwertzuiopü", "asdfghjklöä", "yxcvbnm"),
            alternates = latinExtra('s' to "ß", 'e' to "éèêë", 'a' to "äàáâã", 'o' to "öòóôø", 'u' to "üùúû"),
        ),
        KeyboardLayout(
            id = "fr_azerty",
            name = "Français",
            description = "French · AZERTY",
            rows = listOf("azertyuiop", "qsdfghjklm", "wxcvbn"),
            alternates = latinExtra(
                'e' to "éèêë", 'a' to "àâáäã", 'u' to "ùûúü", 'i' to "îïíì",
                'o' to "ôœòóö", 'c' to "çć", 'y' to "ÿ",
            ),
        ),
        KeyboardLayout(
            id = "es_qwerty",
            name = "Español",
            description = "Spanish · QWERTY",
            rows = listOf("qwertyuiop", "asdfghjklñ", "zxcvbnm"),
            alternates = latinExtra(
                'a' to "áàâä", 'e' to "éèêë", 'i' to "íìîï", 'o' to "óòôö", 'u' to "úüùû",
            ),
        ),
        KeyboardLayout(
            id = "pt_qwerty",
            name = "Português",
            description = "Portuguese · QWERTY",
            rows = listOf("qwertyuiop", "asdfghjklç", "zxcvbnm"),
            alternates = latinExtra(
                'a' to "ãáàâä", 'e' to "éêèë", 'i' to "íìîï", 'o' to "óõôòö", 'u' to "úùûü",
            ),
        ),
        KeyboardLayout(
            id = "it_qwerty",
            name = "Italiano",
            description = "Italian · QWERTY",
            rows = Layouts.LETTER_ROWS,
            alternates = latinExtra(
                'a' to "àáâä", 'e' to "èéêë", 'i' to "ìíîï", 'o' to "òóôö", 'u' to "ùúûü",
            ),
        ),
        KeyboardLayout(
            id = "sv_qwerty",
            name = "Svenska",
            description = "Swedish / Finnish · QWERTY",
            rows = listOf("qwertyuiopå", "asdfghjklöä", "zxcvbnm"),
            alternates = latinExtra('a' to "åäàá", 'o' to "öø", 'e' to "é", 's' to "š", 'z' to "ž"),
        ),
        KeyboardLayout(
            id = "da_qwerty",
            name = "Dansk / Norsk",
            description = "Danish / Norwegian · QWERTY",
            rows = listOf("qwertyuiopå", "asdfghjklæø", "zxcvbnm"),
            alternates = latinExtra('a' to "æåàá", 'o' to "øöô", 'e' to "éë"),
        ),
        KeyboardLayout(
            id = "pl_qwerty",
            name = "Polski",
            description = "Polish · QWERTY",
            rows = Layouts.LETTER_ROWS,
            alternates = latinExtra(
                'a' to "ąàáâä", 'c' to "ćç", 'e' to "ęéèê", 'l' to "ł", 'n' to "ńñ",
                'o' to "óòôö", 's' to "ś", 'z' to "żź",
            ),
        ),
        KeyboardLayout(
            id = "cs_qwertz",
            name = "Čeština",
            description = "Czech · QWERTZ",
            rows = listOf("qwertzuiop", "asdfghjkl", "yxcvbnm"),
            alternates = latinExtra(
                'a' to "á", 'c' to "č", 'd' to "ď", 'e' to "éě", 'i' to "í", 'n' to "ň",
                'o' to "ó", 'r' to "ř", 's' to "š", 't' to "ť", 'u' to "úů", 'y' to "ý", 'z' to "ž",
            ),
        ),
        KeyboardLayout(
            id = "sk_qwertz",
            name = "Slovenčina",
            description = "Slovak · QWERTZ",
            rows = listOf("qwertzuiop", "asdfghjkl", "yxcvbnm"),
            alternates = latinExtra(
                'a' to "áä", 'c' to "č", 'd' to "ď", 'e' to "é", 'i' to "í", 'l' to "ľĺ",
                'n' to "ň", 'o' to "óô", 'r' to "ŕ", 's' to "š", 't' to "ť", 'u' to "ú",
                'y' to "ý", 'z' to "ž",
            ),
        ),
        KeyboardLayout(
            id = "hu_qwertz",
            name = "Magyar",
            description = "Hungarian · QWERTZ",
            rows = listOf("qwertzuiop", "asdfghjkl", "yxcvbnm"),
            alternates = latinExtra('a' to "á", 'e' to "é", 'i' to "í", 'o' to "óöő", 'u' to "úüű"),
        ),
        KeyboardLayout(
            id = "ro_qwerty",
            name = "Română",
            description = "Romanian · QWERTY",
            rows = Layouts.LETTER_ROWS,
            alternates = latinExtra('a' to "ăâà", 'i' to "î", 's' to "ș", 't' to "ț"),
        ),
        KeyboardLayout(
            id = "hr_qwertz",
            name = "Hrvatski / Srpski",
            description = "Croatian / Serbian (Latin) · QWERTZ",
            rows = listOf("qwertzuiopšđ", "asdfghjklčćž", "yxcvbnm"),
            alternates = latinExtra('c' to "čć", 's' to "š", 'z' to "ž", 'd' to "đ"),
        ),
        KeyboardLayout(
            id = "tr_q",
            name = "Türkçe (Q)",
            description = "Turkish · Q-klavye",
            rows = listOf("qwertyuıopğü", "asdfghjklşi", "zxcvbnmöç"),
            shiftedRows = listOf("QWERTYUIOPĞÜ", "ASDFGHJKLŞİ", "ZXCVBNMÖÇ"),
            alternates = latinExtra('a' to "â", 'i' to "î", 'u' to "û", 'o' to "ö", 'g' to "ğ"),
        ),
        KeyboardLayout(
            id = "tr_f",
            name = "Türkçe (F)",
            description = "Turkish · F-klavye",
            rows = listOf("fgğıodrnhpqw", "uieaütkmlyşx", "jövcçzsb"),
            shiftedRows = listOf("FGĞIODRNHPQW", "UİEAÜTKMLYŞX", "JÖVCÇZSB"),
            alternates = latinExtra('a' to "â", 'i' to "î", 'u' to "û"),
        ),
        KeyboardLayout(
            id = "az_qwerty",
            name = "Azərbaycan",
            description = "Azerbaijani · QWERTY",
            rows = listOf("qüertyuiopöğ", "asdfghjklıə", "zxcvbnmçş"),
            shiftedRows = listOf("QÜERTYUİOPÖĞ", "ASDFGHJKLIƏ", "ZXCVBNMÇŞ"),
            alternates = latinExtra(
                listOf("qüertyuiopöğ", "asdfghjklıə", "zxcvbnmçş"),
                'a' to "â", 'i' to "ı", 'u' to "ü", 'o' to "ö", 'e' to "ə",
            ),
        ),
        KeyboardLayout(
            id = "et_qwerty",
            name = "Eesti",
            description = "Estonian · QWERTY",
            rows = listOf("qwertyuiopüõ", "asdfghjklöä", "zxcvbnm"),
            alternates = latinExtra('s' to "š", 'z' to "ž", 'o' to "õö", 'a' to "ä"),
        ),
        KeyboardLayout(
            id = "lt_qwerty",
            name = "Lietuvių",
            description = "Lithuanian · QWERTY",
            rows = Layouts.LETTER_ROWS,
            alternates = latinExtra(
                'a' to "ą", 'c' to "č", 'e' to "ęė", 'i' to "įy", 's' to "š",
                'u' to "ųū", 'z' to "ž",
            ),
        ),
        KeyboardLayout(
            id = "lv_qwerty",
            name = "Latviešu",
            description = "Latvian · QWERTY",
            rows = Layouts.LETTER_ROWS,
            alternates = latinExtra(
                'a' to "ā", 'c' to "č", 'e' to "ē", 'g' to "ģ", 'i' to "ī", 'k' to "ķ",
                'l' to "ļ", 'n' to "ņ", 's' to "š", 'u' to "ū", 'z' to "ž",
            ),
        ),
        KeyboardLayout(
            id = "is_qwerty",
            name = "Íslenska",
            description = "Icelandic · QWERTY",
            rows = Layouts.LETTER_ROWS,
            alternates = latinExtra(
                'a' to "áæ", 'd' to "ð", 'e' to "é", 'i' to "í", 'o' to "óö",
                't' to "þ", 'u' to "ú", 'y' to "ý",
            ),
        ),
        KeyboardLayout(
            id = "ca_qwerty",
            name = "Català",
            description = "Catalan · QWERTY",
            rows = listOf("qwertyuiop", "asdfghjklç", "zxcvbnm"),
            alternates = latinExtra(
                'a' to "àá", 'e' to "èé", 'i' to "íï", 'o' to "òó", 'u' to "úü", 'c' to "ç",
            ),
        ),
        KeyboardLayout(
            id = "sq_qwerty",
            name = "Shqip",
            description = "Albanian · QWERTY",
            rows = Layouts.LETTER_ROWS,
            alternates = latinExtra('e' to "ë", 'c' to "ç"),
        ),
        KeyboardLayout(
            id = "mt_qwerty",
            name = "Malti",
            description = "Maltese · QWERTY",
            rows = Layouts.LETTER_ROWS,
            alternates = latinExtra('c' to "ċ", 'g' to "ġ", 'h' to "ħ", 'z' to "ż", 'a' to "à"),
        ),
        KeyboardLayout(
            id = "cy_qwerty",
            name = "Cymraeg",
            description = "Welsh · QWERTY",
            rows = Layouts.LETTER_ROWS,
            alternates = latinExtra(
                'a' to "âáàä", 'e' to "êéèë", 'i' to "îíìï", 'o' to "ôóòö",
                'u' to "ûúùü", 'w' to "ŵẃẁẅ", 'y' to "ŷýỳÿ",
            ),
        ),
        KeyboardLayout(
            id = "ga_qwerty",
            name = "Gaeilge",
            description = "Irish · QWERTY",
            rows = Layouts.LETTER_ROWS,
            alternates = latinExtra('a' to "á", 'e' to "é", 'i' to "í", 'o' to "ó", 'u' to "ú"),
        ),
        KeyboardLayout(
            id = "eo_qwerty",
            name = "Esperanto",
            description = "Esperanto · QWERTY",
            rows = Layouts.LETTER_ROWS,
            alternates = latinExtra(
                'c' to "ĉ", 'g' to "ĝ", 'h' to "ĥ", 'j' to "ĵ", 's' to "ŝ", 'u' to "ŭ",
            ),
        ),
        KeyboardLayout(
            id = "id_qwerty",
            name = "Indonesia / Melayu",
            description = "Indonesian / Malay · QWERTY",
            rows = Layouts.LETTER_ROWS,
            alternates = Layouts.LATIN_ALTERNATES,
        ),
        KeyboardLayout(
            id = "fil_qwerty",
            name = "Filipino",
            description = "Filipino · QWERTY",
            rows = Layouts.LETTER_ROWS,
            alternates = latinExtra('n' to "ñ", 'a' to "áàä", 'e' to "éè", 'o' to "óò"),
        ),
        KeyboardLayout(
            id = "sw_qwerty",
            name = "Kiswahili",
            description = "Swahili · QWERTY",
            rows = Layouts.LETTER_ROWS,
            alternates = Layouts.LATIN_ALTERNATES,
        ),
        KeyboardLayout(
            id = "af_qwerty",
            name = "Afrikaans",
            description = "Afrikaans · QWERTY",
            rows = Layouts.LETTER_ROWS,
            alternates = latinExtra('e' to "êëé", 'o' to "ôö", 'i' to "îï", 'u' to "ûü", 'a' to "áà"),
        ),
        KeyboardLayout(
            id = "ha_qwerty",
            name = "Hausa",
            description = "Hausa · QWERTY",
            rows = Layouts.LETTER_ROWS,
            alternates = latinExtra('b' to "ɓ", 'd' to "ɗ", 'k' to "ƙ", 'y' to "ƴ"),
        ),
        KeyboardLayout(
            id = "yo_qwerty",
            name = "Yorùbá",
            description = "Yoruba · QWERTY",
            rows = Layouts.LETTER_ROWS,
            alternates = latinExtra(
                'a' to "àá", 'e' to "ẹèé", 'i' to "ìí", 'o' to "ọòó", 'u' to "ùú",
                's' to "ṣ", 'n' to "ń",
            ),
        ),
        KeyboardLayout(
            id = "ig_qwerty",
            name = "Igbo",
            description = "Igbo · QWERTY",
            rows = Layouts.LETTER_ROWS,
            alternates = latinExtra('i' to "ị", 'o' to "ọ", 'u' to "ụ", 'n' to "ṅ", 'a' to "á"),
        ),
        KeyboardLayout(
            id = "vi_qwerty",
            name = "Tiếng Việt",
            description = "Vietnamese · QWERTY",
            rows = Layouts.LETTER_ROWS,
            alternates = latinExtra(
                'a' to "ăâáàảãạ", 'd' to "đ", 'e' to "êéèẻẽẹ", 'i' to "íìỉĩị",
                'o' to "ôơóòỏõọ", 'u' to "ưúùủũụ", 'y' to "ýỳỷỹỵ",
            ),
        ),
        // --- More Latin languages. Plain QWERTY plus the letters that language adds. ---
        KeyboardLayout(
            id = "sl_qwertz",
            name = "Slovenščina",
            description = "Slovenian · QWERTZ",
            rows = listOf("qwertzuiop", "asdfghjkl", "yxcvbnm"),
            alternates = latinExtra('c' to "č", 's' to "š", 'z' to "ž"),
        ),
        KeyboardLayout(
            id = "eu_qwerty",
            name = "Euskara",
            description = "Basque · QWERTY",
            rows = listOf("qwertyuiop", "asdfghjklñ", "zxcvbnm"),
            alternates = latinExtra('u' to "ü", 'a' to "á", 'e' to "é", 'i' to "í", 'o' to "ó"),
        ),
        KeyboardLayout(
            id = "gl_qwerty",
            name = "Galego",
            description = "Galician · QWERTY",
            rows = listOf("qwertyuiop", "asdfghjklñ", "zxcvbnm"),
            alternates = latinExtra('a' to "á", 'e' to "é", 'i' to "í", 'o' to "ó", 'u' to "úü"),
        ),
        KeyboardLayout(
            id = "lb_qwertz",
            name = "Lëtzebuergesch",
            description = "Luxembourgish · QWERTZ",
            rows = listOf("qwertzuiopü", "asdfghjklöä", "yxcvbnm"),
            alternates = latinExtra('e' to "ëé", 'a' to "äà", 'o' to "ö", 'u' to "ü"),
        ),
        KeyboardLayout(
            id = "gd_qwerty",
            name = "Gàidhlig",
            description = "Scottish Gaelic · QWERTY",
            rows = Layouts.LETTER_ROWS,
            alternates = latinExtra('a' to "à", 'e' to "è", 'i' to "ì", 'o' to "ò", 'u' to "ù"),
        ),
        KeyboardLayout(
            id = "br_qwerty",
            name = "Brezhoneg",
            description = "Breton · QWERTY",
            rows = Layouts.LETTER_ROWS,
            alternates = latinExtra('n' to "ñ", 'e' to "êé", 'u' to "ùü", 'a' to "â"),
        ),
        KeyboardLayout(
            id = "so_qwerty",
            name = "Soomaali",
            description = "Somali · QWERTY",
            rows = Layouts.LETTER_ROWS,
            alternates = Layouts.LATIN_ALTERNATES,
        ),
        KeyboardLayout(
            id = "zu_qwerty",
            name = "isiZulu / isiXhosa",
            description = "Zulu / Xhosa · QWERTY",
            rows = Layouts.LETTER_ROWS,
            alternates = Layouts.LATIN_ALTERNATES,
        ),
        KeyboardLayout(
            id = "uz_qwerty",
            name = "Oʻzbekcha",
            description = "Uzbek · QWERTY",
            rows = Layouts.LETTER_ROWS,
            alternates = latinExtra('o' to "ʻ", 'g' to "ʻ", 'c' to "ç", 's' to "ş"),
        ),
        KeyboardLayout(
            id = "tk_qwerty",
            name = "Türkmençe",
            description = "Turkmen · QWERTY",
            rows = Layouts.LETTER_ROWS,
            alternates = latinExtra(
                'a' to "ä", 'z' to "ž", 'n' to "ň", 'o' to "ö", 's' to "ş",
                'u' to "ü", 'y' to "ý", 'c' to "ç",
            ),
        ),
        KeyboardLayout(
            id = "ku_qwerty",
            name = "Kurdî",
            description = "Kurdish (Kurmanji) · QWERTY",
            rows = Layouts.LETTER_ROWS,
            alternates = latinExtra('c' to "ç", 'e' to "ê", 'i' to "î", 's' to "ş", 'u' to "û"),
        ),
    )
}

/** Latin alternates plus this language's own, which take precedence. */
private fun latinExtra(vararg extra: Pair<Char, String>): Map<Char, String> =
    Layouts.LATIN_ALTERNATES + extra.toMap()

/**
 * Same, but only for keys the layout actually has. Long-pressing a key that isn't on the
 * layout can never fire (see the layout tests), so unfiltered defaults would add dead
 * entries — Azerbaijani has no `w`, for example, and no `i` either (it has `ı`).
 */
private fun latinExtra(rows: List<String>, vararg extra: Pair<Char, String>): Map<Char, String> {
    val chars = rows.joinToString("").toSet()
    return (Layouts.LATIN_ALTERNATES + extra.toMap()).filterKeys { it in chars }
}
