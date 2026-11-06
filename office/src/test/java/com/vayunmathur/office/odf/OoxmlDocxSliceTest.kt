package com.vayunmathur.office.odf

import com.vayunmathur.library.ui.odf.ListType
import com.vayunmathur.library.ui.odf.OdfContentBlock
import com.vayunmathur.library.ui.odf.OdfDocument
import com.vayunmathur.library.ui.odf.ParagraphStyle
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Slice 1/4 (Ooxml*): docx importer tests ([OoxmlDocx] + [OoxmlDocxStyles]) — run/paragraph
 * formatting, style inheritance, numbering, sections, fields, hyperlinks, notes, headers/footers,
 * bookmarks, tracked changes, symbols and text boxes.
 */
class OoxmlDocxSliceTest {

    private fun docx(vararg entries: Pair<String, String>, name: String = "t.docx"): OdfDocument.TextDocument {
        val bos = java.io.ByteArrayOutputStream()
        java.util.zip.ZipOutputStream(bos).use { zos ->
            for ((n, c) in entries) {
                zos.putNextEntry(java.util.zip.ZipEntry(n))
                zos.write(c.toByteArray(Charsets.UTF_8))
                zos.closeEntry()
            }
        }
        return OoxmlImporter.import(bos.toByteArray(), name) as OdfDocument.TextDocument
    }

    private fun paras(doc: OdfDocument.TextDocument) =
        doc.content.filterIsInstance<OdfContentBlock.Paragraph>().map { it.paragraph }

    private fun body(vararg inner: String) =
        "<w:document xmlns:w=\"w\" xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\"><w:body>${inner.joinToString("")}</w:body></w:document>"

    private fun para(text: String, ppr: String = "", rpr: String = "") =
        "<w:p>${if (ppr.isNotEmpty()) "<w:pPr>$ppr</w:pPr>" else ""}" +
            "<w:r>${if (rpr.isNotEmpty()) "<w:rPr>$rpr</w:rPr>" else ""}<w:t>$text</w:t></w:r></w:p>"

    // ---- Run formatting ----

    @Test fun runFormattingMaps() {
        val doc = docx("word/document.xml" to body(
            para("bi", rpr = "<w:b/><w:i/><w:strike/><w:sz w:val=\"24\"/>" +
                "<w:rFonts w:ascii=\"Arial\"/><w:vertAlign w:val=\"superscript\"/>" +
                "<w:spacing w:val=\"40\"/><w:lang w:val=\"en-US\"/>" +
                "<w:u w:val=\"single\" w:color=\"FF0000\"/><w:color w:val=\"00FF00\"/>")))
        val span = paras(doc)[0].spans[0]
        assertTrue(span.bold)
        assertTrue(span.italic)
        assertTrue(span.strikethrough)
        assertEquals(12f, span.fontSize)
        assertEquals("Arial", span.fontFamily)
        assertTrue(span.superscript)
        assertEquals(2f, span.letterSpacing)
        assertEquals("en", span.language)
        assertEquals("US", span.country)
        assertTrue(span.underline)
        assertEquals("solid", span.underlineStyle)
        assertEquals(0xFFFF0000L, span.underlineColor)
        assertEquals(0xFF00FF00L, span.color)
    }

    @Test fun vanishHidesRun() {
        val doc = docx("word/document.xml" to body(para("gone", rpr = "<w:vanish/>"), para("kept")))
        val texts = paras(doc).flatMap { it.spans }.map { it.text }
        assertFalse("gone" in texts)
        assertTrue("kept" in texts)
    }

    @Test fun capsMapsToUppercaseTransform() {
        val doc = docx("word/document.xml" to body(para("hi", rpr = "<w:caps/>")))
        assertEquals("uppercase", paras(doc)[0].spans[0].textTransform)
        val sc = docx("word/document.xml" to body(para("hi", rpr = "<w:smallCaps/>")))
        assertEquals("uppercase", paras(sc)[0].spans[0].textTransform)
    }

    @Test fun highlightAndShadeBackground() {
        val doc = docx("word/document.xml" to body(para("h", rpr = "<w:highlight w:val=\"yellow\"/>")))
        assertEquals(0xFFFFFF00L, paras(doc)[0].spans[0].backgroundColor)
        val shd = docx("word/document.xml" to body(para("s", rpr = "<w:shd w:fill=\"FF0000\"/>")))
        assertEquals(0xFFFF0000L, paras(shd)[0].spans[0].backgroundColor)
    }

