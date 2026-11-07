package com.vayunmathur.code.syntax

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Unit tests for [SyntaxTransformation]: spans, current line, brackets, matches, budgets. */
class SyntaxTransformationTest {

    private fun colors() = SyntaxColors(
        keyword = Color.Red,
        string = Color.Green,
        number = Color.Blue,
        comment = Color.Gray,
        annotation = Color.Yellow,
        function = Color.Cyan,
        type = Color.Magenta,
        match = Color.Red,
        activeMatch = Color.Blue,
        currentLine = Color.Gray,
        matchedBracket = Color.Yellow,
        trailingWhitespace = Color.Red,
        brackets = listOf(Color.Red, Color.Green, Color.Blue),
    )

    private fun filter(
        text: String,
        language: Language = Language.KOTLIN,
        matches: List<IntRange> = emptyList(),
        activeMatch: Int = -1,
        caret: Int = -1,
        tsSpans: List<TsColorSpan>? = null,
    ) = SyntaxTransformation(Language.KOTLIN.spec.takeIf { language == Language.KOTLIN } ?: language.spec, colors(), matches, activeMatch, caret, tsSpans)
        .filter(AnnotatedString(text)).text

    @Test
    fun filterPreservesText() {
        assertEquals("fun f() {}", filter("fun f() {}").text)
        assertEquals("", filter("").text)
    }

    @Test
    fun keywordSpanApplied() {
        val out = SyntaxTransformation(Language.KOTLIN.spec, colors())
            .filter(AnnotatedString("fun f"))
        val spans = out.text.spanStyles
        assertTrue(spans.any { it.item.color == Color.Red }, "keyword span missing: $spans")
    }

    @Test
    fun stringSpanApplied() {
        val out = SyntaxTransformation(Language.KOTLIN.spec, colors())
            .filter(AnnotatedString("val s = \"hi\""))
        assertTrue(out.text.spanStyles.any { it.item.color == Color.Green })
    }

    @Test
    fun commentSpanApplied() {
        val out = SyntaxTransformation(Language.KOTLIN.spec, colors())
            .filter(AnnotatedString("// comment"))
        assertTrue(out.text.spanStyles.any { it.item.color == Color.Gray })
    }

    @Test
    fun numberSpanApplied() {
        val out = SyntaxTransformation(Language.KOTLIN.spec, colors())
            .filter(AnnotatedString("val n = 42"))
        assertTrue(out.text.spanStyles.any { it.item.color == Color.Blue })
    }

    @Test
    fun functionCallSpanApplied() {
        val out = SyntaxTransformation(Language.KOTLIN.spec, colors())
            .filter(AnnotatedString("foo()"))
        assertTrue(out.text.spanStyles.any { it.item.color == Color.Cyan }, "function span missing")
    }

    @Test
    fun typeSpanApplied() {
        val out = SyntaxTransformation(Language.KOTLIN.spec, colors())
            .filter(AnnotatedString("val x: String"))
        assertTrue(out.text.spanStyles.any { it.item.color == Color.Magenta }, "type span missing")
    }

    @Test
    fun rainbowBracketsColored() {
        val out = SyntaxTransformation(Language.KOTLIN.spec, colors())
            .filter(AnnotatedString("(a)"))
        // Bracket chars get rainbow colors from the brackets palette.
        assertTrue(out.text.spanStyles.isNotEmpty())
    }

    @Test
    fun trailingWhitespaceFlagged() {
        val out = SyntaxTransformation(Language.KOTLIN.spec, colors())
            .filter(AnnotatedString("a  \nb"))
        assertTrue(out.text.spanStyles.any { it.item.background == Color.Red })
    }

    @Test
    fun currentLineBackgroundAtCaret() {
        val out = SyntaxTransformation(Language.KOTLIN.spec, colors(), caret = 2)
            .filter(AnnotatedString("ab\ncd"))
        assertTrue(out.text.spanStyles.any { it.item.background == Color.Gray })
    }

    @Test
    fun bracketMatchHighlighted() {
        val out = SyntaxTransformation(Language.KOTLIN.spec, colors(), caret = 1)
            .filter(AnnotatedString("(a)"))
        // Caret after '(': both brackets get the match background.
        assertTrue(out.text.spanStyles.count { it.item.background == Color.Yellow } >= 2)
    }

    @Test
    fun findMatchesHighlighted() {
        val out = SyntaxTransformation(
            Language.KOTLIN.spec, colors(),
            matches = listOf(0..2, 5..7), activeMatch = 1,
        ).filter(AnnotatedString("foo bar foo"))
        val matchSpans = out.text.spanStyles.filter { it.item.background == Color.Red || it.item.background == Color.Blue }
        assertTrue(matchSpans.size >= 2)
    }

    @Test
    fun emptyMatchRangesSkipped() {
        val out = SyntaxTransformation(
            Language.KOTLIN.spec, colors(), matches = listOf(IntRange.EMPTY),
        ).filter(AnnotatedString("abc"))
        // No crash; only syntax spans present.
        assertEquals("abc", out.text.text)
    }

    @Test
    fun nullSpecSkipsHighlight() {
        val out = SyntaxTransformation(null, colors(), caret = 1)
            .filter(AnnotatedString("(a)"))
        // No spec: no token spans, no crash.
        assertEquals("(a)", out.text.text)
    }

    @Test
    fun overBudgetSkipsHighlight() {
        val big = "x ".repeat(MAX_HIGHLIGHT_CHARS / 2 + 100)
        val out = SyntaxTransformation(Language.KOTLIN.spec, colors(), caret = 1)
            .filter(AnnotatedString(big))
        assertEquals(big, out.text.text)
    }

    @Test
    fun tsSpansReplaceRegexPath() {
        val spans = listOf(TsColorSpan(0, 3, Color.Green))
        val out = SyntaxTransformation(Language.KOTLIN.spec, colors(), tsSpans = spans)
            .filter(AnnotatedString("fun x"))
        assertTrue(out.text.spanStyles.any { it.item.color == Color.Green })
    }

    @Test
    fun tsSpansClampedToBounds() {
        val spans = listOf(TsColorSpan(-5, 500, Color.Green), TsColorSpan(2, 2, Color.Red))
        val out = SyntaxTransformation(Language.KOTLIN.spec, colors(), tsSpans = spans)
            .filter(AnnotatedString("abc"))
        assertEquals("abc", out.text.text)
        assertTrue(out.text.spanStyles.any { it.item.color == Color.Green })
    }

    @Test
    fun tsColorSpanHoldsFields() {
        val s = TsColorSpan(1, 2, Color.Blue)
        assertEquals(1, s.start)
        assertEquals(2, s.end)
        assertEquals(Color.Blue, s.color)
        assertEquals(s, s.copy())
    }

    @Test
    fun editorThemesConstants() {
        assertEquals("default", EditorThemes.DEFAULT)
        assertEquals("monokai", EditorThemes.MONOKAI)
        assertEquals("solarized", EditorThemes.SOLARIZED)
        assertEquals(3, EditorThemes.ALL.size)
    }
}
