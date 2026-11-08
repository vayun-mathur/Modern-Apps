package com.vayunmathur.office.odf

import com.vayunmathur.library.ui.odf.OdfAnnotation
import com.vayunmathur.library.ui.odf.OdfCell
import com.vayunmathur.library.ui.odf.OdfCondFormat
import com.vayunmathur.library.ui.odf.OdfDataValidation
import com.vayunmathur.library.ui.odf.OdfDocument
import com.vayunmathur.library.ui.odf.OdfMetadata
import com.vayunmathur.library.ui.odf.OdfNamedRange
import com.vayunmathur.library.ui.odf.OdfNumberFormat
import com.vayunmathur.library.ui.odf.OdfRow
import com.vayunmathur.library.ui.odf.OdfSheet
import com.vayunmathur.library.ui.odf.OdfSlideElement
import org.xmlpull.v1.XmlPullParser

// --- Spreadsheet ---

private const val MAX_REPEATED_ROWS = 200
private const val MAX_REPEATED_COLS = 1000
private const val MAX_REPEATED_CELLS = 100
private const val MAX_SPACES = 4096

internal fun OdfParser.parseSpreadsheet(
    xml: String, styles: Map<String, StyleInfo>, numberStyles: Map<String, OdfNumberFormat>,
    title: String, metadata: OdfMetadata, images: Map<String, ByteArray>, objectContents: Map<String, String> =
        emptyMap(),
    freezeMap: Map<String, Pair<Int, Int>> = emptyMap()
): OdfDocument.Spreadsheet {
    val sheets = mutableListOf<OdfSheet>()
    val namedRanges = mutableListOf<OdfNamedRange>()
    val validations = mutableListOf<OdfDataValidation>()
    val parser = newParser(xml)
    var eventType = parser.eventType

    while (eventType != XmlPullParser.END_DOCUMENT) {
        if (isTag(eventType, parser, "table", "table")) {
            val name = getAttr(parser, "name") ?: "Sheet ${sheets.size + 1}"
            val printRanges = getAttr(parser, "print-ranges")
            val r = parseSheetContent(parser, styles, numberStyles, images, objectContents)
            val freeze = freezeMap[name]
            sheets.add(OdfSheet(name, r.rows, r.columnWidths, r.floating, freeze?.first ?: 0, freeze?.second ?: 0,
                rowHeights =
                    r.rowHeights, hiddenRows = r.hiddenRows, hiddenCols = r.hiddenCols, printRanges = printRanges))
        } else if (eventType == XmlPullParser.START_TAG && parser.name == "named-range") {
            val nm = getAttr(parser, "name")
            val addr = getAttr(parser, "cell-range-address")
            if (nm != null && addr != null) namedRanges.add(OdfNamedRange(
                nm,
                addr,
                getAttr(parser, "base-cell-address")))
        } else if (eventType == XmlPullParser.START_TAG && parser.name == "content-validation") {
            val nm = getAttr(parser, "name")
            val cond = getAttr(parser, "condition")
            if (nm != null && cond != null) validations.add(OdfDataValidation(
                nm,
                cond,
                getAttr(parser, "allow-empty-cell") != "false"))
        }
        eventType = parser.next()
    }
    return OdfDocument.Spreadsheet(title, sheets, metadata, images, namedRanges, validations)
}

internal data class SheetParse(
    val rows: List<OdfRow>, val columnWidths: List<Float?>, val floating: List<OdfSlideElement>,
    val rowHeights: List<Float?>, val hiddenRows: Set<Int>, val hiddenCols: Set<Int>
)

/** Sheet-content accumulation state. */
private class SheetContentAcc(
    var colIndex: Int = 0,
)

/** Apply one sheet-content start tag. */
private fun OdfParser.applySheetContentTag(
    parser: XmlPullParser,
    styles: Map<String, StyleInfo>,
    numberStyles: Map<String, OdfNumberFormat>,
    images: Map<String, ByteArray>,
    objectContents: Map<String, String>,
    isDraw: Boolean,
    acc: SheetContentAcc,
    rows: MutableList<OdfRow>,
    columnWidths: MutableList<Float?>,
    floating: MutableList<OdfSlideElement>,
    rowHeights: MutableList<Float?>,
    hiddenRows: MutableSet<Int>,
    hiddenCols: MutableSet<Int>,
) {
    when (parser.name) {
        "table-column" -> applySheetColumn(parser, styles, acc, columnWidths, hiddenCols)
        "table-row" -> applySheetRow(parser, styles, numberStyles, rows, rowHeights, hiddenRows)
        else -> applySheetDrawing(
            parser, styles, images, objectContents, isDraw, floating)
    }
}