    @Test fun subscriptTabsBreaksHyphens() {
        val sub = docx("word/document.xml" to body(
            para("a", rpr = "<w:vertAlign w:val=\"subscript\"/>")))
        assertTrue(paras(sub)[0].spans[0].subscript)
        // Build the run properly (avoids hand-nesting mistakes): tab, break, hyphens, symbol.
        val doc2 = docx("word/document.xml" to body(
            "<w:p><w:r><w:t>a</w:t></w:r><w:r><w:tab/></w:r><w:r><w:br/></w:r>" +
                "<w:r><w:noBreakHyphen/><w:softHyphen/><w:sym w:char=\"F02D\"/></w:r></w:p>"))
        val text = paras(doc2)[0].spans.joinToString("") { it.text }
        assertTrue(text.contains("\t"))
        assertTrue(text.contains("\n"))
        assertTrue(text.contains("‑"))
        assertTrue(text.contains("­"))
    }

    @Test fun symPrintableFallback() {
        val doc = docx("word/document.xml" to body("<w:p><w:r><w:sym w:char=\"0041\"/></w:r></w:p>"))
        assertEquals("A", paras(doc)[0].spans[0].text)
    }

    // ---- Paragraph layout ----

    @Test fun paragraphAlignmentAndDirection() {
        val doc = docx("word/document.xml" to body(
            para("c", ppr = "<w:jc w:val=\"center\"/><w:bidi/>"),
            para("r", ppr = "<w:jc w:val=\"right\"/>"),
            para("j", ppr = "<w:jc w:val=\"both\"/>")))
        val ps = paras(doc)
        assertEquals(androidx.compose.ui.text.style.TextAlign.Center, ps[0].alignment)
        assertEquals(androidx.compose.ui.unit.LayoutDirection.Rtl, ps[0].direction)
        assertEquals(androidx.compose.ui.text.style.TextAlign.End, ps[1].alignment)
        assertEquals(androidx.compose.ui.text.style.TextAlign.Justify, ps[2].alignment)
    }

    @Test fun paragraphMarginsAndLineHeight() {
        val doc = docx("word/document.xml" to body(
            para("m", ppr = "<w:ind w:left=\"1440\" w:right=\"720\" w:firstLine=\"360\"/>" +
                "<w:spacing w:before=\"240\" w:after=\"120\" w:line=\"360\" w:lineRule=\"auto\"/>")))
        val p = paras(doc)[0]
        assertEquals(96f, p.marginLeft, 0.01f)
        assertEquals(48f, p.marginRight, 0.01f)
        assertEquals(24f, p.textIndent, 0.01f)
        assertEquals(16f, p.marginTop, 0.01f)
        assertEquals(8f, p.marginBottom, 0.01f)
        assertEquals(1.5f, p.lineHeightPercent ?: 0f, 0.001f)
    }

    @Test fun exactLineRuleGivesNoMultiplier() {
        val doc = docx("word/document.xml" to body(
            para("e", ppr = "<w:spacing w:line=\"480\" w:lineRule=\"exact\"/>")))
        assertNull(paras(doc)[0].lineHeightPercent)
    }

    @Test fun keepAndBreakFlags() {
        val doc = docx("word/document.xml" to body(
            para("k", ppr = "<w:keepNext/><w:keepLines/><w:widowControl w:val=\"0\"/>" +
                "<w:pageBreakBefore w:val=\"1\"/>")))
        val p = paras(doc)[0]
        assertTrue(p.keepWithNext)
        assertTrue(p.keepTogether)
        assertTrue(p.breakBeforePage)
        assertNull(p.widows)
    }

    @Test fun shadingBordersTabsDropCap() {
        val doc = docx("word/document.xml" to body(
            para("s", ppr = "<w:shd w:fill=\"00FF00\"/>" +
                "<w:pBdr><w:top w:val=\"single\" w:sz=\"8\" w:color=\"FF0000\"/></w:pBdr>" +
                "<w:tabs><w:tab w:val=\"center\" w:pos=\"1440\" w:leader=\"dot\"/>" +
                "<w:tab w:val=\"clear\" w:pos=\"100\"/></w:tabs>" +
                "<w:framePr w:dropCap=\"drop\" w:lines=\"2\"/>")))
        val p = paras(doc)[0]
        assertEquals(0xFF00FF00L, p.backgroundColor)
        assertNotNull(p.borders)
        assertEquals(0xFFFF0000L, p.borderColor)
        assertEquals(1, p.tabStops.size)
        assertEquals(96f, p.tabStops[0], 0.01f)
        assertEquals("center", p.tabStopDetails[0].type)
        assertEquals(".", p.tabStopDetails[0].leaderChar)
        assertEquals(2, p.dropCapLines)
    }

