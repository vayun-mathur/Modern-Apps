package com.vayunmathur.office.odf

import androidx.compose.ui.text.style.TextAlign
import com.vayunmathur.library.ui.odf.*
import org.xmlpull.v1.XmlPullParser

/**
 * XLSX importer (Groups 6-8, phases X1-X9). Resolves cell styles (fonts/fills/borders/number
 * formats/alignment), formulas & value types, sheet layout (column widths, row heights, merges,
 * frozen panes, hidden/visibility, tab color), print & page setup, defined names, data validation,
 * conditional formatting, comments, hyperlinks, and embedded images/charts. Best-effort onto ODF.
 */
internal object OoxmlXlsx {

    fun import(pkg: OoxmlPackage, fileName: String): OdfDocument.Spreadsheet {
        val theme = OoxmlTheme.parse(pkg.entries["xl/theme/theme1.xml"])
        val shared = pkg.entries["xl/sharedStrings.xml"]?.let { parseSharedStrings(it) } ?: emptyList()
        val styles = pkg.entries["xl/styles.xml"]?.let { parseStyles(it, theme) } ?: StyleTable()
        val wbRels = pkg.relsFor("xl/workbook.xml")
        val wb = pkg.entries["xl/workbook.xml"]?.let { parseWorkbook(it) } ?: Workbook(emptyList(), emptyList())

        val namedRanges = mutableListOf<OdfNamedRange>()
        val printRangesBySheet = HashMap<String, String>()
        for (dn in wb.definedNames) {
            when {
                dn.name == "_xlnm.Print_Area" -> dn.localSheet?.let { idx ->
                    wb.sheets.getOrNull(idx)?.let { printRangesBySheet[it.name] = a1RefToOdf(dn.value) }
                }
                dn.name.startsWith("_xlnm") -> {}
                else -> namedRanges.add(OdfNamedRange(dn.name, a1RefToOdf(dn.value)))
            }
        }

        val validations = mutableListOf<OdfDataValidation>()
        val sheets = mutableListOf<OdfSheet>()
        for (wsheet in wb.sheets) {
            val target = wbRels[wsheet.rId]?.target ?: continue
            val xml = pkg.entries[target] ?: continue
            sheets.add(parseWorksheet(pkg, target, xml, wsheet, shared, styles, theme, validations, printRangesBySheet[wsheet.name]))
        }
        if (sheets.isEmpty()) {
            pkg.entries.keys.filter { it.matches(Regex("xl/worksheets/sheet\\d+\\.xml")) }
                .sortedBy { it.substringAfterLast("sheet").substringBefore(".xml").toIntOrNull() ?: 0 }
                .forEachIndexed { i, path ->
                    sheets.add(parseWorksheet(pkg, path, pkg.entries[path]!!, WbSheet("Sheet ${i + 1}", "", false), shared, styles, theme, validations, null))
                }
        }
        if (sheets.isEmpty()) sheets.add(OdfSheet("Sheet 1", emptyList()))

        return OdfDocument.Spreadsheet(
            title = fileName,
            sheets = sheets,
            metadata = OoxmlMetadata.parse(pkg),
            images = collectImages(sheets),
            namedRanges = namedRanges,
            validations = validations
        )
    }

    private fun collectImages(sheets: List<OdfSheet>): Map<String, ByteArray> {
        val out = LinkedHashMap<String, ByteArray>()
        for (s in sheets) for (el in s.floating) if (el is OdfSlideElement.Frame) el.frame.image?.let { out[it.path] = it.imageData }
        return out
    }

    // ---- Workbook ----

    private class WbSheet(val name: String, val rId: String, val hidden: Boolean)
    private class DefinedName(val name: String, val value: String, val localSheet: Int?)
    private class Workbook(val sheets: List<WbSheet>, val definedNames: List<DefinedName>)

    private fun parseWorkbook(xml: String): Workbook {
        val parser = OoxmlXml.newParser(xml)
        val sheets = mutableListOf<WbSheet>()
        val names = mutableListOf<DefinedName>()
        var e = parser.eventType
        while (e != XmlPullParser.END_DOCUMENT) {
            if (e == XmlPullParser.START_TAG) when (parser.name) {
                "sheet" -> {
                    val name = OoxmlXml.attr(parser, "name") ?: "Sheet ${sheets.size + 1}"
                    val rId = OoxmlXml.attrNs(parser, "http://schemas.openxmlformats.org/officeDocument/2006/relationships", "id")
                        ?: OoxmlXml.attr(parser, "id") ?: ""
                    val hidden = OoxmlXml.attr(parser, "state").let { it == "hidden" || it == "veryHidden" }
                    sheets.add(WbSheet(name, rId, hidden))
                }
                "definedName" -> {
                    val name = OoxmlXml.attr(parser, "name") ?: ""
                    val local = OoxmlXml.attr(parser, "localSheetId")?.toIntOrNull()
                    val value = OoxmlXml.readElementText(parser, "definedName")
                    if (name.isNotBlank()) names.add(DefinedName(name, value, local))
                }
            }
            e = parser.next()
        }
        return Workbook(sheets, names)
    }

