package com.vayunmathur.office.odf

import com.vayunmathur.library.ui.odf.OdfContentBlock
import com.vayunmathur.library.ui.odf.OdfDocument
import com.vayunmathur.library.ui.odf.OdfImage
import com.vayunmathur.library.ui.odf.OdfSpan
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.xmlpull.v1.XmlPullParser

/**
 * Slice 2/4 (OdfParser*): inline content — spans, links, whitespace elements,
 * footnotes, annotations, fields, cross-references and inline objects/images.
 */
class OdfParser2InlineTest {

    private val head =
        """<?xml version="1.0" encoding="UTF-8"?>""" +
            """<office:document xmlns:office="urn:oasis:names:tc:opendocument:xmlns:office:1.0" """ +
            """xmlns:text="urn:oasis:names:tc:opendocument:xmlns:text:1.0" """ +
            """xmlns:style="urn:oasis:names:tc:opendocument:xmlns:style:1.0" """ +
            """xmlns:fo="urn:oasis:names:tc:opendocument:xmlns:xsl-fo-compatible:1.0" """ +
            """xmlns:draw="urn:oasis:names:tc:opendocument:xmlns:drawing:1.0" """ +
            """xmlns:svg="urn:oasis:names:tc:opendocument:xmlns:svg-compatible:1.0" """ +
            """xmlns:xlink="http://www.w3.org/1999/xlink" """ +
            """xmlns:dc="http://purl.org/dc/elements/1.1/">"""

    private fun doc(body: String): OdfDocument.TextDocument = OdfParser.parseTextXml(
        head +
            "<office:automatic-styles/>" +
            "<office:body><office:text>$body</office:text></office:body></office:document>"
    )

    private fun paraSpans(body: String) =
        doc(body).content.mapNotNull { (it as? OdfContentBlock.Paragraph)?.paragraph }.single().spans

    /** Runs [OdfParser.parseInlineContent] over a bare `<text:p>` fragment. */
    private fun inlineSpans(
        fragment: String,
        styles: Map<String, StyleInfo> = emptyMap(),
        images: Map<String, ByteArray> = emptyMap(),
        imagesOut: MutableList<OdfImage>? = null,
        objectContents: Map<String, String> = emptyMap(),
        formulasOut: MutableList<String>? = null,
    ): List<OdfSpan> {
        val parser = OdfParser.newParser(
            """<w xmlns:text="urn:oasis:names:tc:opendocument:xmlns:text:1.0" """ +
                """xmlns:draw="urn:oasis:names:tc:opendocument:xmlns:drawing:1.0" """ +
                """xmlns:fo="urn:oasis:names:tc:opendocument:xmlns:xsl-fo-compatible:1.0" """ +
                """xmlns:xlink="http://www.w3.org/1999/xlink" """ +
                """xmlns:svg="urn:oasis:names:tc:opendocument:xmlns:svg-compatible:1.0">""" +
                fragment + "</w>"
        )
        var e = parser.eventType
        while (!(e == XmlPullParser.START_TAG && parser.name == "p")) e = parser.next()
        return OdfParser.parseInlineContent(parser, "p", styles, images, null, imagesOut, objectContents, null, formulasOut)
    }

    // --- Basic spans / links / whitespace ---

    @Test
    fun styled_span_and_link() {
        val stylesXml =
            """<style:style xmlns:style="urn:oasis:names:tc:opendocument:xmlns:style:1.0" """ +
                """xmlns:fo="urn:oasis:names:tc:opendocument:xmlns:xsl-fo-compatible:1.0" """ +
                """style:name="B" style:family="text">""" +
                """<style:text-properties fo:font-weight="bold" fo:font-style="italic"/>""" +
                """</style:style>"""
        val styles = OdfParser.parseStyles(stylesXml)
        val spans = inlineSpans(
            """<text:p>plain<text:span text:style-name="B">bold</text:span>""" +
                """<text:a xlink:href="https://example.com">click</text:a>tail</text:p>""",
            styles
        )
        assertEquals("plain", spans[0].text)
        assertTrue(spans[1].bold && spans[1].italic)
        assertEquals("click", spans[2].text)
        assertEquals("https://example.com", spans[2].href)
        assertTrue(spans[2].underline)
        assertEquals(OdfParser.LINK_COLOR, spans[2].color)
        assertEquals("tail", spans[3].text)
    }

    @Test
    fun whitespace_elements_expand() {
        val spans = inlineSpans("""<text:p>a<text:s text:c="3"/>b<text:tab/>c<text:line-break/>d</text:p>""")
        assertEquals("a   b\tc\nd", spans.single().text)
    }