    @Test fun outlineLevelMapsToHeading() {
        val doc = docx("word/document.xml" to body(
            para("h", ppr = "<w:outlineLvl w:val=\"1\"/>")))
        assertEquals(ParagraphStyle.HEADING2, paras(doc)[0].style)
    }

    // ---- Styles inheritance ----

    @Test fun styleInheritanceBoldFromBasedOn() {
        val styles = "<w:styles xmlns:w=\"w\">" +
            "<w:docDefaults><w:rPrDefault><w:rPr><w:sz w:val=\"20\"/></w:rPr></w:rPrDefault></w:docDefaults>" +
            "<w:style w:styleId=\"Base\" w:type=\"paragraph\"><w:rPr><w:b/></w:rPr></w:style>" +
            "<w:style w:styleId=\"Child\" w:type=\"paragraph\"><w:basedOn w:val=\"Base\"/>" +
            "<w:rPr><w:i/></w:rPr></w:style></w:styles>"
        val doc = docx(
            "word/document.xml" to body(para("x", ppr = "<w:pStyle w:val=\"Child\"/>")),
            "word/styles.xml" to styles)
        val span = paras(doc)[0].spans[0]
        assertTrue(span.bold)
        assertTrue(span.italic)
        assertEquals(10f, span.fontSize)
    }

    @Test fun characterStyleApplies() {
        val styles = "<w:styles xmlns:w=\"w\">" +
            "<w:style w:styleId=\"Em\" w:type=\"character\"><w:rPr><w:i/><w:color w:val=\"FF0000\"/></w:rPr></w:style></w:styles>"
        val doc = docx(
            "word/document.xml" to body("<w:p><w:r><w:rPr><w:rStyle w:val=\"Em\"/></w:rPr><w:t>e</w:t></w:r></w:p>"),
            "word/styles.xml" to styles)
        val span = paras(doc)[0].spans[0]
        assertTrue(span.italic)
        assertEquals(0xFFFF0000L, span.color)
    }

    @Test fun underlineAndNumberingMaps() {
        assertEquals("solid", OoxmlDocxStyles.mapUnderline(null))
        assertEquals("none", OoxmlDocxStyles.mapUnderline("none"))
        assertEquals("double", OoxmlDocxStyles.mapUnderline("double"))
        assertEquals("dotted", OoxmlDocxStyles.mapUnderline("dottedHeavy"))
        assertEquals("dash", OoxmlDocxStyles.mapUnderline("dashLong"))
        assertEquals("wave", OoxmlDocxStyles.mapUnderline("wavyDouble"))
        assertEquals("solid", OoxmlDocxStyles.mapUnderline("weird"))
        assertEquals("a", OoxmlDocxStyles.mapNumFmt("lowerLetter"))
        assertEquals("A", OoxmlDocxStyles.mapNumFmt("upperLetter"))
        assertEquals("i", OoxmlDocxStyles.mapNumFmt("lowerRoman"))
        assertEquals("I", OoxmlDocxStyles.mapNumFmt("upperRoman"))
        assertEquals("1", OoxmlDocxStyles.mapNumFmt("decimal"))
        assertEquals("1", OoxmlDocxStyles.mapNumFmt("mystery"))
        assertEquals("•", OoxmlDocxStyles.mapBullet("\uF0B7"))
        assertEquals("◦", OoxmlDocxStyles.mapBullet("o"))
        assertEquals("•", OoxmlDocxStyles.mapBullet(""))
    }

    // ---- Fields, hyperlinks, bookmarks ----

    @Test fun pageFieldAnnotatesResult() {
        val doc = docx("word/document.xml" to body(
            "<w:p><w:r><w:fldChar w:fldCharType=\"begin\"/></w:r>" +
                "<w:r><w:instrText> PAGE </w:instrText></w:r>" +
                "<w:r><w:fldChar w:fldCharType=\"separate\"/></w:r>" +
                "<w:r><w:t>3</w:t></w:r>" +
                "<w:r><w:fldChar w:fldCharType=\"end\"/></w:r></w:p>"))
        assertEquals("page-number", paras(doc)[0].spans[0].field)
    }