/** Drawing shapes within sheet content. */
private fun OdfParser.applySheetDrawing(
    parser: XmlPullParser,
    styles: Map<String, StyleInfo>,
    images: Map<String, ByteArray>,
    objectContents: Map<String, String>,
    isDraw: Boolean,
    floating: MutableList<OdfSlideElement>,
) {
    if (!isDraw) return
    when (parser.name) {
        "frame" -> floating.add(OdfSlideElement.Frame(parseSingleFrame(
            parser,
            styles,
            images,
            objectContents)))
        "rect" -> floating.add(OdfSlideElement.Shape(parseShape(parser, styles, "rect")))
        "ellipse" -> floating.add(OdfSlideElement.Shape(parseShape(parser, styles, "ellipse")))
        "line" -> floating.add(OdfSlideElement.Shape(parseShape(parser, styles, "line")))
        "custom-shape" -> floating.add(OdfSlideElement.Shape(parseShape(
            parser,
            styles,
            "custom-shape")))
        "polyline" -> floating.add(OdfSlideElement.Shape(parseShape(parser, styles, "polyline")))
        "polygon" -> floating.add(OdfSlideElement.Shape(parseShape(parser, styles, "polygon")))
        "path" -> floating.add(OdfSlideElement.Shape(parsePathShape(parser, styles)))
    }
}

/** Table-column widths/hidden. */
private fun OdfParser.applySheetColumn(
    parser: XmlPullParser,
    styles: Map<String, StyleInfo>,
    acc: SheetContentAcc,
    columnWidths: MutableList<Float?>,
    hiddenCols: MutableSet<Int>,
) {
    val repeated = getAttr(parser, "number-columns-repeated")?.toIntOrNull() ?: 1
    val styleName = getAttr(parser, "style-name")
    val width = resolveStyle(styleName, styles).columnWidth
    val hidden = getAttr(parser, "visibility").let { it == "collapse" || it == "filter" }
    repeat(repeated.coerceAtMost(MAX_REPEATED_COLS)) {
        if (hidden) hiddenCols.add(acc.colIndex)
        columnWidths.add(width); acc.colIndex++
    }
}

/** Table-row cells/heights/hidden. */
private fun OdfParser.applySheetRow(
    parser: XmlPullParser,
    styles: Map<String, StyleInfo>,
    numberStyles: Map<String, OdfNumberFormat>,
    rows: MutableList<OdfRow>,
    rowHeights: MutableList<Float?>,
    hiddenRows: MutableSet<Int>,
) {
    val repeated = getAttr(parser, "number-rows-repeated")?.toIntOrNull() ?: 1
    val styleName = getAttr(parser, "style-name")
    val rh = resolveStyle(styleName, styles).rowHeight
    val hidden = getAttr(parser, "visibility").let { it == "collapse" || it == "filter" }
    val cells = parseSpreadsheetCells(parser, styles, numberStyles)
    repeat(repeated.coerceAtMost(MAX_REPEATED_ROWS)) {
        if (hidden) hiddenRows.add(rows.size)
        rows.add(OdfRow(cells)); rowHeights.add(rh)
    }
}

internal fun OdfParser.parseSheetContent(
    parser: XmlPullParser,
    styles: Map<String,
    StyleInfo>,
    numberStyles: Map<String,
    OdfNumberFormat>,
    images: Map<String,
    ByteArray>,
    objectContents: Map<String,
    String> = emptyMap()): SheetParse {
    val rows = mutableListOf<OdfRow>()
    val columnWidths = mutableListOf<Float?>()
    val floating = mutableListOf<OdfSlideElement>()
    val rowHeights = mutableListOf<Float?>()
    val hiddenRows = mutableSetOf<Int>()
    val hiddenCols = mutableSetOf<Int>()
    val acc = SheetContentAcc()
    val depth = parser.depth
    var eventType = parser.next()

    while (!(eventType == XmlPullParser.END_TAG && parser.depth == depth)) {
        if (eventType == XmlPullParser.START_TAG) {
            val isDraw = parser.namespace?.contains("draw") == true
            applySheetContentTag(
                parser, styles, numberStyles, images, objectContents, isDraw, acc,
                rows, columnWidths, floating, rowHeights, hiddenRows, hiddenCols)
        }
        eventType = parser.next()
    }
    // Trim trailing all-empty rows.
    while (rows.isNotEmpty() && rows.last().cells.all { it.text.isEmpty() && it.formula == null }) {
        rows.removeAt(rows.size - 1)
        if (rowHeights.isNotEmpty()) rowHeights.removeAt(rowHeights.size - 1)
    }
    return SheetParse(
        rows,
        columnWidths,
        floating,
        rowHeights,
        hiddenRows.filter { it < rows.size }.toSet(),
        hiddenCols)
}

internal fun OdfParser.parseSpreadsheetCells(
    parser: XmlPullParser,
    styles: Map<String,
    StyleInfo>,
    numberStyles: Map<String,
    OdfNumberFormat>): List<OdfCell> {
    val cells = mutableListOf<OdfCell>()
    val depth = parser.depth
    var eventType = parser.next()

    while (!(eventType == XmlPullParser.END_TAG && parser.depth == depth)) {
        if (eventType == XmlPullParser.START_TAG) {
            when (parser.name) {
                "table-cell" -> parseTableCell(parser, styles, numberStyles, cells)
                "covered-table-cell" -> parseCoveredCell(parser, cells)
            }
        }
        eventType = parser.next()
    }
    trimFillerCells(cells)
    return cells
}

