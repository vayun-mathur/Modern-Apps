package com.vayunmathur.office.odf

import com.vayunmathur.library.ui.odf.OdfBorders
import com.vayunmathur.library.ui.odf.OdfTabStop
import org.xmlpull.v1.XmlPullParser

/**
 * DOCX style/numbering model and property parsers (extracted from [OoxmlDocx]
 * to keep files under the repo file-length limit; behavior identical).
 *
 * Holds run/paragraph property holders with style-inheritance overlays, the
 * styles/numbering model, and all w:rPr / w:pPr / styles.xml / numbering.xml
 * parsing plus underline/numbering/theme mapping helpers.
 */
internal object OoxmlDocxStyles {

    private const val CHANNEL_MAX = 255f
    private const val OOXML_THOUSANDTHS = 100000
    private const val DEFAULT_DROP_CAP_LINES = 3
    private const val WINGDINGS_MIDDOT = 0xF0B7
    private const val UNICODE_BULLET = 0x2022
    private const val WINGDINGS_SQUARE = 0xF0A7
    private const val UNICODE_SQUARE = 0x25AA
    private const val WINGDINGS_CIRCLE = 0xF06F
    private const val UNICODE_CIRCLE = 0x006F
    private const val WINGDINGS_ARROW = 0xF0D8
    private const val CONTROL_MAX = 0x20

    // ---- Run / paragraph property holders (for style inheritance) ----

    internal class RPr(
        var bold: Boolean? = null,
        var italic: Boolean? = null,
        var underline: String? = null,       // ODF underline style, or "none"
        var underlineColor: Long? = null,
        var strike: Boolean? = null,
        var color: Long? = null,
        var sizeHalfPt: Int? = null,
        var font: String? = null,
        var vertAlign: String? = null,        // "superscript"/"subscript"
        var caps: Boolean? = null,
        var smallCaps: Boolean? = null,
        var spacingTwips: Int? = null,
        var highlight: Long? = null,
        var shdFill: Long? = null,
        var vanish: Boolean? = null,
        var lang: String? = null,
        var styleId: String? = null           // w:rStyle reference (character style)
    ) {
        /** Returns a new RPr with [o]'s non-null values overriding this one's. */
        fun overlay(o: RPr) = RPr(
            bold = o.bold ?: bold,
            italic = o.italic ?: italic,
            underline = o.underline ?: underline,
            underlineColor = o.underlineColor ?: underlineColor,
            strike = o.strike ?: strike,
            color = o.color ?: color,
            sizeHalfPt = o.sizeHalfPt ?: sizeHalfPt,
            font = o.font ?: font,
            vertAlign = o.vertAlign ?: vertAlign,
            caps = o.caps ?: caps,
            smallCaps = o.smallCaps ?: smallCaps,
            spacingTwips = o.spacingTwips ?: spacingTwips,
            highlight = o.highlight ?: highlight,
            shdFill = o.shdFill ?: shdFill,
            vanish = o.vanish ?: vanish,
            lang = o.lang ?: lang,
            styleId = o.styleId ?: styleId,
        )
    }