    @Test fun hyperlinkFieldTarget() {
        val doc = docx("word/document.xml" to body(
            "<w:p><w:r><w:fldChar w:fldCharType=\"begin\"/></w:r>" +
                "<w:r><w:instrText> HYPERLINK \"https://x.test\" </w:instrText></w:r>" +
                "<w:r><w:fldChar w:fldCharType=\"separate\"/></w:r>" +
                "<w:r><w:t>link</w:t></w:r>" +
                "<w:r><w:fldChar w:fldCharType=\"end\"/></w:r></w:p>"))
        assertEquals("https://x.test", paras(doc)[0].spans[0].href)
    }

    @Test fun hyperlinkRelAndBookmarkAnchor() {
        val doc = docx(
            "word/document.xml" to body(
                "<w:p><w:hyperlink r:id=\"rId5\" xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\">" +
                    "<w:r><w:t>ext</w:t></w:r></w:hyperlink></w:p>" +
                    "<w:p><w:bookmarkStart w:name=\"Top\"/><w:r><w:t>b</w:t></w:r></w:p>" +
                    "<w:p><w:hyperlink w:anchor=\"Top\"><w:r><w:t>jump</w:t></w:r></w:hyperlink></w:p>"),
            "word/_rels/document.xml.rels" to (
                "<Relationships xmlns=\"urn:r\"><Relationship Id=\"rId5\" " +
                    "Type=\"http://t/hyperlink\" Target=\"https://e.test\" TargetMode=\"External\"/></Relationships>"))
        val ps = paras(doc)
        assertEquals("https://e.test", ps[0].spans[0].href)
        assertEquals(listOf("Top"), doc.bookmarks.map { it.name })
        val jump = ps[2].spans[0]
        assertEquals("#Top", jump.href)
    }

    @Test fun hyperlinkMissingRelFallsBackToAnchor() {
        // r:id with no matching relationship + w:anchor: rel lookup misses, anchor wins.
        val doc = docx(
            "word/document.xml" to (
                "<w:document xmlns:w=\"w\" xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\">" +
                    "<w:body><w:p><w:hyperlink r:id=\"missing\" w:anchor=\"Top\">" +
                    "<w:r><w:t>jump</w:t></w:r></w:hyperlink></w:p></w:body></w:document>"),
            "word/_rels/document.xml.rels" to (
                "<Relationships xmlns=\"urn:r\"><Relationship Id=\"other\" " +
                    "Type=\"http://t/hyperlink\" Target=\"https://e.test\" TargetMode=\"External\"/></Relationships>"))
        assertEquals("#Top", paras(doc)[0].spans[0].href)
    }

    @Test fun hyperlinkAnchorOnlyLinksFragment() {
        // Anchor without any r:id: href becomes the #fragment form (internal bookmark link).
        val doc = docx("word/document.xml" to body(
            "<w:p><w:hyperlink w:anchor=\"Top\">" +
                "<w:r><w:t>jump</w:t></w:r></w:hyperlink></w:p>"))
        val jump = paras(doc)[0].spans.first { it.text == "jump" }
        assertEquals("#Top", jump.href)
    }

    @Test fun sdtInlineUnwrapsRuns() {
        val doc = docx("word/document.xml" to body(
            "<w:p><w:sdt><w:sdtContent><w:r><w:t>control</w:t></w:r></w:sdtContent></w:sdt></w:p>"))
        assertEquals("control", paras(doc)[0].spans[0].text)
    }

    // ---- Sections / page setup ----

    @Test fun sectionPageSetupAndColumns() {
        val doc = docx("word/document.xml" to (
            "<w:document xmlns:w=\"w\" xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\"><w:body><w:p><w:r><w:t>x</w:t></w:r></w:p>" +
                "<w:sectPr><w:pgSz w:w=\"12240\" w:h=\"15840\" w:orient=\"portrait\"/>" +
                "<w:pgMar w:left=\"1440\" w:right=\"1440\" w:top=\"1440\" w:bottom=\"1440\"/>" +
                "<w:cols w:num=\"2\"/></w:sectPr></w:body></w:document>"))
        assertNotNull(doc.pageSetup)
        assertEquals(816f, doc.pageSetup!!.widthPx, 1f)
        val section = doc.content.filterIsInstance<OdfContentBlock.SectionStart>().firstOrNull()
        assertNotNull(section)
        assertEquals(2, section!!.columnCount)
    }

    @Test fun landscapeSwapsDimensions() {
        val doc = docx("word/document.xml" to (
            "<w:document xmlns:w=\"w\" xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\"><w:body><w:p><w:r><w:t>x</w:t></w:r></w:p>" +
                "<w:sectPr><w:pgSz w:w=\"10000\" w:h=\"20000\" w:orient=\"landscape\"/></w:sectPr></w:body></w:document>"))
        assertTrue(doc.pageSetup!!.widthPx > doc.pageSetup!!.heightPx)
    }

