package com.vayunmathur.office.data.odf

import com.vayunmathur.library.ui.odf.OdfFrame
import com.vayunmathur.library.ui.odf.OdfImage
import com.vayunmathur.library.ui.odf.OdfSlideElement
import org.xmlpull.v1.XmlPullParser

internal object OoxmlXlsxHelper {
    private const val DEFAULT_COL_WIDTH_PX = 64f
    private const val DEFAULT_FALLBACK_HEIGHT_PX = 48f
    private const val DEFAULT_ROW_HEIGHT_PX = 20f
    private const val BOUNDS_X = 0
    private const val BOUNDS_Y = 1
    private const val BOUNDS_W = 2
    private const val BOUNDS_H = 3

    private class AnchorAcc(
        var fromCol: Int = 0,
        var fromRow: Int = 0,
        var fromColOff: Long = 0L,
        var fromRowOff: Long = 0L,
        var toCol: Int = 0,
        var toRow: Int = 0,
        var inFrom: Boolean = false,
        var inTo: Boolean = false,
        var embed: String? = null,
        var chartRid: String? = null,
        var extCx: Long = 0L,
        var extCy: Long = 0L,
    )

    fun parseDrawings(
        pkg: OoxmlPackage, rels: Map<String, OoxmlPackage.Rel>, drawingRid: String?,
        theme: OoxmlTheme, colWidths: Map<Int, Float>, rowHeights: List<Float?>
    ): List<OdfSlideElement> {
        val drawingPart = drawingRid?.let { rels[it]?.target }
            ?: rels.values.firstOrNull { it.type?.endsWith("drawing") == true }?.target
            ?: return emptyList()
        val xml = pkg.entries[drawingPart] ?: return emptyList()
        val drawingRels = pkg.relsFor(drawingPart)
        val parser = OoxmlXml.newParser(xml)
        val out = mutableListOf<OdfSlideElement>()
        val acc = AnchorAcc()
        var e = parser.eventType
        while (e != XmlPullParser.END_DOCUMENT) {
            if (e == XmlPullParser.START_TAG) applyAnchorTag(parser, acc)
            else if (e == XmlPullParser.END_TAG) {
                applyAnchorEndTag(parser, acc, pkg, drawingRels, theme, colWidths, rowHeights, out)
            }
            e = parser.next()
        }
        return out
    }

    private fun applyAnchorTag(parser: XmlPullParser, acc: AnchorAcc) {
        when (parser.name) {
            "from" -> acc.inFrom = true
            "to" -> acc.inTo = true
            "col" -> applyAnchorColTag(parser, acc)
            "row" -> applyAnchorRowTag(parser, acc)
            "colOff" -> applyAnchorColOffTag(parser, acc)
            "rowOff" -> applyAnchorRowOffTag(parser, acc)
            "ext" -> applyAnchorExtTag(parser, acc)
            "blip" -> applyAnchorBlipTag(parser, acc)
            "chart" -> applyAnchorChartTag(parser, acc)
        }
    }

    private fun applyAnchorColTag(parser: XmlPullParser, acc: AnchorAcc) {
        val v = OoxmlXml.readElementText(parser, "col").trim().toIntOrNull() ?: 0
        if (acc.inFrom) acc.fromCol = v else if (acc.inTo) acc.toCol = v
    }

    private fun applyAnchorRowTag(parser: XmlPullParser, acc: AnchorAcc) {
        val v = OoxmlXml.readElementText(parser, "row").trim().toIntOrNull() ?: 0
        if (acc.inFrom) acc.fromRow = v else if (acc.inTo) acc.toRow = v
    }

    private fun applyAnchorColOffTag(parser: XmlPullParser, acc: AnchorAcc) {
        val v = OoxmlXml.readElementText(parser, "colOff").trim().toLongOrNull() ?: 0L
        if (acc.inFrom) acc.fromColOff = v
    }

    private fun applyAnchorRowOffTag(parser: XmlPullParser, acc: AnchorAcc) {
        val v = OoxmlXml.readElementText(parser, "rowOff").trim().toLongOrNull() ?: 0L
        if (acc.inFrom) acc.fromRowOff = v
    }

    private fun applyAnchorExtTag(parser: XmlPullParser, acc: AnchorAcc) {
        acc.extCx = OoxmlXml.attr(parser, "cx")?.toLongOrNull() ?: 0L
        acc.extCy = OoxmlXml.attr(parser, "cy")?.toLongOrNull() ?: 0L
    }

    private fun applyAnchorBlipTag(parser: XmlPullParser, acc: AnchorAcc) {
        acc.embed = OoxmlXml.attrNs(
            parser,
            "http://schemas.openxmlformats.org/officeDocument/2006/relationships",
            "embed") ?: OoxmlXml.attr(parser, "embed")
    }

