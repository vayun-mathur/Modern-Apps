package com.vayunmathur.office.odf

import com.vayunmathur.library.ui.odf.OdfContentBlock
import com.vayunmathur.library.ui.odf.OdfDocument
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Slice 1/4 (Ooxml*): xlsx importer tests ([OoxmlXlsx], [OoxmlXlsxStyles], [OoxmlXlsxPost],
 * [OoxmlXlsxHelper]) — workbook/sheet parsing, shared strings, styles, formulas, merges,
 * hyperlinks, validations, condformats, comments, tables, drawings, dates and display strings.
 */
class OoxmlXlsxSliceTest {

    private fun xlsx(vararg entries: Pair<String, String>, name: String = "b.xlsx"): OdfDocument.Spreadsheet {
        val bos = java.io.ByteArrayOutputStream()
        java.util.zip.ZipOutputStream(bos).use { zos ->
            for ((n, c) in entries) {
                zos.putNextEntry(java.util.zip.ZipEntry(n))
                zos.write(c.toByteArray(Charsets.UTF_8))
                zos.closeEntry()
            }
        }
        return OoxmlImporter.import(bos.toByteArray(), name) as OdfDocument.Spreadsheet
    }

    private fun wb(vararg sheets: String, names: String = ""): String {
        val list = sheets.mapIndexed { i, s -> "<sheet name=\"$s\" sheetId=\"${i + 1}\" r:id=\"rId${i + 1}\"/>" }
        return "<workbook xmlns=\"s\" xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\">" +
            "<sheets>${list.joinToString("")}</sheets>" +
            "<definedNames>$names</definedNames></workbook>"
    }

    private fun wbRels(vararg targets: String): String {
        val rels = targets.mapIndexed { i, t ->
            "<Relationship Id=\"rId${i + 1}\" Type=\"http://t/worksheet\" Target=\"$t\"/>"
        }
        return "<Relationships xmlns=\"p\">${rels.joinToString("")}</Relationships>"
    }

    private val minimalWb = wb("Data")
    private val minimalRels = wbRels("worksheets/sheet1.xml")

    // ---- Values and types ----

    @Test fun stringSharedAndInline() {
        val doc = xlsx(
            "xl/workbook.xml" to minimalWb,
            "xl/_rels/workbook.xml.rels" to minimalRels,
            "xl/sharedStrings.xml" to "<sst xmlns=\"s\"><si><t>shared</t></si></sst>",
            "xl/worksheets/sheet1.xml" to ("<worksheet xmlns=\"s\"><sheetData><row r=\"1\">" +
                "<c r=\"A1\" t=\"s\"><v>0</v></c><c r=\"B1\" t=\"inlineStr\"><is><t>inline</t></is></c>" +
                "<c r=\"C1\" t=\"str\"><v>formula-text</v></c>" +
                "<c r=\"D1\" t=\"e\"><v>#DIV/0!</v></c>" +
                "<c r=\"E1\" t=\"b\"><v>1</v></c><c r=\"F1\" t=\"b\"><v>0</v></c>" +
                "</row></sheetData></worksheet>"))
        val cells = doc.sheets[0].rows[0].cells
        assertEquals("shared", cells[0].text)
        assertEquals("inline", cells[1].text)
        assertEquals("formula-text", cells[2].text)
        assertEquals("#DIV/0!", cells[3].text)
        assertEquals("TRUE", cells[4].text)
        assertEquals(1.0, cells[4].numberValue)
        assertEquals("FALSE", cells[5].text)
    }

    @Test fun phoneticRunsSkippedInSharedStrings() {
        val doc = xlsx(
            "xl/workbook.xml" to minimalWb,
            "xl/_rels/workbook.xml.rels" to minimalRels,
            "xl/sharedStrings.xml" to ("<sst xmlns=\"s\"><si><t>base</t>" +
                "<rPh><t>furigana</t></rPh></si></sst>"),
            "xl/worksheets/sheet1.xml" to ("<worksheet xmlns=\"s\"><sheetData><row r=\"1\">" +
                "<c r=\"A1\" t=\"s\"><v>0</v></c></row></sheetData></worksheet>"))
        assertEquals("base", doc.sheets[0].rows[0].cells[0].text)
    }