    // ---- Footnotes / endnotes / comments ----

    @Test fun footnoteReferenceResolves() {
        val doc = docx(
            "word/document.xml" to body(
                "<w:p><w:r><w:t>text</w:t></w:r><w:r><w:footnoteReference w:id=\"1\"/></w:r></w:p>"),
            "word/footnotes.xml" to (
                "<w:footnotes xmlns:w=\"w\"><w:footnote w:id=\"1\">" +
                    "<w:p><w:r><w:t>note body</w:t></w:r></w:p></w:footnote>" +
                    "<w:footnote w:id=\"0\" w:type=\"separator\"><w:p/></w:footnote></w:footnotes>"))
        assertEquals(1, doc.footnotes.size)
        assertEquals("1", doc.footnotes[0].citation)
        assertEquals("note body", doc.footnotes[0].body[0].spans[0].text)
        val spans = paras(doc)[0].spans
        assertTrue(spans.any { it.superscript && it.text == "1" })
    }

    @Test fun commentXmlParsesToAnnotationMap() {
        // parseComments builds author/date/body maps from comments.xml (wiring the map to
        // spans happens elsewhere); cover the parse including multi-paragraph bodies.
        val doc = docx(
            "word/document.xml" to body(para("text")),
            "word/comments.xml" to (
                "<w:comments xmlns:w=\"w\"><w:comment w:id=\"0\" w:author=\"Al\" w:date=\"2024-05-01\">" +
                    "<w:p><w:r><w:t>fix</w:t></w:r></w:p>" +
                    "<w:p><w:r><w:t>this</w:t></w:r></w:p></w:comment></w:comments>"))
        assertEquals(listOf("text"), paras(doc).map { it.spans[0].text })
    }

    // ---- Tracked changes ----

    @Test fun insertionsAndDeletionsTracked() {
        val doc = docx("word/document.xml" to body(
            "<w:p><w:ins w:author=\"Al\" w:date=\"2024-01-01\"><w:r><w:t>new</w:t></w:r></w:ins>" +
                "<w:del w:author=\"Bo\"><w:r><w:t>old</w:t></w:r></w:del></w:p>"))
        assertEquals(2, doc.changes.size)
        val kinds = doc.changes.map { it.type }.toSet()
        assertEquals(setOf("insertion", "deletion"), kinds)
        val spans = paras(doc)[0].spans
        assertEquals("insertion", spans.first { it.text == "new" }.changeKind)
        assertEquals("deletion", spans.first { it.text == "old" }.changeKind)
        assertEquals("Al", doc.changes.first { it.type == "insertion" }.author)
    }

    // ---- Headers / footers ----

    @Test fun headerFooterImport() {
        val doc = docx(
            "word/document.xml" to body(para("body")),
            "word/_rels/document.xml.rels" to (
                "<Relationships xmlns=\"urn:r\">" +
                    "<Relationship Id=\"h1\" Type=\"http://t/header\" Target=\"header1.xml\"/>" +
                    "<Relationship Id=\"f1\" Type=\"http://t/footer\" Target=\"footer1.xml\"/></Relationships>"),
            "word/header1.xml" to body(para("head text")),
            "word/footer1.xml" to body(para("foot text")))
        assertEquals("head text", doc.headerParagraphs[0].spans[0].text)
        assertEquals("foot text", doc.footerParagraphs[0].spans[0].text)
    }

    // ---- Tables ----