    @Test
    fun make_span_link_color_fallback_only_without_style_color() {
        val stylesXml =
            """<style:style xmlns:style="urn:oasis:names:tc:opendocument:xmlns:style:1.0" """ +
                """xmlns:fo="urn:oasis:names:tc:opendocument:xmlns:xsl-fo-compatible:1.0" """ +
                """style:name="R" style:family="text">""" +
                """<style:text-properties fo:color="#ff0000"/>""" +
                """</style:style>"""
        val styles = OdfParser.parseStyles(stylesXml)
        val linked = OdfParser.makeSpan("x", "R", styles, "https://e.com")
        assertEquals("https://e.com", linked.href)
        assertEquals(0xFFFF0000L, linked.color)
        assertTrue(linked.underline)
        val unstyled = OdfParser.makeSpan("y", null, styles, "https://e.com")
        assertEquals(OdfParser.LINK_COLOR, unstyled.color)
    }

    // --- Footnotes / annotations ---

    @Test
    fun footnote_citation_span_and_body() {
        val d = doc(
            """<text:p>text<text:note text:note-class="footnote">""" +
                """<text:note-citation>1</text:note-citation>""" +
                """<text:note-body><text:p>fn body</text:p></text:note-body>""" +
                """</text:note>after</text:p>"""
        )
        val spans = d.content.mapNotNull { (it as? OdfContentBlock.Paragraph)?.paragraph }.single().spans
        assertEquals(listOf("text", "1", "after"), spans.map { it.text })
        assertTrue(spans[1].superscript)
        assertEquals(1, d.footnotes.size)
        assertEquals("1", d.footnotes.single().citation)
        assertEquals("fn body", d.footnotes.single().body.single().spans.single().text)
    }

    @Test
    fun endnote_flag_and_missing_citation() {
        val d = doc(
            """<text:p>a<text:note text:note-class="endnote">""" +
                """<text:note-citation>i</text:note-citation><text:note-body><text:p>b</text:p></text:note-body>""" +
                """</text:note><text:note><text:note-body><text:p>no citation</text:p></text:note-body></text:note></text:p>"""
        )
        assertTrue(d.footnotes.single().isEndnote)
        // The citation-less note produces no span.
        assertEquals(listOf("a", "i"), d.content
            .mapNotNull { (it as? OdfContentBlock.Paragraph)?.paragraph }.single().spans.map { it.text })
    }

    @Test
    fun annotation_span_carries_author_and_comment() {
        val spans = paraSpans(
            """<text:p>hi<office:annotation><dc:creator>Bob</dc:creator><dc:date>2024-05-01</dc:date>""" +
                """<text:p>comment!</text:p></office:annotation></text:p>"""
        )
        assertEquals("hi", spans[0].text)
        assertEquals(" 📝 ", spans[1].text)
        val ann = assertNotNull(spans[1].annotation)
        assertEquals("Bob", ann.author)
        assertEquals("2024-05-01", ann.date)
        assertEquals("comment!", ann.paragraphs.single().spans.single().text)
    }

    // --- Fields / references / bookmarks ---

    @Test
    fun text_fields_become_field_spans() {
        val spans = paraSpans(
            """<text:p><text:date>2024-01-02</text:date> <text:page-number>3</text:page-number> """ +
                """<text:page-count>10</text:page-count> <text:title>My Doc</text:title></text:p>"""
        )
        assertEquals(listOf("2024-01-02", " ", "3", " ", "10", " ", "My Doc"), spans.map { it.text })
        assertEquals("date", spans[0].field)
        assertEquals("page-number", spans[2].field)
        assertEquals("page-count", spans[4].field)
        assertEquals("title", spans[6].field)
        assertNull(spans[1].field)
    }

    @Test
    fun reference_ref_span_has_target_and_format() {
        val spans = paraSpans(
            """<text:p>see <text:reference-ref text:ref-name="R1" text:reference-format="page">12</text:reference-ref></text:p>"""
        )
        val ref = spans.single { it.refKind == "reference-ref" }
        assertEquals("12", ref.text)
        assertEquals("R1", ref.refName)
        assertEquals("page", ref.refFormat)
        assertEquals(OdfParser.LINK_COLOR, ref.color)
    }

    @Test
    fun bookmark_marks_produce_zero_width_spans() {
        val spans = paraSpans(
            """<text:p>a<text:bookmark text:name="B1"/><text:reference-mark text:name="M1"/>b</text:p>"""
        )
        assertEquals("", spans[1].text)
        assertEquals("bookmark", spans[1].refKind)
        assertEquals("B1", spans[1].refName)
        assertEquals("reference-mark", spans[2].refKind)
        assertEquals("M1", spans[2].refName)
    }

    // --- Inline objects ---

