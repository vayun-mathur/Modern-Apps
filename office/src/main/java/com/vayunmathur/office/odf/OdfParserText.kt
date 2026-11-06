package com.vayunmathur.office.odf

import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.LayoutDirection
import com.vayunmathur.library.ui.odf.ListType
import com.vayunmathur.library.ui.odf.OdfBookmark
import com.vayunmathur.library.ui.odf.OdfChange
import com.vayunmathur.library.ui.odf.OdfChart
import com.vayunmathur.library.ui.odf.OdfContentBlock
import com.vayunmathur.library.ui.odf.OdfDocument
import com.vayunmathur.library.ui.odf.OdfFootnote
import com.vayunmathur.library.ui.odf.OdfImage
import com.vayunmathur.library.ui.odf.OdfMetadata
import com.vayunmathur.library.ui.odf.OdfPageSetup
import com.vayunmathur.library.ui.odf.OdfParagraph
import com.vayunmathur.library.ui.odf.OdfSpan
import com.vayunmathur.library.ui.odf.OdfTable
import com.vayunmathur.library.ui.odf.OdfTableCell
import com.vayunmathur.library.ui.odf.OdfTableColumn
import com.vayunmathur.library.ui.odf.OdfTableRow
import com.vayunmathur.library.ui.odf.ParagraphStyle
import com.vayunmathur.library.ui.odf.formatListNumber
import org.xmlpull.v1.XmlPullParser

// --- Text Document ---

private const val MAX_REPEATED = 100
internal const val MAX_OUTLINE_LEVEL = 10
internal const val HEADING_LEVEL_3 = 3
private const val OUTLINE_COUNTER_SIZE = 11

/** Text-document parse state. */
private class TextDocAcc(
    val content: MutableList<OdfContentBlock> = mutableListOf(),
    val footnotes: MutableList<OdfFootnote> = mutableListOf(),
    val bookmarks: MutableList<OdfBookmark> = mutableListOf(),
    val changes: MutableList<OdfChange> = mutableListOf(),
    var inBody: Boolean = false,
    var listDepth: Int = 0,
    val listTypeStack: MutableList<ListType> = mutableListOf(),
    val listItemCounter: MutableList<Int> = mutableListOf(),
    val listStyleStack: MutableList<ListStyleInfo?> = mutableListOf(),
    val listEndByDepth: HashMap<Int, Int> = HashMap(),
    val listEndById: HashMap<String, Int> = HashMap(),
    val listIdStack: MutableList<String?> = mutableListOf(),
    var pendingListItemChecked: Boolean = false,
    var outlineStyle: ListStyleInfo? = null,
    val outlineCounters: IntArray = IntArray(OUTLINE_COUNTER_SIZE),
)

internal fun OdfParser.parseTextDocument(
    xml: String, styles: Map<String, StyleInfo>, listStyles: Map<String, ListStyleInfo>,
    title: String, metadata: OdfMetadata, images: Map<String, ByteArray>,
    headerFooter: HeaderFooterResult?, objectContents: Map<String, String> = emptyMap(),
    pageSetup: OdfPageSetup? = null
): OdfDocument.TextDocument {
    trackedDeletionText = emptyMap()
    val parser = newParser(xml)
    val acc = TextDocAcc(outlineStyle = listStyles["%outline%"])
    // Word (and LibreOffice) export one <text:list> per item and chain them with
    // text:continue-numbering / text:continue-list; without honoring those every item restarts
    // at 1. Remember where each list left off, by nesting depth and by xml:id. (bugfix)
    var eventType = parser.eventType
    val ctx = TextDocCtx(styles, images, listStyles, objectContents)

    while (eventType != XmlPullParser.END_DOCUMENT) {
        when (eventType) {
            XmlPullParser.START_TAG -> applyTextDocStartTag(parser, acc, ctx)
            XmlPullParser.END_TAG -> applyTextDocEndTag(parser, acc)
        }
        eventType = parser.next()
    }
    return OdfDocument.TextDocument(title, acc.content, metadata, images, acc.footnotes,
        headerParagraphs = headerFooter?.headerParagraphs ?: emptyList(),
        footerParagraphs = headerFooter?.footerParagraphs ?: emptyList(),
        bookmarks = acc.bookmarks, changes = acc.changes, pageSetup = pageSetup)
}