    @Test fun tableWithMergesAndHeader() {
        val doc = docx("word/document.xml" to (
            "<w:document xmlns:w=\"w\" xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\"><w:body><w:tbl>" +
                "<w:tblGrid><w:gridCol w:w=\"2000\"/><w:gridCol w:w=\"2000\"/></w:tblGrid>" +
                "<w:tblPr><w:tblBorders><w:top w:val=\"single\" w:sz=\"4\" w:color=\"000000\"/></w:tblBorders></w:tblPr>" +
                "<w:tr><w:trPr><w:tblHeader/></w:trPr>" +
                "<w:tc><w:tcPr><w:gridSpan w:val=\"2\"/><w:shd w:fill=\"FFFF00\"/></w:tcPr>" +
                "<w:p><w:r><w:t>wide</w:t></w:r></w:p></w:tc></w:tr>" +
                "<w:tr><w:tc><w:tcPr><w:vMerge w:val=\"restart\"/><w:vAlign w:val=\"center\"/></w:tcPr>" +
                "<w:p><w:r><w:t>tall</w:t></w:r></w:p></w:tc>" +
                "<w:tc><w:p><w:r><w:t>r</w:t></w:r></w:p></w:tc></w:tr>" +
                "<w:tr><w:tc><w:tcPr><w:vMerge/></w:tcPr><w:p/></w:tc>" +
                "<w:tc><w:p><w:r><w:t>r2</w:t></w:r></w:p></w:tc></w:tr>" +
                "</w:tbl></w:body></w:document>"))
        val table = doc.content.filterIsInstance<OdfContentBlock.Table>()[0].table
        assertEquals(1, table.headerRowCount)
        assertEquals(2, table.rows[0].cells[0].colSpan)
        assertTrue(table.rows[0].cells[1].isCovered)
        assertEquals(0xFFFFFF00L, table.rows[0].cells[0].backgroundColor)
        assertEquals(2, table.rows[1].cells[0].rowSpan)
        assertTrue(table.rows[2].cells[0].isCovered)
        assertEquals("middle", table.rows[1].cells[0].verticalAlign)
        assertEquals(2, table.columns.size)
        assertNotNull(table.rows[1].cells[1].borders)
    }

    @Test fun nestedTableFlattens() {
        val doc = docx("word/document.xml" to (
            "<w:document xmlns:w=\"w\" xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\"><w:body><w:tbl><w:tr><w:tc>" +
                "<w:p><w:r><w:t>outer</w:t></w:r></w:p>" +
                "<w:tbl><w:tr><w:tc><w:p><w:r><w:t>inner</w:t></w:r></w:p></w:tc></w:tr></w:tbl>" +
                "</w:tc></w:tr></w:tbl></w:body></w:document>"))
        val table = doc.content.filterIsInstance<OdfContentBlock.Table>()[0].table
        val texts = table.rows[0].cells[0].paragraphs.map { it.spans[0].text }
        assertTrue("outer" in texts)
        assertTrue("inner" in texts)
    }

    @Test fun tableCellBorderColorAndEmptyCell() {
        // tcBorders without top/left falls back to the first edge color; a paragraph-less
        // cell still yields one empty paragraph (covers toModel's empty/border branches).
        val doc = docx("word/document.xml" to (
            "<w:document xmlns:w=\"w\" xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\"><w:body><w:tbl>" +
                "<w:tr><w:tc><w:tcPr><w:tcBorders>" +
                "<w:top w:val=\"single\" w:sz=\"4\" w:color=\"FF0000\"/>" +
                "</w:tcBorders></w:tcPr><w:p><w:r><w:t>c</w:t></w:r></w:p></w:tc>" +
                "<w:tc><w:tcPr/></w:tc></w:tr>" +
                "</w:tbl></w:body></w:document>"))
        val row = doc.content.filterIsInstance<OdfContentBlock.Table>()[0].table.rows[0]
        assertEquals(0xFFFF0000L, row.cells[0].borderColor)
        assertNotNull(row.cells[0].borders)
        assertEquals(1, row.cells[1].paragraphs.size)
        assertEquals("", row.cells[1].paragraphs[0].spans[0].text)
    }

    // ---- Drawings: image, chart, textbox, smartart ----

    @Test fun inlineImageFromDrawing() {
        val png = java.util.Base64.getDecoder().decode("iVBORw0KGgo=")
        val bos = java.io.ByteArrayOutputStream()
        java.util.zip.ZipOutputStream(bos).use { zos ->
            zos.putNextEntry(java.util.zip.ZipEntry("word/document.xml"))
            zos.write(body(
                "<w:p><w:r><w:drawing><wp:inline xmlns:wp=\"wp\">" +
                    "<wp:extent cx=\"914400\" cy=\"457200\"/>" +
                    "<wp:docPr name=\"pic\" descr=\"a pic\"/>" +
                    "<a:graphic xmlns:a=\"a\"><a:graphicData>" +
                    "<pic:pic xmlns:pic=\"pic\"><pic:blipFill><a:blip r:embed=\"rId6\" " +
                    "xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\"/>" +
                    "</pic:blipFill></pic:pic></a:graphicData></a:graphic></wp:inline></w:drawing></w:r></w:p>"
            ).toByteArray(Charsets.UTF_8))
            zos.closeEntry()
            zos.putNextEntry(java.util.zip.ZipEntry("word/media/p.png"))
            zos.write(png)
            zos.closeEntry()
            zos.putNextEntry(java.util.zip.ZipEntry("word/_rels/document.xml.rels"))
            zos.write(("<Relationships xmlns=\"urn:r\"><Relationship Id=\"rId6\" Type=\"http://t/image\" " +
                "Target=\"media/p.png\"/></Relationships>").toByteArray(Charsets.UTF_8))
            zos.closeEntry()
        }
        val doc = OoxmlImporter.import(bos.toByteArray(), "img.docx") as OdfDocument.TextDocument
        val img = doc.content.filterIsInstance<OdfContentBlock.Image>().firstOrNull()
        assertNotNull(img)
        assertEquals(96f, img!!.image.width, 0.5f)
        assertEquals("a pic", img.image.altDesc)
        assertTrue(doc.images.containsKey(img.image.path))
    }

