package com.vayunmathur.keyboard.platform.ime

import com.vayunmathur.keyboard.data.PinyinDictionary

/**
 * How a Chinese layout spells a syllable before it becomes a character.
 *
 * 拼音 types the syllable in Latin letters and 注音 types it in bopomofo, but both are asking
 * the same question of the same index, so only the spelling differs.
 */
interface SpellingScheme {
    /** True for keys that extend the spelling. */
    fun accepts(c: Char): Boolean

    /** True for keys that end a syllable and take the best candidate (注音's tone marks). */
    fun selects(c: Char): Boolean = false

    /** The pinyin the buffer spells, or null while it spells nothing yet. */
    fun toPinyin(buffer: String): String?

    /** Singleton holding pinyin state and operations. */
    object Pinyin : SpellingScheme {
        override fun accepts(c: Char) = c in 'a'..'z' || c in 'A'..'Z'
        override fun toPinyin(buffer: String) = buffer.lowercase()
    }

    /**
     * Bopomofo, resolved through the generated 注音 → pinyin table so both schemes share one
     * character index. Tone marks are selectors rather than spelling: the index is toneless.
     */
    class Bopomofo(private val spellings: Map<String, String>) : SpellingScheme {
        override fun accepts(c: Char) = c in 'ㄅ'..'ㄩ'
        override fun selects(c: Char) = c in TONES
        override fun toPinyin(buffer: String) = spellings[buffer]

        private companion object {
            const val TONES = "ˉˊˇˋ˙"
        }
    }
}

/**
 * Chinese input: collects a syllable's spelling, offers the characters it can be, and commits
 * the one that is chosen.
 *
 * Only one syllable is in flight at a time. Full-sentence pinyin — where the engine segments
 * `womenshi` and picks 我们是 from context — needs a language model this keyboard does not
 * ship; per-syllable candidates are what it can do honestly, and every syllable typed is one
 * the user confirms.
 */
class HanComposer(
    private val dictionary: PinyinDictionary,
    private val spelling: SpellingScheme,
) : Composer {

    private val buffer = StringBuilder()

    override val composing: String get() = buffer.toString()

    override val candidates: List<String>
        get() {
            if (buffer.isEmpty()) return emptyList()
            val pinyin = spelling.toPinyin(buffer.toString()) ?: return emptyList()
            return dictionary.candidates(pinyin)
        }

    override fun accept(text: String): ComposeResult? {
        if (text.length != 1) return null
        val c = text[0]
        if (spelling.selects(c)) return if (buffer.isEmpty()) null else pickBest()
        if (!spelling.accepts(c)) return null
        buffer.append(c)
        return ComposeResult(composing = composing)
    }

    override fun backspace(): ComposeResult? {
        if (buffer.isEmpty()) return null
        buffer.setLength(buffer.length - 1)
        return ComposeResult(composing = composing)
    }

    /** Space takes the best candidate, as it does in every CJK IME. */
    override fun space(): ComposeResult? = if (buffer.isEmpty()) null else pickBest()

    /** With no candidate to offer, the spelling itself is committed rather than lost. */
    private fun pickBest(): ComposeResult = pick(candidates.firstOrNull() ?: composing)

    override fun pick(candidate: String): ComposeResult {
        reset()
        return ComposeResult(commit = candidate)
    }

    override fun reset() {
        buffer.setLength(0)
    }
}
