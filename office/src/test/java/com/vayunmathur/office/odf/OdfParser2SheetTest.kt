package com.vayunmathur.office.odf

import com.vayunmathur.library.ui.odf.OdfCell
import com.vayunmathur.library.ui.odf.OdfDocument
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Slice 2/4 (OdfParser*): spreadsheet parsing (sheets, rows, cells, repeats,
 * hidden rows/cols, named ranges, validations, cell content) and CSV import.
 */
class OdfParser2SheetTest {

    private val head =
        """<?xml version="1.0" encoding="UTF-8"?>""" +
            """<office:document xmlns:office="urn:oasis:names:tc:opendocument:xmlns:office:1.0" """ +
            """xmlns:table="urn:oasis:names:tc:opendocument:xmlns:table:1.0" """ +
            """xmlns:text="urn:oasis:names:tc:opendocument:xmlns:text:1.0" """ +
            """xmlns:style="urn:oasis:names:tc:opendocument:xmlns:style:1.0" """ +
            """xmlns:fo="urn:oasis:names:tc:opendocument:xmlns:xsl-fo-compatible:1.0" """ +
            """xmlns:draw="urn:oasis:names:tc:opendocument:xmlns:drawing:1.0" """ +
            """xmlns:dc="http://purl.org/dc/elements/1.1/">"""

    private fun sheet(
        body: String,
        autoStyles: String = "",
        freeze: Map<String, Pair<Int, Int>> = emptyMap(),
    ): OdfDocument.Spreadsheet = OdfParser.parseSpreadsheet(
        head + "<office:automatic-styles>$autoStyles</office:automatic-styles>" +
            "<office:body><office:spreadsheet>$body</office:spreadsheet></office:body></office:document>",
        OdfParser.parseStyles(head + "<office:automatic-styles>$autoStyles</office:automatic-styles></office:document>"),
        emptyMap(),
        "test",
        com.vayunmathur.library.ui.odf.OdfMetadata(),
        emptyMap(),
        emptyMap(),
        freeze
    )

    private fun cell(sheetBody: String, row: Int = 0, col: Int = 0): OdfCell =
        sheet(sheetBody).sheets.single().rows[row].cells[col]

    // --- Basic cells ---

    @Test
    fun text_and_numeric_cells() {
        val c0 = cell(
            """<table:table table:name="S"><table:table-row>""" +
                """<table:table-cell office:value-type="string"><text:p>hello</text:p></table:table-cell>""" +
                """<table:table-cell office:value-type="float" office:value="3.5"><text:p>3.5</text:p></table:table-cell>""" +
                """</table:table-row></table:table>"""
        )
        assertEquals("hello", c0.text)
        assertEquals("string", c0.valueType)
        val c1 = sheet(
            """<table:table table:name="S"><table:table-row>""" +
                """<table:table-cell office:value-type="float" office:value="3.5"><text:p>3.5</text:p></table:table-cell>""" +
                """</table:table-row></table:table>"""
        ).sheets.single().rows[0].cells[0]
        assertEquals(3.5, c1.numberValue)
        assertEquals("float", c1.valueType)
    }

    @Test
    fun formula_cells_keep_openformula_source() {
        val c = cell(
            """<table:table table:name="S"><table:table-row>""" +
                """<table:table-cell table:formula="of:=SUM([.A1:.A3])" """ +
                """office:value-type="float" office:value="6"><text:p>6</text:p></table:table-cell>""" +
                """</table:table-row></table:table>"""
        )
        assertEquals("of:=SUM([.A1:.A3])", c.formula)
    }

    @Test
    fun merged_and_covered_cells() {
        val s = sheet(
            """<table:table table:name="S"><table:table-row>""" +
                """<table:table-cell table:number-columns-spanned="2" table:number-rows-spanned="3">""" +
                """<text:p>M</text:p></table:table-cell>""" +
                """<table:covered-table-cell/>""" +
                """<table:table-cell table:number-columns-repeated="2"><text:p>R</text:p></table:table-cell>""" +
                """</table:table-row></table:table>"""
        )
        val row = s.sheets.single().rows.single()
        assertEquals(2, row.cells[0].spannedColumns)
        assertEquals(3, row.cells[0].rowSpan)
        assertTrue(row.cells[1].isCovered)
        assertEquals(4, row.cells.size)
        assertEquals("R", row.cells[2].text)
        assertEquals("R", row.cells[3].text)
    }

