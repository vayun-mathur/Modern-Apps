package com.vayunmathur.office.data.odf

import androidx.compose.ui.text.style.TextAlign
import com.vayunmathur.library.ui.odf.OdfBorders
import com.vayunmathur.library.ui.odf.OdfCell
import com.vayunmathur.library.ui.odf.OdfDataValidation
import com.vayunmathur.library.ui.odf.OdfDocument
import com.vayunmathur.library.ui.odf.OdfNamedRange
import com.vayunmathur.library.ui.odf.OdfNumberFormat
import com.vayunmathur.library.ui.odf.OdfParagraph
import com.vayunmathur.library.ui.odf.OdfRow
import com.vayunmathur.library.ui.odf.OdfSheet
import com.vayunmathur.library.ui.odf.OdfSlideElement
import com.vayunmathur.library.ui.odf.OdfSpan
import com.vayunmathur.office.util.OfficeNative
import org.xmlpull.v1.XmlPullParser

/**
 * XLSX importer (Groups 6-8, phases X1-X9). Resolves cell styles (fonts/fills/borders/number
 * formats/alignment), formulas & value types, sheet layout (column widths, row heights, merges,
 * frozen panes, hidden/visibility, tab color), print & page setup, defined names, data validation,
 * conditional formatting, comments, hyperlinks, and embedded images/charts. Best-effort onto ODF.
 */
internal object OoxmlXlsx {

    private const val EXCEL_EPOCH_OFFSET_DAYS = 25569 // days between 1899-12-30 and 1970-01-01
    private const val MILLIS_PER_DAY = 86400_000L
    private const val MAX_EXCEL_SERIAL = 2958465.0 // 9999-12-31
    private const val DEFAULT_SCIENTIFIC_DECIMALS = 2

    fun import(pkg: OoxmlPackage, fileName: String): OdfDocument.Spreadsheet {
        val theme = OoxmlTheme.parse(pkg.entries["xl/theme/theme1.xml"])
        val shared = pkg.entries["xl/sharedStrings.xml"]?.let { parseSharedStrings(it) } ?: emptyList()
        val styles = pkg.entries["xl/styles.xml"]?.let { OoxmlXlsxStyles.parseStyles(it, theme) }
            ?: OoxmlXlsxStyles.StyleTable()
        val wbRels = pkg.relsFor("xl/workbook.xml")
        val wb = pkg.entries["xl/workbook.xml"]?.let { parseWorkbook(it) } ?: Workbook(emptyList(), emptyList())
        val ctx = XlsxImportCtx(theme, shared, styles, wbRels, wb)
        val namedRanges = mutableListOf<OdfNamedRange>()
        val validations = mutableListOf<OdfDataValidation>()
        collectWorkbookNames(ctx, namedRanges)
        val sheets = buildWorkbookSheets(pkg, ctx, namedRanges, validations)
        return OdfDocument.Spreadsheet(
            title = fileName,
            sheets = sheets,
            metadata = OoxmlMetadata.parse(pkg),
            images = collectImages(sheets),
            namedRanges = namedRanges,
            validations = validations
        )
    }

    private class XlsxImportCtx(
        val theme: OoxmlTheme,
        val shared: List<String>,
        val styles: OoxmlXlsxStyles.StyleTable,
        val wbRels: Map<String, OoxmlPackage.Rel>,
        val wb: Workbook,
        val printRanges: HashMap<String, String> = HashMap(),
    )

    private fun collectWorkbookNames(ctx: XlsxImportCtx, namedRanges: MutableList<OdfNamedRange>) {
        for (dn in ctx.wb.definedNames) {
            when {
                dn.name == "_xlnm.Print_Area" -> dn.localSheet?.let { idx ->
                    ctx.wb.sheets.getOrNull(idx)?.let { ctx.printRanges[it.name] = a1RefToOdf(dn.value) }
                }
                dn.name.startsWith("_xlnm") -> {}
                else -> namedRanges.add(OdfNamedRange(dn.name, a1RefToOdf(dn.value)))
            }
        }
    }