    internal class PPr(
        var styleId: String? = null,
        var jc: String? = null,
        var indLeft: Int? = null,
        var indRight: Int? = null,
        var indFirstLine: Int? = null,
        var indHanging: Int? = null,
        var spacingBefore: Int? = null,
        var spacingAfter: Int? = null,
        var lineRule: String? = null,
        var line: Int? = null,
        var bidi: Boolean? = null,
        var shdFill: Long? = null,
        var borders: OdfBorders? = null,
        var keepNext: Boolean? = null,
        var keepLines: Boolean? = null,
        var widowControl: Boolean? = null,
        var pageBreakBefore: Boolean? = null,
        var tabs: List<OdfTabStop>? = null,
        var numId: Int? = null,
        var ilvl: Int? = null,
        var outlineLvl: Int? = null,
        var dropCapLines: Int? = null,
        var rPr: RPr? = null
    ) {
        /** Returns a new PPr with [o]'s non-null values overriding this one's. */
        fun overlay(o: PPr) = PPr(
            styleId = o.styleId ?: styleId,
            jc = o.jc ?: jc,
            indLeft = o.indLeft ?: indLeft,
            indRight = o.indRight ?: indRight,
            indFirstLine = o.indFirstLine ?: indFirstLine,
            indHanging = o.indHanging ?: indHanging,
            spacingBefore = o.spacingBefore ?: spacingBefore,
            spacingAfter = o.spacingAfter ?: spacingAfter,
            lineRule = o.lineRule ?: lineRule,
            line = o.line ?: line,
            bidi = o.bidi ?: bidi,
            shdFill = o.shdFill ?: shdFill,
            borders = o.borders ?: borders,
            keepNext = o.keepNext ?: keepNext,
            keepLines = o.keepLines ?: keepLines,
            widowControl = o.widowControl ?: widowControl,
            pageBreakBefore = o.pageBreakBefore ?: pageBreakBefore,
            tabs = o.tabs ?: tabs,
            numId = o.numId ?: numId,
            ilvl = o.ilvl ?: ilvl,
            outlineLvl = o.outlineLvl ?: outlineLvl,
            dropCapLines = o.dropCapLines ?: dropCapLines,
            rPr = overlayRun(o),
        )

        /** Run properties overlay. */
        private fun overlayRun(o: PPr): RPr? {
            val base = rPr ?: RPr()
            return o.rPr?.let { base.overlay(it) } ?: base
        }
    }

    internal class StyleDef(
        val id: String, val type: String?, val basedOn: String?,
        val rpr: RPr, val ppr: PPr, val outlineLvl: Int?, val name: String?
    )

    internal class Styles(
        val byId: Map<String, StyleDef>,
        val docDefaultRPr: RPr,
        val docDefaultPPr: PPr,
        val defaultParaStyle: String?
    ) {
        private val rprCache = HashMap<String, RPr>()
        private val pprCache = HashMap<String, PPr>()

        fun resolvedRPr(id: String?): RPr {
            if (id == null) return docDefaultRPr
            rprCache[id]?.let { return it }
            val chain = chain(id)
            var acc = docDefaultRPr
            for (s in chain) acc = acc.overlay(s.rpr)
            rprCache[id] = acc
            return acc
        }

        /** RPr contributed by a character style's basedOn chain only (no docDefaults). */
        fun charStyleRPr(id: String?): RPr {
            if (id == null) return RPr()
            var acc = RPr()
            for (s in chain(id)) acc = acc.overlay(s.rpr)
            return acc
        }

        fun resolvedPPr(id: String?): PPr {
            if (id == null) return docDefaultPPr
            pprCache[id]?.let { return it }
            val chain = chain(id)
            var acc = docDefaultPPr
            for (s in chain) acc = acc.overlay(s.ppr)
            pprCache[id] = acc
            return acc
        }

        fun outlineLvl(id: String?): Int? {
            var cur = id?.let { byId[it] }
            val seen = HashSet<String>()
            while (cur != null && seen.add(cur.id)) {
                cur.outlineLvl?.let { return it }
                cur = cur.basedOn?.let { byId[it] }
            }
            return null
        }

        /** basedOn chain from root ancestor down to [id]. */
        private fun chain(id: String): List<StyleDef> {
            val out = ArrayDeque<StyleDef>()
            var cur = byId[id]
            val seen = HashSet<String>()
            while (cur != null && seen.add(cur.id)) {
                out.addFirst(cur)
                cur = cur.basedOn?.let { byId[it] }
            }
            return out.toList()
        }
    }

    // ---- Numbering ----

    internal class NumLevel(val numFmt: String, val lvlText: String, val start: Int)
    internal class Numbering(
        private val numToAbstract: Map<Int, Int>,
        private val abstractLevels: Map<Int, Map<Int, NumLevel>>
    ) {
        fun level(numId: Int, ilvl: Int): NumLevel? =
            numToAbstract[numId]?.let { abstractLevels[it]?.get(ilvl) }
    }

