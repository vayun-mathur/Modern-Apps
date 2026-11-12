package com.vayunmathur.keyboard.util

import java.text.Normalizer

/** Which page of keys is currently shown. */
enum class KeyboardPage { LETTERS, SYMBOLS, MORE_SYMBOLS, NUMERIC, PHONE, PHONE_SYMBOLS, EMOJI, CLIPBOARD }

/** Shift/caps state for the letter page. */
enum class ShiftState { OFF, SHIFTED, CAPS_LOCK }

/**
 * Static key layouts. Each page is a list of character rows (one [String] per row, one
 * key per character). Functional keys (shift, backspace, space, enter, page toggles) are
 * added by the UI around these character rows so the data here stays simple.
 */
object Layouts {
    /**
     * QWERTY letters (always lowercase here; the UI upper-cases per shift state). The letter
     * rows the keyboard actually draws come from the user's chosen [KeyboardLayout] — this is
     * the English one, shared by the other layouts that are plain QWERTY.
     */
    val LETTER_ROWS = listOf(
        "qwertyuiop",
        "asdfghjkl",
        "zxcvbnm",
    )

    val SYMBOL_ROWS = listOf(
        "1234567890",
        "@#\$_&-+()/",
        "*\"':;!?",
    )

    val MORE_SYMBOL_ROWS = listOf(
        "~`|•√π÷×¶",
        "£¢€¥^°={}",
        "\\%©®™✓[]",
    )

    /** Compact numeric pad used for number/phone input types. */
    val NUMERIC_ROWS = listOf(
        "123",
        "456",
        "789",
        "*0#",
    )

    /**
     * Long-press alternates shared by the Latin layouts: roughly Gboard's Latin set, so a
     * letter reaches every accent that letter takes in a European language. An empty string
     * = no alternate. Individual layouts override the keys their language cares about, so
     * the Polish `a` gives `ą` rather than `à` (see [KeyboardLayouts]).
     *
     * Each string stays at 7 characters or fewer. The popup gives every entry 44dp and puts
     * the key's own character in front of them, so seven alternates is already 352dp — the
     * full width of a small phone.
     */
    val LATIN_ALTERNATES: Map<Char, String> = mapOf(
        'q' to "", 'w' to "ŵ", 'e' to "éèêëēėę", 'r' to "ř", 't' to "ťţ",
        'y' to "ÿý", 'u' to "ûüùúū", 'i' to "ìįíïîī", 'o' to "ôöòóœøõ", 'p' to "",
        'a' to "àáâäæãå", 's' to "śšß", 'd' to "ď", 'f' to "", 'g' to "ğ",
        'h' to "", 'j' to "", 'k' to "ķ", 'l' to "ł",
        'z' to "žźż", 'x' to "", 'c' to "çćč", 'v' to "", 'b' to "", 'n' to "ñńň", 'm' to "",
    )

    /**
     * Long-press alternates for the punctuation and symbol keys.
     *
     * Deliberately separate from [LATIN_ALTERNATES] rather than merged into it: a layout's
     * alternate map is keyed by characters that layout's letter rows actually contain (a key
     * that isn't on the keyboard can never be long-pressed), and `!`, `?` and friends live on
     * the symbol pages instead.
     */
    val SYMBOL_ALTERNATES: Map<Char, String> = mapOf(
        '!' to "¡", '?' to "¿",
        '(' to "<[{", ')' to ">]}",
        '-' to "–—•", '_' to "–—",
        '"' to "“”«»", '\'' to "‘’",
        '$' to "€£¥¢",
        '.' to "…", ',' to ";", ';' to ":",
        '/' to "\\", '%' to "‰", '+' to "±", '=' to "≠≈", '*' to "†‡",
        '&' to "§", '#' to "№",
    )

    /**
     * Long-press alternates for the digits: superscripts and every precomposed vulgar
     * fraction Unicode has (#555), each filed under its numerator in denominator order.
     *
     * The exception is the 1 key, which would need ten fractions and blow the seven-entry
     * cap the popup can show on a small phone (see KeyboardLayoutTest): it keeps the six
     * commonest, and the rarest three sit on their denominator's key instead — ⅐ on 7, ⅑ on
     * 9, ⅒ on 0 — where the digit already on the key is the hint.
     */
    val DIGIT_ALTERNATES: Map<Char, String> = mapOf(
        '1' to "¹½⅓¼⅕⅙⅛", '2' to "²⅔⅖", '3' to "³¾⅗⅜", '4' to "⁴⅘", '5' to "⁵⅚⅝",
        '6' to "⁶", '7' to "⁷⅞⅐", '8' to "⁸", '9' to "⁹⅑", '0' to "⁰∅ⁿ⅒",
    )

    /** The alternates for a symbol-page or number-row key, or "" if it has none. */
    fun alternatesFor(c: Char): String = SYMBOL_ALTERNATES[c] ?: DIGIT_ALTERNATES[c] ?: ""

    /**
     * The alternate worth printing in the corner of a key, or null if there is nothing
     * distinctive to show.
     *
     * Skipped: anything already drawn elsewhere on the key ([skip]), and any alternate that
     * is only a typographic restyling of [label] — `e` -> `é`, `1` -> `¹`. Printing those
     * would put a mark on every vowel and a smaller copy of every digit beside itself, so
     * those keys fall back to a plain dot.
     */
    fun alternatePreview(alternates: String, label: String, skip: String? = null): String? {
        val base = label.singleOrNull()
        return alternates.firstOrNull { alt ->
            alt.toString() != skip && (base == null || !isRestylingOf(alt, base))
        }?.toString()
    }

    /** True when [alt] is [base] wearing a diacritic (`é`) or a different style (`¹`). */
    private fun isRestylingOf(alt: Char, base: Char): Boolean {
        val text = alt.toString()
        // NFKD folds away superscripts and the like entirely; NFD leaves the combining
        // marks behind, which is what distinguishes an accent from a separate letter.
        if (Normalizer.normalize(text, Normalizer.Form.NFKD).equals(base.toString(), true)) return true
        val decomposed = Normalizer.normalize(text, Normalizer.Form.NFD)
        return decomposed.length > 1 && decomposed[0].equals(base, ignoreCase = true)
    }

    /** Rows for the current character page, or null for pages the UI renders specially. */
    fun rowsFor(page: KeyboardPage): List<String>? = when (page) {
        KeyboardPage.LETTERS -> LETTER_ROWS
        KeyboardPage.SYMBOLS -> SYMBOL_ROWS
        KeyboardPage.MORE_SYMBOLS -> MORE_SYMBOL_ROWS
        KeyboardPage.NUMERIC -> NUMERIC_ROWS
        // Phone/emoji/clipboard pages are laid out directly by the UI (special keys, grids, lists).
        KeyboardPage.PHONE, KeyboardPage.PHONE_SYMBOLS,
        KeyboardPage.EMOJI, KeyboardPage.CLIPBOARD,
        -> null
    }
}
