package com.vayunmathur.office.odf

import com.vayunmathur.library.ui.odf.OdfDocument
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * W4: [ExcelNumFmt] and [ExcelFormula] translation branches, plus
 * [OoxmlImporter] empty-document fallbacks when the primary import of a
 * package yields nothing readable.
 */
class W4FormulaNumFmtTest {

    // ---- ExcelNumFmt ----

    @Test
    fun `numfmt general and text codes yield no format`() {
        assertNull(ExcelNumFmt.parse(""))
        assertNull(ExcelNumFmt.parse("General"))
        assertNull(ExcelNumFmt.parse("general"))
        assertNull(ExcelNumFmt.parse("@"))
        assertNull(ExcelNumFmt.forBuiltin(0))
    }

    @Test
    fun `numfmt quoted literals are not date tokens`() {
        // " days" contains d/y letters but is a quoted suffix on a number.
        val fmt = ExcelNumFmt.parse("""0" days"""")
        assertNotNull(fmt)
        assertTrue(!fmt.isDate, fmt.toString())
        assertEquals(0, fmt.decimals)
        val suffixed = ExcelNumFmt.parse("""#,##0.00" units"""")
        assertNotNull(suffixed)
        assertTrue(suffixed.grouping)
        assertEquals(2, suffixed.decimals)
    }

    @Test
    fun `numfmt currency colors and conditions are stripped`() {
        val euro = ExcelNumFmt.parse("[$€-x-euro] #,##0.00;[Red](#,##0.00)")
        assertNotNull(euro)
        assertEquals("€", euro.currencySymbol)
        assertTrue(euro.grouping)
        assertEquals(2, euro.decimals)
        val conditioned = ExcelNumFmt.parse("[>100]0.0;[<=100]0")
        assertNotNull(conditioned)
        assertEquals(1, conditioned.decimals)
    }

    @Test
    fun `numfmt scientific fraction and percent kinds`() {
        val sci = ExcelNumFmt.parse("0.00E+00")
        assertNotNull(sci)
        assertTrue(sci.isScientific)
        assertEquals(2, sci.decimals)
        val frac = ExcelNumFmt.parse("# ??/??")
        assertNotNull(frac)
        assertTrue(frac.isFraction)
        assertEquals(2, frac.fractionDenominatorDigits)
        val pct = ExcelNumFmt.parse("0.00%")
        assertNotNull(pct)
        assertTrue(pct.percent)
        assertEquals(2, pct.decimals)
    }

    @Test
    fun `numfmt date and elapsed time codes`() {
        val date = ExcelNumFmt.parse("yyyy-mm-dd")
        assertNotNull(date)
        assertTrue(date.isDate)
        val time = ExcelNumFmt.parse("[h]:mm:ss")
        assertNotNull(time)
        assertTrue(time.isTime || time.isDate, time.toString())
        // Builtin ids: 14 is a date, 48 scientific, 3 grouped integer.
        assertTrue(ExcelNumFmt.forBuiltin(14)?.isDate == true)
        assertTrue(ExcelNumFmt.forBuiltin(48)?.isScientific == true)
        assertTrue(ExcelNumFmt.forBuiltin(3)?.grouping == true)
        assertNull(ExcelNumFmt.forBuiltin(999))
    }

    // ---- ExcelFormula ----

    @Test
    fun `formula refs translate both ways with sheets and absolutes`() {
        assertEquals("of:=SUM([.A1:.B2])", ExcelFormula.toOdf("SUM(A1:B2)"))
        assertEquals("=SUM(A1:B2)", ExcelFormula.fromOdf("of:=SUM([.A1:.B2])"))
        // Absolute refs keep their $ anchors in ODF form too.
        assertEquals("of:=SUM([.\$A\$1])", ExcelFormula.toOdf("SUM(\$A\$1)"))
        // Sheet-qualified refs.
        val sheetOdf = ExcelFormula.toOdf("'My Sheet'!A1")
        assertTrue(sheetOdf.contains("My Sheet"), sheetOdf)
        assertTrue(sheetOdf.contains("A1"), sheetOdf)
        val sheetBack = ExcelFormula.fromOdf(sheetOdf)
        assertTrue(sheetBack.contains("A1") && sheetBack.contains("My Sheet"), sheetBack)
        assertEquals("of:=[.A1]", ExcelFormula.toOdf("A1"))
        assertEquals("=A1", ExcelFormula.fromOdf("of:=[.A1]"))
    }

    @Test
    fun `formula whole column and row refs expand`() {
        val col = ExcelFormula.toOdf("SUM(A:A)")
        assertTrue(col.contains("1048576"), col)
        val row = ExcelFormula.toOdf("SUM(1:1)")
        assertTrue(row.contains("XFD") || row.contains("16384"), row)
        // Round-trip whole-column back to Excel form.
        assertEquals("=SUM(A:A)", ExcelFormula.fromOdf(col))
    }

    @Test
    fun `formula strings and function names are left alone`() {
        val withString = ExcelFormula.toOdf("""CONCAT("A1", B2)""")
        assertTrue(withString.contains("\"A1\""), withString)
        assertTrue(withString.contains("B2"), withString)
        // An escaped quote inside the literal does not end the string early.
        val escaped = ExcelFormula.toOdf("""CONCAT("a""A1", C3)""")
        assertTrue(escaped.contains("C3"), escaped)
        assertTrue(escaped.contains("A1"), escaped)
    }

    @Test
    fun `formula shift moves relative refs only`() {
        assertEquals("SUM(B3:B4)", ExcelFormula.shift("SUM(B2:B3)", 1, 0))
        assertEquals("C3", ExcelFormula.shift("B2", 1, 1))
        assertEquals("\$B\$2", ExcelFormula.shift("\$B\$2", 3, 3))
        assertEquals("\$B5", ExcelFormula.shift("\$B2", 3, 0))
        assertEquals("SUM(B2:B3)", ExcelFormula.shift("SUM(B2:B3)", 0, 0))
        // String literals are not shifted.
        assertEquals("""CONCAT("B2", C2)""", ExcelFormula.shift("""CONCAT("B2", B2)""", 0, 1))
    }

    // ---- OoxmlImporter fallback ----

    private fun zipOf(vararg entries: Pair<String, String>): ByteArray {
        val bos = java.io.ByteArrayOutputStream()
        java.util.zip.ZipOutputStream(bos).use { zos ->
            for ((n, c) in entries) {
                zos.putNextEntry(java.util.zip.ZipEntry(n))
                zos.write(c.toByteArray(Charsets.UTF_8))
                zos.closeEntry()
            }
        }
        return bos.toByteArray()
    }

    @Test
    fun `malformed ooxml packages fall back to empty documents`() {
        val docx = OoxmlImporter.import(
            zipOf("word/document.xml" to "<<not xml"), "broken.docx")
        assertTrue(docx is OdfDocument.TextDocument, docx.toString())
        assertEquals("broken.docx", docx?.title)

        val xlsx = OoxmlImporter.import(
            zipOf("xl/workbook.xml" to "<<not xml"), "broken.xlsx")
        assertTrue(xlsx is OdfDocument.Spreadsheet, xlsx.toString())

        val pptx = OoxmlImporter.import(
            zipOf("ppt/slides/slide1.xml" to "<<not xml"), "broken.pptx")
        assertTrue(pptx is OdfDocument.Presentation, pptx.toString())

        // A zip with no office parts at all is not an OOXML document.
        assertNull(OoxmlImporter.import(zipOf("readme.txt" to "hi"), "notes.zip"))
        // Even non-zip bytes fall back by extension so the caller can open an empty doc.
        assertTrue(OoxmlImporter.import(byteArrayOf(1, 2, 3), "raw.docx") is OdfDocument.TextDocument)
    }
}
