package com.vayunmathur.keyboard.ime

import com.vayunmathur.keyboard.util.PinyinDictionary
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The composition engines, driven the way [com.vayunmathur.keyboard.ime.KeyboardService] drives
 * them: feed keys, apply each [ComposeResult] to a model of the text field, and check what the
 * field ends up holding.
 *
 * These are the tests that matter most in the keyboard. A layout can be eyeballed — it is a row
 * of characters — but "does ㄴ belong to the syllable before it or the one after it" is a rule
 * with state behind it, and getting it wrong produces text that is wrong in a way the person
 * typing cannot fix.
 */
class ComposerTest {

    /** A text field: commits append, and the composing region is replaced in place. */
    private class Field(private val composer: Composer) {
        private val committed = StringBuilder()
        private var composing = ""

        val text: String get() = committed.toString() + composing

        fun type(keys: String): Field {
            for (key in keys) {
                val result = composer.accept(key.toString())
                if (result == null) {
                    finish()
                    committed.append(key)
                } else {
                    apply(result)
                }
            }
            return this
        }

        fun backspace(times: Int = 1): Field {
            repeat(times) { composer.backspace()?.let { apply(it) } }
            return this
        }

        fun finish(): Field {
            val result = composer.finish()
            if (result != null) apply(result)
            committed.append(composing)
            composing = ""
            composer.reset()
            return this
        }

        private fun apply(result: ComposeResult) {
            committed.append(result.commit)
            composing = result.composing
        }
    }

    private fun hangul() = Field(HangulComposer())

    // ---- Hangul ----

    @Test
    fun `assembles syllable blocks`() {
        assertEquals("한", hangul().type("ㅎㅏㄴ").finish().text)
        assertEquals("한글", hangul().type("ㅎㅏㄴㄱㅡㄹ").finish().text)
        assertEquals("안녕하세요", hangul().type("ㅇㅏㄴㄴㅕㅇㅎㅏㅅㅔㅇㅛ").finish().text)
    }

    /** The rule that makes Hangul need an engine at all: 가나, never 간ㅏ or 갛나. */
    @Test
    fun `a vowel steals the previous block's final consonant`() {
        assertEquals("가나", hangul().type("ㄱㅏㄴㅏ").finish().text)
        assertEquals("앉아", hangul().type("ㅇㅏㄴㅈㅇㅏ").finish().text)
    }

    @Test
    fun `combines compound vowels and compound finals`() {
        assertEquals("과", hangul().type("ㄱㅗㅏ").finish().text)
        assertEquals("의", hangul().type("ㅇㅡㅣ").finish().text)
        assertEquals("없다", hangul().type("ㅇㅓㅂㅅㄷㅏ").finish().text)
        assertEquals("뷁", hangul().type("ㅂㅜㅔㄹㄱ").finish().text)
    }

    @Test
    fun `shows half-typed blocks as lone jamo`() {
        assertEquals("ㄱ", hangul().type("ㄱ").finish().text)
        assertEquals("ㄱㄴ", hangul().type("ㄱㄴ").finish().text)
        assertEquals("아이", hangul().type("ㅇㅏㅇㅣ").finish().text)
    }

    /** A final after a lone vowel has no syllable to live in, but it is still typed text. */
    @Test
    fun `a final consonant after a lone vowel stays visible`() {
        assertEquals("ㅏㄱ", hangul().type("ㅏㄱ").finish().text)
        assertEquals("ㅏ", hangul().type("ㅏㄱ").backspace().finish().text)
        // The kept half of a compound final survives when a vowel steals the rest.
        assertEquals("ㅏㄱ사", hangul().type("ㅏㄱㅅㅏ").finish().text)
    }

    /** Backspace takes the block apart jamo by jamo instead of deleting the whole syllable. */
    @Test
    fun `backspace decomposes`() {
        assertEquals("하", hangul().type("ㅎㅏㄴ").backspace().finish().text)
        assertEquals("갑", hangul().type("ㄱㅏㅂㅅ").backspace().finish().text)
        assertEquals("고", hangul().type("ㄱㅗㅏ").backspace().finish().text)
        assertEquals("", hangul().type("ㅎㅏㄴ").backspace(3).finish().text)
    }