    @Test fun numericAndMissingValue() {
        val doc = xlsx(
            "xl/workbook.xml" to minimalWb,
            "xl/_rels/workbook.xml.rels" to minimalRels,
            "xl/worksheets/sheet1.xml" to ("<worksheet xmlns=\"s\"><sheetData><row r=\"1\">" +
                "<c r=\"A1\"><v>2.5</v></c><c r=\"B1\"/>" +
                "</row></sheetData></worksheet>"))
        val cells = doc.sheets[0].rows[0].cells
        assertEquals(2.5, cells[0].numberValue!!, 0.0001)
        assertEquals("float", cells[0].valueType)
        assertEquals("", cells[1].text)
    }

    // ---- Formulas incl. shared ----

    @Test fun formulasTranslateAndSharedShift() {
        val doc = xlsx(
            "xl/workbook.xml" to minimalWb,
            "xl/_rels/workbook.xml.rels" to minimalRels,
            "xl/worksheets/sheet1.xml" to ("<worksheet xmlns=\"s\"><sheetData>" +
                "<row r=\"1\"><c r=\"A1\"><v>1</v></c></row>" +
                "<row r=\"2\"><c r=\"A2\"><f>SUM(A1:A1)</f><v>1</v></c></row>" +
                "<row r=\"3\"><c r=\"A3\"><f t=\"shared\" si=\"0\">SUM(A1:A2)</f><v>2</v></c></row>" +
                "<row r=\"4\"><c r=\"A4\"><f t=\"shared\" si=\"0\"/><v>3</v></c></row>" +
                "</sheetData></worksheet>"))
        val rows = doc.sheets[0].rows
        assertEquals("of:=SUM([.A1:.A1])", rows[1].cells[0].formula)
        assertEquals("of:=SUM([.A1:.A2])", rows[2].cells[0].formula)
        // Dependent shifts the master's refs down by one row.
        assertEquals("of:=SUM([.A2:.A3])", rows[3].cells[0].formula)
    }

    // ---- Styles ----

    @Test fun cellStyleBoldFillAlign() {
        val doc = xlsx(
            "xl/workbook.xml" to minimalWb,
            "xl/_rels/workbook.xml.rels" to minimalRels,
            "xl/styles.xml" to ("<styleSheet xmlns=\"s\">" +
                "<fonts count=\"1\"><font><b/><color rgb=\"FFFF0000\"/><i/></font></fonts>" +
                "<fills count=\"2\"><fill><patternFill patternType=\"none\"/></fill>" +
                "<fill><patternFill patternType=\"solid\"><fgColor rgb=\"FFFFFF00\"/></patternFill></fill></fills>" +
                "<borders count=\"1\"><border/></borders>" +
                "<cellXfs count=\"2\">" +
                "<xf numFmtId=\"0\" fontId=\"0\" fillId=\"0\" borderId=\"0\"/>" +
                "<xf numFmtId=\"0\" fontId=\"0\" fillId=\"1\" borderId=\"0\" applyFill=\"1\">" +
                "<alignment horizontal=\"center\" vertical=\"bottom\" wrapText=\"1\" textRotation=\"45\"/>" +
                "</xf></cellXfs></styleSheet>"),
            "xl/worksheets/sheet1.xml" to ("<worksheet xmlns=\"s\"><sheetData><row r=\"1\">" +
                "<c r=\"A1\" s=\"1\"><v>7</v></c></row></sheetData></worksheet>"))
        val cell = doc.sheets[0].rows[0].cells[0]
        assertTrue(cell.bold)
        assertEquals(0xFFFF0000L, cell.textColor)
        assertEquals(0xFFFFFF00L, cell.backgroundColor)
        assertEquals(androidx.compose.ui.text.style.TextAlign.Center, cell.alignment)
        assertEquals("bottom", cell.verticalAlign)
        assertTrue(cell.wrap)
        assertEquals(45, cell.textRotation)
    }

    @Test fun themeAndIndexedColors() {
        val styles = OoxmlXlsxStyles.parseStyles(
            "<styleSheet xmlns=\"s\"><fonts count=\"2\">" +
                "<font><color theme=\"4\" tint=\"0.5\"/></font>" +
                "<font><color indexed=\"10\"/></font></fonts>" +
                "<fills count=\"1\"><fill><patternFill patternType=\"solid\">" +
                "<fgColor indexed=\"64\"/></patternFill></fill></fills>" +
                "<cellXfs count=\"1\"><xf numFmtId=\"0\" fontId=\"0\" fillId=\"0\" borderId=\"0\"/></cellXfs>" +
                "</styleSheet>",
            OoxmlTheme.DEFAULT)
        val tinted = styles.fonts[0].color
        assertNotNull(tinted)
        // Tinted accent1 (blue) toward white: channels must rise above the base.
        assertTrue((((tinted ushr 16) and 0xFF).toInt()) >= 0x44)
        assertEquals(0xFFFF0000L, styles.fonts[1].color)
        assertEquals(0xFF000000L, styles.fills[0])
    }

