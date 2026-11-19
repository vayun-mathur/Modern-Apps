package com.vayunmathur.office.data.odf

import android.util.Base64
import com.vayunmathur.library.ui.odf.OdfAnnotation
import com.vayunmathur.library.ui.odf.OdfChart
import com.vayunmathur.library.ui.odf.OdfFootnote
import com.vayunmathur.library.ui.odf.OdfImage
import com.vayunmathur.library.ui.odf.OdfParagraph
import com.vayunmathur.library.ui.odf.OdfSpan
import org.xmlpull.v1.XmlPullParser

// --- Inline content & span creation ---

internal const val CROP_LEFT = 0
internal const val CROP_TOP = 1
internal const val CROP_RIGHT = 2
internal const val CROP_BOTTOM = 3

internal fun OdfParser.makeSpan(
    text: String,
    styleName: String?,
    styles: Map<String,
    StyleInfo>,
    href: String? = null): OdfSpan {
    val resolved = resolveStyle(styleName, styles)
    return OdfSpan(
        text = text,
        bold = resolved.bold,
        italic = resolved.italic,
        fontSize = resolved.fontSize,
        fontFamily = resolved.fontFamily,
        underline = resolved.underline || href != null,
        strikethrough = resolved.strikethrough,
        color = if (href != null && resolved.color == null) LINK_COLOR else resolved.color,
        backgroundColor = resolved.backgroundColor,
        superscript = resolved.superscript,
        subscript = resolved.subscript,
        href = href,
        underlineStyle = resolved.underlineStyle,
        underlineColor = resolved.underlineColor,
        letterSpacing = resolved.letterSpacing,
        textTransform = resolved.textTransform,
        language = resolved.language,
        country = resolved.country
    )
}

/** Inline-parse accumulation state. */
internal class InlineAcc(
    val textBuffer: StringBuilder = StringBuilder(),
    var currentStyleName: String? = null,
    var currentHref: String? = null,
    var pendingFrameW: Float = 0f,
    var pendingFrameH: Float = 0f,
    var pendingFrameClip: String? = null,
    var pendingFrameStyleClip: String? = null,
    // Track-change insertion ranges (changeId -> span start index). (Priority 6)
    val changeStack: ArrayDeque<Pair<String, Int>> = ArrayDeque(),
)

/** Inline-parse extra context (avoids long parameter lists in helpers). */
private class InlineCtx(
    val styles: Map<String, StyleInfo>,
    val images: Map<String, ByteArray> = emptyMap(),
    val footnotes: MutableList<OdfFootnote>? = null,
    val imagesOut: MutableList<OdfImage>? = null,
    val objectContents: Map<String, String> = emptyMap(),
    val chartsOut: MutableList<OdfChart>? = null,
    val formulasOut: MutableList<String>? = null,
)

internal fun OdfParser.parseInlineContent(
    parser: XmlPullParser, endTag: String,
    styles: Map<String, StyleInfo>,
    images: Map<String, ByteArray> = emptyMap(),
    footnotes: MutableList<OdfFootnote>? = null,
    imagesOut: MutableList<OdfImage>? = null,
    objectContents: Map<String, String> = emptyMap(),
    chartsOut: MutableList<OdfChart>? = null,
    formulasOut: MutableList<String>? = null
): List<OdfSpan> {
    val spans = mutableListOf<OdfSpan>()
    val depth = parser.depth
    var eventType = parser.next()
    val acc = InlineAcc()
    val ctx = InlineCtx(styles, images, footnotes, imagesOut, objectContents, chartsOut, formulasOut)
    while (!(eventType == XmlPullParser.END_TAG && parser.depth == depth && parser.name == endTag)) {
        when (eventType) {
            XmlPullParser.START_TAG -> applyInlineStartTag(parser, acc, spans, ctx)
            XmlPullParser.END_TAG -> applyInlineEndTag(parser, acc, spans, ctx)
            XmlPullParser.TEXT -> acc.textBuffer.append(parser.text)
        }
        eventType = parser.next()
    }
    flushInlineBuf(acc, spans, styles)
    return spans
}

private fun OdfParser.flushInlineBuf(
    acc: InlineAcc,
    spans: MutableList<OdfSpan>,
    styles: Map<String, StyleInfo>,
) {
    if (acc.textBuffer.isNotEmpty()) {
        spans.add(makeSpan(acc.textBuffer.toString(), acc.currentStyleName, styles, acc.currentHref))
        acc.textBuffer.clear()
    }
}

