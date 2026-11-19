package com.vayunmathur.library.ui.odf

import android.util.Base64

/**
 * Pure, Android-light converter between HTML and an [OdfDocument.TextDocument]. Import is tolerant
 * (best-effort tag handling, entity decoding), export produces clean semantic HTML. Covers
 * headings, paragraphs, bold/italic/underline/strike/color/size/font, links, bullet/numbered
 * lists (with nesting), tables, blockquotes, horizontal rules, and inline data-URI images.
 */
object HtmlOdfConverter {

    // region ODF -> HTML (see HtmlOdfExport)

    fun odfToHtml(doc: OdfDocument.TextDocument): String = HtmlOdfExport.odfToHtml(doc)

    // endregion

    // region HTML -> ODF

    fun htmlToOdf(html: String, title: String = ""): OdfDocument.TextDocument {
        val blocks = ArrayList<OdfContentBlock>()
        val parser = HtmlParser(html, blocks)
        parser.run()
        if (blocks.isEmpty()) blocks.add(OdfContentBlock.Paragraph(OdfParagraph(listOf(OdfSpan("")))))
        return OdfDocument.TextDocument(title = title, content = blocks)
    }

    /** A tolerant streaming HTML reader that materializes paragraphs/tables/images into [out]. */
    private class HtmlParser(private val html: String, private val out: MutableList<OdfContentBlock>) {
        private var pos = 0
        private val styleStack = ArrayDeque<OdfSpan>().apply { addLast(OdfSpan("")) }
        private var curSpans = ArrayList<OdfSpan>()
        private var curStyle: ParagraphStyle = ParagraphStyle.BODY
        private var listType: ListType? = null
        private var listLevel = 0
        /** Running item number per open list level, so <ol> items count 1, 2, 3 … (bugfix) */
        private val listCounters = ArrayList<Int>()
        private var curListIndex = 0
        private var align: androidx.compose.ui.text.style.TextAlign? = null
        private var pendingHref: String? = null

        fun run() {
            while (pos < html.length) {
                val lt = html.indexOf('<', pos)
                if (lt < 0) { appendText(html.substring(pos)); break }
                if (lt > pos) appendText(html.substring(pos, lt))
                val gt = html.indexOf('>', lt)
                if (gt < 0) break
                val tag = html.substring(lt + 1, gt).trim()
                pos = gt + 1               // advance first; handlers (skip/table) may move pos further
                handleTag(tag)
            }
            flushParagraph()
        }

        private fun appendText(raw: String) {
            val text = decodeEntities(raw).replace(Regex("\\s+"), " ")
            if (text.isEmpty()) return
            if (text.isBlank() && curSpans.isEmpty()) return
            curSpans.add(styleStack.last().copy(text = text, href = pendingHref))
        }

        private fun handleTag(tagRaw: String) {
            if (tagRaw.startsWith("!") || tagRaw.startsWith("?")) return
            val closing = tagRaw.startsWith("/")
            val body = tagRaw.removePrefix("/")
            val name = body.takeWhile { !it.isWhitespace() && it != '/' }.lowercase()
            val attrs = if (!closing) parseAttrs(body) else emptyMap()
            when (name) {
                "b", "strong" -> pushPop(closing) { it.copy(bold = true) }
                "i", "em" -> pushPop(closing) { it.copy(italic = true) }
                "u" -> pushPop(closing) { it.copy(underline = true) }
                "s", "strike", "del" -> pushPop(closing) { it.copy(strikethrough = true) }
                "sup" -> pushPop(closing) { it.copy(superscript = true) }
                "sub" -> pushPop(closing) { it.copy(subscript = true) }
                "span", "font" -> if (closing) popStyle() else styleStack.addLast(applyInlineStyle(styleStack.last(), attrs))
                "a" -> if (closing) { pendingHref = null; popStyle() } else { pendingHref = attrs["href"]; styleStack.addLast(styleStack.last().copy(underline = true, color = 0xFF0066CC)) }
                "br" -> if (curSpans.isNotEmpty()) curSpans.add(styleStack.last().copy(text = "\n"))
                "p", "div" -> if (closing) flushParagraph() else { flushParagraph(); align = textAlign(attrs["style"]) }
                "h1", "h2", "h3", "h4", "h5", "h6" -> if (closing) flushParagraph() else { flushParagraph(); curStyle = heading(name) }
                "blockquote" -> if (closing) flushParagraph() else flushParagraph()
                "hr" -> { flushParagraph(); out.add(OdfContentBlock.Paragraph(OdfParagraph(listOf(OdfSpan("")), borderColor = 0xFF888888))) }
                "ul" -> if (closing) closeList() else { listLevel++; listType = ListType.BULLET; listCounters.add(0) }
                "ol" -> if (closing) closeList() else {
                    listLevel++; listType = ListType.NUMBERED
                    listCounters.add((attrs["start"]?.toIntOrNull() ?: 1) - 1)
                }
                "li" -> if (closing) flushListItem() else {
                    flushParagraph()
                    curStyle = ParagraphStyle.LIST_ITEM
                    curListIndex = attrs["value"]?.toIntOrNull() ?: ((listCounters.lastOrNull() ?: 0) + 1)
                    if (listCounters.isNotEmpty()) listCounters[listCounters.size - 1] = curListIndex
                }
                "img" -> emitImage(attrs)
                "table" -> parseTable()
                "style", "script", "head" -> skipUntilClose(name)
                else -> {}
            }
        }

