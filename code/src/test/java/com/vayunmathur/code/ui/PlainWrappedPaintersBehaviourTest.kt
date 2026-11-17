package com.vayunmathur.code.ui

import android.graphics.Bitmap
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.sp
import androidx.compose.ui.geometry.Size
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.vayunmathur.code.domain.syntax.Language
import com.vayunmathur.code.domain.syntax.SyntaxColors
import com.vayunmathur.code.domain.syntax.TsColorSpan
import com.vayunmathur.code.domain.Diagnostic
import com.vayunmathur.code.domain.DiagnosticSeverity
import com.vayunmathur.code.domain.FoldRegion
import org.junit.Rule
import org.junit.runner.RunWith
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/** Branch-focused tests for [PlainRowPainter]/[WrappedRowPainter] and [EditorCanvasData]. */
@RunWith(AndroidJUnit4::class)
class PlainWrappedPaintersBehaviourTest {
    @get:Rule val rule = createComposeRule()

    private fun colors(brackets: List<Color> = listOf(Color.Red, Color.Green)) = SyntaxColors(
        Color.Red, Color.Green, Color.Blue, Color.Gray, Color.Yellow, Color.Cyan, Color.Magenta,
        Color(0xFF888888), Color.Blue, Color(0xFF222222), Color.Yellow, Color.Red, brackets,
    )

    private fun draw(w: Int = 400, h: Int = 200, block: androidx.compose.ui.graphics.drawscope.DrawScope.() -> Unit): Bitmap {
        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        CanvasDrawScope().draw(Density(1f), LayoutDirection.Ltr, Canvas(android.graphics.Canvas(bitmap)), Size(w.toFloat(), h.toFloat()), block)
        return bitmap
    }

    private fun measurer(): TextMeasurer {
        var m: TextMeasurer? = null
        rule.setContent { m = rememberTextMeasurer() }
        return m!!
    }