private fun OdfParser.applyInlineStartTag(
    parser: XmlPullParser,
    acc: InlineAcc,
    spans: MutableList<OdfSpan>,
    ctx: InlineCtx,
) {
    if (!applyInlineStyleTag(parser, acc, spans, ctx)) {
        applyInlineMetaTag(parser, acc, spans, ctx)
    }
}

private fun OdfParser.applyInlineStyleTag(
    parser: XmlPullParser,
    acc: InlineAcc,
    spans: MutableList<OdfSpan>,
    ctx: InlineCtx,
): Boolean {
    when (parser.name) {
        "span" -> {
            flushInlineBuf(acc, spans, ctx.styles)
            acc.currentStyleName = getAttr(parser, "style-name")
        }
        "a" -> {
            flushInlineBuf(acc, spans, ctx.styles)
            acc.currentHref = getAttr(parser, "href")
        }
        "tab" -> acc.textBuffer.append("\t")
        // Cap like OdfParserSheet: a hostile text:c must not OOM the parser.
        "s" -> acc.textBuffer.append(" ".repeat((getAttr(parser, "c")?.toIntOrNull() ?: 1).coerceIn(0, MAX_SPACES)))
        "line-break" -> acc.textBuffer.append("\n")
        "frame" -> {
            acc.pendingFrameW = parseDimension(getAttr(parser, "width"))
            acc.pendingFrameH = parseDimension(getAttr(parser, "height"))
            acc.pendingFrameClip = getAttr(parser, "clip")
            acc.pendingFrameStyleClip = resolveStyle(getAttr(parser, "style-name"), ctx.styles).clip
        }
        "image" -> applyInlineImage(parser, acc, ctx.images, ctx.imagesOut)
        "object" -> applyInlineObject(parser, ctx.objectContents, ctx.chartsOut, ctx.formulasOut)
        else -> return false
    }
    return true
}

private fun OdfParser.applyInlineMetaTag(
    parser: XmlPullParser,
    acc: InlineAcc,
    spans: MutableList<OdfSpan>,
    ctx: InlineCtx,
) {
    val flush: () -> Unit = { flushInlineBuf(acc, spans, ctx.styles) }
    when (parser.name) {
        "note" -> applyInlineNote(parser, acc, ctx.styles, spans, ctx.footnotes)
        "annotation" -> applyInlineAnnotation(parser, acc, ctx.styles, spans)
        "change-start" -> applyChangeStart(parser, acc, spans, flush)
        "change-end" -> applyChangeEnd(parser, acc, spans, flush)
        "change" -> applyChangePoint(parser, spans, flush)
        "reference-ref", "bookmark-ref" -> applyReferenceRef(parser, acc, ctx.styles, spans)
        "reference-mark", "reference-mark-start", "reference-mark-end" ->
            addZeroWidthSpan(parser, acc, ctx.styles, spans)
        "bookmark", "bookmark-start", "bookmark-end" ->
            addZeroWidthSpan(parser, acc, ctx.styles, spans)
        "title", "desc" -> applyTitleDesc(parser, acc, ctx.styles, spans, ctx.imagesOut)
        in FIELD_TAGS -> applyInlineField(parser, acc, ctx.styles, spans)
    }
}

private fun OdfParser.addZeroWidthSpan(
    parser: XmlPullParser,
    acc: InlineAcc,
    styles: Map<String, StyleInfo>,
    spans: MutableList<OdfSpan>,
) {
    flushInlineBuf(acc, spans, styles)
    spans.add(OdfSpan(text = "", refKind = parser.name, refName = getAttr(parser, "name")))
}

private fun OdfParser.applyInlineField(
    parser: XmlPullParser,
    acc: InlineAcc,
    styles: Map<String, StyleInfo>,
    spans: MutableList<OdfSpan>,
) {
    // ODF text field elements -> a span carrying the field kind + cached value. (Priority 2)
    flushInlineBuf(acc, spans, styles)
    val kind = parser.name
    val fb = readInlineFieldBody(parser)
    spans.add(makeSpan(fb, acc.currentStyleName, styles, acc.currentHref).copy(field = kind))
}

