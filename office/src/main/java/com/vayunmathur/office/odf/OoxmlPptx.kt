package com.vayunmathur.office.odf

import androidx.compose.ui.text.style.TextAlign
import com.vayunmathur.library.ui.odf.*
import org.xmlpull.v1.XmlPullParser

/**
 * PPTX importer (Groups 9-11, phases P1-P6). Extracts per-shape text with rich run/paragraph
 * formatting, preset-geometry shapes with fills/strokes/gradients/rotation, images, groups,
 * connectors, slide background/size/transition/name, notes, tables, and charts. Best-effort onto
 * the ODF slide model.
 */
internal object OoxmlPptx {

    private const val RELS_NS = "http://schemas.openxmlformats.org/officeDocument/2006/relationships"

    fun import(pkg: OoxmlPackage, fileName: String): OdfDocument.Presentation {
        val theme = OoxmlTheme.parse(firstThemeXml(pkg))
        val order = slideOrder(pkg)
        val slides = order.mapIndexed { i, part ->
            parseSlide(pkg, part, theme, "Slide ${i + 1}")
        }
        val images = LinkedHashMap<String, ByteArray>()
        for (s in slides) for (el in s.elements) collectImage(el, images)
        return OdfDocument.Presentation(
            title = fileName,
            slides = slides.ifEmpty { listOf(OdfSlide("Slide 1")) },
            metadata = OoxmlMetadata.parse(pkg),
            images = images
        )
    }

    private fun collectImage(el: OdfSlideElement, out: MutableMap<String, ByteArray>) {
        if (el is OdfSlideElement.Frame) el.frame.image?.let { out[it.path] = it.imageData }
    }

    private fun firstThemeXml(pkg: OoxmlPackage): String? =
        pkg.entries.keys.firstOrNull { it.matches(Regex("ppt/theme/theme\\d+\\.xml")) }?.let { pkg.entries[it] }

    /** Slide part paths in presentation order (falls back to filename order). */
    private fun slideOrder(pkg: OoxmlPackage): List<String> {
        val pres = pkg.entries["ppt/presentation.xml"]
        val rels = pkg.relsFor("ppt/presentation.xml")
        if (pres != null) {
            val ids = mutableListOf<String>()
            val parser = OoxmlXml.newParser(pres)
            var e = parser.eventType
            while (e != XmlPullParser.END_DOCUMENT) {
                if (e == XmlPullParser.START_TAG && parser.name == "sldId") {
                    val rId = OoxmlXml.attrNs(parser, RELS_NS, "id") ?: OoxmlXml.attr(parser, "id")
                    rels[rId]?.target?.let { ids.add(it) }
                }
                e = parser.next()
            }
            if (ids.isNotEmpty()) return ids
        }
        return pkg.entries.keys.filter { it.matches(Regex("ppt/slides/slide\\d+\\.xml")) }
            .sortedBy { it.substringAfterLast("slide").substringBefore(".xml").toIntOrNull() ?: 0 }
    }

    private class SlideCtx(
        val pkg: OoxmlPackage,
        val part: String,
        val theme: OoxmlTheme,
        val rels: Map<String, OoxmlPackage.Rel>,
        var autoY: Float = 36f
    )

