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
/** Largest visible-line index k with `visualStarts[k] <= visualRow` (soft-wrap row lookup). */
internal fun visualRowToVisibleIndex(visibleLines: List<Int>, visualStarts: IntArray, visualRow: Int): Int {
    if (visibleLines.isEmpty()) return 0
    var lo = 0
    var hi = visibleLines.size - 1
    while (lo < hi) {
        val mid = (lo + hi + 1) ushr 1
        if (visualStarts[mid] <= visualRow) lo = mid else hi = mid - 1
    }
    return lo
}

/** Non-wrapping draw path: one row per visible source line, monospaced columns. */
internal fun DrawScope.drawPlainLines(
    measurer: TextMeasurer,
    style: TextStyle,
    charWidth: Float,
    lineHeight: Float,
    gutterWidth: Float,
    scrollX: Float,
    scrollY: Float,
    visibleLines: List<Int>,
    lines: List<String>,
    lineStarts: IntArray,
    caret: Int,
    selMin: Int,
    selMax: Int,
    highlightMatches: List<IntRange>,
    activeMatch: Int,
    caretColor: Color,
    colors: SyntaxColors,
    spec: LanguageSpec?,
    tsSpans: List<TsColorSpan>?,
    diagnosticsByLine: Map<Int, List<Diagnostic>>,
    severityColor: (DiagnosticSeverity) -> Color,
    foldedHeaders: Set<Int>,
    foldByHeader: Map<Int, FoldRegion>,
    extraCarets: List<Int>,
    extraCaretColor: Color,
    composing: TextRange?,
    showIndentGuides: Boolean,
    tabWidth: Int,
    guideColor: Color,
    showWhitespace: Boolean,
    gutterColor: Color,
) {
    val firstRow = (scrollY / lineHeight).toInt().coerceAtLeast(0)
    val rowsInView = (size.height / lineHeight).toInt() + 2
    val lastRow = (firstRow + rowsInView).coerceAtMost(visibleLines.size - 1)

    for (row in firstRow..lastRow) {
        PlainRowPainter(
            scope = this,
            row = row,
            visibleLines = visibleLines,
            lines = lines,
            lineStarts = lineStarts,
            lineHeight = lineHeight,
            scrollY = scrollY,
            scrollX = scrollX,
            measurer = measurer,
            style = style,
            charWidth = charWidth,
            gutterWidth = gutterWidth,
            caret = caret,
            selMin = selMin,
            selMax = selMax,
            highlightMatches = highlightMatches,
            activeMatch = activeMatch,
            caretColor = caretColor,
            colors = colors,
            spec = spec,
            tsSpans = tsSpans,
            diagnosticsByLine = diagnosticsByLine,
            severityColor = severityColor,
            foldedHeaders = foldedHeaders,
            foldByHeader = foldByHeader,
            extraCarets = extraCarets,
            extraCaretColor = extraCaretColor,
            composing = composing,
            showIndentGuides = showIndentGuides,
            tabWidth = tabWidth,
            guideColor = guideColor,
            showWhitespace = showWhitespace,
            gutterColor = gutterColor,
        ).paint()
    }
}

internal const val MATCH_ALPHA = 0.35f
internal const val HALF = 2f
internal const val WS_DOT_RADIUS = 1.5f
internal const val GUTTER_PAD = 6f
internal const val FOLD_ARROW_X = 2f
internal const val FOLD_CLOSED = "▸"
internal const val FOLD_OPEN = "▾"
internal const val VIEWPORT_SLOP = 2
internal const val CARET_WIDTH = 2f
internal const val COMPOSE_UNDERLINE = 2f
internal const val DIAG_DOT_RADIUS = 3f
internal const val DIAG_DOT_X = 12f

/** Soft-wrap draw path: one measured layout per visible source line. */
internal fun DrawScope.drawWrappedLines(
    measurer: TextMeasurer,
    style: TextStyle,
    wrapConstraints: Constraints,
    lineHeight: Float,
    gutterWidth: Float,
    scrollY: Float,
    visibleLines: List<Int>,
    visualStarts: IntArray,
    totalVisualRows: Int,
    wrapCounts: IntArray,
    lines: List<String>,
    lineStarts: IntArray,
    caret: Int,
    selMin: Int,
    selMax: Int,
    highlightMatches: List<IntRange>,
    activeMatch: Int,
    caretColor: Color,
    colors: SyntaxColors,
    spec: LanguageSpec?,
    tsSpans: List<TsColorSpan>?,
    diagnosticsByLine: Map<Int, List<Diagnostic>>,
    severityColor: (DiagnosticSeverity) -> Color,
    foldedHeaders: Set<Int>,
    foldByHeader: Map<Int, FoldRegion>,
    extraCarets: List<Int>,
    extraCaretColor: Color,
    composing: TextRange?,
    gutterColor: Color,
) {
    val firstVisual = (scrollY / lineHeight).toInt().coerceAtLeast(0)
    val rowsInView = (size.height / lineHeight).toInt() + 2
    val lastVisual = (firstVisual + rowsInView).coerceAtMost((totalVisualRows - 1).coerceAtLeast(0))
    if (totalVisualRows <= 0) return
    val firstK = visualRowToVisibleIndex(visibleLines, visualStarts, firstVisual)
    val lastK = visualRowToVisibleIndex(visibleLines, visualStarts, lastVisual)
    for (k in firstK..lastK) {
        WrappedRowPainter(
            scope = this,
            k = k,
            visibleLines = visibleLines,
            visualStarts = visualStarts,
            wrapCounts = wrapCounts,
            lines = lines,
            lineStarts = lineStarts,
            lineHeight = lineHeight,
            scrollY = scrollY,
            gutterWidth = gutterWidth,
            measurer = measurer,
            style = style,
            wrapConstraints = wrapConstraints,
            caret = caret,
            selMin = selMin,
            selMax = selMax,
            highlightMatches = highlightMatches,
            activeMatch = activeMatch,
            caretColor = caretColor,
            colors = colors,
            spec = spec,
            tsSpans = tsSpans,
            diagnosticsByLine = diagnosticsByLine,
            severityColor = severityColor,
            foldedHeaders = foldedHeaders,
            foldByHeader = foldByHeader,
            extraCarets = extraCarets,
            extraCaretColor = extraCaretColor,
            composing = composing,
            gutterColor = gutterColor,
        ).paint()
    }
}

internal const val COMPOSE_PATH_ALPHA = 0.25f
internal const val DIAG_PATH_ALPHA = 0.20f
