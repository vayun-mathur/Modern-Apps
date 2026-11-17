package com.vayunmathur.office.data.odf

import com.vayunmathur.library.ui.odf.OdfBorders
import com.vayunmathur.library.ui.odf.OdfNumberFormat
import org.xmlpull.v1.XmlPullParser

/** XLSX style-table parser (split from OoxmlXlsx for file length; behavior identical). */
internal object OoxmlXlsxStyles {

    internal class Font(
        val bold: Boolean,
        val italic: Boolean,
        val underline: Boolean,
        val strike: Boolean,
        val color: Long?,
        val size: Float?)
    internal class Xf(
        val numFmtId: Int,
        val fontId: Int,
        val fillId: Int,
        val borderId: Int,
        val applyFont: Boolean,
        val applyFill: Boolean,
        val applyBorder: Boolean,
        val applyNumberFormat: Boolean,
        val applyAlignment: Boolean,
        val halign: String?,
        val valign: String?,
        val wrap: Boolean,
        val rotation: Int,
        val indent: Int,
    )
    internal class Dxf(val fill: Long?, val fontColor: Long?)
    internal class StyleTable(
        val fonts: List<Font> = emptyList(),
        val fills: List<Long?> = emptyList(),
        val borders: List<OdfBorders> = emptyList(),
        val numFmts: Map<Int, String> = emptyMap(),
        val cellXfs: List<Xf> = emptyList(),
        val dxfs: List<Dxf> = emptyList()
    ) {
        fun numberFormat(numFmtId: Int): OdfNumberFormat? =
            numFmts[numFmtId]?.let { ExcelNumFmt.parse(it) } ?: ExcelNumFmt.forBuiltin(numFmtId)
        fun isDateFmt(numFmtId: Int): Boolean =
            numFmts[numFmtId]?.let { ExcelNumFmt.parse(it)?.let { f -> f.isDate || f.isTime } == true }
                ?: ExcelNumFmt.isDateTimeBuiltin(numFmtId)
    }

    fun parseStyles(xml: String, theme: OoxmlTheme): StyleTable {
        val parser = OoxmlXml.newParser(xml)
        val table = StyleAcc()
        var section = ""  // "cellXfs" | "dxfs" | "cellStyleXfs"
        var e = parser.eventType
        while (e != XmlPullParser.END_DOCUMENT) {
            if (e == XmlPullParser.START_TAG) {
                section = applyStyleStart(parser, theme, table, section)
            } else if (e == XmlPullParser.END_TAG) when (parser.name) {
                "fonts", "fills", "borders", "cellXfs", "cellStyleXfs", "dxfs" -> section = ""
            }
            e = parser.next()
        }
        return StyleTable(table.fonts, table.fills, table.borders, table.numFmts, table.cellXfs, table.dxfs)
    }

    /** Style-table accumulation state. */
    private class StyleAcc(
        val fonts: MutableList<Font> = mutableListOf(),
        val fills: MutableList<Long?> = mutableListOf(),
        val borders: MutableList<OdfBorders> = mutableListOf(),
        val numFmts: HashMap<Int, String> = HashMap(),
        val cellXfs: MutableList<Xf> = mutableListOf(),
        val dxfs: MutableList<Dxf> = mutableListOf(),
    )

    /** Apply one styles start tag; returns the (possibly updated) section. */
    private fun applyStyleStart(
        parser: XmlPullParser,
        theme: OoxmlTheme,
        table: StyleAcc,
        section: String,
    ): String {
        var s = applyStyleSectionTag(parser, section)
        applyStyleEntryTag(parser, theme, table, s)
        return s
    }

    private fun applyStyleSectionTag(parser: XmlPullParser, section: String): String {
        return when (parser.name) {
            "fonts" -> "fonts"
            "fills" -> "fills"
            "borders" -> "borders"
            "cellStyleXfs" -> "cellStyleXfs"
            "cellXfs" -> "cellXfs"
            "dxfs" -> "dxfs"
            else -> section
        }
    }

