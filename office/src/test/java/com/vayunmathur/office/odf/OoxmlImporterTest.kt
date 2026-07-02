package com.vayunmathur.office.odf

import com.vayunmathur.library.ui.odf.ChartType
import com.vayunmathur.library.ui.odf.OdfContentBlock
import com.vayunmathur.library.ui.odf.OdfDocument
import com.vayunmathur.library.ui.odf.OdfSlideElement
import com.vayunmathur.library.ui.odf.ListType
import com.vayunmathur.library.ui.odf.ParagraphStyle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for the expanded OOXML importer: pure helpers (units/colors/number-formats/formulas/
 * package paths) plus XML-driven parsers (theme, chart, OMML, and the docx/xlsx/pptx importers) fed
 * hand-crafted minimal fragments.
 */
class OoxmlImporterTest {

    private fun pkg(vararg entries: Pair<String, String>) =
        OoxmlPackage(entries.toMap(), emptyMap())

    // ---- Pure helpers ----

    @Test fun emuAndTwips() {
        assertEquals(1f, OoxmlUnits.emuToPx(9525), 0.001f)
        assertEquals(12f, OoxmlUnits.twipsToPt(240), 0.001f)
        assertEquals(12f, OoxmlUnits.halfPtToPt(24), 0.001f)
        assertEquals(90f, OoxmlUnits.angle60000ToDeg(5_400_000), 0.001f)
    }

    @Test fun hexColorParsing() {
        assertEquals(0xFFFF0000L, OoxmlUnits.hexColor("#FF0000"))
        assertEquals(0xFF00FF00L, OoxmlUnits.hexColor("00FF00"))
        assertEquals(null, OoxmlUnits.hexColor("auto"))
    }

    @Test fun shadeDarkensColor() {
        // 50% shade of white -> mid-grey.
        val shaded = OoxmlUnits.applyTransforms(0xFFFFFFFF, shade = 50000)
        assertEquals(127, ((shaded ushr 16) and 0xFF).toInt())
    }

    @Test fun packageNormalizesRelTargets() {
        assertEquals("word/media/image1.png", OoxmlPackage.normalize("word/document.xml", "media/image1.png"))
        assertEquals("media/image1.png", OoxmlPackage.normalize("word/document.xml", "../media/image1.png"))
        assertEquals("xl/styles.xml", OoxmlPackage.normalize("xl/workbook.xml", "/xl/styles.xml"))
    }

    @Test fun excelFormulaConversion() {
        assertEquals("of:=SUM([.A1:.A3])+[.B1]", ExcelFormula.toOdf("SUM(A1:A3)+B1"))
        assertEquals("of:=IF([.A1]>0;\"y\";\"n\")", ExcelFormula.toOdf("IF(A1>0,\"y\",\"n\")"))
    }

    @Test fun excelNumberFormats() {
        val pct = ExcelNumFmt.parse("0.00%")!!
        assertTrue(pct.percent)
        assertEquals(2, pct.decimals)
        val grouped = ExcelNumFmt.parse("#,##0.00")!!
        assertTrue(grouped.grouping)
        assertEquals(2, grouped.decimals)
        assertTrue(ExcelNumFmt.forBuiltin(14)!!.isDate)
        assertTrue(ExcelNumFmt.isDateTimeBuiltin(20))
    }

    @Test fun excelDateTokens() {
        val fmt = ExcelNumFmt.parse("yyyy-mm-dd")!!
        assertTrue(fmt.isDate)
        assertEquals(listOf("year", "text", "month", "text", "day"), fmt.dateTimeTokens.map { it.kind })
    }

    // ---- Theme ----

    @Test fun themeResolvesSchemeColors() {
        val theme = OoxmlTheme.parse(THEME)
        assertEquals(0xFF4472C4L, theme.colors["accent1"])
        assertEquals("Arial", theme.majorFont)
        assertEquals(0xFF4472C4L, theme.schemeColor("accent1"))
        assertEquals(theme.colors["dk1"], theme.schemeColor("tx1"))
    }

    // ---- Chart ----