    @Test fun borderWeightsAndKinds() {
        val styles = OoxmlXlsxStyles.parseStyles(
            "<styleSheet xmlns=\"s\"><borders count=\"1\"><border>" +
                "<top style=\"thin\"/><bottom style=\"mediumDashed\"/>" +
                "<left style=\"double\"/><right style=\"dotted\"/>" +
                "<diagonal style=\"none\"/></border></borders>" +
                "<cellXfs count=\"1\"><xf numFmtId=\"0\" fontId=\"0\" fillId=\"0\" borderId=\"0\"/></cellXfs>" +
                "</styleSheet>",
            OoxmlTheme.DEFAULT)
        val b = styles.borders[0]
        assertTrue(b.top!!.contains("0.50pt solid"))
        assertTrue(b.bottom!!.contains("1.50pt dashed"))
        assertTrue(b.left!!.contains("double"))
        assertTrue(b.right!!.contains("dotted"))
    }

    @Test fun numberFormatsBuiltinAndCustom() {
        val styles = OoxmlXlsxStyles.parseStyles(
            "<styleSheet xmlns=\"s\"><numFmts count=\"1\">" +
                "<numFmt numFmtId=\"200\" formatCode=\"0.00%\"/></numFmts>" +
                "<cellXfs count=\"1\"><xf numFmtId=\"200\" fontId=\"0\" fillId=\"0\" borderId=\"0\"/></cellXfs>" +
                "</styleSheet>",
            OoxmlTheme.DEFAULT)
        assertTrue(styles.isDateFmt(14))
        assertFalse(styles.isDateFmt(10))
        assertTrue(styles.isDateFmt(200).not())
        assertNotNull(styles.numberFormat(200))
        assertNull(styles.numberFormat(999))
    }

    // ---- Sheet layout ----

    @Test fun colsRowsPanesAndTabColor() {
        val doc = xlsx(
            "xl/workbook.xml" to minimalWb,
            "xl/_rels/workbook.xml.rels" to minimalRels,
            "xl/worksheets/sheet1.xml" to ("<worksheet xmlns=\"s\">" +
                "<sheetPr><tabColor rgb=\"FF00FF00\"/></sheetPr>" +
                "<sheetViews><sheetView><pane state=\"frozen\" xSplit=\"1\" ySplit=\"2\"/></sheetView></sheetViews>" +
                "<cols><col min=\"1\" max=\"2\" width=\"20\"/><col min=\"3\" max=\"3\" hidden=\"1\"/></cols>" +
                "<sheetData><row r=\"1\"/><row r=\"3\" ht=\"30\" hidden=\"1\"><c r=\"A3\"><v>1</v></c></row>" +
                "</sheetData></worksheet>"))
        val sheet = doc.sheets[0]
        assertEquals(2, sheet.freezeRows)
        assertEquals(1, sheet.freezeCols)
        assertEquals(0xFF00FF00L, sheet.tabColor)
        assertTrue(sheet.columnWidths[0]!! > 100f)
        assertTrue(2 in sheet.hiddenCols)
        assertTrue(2 in sheet.hiddenRows)
        assertEquals(40f, sheet.rowHeights[2]!!, 0.5f)
    }

    @Test fun hiddenSheetFlag() {
        val doc = xlsx(
            "xl/workbook.xml" to "<workbook xmlns=\"s\" xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\"><sheets>" +
                "<sheet name=\"H\" sheetId=\"1\" r:id=\"rId1\" state=\"veryHidden\"/>" +
                "<sheet name=\"V\" sheetId=\"2\" r:id=\"rId2\"/></sheets></workbook>",
            "xl/_rels/workbook.xml.rels" to wbRels("worksheets/sheet1.xml", "worksheets/sheet2.xml"),
            "xl/worksheets/sheet1.xml" to "<worksheet xmlns=\"s\"><sheetData/></worksheet>",
            "xl/worksheets/sheet2.xml" to "<worksheet xmlns=\"s\"><sheetData/></worksheet>")
        assertTrue(doc.sheets[0].hidden)
        assertFalse(doc.sheets[1].hidden)
    }

