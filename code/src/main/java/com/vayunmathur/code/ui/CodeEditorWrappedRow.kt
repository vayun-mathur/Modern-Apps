package com.vayunmathur.code.ui

import androidx.compose.ui.text.drawText
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Constraints
import com.vayunmathur.code.domain.syntax.LanguageSpec
import com.vayunmathur.code.domain.syntax.SyntaxColors
import com.vayunmathur.code.domain.syntax.TsColorSpan
import com.vayunmathur.code.domain.Diagnostic
import com.vayunmathur.code.domain.DiagnosticSeverity
import com.vayunmathur.code.domain.FoldRegion
internal class WrappedRowPainter(
    val scope: DrawScope,
    val k: Int,
    val visibleLines: List<Int>,
    val visualStarts: IntArray,
    val wrapCounts: IntArray,
    val lines: List<String>,
    val lineStarts: IntArray,
    val lineHeight: Float,
    val scrollY: Float,
    val gutterWidth: Float,
    val measurer: TextMeasurer,
    val style: TextStyle,
    val wrapConstraints: Constraints,
    val caret: Int,
    val selMin: Int,
    val selMax: Int,
    val highlightMatches: List<IntRange>,
    val activeMatch: Int,
    val caretColor: Color,
    val colors: SyntaxColors,
    val spec: LanguageSpec?,
    val tsSpans: List<TsColorSpan>?,
    val diagnosticsByLine: Map<Int, List<Diagnostic>>,
    val severityColor: (DiagnosticSeverity) -> Color,
    val foldedHeaders: Set<Int>,
    val foldByHeader: Map<Int, FoldRegion>,
    val extraCarets: List<Int>,
    val extraCaretColor: Color,
    val composing: TextRange?,
    val gutterColor: Color,
) {
    val source = visibleLines[k]
    val lineTop = visualStarts[k] * lineHeight - scrollY
    val lineStart = lineStarts[source]
    val lineText = lines[source]
    val lineLen = lineText.length
    val layout = measurer.measure(
        annotatedLine(lineText, spec, colors, tsSpans, lineStart),
        style,
        constraints = wrapConstraints,
    )
    val blockHeight = wrapCounts[source] * lineHeight

    fun paint() {
        paintCaretLine()
        paintSelection()
        paintFindMatches()
        paintGutter()
        with(scope) {
            drawText(layout, topLeft = androidx.compose.ui.geometry.Offset(gutterWidth, lineTop))
        }
        paintCaret()
        paintComposing()
        paintExtraCarets()
        paintDiagnostics()
    }

    private fun paintCaretLine() {
        if (caret !in lineStart..(lineStart + lineLen)) return
        with(scope) {
            drawRect(
                colors.currentLine,
                topLeft = androidx.compose.ui.geometry.Offset(0f, lineTop),
                size = Size(size.width, blockHeight),
            )
        }
    }

    private fun paintSelection() {
        if (selMax <= selMin) return
        val a = (selMin - lineStart).coerceIn(0, lineLen)
        val b = (selMax - lineStart).coerceIn(0, lineLen)
        if (b <= a) return
        val path = layout.getPathForRange(a, b).apply {
            translate(androidx.compose.ui.geometry.Offset(gutterWidth, lineTop))
        }
        with(scope) {
            drawPath(path, colors.match)
        }
    }

    private fun paintFindMatches() {
        if (highlightMatches.isEmpty()) return
        for ((mi, m) in highlightMatches.withIndex()) paintFindMatch(mi, m)
    }

    private fun paintFindMatch(mi: Int, m: IntRange) {
        val ms = m.first
        val me = m.last + 1
        if (me <= lineStart || ms >= lineStart + lineLen) return
        val a = (ms - lineStart).coerceIn(0, lineLen)
        val b = (me - lineStart).coerceIn(0, lineLen)
        if (b <= a) return
        val path = layout.getPathForRange(a, b).apply {
            translate(androidx.compose.ui.geometry.Offset(gutterWidth, lineTop))
        }
        val color = if (mi == activeMatch) caretColor.copy(alpha = MATCH_ALPHA) else colors.match
        with(scope) {
            drawPath(path, color)
        }
    }

    private fun paintGutter() {
        val number = measurer.measure(AnnotatedString((source + 1).toString()), style)
        with(scope) {
            drawText(
                number,
                color = gutterColor,
                topLeft = androidx.compose.ui.geometry.Offset(
                    gutterWidth - number.size.width - GUTTER_PAD,
                    lineTop,
                ),
            )
        }
        if (!foldByHeader.containsKey(source)) return
        val arrow = if (source in foldedHeaders) FOLD_CLOSED else FOLD_OPEN
        with(scope) {
            drawText(
                measurer.measure(AnnotatedString(arrow), style),
                color = gutterColor,
                topLeft = androidx.compose.ui.geometry.Offset(FOLD_ARROW_X, lineTop),
            )
        }
    }

    private fun paintCaret() {
        if (caret !in lineStart..(lineStart + lineLen)) return
        val rect = layout.getCursorRect((caret - lineStart).coerceIn(0, lineLen))
        with(scope) {
            drawRect(
                caretColor,
                topLeft = androidx.compose.ui.geometry.Offset(
                    gutterWidth + rect.left,
                    lineTop + rect.top,
                ),
                size = Size(CARET_WIDTH, rect.height),
            )
        }
    }

    private fun paintComposing() {
        val c = composing ?: return
        if (c.max <= lineStart || c.min >= lineStart + lineLen) return
        val a = (c.min - lineStart).coerceIn(0, lineLen)
        val b = (c.max - lineStart).coerceIn(0, lineLen)
        if (b <= a) return
        val path = layout.getPathForRange(a, b).apply {
            translate(androidx.compose.ui.geometry.Offset(gutterWidth, lineTop))
        }
        with(scope) {
            drawPath(path, caretColor.copy(alpha = COMPOSE_PATH_ALPHA))
        }
    }

    private fun paintExtraCarets() {
        for (extra in extraCarets) {
            if (extra !in lineStart..(lineStart + lineLen)) continue
            val rect = layout.getCursorRect((extra - lineStart).coerceIn(0, lineLen))
            with(scope) {
                drawRect(
                    extraCaretColor,
                    topLeft = androidx.compose.ui.geometry.Offset(
                        gutterWidth + rect.left,
                        lineTop + rect.top,
                    ),
                    size = Size(CARET_WIDTH, rect.height),
                )
            }
        }
    }

    private fun paintDiagnostics() {
        // Diagnostics: highlight the range (wrapped) + a gutter severity dot.
        val diags = diagnosticsByLine[source] ?: return
        for (d in diags) paintDiagnostic(d)
        val worst = diags.minByOrNull { it.severity.ordinal } ?: return
        with(scope) {
            drawCircle(
                severityColor(worst.severity),
                radius = DIAG_DOT_RADIUS,
                center = androidx.compose.ui.geometry.Offset(DIAG_DOT_X, lineTop + lineHeight / HALF),
            )
        }
    }

    private fun paintDiagnostic(d: Diagnostic) {
        val a = d.startCol.coerceIn(0, lineLen)
        val b = (if (d.endCol > d.startCol) d.endCol else lineLen)
            .coerceIn(a + 1, lineLen + 1).coerceAtMost(lineLen)
        if (b <= a) return
        val path = layout.getPathForRange(a, b).apply {
            translate(androidx.compose.ui.geometry.Offset(gutterWidth, lineTop))
        }
        with(scope) {
            drawPath(path, severityColor(d.severity).copy(alpha = DIAG_PATH_ALPHA))
        }
    }
}
