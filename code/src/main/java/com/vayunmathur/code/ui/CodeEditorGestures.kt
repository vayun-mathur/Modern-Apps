package com.vayunmathur.code.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Constraints

/** Tap → text offset on the monospaced grid. */
internal fun offsetAt(
    x: Float,
    y: Float,
    scrollY: Float,
    scrollX: Float,
    lineHeight: Float,
    charWidth: Float,
    gutterWidth: Float,
    visibleLines: List<Int>,
    lines: List<String>,
    lineStarts: IntArray,
    textLength: Int,
): Int {
    val row = ((y + scrollY) / lineHeight).toInt()
    val source = visibleLines.getOrNull(row.coerceIn(0, (visibleLines.size - 1).coerceAtLeast(0))) ?: 0
    val col = (((x + scrollX) - gutterWidth) / charWidth).toInt().coerceIn(0, lines[source].length)
    return (lineStarts[source] + col).coerceIn(0, textLength)
}

/** Tap → text offset when soft-wrapping, using the tapped source line's wrapped layout. */
internal fun offsetAtWrapped(
    x: Float,
    y: Float,
    scrollY: Float,
    lineHeight: Float,
    gutterWidth: Float,
    visibleLines: List<Int>,
    visualStarts: IntArray,
    totalVisualRows: Int,
    lines: List<String>,
    lineStarts: IntArray,
    textLength: Int,
    measurer: TextMeasurer,
    style: TextStyle,
    wrapConstraints: Constraints,
    spec: com.vayunmathur.code.domain.syntax.LanguageSpec?,
    colors: com.vayunmathur.code.domain.syntax.SyntaxColors,
): Int {
    if (visibleLines.isEmpty()) return 0
    val visualRow = ((y + scrollY) / lineHeight).toInt().coerceIn(0, (totalVisualRows - 1).coerceAtLeast(0))
    val k = visualRowToVisibleIndex(visibleLines, visualStarts, visualRow)
    val source = visibleLines[k]
    val sub = (visualRow - visualStarts[k]).coerceAtLeast(0)
    val layout = measurer.measure(annotatedLine(lines[source], spec, colors), style, constraints = wrapConstraints)
    val localY = sub * lineHeight + lineHeight / 2f
    val localX = (x - gutterWidth).coerceAtLeast(0f)
    val local = runCatching { layout.getOffsetForPosition(Offset(localX, localY)) }
        .getOrDefault(0).coerceIn(0, lines[source].length)
    return (lineStarts[source] + local).coerceIn(0, textLength)
}

/** Largest visible-line index k with `visualStarts[k] <= visualRow` (plain row lookup). */
internal fun displayRowToSource(visibleLines: List<Int>, row: Int): Int? = visibleLines.getOrNull(row)