/** Text-doc shared context (avoids long parameter lists). */
private class TextDocCtx(
    val styles: Map<String, StyleInfo>,
    val images: Map<String, ByteArray>,
    val listStyles: Map<String, ListStyleInfo>,
    val objectContents: Map<String, String>,
)

private fun OdfParser.applyTextDocStartTag(
    parser: XmlPullParser,
    acc: TextDocAcc,
    ctx: TextDocCtx,
) {
    when {
        parser.name == "text" && parser.namespace?.contains("office") == true -> acc.inBody = true
        !acc.inBody -> return
        parser.name == "tracked-changes" -> {
            val (parsed, delText) = parseTrackedChanges(parser)
            acc.changes.addAll(parsed)
            trackedDeletionText = delText
        }
        parser.name == "bookmark" || parser.name == "bookmark-start" -> {
            getAttr(parser, "name")?.let { acc.bookmarks.add(OdfBookmark(it, acc.content.size)) }
        }
        parser.name == "h" -> parseHeading(
            parser, ctx.styles, ctx.images, acc.footnotes, ctx.objectContents, acc.content,
            acc.listDepth, acc.listStyleStack, acc.listItemCounter,
            acc.outlineStyle, acc.outlineCounters)
        parser.name == "p" -> parseBodyParagraph(
            parser, ctx.styles, ctx.images, acc.footnotes, ctx.objectContents, acc.content,
            acc.listDepth, acc.listStyleStack, acc.listItemCounter, acc.listTypeStack,
            acc.pendingListItemChecked)
        else -> applyTextDocBlockTag(parser, acc, ctx)
    }
}

private fun OdfParser.applyTextDocBlockTag(
    parser: XmlPullParser,
    acc: TextDocAcc,
    ctx: TextDocCtx,
) {
    when (parser.name) {
        "section" -> {
            val nm = getAttr(parser, "name") ?: "Section"
            val cols = resolveStyle(getAttr(parser, "style-name"), ctx.styles).columnCount
            acc.content.add(OdfContentBlock.SectionStart(nm, cols))
        }
        "list" -> startTextDocList(parser, acc, ctx)
        "list-item" -> startTextDocListItem(parser, acc)
        "table" -> if (parser.namespace?.contains("table") == true) {
            acc.content.add(OdfContentBlock.Table(parseTextTable(parser, ctx.styles)))
        }
        "table-of-content" -> parseTocContent(parser, ctx.styles, acc.content)
        "frame" -> if (parser.namespace?.contains("draw") == true) {
            val frame = parseSingleFrame(parser, ctx.styles, ctx.images)
            frame.image?.let { acc.content.add(OdfContentBlock.Image(it)) }
            for (para in frame.paragraphs) {
                acc.content.add(OdfContentBlock.Paragraph(para))
            }
        }
    }
}

private fun OdfParser.startTextDocList(
    parser: XmlPullParser,
    acc: TextDocAcc,
    ctx: TextDocCtx,
) {
    acc.listDepth++
    val styleName = getAttr(parser, "style-name")
    val styleInfo = when {
        styleName != null -> ctx.listStyles[styleName] ?: acc.listStyleStack.lastOrNull()
        else -> acc.listStyleStack.lastOrNull()
    }
    val type = when {
        styleInfo != null ->
            if (styleInfo.levelStyle(acc.listDepth).numbered) ListType.NUMBERED else ListType.BULLET
        acc.listTypeStack.isNotEmpty() -> acc.listTypeStack.last()
        else -> ListType.BULLET
    }
    acc.listTypeStack.add(type)
    // Honor text:start-value so numbered lists can begin at N (not always 1),
    // and text:continue-numbering / text:continue-list so a list that continues
    // a previous one picks up where that one stopped instead of restarting.
    val startValue = styleInfo?.levelStyle(acc.listDepth)?.startValue ?: 1
    val continueList = getAttr(parser, "continue-list")
    val continued = when {
        continueList != null -> acc.listEndById[continueList] ?: acc.listEndByDepth[acc.listDepth]
        getAttr(parser, "continue-numbering") == "true" -> acc.listEndByDepth[acc.listDepth]
        else -> null
    }
    acc.listItemCounter.add((continued ?: (startValue - 1)).coerceAtLeast(0))
    acc.listStyleStack.add(styleInfo)
    acc.listIdStack.add(getAttr(parser, "id"))
}

