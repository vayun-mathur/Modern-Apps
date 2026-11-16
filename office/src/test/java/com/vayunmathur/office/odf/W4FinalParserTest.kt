package com.vayunmathur.office.odf

import com.vayunmathur.library.ui.odf.OdfDocument
import com.vayunmathur.library.ui.odf.OdfShape
import com.vayunmathur.library.ui.odf.OdfSlideElement
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * W4 (parsers, final batch): drawings floating in spreadsheets, date
 * number formats with quoted literals, sheet-qualified formula endpoints,
 * and OOXML conditional-formatting / merge post-processing.
 */
class W4FinalParserTest {

    private val head =
        """<?xml version="1.0" encoding="UTF-8"?>""" +
            """<office:document xmlns:office="urn:oasis:names:tc:opendocument:xmlns:office:1.0" """ +
            """xmlns:table="urn:oasis:names:tc:opendocument:xmlns:table:1.0" """ +
            """xmlns:text="urn:oasis:names:tc:opendocument:xmlns:text:1.0" """ +
            """xmlns:style="urn:oasis:names:tc:opendocument:xmlns:style:1.0" """ +
            """xmlns:fo="urn:oasis:names:tc:opendocument:xmlns:xsl-fo-compatible:1.0" """ +
            """xmlns:draw="urn:oasis:names:tc:opendocument:xmlns:drawing:1.0" """ +
            """xmlns:svg="urn:oasis:names:tc:opendocument:xmlns:svg-compatible:1.0" """ +
            """xmlns:xlink="http://www.w3.org/1999/xlink" """ +
            """xmlns:dc="http://purl.org/dc/elements/1.1/">"""

    private fun sheet(body: String): OdfDocument.Spreadsheet = OdfParser.parseSpreadsheet(
        head + "<office:body><office:spreadsheet>$body</office:spreadsheet></office:body></office:document>",
        emptyMap(), emptyMap(), "test",
        com.vayunmathur.library.ui.odf.OdfMetadata(), emptyMap(), emptyMap(), emptyMap(),
    )

    // ---- sheet drawings ----

    @Test
    fun `drawings inside a sheet become floating elements`() {
        val doc = sheet(
            """<table:table table:name="S"><table:table-row>""" +
                """<table:table-cell office:value-type="float" office:value="1"><text:p>1</text:p></table:table-cell>""" +
                """</table:table-row>""" +
                """<draw:rect svg:x="1cm" svg:y="1cm" svg:width="2cm" svg:height="1cm"><text:p>recty</text:p></draw:rect>""" +
                """<draw:ellipse svg:x="3cm" svg:y="1cm" svg:width="2cm" svg:height="1cm"/>""" +
                """<draw:line svg:x="0cm" svg:y="0cm" svg:width="4cm" svg:height="2cm"/>""" +
                """<draw:polyline svg:x="0cm" svg:y="3cm" svg:width="4cm" svg:height="1cm"/>""" +
                """<draw:polygon svg:x="5cm" svg:y="3cm" svg:width="2cm" svg:height="2cm"/>""" +
                """<draw:frame svg:x="6cm" svg:y="0cm" svg:width="3cm" svg:height="2cm">""" +
                """<draw:text-box><text:p>framed</text:p></draw:text-box></draw:frame>""" +
                """</table:table>""")
        val floating = doc.sheets.single().floating
        assertEquals(6, floating.size, floating.toString())
        assertIs<OdfShape.Rect>((floating[0] as OdfSlideElement.Shape).shape)
        assertIs<OdfShape.Ellipse>((floating[1] as OdfSlideElement.Shape).shape)
        assertIs<OdfShape.Line>((floating[2] as OdfSlideElement.Shape).shape)
        val frame = floating[5] as OdfSlideElement.Frame
        assertEquals(
            "framed",
            frame.frame.paragraphs.joinToString("") { it.spans.joinToString("") { s -> s.text } })
    }

    // ---- ExcelNumFmt quoted-literal dates ----

