package com.vayunmathur.office.data.odf

import com.vayunmathur.library.ui.odf.OdfMetadata
import com.vayunmathur.library.ui.odf.OdfPageSetup
import com.vayunmathur.library.ui.odf.OdfParagraph
import org.xmlpull.v1.XmlPullParser

// --- Metadata parsing ---

internal fun OdfParser.parseMetadata(xml: String): OdfMetadata {
    val parser = newParser(xml)
    val acc = MetadataAcc()
    var eventType = parser.eventType

    while (eventType != XmlPullParser.END_DOCUMENT) {
        when (eventType) {
            XmlPullParser.START_TAG -> applyMetadataStart(parser, acc)
            XmlPullParser.TEXT -> applyMetadataText(parser, acc)
            XmlPullParser.END_TAG -> acc.currentTag = ""
        }
        eventType = parser.next()
    }
    return OdfMetadata(
        acc.title,
        acc.creator ?: acc.initialCreator,
        acc.initialCreator,
        acc.creationDate,
        acc.modifiedDate,
        acc.description,
        acc.subject,
        acc.keywords,
        acc.pageCount,
        acc.wordCount,
        generator = acc.generator,
        editingCycles = acc.editingCycles,
        charCount = acc.charCount,
        paragraphCount = acc.paragraphCount,
        userDefined = acc.userDefined,
        userDefinedTypes = acc.userDefinedTypes,
    )
}

/** Metadata accumulation state. */
private class MetadataAcc(
    var title: String? = null,
    var creator: String? = null,
    var initialCreator: String? = null,
    var creationDate: String? = null,
    var modifiedDate: String? = null,
    var description: String? = null,
    var subject: String? = null,
    val keywords: MutableList<String> = mutableListOf(),
    var pageCount: Int? = null,
    var wordCount: Int? = null,
    var charCount: Int? = null,
    var paragraphCount: Int? = null,
    var generator: String? = null,
    var editingCycles: Int? = null,
    val userDefined: LinkedHashMap<String, String> = LinkedHashMap(),
    val userDefinedTypes: LinkedHashMap<String, String> = LinkedHashMap(),
    var udName: String? = null,
    var udType: String? = null,
    var currentTag: String = "",
)

/** Apply one metadata start tag. */
private fun OdfParser.applyMetadataStart(parser: XmlPullParser, acc: MetadataAcc) {
    acc.currentTag = parser.name
    if (parser.name == "document-statistic") {
        acc.pageCount = getAttr(parser, "page-count")?.toIntOrNull()
        acc.wordCount = getAttr(parser, "word-count")?.toIntOrNull()
        acc.charCount = getAttr(parser, "character-count")?.toIntOrNull()
        acc.paragraphCount = getAttr(parser, "paragraph-count")?.toIntOrNull()
    }
    if (parser.name == "user-defined") {
        acc.udName = getAttr(
            parser,
            "name")
        acc.udType = getAttr(parser, "value-type")
    }
}

/** Apply one metadata text node. */
private fun applyMetadataText(parser: XmlPullParser, acc: MetadataAcc) {
    val text = parser.text.trim()
    if (text.isEmpty()) return
    applyMetadataTextCore(acc, text)
    applyMetadataTextExtra(acc, text)
}

private fun applyMetadataTextCore(acc: MetadataAcc, text: String) {
    when (acc.currentTag) {
        "title" -> acc.title = text
        "creator" -> acc.creator = text
        "initial-creator" -> acc.initialCreator = text
        "creation-date" -> acc.creationDate = text
        "date" -> acc.modifiedDate = text
        "description" -> acc.description = text
        "subject" -> acc.subject = text
    }
}

private fun applyMetadataTextExtra(acc: MetadataAcc, text: String) {
    when (acc.currentTag) {
        "keyword" -> acc.keywords.add(text)
        "generator" -> acc.generator = text
        "editing-cycles" -> acc.editingCycles = text.toIntOrNull()
        "user-defined" ->
            acc.udName?.let {
                acc.userDefined[it] = text
                if (acc.udType != null) acc.userDefinedTypes[it] = acc.udType!!
            }
    }
}