    private fun applyStyleEntryTag(parser: XmlPullParser, theme: OoxmlTheme, table: StyleAcc, section: String) {
        when (parser.name) {
            "numFmt" -> applyNumFmtTag(parser, table)
            "font" -> if (section == "fonts") table.fonts.add(parseFont(parser, theme))
            "fill" -> if (section == "fills") table.fills.add(parseFill(parser, theme))
            "border" -> if (section == "borders") table.borders.add(parseXlsxBorder(parser))
            "xf" -> if (section == "cellXfs") table.cellXfs.add(parseXf(parser))
            "dxf" -> if (section == "dxfs") table.dxfs.add(parseDxf(parser, theme))
        }
    }

    private fun applyNumFmtTag(parser: XmlPullParser, table: StyleAcc) {
        val id = OoxmlXml.attr(parser, "numFmtId")?.toIntOrNull()
        val code = OoxmlXml.attr(parser, "formatCode")
        if (id != null && code != null) table.numFmts[id] = code
    }

    private fun parseFont(parser: XmlPullParser, theme: OoxmlTheme): Font {
        val depth = parser.depth
        var bold = false; var italic = false; var underline = false; var strike = false
        var color: Long? = null; var size: Float? = null
        var e = parser.next()
        while (!(e == XmlPullParser.END_TAG && parser.depth == depth && parser.name == "font")) {
            if (e == XmlPullParser.END_DOCUMENT) break
            if (e == XmlPullParser.START_TAG) when (parser.name) {
                "b" -> bold = OoxmlXml.boolAttr(OoxmlXml.attr(parser, "val"))
                "i" -> italic = OoxmlXml.boolAttr(OoxmlXml.attr(parser, "val"))
                "u" -> underline = OoxmlXml.attr(parser, "val") != "none"
                "strike" -> strike = OoxmlXml.boolAttr(OoxmlXml.attr(parser, "val"))
                "sz" -> size = OoxmlXml.attr(parser, "val")?.toFloatOrNull()
                "color" -> color = parseColorAttr(parser, theme)
            }
            e = parser.next()
        }
        return Font(bold, italic, underline, strike, color, size)
    }

    private fun parseFill(parser: XmlPullParser, theme: OoxmlTheme): Long? {
        val depth = parser.depth
        var fg: Long? = null; var patternType: String? = null
        var e = parser.next()
        while (!(e == XmlPullParser.END_TAG && parser.depth == depth && parser.name == "fill")) {
            if (e == XmlPullParser.END_DOCUMENT) break
            if (e == XmlPullParser.START_TAG) when (parser.name) {
                "patternFill" -> patternType = OoxmlXml.attr(parser, "patternType")
                "fgColor" -> fg = parseColorAttr(parser, theme)
            }
            e = parser.next()
        }
        return if (patternType == null || patternType == "none") null else fg
    }

    private fun parseXlsxBorder(parser: XmlPullParser): OdfBorders {
        val depth = parser.depth
        var top: String? = null; var bottom: String? = null; var left: String? = null; var right: String? = null
        var e = parser.next()
        while (!(e == XmlPullParser.END_TAG && parser.depth == depth && parser.name == "border")) {
            if (e == XmlPullParser.END_DOCUMENT) break
            if (e == XmlPullParser.START_TAG) {
                val edge = parser.name
                if (edge in setOf("top", "bottom", "left", "right")) {
                    val style = OoxmlXml.attr(parser, "style")
                    if (style != null && style != "none") {
                        val kind = borderStyleKind(style)
                        val v = "%.2fpt %s #000000".format(borderWeight(style), kind)
                        when (edge) {
                            "top" -> top = v
                            "bottom" -> bottom = v
                            "left" -> left = v
                            "right" -> right = v
                        }
                    }
                }
            }
            e = parser.next()
        }
        return OdfBorders(top, right, bottom, left)
    }

