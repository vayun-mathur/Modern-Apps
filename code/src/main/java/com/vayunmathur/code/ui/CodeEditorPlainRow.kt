package com.vayunmathur.code.ui

import androidx.compose.ui.text.drawText
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import com.vayunmathur.code.domain.syntax.LanguageSpec
import com.vayunmathur.code.domain.syntax.SyntaxColors
import com.vayunmathur.code.domain.syntax.TsColorSpan
import com.vayunmathur.code.domain.Diagnostic
import com.vayunmathur.code.domain.DiagnosticSeverity
import com.vayunmathur.code.domain.FoldRegion
internal class PlainRowPainter(
    val scope: DrawScope,
    val row: Int,
    val visibleLines: List<Int>,
    val lines: List<String>,
    val lineStarts: IntArray,
    val lineHeight: Float,
    val scrollY: Float,
    val scrollX: Float,
    val measurer: TextMeasurer,
    val style: TextStyle,
    val charWidth: Float,
    val gutterWidth: Float,
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
    val showIndentGuides: Boolean,
    val tabWidth: Int,
    val guideColor: Color,
    val showWhitespace: Boolean,
    val gutterColor: Color,
) {
    val source = visibleLines[row]
    val y = row * lineHeight - scrollY
    val lineStart = lineStarts[source]
    val lineText = lines[source]
    val lineLen = lineText.length

    fun paint() {
        paintCaretLine()
        paintSelection()
        paintFindMatches()
        paintIndentGuides()
        paintWhitespace()
        paintGutter()
        paintText()
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
                topLeft = androidx.compose.ui.geometry.Offset(0f, y),
                size = Size(size.width, lineHeight),
            )
        }
    }

    private fun paintSelection() {
        if (selMax <= selMin) return
        val a = (selMin - lineStart).coerceIn(0, lineLen)
        val spansEol = selMax > lineStart + lineLen
        val b = if (spansEol) lineLen else (selMax - lineStart).coerceIn(0, lineLen)
        if (b <= a && !(selMin <= lineStart + lineLen && spansEol)) return
        val startX = gutterWidth + a * charWidth - scrollX
        val w = (b - a).coerceAtLeast(0) * charWidth + if (spansEol) charWidth else 0f
        if (w <= 0f) return
        with(scope) {
            drawRect(
                colors.match,
                topLeft = androidx.compose.ui.geometry.Offset(startX, y),
                size = Size(w, lineHeight),
            )
        }
    }

    private fun paintFindMatches() {
        // Find-match highlight (Phase 4): the active match is drawn more strongly.
        if (highlightMatches.isEmpty()) return
        for ((mi, m) in highlightMatches.withIndex()) {
            paintFindMatch(mi, m)
        }
    }

    private fun paintFindMatch(mi: Int, m: IntRange) {
        val ms = m.first
        val me = m.last + 1
        if (me <= lineStart || ms >= lineStart + lineLen) return
        val a = (ms - lineStart).coerceIn(0, lineLen)
        val b = (me - lineStart).coerceIn(0, lineLen)
        if (b <= a) return
        val startX = gutterWidth + a * charWidth - scrollX
        val w = (b - a) * charWidth
        val matchColor = if (mi == activeMatch) caretColor.copy(alpha = MATCH_ALPHA) else colors.match
        with(scope) {
            drawRect(
                matchColor,
                topLeft = androidx.compose.ui.geometry.Offset(startX, y),
                size = Size(w, lineHeight),
            )
        }
    }

    private fun paintIndentGuides() {
        if (!showIndentGuides) return
        val columns = leadingColumns(lineText, tabWidth)
        var level = tabWidth
        while (level < columns) {
            val gx = gutterWidth + level * charWidth - scrollX
            with(scope) {
                drawRect(
                    guideColor,
                    topLeft = androidx.compose.ui.geometry.Offset(gx, y),
                    size = Size(1f, lineHeight),
                )
            }
            level += tabWidth
        }
    }

    private fun paintWhitespace() {
        if (!showWhitespace) return
        var i = 0
        var col = 0
        while (i < lineText.length && (lineText[i] == ' ' || lineText[i] == '\t')) {
            val cx = gutterWidth + col * charWidth - scrollX
            if (lineText[i] == ' ') {
                with(scope) {
                    drawCircle(
                        guideColor,
                        radius = WS_DOT_RADIUS,
                        center = androidx.compose.ui.geometry.Offset(
                            cx + charWidth / HALF,
                            y + lineHeight / HALF,
                        ),
                    )
                }
                col++
            } else {
                col += tabWidth
            }
            i++
        }
    }

    private fun paintGutter() {
        // Gutter: line number and fold arrow.
        val number = measurer.measure(AnnotatedString((source + 1).toString()), style)
        with(scope) {
            drawText(
                number,
                color = gutterColor,
                topLeft = androidx.compose.ui.geometry.Offset(
                    gutterWidth - number.size.width - GUTTER_PAD,
                    y,
                ),
            )
        }
        if (!foldByHeader.containsKey(source)) return
        val arrow = if (source in foldedHeaders) FOLD_CLOSED else FOLD_OPEN
        val arrowLayout = measurer.measure(AnnotatedString(arrow), style)
        with(scope) {
            drawText(
                arrowLayout,
                color = gutterColor,
                topLeft = androidx.compose.ui.geometry.Offset(FOLD_ARROW_X, y),
            )
        }
    }

    private fun paintText() {
        // Lines past MAX_LINE_HIGHLIGHT render unstyled, so only the columns inside the
        // viewport need measuring — a one-line multi-megabyte document (a minified JSON
        // blob, say) would otherwise lay its whole line out on every frame.
        if (lineLen <= MAX_LINE_HIGHLIGHT) {
            val annotated = annotatedLine(lineText, spec, colors, tsSpans, lineStart)
            with(scope) {
                drawText(
                    measurer.measure(annotated, style),
                    topLeft = androidx.compose.ui.geometry.Offset(gutterWidth - scrollX, y),
                )
            }
            return
        }
        val firstCol = (scrollX / charWidth).toInt().coerceIn(0, lineLen)
        val lastCol = (firstCol + (scope.size.width / charWidth).toInt() + VIEWPORT_SLOP)
            .coerceAtMost(lineLen)
        if (lastCol <= firstCol) return
        val slice = measurer.measure(AnnotatedString(lineText.substring(firstCol, lastCol)), style)
        with(scope) {
            drawText(
                slice,
                topLeft = androidx.compose.ui.geometry.Offset(
                    gutterWidth + firstCol * charWidth - scrollX,
                    y,
                ),
            )
        }
    }

    private fun paintCaret() {
        if (caret !in lineStart..(lineStart + lineLen)) return
        val cx = gutterWidth + (caret - lineStart) * charWidth - scrollX
        with(scope) {
            drawRect(
                caretColor,
                topLeft = androidx.compose.ui.geometry.Offset(cx, y),
                size = Size(CARET_WIDTH, lineHeight),
            )
        }
    }

    private fun paintComposing() {
        val c = composing ?: return
        val cs = c.min
        val ce = c.max
        if (ce <= lineStart || cs >= lineStart + lineLen) return
        val a = (cs - lineStart).coerceIn(0, lineLen)
        val b = (ce - lineStart).coerceIn(0, lineLen)
        if (b <= a) return
        val ux = gutterWidth + a * charWidth - scrollX
        with(scope) {
            drawRect(
                caretColor,
                topLeft = androidx.compose.ui.geometry.Offset(ux, y + lineHeight - COMPOSE_UNDERLINE),
                size = Size((b - a) * charWidth, COMPOSE_UNDERLINE),
            )
        }
    }

    private fun paintExtraCarets() {
        for (extra in extraCarets) {
            if (extra !in lineStart..(lineStart + lineLen)) continue
            val cx = gutterWidth + (extra - lineStart) * charWidth - scrollX
            with(scope) {
                drawRect(
                    extraCaretColor,
                    topLeft = androidx.compose.ui.geometry.Offset(cx, y),
                    size = Size(CARET_WIDTH, lineHeight),
                )
            }
        }
    }

    private fun paintDiagnostics() {
        // Diagnostics: squiggly underline per range + a severity dot in the gutter.
        val diags = diagnosticsByLine[source] ?: return
        for (d in diags) paintDiagnostic(d)
        val worst = diags.minByOrNull { it.severity.ordinal } ?: return
        with(scope) {
            drawCircle(
                severityColor(worst.severity),
                radius = DIAG_DOT_RADIUS,
                center = androidx.compose.ui.geometry.Offset(DIAG_DOT_X, y + lineHeight / HALF),
            )
        }
    }

    private fun paintDiagnostic(d: Diagnostic) {
        val a = d.startCol.coerceIn(0, lineLen)
        val b = (if (d.endCol > d.startCol) d.endCol else lineLen).coerceIn(a + 1, (lineLen + 1))
        val ax = gutterWidth + a * charWidth - scrollX
        val bx = gutterWidth + b * charWidth - scrollX
        with(scope) {
            drawSquiggle(ax, bx, y + lineHeight - 1f, severityColor(d.severity))
        }
    }
}