    @Test fun chartParsesSeries() {
        val chart = OoxmlChart.parse(BAR_CHART)!!
        assertEquals(ChartType.BAR, chart.type)
        assertEquals(listOf("A", "B"), chart.categories)
        assertEquals(1, chart.series.size)
        assertEquals("S1", chart.series[0].name)
        assertEquals(listOf(10f, 20f), chart.series[0].values)
        assertTrue(chart.legend)
    }

    // ---- OMML ----

    @Test fun ommlFraction() {
        val mathml = OmmlToMathml.convert(OMML_FRAC)!!
        assertTrue(mathml.contains("<mfrac>"))
        assertTrue(mathml.contains("<mi>x</mi>"))
        assertTrue(mathml.startsWith("<math"))
    }

    // ---- DOCX ----

    @Test fun docxImportsRichContent() {
        val doc = OoxmlImporter.import(zipless(DOCX_ENTRIES), "test.docx") as OdfDocument.TextDocument
        val paras = doc.content
        val heading = paras[0] as OdfContentBlock.Paragraph
        assertEquals(ParagraphStyle.HEADING1, heading.paragraph.style)
        assertEquals("Title", heading.paragraph.spans[0].text)

        val bold = paras[1] as OdfContentBlock.Paragraph
        assertTrue(bold.paragraph.spans[0].bold)

        val table = paras.filterIsInstance<OdfContentBlock.Table>().first()
        assertEquals(2, table.table.rows[0].cells.size)

        val list = paras.filterIsInstance<OdfContentBlock.Paragraph>().first { it.paragraph.listType == ListType.NUMBERED && it.paragraph.spans.any { s -> s.text == "Item" } }
        assertEquals("1", list.paragraph.listNumberFormat)
    }

    // ---- XLSX ----

    @Test fun xlsxImportsValuesAndMerges() {
        val doc = OoxmlImporter.import(zipless(XLSX_ENTRIES), "test.xlsx") as OdfDocument.Spreadsheet
        val sheet = doc.sheets[0]
        assertEquals("Hello", sheet.rows[0].cells[0].text)
        assertEquals(2, sheet.rows[0].cells[0].spannedColumns)
        assertTrue(sheet.rows[0].cells[1].isCovered)
        assertEquals(3.14, sheet.rows[1].cells[0].numberValue!!, 0.0001)
    }

    // ---- PPTX ----

    @Test fun pptxImportsShapeText() {
        val doc = OoxmlImporter.import(zipless(PPTX_ENTRIES), "test.pptx") as OdfDocument.Presentation
        val slide = doc.slides[0]
        val frame = slide.elements.filterIsInstance<OdfSlideElement.Frame>().first()
        val span = frame.frame.paragraphs[0].spans[0]
        assertEquals("Hi", span.text)
        assertTrue(span.bold)
        assertEquals(24f, span.fontSize)
    }

    // Import via OoxmlImporter needs a real zip; build one from the entry map for the public API tests.
    private fun zipless(entries: Map<String, String>): ByteArray {
        val bos = java.io.ByteArrayOutputStream()
        java.util.zip.ZipOutputStream(bos).use { zos ->
            for ((name, content) in entries) {
                zos.putNextEntry(java.util.zip.ZipEntry(name))
                zos.write(content.toByteArray(Charsets.UTF_8))
                zos.closeEntry()
            }
        }
        return bos.toByteArray()
    }