    // ---- Japanese ----

    private fun romaji() = Field(RomajiComposer())

    @Test
    fun `romaji becomes kana`() {
        assertEquals("こんにちは", romaji().type("konnichiha").finish().text)
        assertEquals("きょう", romaji().type("kyou").finish().text)
        assertEquals("しゃしん", romaji().type("shashin").finish().text)
        assertEquals("にほんご", romaji().type("nihongo").finish().text)
        assertEquals("ふじさん", romaji().type("fujisan").finish().text)
    }

    /** A doubled consonant is っ, and a lone trailing `n` is ん once nothing follows it. */
    @Test
    fun `romaji handles the sokuon and a trailing n`() {
        assertEquals("ずっと", romaji().type("zutto").finish().text)
        assertEquals("ほん", romaji().type("hon").finish().text)
        assertEquals("つづく", romaji().type("tsuduku").finish().text)
    }

    @Test
    fun `shift types katakana`() {
        assertEquals("カタカナ", romaji().type("KATAKANA").finish().text)
    }

    @Test
    fun `kana keys take the voicing marks`() {
        assertEquals("が", Field(KanaKeyComposer()).type("か゛").finish().text)
        assertEquals("ぱん", Field(KanaKeyComposer()).type("は゜ん").finish().text)
        assertEquals("かた", Field(KanaKeyComposer()).type("かた").finish().text)
    }

    // ---- Ethiopic ----

    /** A vowel walks along the consonant's row of eight, and re-vowels rather than appends. */
    @Test
    fun `ethiopic builds syllables from a consonant and a vowel`() {
        assertEquals("ኩ", Field(EthiopicComposer()).type("ከኡ").finish().text)
        assertEquals("ኪ", Field(EthiopicComposer()).type("ከኡኢ").finish().text)
        assertEquals("ሰላም", Field(EthiopicComposer()).type("ሰለኣመእ").finish().text)
        // The consonant key alone is already the ä-form.
        assertEquals("ከ", Field(EthiopicComposer()).type("ከ").finish().text)
        // አ is both a consonant and the ä vowel key, so it starts a syllable of its own.
        assertEquals("ከአ", Field(EthiopicComposer()).type("ከአ").finish().text)
    }

    // ---- Chinese ----

    private val dictionary = PinyinDictionary.fromEntries(
        listOf("zhong" to "中种重", "zhu" to "主住", "wo" to "我握", "de" to "的地"),
    )

    @Test
    fun `pinyin offers candidates and space takes the best one`() {
        val composer = HanComposer(dictionary, SpellingScheme.Pinyin)
        composer.accept("w")
        composer.accept("o")
        assertEquals("wo", composer.composing)
        assertEquals(listOf("我", "握"), composer.candidates)
        assertEquals("我", composer.space()?.commit)
        assertEquals("", composer.composing)
    }

    @Test
    fun `pinyin suggests from a partial spelling`() {
        val composer = HanComposer(dictionary, SpellingScheme.Pinyin)
        composer.accept("z")
        composer.accept("h")
        assertTrue(composer.candidates.contains("中"))
        assertEquals("重", composer.pick("重").commit)
    }

    /** Digits and punctuation are not spelling, so the service types them plainly. */
    @Test
    fun `pinyin declines keys that are not letters`() {
        assertNull(HanComposer(dictionary, SpellingScheme.Pinyin).accept("1"))
    }

    @Test
    fun `bopomofo spells the same syllables and a tone mark ends one`() {
        val composer = HanComposer(
            dictionary,
            SpellingScheme.Bopomofo(mapOf("ㄓㄨㄥ" to "zhong")),
        )
        composer.accept("ㄓ")
        composer.accept("ㄨ")
        composer.accept("ㄥ")
        assertEquals("ㄓㄨㄥ", composer.composing)
        assertEquals(listOf("中", "种", "重"), composer.candidates)
        assertEquals("中", composer.accept("ˊ")?.commit)
    }
}