    // ---- Property parsers ----

    internal fun parseRPr(parser: XmlPullParser, theme: OoxmlTheme): RPr {
        val depth = parser.depth
        val r = RPr()
        var e = parser.next()
        val endTags = setOf("rPr", "defRPr")
        while (!(e == XmlPullParser.END_TAG && parser.depth == depth && parser.name in endTags)) {
            if (e == XmlPullParser.END_DOCUMENT) break
            if (e == XmlPullParser.START_TAG) applyRPrTag(parser, theme, r)
            e = parser.next()
        }
        return r
    }

    /** Apply one rPr child tag. */
    private fun applyRPrTag(parser: XmlPullParser, theme: OoxmlTheme, r: RPr) {
        if (applyRPrStyleTag(parser, theme, r)) return
        if (applyRPrDecorTag(parser, r)) return
        applyRPrTextTag(parser, r)
    }

    private fun applyRPrStyleTag(parser: XmlPullParser, theme: OoxmlTheme, r: RPr): Boolean {
        when (parser.name) {
            "rStyle" -> r.styleId = OoxmlXml.attr(parser, "val")
            "b" -> r.bold = OoxmlXml.boolAttr(OoxmlXml.attr(parser, "val"))
            "i" -> r.italic = OoxmlXml.boolAttr(OoxmlXml.attr(parser, "val"))
            "strike" -> r.strike = OoxmlXml.boolAttr(OoxmlXml.attr(parser, "val"))
            "dstrike" -> if (OoxmlXml.boolAttr(OoxmlXml.attr(parser, "val"))) r.strike = true
            "u" -> applyUnderline(parser, r)
            "color" -> r.color = resolveWColor(parser, theme)
            else -> return false
        }
        return true
    }

    private fun applyRPrDecorTag(parser: XmlPullParser, r: RPr): Boolean {
        when (parser.name) {
            "sz" -> r.sizeHalfPt = OoxmlXml.attr(parser, "val")?.toIntOrNull()
            "rFonts" -> r.font = OoxmlXml.attr(parser, "ascii") ?: OoxmlXml.attr(parser, "hAnsi") ?: OoxmlXml.attr(
                parser,
                "cs")
            "vertAlign" -> r.vertAlign = vertAlignOf(parser)
            "caps" -> r.caps = OoxmlXml.boolAttr(OoxmlXml.attr(parser, "val"))
            "smallCaps" -> r.smallCaps = OoxmlXml.boolAttr(OoxmlXml.attr(parser, "val"))
            "spacing" -> r.spacingTwips = OoxmlXml.attr(parser, "val")?.toIntOrNull()
            else -> return false
        }
        return true
    }

    private fun applyRPrTextTag(parser: XmlPullParser, r: RPr) {
        when (parser.name) {
            "highlight" -> r.highlight = OoxmlUnits.highlightColor(OoxmlXml.attr(parser, "val"))
            "shd" -> r.shdFill = OoxmlUnits.hexColor(OoxmlXml.attr(parser, "fill"))
            "vanish" -> r.vanish = OoxmlXml.boolAttr(OoxmlXml.attr(parser, "val"))
            "lang" -> r.lang = OoxmlXml.attr(parser, "val")
        }
    }

    /** Underline + color from a w:u tag. */
    private fun applyUnderline(parser: XmlPullParser, r: RPr) {
        val v = OoxmlXml.attr(parser, "val")
        r.underline = mapUnderline(v)
        r.underlineColor = OoxmlUnits.hexColor(OoxmlXml.attr(parser, "color"))
    }

    /** Vertical alignment from a w:vertAlign tag. */
    private fun vertAlignOf(parser: XmlPullParser): String? = when (OoxmlXml.attr(
        parser,
        "val")) { "superscript" -> "superscript"; "subscript" -> "subscript"; else -> null }