private fun OdfParser.readInlineFieldBody(parser: XmlPullParser): String {
    val d = parser.depth
    val fb = StringBuilder()
    var ev = parser.next()
    while (!(ev == XmlPullParser.END_TAG && parser.depth == d)) {
        if (ev == XmlPullParser.TEXT) fb.append(parser.text)
        if (ev == XmlPullParser.END_DOCUMENT) break
        ev = parser.next()
    }
    return fb.toString()
}

private fun OdfParser.applyInlineEndTag(
    parser: XmlPullParser,
    acc: InlineAcc,
    spans: MutableList<OdfSpan>,
    ctx: InlineCtx,
) {
    when (parser.name) {
        "span" -> {
            flushInlineBuf(acc, spans, ctx.styles)
            acc.currentStyleName = null
        }
        "a" -> {
            flushInlineBuf(acc, spans, ctx.styles)
            acc.currentHref = null
        }
    }
}

/** Track-change start: push the span index. */
private fun OdfParser.applyChangeStart(
    parser: XmlPullParser,
    acc: InlineAcc,
    spans: MutableList<OdfSpan>,
    flushBuf: () -> Unit,
) {
    flushBuf()
    getAttr(parser, "change-id")?.let { acc.changeStack.addLast(it to spans.size) }
}

/** Track-change end: mark spans as insertion. */
private fun OdfParser.applyChangeEnd(
    parser: XmlPullParser,
    acc: InlineAcc,
    spans: MutableList<OdfSpan>,
    flushBuf: () -> Unit,
) {
    flushBuf()
    val id = getAttr(parser, "change-id")
    val idx = acc.changeStack.indexOfLast { it.first == id }
    if (idx >= 0) {
        val (cid, start) = acc.changeStack.removeAt(idx)
        for (i in start until spans.size) spans[i] = spans[i].copy(
            changeKind = "insertion",
            changeId = cid)
    }
}

/** Deletion point: pull deleted text from the tracked-changes region. (Priority 6) */
private fun OdfParser.applyChangePoint(
    parser: XmlPullParser,
    spans: MutableList<OdfSpan>,
    flushBuf: () -> Unit,
) {
    flushBuf()
    getAttr(parser, "change-id")?.let { id ->
        spans.add(OdfSpan(text = trackedDeletionText[id] ?: "", changeKind = "deletion", changeId = id))
    }
}

/** Cross-reference to a reference-mark/bookmark. (Priority 5) */
private fun OdfParser.applyReferenceRef(
    parser: XmlPullParser,
    acc: InlineAcc,
    styles: Map<String, StyleInfo>,
    spans: MutableList<OdfSpan>,
) {
    if (acc.textBuffer.isNotEmpty()) {
        spans.add(makeSpan(acc.textBuffer.toString(), acc.currentStyleName, styles, acc.currentHref))
        acc.textBuffer.clear()
    }
    val kind = parser.name
    val refName = getAttr(parser, "ref-name")
    val refFormat = getAttr(parser, "reference-format")
    val d = parser.depth; val fb = StringBuilder(); var ev = parser.next()
    while (!(ev == XmlPullParser.END_TAG && parser.depth == d)) {
        if (ev == XmlPullParser.TEXT) fb.append(parser.text)
        if (ev == XmlPullParser.END_DOCUMENT) break
        ev = parser.next()
    }
    spans.add(makeSpan(fb.toString(), acc.currentStyleName, styles, acc.currentHref)
        .copy(refKind = kind, refName = refName, refFormat = refFormat, color = LINK_COLOR))
}

/** svg:title/desc alt text or text field. */
private fun OdfParser.applyTitleDesc(
    parser: XmlPullParser,
    acc: InlineAcc,
    styles: Map<String, StyleInfo>,
    spans: MutableList<OdfSpan>,
    imagesOut: MutableList<OdfImage>?,
) {
    // svg:title/svg:desc inside a draw:frame are accessibility alt text (attach to
    // the frame's image), NOT text fields. text:title remains a real field. Either way
    // consume the whole subtree so its text never leaks into the paragraph. (alt-text fix)
    val isSvg = parser.namespace?.contains("svg") == true
    val isField = !isSvg && parser.name in FIELD_TAGS
    if (acc.textBuffer.isNotEmpty() && (isSvg || isField)) {
        spans.add(makeSpan(acc.textBuffer.toString(), acc.currentStyleName, styles, acc.currentHref))
        acc.textBuffer.clear()
    }
    val kind = parser.name
    val raw = readInlineFieldBody(parser)
    applyTitleDescResult(kind, raw, raw.trim(), isSvg, isField, acc, styles, spans, imagesOut)
}