    private const val BORDER_THIN = 0.5f
    private const val BORDER_MEDIUM = 1.5f
    private const val BORDER_THICK = 2.5f
    private const val BORDER_DEFAULT = 1f
    private const val PALETTE_SYSTEM_FG = 64
    private const val PALETTE_SYSTEM_BG = 65
    private const val SYSTEM_FG_RGB = 0x000000L
    private const val SYSTEM_BG_RGB = 0xFFFFFFL
    private const val FULL_ALPHA = 0xFF000000L
    private const val OOXML_THOUSANDTHS = 100000

    private fun borderWeight(style: String): Float = when (style) {
        "thin", "hair" -> BORDER_THIN
        "medium", "mediumDashed" -> BORDER_MEDIUM
        "thick" -> BORDER_THICK
        else -> BORDER_DEFAULT
    }

    private fun borderStyleKind(style: String): String = when {
        style.contains("dash", true) -> "dashed"
        style.contains("dot", true) -> "dotted"
        style == "double" -> "double"
        else -> "solid"
    }

    private fun parseXf(parser: XmlPullParser): Xf {
        val depth = parser.depth
        val numFmtId = OoxmlXml.attr(parser, "numFmtId")?.toIntOrNull() ?: 0
        val fontId = OoxmlXml.attr(parser, "fontId")?.toIntOrNull() ?: 0
        val fillId = OoxmlXml.attr(parser, "fillId")?.toIntOrNull() ?: 0
        val borderId = OoxmlXml.attr(parser, "borderId")?.toIntOrNull() ?: 0
        val applyFont = OoxmlXml.boolAttr(OoxmlXml.attr(parser, "applyFont")) && OoxmlXml.attr(
            parser,
            "applyFont") != null
        val applyFill = OoxmlXml.attr(parser, "applyFill") == "1"
        val applyBorder = OoxmlXml.attr(parser, "applyBorder") == "1"
        val applyNum = OoxmlXml.attr(parser, "applyNumberFormat") == "1"
        val applyAlign = OoxmlXml.attr(parser, "applyAlignment") == "1"
        val align = parseXfAlignment(parser, depth)
        return Xf(
            numFmtId,
            fontId,
            fillId,
            borderId,
            applyFont,
            applyFill,
            applyBorder,
            applyNum,
            applyAlign,
            align.first,
            align.second,
            align.third,
            align.fourth,
            align.fifth)
    }

    /** Alignment child of an xf (halign, valign, wrap, rotation, indent). */
    private fun parseXfAlignment(
        parser: XmlPullParser,
        depth: Int,
    ): Quintuple<String?, String?, Boolean, Int, Int> {
        var halign: String? = null; var valign: String? = null; var wrap = false; var rotation = 0; var indent = 0
        var e = parser.next()
        while (!(e == XmlPullParser.END_TAG && parser.depth == depth && parser.name == "xf")) {
            if (e == XmlPullParser.END_DOCUMENT) break
            if (e == XmlPullParser.START_TAG && parser.name == "alignment") {
                halign = OoxmlXml.attr(parser, "horizontal")
                valign = OoxmlXml.attr(parser, "vertical")
                wrap = OoxmlXml.attr(parser, "wrapText") == "1"
                rotation = OoxmlXml.attr(parser, "textRotation")?.toIntOrNull() ?: 0
                indent = OoxmlXml.attr(parser, "indent")?.toIntOrNull() ?: 0
            }
            e = parser.next()
        }
        return Quintuple(halign, valign, wrap, rotation, indent)
    }

    /** Five-element tuple. */
    private data class Quintuple<A, B, C, D, E>(
        val first: A,
        val second: B,
        val third: C,
        val fourth: D,
        val fifth: E,
    )