    internal fun resolveWColor(parser: XmlPullParser, theme: OoxmlTheme): Long? {
        OoxmlUnits.hexColor(OoxmlXml.attr(parser, "val"))?.let { return it }
        val themeColor = OoxmlXml.attr(parser, "themeColor") ?: return null
        val base = theme.schemeColor(mapWordTheme(themeColor)) ?: return null
        val tint = OoxmlXml.attr(parser, "themeTint")?.toIntOrNull(16)
        val shade = OoxmlXml.attr(parser, "themeShade")?.toIntOrNull(16)
        return when {
            tint != null -> OoxmlUnits.applyTransforms(base, tint = (tint / CHANNEL_MAX * OOXML_THOUSANDTHS).toInt())
            shade != null -> {
                OoxmlUnits.applyTransforms(base, shade = (shade / CHANNEL_MAX * OOXML_THOUSANDTHS).toInt())
            }
            else -> base
        }
    }

    internal fun parsePPr(parser: XmlPullParser, theme: OoxmlTheme): PPr {
        val depth = parser.depth
        val p = PPr()
        var e = parser.next()
        while (!(e == XmlPullParser.END_TAG && parser.depth == depth && parser.name == "pPr")) {
            if (e == XmlPullParser.END_DOCUMENT) break
            if (e == XmlPullParser.START_TAG) applyPPrTag(parser, theme, p)
            e = parser.next()
        }
        return p
    }

    /** Apply one pPr child tag. */
    private fun applyPPrTag(parser: XmlPullParser, theme: OoxmlTheme, p: PPr) {
        if (applyPPrStyleTag(parser, theme, p)) return
        applyPPrLayoutTag(parser, p)
    }

    private fun applyPPrStyleTag(parser: XmlPullParser, theme: OoxmlTheme, p: PPr): Boolean {
        when (parser.name) {
            "pStyle" -> p.styleId = OoxmlXml.attr(parser, "val")
            "shd" -> p.shdFill = OoxmlUnits.hexColor(OoxmlXml.attr(parser, "fill"))
            "pBdr" -> p.borders = parseBorders(parser, "pBdr")
            "numPr" -> parseNumPr(parser, p)
            "tabs" -> p.tabs = parseTabs(parser)
            "framePr" -> applyFramePr(parser, p)
            "rPr" -> p.rPr = parseRPr(parser, theme)
            else -> return false
        }
        return true
    }

    private fun applyPPrLayoutTag(parser: XmlPullParser, p: PPr) {
        when (parser.name) {
            "jc" -> p.jc = OoxmlXml.attr(parser, "val")
            "bidi" -> p.bidi = OoxmlXml.boolAttr(OoxmlXml.attr(parser, "val"))
            "keepNext" -> p.keepNext = OoxmlXml.boolAttr(OoxmlXml.attr(parser, "val"))
            "keepLines" -> p.keepLines = OoxmlXml.boolAttr(OoxmlXml.attr(parser, "val"))
            "widowControl" -> p.widowControl = OoxmlXml.boolAttr(OoxmlXml.attr(parser, "val"))
            "pageBreakBefore" -> p.pageBreakBefore = OoxmlXml.boolAttr(OoxmlXml.attr(parser, "val"))
            "outlineLvl" -> p.outlineLvl = OoxmlXml.attr(parser, "val")?.toIntOrNull()
            "ind" -> applyInd(parser, p)
            "spacing" -> applySpacing(parser, p)
        }
    }

    /** Indentation attributes. */
    private fun applyInd(parser: XmlPullParser, p: PPr) {
        p.indLeft = (OoxmlXml.attr(parser, "left") ?: OoxmlXml.attr(parser, "start"))?.toIntOrNull()
        p.indRight = (OoxmlXml.attr(parser, "right") ?: OoxmlXml.attr(parser, "end"))?.toIntOrNull()
        p.indFirstLine = OoxmlXml.attr(parser, "firstLine")?.toIntOrNull()
        p.indHanging = OoxmlXml.attr(parser, "hanging")?.toIntOrNull()
    }

