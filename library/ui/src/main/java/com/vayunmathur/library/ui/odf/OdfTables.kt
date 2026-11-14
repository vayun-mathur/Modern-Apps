package com.vayunmathur.library.ui.odf

import androidx.compose.ui.text.style.TextAlign

/** A text-document table: columns, rows and header-row count. */
data class OdfTable(
    val name: String = "",
    val columns: List<OdfTableColumn> = emptyList(),
    val rows: List<OdfTableRow> = emptyList(),
    // Number of leading header rows (table:table-header-rows). (Round 2 R5)
    val headerRowCount: Int = 0
)

/** One table column and its optional width. */
data class OdfTableColumn(val width: Float? = null)

/** One row of a text-document table. */
data class OdfTableRow(val cells: List<OdfTableCell>)

/** One text-document table cell: paragraphs, spans, borders and alignment. */
data class OdfTableCell(
    val paragraphs: List<OdfParagraph> = emptyList(),
    val colSpan: Int = 1,
    val rowSpan: Int = 1,
    val backgroundColor: Long? = null,
    val borderColor: Long? = null,
    // Per-edge styled borders (raw fo:border values), if any. Preserved on round-trip. (OOXML import)
    val borders: OdfBorders? = null,
    val isCovered: Boolean = false,
    // OpenFormula source for a text-document table cell, preserved for round-trip. (Round 2 R5)
    val formula: String? = null,
    // Vertical alignment within the cell: "top"/"middle"/"bottom" (style:vertical-align). (Round 3)
    val verticalAlign: String? = null
)

/** One spreadsheet sheet: rows, column widths, freeze panes and print settings. */
data class OdfSheet(
    val name: String,
    val rows: List<OdfRow>,
    val columnWidths: List<Float?> = emptyList(),
    val floating: List<OdfSlideElement> = emptyList(),
    // Freeze panes: number of frozen header rows/columns (0 = none). Persisted in settings.xml. (C2)
    val freezeRows: Int = 0,
    val freezeCols: Int = 0,
    // Row heights in px@96 (parallel to rows; null = default). (Round 2 R6)
    val rowHeights: List<Float?> = emptyList(),
    // Indices of hidden rows / columns (table:visibility="collapse"|"filter"). (Round 2 R6)
    val hiddenRows: Set<Int> = emptySet(),
    val hiddenCols: Set<Int> = emptySet(),
    // Print range address(es), e.g. "Sheet1.A1:Sheet1.D20" (table:print-ranges). (Round 3)
    val printRanges: String? = null,
    // Sheet hidden from the tab bar (xlsx sheet state="hidden"/"veryHidden"). (OOXML import)
    val hidden: Boolean = false,
    // Sheet tab color as 0xAARRGGBB, if any (xlsx sheetPr/tabColor). (OOXML import)
    val tabColor: Long? = null
)

/** A workbook-level named range / expression (table:named-range). (Round 2 R6) */
data class OdfNamedRange(
    val name: String,
    val cellRangeAddress: String,
    val baseCellAddress: String? = null
)

/** A content validation rule (table:content-validation). (Round 3) */
data class OdfDataValidation(
    val name: String,
    val condition: String,
    val allowEmpty: Boolean = true
) {
    /** Extracts the allowed values when this is a "cell-content-is-in-list" validation, else null. */
    fun listValues(): List<String>? {
        val m = Regex("cell-content-is-in-list\\((.*)\\)").find(condition) ?: return null
        return m.groupValues[1].split(";").map { it.trim().trim('"') }.filter { it.isNotEmpty() }
    }
}

/** One spreadsheet row. */
data class OdfRow(val cells: List<OdfCell>)