        private inline fun pushPop(closing: Boolean, crossinline f: (OdfSpan) -> OdfSpan) {
            if (closing) popStyle() else styleStack.addLast(f(styleStack.last()))
        }
        private fun popStyle() { if (styleStack.size > 1) styleStack.removeLast() }

        private fun closeList() {
            listLevel--
            if (listCounters.isNotEmpty()) listCounters.removeAt(listCounters.size - 1)
            if (listLevel <= 0) {
                listType = null
                // Text after the list is body text, not one more (unnumbered) item. (bugfix)
                if (curSpans.isEmpty() && curStyle == ParagraphStyle.LIST_ITEM) curStyle = ParagraphStyle.BODY
                curListIndex = 0
            }
        }

        private fun flushParagraph() {
            if (curSpans.isEmpty()) { align = null; if (curStyle != ParagraphStyle.LIST_ITEM) curStyle = ParagraphStyle.BODY; return }
            out.add(OdfContentBlock.Paragraph(OdfParagraph(
                spans = coalesce(curSpans),
                style = curStyle,
                alignment = align,
                listType = listType ?: ListType.BULLET,
                listLevel = if (curStyle == ParagraphStyle.LIST_ITEM) listLevel.coerceAtLeast(1) else 0,
                listItemIndex = if (curStyle == ParagraphStyle.LIST_ITEM) curListIndex.coerceAtLeast(1) else 0
            )))
            curSpans = ArrayList()
            curStyle = if (listType != null) ParagraphStyle.LIST_ITEM else ParagraphStyle.BODY
            align = null
        }

        private fun flushListItem() {
            if (curSpans.isEmpty()) { curStyle = ParagraphStyle.LIST_ITEM; return }
            curStyle = ParagraphStyle.LIST_ITEM
            flushParagraph()
        }

        private fun emitImage(attrs: Map<String, String>) {
            val src = attrs["src"] ?: return
            if (!src.startsWith("data:")) return
            val comma = src.indexOf(','); if (comma < 0) return
            val bytes = try { Base64.decode(src.substring(comma + 1), Base64.DEFAULT) } catch (_: Exception) { return }
            val ext = when { src.contains("jpeg") -> "jpg"; src.contains("gif") -> "gif"; else -> "png" }
            flushParagraph()
            out.add(OdfContentBlock.Image(OdfImage("media/img${out.size}.$ext", bytes,
                width = attrs["width"]?.toFloatOrNull() ?: 0f, height = attrs["height"]?.toFloatOrNull() ?: 0f,
                altDesc = attrs["alt"])))
        }

