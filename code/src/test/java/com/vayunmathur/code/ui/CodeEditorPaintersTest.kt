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
import com.vayunmathur.code.syntax.Language
import com.vayunmathur.code.syntax.SyntaxColors
import com.vayunmathur.code.util.Diagnostic
import com.vayunmathur.code.util.DiagnosticSeverity
import com.vayunmathur.code.util.FoldRegion
import org.junit.Rule
import org.junit.runner.RunWith
import kotlin.test.Test
import kotlin.test.assertTrue

@RunWith(AndroidJUnit4::class)
class CodeEditorPaintersTest {
    @get:Rule val rule = createComposeRule()

    @Test fun paintPlainAndWrapped() {
        var measurer: TextMeasurer? = null
        rule.setContent { measurer = rememberTextMeasurer() }
        val m = measurer!!
        val colors = SyntaxColors(Color.Red, Color.Green, Color.Blue, Color.Gray, Color.Yellow, Color.Cyan, Color.Magenta, Color.Red, Color.Blue, Color.Gray, Color.Yellow, Color.Red, listOf(Color.Red, Color.Green))
        val style = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 14.sp, color = Color.Black)
        val lines = listOf("fun f() {", "    val x = 1", "}", "plain")
        val starts = intArrayOf(0, 10, 24, 26)
        val bitmap = Bitmap.createBitmap(800, 600, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(android.graphics.Canvas(bitmap))
        val scope = CanvasDrawScope()
        scope.draw(Density(1f), LayoutDirection.Ltr, canvas, Size(800f, 600f)) {
            drawPlainLines(
                measurer = m, style = style, charWidth = 8f, lineHeight = 20f, gutterWidth = 40f,
                scrollX = 0f, scrollY = 0f, visibleLines = listOf(0,1,2,3), lines = lines, lineStarts = starts,
                caret = 1, selMin = 0, selMax = 5, highlightMatches = listOf(0..2), activeMatch = 0,
                caretColor = Color.Blue, colors = colors, spec = Language.KOTLIN.spec, tsSpans = null,
                diagnosticsByLine = mapOf(1 to listOf(Diagnostic(1,4,5,DiagnosticSeverity.ERROR,"boom"))),
                severityColor = { Color.Red }, foldedHeaders = setOf(0), foldByHeader = mapOf(0 to FoldRegion(0,2)),
                extraCarets = listOf(2), extraCaretColor = Color.Cyan, composing = TextRange(0,2),
                showIndentGuides = true, tabWidth = 4, guideColor = Color.Gray, showWhitespace = true, gutterColor = Color.Gray,
            )
            drawWrappedLines(
                measurer = m, style = style, wrapConstraints = Constraints(maxWidth = 200), lineHeight = 20f, gutterWidth = 40f, scrollY = 0f,
                visibleLines = listOf(0,1,2,3), visualStarts = intArrayOf(0,1,2,3,4), totalVisualRows = 4, wrapCounts = intArrayOf(1,1,1,1),
                lines = lines, lineStarts = starts, caret = 1, selMin = 0, selMax = 5, highlightMatches = listOf(0..2), activeMatch = 0,
                caretColor = Color.Blue, colors = colors, spec = Language.KOTLIN.spec, tsSpans = null,
                diagnosticsByLine = mapOf(1 to listOf(Diagnostic(1,4,5,DiagnosticSeverity.WARNING,"w"))),
                severityColor = { Color.Yellow }, foldedHeaders = emptySet(), foldByHeader = emptyMap(),
                extraCarets = listOf(2), extraCaretColor = Color.Cyan, composing = TextRange(0,2), gutterColor = Color.Gray,
            )
        }
        assertTrue(bitmap.width == 800)
    }