    private fun parseSlide(pkg: OoxmlPackage, part: String, theme: OoxmlTheme, defaultName: String): OdfSlide {
        val xml = pkg.entries[part] ?: return OdfSlide(defaultName)
        val ctx = SlideCtx(pkg, part, theme, pkg.relsFor(part))
        val parser = OoxmlXml.newParser(xml)
        val elements = mutableListOf<OdfSlideElement>()
        var bgColor: Long? = null
        var bgImage: String? = null
        var transitionType: String? = null
        var transitionSpeed: String? = null
        var slideName: String? = null
        var event = parser.eventType
        var treeDepth = -1
        while (event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.START_TAG) when (parser.name) {
                "cSld" -> slideName = OoxmlXml.attr(parser, "name")
                "bg" -> { val b = parseBackground(parser, ctx); bgColor = b.first; bgImage = b.second }
                "spTree" -> treeDepth = parser.depth
                "sp" -> if (parser.depth == treeDepth + 1) parseShape(parser, ctx)?.let { elements.add(it) }
                "pic" -> if (parser.depth == treeDepth + 1) parsePic(parser, ctx)?.let { elements.add(it) }
                "grpSp" -> if (parser.depth == treeDepth + 1) parseGroup(parser, ctx, 0f, 0f).let { elements.addAll(it) }
                "cxnSp" -> if (parser.depth == treeDepth + 1) parseConnector(parser, ctx)?.let { elements.add(it) }
                "graphicFrame" -> if (parser.depth == treeDepth + 1) parseGraphicFrame(parser, ctx)?.let { elements.add(it) }
                "transition" -> { transitionSpeed = OoxmlXml.attr(parser, "spd"); transitionType = readTransitionType(parser) }
            }
            event = parser.next()
        }
        val notes = parseNotes(pkg, ctx.rels)
        return OdfSlide(
            name = slideName?.ifBlank { null } ?: defaultName,
            elements = elements,
            backgroundColor = bgColor,
            backgroundImagePath = bgImage,
            notes = notes,
            transitionType = transitionType,
            transitionSpeed = when (transitionSpeed) { "slow" -> "slow"; "fast" -> "fast"; "med" -> "medium"; else -> transitionSpeed }
        )
    }

    // ---- Background / transition ----

    private fun parseBackground(parser: XmlPullParser, ctx: SlideCtx): Pair<Long?, String?> {
        val depth = parser.depth
        var color: Long? = null; var image: String? = null
        var e = parser.next()
        while (!(e == XmlPullParser.END_TAG && parser.depth == depth && parser.name == "bg")) {
            if (e == XmlPullParser.END_DOCUMENT) break
            if (e == XmlPullParser.START_TAG) when (parser.name) {
                "srgbClr", "schemeClr", "sysClr", "prstClr", "scrgbClr" -> if (color == null) color = OoxmlColor.parse(parser, ctx.theme)
                "blip" -> {
                    val embed = OoxmlXml.attrNs(parser, RELS_NS, "embed") ?: OoxmlXml.attr(parser, "embed")
                    val target = ctx.rels[embed]?.target
                    if (target != null && ctx.pkg.mediaBytes(target) != null) image = "media/${target.substringAfterLast('/')}"
                }
            }
            e = parser.next()
        }
        return color to image
    }

    private fun readTransitionType(parser: XmlPullParser): String? {
        val depth = parser.depth
        var type: String? = null
        var e = parser.next()
        while (!(e == XmlPullParser.END_TAG && parser.depth == depth && parser.name == "transition")) {
            if (e == XmlPullParser.END_DOCUMENT) break
            if (e == XmlPullParser.START_TAG && type == null) type = when (parser.name) {
                "fade" -> "fade"; "wipe" -> "wipe"; "dissolve" -> "dissolve"; "push" -> "push"
                "cover" -> "cover"; "cut" -> "cut"; "split" -> "split"; "blinds" -> "blinds"
                "checker" -> "checkerboard"; "circle" -> "circle"; "wheel" -> "wheel"; else -> null
            }
            e = parser.next()
        }
        return type
    }

    // ---- Shapes ----

    private class SpProps(
        var x: Float = 0f, var y: Float = 0f, var w: Float = 0f, var h: Float = 0f, var hasXfrm: Boolean = false,
        var rot: Float = 0f, var flipH: Boolean = false, var flipV: Boolean = false,
        var geom: String? = null, var fill: Long? = null, var gradient: OdfGradient? = null,
        var stroke: Long? = null, var strokeWidth: Float? = null, var strokeDashed: Boolean = false,
        var cornerRadius: Float = 0f
    )

    private fun parseShape(parser: XmlPullParser, ctx: SlideCtx): OdfSlideElement? {
        val depth = parser.depth
        val sp = SpProps()
        val paras = mutableListOf<OdfParagraph>()
        var hasText = false
        var e = parser.next()
        while (!(e == XmlPullParser.END_TAG && parser.depth == depth && parser.name == "sp")) {
            if (e == XmlPullParser.END_DOCUMENT) break
            if (e == XmlPullParser.START_TAG) when (parser.name) {
                "spPr" -> parseSpPr(parser, ctx, sp)
                "txBody" -> { parseTxBody(parser, ctx, paras); if (paras.any { p -> p.spans.any { it.text.isNotBlank() } }) hasText = true }
            }
            e = parser.next()
        }
        applyAutoGeometry(sp, ctx)
        return buildShapeElement(sp, paras, hasText)
    }

    private fun buildShapeElement(sp: SpProps, paras: List<OdfParagraph>, hasText: Boolean): OdfSlideElement {
        val x = sp.x; val y = sp.y; val w = sp.w.coerceAtLeast(1f); val h = sp.h.coerceAtLeast(1f)
        val geom = sp.geom
        // Text boxes and plain rectangles -> Frame (renders text + fill).
        if (hasText || geom == null || geom == "rect" || geom == "textBox") {
            return OdfSlideElement.Frame(OdfFrame(
                x = x, y = y, width = w, height = h.coerceAtLeast(20f), paragraphs = paras.ifEmpty { listOf(OdfParagraph(listOf(OdfSpan("")))) },
                fillColor = sp.fill, strokeColor = sp.stroke, strokeWidth = sp.strokeWidth, fillGradient = sp.gradient
            ))
        }
        val shape = when (geom) {
            "ellipse", "circle" -> OdfShape.Ellipse(x, y, w, h, sp.fill, sp.stroke, sp.strokeWidth, paras, sp.rot, sp.gradient, sp.strokeDashed)
            "roundRect" -> OdfShape.Rect(x, y, w, h, sp.fill, sp.stroke, sp.strokeWidth, paras, cornerRadius = if (sp.cornerRadius > 0) sp.cornerRadius else minOf(w, h) * 0.15f, rotationDegrees = sp.rot, fillGradient = sp.gradient, strokeDashed = sp.strokeDashed)
            "line", "straightConnector1" -> OdfShape.Line(x, y, w, h, sp.fill, sp.stroke, sp.strokeWidth, paras, x2 = x + w, y2 = y + h, rotationDegrees = sp.rot, strokeDashed = sp.strokeDashed)
            else -> OdfShape.CustomShape(x, y, w, h, sp.fill, sp.stroke, sp.strokeWidth, paras, sp.rot, sp.gradient, sp.strokeDashed)
        }
        return OdfSlideElement.Shape(shape)
    }

    private fun applyAutoGeometry(sp: SpProps, ctx: SlideCtx) {
        if (!sp.hasXfrm) {
            sp.x = 36f; sp.w = 640f; sp.y = ctx.autoY; if (sp.h <= 0f) sp.h = 80f
        }
        ctx.autoY = sp.y + sp.h + 12f
    }

    private fun parseSpPr(parser: XmlPullParser, ctx: SlideCtx, sp: SpProps) {
        val depth = parser.depth
        var inLn = false
        var e = parser.next()
        while (!(e == XmlPullParser.END_TAG && parser.depth == depth && parser.name == "spPr")) {
            if (e == XmlPullParser.END_DOCUMENT) break
            if (e == XmlPullParser.START_TAG) when (parser.name) {
                "xfrm" -> {
                    sp.hasXfrm = true
                    OoxmlXml.attr(parser, "rot")?.toIntOrNull()?.let { sp.rot = OoxmlUnits.angle60000ToDeg(it) }
                    sp.flipH = OoxmlXml.attr(parser, "flipH") == "1"
                    sp.flipV = OoxmlXml.attr(parser, "flipV") == "1"
                }
                "off" -> { OoxmlXml.attr(parser, "x")?.toLongOrNull()?.let { sp.x = OoxmlUnits.emuToPx(it) }; OoxmlXml.attr(parser, "y")?.toLongOrNull()?.let { sp.y = OoxmlUnits.emuToPx(it) } }
                "ext" -> { OoxmlXml.attr(parser, "cx")?.toLongOrNull()?.let { sp.w = OoxmlUnits.emuToPx(it) }; OoxmlXml.attr(parser, "cy")?.toLongOrNull()?.let { sp.h = OoxmlUnits.emuToPx(it) } }
                "prstGeom" -> sp.geom = OoxmlXml.attr(parser, "prst")
                "ln" -> { inLn = true; OoxmlXml.attr(parser, "w")?.toLongOrNull()?.let { sp.strokeWidth = OoxmlUnits.emuToPx(it) } }
                "prstDash" -> if (inLn) sp.strokeDashed = OoxmlXml.attr(parser, "val")?.contains("dash", true) == true
                "gradFill" -> sp.gradient = parseGradient(parser, ctx.theme)
                "srgbClr", "schemeClr", "sysClr", "prstClr", "scrgbClr" -> {
                    val c = OoxmlColor.parse(parser, ctx.theme)
                    if (inLn) { if (sp.stroke == null) sp.stroke = c } else if (sp.fill == null) sp.fill = c
                }
                "noFill" -> if (!inLn) sp.fill = null
            } else if (e == XmlPullParser.END_TAG && parser.name == "ln") inLn = false
            e = parser.next()
        }
    }

    private fun parseGradient(parser: XmlPullParser, theme: OoxmlTheme): OdfGradient? {
        val depth = parser.depth
        val stops = mutableListOf<Pair<Int, Long>>()
        var angle = 0f
        var e = parser.next()
        while (!(e == XmlPullParser.END_TAG && parser.depth == depth && parser.name == "gradFill")) {
            if (e == XmlPullParser.END_DOCUMENT) break
            if (e == XmlPullParser.START_TAG) when (parser.name) {
                "gs" -> {
                    val pos = OoxmlXml.attr(parser, "pos")?.toIntOrNull() ?: 0
                    // descend to color child
                    val d = parser.depth
                    var ev = parser.next(); var color: Long? = null
                    while (!(ev == XmlPullParser.END_TAG && parser.depth == d && parser.name == "gs")) {
                        if (ev == XmlPullParser.END_DOCUMENT) break
                        if (ev == XmlPullParser.START_TAG && parser.name in COLOR_TAGS && color == null) color = OoxmlColor.parse(parser, theme)
                        ev = parser.next()
                    }
                    if (color != null) stops.add(pos to color)
                }
                "lin" -> angle = OoxmlXml.attr(parser, "ang")?.toIntOrNull()?.let { OoxmlUnits.angle60000ToDeg(it) } ?: 0f
            }
            e = parser.next()
        }
        if (stops.size < 2) return null
        val sorted = stops.sortedBy { it.first }
        return OdfGradient(startColor = sorted.first().second, endColor = sorted.last().second, angle = angle)
    }

    private val COLOR_TAGS = setOf("srgbClr", "schemeClr", "sysClr", "prstClr", "scrgbClr")

    // ---- Pictures ----

    private fun parsePic(parser: XmlPullParser, ctx: SlideCtx): OdfSlideElement? {
        val depth = parser.depth
        val sp = SpProps()
        var embed: String? = null
        var desc: String? = null
        var e = parser.next()
        while (!(e == XmlPullParser.END_TAG && parser.depth == depth && parser.name == "pic")) {
            if (e == XmlPullParser.END_DOCUMENT) break
            if (e == XmlPullParser.START_TAG) when (parser.name) {
                "spPr" -> parseSpPr(parser, ctx, sp)
                "cNvPr" -> desc = OoxmlXml.attr(parser, "descr") ?: OoxmlXml.attr(parser, "name")
                "blip" -> embed = OoxmlXml.attrNs(parser, RELS_NS, "embed") ?: OoxmlXml.attr(parser, "embed")
            }
            e = parser.next()
        }
        val target = ctx.rels[embed]?.target ?: return null
        val bytes = ctx.pkg.mediaBytes(target) ?: return null
        applyAutoGeometry(sp, ctx)
        val path = "media/${target.substringAfterLast('/')}"
        return OdfSlideElement.Frame(OdfFrame(
            x = sp.x, y = sp.y, width = sp.w.coerceAtLeast(1f), height = sp.h.coerceAtLeast(1f),
            paragraphs = emptyList(),
            image = OdfImage(path, bytes, sp.w, sp.h, rotationDegrees = sp.rot, altDesc = desc)
        ))
    }

    // ---- Groups / connectors ----

    private fun parseGroup(parser: XmlPullParser, ctx: SlideCtx, dx: Float, dy: Float): List<OdfSlideElement> {
        val depth = parser.depth
        val out = mutableListOf<OdfSlideElement>()
        var e = parser.next()
        while (!(e == XmlPullParser.END_TAG && parser.depth == depth && parser.name == "grpSp")) {
            if (e == XmlPullParser.END_DOCUMENT) break
            if (e == XmlPullParser.START_TAG) when (parser.name) {
                "sp" -> parseShape(parser, ctx)?.let { out.add(translate(it, dx, dy)) }
                "pic" -> parsePic(parser, ctx)?.let { out.add(translate(it, dx, dy)) }
                "cxnSp" -> parseConnector(parser, ctx)?.let { out.add(translate(it, dx, dy)) }
                "grpSp" -> out.addAll(parseGroup(parser, ctx, dx, dy))
            }
            e = parser.next()
        }
        return out
    }

    private fun translate(el: OdfSlideElement, dx: Float, dy: Float): OdfSlideElement {
        if (dx == 0f && dy == 0f) return el
        val b = el.bounds()
        return setElementBounds(el, b[0] + dx, b[1] + dy, b[2], b[3])
    }

    private fun parseConnector(parser: XmlPullParser, ctx: SlideCtx): OdfSlideElement? {
        val depth = parser.depth
        val sp = SpProps()
        var e = parser.next()
        while (!(e == XmlPullParser.END_TAG && parser.depth == depth && parser.name == "cxnSp")) {
            if (e == XmlPullParser.END_DOCUMENT) break
            if (e == XmlPullParser.START_TAG && parser.name == "spPr") parseSpPr(parser, ctx, sp)
            e = parser.next()
        }
        if (!sp.hasXfrm) return null
        return OdfSlideElement.Shape(OdfShape.Line(
            sp.x, sp.y, sp.w, sp.h, null, sp.stroke, sp.strokeWidth, emptyList(),
            x2 = sp.x + sp.w, y2 = sp.y + sp.h, rotationDegrees = sp.rot, strokeDashed = sp.strokeDashed
        ))
    }

    // ---- Graphic frames (tables / charts) ----

    private fun parseGraphicFrame(parser: XmlPullParser, ctx: SlideCtx): OdfSlideElement? {
        val depth = parser.depth
        var x = 36f; var y = ctx.autoY; var w = 640f; var h = 200f; var hasXfrm = false
        var chartRid: String? = null
        val tableParas = mutableListOf<OdfParagraph>()
        var e = parser.next()
        while (!(e == XmlPullParser.END_TAG && parser.depth == depth && parser.name == "graphicFrame")) {
            if (e == XmlPullParser.END_DOCUMENT) break
            if (e == XmlPullParser.START_TAG) when (parser.name) {
                "off" -> { OoxmlXml.attr(parser, "x")?.toLongOrNull()?.let { x = OoxmlUnits.emuToPx(it); hasXfrm = true }; OoxmlXml.attr(parser, "y")?.toLongOrNull()?.let { y = OoxmlUnits.emuToPx(it) } }
                "ext" -> { OoxmlXml.attr(parser, "cx")?.toLongOrNull()?.let { w = OoxmlUnits.emuToPx(it) }; OoxmlXml.attr(parser, "cy")?.toLongOrNull()?.let { h = OoxmlUnits.emuToPx(it) } }
                "chart" -> chartRid = OoxmlXml.attrNs(parser, RELS_NS, "id") ?: OoxmlXml.attr(parser, "id")
                "tbl" -> parseSlideTable(parser, ctx, tableParas)
            }
            e = parser.next()
        }
        ctx.autoY = y + h + 12f
        if (chartRid != null) {
            val target = ctx.rels[chartRid]?.target
            val chart = target?.let { ctx.pkg.entries[it] }?.let { OoxmlChart.parse(it, ctx.theme) }
            if (chart != null) return OdfSlideElement.Frame(OdfFrame(x, y, w, h, emptyList(), chart = chart))
        }
        if (tableParas.isNotEmpty()) return OdfSlideElement.Frame(OdfFrame(x, y, w, h.coerceAtLeast(20f), tableParas))
        return null
    }

    /** Flattens an a:tbl into one paragraph per row, cells separated by tabs (best-effort). */
    private fun parseSlideTable(parser: XmlPullParser, ctx: SlideCtx, out: MutableList<OdfParagraph>) {
        val depth = parser.depth
        var e = parser.next()
        while (!(e == XmlPullParser.END_TAG && parser.depth == depth && parser.name == "tbl")) {
            if (e == XmlPullParser.END_DOCUMENT) break
            if (e == XmlPullParser.START_TAG && parser.name == "tr") {
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
                        parseTxBody(parser, ctx, cellParas)
                        for (p in cellParas) spans.addAll(p.spans)
                    }
                    ev = parser.next()
                }
                out.add(OdfParagraph(spans.ifEmpty { listOf(OdfSpan("")) }))
            }
            e = parser.next()
        }
    }

    // ---- Text ----

    private fun parseTxBody(parser: XmlPullParser, ctx: SlideCtx, out: MutableList<OdfParagraph>) {
        val depth = parser.depth
        val endTag = parser.name  // txBody or txbx
        var e = parser.next()
        while (!(e == XmlPullParser.END_TAG && parser.depth == depth && parser.name == endTag)) {
            if (e == XmlPullParser.END_DOCUMENT) break
            if (e == XmlPullParser.START_TAG && parser.name == "p") parseDrawingParagraph(parser, ctx)?.let { out.add(it) }
            e = parser.next()
        }
    }

    private fun parseDrawingParagraph(parser: XmlPullParser, ctx: SlideCtx): OdfParagraph? {
        val depth = parser.depth
        val spans = mutableListOf<OdfSpan>()
        var align: TextAlign? = null
        var level = 0
        var listType: ListType? = null
        var bulletChar = "\u2022"
        var numFmt = "1"
        var e = parser.next()
        while (!(e == XmlPullParser.END_TAG && parser.depth == depth && parser.name == "p")) {
            if (e == XmlPullParser.END_DOCUMENT) break
            if (e == XmlPullParser.START_TAG) when (parser.name) {
                "pPr" -> {
                    align = when (OoxmlXml.attr(parser, "algn")) { "ctr" -> TextAlign.Center; "r" -> TextAlign.End; "just" -> TextAlign.Justify; "l" -> TextAlign.Start; else -> null }
                    level = OoxmlXml.attr(parser, "lvl")?.toIntOrNull() ?: 0
                    val bul = parseBullet(parser)
                    listType = bul.first; bulletChar = bul.second ?: bulletChar; numFmt = bul.third ?: numFmt
                }
                "r" -> parseDrawingRun(parser, ctx)?.let { spans.add(it) }
                "br" -> spans.add(OdfSpan("\n"))
                "fld" -> parseDrawingRun(parser, ctx)?.let { spans.add(it) }
            }
            e = parser.next()
        }
        if (spans.isEmpty()) return null
        return OdfParagraph(
            spans = spans,
            alignment = align,
            listLevel = level,
            listType = listType ?: ListType.BULLET,
            style = if (listType != null) ParagraphStyle.LIST_ITEM else ParagraphStyle.BODY,
            listBulletChar = bulletChar,
            listNumberFormat = numFmt
        )
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

    private fun parseDrawingRun(parser: XmlPullParser, ctx: SlideCtx): OdfSpan? {
        val endTag = parser.name  // r or fld
        val depth = parser.depth
        var bold = false; var italic = false; var underline = false; var strike = false
        var color: Long? = null; var size: Float? = null; var font: String? = null
        var superscript = false; var subscript = false; var href: String? = null
        var letterSpacing: Float? = null; var caps = false
        val sb = StringBuilder()
        var e = parser.next()
        while (!(e == XmlPullParser.END_TAG && parser.depth == depth && parser.name == endTag)) {
            if (e == XmlPullParser.END_DOCUMENT) break
            if (e == XmlPullParser.START_TAG) when (parser.name) {
                "rPr", "defRPr", "endParaRPr" -> {
                    if (OoxmlXml.boolAttr(OoxmlXml.attr(parser, "b")) && OoxmlXml.attr(parser, "b") != null) bold = true
                    if (OoxmlXml.attr(parser, "b") == "1") bold = true
                    if (OoxmlXml.attr(parser, "i") == "1") italic = true
                    if (OoxmlXml.attr(parser, "u")?.let { it != "none" } == true) underline = true
                    if (OoxmlXml.attr(parser, "strike")?.let { it != "noStrike" } == true) strike = true
                    OoxmlXml.attr(parser, "sz")?.toFloatOrNull()?.let { size = it / 100f }
                    OoxmlXml.attr(parser, "spc")?.toIntOrNull()?.let { letterSpacing = OoxmlUnits.hundredthPtToPt(it) }
                    when (OoxmlXml.attr(parser, "cap")) { "all", "small" -> caps = true }
                    OoxmlXml.attr(parser, "baseline")?.toIntOrNull()?.let { if (it > 0) superscript = true else if (it < 0) subscript = true }
                    val r = parseRunColorFontLink(parser, ctx)
                    color = color ?: r.first; font = font ?: r.second; href = href ?: r.third
                }
                "t" -> sb.append(OoxmlXml.readElementText(parser, "t"))
            }
            e = parser.next()
        }
        if (sb.isEmpty()) return null
        return OdfSpan(
            text = sb.toString(), bold = bold, italic = italic, underline = underline, strikethrough = strike,
            fontSize = size, fontFamily = font, color = color, superscript = superscript, subscript = subscript,
            href = href, letterSpacing = letterSpacing, textTransform = if (caps) "uppercase" else null
        )
    }

    /** Parses solidFill color, latin font, and hlinkClick target from within an a:rPr. */
    private fun parseRunColorFontLink(parser: XmlPullParser, ctx: SlideCtx): Triple<Long?, String?, String?> {
        val depth = parser.depth
        var color: Long? = null; var font: String? = null; var href: String? = null
        var inFill = false
        var e = parser.next()
        while (!(e == XmlPullParser.END_TAG && parser.depth == depth && (parser.name == "rPr" || parser.name == "defRPr" || parser.name == "endParaRPr"))) {
            if (e == XmlPullParser.END_DOCUMENT) break
            if (e == XmlPullParser.START_TAG) when (parser.name) {
                "solidFill" -> inFill = true
                "latin" -> font = OoxmlXml.attr(parser, "typeface")
                "hlinkClick" -> { val rId = OoxmlXml.attrNs(parser, RELS_NS, "id") ?: OoxmlXml.attr(parser, "id"); href = ctx.rels[rId]?.target }
                in COLOR_TAGS -> if (inFill && color == null) color = OoxmlColor.parse(parser, ctx.theme)
            } else if (e == XmlPullParser.END_TAG && parser.name == "solidFill") inFill = false
            e = parser.next()
        }
        return Triple(color, font, href)
    }

    // ---- Notes ----

    private fun parseNotes(pkg: OoxmlPackage, rels: Map<String, OoxmlPackage.Rel>): List<OdfParagraph> {
        val notesPart = rels.values.firstOrNull { it.type?.endsWith("notesSlide") == true }?.target ?: return emptyList()
        val xml = pkg.entries[notesPart] ?: return emptyList()
        val ctx = SlideCtx(pkg, notesPart, OoxmlTheme.DEFAULT, pkg.relsFor(notesPart))
        val parser = OoxmlXml.newParser(xml)
        val paras = mutableListOf<OdfParagraph>()
        var e = parser.eventType
        var inBody = false
        while (e != XmlPullParser.END_DOCUMENT) {
            if (e == XmlPullParser.START_TAG && parser.name == "txBody") { parseTxBody(parser, ctx, paras); inBody = true }
            e = parser.next()
        }
        @Suppress("UNUSED_VALUE") run { inBody = inBody }
        return paras
    }
}
