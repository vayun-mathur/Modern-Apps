package com.vayunmathur.code.ui

import androidx.compose.ui.graphics.Color
import com.vayunmathur.code.syntax.Language
import com.vayunmathur.code.syntax.SyntaxColors
import com.vayunmathur.code.util.TsKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Unit tests for the pure editor-chrome helpers: find ranges, ts color mapping, span fallback. */
class EditorChromeLogicTest {

    @Test
    fun literalFindIsCaseInsensitiveByDefault() {
        val ranges = findMatchRanges("Foo foo FOO", "foo", caseSensitive = false, useRegex = false)
        assertEquals(3, ranges.size)
        assertEquals(0..2, ranges[0])
        assertEquals(4..6, ranges[1])
        assertEquals(8..10, ranges[2])
    }

    @Test
    fun literalFindRespectsCase() {
        val ranges = findMatchRanges("Foo foo", "foo", caseSensitive = true, useRegex = false)
        assertEquals(listOf(4..6), ranges)
    }

    @Test
    fun literalFindIsNonOverlapping() {
        val ranges = findMatchRanges("aaa", "aa", caseSensitive = true, useRegex = false)
        assertEquals(listOf(0..1), ranges)
    }

    @Test
    fun emptyQueryYieldsNoRanges() {
        assertTrue(findMatchRanges("text", "", caseSensitive = false, useRegex = false).isEmpty())
        assertTrue(findMatchRanges("text", "", caseSensitive = false, useRegex = true).isEmpty())
    }

    @Test
    fun regexFind() {
        val ranges = findMatchRanges("a1 b22 c333", "\\d+", caseSensitive = true, useRegex = true)
        assertEquals(3, ranges.size)
        assertEquals(1..1, ranges[0])
        assertEquals(4..5, ranges[1])
        assertEquals(8..10, ranges[2])
    }

    @Test
    fun regexFindCaseInsensitive() {
        val ranges = findMatchRanges("Foo foo", "foo", caseSensitive = false, useRegex = true)
        assertEquals(2, ranges.size)
    }

    @Test
    fun invalidRegexYieldsEmpty() {
        assertTrue(findMatchRanges("text", "(", caseSensitive = true, useRegex = true).isEmpty())
    }

    @Test
    fun regexSkipsEmptyMatches() {
        assertTrue(findMatchRanges("abc", "x*", caseSensitive = true, useRegex = true).isEmpty())
    }

    private fun colors() = SyntaxColors(
        keyword = Color.Red,
        string = Color.Green,
        number = Color.Blue,
        comment = Color.Gray,
        annotation = Color.Yellow,
        function = Color.Cyan,
        type = Color.Magenta,
        match = Color.Red,
        activeMatch = Color.Red,
        currentLine = Color.Transparent,
        matchedBracket = Color.Red,
        trailingWhitespace = Color.Red,
        brackets = listOf(Color.Red),
    )

    @Test
    fun tsColorMapsEveryKind() {
        val c = colors()
        assertEquals(Color.Red, c.tsColor(TsKind.KEYWORD))
        assertEquals(Color.Green, c.tsColor(TsKind.STRING))
        assertEquals(Color.Blue, c.tsColor(TsKind.NUMBER))
        assertEquals(Color.Gray, c.tsColor(TsKind.COMMENT))
        assertEquals(Color.Yellow, c.tsColor(TsKind.ANNOTATION))
        assertEquals(Color.Cyan, c.tsColor(TsKind.FUNCTION))
        assertEquals(Color.Magenta, c.tsColor(TsKind.TYPE))
        assertEquals(Color.Magenta, c.tsColor(TsKind.PROPERTY))
    }

    @Test
    fun treeSitterColorSpansNullForUnsupportedLanguage() {
        assertNull(treeSitterColorSpans("x", Language.PLAINTEXT, colors()))
        assertNull(treeSitterColorSpans("x", Language.MARKDOWN, colors()))
    }

    @Test
    fun syntaxColorsColorForMapsTokenKinds() {
        val c = colors()
        assertEquals(Color.Red, c.colorFor(com.vayunmathur.code.syntax.TokenKind.KEYWORD))
        assertEquals(Color.Green, c.colorFor(com.vayunmathur.code.syntax.TokenKind.STRING))
        assertEquals(Color.Blue, c.colorFor(com.vayunmathur.code.syntax.TokenKind.NUMBER))
        assertEquals(Color.Gray, c.colorFor(com.vayunmathur.code.syntax.TokenKind.COMMENT))
        assertEquals(Color.Yellow, c.colorFor(com.vayunmathur.code.syntax.TokenKind.ANNOTATION))
    }
}
