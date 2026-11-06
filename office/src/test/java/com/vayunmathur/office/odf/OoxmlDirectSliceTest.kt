package com.vayunmathur.office.odf

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Slice 1/4 (Ooxml*): direct importer entry-point tests. These call [OoxmlDocx], [OoxmlXlsx] and
 * [OoxmlPptx] directly (bypassing [OoxmlImporter]'s best-effort fallback) so failures surface
 * the real exception, plus direct unit tests for style-table resolution and run-prop overlays.
 */
class OoxmlDirectSliceTest {

    private fun zipBytes(vararg entries: Pair<String, String>): ByteArray {
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

    private fun pkgBytes(pkg: OoxmlPackage): ByteArray {
        val bos = java.io.ByteArrayOutputStream()
        java.util.zip.ZipOutputStream(bos).use { zos ->
            for ((n, c) in pkg.entries) {
                zos.putNextEntry(java.util.zip.ZipEntry(n))
                zos.write(c.toByteArray(Charsets.UTF_8))
                zos.closeEntry()
            }
        }
        return bos.toByteArray()
    }

    @Test fun stylesParseCharStyleColor() {
        val styles = OoxmlDocxStyles.parseStyles(
            "<w:styles xmlns:w=\"w\">" +
                "<w:style w:styleId=\"Em\" w:type=\"character\">" +
                "<w:rPr><w:i/><w:color w:val=\"FF0000\"/></w:rPr></w:style></w:styles>",
            OoxmlTheme.DEFAULT)
        val def = styles.byId["Em"]
        assertNotNull(def, "Em style missing from ${styles.byId.keys}")
        assertTrue(def.rpr.italic == true, "italic was ${def.rpr.italic}")
        assertNotNull(def.rpr.color, "parsed color is null")
        assertEquals(0xFFFF0000L, def.rpr.color)
    }

    @Test fun charStyleOverlayKeepsColor() {
        val styles = OoxmlDocxStyles.parseStyles(
            "<w:styles xmlns:w=\"w\">" +
                "<w:style w:styleId=\"Em\" w:type=\"character\">" +
                "<w:rPr><w:i/><w:color w:val=\"FF0000\"/></w:rPr></w:style></w:styles>",
            OoxmlTheme.DEFAULT)
        val eff = styles.charStyleRPr("Em")
        assertEquals(0xFFFF0000L, eff.color)
        assertTrue(eff.italic == true)
    }

    @Test fun rprOverlayMergesAllFields() {
        val base = OoxmlDocxStyles.RPr(bold = true, sizeHalfPt = 20, color = 0xFF000000L)
        val over = OoxmlDocxStyles.RPr(italic = true, color = 0xFFFF0000L)
        val merged = base.overlay(over)
        assertTrue(merged.bold == true)
        assertTrue(merged.italic == true)
        assertEquals(20, merged.sizeHalfPt)
        assertEquals(0xFFFF0000L, merged.color)
    }

    @Test fun rprOverlayChainKeepsEarlierDecor() {
        // Chained overlays must not wipe decor props when a later layer leaves them null
        // (regression: split overlayText/overlayDecor passes dropped them).
        val charStyle = OoxmlDocxStyles.RPr(italic = true, color = 0xFFFF0000L)
        val direct = OoxmlDocxStyles.RPr(styleId = "Em")
        val eff = OoxmlDocxStyles.RPr().overlay(charStyle).overlay(direct)
        assertTrue(eff.italic == true)
        assertEquals(0xFFFF0000L, eff.color)
        assertEquals("Em", eff.styleId)
    }

    @Test fun docxDirectCharStyle() {
        val stylesXml = "<w:styles xmlns:w=\"w\">" +
            "<w:style w:styleId=\"Em\" w:type=\"character\">" +
            "<w:rPr><w:i/><w:color w:val=\"FF0000\"/></w:rPr></w:style></w:styles>"
        val pkg = OoxmlPackage.read(zipBytes(
            "word/document.xml" to "<w:document xmlns:w=\"w\" xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\"><w:body>" +
                "<w:p><w:r><w:rPr><w:rStyle w:val=\"Em\"/></w:rPr><w:t>e</w:t></w:r></w:p>" +
                "</w:body></w:document>",
            "word/styles.xml" to stylesXml))
        // Isolate: does the retained styles.xml bytes parse to a red style?
        val reread = OoxmlDocxStyles.parseStyles(pkg.entries["word/styles.xml"]!!, OoxmlTheme.DEFAULT)
        assertEquals(0xFFFF0000L, reread.byId["Em"]?.rpr?.color, "reread styles entry lost color")
        val doc = OoxmlImporter.import(pkgBytes(pkg), "t.docx") as com.vayunmathur.library.ui.odf.OdfDocument.TextDocument
        val span = doc.content
            .filterIsInstance<com.vayunmathur.library.ui.odf.OdfContentBlock.Paragraph>()[0]
            .paragraph.spans[0]
        assertTrue(span.italic)
        assertEquals(0xFFFF0000L, span.color)
    }

    @Test fun xlsxDirectChartDrawing() {
        val pkg = OoxmlPackage.read(zipBytes(
            "xl/workbook.xml" to "<workbook xmlns=\"s\" xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\"><sheets><sheet name=\"S\" sheetId=\"1\" r:id=\"rId1\"/></sheets></workbook>",
            "xl/_rels/workbook.xml.rels" to "<Relationships xmlns=\"p\">" +
                "<Relationship Id=\"rId1\" Type=\"http://t/worksheet\" Target=\"worksheets/sheet1.xml\"/></Relationships>",
            "xl/worksheets/sheet1.xml" to ("<worksheet xmlns=\"s\"><sheetData><row r=\"1\">" +
                "<c r=\"A1\"><v>1</v></c></row></sheetData>" +
                "<drawing r:id=\"d1\" xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\"/>" +
                "</worksheet>"),
            "xl/worksheets/_rels/sheet1.xml.rels" to ("<Relationships xmlns=\"p\">" +
                "<Relationship Id=\"d1\" Type=\"http://t/drawing\" Target=\"../drawings/d1.xml\"/></Relationships>"),
            "xl/drawings/d1.xml" to ("<wsDr xmlns=\"d\"><oneCellAnchor>" +
                "<from><col>0</col><row>0</row><colOff>0</colOff><rowOff>0</rowOff></from>" +
                "<ext cx=\"914400\" cy=\"914400\"/>" +
                "<graphicFrame><graphic><graphicData><chart r:id=\"c1\" " +
                "xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\"/></graphicData></graphic></graphicFrame>" +
                "</oneCellAnchor></wsDr>"),
            "xl/drawings/_rels/d1.xml.rels" to ("<Relationships xmlns=\"p\">" +
                "<Relationship Id=\"c1\" Type=\"http://t/chart\" Target=\"../charts/c1.xml\"/></Relationships>"),
            "xl/charts/c1.xml" to ("<chartSpace><chart><plotArea><barChart>" +
                "<ser><tx><v>S</v></tx>" +
                "<val><numRef><numCache><pt idx=\"0\"><v>4</v></pt></numCache></numRef></val></ser>" +
                "</barChart></plotArea></chart></chartSpace>")))
        val doc = OoxmlImporter.import(pkgBytes(pkg), "c.xlsx") as com.vayunmathur.library.ui.odf.OdfDocument.Spreadsheet
        assertEquals(1, doc.sheets[0].floating.size)
    }

    private fun txParas(
        xml: String,
        rels: Map<String, OoxmlPackage.Rel> = emptyMap()
    ): List<com.vayunmathur.library.ui.odf.OdfParagraph> {
        val parser = OoxmlXml.newParser(xml)
        var e = parser.eventType
        while (!(e == org.xmlpull.v1.XmlPullParser.START_TAG && parser.name == "txBody")) e = parser.next()
        val out = mutableListOf<com.vayunmathur.library.ui.odf.OdfParagraph>()
        val pkg = OoxmlPackage(emptyMap(), emptyMap())
        OoxmlPptxText.parseTxBody(
            parser,
            OoxmlPptx.SlideCtx(pkg, "part", OoxmlTheme.DEFAULT, rels),
            out)
        return out
    }

    @Test fun pptxDirectBullets() {
        val paras = txParas(
            "<p:txBody xmlns:p=\"p\" xmlns:a=\"a\"><a:bodyPr wrap=\"square\"/><a:lstStyle/>" +
                "<a:p><a:pPr lvl=\"0\"><a:buChar char=\"-\"/></a:pPr>" +
                "<a:r><a:t>bullet</a:t></a:r></a:p></p:txBody>")
        assertEquals(1, paras.size)
        assertEquals(com.vayunmathur.library.ui.odf.ListType.BULLET, paras[0].listType)
        assertEquals("-", paras[0].listBulletChar)
        assertEquals("bullet", paras[0].spans[0].text)
    }

    @Test fun pptxDirectNumberedAndPlain() {
        val paras = txParas(
            "<p:txBody xmlns:p=\"p\" xmlns:a=\"a\"><a:bodyPr wrap=\"square\"/><a:lstStyle/>" +
                "<a:p><a:pPr lvl=\"1\"><a:buAutoNum type=\"alphaLcPeriod\"/></a:pPr>" +
                "<a:r><a:t>one</a:t></a:r></a:p>" +
                "<a:p><a:pPr lvl=\"1\"><a:buAutoNum type=\"romanUcPeriod\"/></a:pPr>" +
                "<a:r><a:t>two</a:t></a:r></a:p>" +
                "<a:p><a:pPr><a:buNone/></a:pPr><a:r><a:t>plain</a:t></a:r></a:p></p:txBody>")
        assertEquals(3, paras.size)
        assertEquals(com.vayunmathur.library.ui.odf.ListType.NUMBERED, paras[0].listType)
        assertEquals("a", paras[0].listNumberFormat)
        assertEquals(1, paras[0].listItemIndex)
        assertEquals(2, paras[1].listItemIndex)
        assertEquals("I", paras[1].listNumberFormat)
        assertEquals(com.vayunmathur.library.ui.odf.ParagraphStyle.BODY, paras[2].style)
    }

    @Test fun pptxDirectEmptyPara() {
        val paras = txParas(
            "<p:txBody xmlns:p=\"p\" xmlns:a=\"a\"><a:bodyPr wrap=\"square\"/><a:lstStyle/>" +
                "<a:p><a:r><a:t></a:t></a:r></a:p>" +
                "<a:p><a:r><a:t>real</a:t></a:r></a:p></p:txBody>")
        assertEquals(1, paras.size)
        assertEquals("real", paras[0].spans[0].text)
    }

    @Test fun pptxDirectRunHyperlink() {
        val paras = txParas(
            "<p:txBody xmlns:p=\"p\" xmlns:a=\"a\" xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\">" +
                "<a:bodyPr wrap=\"square\"/><a:lstStyle/>" +
                "<a:p><a:r><a:rPr>" +
                "<a:hlinkClick r:id=\"h1\"/>" +
                "</a:rPr><a:t>linked</a:t></a:r></a:p></p:txBody>",
            mapOf("h1" to OoxmlPackage.Rel("h1", "https://r.test", "http://t/hyperlink", true)))
        assertEquals("https://r.test", paras[0].spans[0].href)
    }

    @Test fun pptxDirectRichRun() {
        val paras = txParas(
            "<p:txBody xmlns:p=\"p\" xmlns:a=\"a\" xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\">" +
                "<a:bodyPr wrap=\"square\"/><a:lstStyle/>" +
                "<a:p><a:r><a:rPr b=\"1\" i=\"1\" sz=\"1200\">" +
                "<a:solidFill><a:srgbClr val=\"00FF00\"/></a:solidFill>" +
                "<a:latin typeface=\"Verdana\"/>" +
                "<a:hlinkClick r:id=\"h1\"/>" +
                "</a:rPr><a:t>rich</a:t></a:r>" +
                "<a:br/><a:fld><a:rPr/><a:t>fld</a:t></a:fld></a:p></p:txBody>")
        val spans = paras[0].spans
        val rich = spans.first { it.text == "rich" }
        assertTrue(rich.bold && rich.italic)
        assertEquals(0xFF00FF00L, rich.color)
        assertEquals("Verdana", rich.fontFamily)
        assertEquals(12f, rich.fontSize!!, 0.01f)
        assertTrue(spans.any { it.text == "\n" })
        assertTrue(spans.any { it.text == "fld" })
    }

    @Test fun pptxDirectCapsRun() {
        // cap="all"/"small" maps to uppercase textTransform (covers the reader branch the
        // exporter round-trip cannot reach, since pptxRun() never writes cap).
        for (cap in listOf("all", "small")) {
            val paras = txParas(
                "<p:txBody xmlns:p=\"p\" xmlns:a=\"a\"><a:bodyPr wrap=\"square\"/><a:lstStyle/>" +
                    "<a:p><a:r><a:rPr cap=\"$cap\"/><a:t>capped</a:t></a:r></a:p></p:txBody>")
            assertEquals("uppercase", paras[0].spans.first { it.text == "capped" }.textTransform, "cap=$cap")
        }
        val plain = txParas(
            "<p:txBody xmlns:p=\"p\" xmlns:a=\"a\"><a:bodyPr wrap=\"square\"/><a:lstStyle/>" +
                "<a:p><a:r><a:rPr/><a:t>plain</a:t></a:r></a:p></p:txBody>")
        assertEquals(null, plain[0].spans.first { it.text == "plain" }.textTransform)
    }

    @Test fun notesSlideRoundTrip() {
        // Notes slides are emitted by the exporter and parsed back by OoxmlPptx.parseNotes.
        val doc = com.vayunmathur.library.ui.odf.OdfDocument.Presentation(
            title = "n",
            slides = listOf(com.vayunmathur.library.ui.odf.OdfSlide(
                name = "S1",
                notes = listOf(com.vayunmathur.library.ui.odf.OdfParagraph(
                    listOf(com.vayunmathur.library.ui.odf.OdfSpan("remember this")))))))
        val back = OoxmlImporter.import(
            com.vayunmathur.library.ui.odf.OoxmlExporter.export(doc), "n.pptx")
            as com.vayunmathur.library.ui.odf.OdfDocument.Presentation
        assertTrue(back.slides[0].notes.any { it.spans.any { s -> s.text == "remember this" } })
    }
}