    /** Spacing attributes. */
    private fun applySpacing(parser: XmlPullParser, p: PPr) {
        p.spacingBefore = OoxmlXml.attr(parser, "before")?.toIntOrNull()
        p.spacingAfter = OoxmlXml.attr(parser, "after")?.toIntOrNull()
        p.line = OoxmlXml.attr(parser, "line")?.toIntOrNull()
        p.lineRule = OoxmlXml.attr(parser, "lineRule")
    }

    /** Drop-cap frame attributes. */
    private fun applyFramePr(parser: XmlPullParser, p: PPr) {
        OoxmlXml.attr(
            parser,
            "dropCap")
        ?.let { if (it != "none") {
            p.dropCapLines = OoxmlXml.attr(parser, "lines")?.toIntOrNull() ?: DEFAULT_DROP_CAP_LINES
        } }
    }

    internal fun parseNumPr(parser: XmlPullParser, p: PPr) {
        val depth = parser.depth
        var e = parser.next()
        while (!(e == XmlPullParser.END_TAG && parser.depth == depth && parser.name == "numPr")) {
            if (e == XmlPullParser.END_DOCUMENT) break
            if (e == XmlPullParser.START_TAG) when (parser.name) {
                "ilvl" -> p.ilvl = OoxmlXml.attr(parser, "val")?.toIntOrNull()
                "numId" -> p.numId = OoxmlXml.attr(parser, "val")?.toIntOrNull()
            }
            e = parser.next()
        }
    }

    internal fun parseTabs(parser: XmlPullParser): List<OdfTabStop> {
        val depth = parser.depth
        val tabs = mutableListOf<OdfTabStop>()
        var e = parser.next()
        while (!(e == XmlPullParser.END_TAG && parser.depth == depth && parser.name == "tabs")) {
            if (e == XmlPullParser.END_DOCUMENT) break
            if (e == XmlPullParser.START_TAG && parser.name == "tab") parseTabTag(parser)?.let { tabs.add(it) }
            e = parser.next()
        }
        return tabs
    }

    private fun parseTabTag(parser: XmlPullParser): OdfTabStop? {
        val pos = OoxmlXml.attr(parser, "pos")?.toIntOrNull()
        val valType = OoxmlXml.attr(parser, "val")
        if (pos == null || valType == "clear") return null
        return OdfTabStop(
            position = OoxmlUnits.twipsToPx(pos),
            type = tabTypeFor(valType),
            leaderChar = tabLeaderFor(OoxmlXml.attr(parser, "leader"))
        )
    }

    private fun tabTypeFor(valType: String?): String = when (valType) {
        "center" -> "center"
        "right", "end" -> "right"
        "decimal" -> "char"
        else -> "left"
    }

    private fun tabLeaderFor(leader: String?): String? = when (leader) {
        "dot" -> "."
        "hyphen" -> "-"
        "underscore" -> "_"
        else -> null
    }

    internal fun parseBorders(parser: XmlPullParser, endTag: String): OdfBorders {
        val depth = parser.depth
        var top: String? = null; var right: String? = null; var bottom: String? = null; var left: String? = null
        var e = parser.next()
        while (!(e == XmlPullParser.END_TAG && parser.depth == depth && parser.name == endTag)) {
            if (e == XmlPullParser.END_DOCUMENT) break
            if (e == XmlPullParser.START_TAG) {
                val edge = edgeFor(parser.name)
                if (edge != null) {
                    val v = borderValue(parser)
                    when (edge) { "top" -> top = v; "bottom" -> bottom = v; "left" -> left = v; "right" -> right = v }
                }
            }
            e = parser.next()
        }
        return OdfBorders(top, right, bottom, left)
    }

    /** Border edge name, or null for unhandled tags. */
    private fun edgeFor(tag: String): String? = when (tag) {
        "top" -> "top"
        "bottom" -> "bottom"
        "left", "start" -> "left"
        "right", "end" -> "right"
        else -> null
    }

