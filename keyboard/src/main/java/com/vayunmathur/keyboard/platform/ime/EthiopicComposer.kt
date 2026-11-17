package com.vayunmathur.keyboard.platform.ime

/**
 * Ethiopic (Geʽez) syllable assembly, for Amharic and Tigrinya.
 *
 * The script has around 300 syllables, far too many to be keys, but they are perfectly
 * regular: every consonant occupies eight consecutive codepoints, one per vowel, in the same
 * order (ä u i a e ə o). So the keyboard offers the ~34 consonants in their ä-form plus the
 * seven forms of አ as vowel keys, and typing a consonant then a vowel walks that many
 * codepoints along the consonant's row — ከ then ኡ is ኩ.
 *
 * A vowel always re-vowels the syllable in front of it rather than being appended, so a wrong
 * vowel is corrected by pressing the right one instead of by deleting.
 */
class EthiopicComposer : Composer {

    private var pending = ""

    override val composing: String get() = pending

    override fun accept(text: String): ComposeResult? {
        if (text.length != 1) return null
        val typed = text[0]
        if (typed !in FIRST..LAST) return null
        val vowel = VOWEL_KEYS.indexOf(typed)
        val base = pending.firstOrNull()?.let { it - ((it - FIRST) % FORMS) }
        // Vowel 0 is the ä-form, which is the consonant key itself; pressing አ after a
        // consonant therefore starts a new syllable rather than doing nothing.
        if (vowel > 0 && base != null) {
            pending = (base + vowel).toString()
            return ComposeResult(composing = pending)
        }
        val settled = pending
        pending = typed.toString()
        return ComposeResult(settled, pending)
    }

    override fun backspace(): ComposeResult? {
        if (pending.isEmpty()) return null
        pending = ""
        return ComposeResult()
    }

    override fun pick(candidate: String): ComposeResult {
        reset()
        return ComposeResult(commit = candidate)
    }

    override fun reset() {
        pending = ""
    }

    private companion object {
        const val FIRST = 'ሀ'
        const val LAST = '፿'

        /** Codepoints per consonant: seven vowels plus one labialized form. */
        const val FORMS = 8

        /** The seven forms of አ, which are both letters and this layout's vowel keys. */
        const val VOWEL_KEYS = "አኡኢኣኤእኦ"
    }
}