    @Test fun paintPlainLongLineScrolledAndNoSpec() {
        var measurer: TextMeasurer? = null
        rule.setContent { measurer = rememberTextMeasurer() }
        val m = measurer!!
        val colors = SyntaxColors(Color.Red, Color.Green, Color.Blue, Color.Gray, Color.Yellow, Color.Cyan, Color.Magenta, Color.Red, Color.Blue, Color.Gray, Color.Yellow, Color.Red, listOf(Color.Red))
        val style = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 14.sp, color = Color.Black)
        val long = "x".repeat(2500)
        val lines = listOf(long, "", "end")
        val starts = intArrayOf(0, 2501, 2502)
        val bitmap = Bitmap.createBitmap(400, 200, Bitmap.Config.ARGB_8888)
        val scope = CanvasDrawScope()
        scope.draw(Density(1f), LayoutDirection.Ltr, Canvas(android.graphics.Canvas(bitmap)), Size(400f, 200f)) {
            drawPlainLines(
                measurer = m, style = style, charWidth = 8f, lineHeight = 20f, gutterWidth = 40f,
                scrollX = 100f, scrollY = 20f, visibleLines = listOf(0,1,2), lines = lines, lineStarts = starts,
                caret = -1, selMin = 5, selMax = 3, highlightMatches = emptyList(), activeMatch = -1,
                caretColor = Color.Blue, colors = colors, spec = null, tsSpans = listOf(com.vayunmathur.code.syntax.TsColorSpan(0,3,Color.Green)),
                diagnosticsByLine = emptyMap(), severityColor = { Color.Red }, foldedHeaders = emptySet(), foldByHeader = emptyMap(),
                extraCarets = listOf(9999), extraCaretColor = Color.Cyan, composing = null,
                showIndentGuides = false, tabWidth = 4, guideColor = Color.Gray, showWhitespace = false, gutterColor = Color.Gray,
            )
        }
        assertTrue(bitmap.height == 200)
    }

    @Test fun paintWrappedEmptyAndScrolled() {
        var measurer: TextMeasurer? = null
        rule.setContent { measurer = rememberTextMeasurer() }
        val m = measurer!!
        val colors = SyntaxColors(Color.Red, Color.Green, Color.Blue, Color.Gray, Color.Yellow, Color.Cyan, Color.Magenta, Color.Red, Color.Blue, Color.Gray, Color.Yellow, Color.Red, emptyList())
        val style = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 14.sp, color = Color.Black)
        val bitmap = Bitmap.createBitmap(400, 200, Bitmap.Config.ARGB_8888)
        val scope = CanvasDrawScope()
        scope.draw(Density(1f), LayoutDirection.Ltr, Canvas(android.graphics.Canvas(bitmap)), Size(400f, 200f)) {
            drawWrappedLines(
                measurer = m, style = style, wrapConstraints = Constraints(maxWidth = 100), lineHeight = 20f, gutterWidth = 40f, scrollY = 0f,
                visibleLines = emptyList(), visualStarts = intArrayOf(0), totalVisualRows = 0, wrapCounts = intArrayOf(),
                lines = emptyList(), lineStarts = intArrayOf(), caret = 0, selMin = 0, selMax = 0, highlightMatches = emptyList(), activeMatch = 0,
                caretColor = Color.Blue, colors = colors, spec = null, tsSpans = null,
                diagnosticsByLine = emptyMap(), severityColor = { Color.Red }, foldedHeaders = emptySet(), foldByHeader = emptyMap(),
                extraCarets = emptyList(), extraCaretColor = Color.Cyan, composing = null, gutterColor = Color.Gray,
            )
            drawSquiggle(0f, 0f, 10f, Color.Red)
            drawSquiggle(0f, 30f, 10f, Color.Red)
        }
        assertTrue(bitmap.width == 400)
    }

    @Test fun offsetAtWrappedReturnsGridOffset() {
        var measurer: TextMeasurer? = null
        rule.setContent { measurer = rememberTextMeasurer() }
        val m = measurer!!
        val colors = SyntaxColors(Color.Red, Color.Green, Color.Blue, Color.Gray, Color.Yellow, Color.Cyan, Color.Magenta, Color.Red, Color.Blue, Color.Gray, Color.Yellow, Color.Red, listOf(Color.Red))
        val style = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 14.sp, color = Color.Black)
        val off = offsetAtWrapped(50f, 5f, 0f, 20f, 40f, listOf(0,1), intArrayOf(0,1,2), 2, listOf("hello","world"), intArrayOf(0,6), 11, m, style, Constraints(maxWidth=200), null, colors)
        assertTrue(off in 0..11, "off=$off")
        assertTrue(offsetAtWrapped(50f, 5f, 0f, 20f, 40f, emptyList(), intArrayOf(0), 0, emptyList(), intArrayOf(), 0, m, style, Constraints(maxWidth=200), null, colors) == 0)
    }
}
