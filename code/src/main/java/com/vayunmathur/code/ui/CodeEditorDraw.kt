package com.vayunmathur.code.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import com.vayunmathur.code.domain.syntax.LanguageSpec
import com.vayunmathur.code.domain.syntax.SyntaxColors
import com.vayunmathur.code.domain.syntax.TsColorSpan
/** Tokenizes a single line with [spec] and paints rainbow brackets; returns a colored string. */
internal fun annotatedLine(
    line: String,
    spec: LanguageSpec?,
    colors: SyntaxColors,
    tsSpans: List<TsColorSpan>? = null,
    lineStart: Int = 0,
): AnnotatedString {
    if (line.length > MAX_LINE_HIGHLIGHT) return AnnotatedString(line)
    // Tree-sitter path: paint the spans that fall on this line (over rainbow brackets).
    if (tsSpans != null && line.isNotEmpty()) {
        val builder = AnnotatedString.Builder(line)
        if (colors.brackets.isNotEmpty()) {
            var depth = 0
            val n = colors.brackets.size
            for (i in line.indices) {
                when (line[i]) {
                    '(', '[', '{' -> {
                        builder.addStyle(SpanStyle(color = colors.brackets[depth % n]), i, i + 1)
                        depth++
                    }
                    ')', ']', '}' -> {
                        depth = (depth - 1).coerceAtLeast(0)
                        builder.addStyle(SpanStyle(color = colors.brackets[depth % n]), i, i + 1)
                    }
                }
            }
        }
        val lineEnd = lineStart + line.length
        for (s in tsSpans) {
            if (s.end <= lineStart || s.start >= lineEnd) continue
            val a = (s.start - lineStart).coerceIn(0, line.length)
            val b = (s.end - lineStart).coerceIn(a, line.length)
            if (b > a) builder.addStyle(SpanStyle(color = s.color), a, b)
        }
        return builder.toAnnotatedString()
    }
    if (spec == null || line.isEmpty()) return AnnotatedString(line)
    val builder = AnnotatedString.Builder(line)
    if (colors.brackets.isNotEmpty()) {
        var depth = 0
        val n = colors.brackets.size
        for (i in line.indices) {
            when (line[i]) {
                '(', '[', '{' -> {
                    builder.addStyle(SpanStyle(color = colors.brackets[depth % n]), i, i + 1)
                    depth++
                }
                ')', ']', '}' -> {
                    depth = (depth - 1).coerceAtLeast(0)
                    builder.addStyle(SpanStyle(color = colors.brackets[depth % n]), i, i + 1)
                }
            }
        }
    }
    for (match in spec.regex.findAll(line)) {
        val kind = spec.kindFor(match) ?: continue
        builder.addStyle(SpanStyle(color = colors.colorFor(kind)), match.range.first, match.range.last + 1)
    }
    return builder.toAnnotatedString()
}

/** Leading indentation width in columns, expanding each tab to [tabWidth]. */
internal fun leadingColumns(line: String, tabWidth: Int): Int {
    var col = 0
    for (c in line) {
        when (c) {
            ' ' -> col++
            '\t' -> col += tabWidth
            else -> return col
        }
    }
    return col
}

/** The 0-based line containing [offset], by binary search over [lineStarts]. */
internal fun lineOfOffset(lineStarts: IntArray, offset: Int): Int {
    var lo = 0
    var hi = lineStarts.size - 1
    while (lo < hi) {
        val mid = (lo + hi + 1) ushr 1
        if (lineStarts[mid] <= offset) lo = mid else hi = mid - 1
    }
    return lo
}

/** Draws a zigzag ("squiggle") underline from [ax] to [bx] at baseline [yBase]. */
internal fun DrawScope.drawSquiggle(
    ax: Float,
    bx: Float,
    yBase: Float,
    color: Color,
) {
    if (bx <= ax) return
    val step = 3f
    val amp = 2f
    var x = ax
    var prev = Offset(ax, yBase)
    var up = false
    while (x < bx) {
        val nx = (x + step).coerceAtMost(bx)
        val ny = if (up) yBase - amp else yBase
        drawLine(color, prev, Offset(nx, ny), strokeWidth = 1f)
        prev = Offset(nx, ny)
        x = nx
        up = !up
    }
}

internal const val MAX_LINE_HIGHLIGHT = 2000
