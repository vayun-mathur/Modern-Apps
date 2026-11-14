package com.vayunmathur.library.ui

import android.graphics.Canvas
import android.graphics.Paint
import android.text.Editable
import android.text.Layout
import android.text.style.LeadingMarginSpan

/** Leading-margin span that draws an ordered-list number in the margin. */
class OrderedListSpan(var number: Int, private val gapWidth: Int = 48) : LeadingMarginSpan {
    override fun getLeadingMargin(first: Boolean): Int = gapWidth
    override fun drawLeadingMargin(
        c: Canvas, p: Paint, x: Int, dir: Int,
        top: Int, baseline: Int, bottom: Int,
        text: CharSequence, start: Int, end: Int,
        first: Boolean, layout: Layout?,
    ) {
        if (!first) return
        val oldStyle = p.style
        p.style = Paint.Style.FILL
        val numText = "$number."
        val width = p.measureText(numText)
        val xPos = (x + gapWidth - width - 8).coerceAtLeast(x.toFloat())
        c.drawText(numText, xPos, baseline.toFloat(), p)
        p.style = oldStyle
    }
}

/** Leading-margin span indenting a paragraph by [level] steps. */
class IndentSpan(val level: Int) : LeadingMarginSpan.Standard(level * INDENT_PER_LEVEL) {
    /** Pixels of indent per [IndentSpan] level. */
    companion object {
        const val INDENT_PER_LEVEL = 48
        const val MAX_LEVEL = 6
    }
}

fun Editable.forEachParagraph(selStartIn: Int, selEndIn: Int, action: (Int, Int) -> Unit) {
    val len = length
    if (len == 0) return
    val rawStart = minOf(selStartIn, selEndIn).coerceIn(0, len)
    val rawEnd = maxOf(selStartIn, selEndIn).coerceIn(0, len)
    val blockStart = lastIndexOf('\n', (rawStart - 1).coerceAtLeast(0)).let { if (it < 0) 0 else it + 1 }
    val blockEnd = indexOf('\n', rawEnd).let { if (it < 0) len else it }
    var pos = blockStart
    while (pos <= blockEnd && pos < len) {
        val nextNl = indexOf('\n', pos).let { if (it < 0) len else it }
        if (nextNl > pos || pos < len) action(pos, nextNl)
        pos = nextNl + 1
        if (pos > blockEnd && nextNl == len) break
        if (pos > len) break
    }
}

fun Editable.paragraphsAll(): List<Pair<Int, Int>> {
    val list = mutableListOf<Pair<Int, Int>>()
    var pos = 0
    while (pos < length) {
        val next = indexOf('\n', pos).let { if (it < 0) length else it }
        list.add(pos to next)
        pos = next + 1
    }
    if (list.isEmpty() && length == 0) list.add(0 to 0)
    if (length > 0 && this[length - 1] == '\n') list.add(length to length)
    return list
}