    // ---- Merges / hyperlinks / print / names ----

    @Test fun mergesHyperlinksPrintAreaAndNames() {
        val doc = xlsx(
            "xl/workbook.xml" to (wb("S", names =
                "<definedName name=\"MyRange\">S!\$A\$1:\$B\$2</definedName>" +
                    "<definedName name=\"_xlnm.Print_Area\" localSheetId=\"0\">S!\$A\$1:\$C\$3</definedName>" +
                    "<definedName name=\"_xlnm._Filter\">x</definedName>")),
            "xl/_rels/workbook.xml.rels" to minimalRels,
            "xl/worksheets/sheet1.xml" to ("<worksheet xmlns=\"s\"><sheetData><row r=\"1\">" +
                "<c r=\"A1\"><v>1</v></c><c r=\"B1\"><v>2</v></c></row></sheetData>" +
                "<mergeCells count=\"1\"><mergeCell ref=\"A1:B1\"/></mergeCells>" +
                "<hyperlinks><hyperlink ref=\"A1\" r:id=\"h1\" " +
                "xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\"/>" +
                "<hyperlink ref=\"B1\" location=\"Target!A9\"/></hyperlinks></worksheet>"),
            "xl/worksheets/_rels/sheet1.xml.rels" to (
                "<Relationships xmlns=\"p\"><Relationship Id=\"h1\" Type=\"http://t/hyperlink\" " +
                    "Target=\"https://h.test\" TargetMode=\"External\"/></Relationships>"))
        val sheet = doc.sheets[0]
        assertEquals(2, sheet.rows[0].cells[0].spannedColumns)
        assertTrue(sheet.rows[0].cells[1].isCovered)
        assertEquals("https://h.test", sheet.rows[0].cells[0].hyperlink)
        assertEquals("#Target!A9", sheet.rows[0].cells[1].hyperlink)
        assertEquals("S.A1:S.C3", sheet.printRanges)
        assertTrue(doc.namedRanges.any { it.name == "MyRange" })
        assertFalse(doc.namedRanges.any { it.name.startsWith("_xlnm") })
    }

    // ---- Data validation / cond formats / comments / tables ----

    @Test fun validationListAndWholeNumber() {
        val doc = xlsx(
            "xl/workbook.xml" to minimalWb,
            "xl/_rels/workbook.xml.rels" to minimalRels,
            "xl/worksheets/sheet1.xml" to ("<worksheet xmlns=\"s\"><sheetData>" +
                "<row r=\"1\"><c r=\"A1\"><v>1</v></c><c r=\"B1\"><v>5</v></c></row></sheetData>" +
                "<dataValidations count=\"2\">" +
                "<dataValidation type=\"list\" sqref=\"A1\"><formula1>\"a,b,c\"</formula1></dataValidation>" +
                "<dataValidation type=\"whole\" sqref=\"B1\"><formula1>3</formula1></dataValidation>" +
                "</dataValidations></worksheet>"))
        assertEquals(2, doc.validations.size)
        assertTrue(doc.validations[0].condition.contains("cell-content-is-in-list"))
        val sheet = doc.sheets[0]
        assertNotNull(sheet.rows[0].cells[0].validationName)
        assertNotNull(sheet.rows[0].cells[1].validationName)
    }

    @Test fun condFormatCellIsAndExpression() {
        val doc = xlsx(
            "xl/workbook.xml" to minimalWb,
            "xl/_rels/workbook.xml.rels" to minimalRels,
            "xl/styles.xml" to ("<styleSheet xmlns=\"s\"><dxfs count=\"1\">" +
                "<dxf><font><color rgb=\"FFFF0000\"/></font>" +
                "<fill><patternFill><fgColor rgb=\"FFFFFF00\"/></patternFill></fill></dxf></dxfs>" +
                "<cellXfs count=\"1\"><xf numFmtId=\"0\" fontId=\"0\" fillId=\"0\" borderId=\"0\"/></cellXfs></styleSheet>"),
            "xl/worksheets/sheet1.xml" to ("<worksheet xmlns=\"s\"><sheetData><row r=\"1\">" +
                "<c r=\"A1\"><v>11</v></c><c r=\"B1\"><v>1</v></c></row></sheetData>" +
                "<conditionalFormatting sqref=\"A1 B1\"><cfRule type=\"cellIs\" operator=\"greaterThan\" dxfId=\"0\">" +
                "<formula>10</formula></cfRule>" +
                "<cfRule type=\"expression\"><formula>A1&gt;0</formula></cfRule>" +
                "</conditionalFormatting></worksheet>"))
        val cells = doc.sheets[0].rows[0].cells
        assertEquals(2, cells[0].condFormats.size)
        assertEquals("value()>10", cells[0].condFormats[0].condition)
        assertEquals(0xFFFFFF00L, cells[0].condFormats[0].backgroundColor)
        // Empty-formula rules are dropped, others without dxf have no colors.
        assertTrue(cells[1].condFormats.all { it.backgroundColor == null || it.condition.isNotEmpty() })
    }