    @Test
    fun trailing_filler_cells_trimmed_but_styled_kept() {
        val styles = """<style:style style:name="HL" style:family="table-cell">""" +
            """<style:table-cell-properties fo:background-color="#ffff00"/>""" +
            """</style:style>"""
        val s = sheet(
            """<table:table table:name="S"><table:table-row>""" +
                """<table:table-cell><text:p>A</text:p></table:table-cell>""" +
                """<table:table-cell table:style-name="HL"><text:p/></table:table-cell>""" +
                """<table:table-cell table:number-columns-repeated="10"><text:p/></table:table-cell>""" +
                """</table:table-row></table:table>""",
            styles
        )
        val row = s.sheets.single().rows.single()
        // Trailing unstyled fillers trimmed; the styled (highlighted) empty cell is kept.
        assertEquals(2, row.cells.size)
        assertEquals("A", row.cells[0].text)
        assertEquals(0xFFFFFF00L, row.cells[1].backgroundColor)
    }

    @Test
    fun trailing_empty_rows_trimmed() {
        val s = sheet(
            """<table:table table:name="S">""" +
                """<table:table-row><table:table-cell><text:p>data</text:p></table:table-cell></table:table-row>""" +
                """<table:table-row><table:table-cell><text:p/></table:table-cell></table:table-row>""" +
                """</table:table>"""
        )
        assertEquals(1, s.sheets.single().rows.size)
    }

    // --- Rows / columns / hidden / widths ---

    @Test
    fun repeated_rows_hidden_rows_and_heights() {
        val styles = """<style:style style:name="RH" style:family="table-row">""" +
            """<style:table-row-properties style:row-height="2cm"/>""" +
            """</style:style>"""
        val s = sheet(
            """<table:table table:name="S">""" +
                """<table:table-column table:style-name="nope"/>""" +
                """<table:table-row><table:table-cell><text:p>a</text:p></table:table-cell></table:table-row>""" +
                """<table:table-row table:number-rows-repeated="3"><table:table-cell><text:p>r</text:p></table:table-cell></table:table-row>""" +
                """<table:table-row table:style-name="RH" table:visibility="collapse">""" +
                """<table:table-cell><text:p>h</text:p></table:table-cell></table:table-row>""" +
                """</table:table>""",
            styles
        )
        val sh = s.sheets.single()
        assertEquals(5, sh.rows.size)
        assertEquals(setOf(4), sh.hiddenRows)
        assertEquals(2 * 37.8f, sh.rowHeights[4] ?: 0f, 0.01f)
    }

    @Test
    fun column_widths_and_hidden_columns() {
        val styles = """<style:style style:name="W" style:family="table-column">""" +
            """<style:table-column-properties style:column-width="4cm"/>""" +
            """</style:style>"""
        val s = sheet(
            """<table:table table:name="S">""" +
                """<table:table-column table:style-name="W"/>""" +
                """<table:table-column table:number-columns-repeated="2" table:visibility="filter"/>""" +
                """<table:table-row><table:table-cell><text:p>a</text:p></table:table-cell></table:table-row>""" +
                """</table:table>""",
            styles
        )
        val sh = s.sheets.single()
        assertEquals(3, sh.columnWidths.size)
        assertEquals(4 * 37.8f, sh.columnWidths[0] ?: 0f, 0.01f)
        assertEquals(setOf(1, 2), sh.hiddenCols)
    }

    @Test
    fun freeze_panes_print_ranges_and_default_sheet_name() {
        val s = sheet(
            """<table:table table:name="Data" table:print-ranges="Data.A1:Data.B2">""" +
                """<table:table-row><table:table-cell><text:p>x</text:p></table:table-cell></table:table-row>""" +
                """</table:table>""" +
                """<table:table><table:table-row><table:table-cell><text:p>y</text:p></table:table-cell></table:table-row></table:table>""",
            freeze = mapOf("Data" to (1 to 2))
        )
        assertEquals("Data", s.sheets[0].name)
        assertEquals(1, s.sheets[0].freezeRows)
        assertEquals(2, s.sheets[0].freezeCols)
        assertEquals("Data.A1:Data.B2", s.sheets[0].printRanges)
        assertEquals("Sheet 2", s.sheets[1].name)
        assertEquals(0, s.sheets[1].freezeRows)
    }