        private fun parseTable() {
            // Find matching </table> and parse rows/cells with a nested reader.
            val end = indexOfClose("table")
            val inner = if (end > 0) html.substring(pos, end) else html.substring(pos)
            val rows = ArrayList<OdfTableRow>()
            var headerRows = 0
            val rowRe = Regex("<tr\\b[^>]*>(.*?)</tr>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
            val cellRe = Regex("<(td|th)\\b([^>]*)>(.*?)</\\1>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
            for (rm in rowRe.findAll(inner)) {
                val cells = ArrayList<OdfTableCell>()
                var anyHeader = false
                for (cm in cellRe.findAll(rm.groupValues[1])) {
                    if (cm.groupValues[1].equals("th", true)) anyHeader = true
                    val cattrs = parseAttrs(cm.groupValues[1] + cm.groupValues[2])
                    val text = decodeEntities(cm.groupValues[3].replace(Regex("<[^>]+>"), " ")).trim().replace(Regex("\\s+"), " ")
                    cells.add(OdfTableCell(
                        paragraphs = listOf(OdfParagraph(listOf(OdfSpan(text)))),
                        colSpan = cattrs["colspan"]?.toIntOrNull() ?: 1,
                        rowSpan = cattrs["rowspan"]?.toIntOrNull() ?: 1
                    ))
                }
                if (cells.isNotEmpty()) { rows.add(OdfTableRow(cells)); if (anyHeader && rows.size == headerRows + 1) headerRows++ }
            }
            if (rows.isNotEmpty()) { flushParagraph(); out.add(OdfContentBlock.Table(OdfTable(rows = rows, headerRowCount = headerRows))) }
            if (end > 0) pos = html.indexOf('>', end) + 1
        }

        private fun indexOfClose(tag: String): Int {
            val m = Regex("</$tag\\s*>", RegexOption.IGNORE_CASE).find(html, pos)
            return m?.range?.first ?: -1
        }

        private fun skipUntilClose(tag: String) {
            val end = indexOfClose(tag)
            if (end > 0) pos = html.indexOf('>', end) + 1
        }

        private fun heading(name: String): ParagraphStyle = when (name) {
            "h1" -> ParagraphStyle.HEADING1; "h2" -> ParagraphStyle.HEADING2
            "h3" -> ParagraphStyle.HEADING3; else -> ParagraphStyle.HEADING4
        }

        private fun applyInlineStyle(base: OdfSpan, attrs: Map<String, String>): OdfSpan {
            var s = base
            val style = attrs["style"] ?: ""
            Regex("color\\s*:\\s*#?([0-9a-fA-F]{6})").find(style)?.let { s = s.copy(color = 0xFF000000L or it.groupValues[1].toLong(16)) }
            Regex("font-size\\s*:\\s*([0-9.]+)\\s*(pt|px)").find(style)?.let { m ->
                // [0-9.]+ also matches "." / "1.2.3" in malformed HTML; toFloat() would throw.
                val v = m.groupValues[1].toFloatOrNull() ?: return@let
                s = s.copy(fontSize = if (m.groupValues[2] == "px") v * 0.75f else v)
            }
            if (style.contains("font-weight") && (style.contains("bold") || Regex("font-weight\\s*:\\s*[6-9]00").containsMatchIn(style))) s = s.copy(bold = true)
            if (Regex("font-style\\s*:\\s*italic").containsMatchIn(style)) s = s.copy(italic = true)
            if (style.contains("line-through")) s = s.copy(strikethrough = true)
            if (Regex("text-decoration[^;]*underline").containsMatchIn(style)) s = s.copy(underline = true)
            attrs["color"]?.let { c -> parseNamedOrHex(c)?.let { s = s.copy(color = it) } }
            return s
        }

        private fun textAlign(style: String?): androidx.compose.ui.text.style.TextAlign? {
            style ?: return null
            return when (Regex("text-align\\s*:\\s*(\\w+)").find(style)?.groupValues?.get(1)) {
                "center" -> androidx.compose.ui.text.style.TextAlign.Center
                "right" -> androidx.compose.ui.text.style.TextAlign.End
                "justify" -> androidx.compose.ui.text.style.TextAlign.Justify
                "left" -> androidx.compose.ui.text.style.TextAlign.Start
                else -> null
            }
        }

        private fun parseNamedOrHex(v: String): Long? {
            val t = v.trim()
            if (t.startsWith("#") && t.length == 7) return runCatching { 0xFF000000L or t.substring(1).toLong(16) }.getOrNull()
            return when (t.lowercase()) { "red" -> 0xFFFF0000; "green" -> 0xFF008000; "blue" -> 0xFF0000FF; "black" -> 0xFF000000; else -> null }
        }
    }

    private fun parseAttrs(tagBody: String): Map<String, String> {
        val map = HashMap<String, String>()
        val re = Regex("([a-zA-Z_:-]+)\\s*=\\s*(\"([^\"]*)\"|'([^']*)'|([^\\s>]+))")
        for (m in re.findAll(tagBody)) {
            val key = m.groupValues[1].lowercase()
            val value = m.groupValues[3].ifEmpty { m.groupValues[4].ifEmpty { m.groupValues[5] } }
            map[key] = decodeEntities(value)
        }
        return map
    }

    private fun coalesce(spans: List<OdfSpan>): List<OdfSpan> {
        if (spans.isEmpty()) return listOf(OdfSpan(""))
        val out = ArrayList<OdfSpan>()
        var cur = spans[0]; val sb = StringBuilder(cur.text)
        for (k in 1 until spans.size) {
            val s = spans[k]
            if (s.copy(text = "") == cur.copy(text = "")) sb.append(s.text)
            else { out.add(cur.copy(text = sb.toString())); cur = s; sb.setLength(0); sb.append(s.text) }
        }
        out.add(cur.copy(text = sb.toString()))
        return out
    }

    private fun decodeEntities(s: String): String {
        if (!s.contains('&')) return s
        return s.replace("&nbsp;", " ").replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
            .replace("&quot;", "\"").replace("&#39;", "'").replace("&apos;", "'")
            .replace(Regex("&#(\\d+);")) { m -> m.groupValues[1].toIntOrNull()?.let { cp -> runCatching { String(Character.toChars(cp)) }.getOrNull() } ?: m.value }
            .replace(Regex("&#x([0-9a-fA-F]+);")) { m -> m.groupValues[1].toIntOrNull(16)?.let { cp -> runCatching { String(Character.toChars(cp)) }.getOrNull() } ?: m.value }
    }

    // endregion
}