    private fun buildWorkbookSheets(
        pkg: OoxmlPackage, ctx: XlsxImportCtx, namedRanges: MutableList<OdfNamedRange>,
        validations: MutableList<OdfDataValidation>,
    ): List<OdfSheet> {
        val sheets = mutableListOf<OdfSheet>()
        for (wsheet in ctx.wb.sheets) {
            val target = ctx.wbRels[wsheet.rId]?.target
            val xml = target?.let { pkg.entries[it] }
            if (target != null && xml != null) {
                sheets.add(parseWorksheet(
                    pkg,
                    target,
                    xml,
                    wsheet,
                    ctx.shared,
                    ctx.styles,
                    ctx.theme,
                    validations,
                    ctx.printRanges[wsheet.name],
                    namedRanges))
            }
        }
        if (sheets.isEmpty()) collectFallbackSheets(pkg, ctx, namedRanges, validations, sheets)
        if (sheets.isEmpty()) sheets.add(OdfSheet("Sheet 1", emptyList()))
        return sheets
    }

    private fun collectFallbackSheets(
        pkg: OoxmlPackage, ctx: XlsxImportCtx, namedRanges: MutableList<OdfNamedRange>,
        validations: MutableList<OdfDataValidation>, sheets: MutableList<OdfSheet>,
    ) {
        pkg.entries.keys.filter { it.matches(Regex("xl/worksheets/sheet\\d+\\.xml")) }
            .sortedBy { it.substringAfterLast("sheet").substringBefore(".xml").toIntOrNull() ?: 0 }
            .forEachIndexed { i, path ->
                sheets.add(parseWorksheet(
                    pkg,
                    path,
                    pkg.entries[path]!!,
                    WbSheet("Sheet ${i + 1}", "", false),
                    ctx.shared,
                    ctx.styles,
                    ctx.theme,
                    validations,
                    null,
                    namedRanges))
            }
    }