    // --- Named ranges / validations / conditional formats ---

    @Test
    fun named_ranges_and_validations() {
        val s = sheet(
            """<table:named-range table:name="Total" table:cell-range-address="S.${'$'}A${'$'}1" table:base-cell-address="S.${'$'}A${'$'}1"/>""" +
                """<table:content-validation table:name="V1" table:condition="cell-content-is-in-list(&quot;a;b&quot;)" """ +
                """table:allow-empty-cell="false"/>""" +
                """<table:table table:name="S"><table:table-row>""" +
                """<table:table-cell table:content-validation-name="V1"><text:p>a</text:p></table:table-cell>""" +
                """</table:table-row></table:table>"""
        )
        assertEquals(1, s.namedRanges.size)
        assertEquals("Total", s.namedRanges.single().name)
        assertEquals("S.\$A\$1", s.namedRanges.single().cellRangeAddress)
        assertEquals(1, s.validations.size)
        assertEquals("V1", s.validations.single().name)
        assertFalse(s.validations.single().allowEmpty)
        assertEquals(listOf("a", "b"), s.validations.single().listValues())
        assertEquals("V1", s.sheets.single().rows.single().cells.single().validationName)
    }

    @Test
    fun conditional_formats_resolved_from_style_map() {
        val styles = """<style:style style:name="High" style:family="table-cell">""" +
            """<style:table-cell-properties fo:background-color="#ff0000"/>""" +
            """</style:style>""" +
            """<style:style style:name="Base" style:family="table-cell">""" +
            """<style:table-cell-properties><style:map style:condition="cell-content()&gt;5" """ +
            """style:apply-style-name="High"/></style:table-cell-properties></style:style>"""
        val c = cell(
            """<table:table table:name="S"><table:table-row>""" +
                """<table:table-cell table:style-name="Base"><text:p>9</text:p></table:table-cell>""" +
                """</table:table-row></table:table>""",
            0, 0
        )
        // Re-parse with styles wired through (the helper above already does).
        val s = sheet(
            """<table:table table:name="S"><table:table-row>""" +
                """<table:table-cell table:style-name="Base"><text:p>9</text:p></table:table-cell>""" +
                """</table:table-row></table:table>""",
            styles
        )
        val cf = s.sheets.single().rows.single().cells.single().condFormats.single()
        assertEquals("cell-content()>5", cf.condition)
        assertEquals(0xFFFF0000L, cf.backgroundColor)
        assertEquals("9", s.sheets.single().rows.single().cells.single().text)
        assertEquals("9", c.text)
    }

    // --- Cell content details ---

    @Test
    fun cell_content_whitespace_and_annotation() {
        val s = sheet(
            """<table:table table:name="S"><table:table-row><table:table-cell>""" +
                """<text:p>line1</text:p><text:p>line2<text:s text:c="2"/>x<text:tab/>y<text:line-break/>z</text:p>""" +
                """<office:annotation><dc:creator xmlns:dc="http://purl.org/dc/elements/1.1">Ann</dc:creator>""" +
                """<text:p>note</text:p></office:annotation>""" +
                """</table:table-cell></table:table-row></table:table>"""
        )
        val c = s.sheets.single().rows.single().cells.single()
        assertEquals("line1\nline2  x\ty\nz", c.text)
        assertEquals("Ann", c.annotation!!.author)
        assertEquals("note", c.annotation!!.paragraphs.single().spans.single().text)
    }