/** One spreadsheet cell: text, spans, colours and formula presentation. */
data class OdfCell(
    val text: String,
    val spannedColumns: Int = 1,
    val rowSpan: Int = 1,
    val backgroundColor: Long? = null,
    val textColor: Long? = null,
    val bold: Boolean = false,
    val italic: Boolean = false,
    val alignment: TextAlign? = null,
    val borderColor: Long? = null,
    // Per-edge styled borders (raw fo:border values), if any (Priority 4). Preserved on round-trip.
    val borders: OdfBorders? = null,
    val isCovered: Boolean = false,
    // OpenFormula source (e.g. "of:=SUM([.A1:.A3])"), if this is a formula cell. (H49)
    val formula: String? = null,
    // ODF value type: "float", "string", "date", "percentage", "currency", "boolean". (H50/H54)
    val valueType: String? = null,
    // Cached numeric value from office:value, if numeric. (H54)
    val numberValue: Double? = null,
    // Resolved number/date/currency display format. (H50)
    val numberFormat: OdfNumberFormat? = null,
    // Wrap text in cell. (H53)
    val wrap: Boolean = false,
    // Cell comment/note (office:annotation), if any (Round 3).
    val annotation: OdfAnnotation? = null,
    // Name of a content-validation rule applied to this cell (Round 3).
    val validationName: String? = null,
    // Conditional formatting rules (from style:map), evaluated at render time (Round 3).
    val condFormats: List<OdfCondFormat> = emptyList(),
    // Hyperlink target (xlsx cell hyperlink), if any. (OOXML import)
    val hyperlink: String? = null,
    // Text rotation in degrees counter-clockwise (xlsx alignment textRotation). (OOXML import)
    val textRotation: Int = 0,
    // Vertical alignment within the cell: "top"/"middle"/"bottom" (xlsx alignment vertical). (OOXML import)
    val verticalAlign: String? = null
)

/** A conditional-format rule: when [condition] is true, apply the given colors. (Round 3) */
data class OdfCondFormat(
    val condition: String,
    val backgroundColor: Long? = null,
    val textColor: Long? = null
)

/** Resolved spreadsheet number-format descriptor (H50). */
data class OdfNumberFormat(
    val decimals: Int? = null,
    val percent: Boolean = false,
    val currencySymbol: String? = null,
    val grouping: Boolean = false,
    val isDate: Boolean = false,
    // Additional number-style kinds (Priority 4).
    val isTime: Boolean = false,
    val isScientific: Boolean = false,
    val isFraction: Boolean = false,
    // Fraction denominator digit count (e.g. 2 -> ?/??). Defaults to 1.
    val fractionDenominatorDigits: Int = 1,
    // Ordered source tokens for date/time styles, preserved to re-emit the exact pattern
    // instead of a hard-coded YYYY-MM-DD / HH:MM:SS on write. (Phase 4)
    val dateTimeTokens: List<OdfNumberToken> = emptyList()
)

/**
 * One ordered child token of a number:date-style / number:time-style (Phase 4).
 * [kind] is the number:* element local name (e.g. "year", "month", "text"); [style]
 * is number:style ("long"/"short"); [text] is the literal for number:text; [textual]
 * mirrors number:textual (month names).
 */
data class OdfNumberToken(
    val kind: String,
    val style: String? = null,
    val text: String? = null,
    val textual: Boolean = false
)

/**
 * Per-edge styled borders for a cell or paragraph (Priority 4). Each value is the
 * raw fo:border string (e.g. "0.5pt solid #000000"), preserved verbatim for ODF
 * round-trip fidelity. A representative color for rendering can be derived via
 * [renderColor].
 */
data class OdfBorders(
    val top: String? = null,
    val right: String? = null,
    val bottom: String? = null,
    val left: String? = null
) {
    fun isEmpty(): Boolean = top == null && right == null && bottom == null && left == null

    /** Border-string parsing helpers for [OdfBorders]. */
    companion object {
        /** Extracts the first #RRGGBB color from a raw fo:border value as 0xFFRRGGBB. */
        fun renderColor(border: String?): Long? {
            if (border == null) return null
            val hex = Regex("#([0-9a-fA-F]{6})").find(border)?.groupValues?.get(1) ?: return null
            return 0xFF000000L or hex.toLong(16)
        }
    }
}
