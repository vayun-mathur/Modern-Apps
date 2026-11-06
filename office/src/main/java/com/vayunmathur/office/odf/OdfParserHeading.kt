package com.vayunmathur.office.odf

import com.vayunmathur.library.ui.odf.OdfBookmark
import com.vayunmathur.library.ui.odf.OdfChart
import com.vayunmathur.library.ui.odf.OdfContentBlock
import com.vayunmathur.library.ui.odf.OdfDocument
import com.vayunmathur.library.ui.odf.OdfFootnote
import com.vayunmathur.library.ui.odf.OdfImage
import com.vayunmathur.library.ui.odf.ListType
import com.vayunmathur.library.ui.odf.OdfParagraph
import com.vayunmathur.library.ui.odf.OdfSpan
import com.vayunmathur.library.ui.odf.ParagraphStyle
import com.vayunmathur.library.ui.odf.formatListNumber
import org.xmlpull.v1.XmlPullParser

/** Heading branch (split from OdfParserText.kt for file length). */

internal fun OdfParser.parseHeading(
    parser: XmlPullParser,
    styles: Map<String, StyleInfo>,
    images: Map<String, ByteArray>,
    footnotes: MutableList<OdfFootnote>,
    objectContents: Map<String, String>,
    content: MutableList<OdfContentBlock>,
    listDepth: Int,
    listStyleStack: MutableList<ListStyleInfo?>,
    listItemCounter: MutableList<Int>,
    outlineStyle: ListStyleInfo?,
    outlineCounters: IntArray,
) {
    val level = getAttr(parser, "outline-level")?.toIntOrNull() ?: 1
    val styleName = getAttr(parser, "style-name")
    val resolved = resolveStyle(styleName, styles)
    if (resolved.breakBefore == "page") content.add(OdfContentBlock.PageBreak)
    val collected = collectHeadingInline(parser, styles, images, footnotes, objectContents)
    val para = buildHeadingPara(
        level, collected.spans, resolved, listDepth, listStyleStack,
        listItemCounter, outlineStyle, outlineCounters)
    content.add(OdfContentBlock.Paragraph(para))
    emitInlineBlocks(content, collected)
    if (resolved.breakAfter == "page") content.add(OdfContentBlock.PageBreak)
}

/** Inline spans + media collected from a heading element. */
private class HeadingInline(
    val spans: List<OdfSpan>,
    val images: List<OdfImage>,
    val charts: List<OdfChart>,
    val formulas: List<String>,
)

/** Reads spans + inline media from a heading element. */
private fun OdfParser.collectHeadingInline(
    parser: XmlPullParser,
    styles: Map<String, StyleInfo>,
    images: Map<String, ByteArray>,
    footnotes: MutableList<OdfFootnote>,
    objectContents: Map<String, String>,
): HeadingInline {
    val inlineImages = mutableListOf<OdfImage>()
    val inlineCharts = mutableListOf<OdfChart>()
    val inlineFormulas = mutableListOf<String>()
    val spans = parseInlineContent(
        parser,
        "h",
        styles,
        images,
        footnotes,
        inlineImages,
        objectContents,
        inlineCharts,
        inlineFormulas)
    return HeadingInline(spans, inlineImages, inlineCharts, inlineFormulas)
}

/** Builds the heading paragraph model. */
private fun OdfParser.buildHeadingPara(
    level: Int,
    spans: List<OdfSpan>,
    resolved: StyleInfo,
    listDepth: Int,
    listStyleStack: MutableList<ListStyleInfo?>,
    listItemCounter: MutableList<Int>,
    outlineStyle: ListStyleInfo?,
    outlineCounters: IntArray,
): OdfParagraph {
    val paraStyle = headingStyleFor(level)
    val hLevelStyle = if (listDepth > 0) listStyleStack.lastOrNull()?.levelStyle(listDepth) else null
    val outlineNum = computeOutlineNumber(outlineStyle, level, outlineCounters)
    return OdfParagraph(
        spans = spans, style = paraStyle,
        alignment = resolved.textAlign, marginLeft = resolved.marginLeft,
        marginTop = resolved.marginTop, marginBottom = resolved.marginBottom,
        textIndent = resolved.textIndent ?: 0f, backgroundColor = resolved.paragraphBackgroundColor,
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
        listLevel = if (listDepth > 0) listDepth else 0,
        listType = if (hLevelStyle?.numbered == true) ListType.NUMBERED else ListType.BULLET,
        listItemIndex = if (listItemCounter.isNotEmpty()) listItemCounter.last() else 0,
        listNumberFormat = hLevelStyle?.numberFormat ?: "1",
        listBulletChar = hLevelStyle?.bulletChar ?: "•",
        listNumberPrefix = hLevelStyle?.prefix ?: "",
        listNumberSuffix = hLevelStyle?.suffix ?: ".",
        outlineNumber = outlineNum
    )
}

/** Paragraph style for an outline level. */
private fun headingStyleFor(level: Int): ParagraphStyle = when (level) {
    1 -> ParagraphStyle.HEADING1
    2 -> ParagraphStyle.HEADING2
    HEADING_LEVEL_3 -> ParagraphStyle.HEADING3
    else -> ParagraphStyle.HEADING4
}

/** Automatic outline heading numbering (text:outline-style). (Round 3) */
private fun OdfParser.computeOutlineNumber(
    outlineStyle: ListStyleInfo?,
    level: Int,
    outlineCounters: IntArray,
): String? {
    val os = outlineStyle ?: return null
    val lvlStyle = os.levels[level] ?: return null
    if (lvlStyle.numbered != true) return null
    outlineCounters[level]++
    if (outlineCounters[level] < lvlStyle.startValue) outlineCounters[level] = lvlStyle.startValue
    for (i in level + 1..MAX_OUTLINE_LEVEL) outlineCounters[i] = 0
    val d = lvlStyle.displayLevels.coerceIn(1, level)
    return ((level - d + 1)..level).joinToString(".") { lv ->
        formatListNumber(
            outlineCounters[lv].coerceAtLeast(1),
            os.levels[lv]?.numberFormat ?: "1")
    } + lvlStyle.suffix
}

/** Emits inline media/formula blocks collected from a paragraph. */
private fun emitInlineBlocks(content: MutableList<OdfContentBlock>, collected: HeadingInline) {
    for (img in collected.images) content.add(OdfContentBlock.Image(img))
    for (ch in collected.charts) content.add(OdfContentBlock.Chart(ch))
    for (f in collected.formulas) content.add(formulaBlock(f))
}