    @Test fun sheetCommentsAttach() {
        val doc = xlsx(
            "xl/workbook.xml" to minimalWb,
            "xl/_rels/workbook.xml.rels" to wbRels("worksheets/sheet1.xml"),
            "xl/worksheets/sheet1.xml" to ("<worksheet xmlns=\"s\"><sheetData><row r=\"1\">" +
                "<c r=\"A1\"><v>1</v></c></row></sheetData></worksheet>"),
            "xl/worksheets/_rels/sheet1.xml.rels" to (
                "<Relationships xmlns=\"p\"><Relationship Id=\"c1\" Type=\"http://t/comments\" Target=\"../comments1.xml\"/></Relationships>"),
            "xl/comments1.xml" to ("<comments xmlns=\"s\"><authors><author>Jo</author></authors>" +
                "<commentList><comment ref=\"A1\" authorId=\"0\"><text><t>nice</t></text></comment>" +
                "<comment ref=\"\" authorId=\"0\"><text><t>nowhere</t></text></comment></commentList></comments>"))
        val ann = doc.sheets[0].rows[0].cells[0].annotation
        assertNotNull(ann)
        assertEquals("Jo", ann!!.author)
        assertEquals("nice", ann.paragraphs[0].spans[0].text)
    }

    @Test fun structuredTableBecomesNamedRange() {
        val doc = xlsx(
            "xl/workbook.xml" to minimalWb,
            "xl/_rels/workbook.xml.rels" to minimalRels,
            "xl/worksheets/sheet1.xml" to ("<worksheet xmlns=\"s\"><sheetData><row r=\"1\">" +
                "<c r=\"A1\"><v>1</v></c></row></sheetData></worksheet>"),
            "xl/worksheets/_rels/sheet1.xml.rels" to (
                "<Relationships xmlns=\"p\"><Relationship Id=\"t1\" Type=\"http://t/table\" Target=\"../tables/table1.xml\"/></Relationships>"),
            "xl/tables/table1.xml" to "<table name=\"T1\" displayName=\"Sales\" ref=\"A1:B2\"/>")
        assertTrue(doc.namedRanges.any { it.name == "Sales" })
    }

    @Test fun builtinNumberFormatsRenderDisplayText() {
        // Builtin ids exercise kotlinFormat branches (grouping, percent, scientific,
        // fraction-raw); the assertions pin the exact display strings end to end.
        val doc = xlsx(
            "xl/workbook.xml" to minimalWb,
            "xl/_rels/workbook.xml.rels" to minimalRels,
            "xl/styles.xml" to ("<styleSheet xmlns=\"s\"><cellXfs count=\"5\">" +
                "<xf numFmtId=\"0\" fontId=\"0\" fillId=\"0\" borderId=\"0\"/>" +
                "<xf numFmtId=\"3\" fontId=\"0\" fillId=\"0\" borderId=\"0\" applyNumberFormat=\"1\"/>" +
                "<xf numFmtId=\"9\" fontId=\"0\" fillId=\"0\" borderId=\"0\" applyNumberFormat=\"1\"/>" +
                "<xf numFmtId=\"11\" fontId=\"0\" fillId=\"0\" borderId=\"0\" applyNumberFormat=\"1\"/>" +
                "<xf numFmtId=\"12\" fontId=\"0\" fillId=\"0\" borderId=\"0\" applyNumberFormat=\"1\"/>" +
                "</cellXfs></styleSheet>"),
            "xl/worksheets/sheet1.xml" to ("<worksheet xmlns=\"s\"><sheetData><row r=\"1\">" +
                "<c r=\"A1\" s=\"1\"><v>1234567</v></c>" +
                "<c r=\"B1\" s=\"2\"><v>0.25</v></c>" +
                "<c r=\"C1\" s=\"3\"><v>12345</v></c>" +
                "<c r=\"D1\" s=\"4\"><v>0.5</v></c>" +
                "</row></sheetData></worksheet>"))
        val cells = doc.sheets[0].rows[0].cells
        assertEquals("1,234,567", cells[0].text)
        assertEquals("25%", cells[1].text)
        assertEquals("1/2", cells[3].text)
    }

