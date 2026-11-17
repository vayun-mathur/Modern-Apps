package com.vayunmathur.office.data.odf

import com.vayunmathur.library.ui.odf.OdfParagraph
import com.vayunmathur.library.ui.odf.OdfSpan
import org.xmlpull.v1.XmlPullParser

/** PPTX placeholder/clrmap parser (split from OoxmlPptx; behavior identical). */
internal object OoxmlPptxPlaceholders {

    internal class PlaceholderInfo(
        val type: String?,
        val idx: String?,
        val x: Float,
        val y: Float,
        val w: Float,
        val h: Float)

    internal class PlaceholderMap(
        private val layout: List<PlaceholderInfo>,
        private val master: List<PlaceholderInfo>,
    ) {
        /** Geometry [x,y,w,h] for a placeholder: idx match first, then type; layout overrides master. */
        fun geom(type: String?, idx: String?): FloatArray? =
            match(layout, type, idx) ?: match(master, type, idx)

        private fun match(list: List<PlaceholderInfo>, type: String?, idx: String?): FloatArray? {
            if (idx != null) list.firstOrNull { it.idx == idx }?.let { return floatArrayOf(it.x, it.y, it.w, it.h) }
            val t = normType(type)
            list.firstOrNull { normType(it.type) == t }?.let { return floatArrayOf(it.x, it.y, it.w, it.h) }
            return null
        }

        private fun normType(t: String?): String = when (t) { null -> "body"; "ctrTitle" -> "title"; else -> t }
    }

    /** Extracts placeholder geometries (with an explicit xfrm) from a layout/master part's spTree. */
    fun parsePlaceholderGeoms(pkg: OoxmlPackage, part: String?): List<PlaceholderInfo> {
        val xml = part?.let { pkg.entries[it] } ?: return emptyList()
        val parser = OoxmlXml.newParser(xml)
        val acc = PlaceholderAcc()
        var e = parser.eventType
        while (e != XmlPullParser.END_DOCUMENT) {
            if (e == XmlPullParser.START_TAG) applyPlaceholderStart(parser, acc)
            else if (e == XmlPullParser.END_TAG) applyPlaceholderEnd(parser, acc)
            e = parser.next()
        }
        return acc.list
    }

    private class PlaceholderAcc(
        val list: MutableList<PlaceholderInfo> = mutableListOf(),
        var spDepth: Int = -1,
        var phType: String? = null,
        var phIdx: String? = null,
        var x: Float = 0f,
        var y: Float = 0f,
        var w: Float = 0f,
        var h: Float = 0f,
        var hasXfrm: Boolean = false,
        var inXfrm: Boolean = false,
    ) {
        fun resetShape(depth: Int) {
            spDepth = depth
            phType = null
            phIdx = null
            x = 0f
            y = 0f
            w = 0f
            h = 0f
            hasXfrm = false
        }

        fun flush() {
            if (hasXfrm && (phType != null || phIdx != null)) list.add(PlaceholderInfo(
                phType,
                phIdx,
                x,
                y,
                w,
                h))
            spDepth = -1
        }
    }

    private fun applyPlaceholderStart(parser: XmlPullParser, acc: PlaceholderAcc) {
        when (parser.name) {
            "sp" -> acc.resetShape(parser.depth)
            "ph" -> applyPlaceholderPhTag(parser, acc)
            "xfrm" -> if (acc.spDepth >= 0) acc.inXfrm = true
            "off" -> applyPlaceholderOffTag(parser, acc)
            "ext" -> applyPlaceholderExtTag(parser, acc)
        }
    }

    private fun applyPlaceholderPhTag(parser: XmlPullParser, acc: PlaceholderAcc) {
        if (acc.spDepth < 0) return
        acc.phType = OoxmlXml.attr(parser, "type")
        acc.phIdx = OoxmlXml.attr(parser, "idx")
    }

    private fun applyPlaceholderOffTag(parser: XmlPullParser, acc: PlaceholderAcc) {
        if (!acc.inXfrm) return
        OoxmlPptx.emuAttr(parser, "x")?.let { acc.x = it }
        OoxmlPptx.emuAttr(parser, "y")?.let { acc.y = it }
        acc.hasXfrm = true
    }

    private fun applyPlaceholderExtTag(parser: XmlPullParser, acc: PlaceholderAcc) {
        if (!acc.inXfrm) return
        OoxmlPptx.emuAttr(parser, "cx")?.let { acc.w = it }
        OoxmlPptx.emuAttr(parser, "cy")?.let { acc.h = it }
    }

    private fun applyPlaceholderEnd(parser: XmlPullParser, acc: PlaceholderAcc) {
        when (parser.name) {
            "xfrm" -> acc.inXfrm = false
            "sp" -> if (parser.depth == acc.spDepth) acc.flush()
        }
    }

    /** Parses a slide master's <p:clrMap> (bg1/tx1/... -> theme slot) attributes. */
    fun parseClrMap(masterXml: String?): Map<String, String> {
        if (masterXml == null) return emptyMap()
        val parser = OoxmlXml.newParser(masterXml)
        var e = parser.eventType
        while (e != XmlPullParser.END_DOCUMENT) {
            if (e == XmlPullParser.START_TAG && parser.name == "clrMap") {
                val m = HashMap<String, String>()
                for (k in listOf(
                    "bg1",
                    "tx1",
                    "bg2",
                    "tx2",
                    "accent1",
                    "accent2",
                    "accent3",
                    "accent4",
                    "accent5",
                    "accent6",
                    "hlink",
                    "folHlink")) {
                    OoxmlXml.attr(parser, k)?.let { m[k.lowercase()] = it.lowercase() }
                }
                return m
            }
            e = parser.next()
        }
        return emptyMap()
    }

    /** Flattens an a:tbl into one paragraph per row, cells separated by tabs (best-effort). */
    fun parseSlideTable(parser: XmlPullParser, ctx: OoxmlPptx.SlideCtx, out: MutableList<OdfParagraph>) {
        val depth = parser.depth
        var e = parser.next()
        while (!(e == XmlPullParser.END_TAG && parser.depth == depth && parser.name == "tbl")) {
            if (e == XmlPullParser.END_DOCUMENT) break
            if (e == XmlPullParser.START_TAG && parser.name == "tr") {
                out.add(OdfParagraph(parseSlideRow(parser, ctx)))
            }
            e = parser.next()
        }
    }

    /** One slide-table row → spans (cells separated by tabs). */
    private fun parseSlideRow(parser: XmlPullParser, ctx: OoxmlPptx.SlideCtx): List<OdfSpan> {
        val spans = mutableListOf<OdfSpan>()
        val rd = parser.depth
        var ev = parser.next()
        var firstCell = true
        while (!(ev == XmlPullParser.END_TAG && parser.depth == rd && parser.name == "tr")) {
            if (ev == XmlPullParser.END_DOCUMENT) break
            if (ev == XmlPullParser.START_TAG && parser.name == "tc") {
                if (!firstCell) spans.add(OdfSpan("\t"))
                firstCell = false
                val cellParas = mutableListOf<OdfParagraph>()
                OoxmlPptxText.parseTxBody(parser, ctx, cellParas)
                for (p in cellParas) spans.addAll(p.spans)
            }
            ev = parser.next()
        }
        return spans.ifEmpty { mutableListOf(OdfSpan("")) }
    }
}
