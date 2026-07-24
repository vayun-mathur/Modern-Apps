package com.vayunmathur.email.composer

import android.graphics.drawable.Drawable
import android.text.Spanned
import com.vayunmathur.library.ui.IndentSpan
import com.vayunmathur.library.ui.OrderedListSpan
import android.text.style.BulletSpan
import android.text.style.ImageSpan
import android.text.style.StrikethroughSpan
import android.text.style.StyleSpan
import android.text.style.URLSpan
import android.text.style.UnderlineSpan
import android.graphics.Typeface
import kotlin.math.min

/**
 * Serialize a Spanned (from HtmlEditor) to HTML, emitting <img src="cid:...">
 * for [CidImageSpan] and preserving bold/italic/underline/strike/links/lists/indent.
 */

fun serializeEmailHtml(spanned: Spanned): String {
    if (spanned.isEmpty()) return ""
    val len = spanned.length
    val out = StringBuilder()

    // Paragraph walk to emit <ul>/<ol> etc. Similar logic to library serializeRich
    // but with CID image support and inline style detection.
    // For simplicity we will first build paragraph boundaries then handle list grouping.

    data class Para(val start: Int, val end: Int)

    fun paras(): List<Para> {
        val list = mutableListOf<Para>()
        var pos = 0
        val text = spanned.toString()
        while (pos < len) {
            val nl = text.indexOf('\n', pos).let { if (it < 0) len else it }
            list.add(Para(pos, nl))
            pos = nl + 1
        }
        if (list.isEmpty() && len == 0) return emptyList()
        // keep trailing empty handled as <br> later
        return list
    }

    val allParas = paras()
    var currentList: String? = null
    var orderedCounter = 0

    fun closeList() {
        if (currentList != null) {
            out.append("</${currentList}>")
            currentList = null
            orderedCounter = 0
        }
    }

    fun hasBullet(p: Para): Boolean {
        return spanned.getSpans(p.start, p.end, BulletSpan::class.java).any {
            spanned.getSpanStart(it) < p.end && spanned.getSpanEnd(it) > p.start
        }
    }

    fun orderedSpan(p: Para): OrderedListSpan? {
        return spanned.getSpans(p.start, p.end, OrderedListSpan::class.java).firstOrNull {
            spanned.getSpanStart(it) < p.end && spanned.getSpanEnd(it) > p.start
        }
    }

    fun indentLevel(p: Para): Int {
        return spanned.getSpans(p.start, p.end, IndentSpan::class.java).firstOrNull {
            spanned.getSpanStart(it) < p.end && spanned.getSpanEnd(it) > p.start
        }?.level ?: 0
    }

    fun inlineHtmlFor(p: Para): String {
        if (p.start >= p.end) return ""
        return buildInlineHtml(spanned, p.start, p.end)
    }

    for (para in allParas) {
        if (para.start >= para.end) {
            closeList()
            val lvl = indentLevel(para)
            if (lvl > 0) out.append("<div style=\"margin-left: ${lvl * 24}px\"><br></div>")
            else out.append("<br>")
            continue
        }

        val bullet = hasBullet(para)
        val oSpan = orderedSpan(para)
        val indentLvl = indentLevel(para)
        val rawInline = inlineHtmlFor(para)
        val inner = if (rawInline.isBlank()) "<br>" else rawInline
        val contentWithIndent = if (indentLvl > 0 && !bullet && oSpan == null) {
            "<div style=\"margin-left: ${indentLvl * 24}px\">$inner</div>"
        } else if (indentLvl > 0) {
            "<div style=\"margin-left: ${indentLvl * 24}px\">$inner</div>"
        } else inner

        when {
            bullet -> {
                if (currentList != "ul") {
                    closeList()
                    out.append("<ul>")
                    currentList = "ul"
                }
                out.append("<li>$contentWithIndent</li>")
            }
            oSpan != null -> {
                if (currentList != "ol") {
                    closeList()
                    out.append("<ol>")
                    currentList = "ol"
                    orderedCounter = 0
                }
                orderedCounter++
                out.append("<li>$contentWithIndent</li>")
            }
            else -> {
                closeList()
                if (indentLvl > 0) {
                    out.append(contentWithIndent)
                } else {
                    out.append("<div>$inner</div>")
                }
            }
        }
    }
    closeList()
    val result = out.toString()
    return result.ifBlank { "<div><br></div>" }
}