    @Test
    fun cell_number_format_from_data_style() {
        val autoStyles =
            """<number:number-style style:name="N2" xmlns:number="urn:oasis:names:tc:opendocument:xmlns:datastyle:1.0">""" +
                """<number:number number:decimal-places="2" number:grouping="true"/></number:number-style>""" +
                """<style:style style:name="Num" style:family="table-cell" style:data-style-name="N2"/>"""
        val st = OdfParser.parseStyles(head + "<office:automatic-styles>$autoStyles</office:automatic-styles></office:document>")
        val ns = OdfParser.parseNumberStyles(head + "<office:automatic-styles>$autoStyles</office:automatic-styles></office:document>")
        assertEquals(2, ns["N2"]!!.decimals)
        assertTrue(ns["N2"]!!.grouping)
        val s = OdfParser.parseSpreadsheet(
            head + "<office:automatic-styles>$autoStyles</office:automatic-styles>" +
                """<office:body><office:spreadsheet><table:table table:name="S"><table:table-row>""" +
                """<table:table-cell table:style-name="Num" office:value-type="float" office:value="1.5">""" +
                """<text:p>1.50</text:p></table:table-cell></table:table-row></table:table>""" +
                """</office:spreadsheet></office:body></office:document>""",
            st, ns, "t",
            com.vayunmathur.library.ui.odf.OdfMetadata(), emptyMap(), emptyMap(), emptyMap()
        )
        assertEquals(2, s.sheets.single().rows.single().cells.single().numberFormat!!.decimals)
    }

    // --- CSV ---

    @Test
    fun csv_basic_quoted_and_escaped() {
        val doc = OdfParser.parseCsv("a,b,c\n\"x,y\",\"q\"\"q\",z\n", "f.csv")
        assertEquals("f.csv", doc.title)
        val rows = doc.sheets.single().rows
        assertEquals(2, rows.size)
        assertEquals(listOf("a", "b", "c"), rows[0].cells.map { it.text })
        assertEquals(listOf("x,y", "q\"q", "z"), rows[1].cells.map { it.text })
    }

    @Test
    fun csv_quoted_newlines_crlf_and_blank_lines() {
        // A quoted field absorbs the \r\n; the standalone \r\n after the closing quote
        // ends the record. Blank/whitespace-only records are skipped.
        val doc = OdfParser.parseCsv("a,\"multi\r\nline\"\r\n,,kept\r\n\r\n   \nlast", "f.csv", ',')
        val rows = doc.sheets.single().rows
        assertEquals(3, rows.size)
        assertEquals("multi\r\nline", rows[0].cells[1].text)
        assertEquals(listOf("", "", "kept"), rows[1].cells.map { it.text })
        assertEquals("last", rows[2].cells.single().text)
    }

    @Test
    fun csv_custom_delimiter_and_unterminated_quote() {
        val doc = OdfParser.parseCsv("a;b\nc;d", "f.csv", ';')
        assertEquals(listOf("a", "b"), doc.sheets.single().rows[0].cells.map { it.text })
        val unterminated = OdfParser.parseCsv("\"abc", "u.csv")
        assertEquals("abc", unterminated.sheets.single().rows.single().cells.single().text)
    }

    @Test
    fun csv_single_sheet_named_sheet_1() {
        val doc = OdfParser.parseCsv("only", "o.csv")
        assertEquals("Sheet 1", doc.sheets.single().name)
        assertIs<OdfDocument.Spreadsheet>(doc)
    }

    // --- Floating drawings in sheets ---

    @Test
    fun sheet_drawings_collected_as_floating() {
        val s = sheet(
            """<table:table table:name="S">""" +
                """<table:table-row><table:table-cell><text:p>v</text:p></table:table-cell></table:table-row>""" +
                """<draw:rect draw:name="r" svg:x="1cm" svg:y="1cm" svg:width="2cm" svg:height="1cm" """ +
                """xmlns:svg="urn:oasis:names:tc:opendocument:xmlns:svg-compatible:1.0"/>""" +
                """</table:table>"""
        )
        assertEquals(1, s.sheets.single().floating.size)
        assertIs<com.vayunmathur.library.ui.odf.OdfSlideElement.Shape>(s.sheets.single().floating.single())
    }

    @Test
    fun non_draw_namespaced_tags_ignored_in_sheet() {
        val s = sheet(
            """<table:table table:name="S"><table:table-row>""" +
                """<table:table-cell><text:p>v</text:p></table:table-cell></table:table-row>""" +
                """<text:p>not a drawing</text:p></table:table>"""
        )
        assertTrue(s.sheets.single().floating.isEmpty())
        assertEquals("v", s.sheets.single().rows.single().cells.single().text)
    }
}