    @Test fun drawingChartBecomesBlock() {
        val doc = docx(
            "word/document.xml" to body(
                "<w:p><w:r><w:drawing><wp:inline xmlns:wp=\"wp\"><wp:extent cx=\"100\" cy=\"100\"/>" +
                    "<a:graphic xmlns:a=\"a\"><a:graphicData>" +
                    "<c:chart xmlns:c=\"c\" xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\" r:id=\"rId9\"/>" +
                    "</a:graphicData></a:graphic></wp:inline></w:drawing></w:r></w:p>"),
            "word/_rels/document.xml.rels" to (
                "<Relationships xmlns=\"urn:r\"><Relationship Id=\"rId9\" Type=\"http://t/chart\" Target=\"charts/c1.xml\"/></Relationships>"),
            "word/charts/c1.xml" to (
                "<chartSpace><chart><plotArea><barChart>" +
                    "<ser><tx><v>S</v></tx>" +
                    "<val><numRef><numCache><pt idx=\"0\"><v>1</v></pt></numCache></numRef></val></ser>" +
                    "</barChart></plotArea></chart></chartSpace>"))
        assertEquals(1, doc.content.filterIsInstance<OdfContentBlock.Chart>().size)
    }

    @Test fun textboxContentSurfaces() {
        val doc = docx("word/document.xml" to body(
            "<w:p><w:r><w:drawing><wp:anchor xmlns:wp=\"wp\">" +
                "<w:txbxContent xmlns:w=\"w\"><w:p><w:r><w:t>boxed</w:t></w:r></w:p></w:txbxContent>" +
                "</wp:anchor></w:drawing></w:r></w:p>"))
        assertTrue(paras(doc).any { it.spans.any { s -> s.text == "boxed" } })
    }

    @Test fun drawingCropAndMetaMapToImage() {
        // srcRect crop percentages + docPr title/descr + xfrm rotation land on the image model.
        val png = java.util.Base64.getDecoder().decode("iVBORw0KGgo=")
        val bos = java.io.ByteArrayOutputStream()
        java.util.zip.ZipOutputStream(bos).use { zos ->
            zos.putNextEntry(java.util.zip.ZipEntry("word/document.xml"))
            zos.write(body(
                "<w:p><w:r><w:drawing><wp:inline xmlns:wp=\"wp\">" +
                    "<wp:extent cx=\"914400\" cy=\"914400\"/>" +
                    "<wp:docPr name=\"titled\" descr=\"described\"/>" +
                    "<a:graphic xmlns:a=\"a\"><a:graphicData>" +
                    "<pic:pic xmlns:pic=\"pic\"><pic:blipFill>" +
                    "<a:blip r:embed=\"rId7\" " +
                    "xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\">" +
                    "<a:srcRect l=\"10000\" t=\"20000\" r=\"5000\" b=\"0\"/></a:blip>" +
                    "</pic:blipFill></pic:pic></a:graphicData></a:graphic></wp:inline>" +
                    "</w:drawing></w:r></w:p>"
            ).toByteArray(Charsets.UTF_8))
            zos.closeEntry()
            zos.putNextEntry(java.util.zip.ZipEntry("word/media/q.png"))
            zos.write(png)
            zos.closeEntry()
            zos.putNextEntry(java.util.zip.ZipEntry("word/_rels/document.xml.rels"))
            zos.write(("<Relationships xmlns=\"urn:r\"><Relationship Id=\"rId7\" Type=\"http://t/image\" " +
                "Target=\"media/q.png\"/></Relationships>").toByteArray(Charsets.UTF_8))
            zos.closeEntry()
        }
        val doc = OoxmlImporter.import(bos.toByteArray(), "crop.docx") as OdfDocument.TextDocument
        val img = doc.content.filterIsInstance<OdfContentBlock.Image>().firstOrNull()
        assertNotNull(img)
        assertEquals(0.1f, img!!.image.cropLeftPct, 0.001f)
        assertEquals(0.2f, img.image.cropTopPct, 0.001f)
        assertEquals(0.05f, img.image.cropRightPct, 0.001f)
        assertEquals(0f, img.image.cropBottomPct, 0.001f)
        assertEquals("titled", img.image.altTitle)
        assertEquals("described", img.image.altDesc)
    }

