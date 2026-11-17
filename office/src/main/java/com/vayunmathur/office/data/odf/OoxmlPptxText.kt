package com.vayunmathur.office.data.odf

import androidx.compose.ui.text.style.TextAlign
import com.vayunmathur.library.ui.odf.ListType
import com.vayunmathur.library.ui.odf.OdfParagraph
import com.vayunmathur.library.ui.odf.OdfSpan
import com.vayunmathur.library.ui.odf.ParagraphStyle
import org.xmlpull.v1.XmlPullParser

/** PPTX drawing-text parser: txBody/paragraph/run/bullet (split from OoxmlPptx; behavior identical). */
internal object OoxmlPptxText {

    fun parseTxBody(parser: XmlPullParser, ctx: OoxmlPptx.SlideCtx, out: MutableList<OdfParagraph>) {
        val start = out.size
        val depth = parser.depth
        val endTag = parser.name  // txBody or txbx
        var e = parser.next()
        while (!(e == XmlPullParser.END_TAG && parser.depth == depth && parser.name == endTag)) {
            if (e == XmlPullParser.END_DOCUMENT) break
            if (e == XmlPullParser.START_TAG && parser.name == "p") parseDrawingParagraph(
                parser,
                ctx)?.let { out.add(it) }
            e = parser.next()
        }
        // Assign running 1/2/3 numbering to contiguous numbered (buAutoNum) items per level.
        val counters = HashMap<Int, Int>()
        for (i in start until out.size) {
            val p = out[i]
            if (p.listType == ListType.NUMBERED) {
                val n = (counters[p.listLevel] ?: 0) + 1
                counters[p.listLevel] = n
                counters.keys.filter { it > p.listLevel }.toList().forEach { counters.remove(it) }
                out[i] = p.copy(listItemIndex = n)
            } else {
                counters.keys.filter { it >= p.listLevel }.toList().forEach { counters.remove(it) }
            }
        }
    }

    private fun parseDrawingParagraph(parser: XmlPullParser, ctx: OoxmlPptx.SlideCtx): OdfParagraph? {
        val depth = parser.depth
        val para = DrawingParaAcc()
        var e = parser.next()
        while (!(e == XmlPullParser.END_TAG && parser.depth == depth && parser.name == "p")) {
            if (e == XmlPullParser.END_DOCUMENT) break
            if (e == XmlPullParser.START_TAG) applyDrawingParaTag(parser, ctx, para)
            e = parser.next()
        }
        if (para.spans.isEmpty()) return null
        return OdfParagraph(
            spans = para.spans,
            alignment = para.align,
            listLevel = para.level,
            listType = para.listType ?: ListType.BULLET,
            style = if (para.listType != null) ParagraphStyle.LIST_ITEM else ParagraphStyle.BODY,
            listBulletChar = para.bulletChar,
            listNumberFormat = para.numFmt
        )
    }

    /** Drawing-paragraph accumulation state. */
    private class DrawingParaAcc(
        val spans: MutableList<OdfSpan> = mutableListOf(),
        var align: TextAlign? = null,
        var level: Int = 0,
        var listType: ListType? = null,
        var bulletChar: String = "•",
        var numFmt: String = "1",
    )

    /** Apply one drawing-paragraph child tag. */
    private fun applyDrawingParaTag(parser: XmlPullParser, ctx: OoxmlPptx.SlideCtx, para: DrawingParaAcc) {
        when (parser.name) {
            "pPr" -> {
                para.align = OoxmlPptx.textAlignOf(OoxmlXml.attr(parser, "algn"))
                para.level = OoxmlXml.attr(parser, "lvl")?.toIntOrNull() ?: 0
                val bul = parseBullet(parser)
                para.listType = bul.first
                para.bulletChar = bul.second ?: para.bulletChar
                para.numFmt = bul.third ?: para.numFmt
            }
            "r" -> parseDrawingRun(parser, ctx)?.let { para.spans.add(it) }
            "br" -> para.spans.add(OdfSpan("\n"))
            "fld" -> parseDrawingRun(parser, ctx)?.let { para.spans.add(it) }
        }
    }

    /** Returns (listType or null, bulletChar, numberFormat) from an a:pPr's bullet children. */
    private fun parseBullet(parser: XmlPullParser): Triple<ListType?, String?, String?> {
        val depth = parser.depth
        var type: ListType? = null; var char: String? = null; var numFmt: String? = null
        var e = parser.next()
        while (!(e == XmlPullParser.END_TAG && parser.depth == depth && parser.name == "pPr")) {
            if (e == XmlPullParser.END_DOCUMENT) break
            if (e == XmlPullParser.START_TAG) when (parser.name) {
                "buChar" -> { type = ListType.BULLET; char = OoxmlXml.attr(parser, "char") }
                "buAutoNum" -> { type = ListType.NUMBERED; numFmt = mapAutoNum(OoxmlXml.attr(parser, "type")) }
                "buNone" -> type = null
            }
            e = parser.next()
        }
        return Triple(type, char, numFmt)
    }

    private fun mapAutoNum(type: String?): String = when {
        type == null -> "1"
        type.startsWith("alphaLc") -> "a"
        type.startsWith("alphaUc") -> "A"
        type.startsWith("romanLc") -> "i"
        type.startsWith("romanUc") -> "I"
        else -> "1"
    }