// --- Header/Footer parsing from styles.xml ---

internal data class HeaderFooterResult(
    val headerParagraphs: List<OdfParagraph>,
    val footerParagraphs: List<OdfParagraph>
)

/** Parses page geometry from the first style:page-layout-properties in styles.xml. (Priority 7) */
internal fun OdfParser.parsePageSetup(stylesXml: String): OdfPageSetup? {
    val parser = newParser(stylesXml)
    var e = parser.eventType
    while (e != XmlPullParser.END_DOCUMENT) {
        if (e == XmlPullParser.START_TAG && parser.name == "page-layout-properties") {
            return readPageSetup(parser)
        }
        e = parser.next()
    }
    return null
}

private fun OdfParser.readPageSetup(parser: XmlPullParser): OdfPageSetup {
    val def = OdfPageSetup()
    val mAll = getAttr(parser, "margin")?.let { parseDimension(it) }
    return OdfPageSetup(
        widthPx = readPageWidth(parser, def),
        heightPx = getAttr(parser, "page-height")?.let { parseDimension(it) } ?: def.heightPx,
        marginLeftPx = readPageMargin(parser, "margin-left", mAll, def.marginLeftPx),
        marginRightPx = readPageMargin(parser, "margin-right", mAll, def.marginRightPx),
        marginTopPx = readPageMargin(parser, "margin-top", mAll, def.marginTopPx),
        marginBottomPx = readPageMargin(parser, "margin-bottom", mAll, def.marginBottomPx)
    )
}

private fun OdfParser.readPageWidth(parser: XmlPullParser, def: OdfPageSetup): Float {
    return getAttr(parser, "page-width")?.let { parseDimension(it) } ?: def.widthPx
}

private fun OdfParser.readPageMargin(
    parser: XmlPullParser,
    name: String,
    mAll: Float?,
    defVal: Float,
): Float {
    return getAttr(parser, name)?.let { parseDimension(it) } ?: mAll ?: defVal
}

internal fun OdfParser.parseHeaderFooter(stylesXml: String, styles: Map<String, StyleInfo>): HeaderFooterResult {
    val headerParas = mutableListOf<OdfParagraph>()
    val footerParas = mutableListOf<OdfParagraph>()
    val parser = newParser(stylesXml)
    val state = HfAcc()
    var eventType = parser.eventType

    while (eventType != XmlPullParser.END_DOCUMENT) {
        when (eventType) {
            XmlPullParser.START_TAG -> applyHfStart(parser, styles, state, headerParas, footerParas)
            XmlPullParser.END_TAG -> when (parser.name) {
                "header" -> if (parser.namespace?.contains("style") == true) state.inHeader = false
                "footer" -> if (parser.namespace?.contains("style") == true) state.inFooter = false
            }
        }
        eventType = parser.next()
    }
    return HeaderFooterResult(headerParas, footerParas)
}

/** Header/footer parse state. */
private class HfAcc(
    var inHeader: Boolean = false,
    var inFooter: Boolean = false,
)

/** Apply one header/footer start tag. */
private fun OdfParser.applyHfStart(
    parser: XmlPullParser,
    styles: Map<String, StyleInfo>,
    state: HfAcc,
    headerParas: MutableList<OdfParagraph>,
    footerParas: MutableList<OdfParagraph>,
) {
    when (parser.name) {
        "header" -> if (parser.namespace?.contains("style") == true) state.inHeader = true
        "footer" -> if (parser.namespace?.contains("style") == true) state.inFooter = true
        "p" -> if (state.inHeader || state.inFooter) {
            val spans = parseInlineContent(parser, "p", styles)
            if (spans.isNotEmpty()) {
                val para = OdfParagraph(spans)
                if (state.inHeader) headerParas.add(para)
                else footerParas.add(para)
            }
        }
    }
}