    private fun parseSharedStrings(xml: String): List<String> {
        val list = mutableListOf<String>()
        val parser = OoxmlXml.newParser(xml)
        var e = parser.eventType
        while (e != XmlPullParser.END_DOCUMENT) {
            if (e == XmlPullParser.START_TAG && parser.name == "si") {
                val depth = parser.depth
                val sb = StringBuilder()
                var ev = parser.next()
                while (!(ev == XmlPullParser.END_TAG && parser.depth == depth && parser.name == "si")) {
                    if (ev == XmlPullParser.START_TAG && parser.name == "t") sb.append(OoxmlXml.readElementText(parser, "t"))
                    if (ev == XmlPullParser.END_DOCUMENT) break
                    ev = parser.next()
                }
                list.add(sb.toString())
            }
            e = parser.next()
        }
        return list
    }

    // ---- Styles ----

    private class Font(val bold: Boolean, val italic: Boolean, val underline: Boolean, val strike: Boolean, val color: Long?, val size: Float?)
    private class Xf(
        val numFmtId: Int, val fontId: Int, val fillId: Int, val borderId: Int,
        val applyFont: Boolean, val applyFill: Boolean, val applyBorder: Boolean, val applyNumberFormat: Boolean, val applyAlignment: Boolean,
        val halign: String?, val valign: String?, val wrap: Boolean, val rotation: Int, val indent: Int
    )
    private class Dxf(val fill: Long?, val fontColor: Long?)
    private class StyleTable(
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

    private fun parseStyles(xml: String, theme: OoxmlTheme): StyleTable {
        val parser = OoxmlXml.newParser(xml)
        val fonts = mutableListOf<Font>()
        val fills = mutableListOf<Long?>()
        val borders = mutableListOf<OdfBorders>()
        val numFmts = HashMap<Int, String>()
        val cellXfs = mutableListOf<Xf>()
        val dxfs = mutableListOf<Dxf>()
        var section = ""  // "cellXfs" | "dxfs" | "cellStyleXfs"
        var e = parser.eventType
        while (e != XmlPullParser.END_DOCUMENT) {
            if (e == XmlPullParser.START_TAG) when (parser.name) {
                "numFmt" -> {
                    val id = OoxmlXml.attr(parser, "numFmtId")?.toIntOrNull()
                    val code = OoxmlXml.attr(parser, "formatCode")
                    if (id != null && code != null) numFmts[id] = code
                }
                "fonts" -> section = "fonts"
                "fills" -> section = "fills"
                "borders" -> section = "borders"
                "cellStyleXfs" -> section = "cellStyleXfs"
                "cellXfs" -> section = "cellXfs"
                "dxfs" -> section = "dxfs"
                "font" -> if (section == "fonts") fonts.add(parseFont(parser, theme))
                "fill" -> if (section == "fills") fills.add(parseFill(parser, theme))
                "border" -> if (section == "borders") borders.add(parseXlsxBorder(parser))
                "xf" -> if (section == "cellXfs") cellXfs.add(parseXf(parser))
                "dxf" -> if (section == "dxfs") dxfs.add(parseDxf(parser, theme))
            } else if (e == XmlPullParser.END_TAG) when (parser.name) {
                "fonts", "fills", "borders", "cellXfs", "cellStyleXfs", "dxfs" -> section = ""
            }
            e = parser.next()
        }
        return StyleTable(fonts, fills, borders, numFmts, cellXfs, dxfs)
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
                        val v = "%.2fpt %s #000000".format(borderWeight(style), if (style.contains("dash", true)) "dashed" else if (style.contains("dot", true)) "dotted" else if (style == "double") "double" else "solid")
                        when (edge) { "top" -> top = v; "bottom" -> bottom = v; "left" -> left = v; "right" -> right = v }
                    }
                }
            }
            e = parser.next()
        }
        return OdfBorders(top, right, bottom, left)
    }

    private fun borderWeight(style: String): Float = when (style) {
        "thin", "hair" -> 0.5f; "medium", "mediumDashed" -> 1.5f; "thick" -> 2.5f; else -> 1f
    }

    private fun parseXf(parser: XmlPullParser): Xf {
        val depth = parser.depth
        val numFmtId = OoxmlXml.attr(parser, "numFmtId")?.toIntOrNull() ?: 0
        val fontId = OoxmlXml.attr(parser, "fontId")?.toIntOrNull() ?: 0
        val fillId = OoxmlXml.attr(parser, "fillId")?.toIntOrNull() ?: 0
        val borderId = OoxmlXml.attr(parser, "borderId")?.toIntOrNull() ?: 0
        val applyFont = OoxmlXml.boolAttr(OoxmlXml.attr(parser, "applyFont")) && OoxmlXml.attr(parser, "applyFont") != null
        val applyFill = OoxmlXml.attr(parser, "applyFill") == "1"
        val applyBorder = OoxmlXml.attr(parser, "applyBorder") == "1"
        val applyNum = OoxmlXml.attr(parser, "applyNumberFormat") == "1"
        val applyAlign = OoxmlXml.attr(parser, "applyAlignment") == "1"
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
        return Xf(numFmtId, fontId, fillId, borderId, applyFont, applyFill, applyBorder, applyNum, applyAlign, halign, valign, wrap, rotation, indent)
    }

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
    private fun parseColorAttr(parser: XmlPullParser, theme: OoxmlTheme): Long? {
        OoxmlUnits.hexColor(OoxmlXml.attr(parser, "rgb"))?.let { return it }
        val themeIdx = OoxmlXml.attr(parser, "theme")?.toIntOrNull()
        if (themeIdx != null) {
            val base = theme.colors[themeSlot(themeIdx)] ?: return null
            val tint = OoxmlXml.attr(parser, "tint")?.toDoubleOrNull() ?: 0.0
            return applyExcelTint(base, tint)
        }
        return null
    }

    private fun themeSlot(idx: Int): String = when (idx) {
        0 -> "lt1"; 1 -> "dk1"; 2 -> "lt2"; 3 -> "dk2"
        4 -> "accent1"; 5 -> "accent2"; 6 -> "accent3"; 7 -> "accent4"; 8 -> "accent5"; 9 -> "accent6"
        10 -> "hlink"; 11 -> "folhlink"; else -> "dk1"
    }

    private fun applyExcelTint(base: Long, tint: Double): Long {
        if (tint == 0.0) return base
        return if (tint < 0) OoxmlUnits.applyTransforms(base, shade = ((1 + tint) * 100000).toInt())
        else OoxmlUnits.applyTransforms(base, tint = ((1 - tint) * 100000).toInt())
    }

    // ---- Worksheet ----

    private fun parseWorksheet(
        pkg: OoxmlPackage, part: String, xml: String, wsheet: WbSheet,
        shared: List<String>, styles: StyleTable, theme: OoxmlTheme,
        validations: MutableList<OdfDataValidation>, printRange: String?
    ): OdfSheet {
        val rels = pkg.relsFor(part)
        val parser = OoxmlXml.newParser(xml)
        val rows = mutableListOf<OdfRow>()
        val rowHeights = mutableListOf<Float?>()
        val hiddenRows = HashSet<Int>()
        val hiddenCols = HashSet<Int>()
        val colWidths = HashMap<Int, Float>()
        val merges = mutableListOf<String>()
        var freezeRows = 0; var freezeCols = 0
        var tabColor: Long? = null
        val hyperlinkRefs = mutableListOf<Triple<String, String?, String?>>() // ref, rId, location
        val condFormats = mutableListOf<Pair<String, List<CfRule>>>() // sqref -> rules
        val valRefs = mutableListOf<Pair<String, List<String>>>()
        var curCells: MutableList<Pair<Int, OdfCell>>? = null
        var curRowHidden = false
        var rowIndex = 0
        var drawingRid: String? = null

        var e = parser.eventType
        while (e != XmlPullParser.END_DOCUMENT) {
            when (e) {
                XmlPullParser.START_TAG -> when (parser.name) {
                    "sheetPr" -> {}
                    "tabColor" -> tabColor = parseColorAttr(parser, theme)
                    "pane" -> {
                        if (OoxmlXml.attr(parser, "state").let { it == "frozen" || it == "frozenSplit" }) {
                            freezeCols = OoxmlXml.attr(parser, "xSplit")?.toDoubleOrNull()?.toInt() ?: 0
                            freezeRows = OoxmlXml.attr(parser, "ySplit")?.toDoubleOrNull()?.toInt() ?: 0
                        }
                    }
                    "col" -> {
                        val min = OoxmlXml.attr(parser, "min")?.toIntOrNull() ?: 1
                        val max = OoxmlXml.attr(parser, "max")?.toIntOrNull() ?: min
                        val width = OoxmlXml.attr(parser, "width")?.toFloatOrNull()
                        val hidden = OoxmlXml.attr(parser, "hidden") == "1"
                        for (c in (min - 1) until max) {
                            width?.let { colWidths[c] = OoxmlUnits.excelColWidthToPx(it) }
                            if (hidden) hiddenCols.add(c)
                        }
                    }
                    "row" -> {
                        curCells = mutableListOf()
                        rowIndex = (OoxmlXml.attr(parser, "r")?.toIntOrNull() ?: (rows.size + 1)) - 1
                        curRowHidden = OoxmlXml.attr(parser, "hidden") == "1"
                        val ht = OoxmlXml.attr(parser, "ht")?.toFloatOrNull()
                        while (rowHeights.size <= rowIndex) rowHeights.add(null)
                        rowHeights[rowIndex] = ht?.let { OoxmlUnits.ptToPx(it) }
                        if (curRowHidden) hiddenRows.add(rowIndex)
                    }
                    "c" -> if (curCells != null) {
                        val ref = OoxmlXml.attr(parser, "r") ?: ""
                        val ci = if (ref.isNotEmpty()) OoxmlXml.colIndex(ref) else curCells!!.size
                        curCells!!.add(ci to parseCell(parser, shared, styles))
                    }
                    "mergeCell" -> OoxmlXml.attr(parser, "ref")?.let { merges.add(it) }
                    "dataValidation" -> parseDataValidation(parser)?.let { (v, refs) ->
                        validations.add(v)
                        valRefs.add(v.name to refs)
                    }
                    "conditionalFormatting" -> {
                        val sqref = OoxmlXml.attr(parser, "sqref") ?: ""
                        condFormats.add(sqref to parseCfRules(parser))
                    }
                    "hyperlink" -> {
                        val ref = OoxmlXml.attr(parser, "ref")
                        val rId = OoxmlXml.attrNs(parser, "http://schemas.openxmlformats.org/officeDocument/2006/relationships", "id")
                        val loc = OoxmlXml.attr(parser, "location")
                        if (ref != null) hyperlinkRefs.add(Triple(ref, rId, loc))
                    }
                    "drawing" -> drawingRid = OoxmlXml.attrNs(parser, "http://schemas.openxmlformats.org/officeDocument/2006/relationships", "id")
                }
                XmlPullParser.END_TAG -> if (parser.name == "row" && curCells != null) {
                    val maxCol = curCells!!.maxOfOrNull { it.first } ?: -1
                    val arr = MutableList(maxCol + 1) { OdfCell(text = "") }
                    for ((ci, cell) in curCells!!) if (ci in arr.indices) arr[ci] = cell
                    while (rows.size < rowIndex) { rows.add(OdfRow(emptyList())); }
                    if (rows.size == rowIndex) rows.add(OdfRow(arr)) else if (rowIndex in rows.indices) rows[rowIndex] = OdfRow(arr) else rows.add(OdfRow(arr))
                    curCells = null
                }
            }
            e = parser.next()
        }

        var sheet = OdfSheet(
            name = wsheet.name,
            rows = rows,
            columnWidths = buildColWidths(colWidths, rows),
            freezeRows = freezeRows,
            freezeCols = freezeCols,
            rowHeights = rowHeights.toList(),
            hiddenRows = hiddenRows,
            hiddenCols = hiddenCols,
            printRanges = printRange,
            hidden = wsheet.hidden,
            tabColor = tabColor
        )

        sheet = applyMerges(sheet, merges)
        sheet = applyHyperlinks(sheet, hyperlinkRefs, rels)
        sheet = applyCondFormats(sheet, condFormats, styles)
        sheet = applyComments(pkg, part, rels, sheet)
        val floating = parseDrawings(pkg, part, rels, drawingRid, theme, colWidths, rowHeights)
        if (floating.isNotEmpty()) sheet = sheet.copy(floating = floating)
        if (valRefs.isNotEmpty()) sheet = applyValidationNames(sheet, valRefs)
        return sheet
    }

    private fun buildColWidths(map: Map<Int, Float>, rows: List<OdfRow>): List<Float?> {
        if (map.isEmpty()) return emptyList()
        val maxCol = maxOf(map.keys.maxOrNull() ?: 0, rows.maxOfOrNull { it.cells.size - 1 } ?: 0)
        return (0..maxCol).map { map[it] }
    }

    private fun parseCell(parser: XmlPullParser, shared: List<String>, styles: StyleTable): OdfCell {
        val type = OoxmlXml.attr(parser, "t")
        val styleIdx = OoxmlXml.attr(parser, "s")?.toIntOrNull()
        val depth = parser.depth
        var value: String? = null
        var inlineText: String? = null
        var formula: String? = null
        var e = parser.next()
        while (!(e == XmlPullParser.END_TAG && parser.depth == depth && parser.name == "c")) {
            if (e == XmlPullParser.START_TAG) when (parser.name) {
                "v" -> value = OoxmlXml.readElementText(parser, "v")
                "t" -> inlineText = (inlineText ?: "") + OoxmlXml.readElementText(parser, "t")
                "f" -> {
                    val f = OoxmlXml.readElementText(parser, "f")
                    if (f.isNotBlank()) formula = ExcelFormula.toOdf(f)
                }
            }
            if (e == XmlPullParser.END_DOCUMENT) break
            e = parser.next()
        }

        val xf = styleIdx?.let { styles.cellXfs.getOrNull(it) }
        val cellStyle = xf?.let { resolveCellStyle(it, styles) } ?: CellStyle()
        val isDate = xf?.let { styles.isDateFmt(it.numFmtId) } == true

        val base = when (type) {
            "s" -> OdfCell(text = shared.getOrNull(value?.toIntOrNull() ?: -1) ?: "", valueType = "string")
            "inlineStr" -> OdfCell(text = inlineText ?: "", valueType = "string")
            "str" -> OdfCell(text = value ?: "", valueType = "string")
            "e" -> OdfCell(text = value ?: "#ERR", valueType = "string")
            "b" -> OdfCell(text = if (value == "1") "TRUE" else "FALSE", numberValue = if (value == "1") 1.0 else 0.0, valueType = "boolean")
            else -> {
                val num = value?.toDoubleOrNull()
                if (num != null) OdfCell(text = value ?: "", numberValue = num, valueType = if (isDate) "date" else "float")
                else OdfCell(text = value ?: "")
            }
        }
        return base.copy(
            formula = formula ?: base.formula,
            backgroundColor = cellStyle.fill,
            textColor = cellStyle.fontColor,
            bold = cellStyle.bold,
            italic = cellStyle.italic,
            alignment = cellStyle.align,
            borders = cellStyle.borders?.takeIf { !it.isEmpty() },
            borderColor = cellStyle.borders?.let { OdfBorders.renderColor(it.top ?: it.left) },
            numberFormat = xf?.let { if (it.applyNumberFormat || it.numFmtId != 0) styles.numberFormat(it.numFmtId) else null } ?: base.numberFormat,
            wrap = cellStyle.wrap,
            textRotation = cellStyle.rotation,
            verticalAlign = cellStyle.valign
        )
    }

    private class CellStyle(
        val fill: Long? = null, val fontColor: Long? = null, val bold: Boolean = false, val italic: Boolean = false,
        val align: TextAlign? = null, val valign: String? = null, val wrap: Boolean = false, val rotation: Int = 0,
        val borders: OdfBorders? = null
    )

    private fun resolveCellStyle(xf: Xf, styles: StyleTable): CellStyle {
        val font = styles.fonts.getOrNull(xf.fontId)
        val fill = if (xf.fillId > 0) styles.fills.getOrNull(xf.fillId) else null
        val borders = if (xf.borderId > 0) styles.borders.getOrNull(xf.borderId) else null
        return CellStyle(
            fill = fill,
            fontColor = font?.color,
            bold = font?.bold == true,
            italic = font?.italic == true,
            align = when (xf.halign) { "center" -> TextAlign.Center; "right" -> TextAlign.End; "left" -> TextAlign.Start; "justify" -> TextAlign.Justify; else -> null },
            valign = when (xf.valign) { "center" -> "middle"; "top" -> "top"; "bottom" -> "bottom"; else -> null },
            wrap = xf.wrap,
            rotation = xf.rotation,
            borders = borders
        )
    }

    // ---- Post-processing (merges, hyperlinks, condformat, comments, validation) ----

    private fun applyMerges(sheet: OdfSheet, merges: List<String>): OdfSheet {
        if (merges.isEmpty()) return sheet
        val grid = sheet.rows.map { it.cells.toMutableList() }.toMutableList()
        fun ensure(r: Int, c: Int) {
            while (grid.size <= r) grid.add(mutableListOf())
            while (grid[r].size <= c) grid[r].add(OdfCell(text = ""))
        }
        for (m in merges) {
            val (a, b) = m.split(":").let { if (it.size == 2) it[0] to it[1] else return@let it[0] to it[0] }
            val r1 = OoxmlXml.rowIndex(a); val c1 = OoxmlXml.colIndex(a)
            val r2 = OoxmlXml.rowIndex(b); val c2 = OoxmlXml.colIndex(b)
            if (r1 < 0 || c1 < 0) continue
            ensure(r1, c1)
            grid[r1][c1] = grid[r1][c1].copy(spannedColumns = (c2 - c1 + 1).coerceAtLeast(1), rowSpan = (r2 - r1 + 1).coerceAtLeast(1))
            for (r in r1..r2) for (c in c1..c2) {
                if (r == r1 && c == c1) continue
                ensure(r, c)
                grid[r][c] = grid[r][c].copy(isCovered = true)
            }
        }
        return sheet.copy(rows = grid.map { OdfRow(it) })
    }

    private fun applyHyperlinks(sheet: OdfSheet, links: List<Triple<String, String?, String?>>, rels: Map<String, OoxmlPackage.Rel>): OdfSheet {
        if (links.isEmpty()) return sheet
        val grid = sheet.rows.map { it.cells.toMutableList() }.toMutableList()
        for ((ref, rId, loc) in links) {
            val first = ref.split(":").first()
            val r = OoxmlXml.rowIndex(first); val c = OoxmlXml.colIndex(first)
            if (r !in grid.indices || c !in grid[r].indices) continue
            val target = rId?.let { rels[it]?.target } ?: loc?.let { "#$it" } ?: continue
            grid[r][c] = grid[r][c].copy(hyperlink = target)
        }
        return sheet.copy(rows = grid.map { OdfRow(it) })
    }

    private class CfRule(val condition: String, val bg: Long?, val fontColor: Long?)

    private fun parseCfRules(parser: XmlPullParser): List<CfRule> {
        val depth = parser.depth
        val rules = mutableListOf<CfRule>()
        var e = parser.next()
        while (!(e == XmlPullParser.END_TAG && parser.depth == depth && parser.name == "conditionalFormatting")) {
            if (e == XmlPullParser.END_DOCUMENT) break
            if (e == XmlPullParser.START_TAG && parser.name == "cfRule") {
                val type = OoxmlXml.attr(parser, "type")
                val op = OoxmlXml.attr(parser, "operator")
                val dxfId = OoxmlXml.attr(parser, "dxfId")?.toIntOrNull()
                val d = parser.depth
                val formulas = mutableListOf<String>()
                var ev = parser.next()
                while (!(ev == XmlPullParser.END_TAG && parser.depth == d && parser.name == "cfRule")) {
                    if (ev == XmlPullParser.END_DOCUMENT) break
                    if (ev == XmlPullParser.START_TAG && parser.name == "formula") formulas.add(OoxmlXml.readElementText(parser, "formula"))
                    ev = parser.next()
                }
                if (type == "cellIs" && formulas.isNotEmpty()) {
                    val cond = cellIsCondition(op, formulas)
                    rules.add(CfRule(cond, null, null).let { it.copyWithDxf(dxfId) })
                } else if (type == "expression" && formulas.isNotEmpty()) {
                    rules.add(CfRule(formulas[0], null, null).let { it.copyWithDxf(dxfId) })
                }
            }
            e = parser.next()
        }
        return rules
    }

    // dxf colors resolved later against StyleTable; store id via a sentinel condition suffix.
    private fun CfRule.copyWithDxf(dxfId: Int?): CfRule = CfRule(if (dxfId != null) "$condition\u0000$dxfId" else condition, bg, fontColor)

    private fun cellIsCondition(op: String?, formulas: List<String>): String = when (op) {
        "greaterThan" -> "value()>${formulas[0]}"
        "lessThan" -> "value()<${formulas[0]}"
        "greaterThanOrEqual" -> "value()>=${formulas[0]}"
        "lessThanOrEqual" -> "value()<=${formulas[0]}"
        "equal" -> "value()=${formulas[0]}"
        "notEqual" -> "value()!=${formulas[0]}"
        "between" -> if (formulas.size >= 2) "value()>=${formulas[0]} and value()<=${formulas[1]}" else "value()>=${formulas[0]}"
        else -> "value()=${formulas.getOrElse(0) { "0" }}"
    }

    private fun applyCondFormats(sheet: OdfSheet, cfs: List<Pair<String, List<CfRule>>>, styles: StyleTable): OdfSheet {
        if (cfs.isEmpty()) return sheet
        val grid = sheet.rows.map { it.cells.toMutableList() }.toMutableList()
        for ((sqref, rules) in cfs) {
            val odfRules = rules.map { r ->
                val parts = r.condition.split('\u0000')
                val cond = parts[0]
                val dxf = parts.getOrNull(1)?.toIntOrNull()?.let { styles.dxfs.getOrNull(it) }
                OdfCondFormat(condition = cond, backgroundColor = dxf?.fill, textColor = dxf?.fontColor)
            }
            for (range in sqref.split(" ")) {
                val (a, b) = range.split(":").let { if (it.size == 2) it[0] to it[1] else it[0] to it[0] }
                val r1 = OoxmlXml.rowIndex(a); val c1 = OoxmlXml.colIndex(a)
                val r2 = OoxmlXml.rowIndex(b); val c2 = OoxmlXml.colIndex(b)
                for (r in r1..r2) for (c in c1..c2) {
                    if (r in grid.indices && c in grid[r].indices) grid[r][c] = grid[r][c].copy(condFormats = grid[r][c].condFormats + odfRules)
                }
            }
        }
        return sheet.copy(rows = grid.map { OdfRow(it) })
    }

    private fun parseDataValidation(parser: XmlPullParser): Pair<OdfDataValidation, List<String>>? {
        val type = OoxmlXml.attr(parser, "type") ?: return null
        val sqref = OoxmlXml.attr(parser, "sqref") ?: return null
        val depth = parser.depth
        val formulas = mutableListOf<String>()
        var e = parser.next()
        while (!(e == XmlPullParser.END_TAG && parser.depth == depth && parser.name == "dataValidation")) {
            if (e == XmlPullParser.END_DOCUMENT) break
            if (e == XmlPullParser.START_TAG && (parser.name == "formula1" || parser.name == "formula2")) formulas.add(OoxmlXml.readElementText(parser, parser.name))
            e = parser.next()
        }
        val name = "val_${sqref.replace(Regex("[^A-Za-z0-9]"), "_")}"
        val condition = when (type) {
            "list" -> {
                val f = formulas.getOrElse(0) { "" }.trim('"')
                val values = f.split(",").joinToString(";") { "\"${it.trim()}\"" }
                "of:cell-content-is-in-list($values)"
            }
            "whole", "decimal" -> "of:cell-content()>=${formulas.getOrElse(0) { "0" }}"
            "textLength" -> "of:cell-content-text-length()>=${formulas.getOrElse(0) { "0" }}"
            else -> "of:cell-content()"
        }
        return OdfDataValidation(name, condition) to sqref.split(" ")
    }

    private fun applyValidationNames(sheet: OdfSheet, list: List<Pair<String, List<String>>>): OdfSheet {
        val grid = sheet.rows.map { it.cells.toMutableList() }.toMutableList()
        for ((name, refs) in list) for (range in refs) {
            val (a, b) = range.split(":").let { if (it.size == 2) it[0] to it[1] else it[0] to it[0] }
            val r1 = OoxmlXml.rowIndex(a); val c1 = OoxmlXml.colIndex(a)
            val r2 = OoxmlXml.rowIndex(b); val c2 = OoxmlXml.colIndex(b)
            for (r in r1..r2) for (c in c1..c2) if (r in grid.indices && c in grid[r].indices) grid[r][c] = grid[r][c].copy(validationName = name)
        }
        return sheet.copy(rows = grid.map { OdfRow(it) })
    }

    private fun applyComments(pkg: OoxmlPackage, part: String, rels: Map<String, OoxmlPackage.Rel>, sheet: OdfSheet): OdfSheet {
        val commentsPart = rels.values.firstOrNull { it.type?.endsWith("comments") == true }?.target ?: return sheet
        val xml = pkg.entries[commentsPart] ?: return sheet
        val comments = parseSheetComments(xml)
        if (comments.isEmpty()) return sheet
        val grid = sheet.rows.map { it.cells.toMutableList() }.toMutableList()
        for ((ref, ann) in comments) {
            val r = OoxmlXml.rowIndex(ref); val c = OoxmlXml.colIndex(ref)
            if (r in grid.indices && c in grid[r].indices) grid[r][c] = grid[r][c].copy(annotation = ann)
        }
        return sheet.copy(rows = grid.map { OdfRow(it) })
    }

    private fun parseSheetComments(xml: String): Map<String, OdfAnnotation> {
        val parser = OoxmlXml.newParser(xml)
        val authors = mutableListOf<String>()
        val out = LinkedHashMap<String, OdfAnnotation>()
        var e = parser.eventType
        while (e != XmlPullParser.END_DOCUMENT) {
            if (e == XmlPullParser.START_TAG) when (parser.name) {
                "author" -> authors.add(OoxmlXml.readElementText(parser, "author"))
                "comment" -> {
                    val ref = OoxmlXml.attr(parser, "ref") ?: ""
                    val aIdx = OoxmlXml.attr(parser, "authorId")?.toIntOrNull()
                    val depth = parser.depth
                    val sb = StringBuilder()
                    var ev = parser.next()
                    while (!(ev == XmlPullParser.END_TAG && parser.depth == depth && parser.name == "comment")) {
                        if (ev == XmlPullParser.END_DOCUMENT) break
                        if (ev == XmlPullParser.START_TAG && parser.name == "t") sb.append(OoxmlXml.readElementText(parser, "t"))
                        ev = parser.next()
                    }
                    if (ref.isNotBlank()) out[ref] = OdfAnnotation(
                        author = aIdx?.let { authors.getOrNull(it) },
                        paragraphs = listOf(OdfParagraph(listOf(OdfSpan(sb.toString()))))
                    )
                }
            }
            e = parser.next()
        }
        return out
    }

    // ---- Drawings (images + charts) ----

    private fun parseDrawings(
        pkg: OoxmlPackage, part: String, rels: Map<String, OoxmlPackage.Rel>, drawingRid: String?,
        theme: OoxmlTheme, colWidths: Map<Int, Float>, rowHeights: List<Float?>
    ): List<OdfSlideElement> {
        val drawingPart = drawingRid?.let { rels[it]?.target }
            ?: rels.values.firstOrNull { it.type?.endsWith("drawing") == true }?.target
            ?: return emptyList()
        val xml = pkg.entries[drawingPart] ?: return emptyList()
        val drawingRels = pkg.relsFor(drawingPart)
        val parser = OoxmlXml.newParser(xml)
        val out = mutableListOf<OdfSlideElement>()
        var fromCol = 0; var fromRow = 0; var fromColOff = 0L; var fromRowOff = 0L
        var toCol = 0; var toRow = 0
        var inFrom = false; var inTo = false
        var embed: String? = null; var chartRid: String? = null
        var extCx = 0L; var extCy = 0L
        val relsNs = "http://schemas.openxmlformats.org/officeDocument/2006/relationships"
        var e = parser.eventType
        fun flush() {
            val x = colX(fromCol, colWidths) + OoxmlUnits.emuToPx(fromColOff)
            val y = rowY(fromRow, rowHeights) + OoxmlUnits.emuToPx(fromRowOff)
            val w = if (extCx > 0) OoxmlUnits.emuToPx(extCx) else (colX(toCol, colWidths) - colX(fromCol, colWidths)).coerceAtLeast(64f)
            val h = if (extCy > 0) OoxmlUnits.emuToPx(extCy) else (rowY(toRow, rowHeights) - rowY(fromRow, rowHeights)).coerceAtLeast(48f)
            if (chartRid != null) {
                val target = drawingRels[chartRid]?.target
                val chart = target?.let { pkg.entries[it] }?.let { OoxmlChart.parse(it, theme) }
                if (chart != null) out.add(OdfSlideElement.Frame(OdfFrame(x, y, w, h, emptyList(), chart = chart)))
            } else if (embed != null) {
                val target = drawingRels[embed]?.target
                val bytes = target?.let { pkg.mediaBytes(it) }
                if (bytes != null) {
                    val path = "media/${target.substringAfterLast('/')}"
                    out.add(OdfSlideElement.Frame(OdfFrame(x, y, w, h, emptyList(), image = OdfImage(path, bytes, w, h))))
                }
            }
            embed = null; chartRid = null; extCx = 0; extCy = 0
        }
        while (e != XmlPullParser.END_DOCUMENT) {
            if (e == XmlPullParser.START_TAG) when (parser.name) {
                "from" -> inFrom = true
                "to" -> inTo = true
                "col" -> { val v = OoxmlXml.readElementText(parser, "col").trim().toIntOrNull() ?: 0; if (inFrom) fromCol = v else if (inTo) toCol = v }
                "row" -> { val v = OoxmlXml.readElementText(parser, "row").trim().toIntOrNull() ?: 0; if (inFrom) fromRow = v else if (inTo) toRow = v }
                "colOff" -> { val v = OoxmlXml.readElementText(parser, "colOff").trim().toLongOrNull() ?: 0L; if (inFrom) fromColOff = v }
                "rowOff" -> { val v = OoxmlXml.readElementText(parser, "rowOff").trim().toLongOrNull() ?: 0L; if (inFrom) fromRowOff = v }
                "ext" -> { extCx = OoxmlXml.attr(parser, "cx")?.toLongOrNull() ?: 0L; extCy = OoxmlXml.attr(parser, "cy")?.toLongOrNull() ?: 0L }
                "blip" -> embed = OoxmlXml.attrNs(parser, relsNs, "embed") ?: OoxmlXml.attr(parser, "embed")
                "chart" -> chartRid = OoxmlXml.attrNs(parser, relsNs, "id") ?: OoxmlXml.attr(parser, "id")
            } else if (e == XmlPullParser.END_TAG) when (parser.name) {
                "from" -> inFrom = false
                "to" -> inTo = false
                "oneCellAnchor", "twoCellAnchor", "absoluteAnchor" -> flush()
            }
            e = parser.next()
        }
        return out
    }

    private fun colX(col: Int, widths: Map<Int, Float>): Float {
        var x = 0f
        for (c in 0 until col) x += widths[c] ?: 64f
        return x
    }

    private fun rowY(row: Int, heights: List<Float?>): Float {
        var y = 0f
        for (r in 0 until row) y += heights.getOrNull(r) ?: 20f
        return y
    }

    // ---- Helpers ----

    /** Converts an Excel A1 range like "Sheet1!$A$1:$D$20" to an ODF address "Sheet1.A1:Sheet1.D20". */
    private fun a1RefToOdf(ref: String): String {
        return ref.split(",").joinToString(" ") { part ->
            val p = part.trim()
            val sheet = if (p.contains("!")) p.substringBefore("!").trim('\'', '$') else null
            val range = p.substringAfter("!").replace("$", "")
            if (sheet != null) {
                if (range.contains(":")) {
                    val (a, b) = range.split(":", limit = 2)
                    "$sheet.$a:$sheet.$b"
                } else "$sheet.$range"
            } else range
        }
    }
}
