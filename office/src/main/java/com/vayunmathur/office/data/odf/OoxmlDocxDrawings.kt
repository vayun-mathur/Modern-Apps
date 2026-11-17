package com.vayunmathur.office.data.odf

import com.vayunmathur.library.ui.odf.OdfContentBlock
import com.vayunmathur.library.ui.odf.OdfImage
import com.vayunmathur.library.ui.odf.OdfParagraph
import com.vayunmathur.library.ui.odf.OdfSpan
import org.xmlpull.v1.XmlPullParser

/** DOCX drawing parser (split from OoxmlDocx for file length; behavior identical). */
internal object OoxmlDocxDrawings {

    private class DrawingAcc(
        var cx: Long = 0L,
        var cy: Long = 0L,
        var embed: String? = null,
        var title: String? = null,
        var desc: String? = null,
        var rot: Int = 0,
        var chartRid: String? = null,
        var dmRid: String? = null,
        var cropL: Float = 0f,
        var cropT: Float = 0f,
        var cropR: Float = 0f,
        var cropB: Float = 0f,
        val textboxParas: MutableList<OdfParagraph> = mutableListOf(),
    )

    fun parseDrawing(parser: XmlPullParser, ctx: OoxmlDocx.DocxCtx) {
        val depth = parser.depth
        val endTag = parser.name
        val acc = DrawingAcc()
        var e = parser.next()
        while (!(e == XmlPullParser.END_TAG && parser.depth == depth && parser.name == endTag)) {
            if (e == XmlPullParser.END_DOCUMENT) break
            if (e == XmlPullParser.START_TAG) applyDrawingTag(parser, ctx, acc)
            e = parser.next()
        }
        if (emitDrawingChart(ctx, acc)) return
        if (emitDrawingImage(ctx, acc)) return
        emitDrawingFallback(ctx, acc)
    }

    private fun applyDrawingTag(parser: XmlPullParser, ctx: OoxmlDocx.DocxCtx, acc: DrawingAcc) {
        if (applyDrawingMediaTag(parser, ctx, acc)) return
        applyDrawingMetaTag(parser, acc)
    }

    private fun applyDrawingMediaTag(parser: XmlPullParser, ctx: OoxmlDocx.DocxCtx, acc: DrawingAcc): Boolean {
        val relsNs = "http://schemas.openxmlformats.org/officeDocument/2006/relationships"
        when (parser.name) {
            "extent" -> {
                acc.cx = OoxmlXml.attr(parser, "cx")?.toLongOrNull() ?: 0L
                acc.cy = OoxmlXml.attr(parser, "cy")?.toLongOrNull() ?: 0L
            }
            "blip" -> if (acc.embed == null) acc.embed = OoxmlXml.attrNs(parser, relsNs, "embed") ?: OoxmlXml.attr(
                parser,
                "embed")
            "chart" -> acc.chartRid = OoxmlXml.attrNs(parser, relsNs, "id") ?: OoxmlXml.attr(parser, "id")
            "txbxContent" -> parseTextboxContent(parser, ctx, acc.textboxParas)
            else -> return false
        }
        return true
    }

    private fun applyDrawingMetaTag(parser: XmlPullParser, acc: DrawingAcc) {
        val relsNs = "http://schemas.openxmlformats.org/officeDocument/2006/relationships"
        when (parser.name) {
            "docPr" -> {
                acc.title = OoxmlXml.attr(parser, "title") ?: OoxmlXml.attr(parser, "name")
                acc.desc = OoxmlXml.attr(parser, "descr")
            }
            "xfrm" -> OoxmlXml.attr(parser, "rot")?.toIntOrNull()?.let { acc.rot = it }
            "srcRect" -> applyDrawingCropTag(parser, acc)
            "relIds" -> acc.dmRid = OoxmlXml.attrNs(parser, relsNs, "dm")
        }
    }

    private fun applyDrawingCropTag(parser: XmlPullParser, acc: DrawingAcc) {
        acc.cropL = (OoxmlXml.attr(parser, "l")?.toIntOrNull() ?: 0) / OoxmlDocx.OOXML_THOUSANDTHS
        acc.cropT = (OoxmlXml.attr(parser, "t")?.toIntOrNull() ?: 0) / OoxmlDocx.OOXML_THOUSANDTHS
        acc.cropR = (OoxmlXml.attr(parser, "r")?.toIntOrNull() ?: 0) / OoxmlDocx.OOXML_THOUSANDTHS
        acc.cropB = (OoxmlXml.attr(parser, "b")?.toIntOrNull() ?: 0) / OoxmlDocx.OOXML_THOUSANDTHS
    }

    private fun emitDrawingChart(ctx: OoxmlDocx.DocxCtx, acc: DrawingAcc): Boolean {
        val chartRid = acc.chartRid ?: return false
        val target = ctx.rels[chartRid]?.target
        val chartXml = target?.let { ctx.pkg.entries[it] }
        if (chartXml != null) OoxmlChart.parse(
            chartXml,
            ctx.theme)?.let { ctx.pendingBlocks.add(OdfContentBlock.Chart(it)); return true }
        return false
    }

    private fun emitDrawingImage(ctx: OoxmlDocx.DocxCtx, acc: DrawingAcc): Boolean {
        val embed = acc.embed ?: return false
        val target = ctx.rels[embed]?.target
        val bytes = target?.let { ctx.pkg.mediaBytes(it) } ?: return false
        val path = "media/${target.substringAfterLast('/')}"
        ctx.extraImages[path] = bytes
        ctx.pendingBlocks.add(OdfContentBlock.Image(OdfImage(
            path = path, imageData = bytes,
            width = OoxmlUnits.emuToPx(acc.cx), height = OoxmlUnits.emuToPx(acc.cy),
            rotationDegrees = OoxmlUnits.angle60000ToDeg(acc.rot),
            cropLeftPct = acc.cropL, cropTopPct = acc.cropT, cropRightPct = acc.cropR, cropBottomPct = acc.cropB,
            altTitle = acc.title, altDesc = acc.desc
        )))
        return true
    }

    private fun emitDrawingFallback(ctx: OoxmlDocx.DocxCtx, acc: DrawingAcc) {
        for (p in acc.textboxParas) ctx.pendingBlocks.add(OdfContentBlock.Paragraph(p))
        // SmartArt: extract diagram text (best-effort) if no image/chart/textbox.
        val hasVisual = acc.chartRid != null || acc.embed != null || acc.textboxParas.isNotEmpty()
        if (!hasVisual && acc.dmRid != null) {
            val dataPart = ctx.rels[acc.dmRid]?.target
            for (line in OoxmlDiagram.extractText(ctx.pkg, dataPart)) {
                ctx.pendingBlocks.add(OdfContentBlock.Paragraph(OdfParagraph(listOf(OdfSpan(line)))))
            }
        }
    }

    private fun parseTextboxContent(parser: XmlPullParser, ctx: OoxmlDocx.DocxCtx, out: MutableList<OdfParagraph>) {
        val depth = parser.depth
        val blocks = mutableListOf<OdfContentBlock>()
        var e = parser.next()
        while (!(e == XmlPullParser.END_TAG && parser.depth == depth && parser.name == "txbxContent")) {
            if (e == XmlPullParser.END_DOCUMENT) break
            if (e == XmlPullParser.START_TAG && parser.name == "p") OoxmlDocx.parseParagraph(parser, ctx, blocks)
            e = parser.next()
        }
        out.addAll(blocks.filterIsInstance<OdfContentBlock.Paragraph>().map { it.paragraph })
    }
}