    private fun parseDrawingRun(parser: XmlPullParser, ctx: OoxmlPptx.SlideCtx): OdfSpan? {
        val endTag = parser.name  // r or fld
        val depth = parser.depth
        val run = DrawingRunAcc()
        var e = parser.next()
        while (!(e == XmlPullParser.END_TAG && parser.depth == depth && parser.name == endTag)) {
            if (e == XmlPullParser.END_DOCUMENT) break
            if (e == XmlPullParser.START_TAG) applyDrawingRunTag(parser, ctx, run)
            e = parser.next()
        }
        if (run.sb.isEmpty()) return null
        return OdfSpan(
            text = run.sb.toString(), bold = run.bold, italic = run.italic, underline = run.underline,
            strikethrough = run.strike,
            fontSize = run.size, fontFamily = run.font, color = run.color, superscript = run.superscript,
            subscript = run.subscript,
            href = run.href, letterSpacing = run.letterSpacing,
            textTransform = if (run.caps) "uppercase" else null
        )
    }

    /** Drawing-run accumulation state. */
    private class DrawingRunAcc(
        var bold: Boolean = false,
        var italic: Boolean = false,
        var underline: Boolean = false,
        var strike: Boolean = false,
        var color: Long? = null,
        var size: Float? = null,
        var font: String? = null,
        var superscript: Boolean = false,
        var subscript: Boolean = false,
        var href: String? = null,
        var letterSpacing: Float? = null,
        var caps: Boolean = false,
        val sb: StringBuilder = StringBuilder(),
    )

    /** Apply one drawing-run child tag. */
    private fun applyDrawingRunTag(parser: XmlPullParser, ctx: OoxmlPptx.SlideCtx, run: DrawingRunAcc) {
        when (parser.name) {
            "rPr", "defRPr", "endParaRPr" -> applyDrawingRPr(parser, ctx, run)
            "t" -> run.sb.append(OoxmlXml.readElementText(parser, "t"))
        }
    }

    /** Run properties from an a:rPr tag. */
    private fun applyDrawingRPr(parser: XmlPullParser, ctx: OoxmlPptx.SlideCtx, run: DrawingRunAcc) {
        applyRPrEmphasisTag(parser, run)
        applyRPrMetricsTag(parser, run)
        val r = parseRunColorFontLink(parser, ctx)
        run.color = run.color ?: r.first
        run.font = run.font ?: r.second
        run.href = run.href ?: r.third
    }

    private fun applyRPrEmphasisTag(parser: XmlPullParser, run: DrawingRunAcc) {
        if (OoxmlXml.boolAttr(OoxmlXml.attr(parser, "b")) && OoxmlXml.attr(parser, "b") != null) run.bold = true
        if (OoxmlXml.attr(parser, "b") == "1") run.bold = true
        if (OoxmlXml.attr(parser, "i") == "1") run.italic = true
        if (OoxmlXml.attr(parser, "u")?.let { it != "none" } == true) run.underline = true
        if (OoxmlXml.attr(parser, "strike")?.let { it != "noStrike" } == true) run.strike = true
    }

    private fun applyRPrMetricsTag(parser: XmlPullParser, run: DrawingRunAcc) {
        OoxmlXml.attr(parser, "sz")?.toFloatOrNull()?.let { run.size = it / OoxmlPptx.PERCENT_DIVISOR }
        OoxmlXml.attr(parser, "spc")?.toIntOrNull()?.let { run.letterSpacing = OoxmlUnits.hundredthPtToPt(it) }
        when (OoxmlXml.attr(parser, "cap")) { "all", "small" -> run.caps = true }
        OoxmlXml.attr(
            parser,
            "baseline")
        ?.toIntOrNull()?.let { if (it > 0) run.superscript = true else if (it < 0) run.subscript = true }
    }

    private class RunLinkAcc(
        var color: Long? = null,
        var font: String? = null,
        var href: String? = null,
        var inFill: Boolean = false,
    )

    /** Parses solidFill color, latin font, and hlinkClick target from within an a:rPr. */
    private fun parseRunColorFontLink(parser: XmlPullParser, ctx: OoxmlPptx.SlideCtx): Triple<Long?, String?, String?> {
        val depth = parser.depth
        val acc = RunLinkAcc()
        var e = parser.next()
        val endTags = setOf("rPr", "defRPr", "endParaRPr")
        while (!(e == XmlPullParser.END_TAG && parser.depth == depth && parser.name in endTags)) {
            if (e == XmlPullParser.END_DOCUMENT) break
            if (e == XmlPullParser.START_TAG) applyRunLinkTag(parser, ctx, acc)
            else if (e == XmlPullParser.END_TAG && parser.name == "solidFill") acc.inFill = false
            e = parser.next()
        }
        return Triple(acc.color, acc.font, acc.href)
    }

    private fun applyRunLinkTag(parser: XmlPullParser, ctx: OoxmlPptx.SlideCtx, acc: RunLinkAcc) {
        when (parser.name) {
            "solidFill" -> acc.inFill = true
            "latin" -> acc.font = OoxmlXml.attr(parser, "typeface")
            "hlinkClick" -> applyRunLinkHrefTag(parser, ctx, acc)
            in OoxmlPptx.COLOR_TAGS -> applyRunLinkColorTag(parser, ctx, acc)
        }
    }

    private fun applyRunLinkHrefTag(parser: XmlPullParser, ctx: OoxmlPptx.SlideCtx, acc: RunLinkAcc) {
        val rId = OoxmlXml.attrNs(
            parser,
            OoxmlPptx.RELS_NS,
            "id") ?: OoxmlXml.attr(parser, "id")
        acc.href = ctx.rels[rId]?.target
    }

    private fun applyRunLinkColorTag(parser: XmlPullParser, ctx: OoxmlPptx.SlideCtx, acc: RunLinkAcc) {
        if (acc.inFill && acc.color == null) acc.color = OoxmlColor.parse(parser, ctx.theme)
    }
}