private fun OdfParser.startTextDocListItem(parser: XmlPullParser, acc: TextDocAcc) {
    if (acc.listItemCounter.isNotEmpty()) {
        val startValue = getAttr(parser, "start-value")?.toIntOrNull()
        acc.listItemCounter[acc.listItemCounter.size - 1] =
            startValue ?: (acc.listItemCounter.last() + 1)
    }
    acc.pendingListItemChecked = getAttr(
        parser,
        "checkbox-status")?.let { it == "checked" || it == "true" } ?: false
}

private fun OdfParser.applyTextDocEndTag(parser: XmlPullParser, acc: TextDocAcc) {
    when {
        parser.name == "text" && parser.namespace?.contains("office") == true -> acc.inBody = false
        parser.name == "list" && acc.inBody -> endTextDocList(acc)
        parser.name == "section" && acc.inBody -> acc.content.add(OdfContentBlock.SectionEnd)
    }
}

private fun endTextDocList(acc: TextDocAcc) {
    val endedDepth = acc.listDepth
    acc.listDepth--
    if (acc.listTypeStack.isNotEmpty()) acc.listTypeStack.removeAt(acc.listTypeStack.size - 1)
    if (acc.listItemCounter.isNotEmpty()) {
        // Remember the final number so a following continue-numbering list resumes here.
        val ended = acc.listItemCounter.removeAt(acc.listItemCounter.size - 1)
        acc.listEndByDepth[endedDepth] = ended
        acc.listIdStack.lastOrNull()?.let { acc.listEndById[it] = ended }
    }
    if (acc.listIdStack.isNotEmpty()) acc.listIdStack.removeAt(acc.listIdStack.size - 1)
    if (acc.listStyleStack.isNotEmpty()) acc.listStyleStack.removeAt(acc.listStyleStack.size - 1)
}

/** Centered italic formula paragraph for an inline formula string. */
internal fun formulaBlock(formula: String): OdfContentBlock = if (formula.contains("math")) {
    OdfContentBlock.Formula(formula)
} else {
    val span = OdfSpan(text = formula, italic = true)
    val para = OdfParagraph(listOf(span), alignment = TextAlign.Center)
    OdfContentBlock.Paragraph(para)
}

internal fun OdfParser.parseDirection(writingMode: String?): LayoutDirection? = when {
    writingMode == null -> null
    writingMode.startsWith("rl") -> LayoutDirection.Rtl
    writingMode.startsWith("lr") -> LayoutDirection.Ltr
    else -> null
}

// --- Tables in text documents ---

internal fun OdfParser.parseTextTable(parser: XmlPullParser, styles: Map<String, StyleInfo>): OdfTable {
    val tableName = getAttr(parser, "name") ?: ""
    val columns = mutableListOf<OdfTableColumn>()
    val rows = mutableListOf<OdfTableRow>()
    var headerRowCount = 0
    var inHeader = false
    val depth = parser.depth
    var eventType = parser.next()

    while (!(eventType == XmlPullParser.END_TAG && parser.depth == depth)) {
        if (eventType == XmlPullParser.START_TAG) when (parser.name) {
            "table-column" -> {
                val repeated = getAttr(parser, "number-columns-repeated")?.toIntOrNull() ?: 1
                val styleName = getAttr(parser, "style-name")
                val width = resolveStyle(styleName, styles).columnWidth
                repeat(repeated.coerceAtMost(MAX_REPEATED)) { columns.add(OdfTableColumn(width = width)) }
            }
            "table-row" -> { rows.add(OdfTableRow(parseTableCells(parser, styles))); if (inHeader) headerRowCount++ }
            "table-header-rows" -> inHeader = true
        } else if (eventType == XmlPullParser.END_TAG && parser.name == "table-header-rows") {
            inHeader = false
        }
        eventType = parser.next()
    }
    return OdfTable(tableName, columns, rows, headerRowCount)
}

