package com.vayunmathur.keyboard.ime

/**
 * Hangul (한글) assembly: turns the jamo the Korean layouts emit into syllable blocks.
 *
 * Korean is written in square blocks of two or three jamo, and which block a jamo belongs to
 * is only decided by what comes *next*: type ㄱ ㅏ ㄴ and you have 간, but type ㅏ after that
 * and the ㄴ was never a final at all — it was the initial of 나, leaving 가 behind. That
 * "stealing" rule is why a Korean keyboard cannot just emit characters, and it is what this
 * class exists to do.
 *
 * The state is the three slots of one block (initial / medial / final); everything before the
 * current block has already been committed. Compound vowels (ㅗ + ㅏ = ㅘ) and compound finals
 * (ㄹ + ㄱ = ㄺ) are assembled and, on backspace, taken apart again one jamo at a time — the
 * behaviour of every Korean IME, and the reason backspace is not simply "delete a character".
 */
class HangulComposer : Composer {

    private var initial = -1
    private var medial = -1
    private var final = 0

    override val composing: String get() = render()

    override fun accept(text: String): ComposeResult? {
        if (text.length != 1) return null
        val jamo = text[0]
        return when {
            jamo in MEDIALS -> acceptVowel(MEDIALS.indexOf(jamo))
            jamo in INITIALS -> acceptConsonant(jamo)
            else -> null
        }
    }

    private fun acceptVowel(vowel: Int): ComposeResult {
        // A final consonant in front of a vowel was the next block's initial all along.
        if (final != 0) {
            val (kept, stolen) = splitFinal(final)
            final = kept
            val settled = render()
            initial = INITIALS.indexOf(stolen)
            medial = vowel
            final = 0
            return ComposeResult(settled, render())
        }
        if (medial >= 0) {
            val combined = VOWEL_COMBINATIONS[MEDIALS[medial].toString() + MEDIALS[vowel]]
            if (combined == null) {
                // Two vowels that don't combine: the block is done, the new one is bare.
                val settled = render()
                initial = -1
                medial = vowel
                return ComposeResult(settled, render())
            }
            medial = MEDIALS.indexOf(combined)
            return ComposeResult(composing = render())
        }
        medial = vowel
        return ComposeResult(composing = render())
    }

    private fun acceptConsonant(jamo: Char): ComposeResult {
        // No vowel yet, so this consonant cannot join the block in front of it.
        if (medial < 0) {
            if (initial < 0) {
                initial = INITIALS.indexOf(jamo)
                return ComposeResult(composing = render())
            }
            return startNewBlock(jamo)
        }
        val candidate = when {
            final == 0 -> FINALS.indexOf(jamo).takeIf { it > 0 }
            else -> FINAL_COMBINATIONS[FINALS[final].toString() + jamo]?.let { FINALS.indexOf(it) }
        }
        // ㄸ, ㅃ and ㅉ are never finals, and not every pair of finals combines.
        if (candidate == null || candidate <= 0) return startNewBlock(jamo)
        final = candidate
        return ComposeResult(composing = render())
    }

    private fun startNewBlock(jamo: Char): ComposeResult {
        val settled = render()
        initial = INITIALS.indexOf(jamo)
        medial = -1
        final = 0
        return ComposeResult(settled, render())
    }

    override fun backspace(): ComposeResult? {
        if (!isComposing) return null
        when {
            final != 0 -> {
                val (kept, _) = splitFinal(final)
                final = kept
            }
            medial >= 0 -> {
                val split = VOWEL_SPLITS[MEDIALS[medial]]
                medial = if (split == null) -1 else MEDIALS.indexOf(split)
            }
            else -> initial = -1
        }
        return ComposeResult(composing = render())
    }

    override fun pick(candidate: String): ComposeResult {
        reset()
        return ComposeResult(commit = candidate)
    }

    override fun reset() {
        initial = -1
        medial = -1
        final = 0
    }

    /**
     * The block as text: a composed syllable once it has both an initial and a medial, and
     * otherwise the lone compatibility jamo, which is what a half-typed block looks like.
     */
    private fun render(): String = when {
        initial >= 0 && medial >= 0 ->
            (SYLLABLE_BASE + (initial * MEDIALS.length + medial) * FINALS.length + final)
                .toChar().toString()
        initial >= 0 -> INITIALS[initial].toString()
        // A vowel typed first has no initial to form a syllable with, so a final
        // consonant after it stays a lone jamo beside it. Dropping it from the
        // render would hide the keystroke (and lose the kept half of a compound
        // final entirely when a following vowel steals the other half).
        medial >= 0 ->
            if (final != 0) MEDIALS[medial].toString() + FINALS[final].toString()
            else MEDIALS[medial].toString()
        else -> ""
    }

    /** A final split into what stays behind and the jamo that moves to the next block. */
    private fun splitFinal(index: Int): Pair<Int, Char> {
        val jamo = FINALS[index]
        val parts = FINAL_SPLITS[jamo] ?: return 0 to jamo
        return FINALS.indexOf(parts[0]) to parts[1]
    }

    private companion object {
        const val SYLLABLE_BASE = 0xAC00

        /** The 19 initials, 21 medials and 28 finals of the Hangul syllable block, in order. */
        const val INITIALS = "ㄱㄲㄴㄷㄸㄹㅁㅂㅃㅅㅆㅇㅈㅉㅊㅋㅌㅍㅎ"
        const val MEDIALS = "ㅏㅐㅑㅒㅓㅔㅕㅖㅗㅘㅙㅚㅛㅜㅝㅞㅟㅠㅡㅢㅣ"
        const val FINALS = " ㄱㄲㄳㄴㄵㄶㄷㄹㄺㄻㄼㄽㄾㄿㅀㅁㅂㅄㅅㅆㅇㅈㅊㅋㅌㅍㅎ"

        val VOWEL_COMBINATIONS = mapOf(
            "ㅗㅏ" to 'ㅘ', "ㅗㅐ" to 'ㅙ', "ㅗㅣ" to 'ㅚ',
            "ㅜㅓ" to 'ㅝ', "ㅜㅔ" to 'ㅞ', "ㅜㅣ" to 'ㅟ',
            "ㅡㅣ" to 'ㅢ',
        )
        val VOWEL_SPLITS = VOWEL_COMBINATIONS.entries.associate { it.value to it.key[0] }

        val FINAL_COMBINATIONS = mapOf(
            "ㄱㅅ" to 'ㄳ', "ㄴㅈ" to 'ㄵ', "ㄴㅎ" to 'ㄶ',
            "ㄹㄱ" to 'ㄺ', "ㄹㅁ" to 'ㄻ', "ㄹㅂ" to 'ㄼ', "ㄹㅅ" to 'ㄽ',
            "ㄹㅌ" to 'ㄾ', "ㄹㅍ" to 'ㄿ', "ㄹㅎ" to 'ㅀ',
            "ㅂㅅ" to 'ㅄ',
        )
        val FINAL_SPLITS = FINAL_COMBINATIONS.entries.associate { it.value to it.key }
    }
}