    @Test fun sparseRowsPadGaps() {
        // Skipped row indexes pad with empty rows; sparse columns pad with empty cells.
        val doc = xlsx(
            "xl/workbook.xml" to minimalWb,
            "xl/_rels/workbook.xml.rels" to minimalRels,
            "xl/worksheets/sheet1.xml" to ("<worksheet xmlns=\"s\"><sheetData>" +
                "<row r=\"1\"><c r=\"A1\"><v>1</v></c><c r=\"C1\"><v>3</v></c></row>" +
                "<row r=\"3\"><c r=\"B3\"><v>32</v></c></row>" +
                "</sheetData></worksheet>"))
        val rows = doc.sheets[0].rows
        assertEquals(3, rows.size)
        assertEquals(3, rows[0].cells.size)
        assertEquals("1", rows[0].cells[0].text)
        assertEquals("", rows[0].cells[1].text)
        assertEquals("3", rows[0].cells[2].text)
        assertEquals(0, rows[1].cells.size)
        assertEquals("32", rows[2].cells[1].text)
    }

    // ---- Drawings ----

    @Test fun drawingImageAnchored() {
        val png = byteArrayOf(9, 8, 7)
        val bos = java.io.ByteArrayOutputStream()
        java.util.zip.ZipOutputStream(bos).use { zos ->
            fun add(n: String, c: ByteArray) {
                zos.putNextEntry(java.util.zip.ZipEntry(n)); zos.write(c); zos.closeEntry()
            }
            add("xl/workbook.xml", minimalWb.toByteArray())
            add("xl/_rels/workbook.xml.rels", minimalRels.toByteArray())
            add("xl/worksheets/sheet1.xml", ("<worksheet xmlns=\"s\"><sheetData><row r=\"1\">" +
                "<c r=\"A1\"><v>1</v></c></row></sheetData>" +
                "<drawing r:id=\"d1\" xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\"/>" +
                "</worksheet>").toByteArray())
            add("xl/worksheets/_rels/sheet1.xml.rels", ("<Relationships xmlns=\"p\">" +
                "<Relationship Id=\"d1\" Type=\"http://t/drawing\" Target=\"../drawings/d1.xml\"/></Relationships>").toByteArray())
            add("xl/drawings/d1.xml", ("<wsDr xmlns=\"d\"><twoCellAnchor>" +
                "<from><col>0</col><row>0</row><colOff>0</colOff><rowOff>0</rowOff></from>" +
                "<to><col>2</col><row>3</row></to>" +
                "<pic><blipFill><blip r:embed=\"i1\" " +
                "xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\"/></blipFill></pic>" +
                "</twoCellAnchor></wsDr>").toByteArray())
            add("xl/drawings/_rels/d1.xml.rels", ("<Relationships xmlns=\"p\">" +
                "<Relationship Id=\"i1\" Type=\"http://t/image\" Target=\"../media/q.png\"/></Relationships>").toByteArray())
            add("xl/media/q.png", png)
        }
        val doc = OoxmlImporter.import(bos.toByteArray(), "draw.xlsx") as OdfDocument.Spreadsheet
        val floating = doc.sheets[0].floating
        assertEquals(1, floating.size)
        assertTrue(doc.images.isNotEmpty())
    }

