package com.vayunmathur.keyboard.domain

/**
 * The composition engine a layout needs, for scripts where a keystroke is not a character.
 * Resolved to an implementation by the IME service (see `com.vayunmathur.keyboard.platform.ime`).
 */
enum class ComposerKind {
    /** Korean: assemble jamo into syllable blocks. */
    HANGUL,

    /** Chinese: pinyin spelling, simplified-first candidates. */
    PINYIN_SIMPLIFIED,

    /** Chinese: pinyin spelling, traditional-first candidates. */
    PINYIN_TRADITIONAL,

    /** Chinese: bopomofo spelling, traditional-first candidates. */
    BOPOMOFO,

    /** Japanese: romaji to kana. */
    ROMAJI,

    /** Japanese: kana keys, with the voicing marks applied to the kana before them. */
    KANA,

    /** Amharic and Tigrinya: consonant plus vowel makes a Geʽez syllable. */
    ETHIOPIC,
}

/**
 * One selectable letter layout.
 *
 * Only the three letter rows vary between languages — the number row, the symbol pages, the
 * numeric/phone pads and every functional key are shared — so a layout is just character data
 * plus the handful of script-dependent rules the UI and the service have to know about.
 */
data class KeyboardLayout(
    /** Stable id persisted in settings. Never shown, never localized. */
    val id: String,
    /** Endonym: what speakers call the language, shown in the picker and on the space bar. */
    val name: String,
    /** English name and layout family ("Russian · ЙЦУКЕН"), shown under [name] in the picker. */
    val description: String,
    /**
     * Three rows of characters, one key per character; rows need not be the same length.
     * A fourth row is allowed for the layouts that need one (注音, JIS kana), and replaces
     * the digit row rather than stacking on top of it.
     */
    val rows: List<String>,
    /**
     * What each key produces while shift is held, for scripts where shift is a second layer
     * rather than upper case (Devanagari, Thai, Georgian, and Turkish's dotted/dotless i).
     * Positionally 1:1 with [rows]; null means "upper-case the base character".
     */
    val shiftedRows: List<String>? = null,
    /**
     * Long-press alternates keyed by the base (unshifted) character. Holding the key opens
     * them as a row to slide across, so each list is ordered with the character that language
     * needs most first (Polish `a` gives `ą` before `à`) since that one sits under the finger.
     */
    val alternates: Map<Char, String> = emptyMap(),
    /**
     * True only when the bundled English word list describes this layout's language.
     * Suggestions and autocorrect stay off for every other layout rather than proposing
     * English words to someone writing Greek.
     */
    val englishDictionary: Boolean = false,
    /** The composition engine this layout types through, for scripts that need one. */
    val composer: ComposerKind? = null,
    /** What the two punctuation keys beside the space bar produce. */
    val comma: String = ",",
    val period: String = ".",
) {
    /** Key columns in the widest row; shorter rows are centred within this width. */
    val width: Int = rows.maxOf { it.length }

    /** False for scripts with neither case nor a shift layer (Arabic, Hebrew, Persian). */
    val hasShift: Boolean =
        shiftedRows != null || rows.any { row -> row.any { it.uppercaseChar() != it } }

    /**
     * True when shift means "upper case", which is also what makes auto-capitalize
     * meaningful. Composed scripts are excluded even when the keys are Latin: on the romaji
     * layout shift selects katakana, so auto-capitalizing would silently start every
     * sentence in the wrong script.
     */
    val cased: Boolean = shiftedRows == null && hasShift && composer == null

    /** True for the engines that put a candidate list in the strip. */
    val offersCandidates: Boolean = composer == ComposerKind.PINYIN_SIMPLIFIED ||
        composer == ComposerKind.PINYIN_TRADITIONAL ||
        composer == ComposerKind.BOPOMOFO

    /**
     * True when the layout's rows are Latin script (ASCII-forced fields keep these and
     * fall back to English QWERTY for everything else). Detected from the first row
     * rather than the id so transliteration layouts (e.g. romaji) count as Latin.
     */
    val isLatinScript: Boolean =
        rows.firstOrNull()?.any { it in 'A'..'Z' || it in 'a'..'z' } == true

    /** What the key at [col] of [row] produces in the given shift state. */
    fun charAt(row: Int, col: Int, shifted: Boolean): String {
        val base = rows[row][col]
        if (!shifted) return base.toString()
        return (shiftedRows?.getOrNull(row)?.getOrNull(col) ?: base.uppercaseChar()).toString()
    }
}