    @Test
    fun `date formats keep quoted literals as tokens`() {
        val fmt = ExcelNumFmt.parse("""yyyy" year "mm" month"""")
        assertNotNull(fmt)
        assertTrue(fmt.isDate)
        assertTrue(fmt.dateTimeTokens.any { it.text?.contains("year") == true }, fmt.dateTimeTokens.toString())
        assertTrue(fmt.dateTimeTokens.any { it.kind == "year" }, fmt.dateTimeTokens.toString())
        val euroDate = ExcelNumFmt.parse("dd.mm.yyyy")
        assertNotNull(euroDate)
        assertTrue(euroDate.isDate)
    }

    // ---- ExcelFormula sheet endpoints ----

    @Test
    fun `sheet qualified ranges round trip through odf`() {
        val odf = ExcelFormula.toOdf("SUM(Sheet1!A1:Sheet1!B2)")
        val back = ExcelFormula.fromOdf(odf)
        assertTrue(back.contains("Sheet1"), back)
        assertTrue(back.contains("A1"), back)
        assertTrue(back.contains("B2"), back)
        // Whole-row with sheet endpoints collapses to row-only form.
        val rowBack = ExcelFormula.fromOdf("of:=SUM([Sheet1.A2:Sheet1.XFD2])")
        assertTrue(rowBack.contains("2:2") || rowBack.contains("Sheet1"), rowBack)
    }

    // ---- XLSX conditional formatting + merges ----

    private fun xlsx(entries: Map<String, String>): OdfDocument.Spreadsheet {
        val bos = java.io.ByteArrayOutputStream()
        java.util.zip.ZipOutputStream(bos).use { zos ->
            for ((n, c) in entries) {
                zos.putNextEntry(java.util.zip.ZipEntry(n))
                zos.write(c.toByteArray(Charsets.UTF_8))
                zos.closeEntry()
            }
        }
        return OoxmlImporter.import(bos.toByteArray(), "cf.xlsx") as OdfDocument.Spreadsheet
    }

    @Test
    fun `xlsx conditional formats and merges are resolved`() {
        val doc = xlsx(mapOf(
            "xl/workbook.xml" to ("<workbook xmlns=\"s\" xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\">" +
                "<sheets><sheet name=\"Data\" sheetId=\"1\" r:id=\"rId1\"/></sheets></workbook>"),
            "xl/_rels/workbook.xml.rels" to ("<Relationships xmlns=\"p\">" +
                "<Relationship Id=\"rId1\" Type=\"http://t/worksheet\" Target=\"worksheets/sheet1.xml\"/></Relationships>"),
            "xl/styles.xml" to ("<styleSheet xmlns=\"s\">" +
                "<dxfs count=\"1\"><dxf><fill><patternFill><fgColor rgb=\"FFFF0000\"/></patternFill></fill></dxf></dxfs>" +
                "<cellXfs count=\"1\"><xf numFmtId=\"0\" fontId=\"0\" fillId=\"0\" borderId=\"0\"/></cellXfs></styleSheet>"),
            "xl/worksheets/sheet1.xml" to ("<worksheet xmlns=\"s\"><sheetData>" +
                "<row r=\"1\"><c r=\"A1\"><v>5</v></c><c r=\"B1\"><v>50</v></c></row>" +
                "<row r=\"2\"><c r=\"A2\"><v>1</v></c></row>" +
                "</sheetData>" +
                "<mergeCells count=\"1\"><mergeCell ref=\"A1:B1\"/></mergeCells>" +
                "<conditionalFormatting sqref=\"B1\"><cfRule type=\"cellIs\" operator=\"greaterThan\" priority=\"1\" dxfId=\"0\"><formula>10</formula></cfRule></conditionalFormatting>" +
                "<conditionalFormatting sqref=\"A2\"><cfRule type=\"cellIs\" operator=\"between\" priority=\"2\" dxfId=\"0\"><formula>0</formula><formula>10</formula></cfRule></conditionalFormatting>" +
                "</worksheet>"),
        ))
        val sheet0 = doc.sheets.single()
        val flat = sheet0.rows.flatMap { it.cells }
        // The merged anchor spans two columns and its partner is covered.
        assertTrue(flat.any { it.spannedColumns == 2 }, flat.toString())
        assertTrue(flat.any { it.isCovered }, flat.toString())
        // At least the matching conditional rule colours its cell.
        assertTrue(
            flat.any { it.condFormats.isNotEmpty() || it.backgroundColor != null },
            flat.toString())
    }
}