internal fun OdfParser.parseTableCells(parser: XmlPullParser, styles: Map<String, StyleInfo>): List<OdfTableCell> {
    val cells = mutableListOf<OdfTableCell>()
    val depth = parser.depth
    var eventType = parser.next()

    while (!(eventType == XmlPullParser.END_TAG && parser.depth == depth)) {
        if (eventType == XmlPullParser.START_TAG) when (parser.name) {
            "table-cell" -> {
                val colSpan = getAttr(parser, "number-columns-spanned")?.toIntOrNull() ?: 1
                val rowSpan = getAttr(parser, "number-rows-spanned")?.toIntOrNull() ?: 1
                val repeated = getAttr(parser, "number-columns-repeated")?.toIntOrNull() ?: 1
                val styleName = getAttr(parser, "style-name")
                val resolved = resolveStyle(styleName, styles)
                val formula = getAttr(parser, "formula")
                val paragraphs = parseTableCellContent(parser, styles)
                repeat(repeated.coerceAtMost(MAX_REPEATED)) {
                    cells.add(OdfTableCell(
                        paragraphs = paragraphs,
                        colSpan = colSpan, rowSpan = rowSpan,
                        backgroundColor = resolved.cellBackgroundColor,
                        borderColor = resolved.cellBorderColor,
                        formula = formula,
                        verticalAlign = resolved.cellVerticalAlign
                    ))
                }
            }
            "covered-table-cell" -> {
                val repeated = getAttr(parser, "number-columns-repeated")?.toIntOrNull() ?: 1
                repeat(repeated.coerceAtMost(MAX_REPEATED)) { cells.add(OdfTableCell(isCovered = true)) }
                skipElement(parser)
            }
        }
        eventType = parser.next()
    }
    return cells
}

internal fun OdfParser.parseTableCellContent(
    parser: XmlPullParser,
    styles: Map<String,
    StyleInfo>): List<OdfParagraph> {
    val paragraphs = mutableListOf<OdfParagraph>()
    val depth = parser.depth
    var eventType = parser.next()
    while (!(eventType == XmlPullParser.END_TAG && parser.depth == depth)) {
        if (eventType == XmlPullParser.START_TAG && parser.name == "p") {
            val spans = parseInlineContent(parser, "p", styles)
            paragraphs.add(OdfParagraph(spans))
        }
        eventType = parser.next()
    }
    return paragraphs
}

// --- TOC ---

internal fun OdfParser.parseTocContent(
    parser: XmlPullParser,
    styles: Map<String,
    StyleInfo>,
    content: MutableList<OdfContentBlock>) {
    val acc = TocAcc()
    val depth = parser.depth
    var eventType = parser.next()

    while (!(eventType == XmlPullParser.END_TAG && parser.depth == depth)) {
        when {
            eventType == XmlPullParser.START_TAG && parser.name == "index-body" -> acc.inIndexBody = true
            eventType == XmlPullParser.END_TAG && parser.name == "index-body" -> acc.inIndexBody = false
            else -> applyTocTag(parser, eventType, acc, styles)
        }
        eventType = parser.next()
    }
    content.add(OdfContentBlock.TableOfContents(acc.title, acc.entries))
}

/** TOC parse state. */
private class TocAcc(
    var inIndexBody: Boolean = false,
    var inIndexTitle: Boolean = false,
    var title: String = "Table of Contents",
    val entries: MutableList<OdfParagraph> = mutableListOf(),
)