/** One table-cell (with repeats). */
private fun OdfParser.parseTableCell(
    parser: XmlPullParser,
    styles: Map<String, StyleInfo>,
    numberStyles: Map<String, OdfNumberFormat>,
    cells: MutableList<OdfCell>,
) {
    val spanned = getAttr(parser, "number-columns-spanned")?.toIntOrNull() ?: 1
    val rowSpan = getAttr(parser, "number-rows-spanned")?.toIntOrNull() ?: 1
    val repeated = getAttr(parser, "number-columns-repeated")?.toIntOrNull() ?: 1
    val styleName = getAttr(parser, "style-name")
    val resolved = resolveStyle(styleName, styles)
    val formula = getAttr(parser, "formula")
    val valueType = getAttr(parser, "value-type")
    val numberValue = getAttr(parser, "value")?.toDoubleOrNull()
    val numberFormat = resolved.dataStyleName?.let { numberStyles[it] }
    val validationName = getAttr(parser, "content-validation-name")
    val condFormats = resolved.conditionalMaps.map { (cond, sn) ->
        val t = resolveStyle(sn, styles); OdfCondFormat(cond, t.cellBackgroundColor, t.color)
    }
    val cellContent = parseCellContent(parser, styles)
    repeat(repeated.coerceAtMost(MAX_REPEATED_CELLS)) {
        cells.add(OdfCell(
            text = cellContent.first, spannedColumns = spanned, rowSpan = rowSpan,
            backgroundColor = resolved.cellBackgroundColor, textColor = resolved.color,
            bold = resolved.bold, italic = resolved.italic, alignment = resolved.textAlign,
            borderColor = resolved.cellBorderColor,
            borders = resolved.cellBorders,
            formula = formula, valueType = valueType, numberValue = numberValue,
            numberFormat = numberFormat, wrap = resolved.cellWrap,
            annotation = cellContent.second, validationName = validationName,
            condFormats = condFormats
        ))
    }
}

/** One covered-table-cell (with repeats). */
private fun OdfParser.parseCoveredCell(parser: XmlPullParser, cells: MutableList<OdfCell>) {
    val repeated = getAttr(parser, "number-columns-repeated")?.toIntOrNull() ?: 1
    repeat(repeated.coerceAtMost(MAX_REPEATED_CELLS)) { cells.add(OdfCell(text = "", isCovered = true)) }
    skipElement(parser)
}

/** Drop trailing "filler" cells (spreadsheets pad each row out to 1024 columns). */
private fun trimFillerCells(cells: MutableList<OdfCell>) {
    // Drop trailing "filler" cells (spreadsheets pad each row out to 1024 columns via
    // number-columns-repeated on an empty, unstyled cell). Keeping them bloats the grid width.
    while (cells.isNotEmpty()) {
        if (isFillerCell(cells.last())) cells.removeAt(cells.size - 1) else break
    }
}

/** True when a cell is an empty unstyled filler. */
private fun isFillerCell(cell: OdfCell): Boolean {
    if (cell.text.isNotEmpty() || cell.formula != null || cell.isCovered) return false
    if (cell.backgroundColor != null || cell.borderColor != null) return false
    if (cell.borders?.isEmpty() == false || cell.annotation != null) return false
    if (cell.numberValue != null || cell.spannedColumns != 1 || cell.rowSpan != 1) return false
    if (cell.condFormats.isNotEmpty() || cell.validationName != null || cell.hyperlink != null) return false
    return true
}

/** Parses a spreadsheet cell's text plus an optional office:annotation (cell comment). (Round 3) */
internal fun OdfParser.parseCellContent(
    parser: XmlPullParser,
    styles: Map<String,
    StyleInfo>): Pair<String, OdfAnnotation?> {
    val sb = StringBuilder()
    var annotation: OdfAnnotation? = null
    val depth = parser.depth
    var eventType = parser.next()
    while (!(eventType == XmlPullParser.END_TAG && parser.depth == depth)) {
        if (eventType == XmlPullParser.START_TAG) when (parser.name) {
            "annotation" -> annotation = parseAnnotation(parser, styles)
            // Separate successive paragraphs within a cell with a line break.
            "p" -> if (sb.isNotEmpty()) sb.append("\n")
            // Whitespace-preserving inline elements (multi-space runs, tabs, line breaks).
            "s" -> sb.append(" ".repeat((getAttr(parser, "c")?.toIntOrNull() ?: 1).coerceIn(0, MAX_SPACES)))
            "tab" -> sb.append("\t")
            "line-break" -> sb.append("\n")
        } else if (eventType == XmlPullParser.TEXT) {
            sb.append(parser.text)
        }
        eventType = parser.next()
    }
    return sb.toString().trim() to annotation
}