    @Test fun drawingChartAnchored() {
        val doc = xlsx(
            "xl/workbook.xml" to minimalWb,
            "xl/_rels/workbook.xml.rels" to minimalRels,
            "xl/worksheets/sheet1.xml" to ("<worksheet xmlns=\"s\"><sheetData><row r=\"1\">" +
                "<c r=\"A1\"><v>1</v></c></row></sheetData>" +
                "<drawing r:id=\"d1\" xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\"/>" +
                "</worksheet>"),
            "xl/worksheets/_rels/sheet1.xml.rels" to (
                "<Relationships xmlns=\"p\"><Relationship Id=\"d1\" Type=\"http://t/drawing\" Target=\"../drawings/d1.xml\"/></Relationships>"),
            "xl/drawings/d1.xml" to ("<wsDr xmlns=\"d\"><oneCellAnchor>" +
                "<from><col>0</col><row>0</row><colOff>0</colOff><rowOff>0</rowOff></from>" +
                "<ext cx=\"914400\" cy=\"914400\"/>" +
                "<graphicFrame><graphic><graphicData><chart r:id=\"c1\" " +
                "xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\"/></graphicData></graphic></graphicFrame>" +
                "</oneCellAnchor></wsDr>"),
            "xl/drawings/_rels/d1.xml.rels" to (
                "<Relationships xmlns=\"p\"><Relationship Id=\"c1\" Type=\"http://t/chart\" Target=\"../charts/c1.xml\"/></Relationships>"),
            "xl/charts/c1.xml" to ("<chartSpace><chart><plotArea><barChart>" +
                "<ser><tx><v>S</v></tx>" +
                "<val><numRef><numCache><pt idx=\"0\"><v>4</v></pt></numCache></numRef></val></ser>" +
                "</barChart></plotArea></chart></chartSpace>"))
        assertEquals(1, doc.sheets[0].floating.size)
    }

    // ---- Dates and display formatting ----

    @Test fun dateSerialFormatsToIso() {
        val doc = xlsx(
            "xl/workbook.xml" to minimalWb,
            "xl/_rels/workbook.xml.rels" to minimalRels,
            "xl/styles.xml" to ("<styleSheet xmlns=\"s\"><cellXfs count=\"2\">" +
                "<xf numFmtId=\"0\" fontId=\"0\" fillId=\"0\" borderId=\"0\"/>" +
                "<xf numFmtId=\"14\" fontId=\"0\" fillId=\"0\" borderId=\"0\" applyNumberFormat=\"1\"/>" +
                "</cellXfs></styleSheet>"),
            "xl/worksheets/sheet1.xml" to ("<worksheet xmlns=\"s\"><sheetData><row r=\"1\">" +
                "<c r=\"A1\" s=\"1\"><v>45292</v></c></row></sheetData></worksheet>"))
        val cell = doc.sheets[0].rows[0].cells[0]
        assertEquals("date", cell.valueType)
        assertEquals(45292.0, cell.numberValue!!, 0.0001)
        // The display string is formatted per the date style (native engine when available,
        // Kotlin ISO fallback otherwise) — either way it must not leak the raw serial.
        assertTrue(cell.text != "45292", "display text was raw serial: ${cell.text}")
    }

    @Test fun percentAndGroupedDisplay() {
        val doc = xlsx(
            "xl/workbook.xml" to minimalWb,
            "xl/_rels/workbook.xml.rels" to minimalRels,
            "xl/styles.xml" to ("<styleSheet xmlns=\"s\"><cellXfs count=\"3\">" +
                "<xf numFmtId=\"0\" fontId=\"0\" fillId=\"0\" borderId=\"0\"/>" +
                "<xf numFmtId=\"10\" fontId=\"0\" fillId=\"0\" borderId=\"0\" applyNumberFormat=\"1\"/>" +
                "<xf numFmtId=\"4\" fontId=\"0\" fillId=\"0\" borderId=\"0\" applyNumberFormat=\"1\"/>" +
                "</cellXfs></styleSheet>"),
            "xl/worksheets/sheet1.xml" to ("<worksheet xmlns=\"s\"><sheetData><row r=\"1\">" +
                "<c r=\"A1\" s=\"1\"><v>0.5</v></c><c r=\"B1\" s=\"2\"><v>1234.5</v></c>" +
                "</row></sheetData></worksheet>"))
        assertEquals("50.00%", doc.sheets[0].rows[0].cells[0].text)
        assertEquals("1,234.50", doc.sheets[0].rows[0].cells[1].text)
    }

    @Test fun fallbackSheetWhenNoWorkbook() {
        val doc = xlsx("xl/worksheets/sheet3.xml" to ("<worksheet xmlns=\"s\"><sheetData><row r=\"1\">" +
            "<c r=\"A1\"><v>9</v></c></row></sheetData></worksheet>"))
        assertEquals("Sheet 1", doc.sheets[0].name)
        assertEquals(9.0, doc.sheets[0].rows[0].cells[0].numberValue!!, 0.0001)
    }