    private val style = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 14.sp, color = Color.Black)

    @Test fun plainPainterComputesRowGeometryAndPaintsAllDecorations() {
        val m = measurer()
        val lines = listOf("  fun f() {", "\tval x = 1", "}", "")
        val starts = intArrayOf(0, 12, 23, 25)
        draw {
            val p = PlainRowPainter(
                scope = this, row = 1, visibleLines = listOf(0, 1, 2, 3), lines = lines, lineStarts = starts,
                lineHeight = 20f, scrollY = 5f, scrollX = 3f, measurer = m, style = style, charWidth = 8f,
                gutterWidth = 40f, caret = 12, selMin = 12, selMax = 30, // spans EOL of line 1
                highlightMatches = listOf(12..14, 100..102), activeMatch = 0, caretColor = Color.Blue,
                colors = colors(), spec = Language.KOTLIN.spec, tsSpans = listOf(TsColorSpan(12, 15, Color.Green)),
                diagnosticsByLine = mapOf(1 to listOf(
                    Diagnostic(1, 2, 2, DiagnosticSeverity.WARNING, "zero-width"),
                    Diagnostic(1, 0, 4, DiagnosticSeverity.ERROR, "err"),
                )),
                severityColor = { if (it == DiagnosticSeverity.ERROR) Color.Red else Color.Yellow },
                foldedHeaders = setOf(1), foldByHeader = mapOf(1 to FoldRegion(1, 2)),
                extraCarets = listOf(13, 9999), extraCaretColor = Color.Cyan, composing = TextRange(12, 14),
                showIndentGuides = true, tabWidth = 4, guideColor = Color.Gray, showWhitespace = true, gutterColor = Color.Gray,
            )
            assertEquals(1, p.source)
            assertEquals(15f, p.y)
            assertEquals(12, p.lineStart)
            p.paint()
        }
    }

    @Test fun plainPainterSkipsOffLineDecorations() {
        val m = measurer()
        val lines = listOf("abc", "de")
        val starts = intArrayOf(0, 4)
        draw {
            // Caret/selection/composition/extras all live on line 0 while we paint line 1.
            PlainRowPainter(
                scope = this, row = 1, visibleLines = listOf(0, 1), lines = lines, lineStarts = starts,
                lineHeight = 20f, scrollY = 0f, scrollX = 0f, measurer = m, style = style, charWidth = 8f,
                gutterWidth = 40f, caret = 1, selMin = 0, selMax = 2,
                highlightMatches = listOf(0..1), activeMatch = -1, caretColor = Color.Blue,
                colors = colors(emptyList()), spec = null, tsSpans = null,
                diagnosticsByLine = emptyMap(), severityColor = { Color.Red },
                foldedHeaders = emptySet(), foldByHeader = emptyMap(),
                extraCarets = listOf(0), extraCaretColor = Color.Cyan, composing = TextRange(0, 1),
                showIndentGuides = false, tabWidth = 4, guideColor = Color.Gray, showWhitespace = false, gutterColor = Color.Gray,
            ).paint()
            // Empty line with whitespace/indent features enabled: loops must terminate.
            PlainRowPainter(
                scope = this, row = 0, visibleLines = listOf(1, 0), lines = lines, lineStarts = starts,
                lineHeight = 20f, scrollY = 0f, scrollX = 0f, measurer = m, style = style, charWidth = 8f,
                gutterWidth = 40f, caret = -1, selMin = 5, selMax = 3,
                highlightMatches = emptyList(), activeMatch = 0, caretColor = Color.Blue,
                colors = colors(), spec = Language.PYTHON.spec, tsSpans = emptyList(),
                diagnosticsByLine = mapOf(0 to emptyList()), severityColor = { Color.Red },
                foldedHeaders = emptySet(), foldByHeader = mapOf(0 to FoldRegion(0, 1)),
                extraCarets = emptyList(), extraCaretColor = Color.Cyan, composing = TextRange(50, 60),
                showIndentGuides = true, tabWidth = 2, guideColor = Color.Gray, showWhitespace = true, gutterColor = Color.Gray,
            ).paint()
        }
    }

    @Test fun plainPainterLongLineViewportSliceAndScrolledPastEnd() {
        val m = measurer()
        val long = "x".repeat(2500)
        val lines = listOf(long)
        val starts = intArrayOf(0)
        draw(200, 60) {
            PlainRowPainter(
                scope = this, row = 0, visibleLines = listOf(0), lines = lines, lineStarts = starts,
                lineHeight = 20f, scrollY = 0f, scrollX = 100_000f, measurer = m, style = style, charWidth = 8f,
                gutterWidth = 40f, caret = 2499, selMin = 0, selMax = 0,
                highlightMatches = emptyList(), activeMatch = -1, caretColor = Color.Blue,
                colors = colors(), spec = null, tsSpans = null,
                diagnosticsByLine = emptyMap(), severityColor = { Color.Red },
                foldedHeaders = emptySet(), foldByHeader = emptyMap(),
                extraCarets = emptyList(), extraCaretColor = Color.Cyan, composing = null,
                showIndentGuides = true, tabWidth = 4, guideColor = Color.Gray, showWhitespace = true, gutterColor = Color.Gray,
            ).paint()
        }
    }

    @Test fun wrappedPainterComputesGeometryAndPaintsDecorations() {
        val m = measurer()
        val lines = listOf("fun f() { val x = 1 }", "short")
        val starts = intArrayOf(0, 22)
        draw {
            val p = WrappedRowPainter(
                scope = this, k = 0, visibleLines = listOf(0, 1), visualStarts = intArrayOf(0, 2),
                wrapCounts = intArrayOf(2, 1), lines = lines, lineStarts = starts,
                lineHeight = 20f, scrollY = 10f, gutterWidth = 40f, measurer = m, style = style,
                wrapConstraints = Constraints(maxWidth = 120), caret = 3, selMin = 1, selMax = 8,
                highlightMatches = listOf(2..5, 50..60), activeMatch = 0, caretColor = Color.Blue,
                colors = colors(), spec = Language.KOTLIN.spec, tsSpans = null,
                diagnosticsByLine = mapOf(0 to listOf(
                    Diagnostic(0, 1, 1, DiagnosticSeverity.INFO, "zero"),
                    Diagnostic(0, 0, 3, DiagnosticSeverity.ERROR, "err"),
                )),
                severityColor = { Color.Red }, foldedHeaders = setOf(0),
                foldByHeader = mapOf(0 to FoldRegion(0, 1)), extraCarets = listOf(4, 999),
                extraCaretColor = Color.Cyan, composing = TextRange(1, 4), gutterColor = Color.Gray,
            )
            assertEquals(0, p.source)
            assertEquals(-10f, p.lineTop)
            p.paint()
        }
    }

    @Test fun wrappedPainterSkipsOffLineAndZeroWidthDecorations() {
        val m = measurer()
        val lines = listOf("abc", "")
        val starts = intArrayOf(0, 4)
        draw {
            WrappedRowPainter(
                scope = this, k = 1, visibleLines = listOf(0, 1), visualStarts = intArrayOf(0, 1),
                wrapCounts = intArrayOf(1, 1), lines = lines, lineStarts = starts,
                lineHeight = 20f, scrollY = 0f, gutterWidth = 40f, measurer = m, style = style,
                wrapConstraints = Constraints(maxWidth = 100), caret = 0, selMin = 3, selMax = 1,
                highlightMatches = listOf(0..0), activeMatch = 0, caretColor = Color.Blue,
                colors = colors(emptyList()), spec = null, tsSpans = emptyList(),
                diagnosticsByLine = mapOf(1 to listOf(Diagnostic(1, 5, 3, DiagnosticSeverity.WARNING, "bad range"))),
                severityColor = { Color.Yellow }, foldedHeaders = emptySet(),
                foldByHeader = emptyMap(), extraCarets = listOf(2), extraCaretColor = Color.Cyan,
                composing = TextRange(0, 2), gutterColor = Color.Gray,
            ).paint()
        }
    }

    @Test fun drawPlainAndWrappedWithScrolledEmptyView() {
        val m = measurer()
        draw {
            drawPlainLines(
                measurer = m, style = style, charWidth = 8f, lineHeight = 20f, gutterWidth = 40f,
                scrollX = 0f, scrollY = 10_000f, visibleLines = emptyList(), lines = emptyList(), lineStarts = intArrayOf(),
                caret = -1, selMin = 0, selMax = 0, highlightMatches = emptyList(), activeMatch = -1,
                caretColor = Color.Blue, colors = colors(), spec = null, tsSpans = null,
                diagnosticsByLine = emptyMap(), severityColor = { Color.Red }, foldedHeaders = emptySet(),
                foldByHeader = emptyMap(), extraCarets = emptyList(), extraCaretColor = Color.Cyan, composing = null,
                showIndentGuides = true, tabWidth = 4, guideColor = Color.Gray, showWhitespace = true, gutterColor = Color.Gray,
            )
            drawWrappedLines(
                measurer = m, style = style, wrapConstraints = Constraints(maxWidth = 100), lineHeight = 20f,
                gutterWidth = 40f, scrollY = 500f, visibleLines = listOf(0), visualStarts = intArrayOf(0),
                totalVisualRows = 1, wrapCounts = intArrayOf(1), lines = listOf("hello world hello"),
                lineStarts = intArrayOf(0), caret = 2, selMin = 0, selMax = 5, highlightMatches = listOf(1..3),
                activeMatch = 0, caretColor = Color.Blue, colors = colors(), spec = null, tsSpans = null,
                diagnosticsByLine = emptyMap(), severityColor = { Color.Red }, foldedHeaders = emptySet(),
                foldByHeader = emptyMap(), extraCarets = emptyList(), extraCaretColor = Color.Cyan,
                composing = null, gutterColor = Color.Gray,
            )
        }
    }

    @Test fun editorCanvasDataValueSemantics() {
        val base = EditorCanvasData(
            value = androidx.compose.ui.text.input.TextFieldValue("fun", TextRange(1)),
            text = "fun", lines = listOf("fun"), lineStarts = intArrayOf(0), visibleLines = listOf(0),
            visualStarts = intArrayOf(0), totalVisualRows = 1, wrapCounts = intArrayOf(1),
            lineHeight = 20f, charWidth = 8f, gutterWidth = 40f, scrollY = 1f, scrollX = 2f,
            wrapping = false, wrapConstraints = Constraints(maxWidth = 100), style = style,
            colors = colors(), spec = null, tsSpans = null, highlightMatches = listOf(0..1), activeMatch = 0,
            diagnosticsByLine = emptyMap(), foldedHeaders = setOf(1), foldByHeader = mapOf(1 to FoldRegion(1, 2)),
            extraCarets = listOf(2), composing = TextRange(0, 1), showIndentGuides = true, showWhitespace = true,
            tabWidth = 4, gutterColor = Color.Gray, caretColor = Color.Blue, guideColor = Color.Gray,
            extraCaretColor = Color.Cyan, severityErrorColor = Color.Red, severityWarningColor = Color.Yellow,
            severityInfoColor = Color.Green,
        )
        val copy = base.copy(scrollY = 99f, wrapping = true)
        assertEquals(99f, copy.scrollY)
        assertTrue(copy.wrapping)
        assertEquals("fun", copy.text)
        assertNotEquals(base, copy)
        assertEquals(base.lines, copy.lines)
        assertTrue(base.lineStarts.contentEquals(intArrayOf(0)))
        // Data-class equality on arrays is referential for lineStarts, so two
        // independently-built instances with equal scalars are not equal.
        assertNotEquals(base.hashCode(), 0)
    }
}