    internal fun borderValue(parser: XmlPullParser): String? {
        val style = OoxmlXml.attr(parser, "val") ?: return null
        if (style == "nil" || style == "none") return null
        val szEighthPt = OoxmlXml.attr(parser, "sz")?.toIntOrNull() ?: 4
        val pt = szEighthPt / 8f
        val color = OoxmlXml.attr(parser, "color")?.takeIf { !it.equals("auto", true) }?.let { "#$it" } ?: "#000000"
        val odfStyle = when (style) {
            "single" -> "solid"
            "double" -> "double"
            "dotted" -> "dotted"
            "dashed" -> "dashed"
            else -> "solid"
        }
        // Force a dot decimal separator; a locale-formatted "0,50pt" is an invalid fo:border value.
        return "%.2fpt %s %s".format(java.util.Locale.ROOT, pt, odfStyle, color)
    }

    // ---- Styles / numbering parsing ----

    internal fun parseStyles(xml: String, theme: OoxmlTheme): Styles {
        val parser = OoxmlXml.newParser(xml)
        val byId = LinkedHashMap<String, StyleDef>()
        var docRPr = RPr(); var docPPr = PPr(); var defaultPara: String? = null
        var e = parser.eventType
        while (e != XmlPullParser.END_DOCUMENT) {
            if (e == XmlPullParser.START_TAG) when (parser.name) {
                "docDefaults" -> { val d = parseDocDefaults(parser, theme); docRPr = d.first; docPPr = d.second }
                "style" -> {
                    val isDefault = OoxmlXml.attr(parser, "default") == "1"
                    val styleType = OoxmlXml.attr(parser, "type")
                    val def = parseStyle(parser, theme)
                    byId[def.id] = def
                    if (isDefault && styleType == "paragraph") defaultPara = def.id
                }
            }
            e = parser.next()
        }
        return Styles(byId, docRPr, docPPr, defaultPara)
    }

    internal fun parseDocDefaults(parser: XmlPullParser, theme: OoxmlTheme): Pair<RPr, PPr> {
        val depth = parser.depth
        var rpr = RPr(); var ppr = PPr()
        var e = parser.next()
        while (!(e == XmlPullParser.END_TAG && parser.depth == depth && parser.name == "docDefaults")) {
            if (e == XmlPullParser.END_DOCUMENT) break
            if (e == XmlPullParser.START_TAG) when (parser.name) {
                "rPr" -> rpr = parseRPr(parser, theme)
                "pPr" -> ppr = parsePPr(parser, theme)
            }
            e = parser.next()
        }
        return rpr to ppr
    }

    internal fun parseStyle(parser: XmlPullParser, theme: OoxmlTheme): StyleDef {
        val depth = parser.depth
        val type = OoxmlXml.attr(parser, "type")
        val id = OoxmlXml.attr(parser, "styleId") ?: ""
        var basedOn: String? = null; var name: String? = null; var outline: Int? = null
        var rpr = RPr(); var ppr = PPr()
        var e = parser.next()
        while (!(e == XmlPullParser.END_TAG && parser.depth == depth && parser.name == "style")) {
            if (e == XmlPullParser.END_DOCUMENT) break
            if (e == XmlPullParser.START_TAG) when (parser.name) {
                "basedOn" -> basedOn = OoxmlXml.attr(parser, "val")
                "name" -> name = OoxmlXml.attr(parser, "val")
                "rPr" -> rpr = parseRPr(parser, theme)
                "pPr" -> { ppr = parsePPr(parser, theme); outline = ppr.outlineLvl }
            }
            e = parser.next()
        }
        return StyleDef(id, type, basedOn, rpr, ppr, outline, name)
    }

    internal fun parseNumbering(xml: String): Numbering {
        val parser = OoxmlXml.newParser(xml)
        val numToAbstract = HashMap<Int, Int>()
        val abstractLevels = HashMap<Int, MutableMap<Int, NumLevel>>()
        var e = parser.eventType
        while (e != XmlPullParser.END_DOCUMENT) {
            if (e == XmlPullParser.START_TAG) when (parser.name) {
                "abstractNum" -> {
                    val aid = OoxmlXml.attr(parser, "abstractNumId")?.toIntOrNull()
                    if (aid != null) abstractLevels[aid] = parseAbstractNum(parser)
                }
                "num" -> {
                    val numId = OoxmlXml.attr(parser, "numId")?.toIntOrNull()
                    val aid = readAbstractNumId(parser)
                    if (numId != null && aid != null) numToAbstract[numId] = aid
                }
            }
            e = parser.next()
        }
        return Numbering(numToAbstract, abstractLevels)
    }

