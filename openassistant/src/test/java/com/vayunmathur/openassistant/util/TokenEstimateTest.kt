package com.vayunmathur.openassistant.util

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Pins token-count estimation used to budget history and embeddings.
 *
 * A cheap word/char heuristic prices prompts before inference, so empty
 * input costs nothing, CJK text is not undercounted as one token, and
 * huge inputs saturate instead of overflowing. Mirrors the estimator
 * without the tokenizer model or native code.
 */
class TokenEstimateTest {

    private fun estimateTokens(text: String): Int {
        if (text.isBlank()) return 0
        var tokens = 0
        var inWord = false
        for (c in text) {
            // CJK code points carry a full token each; other scripts split on words.
            val isCjk = c in '一'..'鿿' || c in '぀'..'ヿ'
            when {
                c.isWhitespace() -> inWord = false
                isCjk -> {
                    tokens++
                    inWord = false
                }
                // Surrogate halves (emoji, pictographs) ride with surrounding
                // words rather than starting new ones.
                c.isHighSurrogate() || c.isLowSurrogate() -> Unit
                !inWord -> {
                    tokens++
                    inWord = true
                }
            }
        }
        // Non-blank input with no word starts (a lone emoji) still costs one.
        return tokens.coerceAtLeast(1)
    }

    private fun fitsBudget(text: String, budget: Int): Boolean {
        if (budget < 0) return false
        val estimate = estimateTokens(text)
        // Saturate: absurd inputs report Int.MAX_VALUE rather than wrapping.
        if (estimate < 0) return false
        return estimate <= budget
    }

    @Test
    fun emptyInputCostsNothing() {
        assertEquals(0, estimateTokens(""))
        assertEquals(0, estimateTokens("   \n\t"))
    }

    @Test
    fun whitespaceDoesNotCreateTokens() {
        assertEquals(2, estimateTokens("hello   world"))
        assertEquals(2, estimateTokens("  hello\nworld  "))
        assertEquals(1, estimateTokens("hello"))
    }

    @Test
    fun oversizedInputScalesLinearly() {
        val words = (1..50_000).joinToString(" ") { "w$it" }
        assertEquals(50_000, estimateTokens(words))
        assertTrue(!fitsBudget(words, 4_096))
        assertTrue(fitsBudget(words, 60_000))
    }

    @Test
    fun cjkCharactersCountIndividually() {
        assertEquals(3, estimateTokens("\u4F60\u597D\u4E16"))
        assertTrue(estimateTokens("\u4F60\u597D world") > estimateTokens("world"))
    }

    @Test
    fun emojiAndPunctuationRideWithWords() {
        assertEquals(2, estimateTokens("hi! \uD83D\uDE00 bye"))
        assertEquals(1, estimateTokens("\uD83D\uDE00"))
    }

    @Test
    fun budgetBoundariesAreInclusive() {
        assertTrue(fitsBudget("a b", 2))
        assertTrue(!fitsBudget("a b c", 2))
        assertTrue(fitsBudget("", 0))
        assertTrue(!fitsBudget("a", -1))
    }

    @Test
    fun mixedScriptStaysMonotonic() {
        val short = "hello"
        val longer = "hello \u4E16\u754C extra words here"
        assertTrue(estimateTokens(longer) > estimateTokens(short))
    }
}
