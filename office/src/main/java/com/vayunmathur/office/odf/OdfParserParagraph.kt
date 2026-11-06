package com.vayunmathur.office.odf

import com.vayunmathur.library.ui.odf.OdfBookmark
import com.vayunmathur.library.ui.odf.OdfChart
import com.vayunmathur.library.ui.odf.OdfContentBlock
import com.vayunmathur.library.ui.odf.OdfDocument
import com.vayunmathur.library.ui.odf.OdfFootnote
import com.vayunmathur.library.ui.odf.OdfImage
import com.vayunmathur.library.ui.odf.OdfParagraph
import com.vayunmathur.library.ui.odf.OdfSpan
import com.vayunmathur.library.ui.odf.ListType
import com.vayunmathur.library.ui.odf.ParagraphStyle
import org.xmlpull.v1.XmlPullParser

/** Body paragraph branch (split from OdfParserText.kt for file length). */

internal fun OdfParser.parseBodyParagraph(
    parser: XmlPullParser,
    styles: Map<String, StyleInfo>,
    images: Map<String, ByteArray>,
    footnotes: MutableList<OdfFootnote>,
    objectContents: Map<String, String>,
    content: MutableList<OdfContentBlock>,
    listDepth: Int,
    listStyleStack: MutableList<ListStyleInfo?>,
    listItemCounter: MutableList<Int>,
    listTypeStack: MutableList<ListType>,
    pendingListItemChecked: Boolean,
) {
    val styleName = getAttr(parser, "style-name")
    val resolved = resolveStyle(styleName, styles)
    if (resolved.breakBefore == "page") content.add(OdfContentBlock.PageBreak)
    val collected = collectBodyInline(parser, styles, images, footnotes, objectContents)
    if (collected.spans.isNotEmpty() || listDepth > 0) {
        content.add(OdfContentBlock.Paragraph(buildBodyPara(
            collected.spans, resolved, listDepth, listStyleStack,
            listItemCounter, listTypeStack, pendingListItemChecked)))
    }
    emitBodyBlocks(content, collected)
    if (resolved.breakAfter == "page") content.add(OdfContentBlock.PageBreak)
}

/** Inline spans + media collected from a body paragraph. */
private class BodyInline(
    val spans: List<OdfSpan>,
    val images: List<OdfImage>,
    val charts: List<OdfChart>,
    val formulas: List<String>,
)

/** Reads spans + inline media from a body paragraph. */
private fun OdfParser.collectBodyInline(
    parser: XmlPullParser,
    styles: Map<String, StyleInfo>,
    images: Map<String, ByteArray>,
    footnotes: MutableList<OdfFootnote>,
    objectContents: Map<String, String>,
): BodyInline {
    val inlineImages = mutableListOf<OdfImage>()
    val inlineCharts = mutableListOf<OdfChart>()
    val inlineFormulas = mutableListOf<String>()
    val spans = parseInlineContent(
        parser,
        "p",
        styles,
        images,
        footnotes,
        inlineImages,
        objectContents,
        inlineCharts,
        inlineFormulas)
    return BodyInline(spans, inlineImages, inlineCharts, inlineFormulas)
}

/** Builds the body paragraph model. */
private fun OdfParser.buildBodyPara(
    spans: List<OdfSpan>,
    resolved: StyleInfo,
    listDepth: Int,
    listStyleStack: MutableList<ListStyleInfo?>,
    listItemCounter: MutableList<Int>,
    listTypeStack: MutableList<ListType>,
    pendingListItemChecked: Boolean,
): OdfParagraph {
    val style = if (listDepth > 0) ParagraphStyle.LIST_ITEM else ParagraphStyle.BODY
    val itemIdx = if (listItemCounter.isNotEmpty()) listItemCounter.last() else 0
    val levelStyle = listStyleStack.lastOrNull()?.levelStyle(listDepth)
    return OdfParagraph(
        spans = spans, style = style,
        alignment = resolved.textAlign, marginLeft = resolved.marginLeft,
        marginTop = resolved.marginTop, marginBottom = resolved.marginBottom,
        textIndent = resolved.textIndent ?: 0f, backgroundColor = resolved.paragraphBackgroundColor,
        listLevel = listDepth,
        listType = resolveBodyListType(levelStyle, listTypeStack),
        listItemIndex = itemIdx,
        listChecked = pendingListItemChecked,
        direction = parseDirection(resolved.writingMode),
        lineHeightPercent = resolved.lineHeightPercent,
        borderColor = resolved.paragraphBorderColor,
        borders = resolved.paraBorders,
        marginRight = resolved.marginRight,
        keepWithNext = resolved.keepWithNext,
        keepTogether = resolved.keepTogether,
        widows = resolved.widows,
        orphans = resolved.orphans,
        tabStops = resolved.tabStops,
        tabStopDetails = resolved.tabStopDetails,
        dropCapLines = resolved.dropCapLines,
        dropCapLength = resolved.dropCapLength,
        padding = resolved.padding,
        listNumberFormat = levelStyle?.numberFormat ?: "1",
        listBulletChar = levelStyle?.bulletChar ?: "•",
        listNumberPrefix = levelStyle?.prefix ?: "",
        listNumberSuffix = levelStyle?.suffix ?: "."
    )
}

/** List type for a body paragraph. */
private fun resolveBodyListType(
    levelStyle: ListLevelStyle?,
    listTypeStack: MutableList<ListType>,
): ListType = when {
    levelStyle?.checkbox == true -> ListType.CHECKBOX
    levelStyle != null -> if (levelStyle.numbered) ListType.NUMBERED else ListType.BULLET
    listTypeStack.isNotEmpty() -> listTypeStack.last()
    else -> ListType.BULLET
}

/** Emits inline media/formula blocks collected from a body paragraph. */
private fun emitBodyBlocks(content: MutableList<OdfContentBlock>, collected: BodyInline) {
    for (img in collected.images) content.add(OdfContentBlock.Image(img))
    for (ch in collected.charts) content.add(OdfContentBlock.Chart(ch))
    for (f in collected.formulas) content.add(formulaBlock(f))
}