private fun OdfParser.applyTitleDescResult(
    kind: String,
    raw: String,
    trimmed: String,
    isSvg: Boolean,
    isField: Boolean,
    acc: InlineAcc,
    styles: Map<String, StyleInfo>,
    spans: MutableList<OdfSpan>,
    imagesOut: MutableList<OdfImage>?,
) {
    when {
        isSvg -> attachSvgAltText(kind, trimmed, imagesOut)
        isField -> spans.add(
            makeSpan(
                raw,
                acc.currentStyleName,
                styles,
                acc.currentHref
            ).copy(field = kind)
        )
    }
}

private fun attachSvgAltText(kind: String, t: String, imagesOut: MutableList<OdfImage>?) {
    if (t.isEmpty() || imagesOut == null || imagesOut.isEmpty()) return
    val idx = imagesOut.size - 1
    imagesOut[idx] = if (kind == "title") imagesOut[idx].copy(altTitle = t)
    else imagesOut[idx].copy(altDesc = t)
}

// --- Footnotes ---

internal fun OdfParser.parseFootnote(parser: XmlPullParser, styles: Map<String, StyleInfo>): OdfFootnote? {
    val isEndnote = getAttr(parser, "note-class") == "endnote"
    var citation = ""
    val body = mutableListOf<OdfParagraph>()
    val depth = parser.depth
    var eventType = parser.next()

    while (!(eventType == XmlPullParser.END_TAG && parser.depth == depth)) {
        if (eventType == XmlPullParser.START_TAG) {
            when (parser.name) {
                "note-citation" -> citation = readNoteCitation(parser)
                "note-body" -> readNoteBody(parser, styles, body)
            }
        }
        eventType = parser.next()
    }
    return if (citation.isNotEmpty()) OdfFootnote(citation, body, isEndnote) else null
}

/** Citation text of a footnote. */
private fun OdfParser.readNoteCitation(parser: XmlPullParser): String {
    val citDepth = parser.depth
    var citEvent = parser.next()
    val sb = StringBuilder()
    while (!(citEvent == XmlPullParser.END_TAG && parser.depth == citDepth)) {
        if (citEvent == XmlPullParser.TEXT) sb.append(parser.text)
        citEvent = parser.next()
    }
    return sb.toString().trim()
}

/** Body paragraphs of a footnote. */
private fun OdfParser.readNoteBody(
    parser: XmlPullParser,
    styles: Map<String, StyleInfo>,
    body: MutableList<OdfParagraph>,
) {
    val bodyDepth = parser.depth
    var bodyEvent = parser.next()
    while (!(bodyEvent == XmlPullParser.END_TAG && parser.depth == bodyDepth)) {
        if (bodyEvent == XmlPullParser.START_TAG && parser.name == "p") {
            val spans = parseInlineContent(parser, "p", styles)
            if (spans.isNotEmpty()) body.add(OdfParagraph(spans))
        }
        bodyEvent = parser.next()
    }
}

// --- Annotations ---

internal fun OdfParser.parseAnnotation(parser: XmlPullParser, styles: Map<String, StyleInfo>): OdfAnnotation? {
    var author: String? = null
    var date: String? = null
    val paragraphs = mutableListOf<OdfParagraph>()
    val depth = parser.depth
    var eventType = parser.next()
    var currentTag = ""

    while (!(eventType == XmlPullParser.END_TAG && parser.depth == depth)) {
        when (eventType) {
            XmlPullParser.START_TAG -> {
                currentTag = parser.name
                if (parser.name == "p" && parser.namespace?.contains("text") == true) {
                    val spans = parseInlineContent(parser, "p", styles)
                    if (spans.isNotEmpty()) paragraphs.add(OdfParagraph(spans))
                    currentTag = ""
                }
            }
            XmlPullParser.TEXT -> {
                val text = parser.text.trim()
                if (text.isNotEmpty()) when (currentTag) {
                    "creator" -> author = text
                    "date" -> date = text
                }
            }
            XmlPullParser.END_TAG -> currentTag = ""
        }
        eventType = parser.next()
    }
    return OdfAnnotation(author, date, paragraphs)
}