    private fun applyAnchorChartTag(parser: XmlPullParser, acc: AnchorAcc) {
        acc.chartRid = OoxmlXml.attrNs(
            parser,
            "http://schemas.openxmlformats.org/officeDocument/2006/relationships",
            "id") ?: OoxmlXml.attr(parser, "id")
    }

    private fun applyAnchorEndTag(
        parser: XmlPullParser, acc: AnchorAcc, pkg: OoxmlPackage,
        drawingRels: Map<String, OoxmlPackage.Rel>, theme: OoxmlTheme,
        colWidths: Map<Int, Float>, rowHeights: List<Float?>, out: MutableList<OdfSlideElement>,
    ) {
        when (parser.name) {
            "from" -> acc.inFrom = false
            "to" -> acc.inTo = false
            "oneCellAnchor", "twoCellAnchor", "absoluteAnchor" ->
                flushAnchor(acc, pkg, drawingRels, theme, colWidths, rowHeights, out)
        }
    }

    private fun anchorSize(ext: Long, from: Float, to: Float, fallback: Float): Float {
        if (ext > 0) return OoxmlUnits.emuToPx(ext)
        return (to - from).coerceAtLeast(fallback)
    }

    private fun anchorBounds(
        acc: AnchorAcc, colWidths: Map<Int, Float>, rowHeights: List<Float?>
    ): FloatArray {
        val x = colX(acc.fromCol, colWidths) + OoxmlUnits.emuToPx(acc.fromColOff)
        val y = rowY(acc.fromRow, rowHeights) + OoxmlUnits.emuToPx(acc.fromRowOff)
        val fromX = colX(acc.fromCol, colWidths)
        val toX = colX(acc.toCol, colWidths)
        val fromY = rowY(acc.fromRow, rowHeights)
        val toY = rowY(acc.toRow, rowHeights)
        val w = anchorSize(acc.extCx, fromX, toX, DEFAULT_COL_WIDTH_PX)
        val h = anchorSize(acc.extCy, fromY, toY, DEFAULT_FALLBACK_HEIGHT_PX)
        return floatArrayOf(x, y, w, h)
    }

    private fun flushAnchor(
        acc: AnchorAcc, pkg: OoxmlPackage, drawingRels: Map<String, OoxmlPackage.Rel>,
        theme: OoxmlTheme, colWidths: Map<Int, Float>, rowHeights: List<Float?>, out: MutableList<OdfSlideElement>,
    ) {
        val b = anchorBounds(acc, colWidths, rowHeights)
        if (acc.chartRid != null) {
            flushAnchorChart(acc, pkg, drawingRels, theme, b, out)
        } else if (acc.embed != null) {
            flushAnchorImage(acc, pkg, drawingRels, b, out)
        }
        acc.embed = null; acc.chartRid = null; acc.extCx = 0; acc.extCy = 0
    }

    private fun flushAnchorChart(
        acc: AnchorAcc, pkg: OoxmlPackage, drawingRels: Map<String, OoxmlPackage.Rel>,
        theme: OoxmlTheme, b: FloatArray, out: MutableList<OdfSlideElement>,
    ) {
        val target = drawingRels[acc.chartRid]?.target
        val chart = target?.let { pkg.entries[it] }?.let { OoxmlChart.parse(it, theme) }
        if (chart != null) {
            val frame = OdfFrame(b[BOUNDS_X], b[BOUNDS_Y], b[BOUNDS_W], b[BOUNDS_H], emptyList(), chart = chart)
            out.add(OdfSlideElement.Frame(frame))
        }
    }

    private fun flushAnchorImage(
        acc: AnchorAcc, pkg: OoxmlPackage, drawingRels: Map<String, OoxmlPackage.Rel>,
        b: FloatArray, out: MutableList<OdfSlideElement>,
    ) {
        val target = drawingRels[acc.embed]?.target
        val bytes = target?.let { pkg.mediaBytes(it) }
        if (bytes != null) {
            val path = "media/${target.substringAfterLast('/')}"
            val frame = OdfFrame(
                b[BOUNDS_X],
                b[BOUNDS_Y],
                b[BOUNDS_W],
                b[BOUNDS_H],
                emptyList(),
                image = OdfImage(path, bytes, b[BOUNDS_W], b[BOUNDS_H]))
            out.add(OdfSlideElement.Frame(frame))
        }
    }

    private fun colX(col: Int, widths: Map<Int, Float>): Float {
        var x = 0f
        for (c in 0 until col) x += widths[c] ?: DEFAULT_COL_WIDTH_PX
        return x
    }

    private fun rowY(row: Int, heights: List<Float?>): Float {
        var y = 0f
        for (r in 0 until row) y += heights.getOrNull(r) ?: DEFAULT_ROW_HEIGHT_PX
        return y
    }
}