    companion object {
        private const val THEME =
            "<a:theme xmlns:a=\"a\"><a:themeElements><a:clrScheme name=\"X\">" +
                "<a:dk1><a:srgbClr val=\"111111\"/></a:dk1><a:lt1><a:srgbClr val=\"EEEEEE\"/></a:lt1>" +
                "<a:accent1><a:srgbClr val=\"4472C4\"/></a:accent1></a:clrScheme>" +
                "<a:fontScheme><a:majorFont><a:latin typeface=\"Arial\"/></a:majorFont>" +
                "<a:minorFont><a:latin typeface=\"Calibri\"/></a:minorFont></a:fontScheme>" +
                "</a:themeElements></a:theme>"

        private const val BAR_CHART =
            "<c:chartSpace xmlns:c=\"c\"><c:chart><c:plotArea><c:barChart>" +
                "<c:barDir val=\"col\"/><c:grouping val=\"clustered\"/>" +
                "<c:ser><c:tx><c:v>S1</c:v></c:tx>" +
                "<c:cat><c:strRef><c:strCache><c:pt idx=\"0\"><c:v>A</c:v></c:pt><c:pt idx=\"1\"><c:v>B</c:v></c:pt></c:strCache></c:strRef></c:cat>" +
                "<c:val><c:numRef><c:numCache><c:pt idx=\"0\"><c:v>10</c:v></c:pt><c:pt idx=\"1\"><c:v>20</c:v></c:pt></c:numCache></c:numRef></c:val>" +
                "</c:ser></c:barChart></c:plotArea><c:legend><c:legendPos val=\"r\"/></c:legend></c:chart></c:chartSpace>"

        private const val OMML_FRAC =
            "<m:oMath xmlns:m=\"m\"><m:f><m:num><m:r><m:t>x</m:t></m:r></m:num>" +
                "<m:den><m:r><m:t>2</m:t></m:r></m:den></m:f></m:oMath>"

        private val DOCX_ENTRIES = mapOf(
            "word/document.xml" to (
                "<w:document xmlns:w=\"w\"><w:body>" +
                    "<w:p><w:pPr><w:pStyle w:val=\"Heading1\"/></w:pPr><w:r><w:t>Title</w:t></w:r></w:p>" +
                    "<w:p><w:r><w:rPr><w:b/></w:rPr><w:t>Bold</w:t></w:r></w:p>" +
                    "<w:tbl><w:tr><w:tc><w:p><w:r><w:t>a</w:t></w:r></w:p></w:tc>" +
                    "<w:tc><w:p><w:r><w:t>b</w:t></w:r></w:p></w:tc></w:tr></w:tbl>" +
                    "<w:p><w:pPr><w:numPr><w:ilvl w:val=\"0\"/><w:numId w:val=\"1\"/></w:numPr></w:pPr><w:r><w:t>Item</w:t></w:r></w:p>" +
                    "</w:body></w:document>"),
            "word/numbering.xml" to (
                "<w:numbering xmlns:w=\"w\"><w:abstractNum w:abstractNumId=\"0\"><w:lvl w:ilvl=\"0\">" +
                    "<w:numFmt w:val=\"decimal\"/><w:lvlText w:val=\"%1.\"/></w:lvl></w:abstractNum>" +
                    "<w:num w:numId=\"1\"><w:abstractNumId w:val=\"0\"/></w:num></w:numbering>")
        )

        private val XLSX_ENTRIES = mapOf(
            "xl/sharedStrings.xml" to "<sst xmlns=\"s\"><si><t>Hello</t></si></sst>",
            "xl/worksheets/sheet1.xml" to (
                "<worksheet xmlns=\"s\"><sheetData>" +
                    "<row r=\"1\"><c r=\"A1\" t=\"s\"><v>0</v></c><c r=\"B1\"><v>42</v></c></row>" +
                    "<row r=\"2\"><c r=\"A2\"><v>3.14</v></c></row>" +
                    "</sheetData><mergeCells count=\"1\"><mergeCell ref=\"A1:B1\"/></mergeCells></worksheet>")
        )

        private val PPTX_ENTRIES = mapOf(
            "ppt/slides/slide1.xml" to (
                "<p:sld xmlns:p=\"p\" xmlns:a=\"a\"><p:cSld><p:spTree>" +
                    "<p:sp><p:spPr><a:xfrm><a:off x=\"914400\" y=\"914400\"/><a:ext cx=\"1828800\" cy=\"457200\"/></a:xfrm>" +
                    "<a:prstGeom prst=\"rect\"/></p:spPr>" +
                    "<p:txBody><a:p><a:r><a:rPr b=\"1\" sz=\"2400\"/><a:t>Hi</a:t></a:r></a:p></p:txBody></p:sp>" +
                    "</p:spTree></p:cSld></p:sld>")
        )
    }
}
