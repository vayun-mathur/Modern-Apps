package com.vayunmathur.code.ui

import androidx.compose.ui.graphics.Color
import com.vayunmathur.code.syntax.Language
import com.vayunmathur.code.syntax.SyntaxColors
import com.vayunmathur.code.syntax.TsColorSpan
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Unit tests for the experimental editor's pure row/line helpers (no composition). */
class CodeEditorDrawTest {

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
        brackets = listOf(Color.Red, Color.Green),
    )

    @Test
    fun annotatedLineHighlightsTokens() {
        val out = annotatedLine("fun f() // c", Language.KOTLIN.spec, colors())
        assertEquals("fun f() // c", out.text)
        assertTrue(out.spanStyles.any { it.item.color == Color.Red }, "keyword missing")
        assertTrue(out.spanStyles.any { it.item.color == Color.Gray }, "comment missing")
    }

    @Test
    fun annotatedLineEmptyAndNullSpec() {
        assertEquals("", annotatedLine("", Language.KOTLIN.spec, colors()).text)
        assertEquals("plain", annotatedLine("plain", null, colors()).text)
    }

    @Test
    fun annotatedLineOverBudgetReturnsPlain() {
        val long = "x".repeat(MAX_LINE_HIGHLIGHT + 10)
        val out = annotatedLine(long, Language.KOTLIN.spec, colors())
        assertEquals(long, out.text)
        assertTrue(out.spanStyles.isEmpty())
    }

    @Test
    fun annotatedLineRainbowBrackets() {
        val out = annotatedLine("(a)", Language.KOTLIN.spec, colors())
        // Bracket chars painted from the palette.
        assertTrue(out.spanStyles.isNotEmpty())
    }

    @Test
    fun annotatedLineTsSpansPath() {
        val spans = listOf(TsColorSpan(0, 3, Color.Green))
        val out = annotatedLine("fun x", Language.KOTLIN.spec, colors(), tsSpans = spans, lineStart = 0)
        assertTrue(out.spanStyles.any { it.item.color == Color.Green })
    }

    @Test
    fun annotatedLineTsSpansOutsideLineIgnored() {
        val spans = listOf(TsColorSpan(100, 110, Color.Green))
        val out = annotatedLine("abc", Language.KOTLIN.spec, colors(), tsSpans = spans, lineStart = 0)
        assertEquals("abc", out.text)
        assertTrue(out.spanStyles.none { it.item.color == Color.Green })
    }

    @Test
    fun leadingColumnsExpandsTabs() {
        assertEquals(0, leadingColumns("abc", 4))
        assertEquals(3, leadingColumns("   abc", 4))
        assertEquals(4, leadingColumns("\tabc", 4))
        assertEquals(8, leadingColumns("\t\tabc", 4))
        assertEquals(6, leadingColumns(" \t abc", 4))
        assertEquals(3, leadingColumns("   ", 4))
    }

    @Test
    fun lineOfOffsetBinarySearch() {
        val starts = intArrayOf(0, 5, 10)
        assertEquals(0, lineOfOffset(starts, 0))
        assertEquals(0, lineOfOffset(starts, 4))
        assertEquals(1, lineOfOffset(starts, 5))
        assertEquals(1, lineOfOffset(starts, 9))
        assertEquals(2, lineOfOffset(starts, 10))
        assertEquals(2, lineOfOffset(starts, 99))
    }

    @Test
    fun visualRowToVisibleIndexLookup() {
        val visible = listOf(0, 2, 5)
        val starts = intArrayOf(0, 3, 6)
        assertEquals(0, visualRowToVisibleIndex(visible, starts, 0))
        assertEquals(0, visualRowToVisibleIndex(visible, starts, 2))
        assertEquals(1, visualRowToVisibleIndex(visible, starts, 3))
        assertEquals(1, visualRowToVisibleIndex(visible, starts, 5))
        assertEquals(2, visualRowToVisibleIndex(visible, starts, 6))
        assertEquals(2, visualRowToVisibleIndex(visible, starts, 99))
        assertEquals(0, visualRowToVisibleIndex(emptyList(), intArrayOf(), 5))
    }

    @Test
    fun displayRowToSource() {
        assertEquals(5, displayRowToSource(listOf(3, 5, 7), 1))
        assertEquals(null, displayRowToSource(listOf(3), 9))
    }

    @Test
    fun offsetAtGridMath() {
        val lines = listOf("hello", "world")
        val starts = intArrayOf(0, 6)
        // First row, first col.
        assertEquals(0, offsetAt(40f, 5f, 0f, 0f, 20f, 10f, 40f, listOf(0, 1), lines, starts, 11))
        // Second row.
        assertEquals(6, offsetAt(40f, 25f, 0f, 0f, 20f, 10f, 40f, listOf(0, 1), lines, starts, 11))
        // Column clamps to line length; offset clamps to text length.
        assertEquals(5, offsetAt(500f, 5f, 0f, 0f, 20f, 10f, 40f, listOf(0, 1), lines, starts, 11))
    }
}