private fun OdfParser.applyTocTag(
    parser: XmlPullParser,
    eventType: Int,
    acc: TocAcc,
    styles: Map<String, StyleInfo>,
) {
    when {
        eventType == XmlPullParser.START_TAG && parser.name == "index-title" -> acc.inIndexTitle = true
        eventType == XmlPullParser.END_TAG && parser.name == "index-title" -> acc.inIndexTitle = false
        eventType == XmlPullParser.START_TAG && parser.name == "p" && acc.inIndexBody -> {
            val spans = parseInlineContent(parser, "p", styles)
            val text = spans.joinToString("") { it.text }.trim()
            if (acc.inIndexTitle) {
                if (text.isNotEmpty()) acc.title = text
            } else if (spans.isNotEmpty()) {
                acc.entries.add(OdfParagraph(spans))
            }
        }
    }
}

/** Parses text:tracked-changes into change metadata + a map of deletion text by change-id. (Priority 6) */
internal fun OdfParser.parseTrackedChanges(parser: XmlPullParser): Pair<List<OdfChange>, Map<String, String>> {
    val acc = TrackedChangesAcc()
    // Capture the depth BEFORE advancing: the loop exits at the END_TAG matching
    // the element we were positioned on entry (text:tracked-changes).
    val depth = parser.depth
    var ev = parser.next()
    while (!(ev == XmlPullParser.END_TAG && parser.depth == depth)) {
        when (ev) {
            XmlPullParser.START_TAG -> applyTrackedStart(parser, acc)
            XmlPullParser.END_TAG -> applyTrackedEnd(parser, acc)
        }
        ev = parser.next()
    }
    return acc.list to acc.delText
}

/** Tracked-changes parse state. */
private class TrackedChangesAcc(
    val list: MutableList<OdfChange> = mutableListOf(),
    val delText: HashMap<String, String> = HashMap(),
    var curId: String? = null,
    var curType: String? = null,
    var author: String? = null,
    var date: String? = null,
    val delBuf: StringBuilder = StringBuilder(),
    var inDeletion: Boolean = false,
) {
    fun flush() {
        val id = curId ?: return
        val type = curType ?: "insertion"
        list.add(OdfChange(id, type, author, date))
        if (type == "deletion") delText[id] = delBuf.toString()
    }
}

private fun OdfParser.applyTrackedStart(parser: XmlPullParser, acc: TrackedChangesAcc) {
    when (parser.name) {
        "changed-region" -> {
            acc.curId = getAttr(parser, "id")
            acc.curType = null
            acc.author = null
            acc.date = null
            acc.delBuf.clear()
            acc.inDeletion = false
        }
        "insertion" -> acc.curType = "insertion"
        "deletion" -> {
            acc.curType = "deletion"
            acc.inDeletion = true
        }
        else -> applyTrackedContentTag(parser, acc)
    }
}

private fun OdfParser.applyTrackedContentTag(parser: XmlPullParser, acc: TrackedChangesAcc) {
    when (parser.name) {
        "format-change" -> acc.curType = "format-change"
        "creator" -> acc.author = collectTrackedText(parser).trim()
        "date" -> acc.date = collectTrackedText(parser).trim()
        "p" -> if (acc.inDeletion) {
            if (acc.delBuf.isNotEmpty()) acc.delBuf.append("\n")
            acc.delBuf.append(collectTrackedText(parser))
        }
    }
}

private fun OdfParser.applyTrackedEnd(parser: XmlPullParser, acc: TrackedChangesAcc) {
    when (parser.name) {
        "changed-region" -> { acc.flush(); acc.curId = null }
        "deletion" -> acc.inDeletion = false
    }
}

private fun OdfParser.collectTrackedText(parser: XmlPullParser): String {
    val d = parser.depth
    val sb = StringBuilder()
    var ev = parser.next()
    while (!(ev == XmlPullParser.END_TAG && parser.depth == d)) {
        if (ev == XmlPullParser.TEXT) sb.append(parser.text)
        if (ev == XmlPullParser.END_DOCUMENT) break
        ev = parser.next()
    }
    return sb.toString()
}