    @Test
    fun inline_object_branches() {
        val formulas = mutableListOf<String>()
        inlineSpans(
            """<text:p>x<draw:object xlink:href="./Object 1"/><draw:object xlink:href="./Object 2"/>""" +
                """<draw:object xlink:href="./Object 3"/><draw:object xlink:href="./Object 4"/>""" +
                """<draw:object xlink:href="./Missing"/>y</text:p>""",
            objectContents = mapOf(
                "Object 1/content.xml" to "<math xmlns:m=\"urn:math\"><m:mi>x</m:mi></math>",
                "Object 2/content.xml" to "<office:spreadsheet xmlns:office=\"urn:oasis:names:tc:opendocument:xmlns:office:1.0\"/>",
                "Object 3/content.xml" to "<office:text xmlns:office=\"urn:oasis:names:tc:opendocument:xmlns:office:1.0\"/>",
                "Object 4/content.xml" to "<blob/>"
            ),
            formulasOut = formulas
        )
        assertEquals(
            listOf(
                "<math xmlns:m=\"urn:math\"><m:mi>x</m:mi></math>",
                "📊 [Embedded spreadsheet]",
                "📄 [Embedded document]",
                "📦 [Embedded object]",
                "📦 [Embedded object]"
            ),
            formulas
        )
    }

    @Test
    fun inline_object_chart_branch() {
        val chartXml =
            """<office:document xmlns:office="urn:oasis:names:tc:opendocument:xmlns:office:1.0" """ +
                """xmlns:chart="urn:oasis:names:tc:opendocument:xmlns:chart:1.0" """ +
                """xmlns:table="urn:oasis:names:tc:opendocument:xmlns:table:1.0" """ +
                """xmlns:text="urn:oasis:names:tc:opendocument:xmlns:text:1.0" """ +
                """xmlns:office2="urn:oasis:names:tc:opendocument:xmlns:office:1.0">""" +
                """<office:body><office:text>""" +
                """<chart:chart chart:class="chart:line"><chart:title><text:p>T</text:p></chart:title>""" +
                """<table:table table:name="local-table">""" +
                """<table:table-row><table:table-cell><text:p></text:p></table:table-cell>""" +
                """<table:table-cell><text:p>S</text:p></table:table-cell></table:table-row>""" +
                """<table:table-row><table:table-cell><text:p>A</text:p></table:table-cell>""" +
                """<table:table-cell table:value="5"><text:p>5</text:p></table:table-cell></table:table-row>""" +
                """</table:table></chart:chart></office:text></office:body></office:document>"""
        val charts = mutableListOf<com.vayunmathur.library.ui.odf.OdfChart>()
        val parser = OdfParser.newParser(
            """<w xmlns:text="urn:t" xmlns:draw="urn:oasis:names:tc:opendocument:xmlns:drawing:1.0" """ +
                """xmlns:xlink="http://www.w3.org/1999/xlink">""" +
                """<text:p><draw:object xlink:href="./Object 9"/></text:p></w>"""
        )
        var e = parser.eventType
        while (!(e == XmlPullParser.START_TAG && parser.name == "p")) e = parser.next()
        OdfParser.parseInlineContent(parser, "p", emptyMap(), emptyMap(), null, null,
            mapOf("Object 9/content.xml" to chartXml), charts, null)
        assertEquals(1, charts.size)
        assertEquals("S", charts.single().series.single().name)
    }

    // --- Inline images (packaged path; bitmap decode is stubbed on JVM) ---

    @Test
    fun packaged_inline_image_with_clip_and_svg_alt_text() {
        val imagesOut = mutableListOf<OdfImage>()
        val spans = inlineSpans(
            """<text:p>t<draw:frame draw:name="f" svg:width="2cm" svg:height="1cm" fo:clip="rect(10% 0% 0% 0%)">""" +
                """<draw:image xlink:href="Pictures/a.png"/>""" +
                """<svg:title>Alt title</svg:title><svg:desc>Alt desc</svg:desc>""" +
                """</draw:frame></text:p>""",
            images = mapOf("Pictures/a.png" to byteArrayOf(1, 2, 3)),
            imagesOut = imagesOut
        )
        assertEquals("t", spans.single().text)
        val img = imagesOut.single()
        assertEquals("Pictures/a.png", img.path)
        assertEquals(2f * 37.8f, img.width, 0.01f)
        assertEquals(0.1f, img.cropTopPct, 0.0001f)
        assertEquals("Alt title", img.altTitle)
        assertEquals("Alt desc", img.altDesc)
    }

    @Test
    fun unknown_image_href_emits_nothing() {
        val imagesOut = mutableListOf<OdfImage>()
        val spans = inlineSpans(
            """<text:p>t<draw:frame draw:name="f"><draw:image xlink:href="Pictures/nope.png"/>""" +
                """</draw:frame></text:p>""",
            imagesOut = imagesOut
        )
        assertEquals("t", spans.single().text)
        assertTrue(imagesOut.isEmpty())
    }

    @Test
    fun svg_alt_text_without_image_is_dropped() {
        val imagesOut = mutableListOf<OdfImage>()
        val spans = inlineSpans(
            """<text:p>t<svg:title>Orphan</svg:title></text:p>""",
            imagesOut = imagesOut
        )
        assertEquals("t", spans.single().text)
    }
}