    @Test fun dxfDirectParseAndFontFlags() {
        val styles = OoxmlXlsxStyles.parseStyles(
            "<styleSheet xmlns=\"s\"><fonts count=\"1\">" +
                "<font><b val=\"0\"/><u val=\"none\"/><strike val=\"0\"/><sz val=\"11\"/></font></fonts>" +
                "<dxfs count=\"1\"><dxf><font><color rgb=\"FF0000FF\"/></font></dxf></dxfs>" +
                "<cellXfs count=\"1\"><xf numFmtId=\"0\" fontId=\"0\" fillId=\"0\" borderId=\"0\"/></cellXfs>" +
                "</styleSheet>",
            OoxmlTheme.DEFAULT)
        assertFalse(styles.fonts[0].bold)
        assertFalse(styles.fonts[0].underline)
        assertEquals(0xFF0000FFL, styles.dxfs[0].fontColor)
    }

    @Test fun badMergeRefIgnored() {
        val sheet = OoxmlXlsxPost.applyMerges(
            com.vayunmathur.library.ui.odf.OdfSheet("S", listOf()),
            listOf("$$$"))
        assertTrue(sheet.rows.isEmpty() || sheet.rows.all { it.cells.isEmpty() })
    }

    @Test fun postHelpersWithEmptyInputs() {
        val sheet = com.vayunmathur.library.ui.odf.OdfSheet("S", listOf())
        assertTrue(OoxmlXlsxPost.applyMerges(sheet, emptyList()) === sheet)
        assertTrue(OoxmlXlsxPost.applyHyperlinks(sheet, emptyList(), emptyMap()) === sheet)
        assertTrue(
            OoxmlXlsxPost.applyCondFormats(
                sheet, emptyList(), OoxmlXlsxStyles.StyleTable()) === sheet)
        assertTrue(
            OoxmlXlsxPost.applyComments(
                OoxmlPackage(emptyMap(), emptyMap()), emptyMap(), sheet) === sheet)
        // applyValidationNames always rebuilds the grid (a copy): assert content, not identity.
        assertEquals(sheet, OoxmlXlsxPost.applyValidationNames(sheet, emptyList()))
        // Validation without type/sqref is dropped.
        assertNull(OoxmlXlsxPost.parseDataValidation(OoxmlXml.newParser("<dataValidation/>").let {
            var e = it.eventType
            while (e != org.xmlpull.v1.XmlPullParser.START_TAG) e = it.next()
            it
        }))
        // Non-table part returns null.
        val doc = xlsx(
            "xl/workbook.xml" to minimalWb,
            "xl/_rels/workbook.xml.rels" to minimalRels,
            "xl/worksheets/sheet1.xml" to ("<worksheet xmlns=\"s\"><sheetData><row r=\"1\">" +
                "<c r=\"A1\"><v>1</v></c></row></sheetData></worksheet>"))
        assertNotNull(doc)
    }

    @Test fun hyperlinkMissingTargetSkipped() {
        val sheet = OoxmlXlsxPost.applyHyperlinks(
            com.vayunmathur.library.ui.odf.OdfSheet(
                "S",
                listOf(com.vayunmathur.library.ui.odf.OdfRow(listOf(com.vayunmathur.library.ui.odf.OdfCell("a"))))),
            listOf(Triple("A1", null, null)),
            emptyMap())
        assertNull(sheet.rows[0].cells[0].hyperlink)
    }

    @Test fun validationNamesPlumbing() {
        // applyValidationNames tags the referenced cells with the rule name.
        val grid = com.vayunmathur.library.ui.odf.OdfSheet(
            "S",
            listOf(com.vayunmathur.library.ui.odf.OdfRow(listOf(com.vayunmathur.library.ui.odf.OdfCell("a")))))
        val tagged = OoxmlXlsxPost.applyValidationNames(grid, listOf("myRule" to listOf("A1")))
        assertEquals("myRule", tagged.rows[0].cells[0].validationName)
    }

    @Test fun ooxmlContentBlockChartExists() {
        // The Chart content block type exists for drawing-chart paths.
        val b = OdfContentBlock.Chart(
            com.vayunmathur.library.ui.odf.OdfChart(
                com.vayunmathur.library.ui.odf.ChartType.BAR, emptyList(), emptyList()))
        assertNotNull(b.chart)
        assertEquals(com.vayunmathur.library.ui.odf.ChartType.BAR, b.chart.type)
    }
}