private fun buildInlineHtml(spanned: Spanned, start: Int, end: Int): String {
    // Walk character by character emitting tags when spans change.
    // We collect events: open/close of StyleSpan bold/italic, underline, strike, link, and CidImageSpan placeholder.
    val sb = StringBuilder()
    // For each char position, determine active spans
    // We'll build runs where the set of spans is constant
    var i = start
    while (i < end) {
        val char = spanned[i]
        // Check if this char is covered by CidImageSpan — emit <img> and skip obj replacement char
        val cidSpans = spanned.getSpans(i, i + 1, CidImageSpan::class.java).filter {
            spanned.getSpanStart(it) <= i && spanned.getSpanEnd(it) > i
        }
        if (cidSpans.isNotEmpty()) {
            val cs = cidSpans.first()
            sb.append("<img src=\"cid:${cs.cid}\">")
            // advance past this span's range
            val spanEnd = min(spanned.getSpanEnd(cs), end)
            i = spanEnd
            continue
        }
        // Regular object replacement char for image? Skip if it's the generic placeholder
        if (char == '\uFFFC') {
            // If no cid span found (fallback generic ImageSpan), emit generic placeholder maybe skip
            // Just skip
            i++
            continue
        }
        // Find run length where inline style set stays same
        val nextChange = findNextSpanBoundary(spanned, i, end)
        val slice = spanned.subSequence(i, nextChange).toString()
        // Encode and wrap with tags based on current spans at i
        var piece = android.text.TextUtils.htmlEncode(slice)
        // Link outermost, then bold/italic/underline/strike
        val urlSpan = spanned.getSpans(i, i + 1, URLSpan::class.java).firstOrNull {
            spanned.getSpanStart(it) <= i && spanned.getSpanEnd(it) > i
        }
        if (urlSpan != null) {
            piece = "<a href=\"${escapeAttr(urlSpan.url ?: "")}\">$piece</a>"
        }
        val bold = spanned.getSpans(i, i + 1, StyleSpan::class.java).any {
            spanned.getSpanStart(it) <= i && spanned.getSpanEnd(it) > i && (it.style and Typeface.BOLD) != 0
        }
        if (bold) piece = "<b>$piece</b>"
        val italic = spanned.getSpans(i, i + 1, StyleSpan::class.java).any {
            spanned.getSpanStart(it) <= i && spanned.getSpanEnd(it) > i && (it.style and Typeface.ITALIC) != 0
        }
        if (italic) piece = "<i>$piece</i>"
        val ul = spanned.getSpans(i, i + 1, UnderlineSpan::class.java).any {
            spanned.getSpanStart(it) <= i && spanned.getSpanEnd(it) > i
        }
        if (ul) piece = "<u>$piece</u>"
        val strike = spanned.getSpans(i, i + 1, StrikethroughSpan::class.java).any {
            spanned.getSpanStart(it) <= i && spanned.getSpanEnd(it) > i
        }
        if (strike) piece = "<s>$piece</s>"

        sb.append(piece)
        i = nextChange
    }
    // Replace newlines inside slice shouldn't exist (paras split), but handle \n -> <br>
    return sb.toString().replace("\n", "<br>")
}

private fun findNextSpanBoundary(spanned: Spanned, from: Int, end: Int): Int {
    var next = end
    val spans = spanned.getSpans(from, end, Any::class.java)
    for (sp in spans) {
        val s = spanned.getSpanStart(sp)
        val e = spanned.getSpanEnd(sp)
        if (s > from && s < next) next = s
        if (e > from && e < next) next = e
    }
    // Also consider ImageSpan boundaries
    // Ensure at least advance 1
    if (next == from) next = from + 1
    return min(next, end)
}

private fun escapeAttr(value: String): String {
    return value.replace("&", "&amp;").replace("\"", "&quot;").replace("<", "&lt;").replace(">", "&gt;")
}
