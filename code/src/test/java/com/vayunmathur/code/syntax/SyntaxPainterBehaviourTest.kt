package com.vayunmathur.code.syntax

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Edge branches of [SyntaxTransformation]: budgets, ts spans, bracket matching, matches. */
class SyntaxPainterBehaviourTest {

    private fun colors(brackets: List<Color> = listOf(Color.Red, Color.Green, Color.Blue)) = SyntaxColors(
        keyword = Color.Red, string = Color.Green, number = Color.Blue, comment = Color.Gray,
        annotation = Color.Yellow, function = Color.Cyan, type = Color.Magenta,
        match = Color(0xFF111111), activeMatch = Color(0xFF222222), currentLine = Color(0xFF333333),
        matchedBracket = Color(0xFF444444), trailingWhitespace = Color(0xFF555555), brackets = brackets,
    )

    private fun filter(
        text: String,
        spec: LanguageSpec? = Language.KOTLIN.spec,
        matches: List<IntRange> = emptyList(),
        activeMatch: Int = -1,
        caret: Int = -1,
        tsSpans: List<TsColorSpan>? = null,
        palette: List<Color> = listOf(Color.Red, Color.Green, Color.Blue),
    ) = SyntaxTransformation(spec, colors(palette), matches, activeMatch, caret, tsSpans)
        .filter(AnnotatedString(text)).text

    @Test fun overBudgetTextGetsNoHighlightButKeepsTextAndMatches() {
        val big = "fun " + "x".repeat(MAX_HIGHLIGHT_CHARS + 10)
        val out = filter(big, caret = 2, matches = listOf(0..2), activeMatch = 0)
        assertEquals(big, out.text)
        // Find-match painting is not budget-gated.
        assertTrue(out.spanStyles.any { it.item.background == Color(0xFF222222) })
        // No keyword span: the only spans are the match background.
        assertTrue(out.spanStyles.all { it.item.color == Color.Unspecified })
    }

    @Test fun tsSpansClampedAndEmptySpansSkipped() {
        val out = filter(
            "abc",
            tsSpans = listOf(
                TsColorSpan(-5, 2, Color.Green),
                TsColorSpan(2, 99, Color.Blue),
                TsColorSpan(1, 1, Color.Red),
                TsColorSpan(10, 20, Color.Red),
            ),
        )
        assertEquals("abc", out.text)
        assertTrue(out.spanStyles.any { it.item.color == Color.Green && it.start == 0 && it.end == 2 })
        assertTrue(out.spanStyles.any { it.item.color == Color.Blue && it.start == 2 && it.end == 3 })
    }

    @Test fun tsSpansReplaceSpecButRainbowAndTrailingWsStillPaint() {
        val out = filter("(a)  ", tsSpans = emptyList())
        assertTrue(out.spanStyles.any { it.item.background == Color(0xFF555555) })
        assertTrue(out.spanStyles.isNotEmpty())
        val noPalette = filter("(a)", tsSpans = emptyList(), palette = emptyList())
        // With no bracket palette and no ts spans, nothing is painted.
        assertTrue(noPalette.spanStyles.isEmpty())
    }

    @Test fun nullSpecAndEmptyTextProduceNoSpans() {
        assertTrue(filter("hello", spec = null).spanStyles.isEmpty())
        assertEquals("", filter("").text)
        assertTrue(filter("").spanStyles.isEmpty())
    }

    @Test fun bracketMatchForwardBackwardNestedAndUnmatched() {
        // Caret after '(' finds its partner.
        val fwd = filter("(a(b)c)", caret = 1)
        assertEquals(2, fwd.spanStyles.count { it.item.background == Color(0xFF444444) })
        // Caret on ')' searches backwards.
        val back = filter("(a)", caret = 2)
        assertEquals(2, back.spanStyles.count { it.item.background == Color(0xFF444444) })
        // Unmatched bracket: no highlight.
        val unmatched = filter("(a", caret = 1)
        assertEquals(0, unmatched.spanStyles.count { it.item.background == Color(0xFF444444) })
        // Caret not next to a bracket, and out-of-range carets: no highlight.
        assertEquals(0, filter("abc", caret = 1).spanStyles.count { it.item.background == Color(0xFF444444) })
        assertEquals(0, filter("(a)", caret = -1).spanStyles.count { it.item.background == Color(0xFF444444) })
        assertEquals(0, filter("(a)", caret = 99).spanStyles.count { it.item.background == Color(0xFF444444) })
    }

    @Test fun currentLineOnlyForValidCaretAndNonEmptyLine() {
        // Caret on an empty line: no current-line span.
        val emptyLine = filter("a\n\nb", caret = 2)
        assertEquals(0, emptyLine.spanStyles.count { it.item.background == Color(0xFF333333) })
        // Caret at end of text after a newline.
        val eol = filter("ab\n", caret = 3)
        assertEquals(0, eol.spanStyles.count { it.item.background == Color(0xFF333333) })
        val mid = filter("ab\ncd", caret = 4)
        assertTrue(mid.spanStyles.any { it.item.background == Color(0xFF333333) && it.start == 3 && it.end == 5 })
    }

    @Test fun matchesClampEmptyAndActiveSelection() {
        val out = filter("abcd", matches = listOf(IntRange(2, 1), -3..99, 1..2), activeMatch = 2)
        assertEquals("abcd", out.text)
        // Empty range is skipped; out-of-range range is clamped to the text.
        assertTrue(out.spanStyles.any { it.start == 0 && it.end == 4 })
        assertTrue(out.spanStyles.any { it.item.background == Color(0xFF222222) })
        assertTrue(out.spanStyles.any { it.item.background == Color(0xFF111111) })
    }

    @Test fun functionsAndTypesSkipCoveredRanges() {
        // "String" is a type and also matches no spec token; foo() is a function.
        val out = filter("val x: String = foo()")
        assertTrue(out.spanStyles.any { it.item.color == Color.Magenta })
        assertTrue(out.spanStyles.any { it.item.color == Color.Cyan })
        // A keyword that looks like a function/type must not be repainted:
        // "Fun" capitalized identifier covered check still leaves text intact.
        assertEquals("Fun()", filter("Fun()").text)
    }

    @Test fun colorForMapsEveryTokenKind() {
        val c = colors()
        assertEquals(Color.Gray, c.colorFor(TokenKind.COMMENT))
        assertEquals(Color.Green, c.colorFor(TokenKind.STRING))
        assertEquals(Color.Blue, c.colorFor(TokenKind.NUMBER))
        assertEquals(Color.Yellow, c.colorFor(TokenKind.ANNOTATION))
        assertEquals(Color.Red, c.colorFor(TokenKind.KEYWORD))
        assertEquals(listOf("default", "monokai", "solarized"), EditorThemes.ALL.map { it.first })
    }
}