    private fun collectImages(sheets: List<OdfSheet>): Map<String, ByteArray> {
        val out = LinkedHashMap<String, ByteArray>()
        for (s in sheets) {
            for (el in s.floating) {
                if (el is OdfSlideElement.Frame) el.frame.image?.let { out[it.path] = it.imageData }
            }
        }
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
                    val rId = OoxmlXml.attrNs(
                        parser,
                        "http://schemas.openxmlformats.org/officeDocument/2006/relationships",
                        "id")
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
                list.add(parseSharedItem(parser))
            }
            e = parser.next()
        }
        return list
    }

    /** One shared-string item (skipping phonetic-guide runs). */
    private fun parseSharedItem(parser: XmlPullParser): String {
        val depth = parser.depth
        val sb = StringBuilder()
        var phoneticDepth = -1
        var ev = parser.next()
        while (!(ev == XmlPullParser.END_TAG && parser.depth == depth && parser.name == "si")) {
            when {
                // Skip <t> inside <rPh> phonetic-guide runs (furigana), which aren't visible text.
                ev == XmlPullParser.START_TAG && parser.name == "rPh" -> phoneticDepth = parser.depth
                ev == XmlPullParser.END_TAG && parser.name == "rPh" -> phoneticDepth = -1
                ev == XmlPullParser.START_TAG && parser.name == "t" && phoneticDepth < 0 ->
                    sb.append(OoxmlXml.readElementText(parser, "t"))
            }
            if (ev == XmlPullParser.END_DOCUMENT) break
            ev = parser.next()
        }
        return sb.toString()
    }

    // ---- Styles: see OoxmlXlsxStyles (split for file length; behavior identical) ----

    // ---- Worksheet ----

    private fun parseWorksheet(
        pkg: OoxmlPackage, part: String, xml: String, wsheet: WbSheet,
        shared: List<String>, styles: OoxmlXlsxStyles.StyleTable, theme: OoxmlTheme,
        validations: MutableList<OdfDataValidation>, printRange: String?, namedRanges: MutableList<OdfNamedRange>
    ): OdfSheet {
        val rels = pkg.relsFor(part)
        val parser = OoxmlXml.newParser(xml)
        val acc = SheetAcc()
        val sharedFormulas = HashMap<Int, SharedFormulaDef>()

        var e = parser.eventType
        while (e != XmlPullParser.END_DOCUMENT) {
            when (e) {
                XmlPullParser.START_TAG -> applyWorksheetStart(
                    parser, theme, acc, shared, styles, sharedFormulas, validations)
                XmlPullParser.END_TAG -> applyWorksheetEnd(parser, acc)
            }
            e = parser.next()
        }

        var sheet = OdfSheet(
            name = wsheet.name,
            rows = acc.rows,
            columnWidths = buildColWidths(acc.colWidths, acc.rows),
            freezeRows = acc.freezeRows,
            freezeCols = acc.freezeCols,
            rowHeights = acc.rowHeights.toList(),
            hiddenRows = acc.hiddenRows,
            hiddenCols = acc.hiddenCols,
            printRanges = printRange,
            hidden = wsheet.hidden,
            tabColor = acc.tabColor
        )

        sheet = OoxmlXlsxPost.applyMerges(sheet, acc.merges)
        sheet = OoxmlXlsxPost.applyHyperlinks(sheet, acc.hyperlinkRefs, rels)
        sheet = OoxmlXlsxPost.applyCondFormats(sheet, acc.condFormats, styles)
        sheet = OoxmlXlsxPost.applyComments(pkg, rels, sheet)
        val floating = OoxmlXlsxHelper.parseDrawings(
            pkg, rels, acc.drawingRid, theme, acc.colWidths, acc.rowHeights)
        if (floating.isNotEmpty()) sheet = sheet.copy(floating = floating)
        if (acc.valRefs.isNotEmpty()) sheet = OoxmlXlsxPost.applyValidationNames(sheet, acc.valRefs)
        // Structured tables (xl/tables/tableN.xml) -> named ranges.
        for (rel in rels.values) if (rel.type?.endsWith("table") == true) {
            pkg.entries[rel.target]?.let { tx -> parseTablePart(tx, wsheet.name)?.let { namedRanges.add(it) } }
        }
        return sheet
    }

    /** Worksheet accumulation state. */
    private class SheetAcc(
        val rows: MutableList<OdfRow> = mutableListOf(),
        val rowHeights: MutableList<Float?> = mutableListOf(),
        val hiddenRows: HashSet<Int> = HashSet(),
        val hiddenCols: HashSet<Int> = HashSet(),
        val colWidths: HashMap<Int, Float> = HashMap(),
        val merges: MutableList<String> = mutableListOf(),
        var freezeRows: Int = 0,
        var freezeCols: Int = 0,
        var tabColor: Long? = null,
        val hyperlinkRefs: MutableList<Triple<String, String?, String?>> = mutableListOf(),
        val condFormats: MutableList<Pair<String, List<OoxmlXlsxPost.CfRule>>> = mutableListOf(),
        val valRefs: MutableList<Pair<String, List<String>>> = mutableListOf(),
        var curCells: MutableList<Pair<Int, OdfCell>>? = null,
        var curRowHidden: Boolean = false,
        var rowIndex: Int = 0,
        var drawingRid: String? = null,
    )

    /** Apply one worksheet start tag. */
    private fun applyWorksheetStart(
        parser: XmlPullParser,
        theme: OoxmlTheme,
        acc: SheetAcc,
        shared: List<String>,
        styles: OoxmlXlsxStyles.StyleTable,
        sharedFormulas: HashMap<Int, SharedFormulaDef>,
        validations: MutableList<OdfDataValidation>,
    ) {
        if (applyWorksheetStyleTag(parser, theme, acc)) return
        applyWorksheetDataTag(parser, acc, shared, styles, sharedFormulas, validations)
    }

    private fun applyWorksheetStyleTag(parser: XmlPullParser, theme: OoxmlTheme, acc: SheetAcc): Boolean {
        when (parser.name) {
            "sheetPr" -> {}
            "tabColor" -> acc.tabColor = OoxmlXlsxStyles.parseColorAttr(parser, theme)
            "pane" -> applyPane(parser, acc)
            "col" -> applyCol(parser, acc)
            else -> return false
        }
        return true
    }

    private fun applyWorksheetDataTag(
        parser: XmlPullParser,
        acc: SheetAcc,
        shared: List<String>,
        styles: OoxmlXlsxStyles.StyleTable,
        sharedFormulas: HashMap<Int, SharedFormulaDef>,
        validations: MutableList<OdfDataValidation>,
    ) {
        when (parser.name) {
            "row" -> applyRowStart(parser, acc)
            "c" -> applyCellStart(parser, acc, shared, styles, sharedFormulas)
            "mergeCell" -> OoxmlXml.attr(parser, "ref")?.let { acc.merges.add(it) }
            "dataValidation" -> applyDataValidationTag(parser, acc, validations)
            "conditionalFormatting" -> applyCondFmtTag(parser, acc)
            "hyperlink" -> applyHyperlink(parser, acc)
            "drawing" -> applyDrawingTag(parser, acc)
        }
    }

    private fun applyDataValidationTag(
        parser: XmlPullParser, acc: SheetAcc, validations: MutableList<OdfDataValidation>,
    ) {
        OoxmlXlsxPost.parseDataValidation(parser)?.let { (v, refs) ->
            validations.add(v)
            acc.valRefs.add(v.name to refs)
        }
    }

    private fun applyCondFmtTag(parser: XmlPullParser, acc: SheetAcc) {
        val sqref = OoxmlXml.attr(parser, "sqref") ?: ""
        acc.condFormats.add(sqref to OoxmlXlsxPost.parseCfRules(parser))
    }

    private fun applyDrawingTag(parser: XmlPullParser, acc: SheetAcc) {
        acc.drawingRid = OoxmlXml.attrNs(
            parser,
            "http://schemas.openxmlformats.org/officeDocument/2006/relationships",
            "id")
    }

    /** Pane (freeze) attributes. */
    private fun applyPane(parser: XmlPullParser, acc: SheetAcc) {
        if (OoxmlXml.attr(parser, "state").let { it == "frozen" || it == "frozenSplit" }) {
            acc.freezeCols = OoxmlXml.attr(parser, "xSplit")?.toDoubleOrNull()?.toInt() ?: 0
            acc.freezeRows = OoxmlXml.attr(parser, "ySplit")?.toDoubleOrNull()?.toInt() ?: 0
        }
    }

    /** Column width/hidden attributes. */
    private fun applyCol(parser: XmlPullParser, acc: SheetAcc) {
        val min = OoxmlXml.attr(parser, "min")?.toIntOrNull() ?: 1
        val max = OoxmlXml.attr(parser, "max")?.toIntOrNull() ?: min
        val width = OoxmlXml.attr(parser, "width")?.toFloatOrNull()
        val hidden = OoxmlXml.attr(parser, "hidden") == "1"
        for (c in (min - 1) until max) {
            width?.let { acc.colWidths[c] = OoxmlUnits.excelColWidthToPx(it) }
            if (hidden) acc.hiddenCols.add(c)
        }
    }

    /** Row start: new cell list + height/hidden. */
    private fun applyRowStart(parser: XmlPullParser, acc: SheetAcc) {
        acc.curCells = mutableListOf()
        acc.rowIndex = (OoxmlXml.attr(parser, "r")?.toIntOrNull() ?: (acc.rows.size + 1)) - 1
        acc.curRowHidden = OoxmlXml.attr(parser, "hidden") == "1"
        val ht = OoxmlXml.attr(parser, "ht")?.toFloatOrNull()
        while (acc.rowHeights.size <= acc.rowIndex) acc.rowHeights.add(null)
        acc.rowHeights[acc.rowIndex] = ht?.let { OoxmlUnits.ptToPx(it) }
        if (acc.curRowHidden) acc.hiddenRows.add(acc.rowIndex)
    }

    /** Cell start: parse and append. */
    private fun applyCellStart(
        parser: XmlPullParser,
        acc: SheetAcc,
        shared: List<String>,
        styles: OoxmlXlsxStyles.StyleTable,
        sharedFormulas: HashMap<Int, SharedFormulaDef>,
    ) {
        val curCells = acc.curCells ?: return
        val ref = OoxmlXml.attr(parser, "r") ?: ""
        val ci = if (ref.isNotEmpty()) OoxmlXml.colIndex(ref) else curCells.size
        curCells.add(ci to parseCell(parser, shared, styles, sharedFormulas, acc.rowIndex, ci))
    }

    /** Hyperlink attributes. */
    private fun applyHyperlink(parser: XmlPullParser, acc: SheetAcc) {
        val ref = OoxmlXml.attr(parser, "ref")
        val rId = OoxmlXml.attrNs(
            parser,
            "http://schemas.openxmlformats.org/officeDocument/2006/relationships",
            "id")
        val loc = OoxmlXml.attr(parser, "location")
        if (ref != null) acc.hyperlinkRefs.add(Triple(ref, rId, loc))
    }

    /** Apply one worksheet end tag (row finalization). */
    private fun applyWorksheetEnd(parser: XmlPullParser, acc: SheetAcc) {
        if (parser.name != "row") return
        val curCells = acc.curCells ?: return
        val maxCol = curCells.maxOfOrNull { it.first } ?: -1
        val arr = MutableList(maxCol + 1) { OdfCell(text = "") }
        for ((ci, cell) in curCells) if (ci in arr.indices) arr[ci] = cell
        while (acc.rows.size < acc.rowIndex) { acc.rows.add(OdfRow(emptyList())); }
        if (acc.rows.size == acc.rowIndex) acc.rows.add(OdfRow(arr))
        else if (acc.rowIndex in acc.rows.indices) acc.rows[acc.rowIndex] =
            OdfRow(arr) else acc.rows.add(OdfRow(arr))
        acc.curCells = null
    }

    private fun parseTablePart(xml: String, sheetName: String): OdfNamedRange? {
        val parser = OoxmlXml.newParser(xml)
        var e = parser.eventType
        while (e != XmlPullParser.END_DOCUMENT) {
            if (e == XmlPullParser.START_TAG && parser.name == "table") {
                val name = OoxmlXml.attr(parser, "displayName") ?: OoxmlXml.attr(parser, "name") ?: return null
                val ref = OoxmlXml.attr(parser, "ref") ?: return null
                val addr = ref.split(":").joinToString(":") { "$sheetName.$it" }
                return OdfNamedRange(name, addr)
            }
            e = parser.next()
        }
        return null
    }

    private fun buildColWidths(map: Map<Int, Float>, rows: List<OdfRow>): List<Float?> {
        if (map.isEmpty()) return emptyList()
        val maxCol = maxOf(map.keys.maxOrNull() ?: 0, rows.maxOfOrNull { it.cells.size - 1 } ?: 0)
        return (0..maxCol).map { map[it] }
    }

    /** Master definition of a shared formula: the master's A1 body and its 0-based row/col. */
    private class SharedFormulaDef(val a1: String, val row: Int, val col: Int)

    private fun parseCell(
        parser: XmlPullParser, shared: List<String>, styles: OoxmlXlsxStyles.StyleTable,
        sharedFormulas: MutableMap<Int, SharedFormulaDef>, cellRow: Int, cellCol: Int
    ): OdfCell {
        val type = OoxmlXml.attr(parser, "t")
        val styleIdx = OoxmlXml.attr(parser, "s")?.toIntOrNull()
        val cell = readCellContent(parser, sharedFormulas, cellRow, cellCol)
        val xf = styleIdx?.let { styles.cellXfs.getOrNull(it) }
        val cellStyle = xf?.let { resolveCellStyle(it, styles) } ?: CellStyle()
        val isDate = xf?.let { styles.isDateFmt(it.numFmtId) } == true
        val base = baseCell(type, cell.value, cell.inlineText, shared, isDate)
        return styleCell(base, xf, styles, cellStyle, cell.formula)
    }

    private fun styleCell(
        base: OdfCell,
        xf: OoxmlXlsxStyles.Xf?,
        styles: OoxmlXlsxStyles.StyleTable,
        cellStyle: CellStyle,
        formula: String?,
    ): OdfCell {
        val resolvedFmt = xf?.let {
            if (it.applyNumberFormat || it.numFmtId != 0) styles.numberFormat(it.numFmtId) else null
        } ?: base.numberFormat
        // Excel stores the raw number (e.g. a date serial "45292"); the display string must be
        // formatted per the number format so dates/percent/currency don't show as bare numbers.
        val fmtForDisplay = resolvedFmt ?: if (base.valueType == "date") OdfNumberFormat(isDate = true) else null
        val displayText = if (base.numberValue != null && base.valueType != "boolean" && fmtForDisplay != null)
            formatDisplayValue(base.numberValue!!, fmtForDisplay, base.text) else base.text
        return base.copy(
            text = displayText,
            formula = formula ?: base.formula,
            backgroundColor = cellStyle.fill,
            textColor = cellStyle.fontColor,
            bold = cellStyle.bold,
            italic = cellStyle.italic,
            alignment = cellStyle.align,
            borders = cellStyle.borders?.takeIf { !it.isEmpty() },
            borderColor = cellStyle.borders?.let { OdfBorders.renderColor(it.top ?: it.left) },
            numberFormat = resolvedFmt,
            wrap = cellStyle.wrap,
            textRotation = cellStyle.rotation,
            verticalAlign = cellStyle.valign
        )
    }

    /**
     * Display string for a numeric cell. Prefers the native formatter (exact Excel rendering),
     * but a cosmetic string must never fail the whole import: when the engine can't load
     * (JVM unit tests, or a device-side load failure) a pure-Kotlin best-effort applies.
     */
    private fun formatDisplayValue(value: Double, fmt: OdfNumberFormat, raw: String): String {
        runCatching { return OfficeNative.formatValue(value, fmt) }
        return kotlinFormat(value, fmt, raw)
    }

    /** Best-effort number/date rendering without the native engine. */
    private fun kotlinFormat(value: Double, fmt: OdfNumberFormat, raw: String): String {
        if (fmt.isDate || fmt.isTime) return excelSerialToIso(value) ?: raw
        if (fmt.isScientific) {
            return "%.${fmt.decimals ?: DEFAULT_SCIENTIFIC_DECIMALS}E".format(java.util.Locale.ROOT, value)
        }
        if (fmt.isFraction) return raw
        val scaled = if (fmt.percent) value * 100 else value
        val decimals = fmt.decimals ?: 0
        var s = if (decimals > 0) "%.${decimals}f".format(java.util.Locale.ROOT, scaled)
        else kotlin.math.round(scaled).toLong().toString()
        if (fmt.grouping) s = groupThousands(s)
        if (fmt.percent) s += "%"
        fmt.currencySymbol?.let { s = "$it$s" }
        return s
    }

    private fun groupThousands(s: String): String {
        val neg = s.startsWith("-")
        val body = if (neg) s.substring(1) else s
        val dot = body.indexOf('.')
        val int = if (dot >= 0) body.substring(0, dot) else body
        val frac = if (dot >= 0) body.substring(dot) else ""
        val grouped = int.reversed().chunked(3).joinToString(",").reversed()
        return (if (neg) "-" else "") + grouped + frac
    }

    /** Excel serial (days since 1899-12-30) to "yyyy-mm-dd" (with time when fractional). */
    private fun excelSerialToIso(serial: Double): String? {
        if (serial < 0 || serial > MAX_EXCEL_SERIAL) return null
        val millis = ((serial - EXCEL_EPOCH_OFFSET_DAYS) * MILLIS_PER_DAY).toLong()
        val cal = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC"))
        cal.timeInMillis = millis
        fun p(field: Int, digits: Int = 2) = cal.get(field).toString().padStart(digits, '0')
        val date = "${p(java.util.Calendar.YEAR, 4)}-" +
            "${(cal.get(java.util.Calendar.MONTH) + 1).toString().padStart(2, '0')}-" +
            p(java.util.Calendar.DAY_OF_MONTH)
        return if (serial % 1 != 0.0) {
            "$date ${p(java.util.Calendar.HOUR_OF_DAY)}:${p(java.util.Calendar.MINUTE)}"
        } else {
            date
        }
    }

    /** Raw cell content (value/inline/formula). */
    private class CellContent(
        var value: String? = null,
        var inlineText: String? = null,
        var formula: String? = null,
    )

    /** Read the value/inline/formula children of a cell. */
    private fun readCellContent(
        parser: XmlPullParser,
        sharedFormulas: MutableMap<Int, SharedFormulaDef>,
        cellRow: Int,
        cellCol: Int,
    ): CellContent {
        val depth = parser.depth
        val cell = CellContent()
        var e = parser.next()
        while (!(e == XmlPullParser.END_TAG && parser.depth == depth && parser.name == "c")) {
            if (e == XmlPullParser.START_TAG) applyCellChild(parser, cell, sharedFormulas, cellRow, cellCol)
            if (e == XmlPullParser.END_DOCUMENT) break
            e = parser.next()
        }
        return cell
    }

    /** Apply one cell child tag. */
    private fun applyCellChild(
        parser: XmlPullParser,
        cell: CellContent,
        sharedFormulas: MutableMap<Int, SharedFormulaDef>,
        cellRow: Int,
        cellCol: Int,
    ) {
        when (parser.name) {
            "v" -> cell.value = OoxmlXml.readElementText(parser, "v")
            "t" -> cell.inlineText = (cell.inlineText ?: "") + OoxmlXml.readElementText(parser, "t")
            "f" -> cell.formula = readCellFormula(parser, sharedFormulas, cellRow, cellCol)
        }
    }

    /** Cell formula with shared-formula resolution. */
    private fun readCellFormula(
        parser: XmlPullParser,
        sharedFormulas: MutableMap<Int, SharedFormulaDef>,
        cellRow: Int,
        cellCol: Int,
    ): String? {
        val ft = OoxmlXml.attr(parser, "t")
        val si = OoxmlXml.attr(parser, "si")?.toIntOrNull()
        val f = OoxmlXml.readElementText(parser, "f")
        return when {
            // Master of a shared formula: remember its A1 body + position for dependents.
            ft == "shared" && f.isNotBlank() -> {
                if (si != null) sharedFormulas[si] = SharedFormulaDef(f, cellRow, cellCol)
                ExcelFormula.toOdf(f)
            }
            // Dependent: re-base the master's relative refs to this cell before translating.
            ft == "shared" && f.isBlank() -> si?.let { sharedFormulas[it] }?.let { def ->
                ExcelFormula.toOdf(ExcelFormula.shift(def.a1, cellRow - def.row, cellCol - def.col))
            }
            f.isNotBlank() -> ExcelFormula.toOdf(f)
            else -> null
        }
    }

    /** Base cell from the type + raw content. */
    private fun baseCell(
        type: String?,
        value: String?,
        inlineText: String?,
        shared: List<String>,
        isDate: Boolean,
    ): OdfCell {
        if (isStringType(type)) return stringCell(type, value, inlineText, shared)
        if (type == "b") return boolCell(value)
        return numericCell(value, isDate)
    }

    private fun isStringType(type: String?): Boolean =
        type == "s" || type == "inlineStr" || type == "str" || type == "e"

    private fun stringCell(type: String?, value: String?, inlineText: String?, shared: List<String>): OdfCell {
        return when (type) {
            "s" -> OdfCell(text = shared.getOrNull(value?.toIntOrNull() ?: -1) ?: "", valueType = "string")
            "inlineStr" -> OdfCell(text = inlineText ?: "", valueType = "string")
            "str" -> OdfCell(text = value ?: "", valueType = "string")
            else -> OdfCell(text = value ?: "#ERR", valueType = "string")
        }
    }

    private fun boolCell(value: String?): OdfCell {
        return OdfCell(
            text = if (value == "1") "TRUE" else "FALSE",
            numberValue = if (value == "1") 1.0 else 0.0,
            valueType = "boolean")
    }

    private fun numericCell(value: String?, isDate: Boolean): OdfCell {
        val num = value?.toDoubleOrNull()
        if (num != null) return OdfCell(text = value, numberValue = num, valueType = if (isDate) "date" else "float")
        return OdfCell(text = value ?: "")
    }

    private class CellStyle(
        val fill: Long? = null, val fontColor: Long? = null, val bold: Boolean = false, val italic: Boolean = false,
        val align: TextAlign? = null, val valign: String? = null, val wrap: Boolean = false, val rotation: Int = 0,
        val borders: OdfBorders? = null
    )

    private fun resolveCellStyle(xf: OoxmlXlsxStyles.Xf, styles: OoxmlXlsxStyles.StyleTable): CellStyle {
        val font = styles.fonts.getOrNull(xf.fontId)
        val fill = if (xf.fillId > 0) styles.fills.getOrNull(xf.fillId) else null
        val borders = if (xf.borderId > 0) styles.borders.getOrNull(xf.borderId) else null
        return CellStyle(
            fill = fill,
            fontColor = font?.color,
            bold = font?.bold == true,
            italic = font?.italic == true,
            align = when (xf.halign) {
                "center" -> TextAlign.Center
                "right" -> TextAlign.End
                "left" -> TextAlign.Start
                "justify" -> TextAlign.Justify
                else -> null
            },
            valign = when (xf.valign) {
                "center" -> "middle"
                "top" -> "top"
                "bottom" -> "bottom"
                else -> null
            },
            wrap = xf.wrap,
            rotation = xf.rotation,
            borders = borders
        )
    }

    // ---- Post-processing: see OoxmlXlsxPost (split for file length; behavior identical) ----

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