    @Test fun smartArtTextSurfacesWithoutVisual() {
        // A drawing with only a diagram relId falls back to SmartArt text extraction.
        val doc = docx(
            "word/document.xml" to body(
                "<w:p><w:r><w:drawing><wp:inline xmlns:wp=\"wp\">" +
                    "<a:graphic xmlns:a=\"a\"><a:graphicData>" +
                    "<dgm:relIds xmlns:dgm=\"d\" " +
                    "xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\" r:dm=\"rId8\"/>" +
                    "</a:graphicData></a:graphic></wp:inline></w:drawing></w:r></w:p>"),
            "word/_rels/document.xml.rels" to (
                "<Relationships xmlns=\"urn:r\"><Relationship Id=\"rId8\" " +
                    "Type=\"http://t/diagramData\" Target=\"diagrams/data1.xml\"/></Relationships>"),
            "word/diagrams/data1.xml" to (
                "<dgm:dataModel xmlns:dgm=\"d\" xmlns:a=\"http://schemas.openxmlformats.org/drawingml/2006/main\">" +
                    "<dgm:pt><dgm:prSet/><a:p><a:t>smart</a:t></a:p></dgm:pt>" +
                    "<dgm:pt><dgm:prSet/><a:p><a:t>art</a:t></a:p></dgm:pt>" +
                    "</dgm:dataModel>"))
        assertTrue(paras(doc).any { it.spans.any { s -> s.text == "smart" } })
        assertTrue(paras(doc).any { it.spans.any { s -> s.text == "art" } })
    }

    // ---- Numbering edge: bullet + start override ----

    @Test fun bulletListAndCustomStart() {
        val doc = docx(
            "word/document.xml" to body(
                "<w:p><w:pPr><w:numPr><w:ilvl w:val=\"0\"/><w:numId w:val=\"5\"/></w:numPr></w:pPr>" +
                    "<w:r><w:t>dot</w:t></w:r></w:p>"),
            "word/numbering.xml" to (
                "<w:numbering xmlns:w=\"w\"><w:abstractNum w:abstractNumId=\"7\">" +
                    "<w:lvl w:ilvl=\"0\"><w:start w:val=\"3\"/><w:numFmt w:val=\"bullet\"/>" +
                    "<w:lvlText w:val=\"\uF0B7\"/></w:lvl></w:abstractNum>" +
                    "<w:num w:numId=\"5\"><w:abstractNumId w:val=\"7\"/></w:num></w:numbering>"))
        val p = paras(doc)[0]
        assertEquals(ListType.BULLET, p.listType)
        assertEquals("•", p.listBulletChar)
    }

    @Test fun themeTintColorResolves() {
        val doc = docx(
            "word/document.xml" to body(para("t", rpr = "<w:color w:themeColor=\"accent1\"/>")),
            "word/theme/theme1.xml" to (
                "<theme><themeElements><clrScheme name=\"X\">" +
                    "<accent1><srgbClr val=\"4472C4\"/></accent1></clrScheme>" +
                    "</themeElements></theme>"))
        assertEquals(0xFF4472C4L, paras(doc)[0].spans[0].color)
    }

    @Test fun emptyDocWithoutPartGivesEmpty() {
        val doc = docx("other.xml" to "<x/>")
        assertTrue(doc.content.isEmpty())
    }

    @Test fun omathParaBecomesFormula() {
        val doc = docx("word/document.xml" to (
            "<w:document xmlns:w=\"w\" xmlns:m=\"m\" xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\"><w:body>" +
                "<m:oMathPara><m:oMath><m:r><m:t>x</m:t></m:r></m:oMath></m:oMathPara>" +
                "</w:body></w:document>"))
        val f = doc.content.filterIsInstance<OdfContentBlock.Formula>().firstOrNull()
        assertNotNull(f)
        assertTrue(f!!.mathml.contains("<math"))
    }
}
