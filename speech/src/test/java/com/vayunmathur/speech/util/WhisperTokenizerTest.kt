package com.vayunmathur.speech.util

import java.io.ByteArrayInputStream
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.Test
import kotlin.test.assertEquals

@RunWith(RobolectricTestRunner::class)
class WhisperTokenizerTest {

    private fun tokenizer(vocab: String): WhisperTokenizer =
        WhisperTokenizer.load(ByteArrayInputStream(vocab.toByteArray(Charsets.UTF_8)))

    @Test
    fun decodesPlainAndSpacePrefixedTokens() {
        // Ġ is U+0120, the GPT-2 byte-level spelling of a space.
        val tok = tokenizer("""{"Hello":0,"Ġworld":1,"!":2}""")
        assertEquals("Hello world!", tok.decode(listOf(0, 1, 2)))
        assertEquals("Hello", tok.decode(listOf(0)))
        assertEquals("", tok.decode(emptyList()))
    }

    @Test
    fun decodesMultiByteUtf8AcrossTokenBoundaries() {
        // é is UTF-8 C3 A9; both bytes are printable-range, so the tokens are Ã and ©.
        val tok = tokenizer("""{"Ã":0,"©":1,"Ã©":2}""")
        assertEquals("é", tok.decode(listOf(2)))
        // Split across two tokens it must still decode once over the whole byte stream.
        assertEquals("é", tok.decode(listOf(0, 1)))
    }

    @Test
    fun decodesShiftedControlByteAlphabet() {
        // Byte 0 maps to U+0100 (Ā), byte 10 (newline) to U+010A (Ċ).
        val tok = tokenizer("""{"Ā":0,"Ċ":1,"a":2}""")
        assertEquals("\u0000\na", tok.decode(listOf(0, 1, 2)))
    }

    @Test
    fun skipsSpecialUnknownAndOutOfRangeIds() {
        val tok = tokenizer("""{"a":0,"b":1}""")
        assertEquals(50257, WhisperTokenizer.FIRST_SPECIAL_ID)
        assertEquals("ab", tok.decode(listOf(0, WhisperTokenizer.FIRST_SPECIAL_ID, 1)))
        assertEquals("ab", tok.decode(listOf(0, 99999, -1, 1)))
        assertEquals("", tok.decode(listOf(50258, 50363)))
    }

    @Test
    fun loadIgnoresIdsOutsideVocabularyRange() {
        val tok = tokenizer("""{"a":0,"bad":-5,"special":50257,"huge":999999}""")
        assertEquals("a", tok.decode(listOf(0)))
        assertEquals("", tok.decode(listOf(-5, 50257, 999999)))
    }

    @Test
    fun loadEmptyVocabularyDecodesNothing() {
        val tok = tokenizer("""{}""")
        assertEquals("", tok.decode(listOf(0, 1, 2)))
    }

    @Test
    fun charactersOutsideByteAlphabetAreDropped() {
        // 中 has no entry in the GPT-2 byte-to-char map, so its bytes contribute nothing.
        val tok = tokenizer("""{"a中b":0,"c":1}""")
        assertEquals("abc", tok.decode(listOf(0, 1)))
    }

    @Test
    fun repeatedTokensRepeatOutput() {
        val tok = tokenizer("""{"ha":0}""")
        assertEquals("hahaha", tok.decode(listOf(0, 0, 0)))
    }
}