    private fun parseDxf(parser: XmlPullParser, theme: OoxmlTheme): Dxf {
        val depth = parser.depth
        var fill: Long? = null; var fontColor: Long? = null; var inFont = false
        var e = parser.next()
        while (!(e == XmlPullParser.END_TAG && parser.depth == depth && parser.name == "dxf")) {
            if (e == XmlPullParser.END_DOCUMENT) break
            if (e == XmlPullParser.START_TAG) when (parser.name) {
                "font" -> inFont = true
                "color" -> if (inFont) fontColor = parseColorAttr(parser, theme)
                "fgColor", "bgColor" -> if (fill == null) fill = parseColorAttr(parser, theme)
            } else if (e == XmlPullParser.END_TAG && parser.name == "font") inFont = false
            e = parser.next()
        }
        return Dxf(fill, fontColor)
    }

    /** Reads an xlsx color attribute set (rgb / theme+tint / indexed) on the current element. */
    internal fun parseColorAttr(parser: XmlPullParser, theme: OoxmlTheme): Long? {
        OoxmlUnits.hexColor(OoxmlXml.attr(parser, "rgb"))?.let { return it }
        val themeIdx = OoxmlXml.attr(parser, "theme")?.toIntOrNull()
        if (themeIdx != null) {
            val base = theme.colors[themeSlot(themeIdx)] ?: return null
            val tint = OoxmlXml.attr(parser, "tint")?.toDoubleOrNull() ?: 0.0
            return applyExcelTint(base, tint)
        }
        OoxmlXml.attr(parser, "indexed")?.toIntOrNull()?.let { return indexedColor(it) }
        return null
    }

    /** Resolves a legacy indexed color (BIFF8 default palette) to 0xFFRRGGBB. */
    private fun indexedColor(idx: Int): Long? {
        val rgb = INDEXED_PALETTE.getOrNull(idx) ?: when (idx) {
            PALETTE_SYSTEM_FG -> SYSTEM_FG_RGB   // system foreground
            PALETTE_SYSTEM_BG -> SYSTEM_BG_RGB   // system background
            else -> return null
        }
        return FULL_ALPHA or rgb
    }

    private val INDEXED_PALETTE = longArrayOf(
        0x000000, 0xFFFFFF, 0xFF0000, 0x00FF00, 0x0000FF, 0xFFFF00, 0xFF00FF, 0x00FFFF,
        0x000000, 0xFFFFFF, 0xFF0000, 0x00FF00, 0x0000FF, 0xFFFF00, 0xFF00FF, 0x00FFFF,
        0x800000, 0x008000, 0x000080, 0x808000, 0x800080, 0x008080, 0xC0C0C0, 0x808080,
        0x9999FF, 0x993366, 0xFFFFCC, 0xCCFFFF, 0x660066, 0xFF8080, 0x0066CC, 0xCCCCFF,
        0x000080, 0xFF00FF, 0xFFFF00, 0x00FFFF, 0x800080, 0x800000, 0x008080, 0x0000FF,
        0x00CCFF, 0xCCFFFF, 0xCCFFCC, 0xFFFF99, 0x99CCFF, 0xFF99CC, 0xCC99FF, 0xFFCC99,
        0x3366FF, 0x33CCCC, 0x99CC00, 0xFFCC00, 0xFF9900, 0xFF6600, 0x666699, 0x969696,
        0x003366, 0x339966, 0x003300, 0x333300, 0x993300, 0x993366, 0x333399, 0x333333
    )

    private fun themeSlot(idx: Int): String = THEME_SLOTS[idx] ?: "dk1"

    private val THEME_SLOTS = mapOf(
        0 to "lt1", 1 to "dk1", 2 to "lt2", 3 to "dk2",
        4 to "accent1", 5 to "accent2", 6 to "accent3",
        7 to "accent4", 8 to "accent5", 9 to "accent6",
        10 to "hlink", 11 to "folhlink",
    )

    private fun applyExcelTint(base: Long, tint: Double): Long {
        if (tint == 0.0) return base
        val scaled = ((1 - kotlin.math.abs(tint)) * OOXML_THOUSANDTHS).toInt()
        return if (tint < 0) {
            OoxmlUnits.applyTransforms(base, shade = scaled)
        } else {
            OoxmlUnits.applyTransforms(base, tint = scaled)
        }
    }
}