    internal fun readAbstractNumId(parser: XmlPullParser): Int? {
        val depth = parser.depth
        var aid: Int? = null
        var e = parser.next()
        while (!(e == XmlPullParser.END_TAG && parser.depth == depth && parser.name == "num")) {
            if (e == XmlPullParser.END_DOCUMENT) break
            if (e == XmlPullParser.START_TAG && parser.name == "abstractNumId") aid = OoxmlXml.attr(
                parser,
                "val")?.toIntOrNull()
            e = parser.next()
        }
        return aid
    }

    internal fun parseAbstractNum(parser: XmlPullParser): MutableMap<Int, NumLevel> {
        val depth = parser.depth
        val levels = HashMap<Int, NumLevel>()
        var e = parser.next()
        while (!(e == XmlPullParser.END_TAG && parser.depth == depth && parser.name == "abstractNum")) {
            if (e == XmlPullParser.END_DOCUMENT) break
            if (e == XmlPullParser.START_TAG && parser.name == "lvl") {
                val ilvl = OoxmlXml.attr(parser, "ilvl")?.toIntOrNull() ?: 0
                levels[ilvl] = parseLvl(parser)
            }
            e = parser.next()
        }
        return levels
    }

    internal fun parseLvl(parser: XmlPullParser): NumLevel {
        val depth = parser.depth
        var numFmt = "decimal"; var lvlText = "%1."; var start = 1
        var e = parser.next()
        while (!(e == XmlPullParser.END_TAG && parser.depth == depth && parser.name == "lvl")) {
            if (e == XmlPullParser.END_DOCUMENT) break
            if (e == XmlPullParser.START_TAG) when (parser.name) {
                "numFmt" -> numFmt = OoxmlXml.attr(parser, "val") ?: numFmt
                "lvlText" -> lvlText = OoxmlXml.attr(parser, "val") ?: lvlText
                "start" -> start = OoxmlXml.attr(parser, "val")?.toIntOrNull() ?: start
            }
            e = parser.next()
        }
        return NumLevel(numFmt, lvlText, start)
    }

    // ---- Mapping helpers ----

    internal fun mapUnderline(v: String?): String? = when (v) {
        null -> "solid"
        "none" -> "none"
        "single", "words" -> "solid"
        "double" -> "double"
        "dotted", "dottedHeavy" -> "dotted"
        "dash", "dashLong", "dashedHeavy" -> "dash"
        "wave", "wavyHeavy", "wavyDouble" -> "wave"
        else -> "solid"
    }

    internal fun mapNumFmt(fmt: String): String = when (fmt) {
        "decimal", "decimalZero" -> "1"
        "lowerLetter" -> "a"
        "upperLetter" -> "A"
        "lowerRoman" -> "i"
        "upperRoman" -> "I"
        else -> "1"
    }

    internal fun mapBullet(lvlText: String): String {
        val ch = lvlText.firstOrNull() ?: return "•"
        return when (ch.code) {
            WINGDINGS_MIDDOT, UNICODE_BULLET -> "•"
            WINGDINGS_SQUARE, UNICODE_SQUARE -> "▪"
            WINGDINGS_CIRCLE, UNICODE_CIRCLE -> "◦"
            WINGDINGS_ARROW -> "➢"
            else -> if (ch.isLetterOrDigit() || ch.code < CONTROL_MAX) "•" else ch.toString()
        }
    }

    internal fun mapWordTheme(name: String): String = when (name.lowercase()) {
        "text1", "dark1" -> "dk1"
        "background1", "light1" -> "lt1"
        "text2", "dark2" -> "dk2"
        "background2", "light2" -> "lt2"
        "hyperlink" -> "hlink"
        "followedhyperlink" -> "folhlink"
        else -> name
    }
}
