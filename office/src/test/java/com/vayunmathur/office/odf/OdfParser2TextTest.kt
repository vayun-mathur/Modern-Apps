package com.vayunmathur.office.odf

import androidx.compose.ui.text.style.TextAlign
import com.vayunmathur.library.ui.odf.ListType
import com.vayunmathur.library.ui.odf.OdfBookmark
import com.vayunmathur.library.ui.odf.OdfContentBlock
import com.vayunmathur.library.ui.odf.OdfDocument
import com.vayunmathur.library.ui.odf.OdfTableCell
import com.vayunmathur.library.ui.odf.ParagraphStyle
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Slice 2/4 (OdfParser*): text-document body parsing — headings, paragraphs, lists,
 * tables, sections, TOC, bookmarks, page breaks and tracked changes.
 */
class OdfParser2TextTest {

    private val head =
        """<?xml version="1.0" encoding="UTF-8"?>""" +
            """<office:document xmlns:office="urn:oasis:names:tc:opendocument:xmlns:office:1.0" """ +
            """xmlns:text="urn:oasis:names:tc:opendocument:xmlns:text:1.0" """ +
            """xmlns:table="urn:oasis:names:tc:opendocument:xmlns:table:1.0" """ +
            """xmlns:style="urn:oasis:names:tc:opendocument:xmlns:style:1.0" """ +
            """xmlns:fo="urn:oasis:names:tc:opendocument:xmlns:xsl-fo-compatible:1.0" """ +
            """xmlns:draw="urn:oasis:names:tc:opendocument:xmlns:drawing:1.0" """ +
            """xmlns:dc="http://purl.org/dc/elements/1.1/">"""

    private val numListStyle =
        """<text:list-style style:name="L1">""" +
            """<text:list-level-style-number text:level="1" text:num-format="1" text:num-suffix="."/>""" +
            """</text:list-style>"""

    private val bulletListStyle =
        """<text:list-style style:name="LB">""" +
            """<text:list-level-style-bullet text:level="1" text:bullet-char="▪"/>""" +
            """</text:list-style>"""

    private fun doc(body: String, autoStyles: String = numListStyle): OdfDocument.TextDocument =
        OdfParser.parseTextXml(
            head +
                "<office:automatic-styles>$autoStyles</office:automatic-styles>" +
                "<office:body><office:text>$body</office:text></office:body></office:document>"
        )

    private fun paragraphs(d: OdfDocument.TextDocument) =
        d.content.mapNotNull { (it as? OdfContentBlock.Paragraph)?.paragraph }

    // --- Headings ---

    @Test
    fun heading_levels_map_to_styles() {
        val d = doc(
            """<text:h text:outline-level="1">One</text:h>""" +
                """<text:h text:outline-level="2">Two</text:h>""" +
                """<text:h text:outline-level="3">Three</text:h>""" +
                """<text:h text:outline-level="9">Nine</text:h>"""
        )
        val styles = paragraphs(d).map { it.style }
        assertEquals(
            listOf(ParagraphStyle.HEADING1, ParagraphStyle.HEADING2, ParagraphStyle.HEADING3, ParagraphStyle.HEADING4),
            styles
        )
        assertEquals(listOf("One", "Two", "Three", "Nine"), paragraphs(d).map { it.spans.single().text })
    }

    @Test
    fun heading_defaults_to_level_one_and_resolves_alignment() {
        val stylesXml =
            """<style:style style:name="HC" style:family="paragraph">""" +
                """<style:paragraph-properties fo:text-align="center"/>""" +
                """</style:style>"""
        val d = OdfParser.parseTextXml(
            head +
                "<office:automatic-styles>$stylesXml</office:automatic-styles>" +
                """<office:body><office:text><text:h text:style-name="HC">T</text:h></office:text></office:body></office:document>"""
        )
        val h = paragraphs(d).single()
        assertEquals(ParagraphStyle.HEADING1, h.style)
        assertEquals(TextAlign.Center, h.alignment)
    }

    @Test
    fun heading_page_breaks_emit_page_break_blocks() {
        val stylesXml =
            """<style:style style:name="PB" style:family="paragraph">""" +
                """<style:paragraph-properties fo:break-before="page" fo:break-after="page"/>""" +
                """</style:style>"""
        val d = OdfParser.parseTextXml(
            head +
                "<office:automatic-styles>$stylesXml</office:automatic-styles>" +
                """<office:body><office:text><text:h text:style-name="PB">T</text:h></office:text></office:body></office:document>"""
        )
        assertEquals(3, d.content.size)
        assertIs<OdfContentBlock.PageBreak>(d.content[0])
        assertIs<OdfContentBlock.Paragraph>(d.content[1])
        assertIs<OdfContentBlock.PageBreak>(d.content[2])
    }

    @Test
    fun heading_outline_numbering_two_levels() {
        val autoStyles =
            """<text:outline-style>""" +
                """<text:outline-level-style text:level="1" text:num-format="1" text:num-suffix="."/>""" +
                """<text:outline-level-style text:level="2" text:num-format="1" text:num-suffix="."/>""" +
                """</text:outline-style>"""
        val d = doc(
            """<text:h text:outline-level="1">A</text:h>""" +
                """<text:h text:outline-level="1">B</text:h>""" +
                """<text:h text:outline-level="2">C</text:h>""",
            autoStyles
        )
        val nums = paragraphs(d).map { it.outlineNumber }
        // Level 2 shows only its own counter (display-levels=1, i.e. "1." not "2.1.").
        assertEquals(listOf("1.", "2.", "1."), nums)
    }

    @Test
    fun heading_without_outline_style_has_no_number() {
        val d = doc("""<text:h text:outline-level="1">A</text:h>""", "")
        assertEquals(null, paragraphs(d).single().outlineNumber)
    }

    // --- Body paragraphs ---

    @Test
    fun plain_paragraphs_collected_with_styles() {
        val d = doc("""<text:p>first</text:p><text:p>second</text:p>""", "")
        assertEquals(listOf("first", "second"), paragraphs(d).map { it.spans.single().text })
        assertTrue(paragraphs(d).all { it.style == ParagraphStyle.BODY })
    }

    @Test
    fun empty_paragraph_outside_list_is_dropped() {
        val d = doc("""<text:p></text:p><text:p>kept</text:p>""", "")
        assertEquals(listOf("kept"), paragraphs(d).map { it.spans.single().text })
    }

    @Test
    fun body_page_break_style_emits_breaks() {
        val stylesXml =
            """<style:style style:name="BB" style:family="paragraph">""" +
                """<style:paragraph-properties fo:break-before="page"/>""" +
                """</style:style>"""
        val d = OdfParser.parseTextXml(
            head +
                "<office:automatic-styles>$stylesXml</office:automatic-styles>" +
                """<office:body><office:text><text:p text:style-name="BB">T</text:p></office:text></office:body></office:document>"""
        )
        assertIs<OdfContentBlock.PageBreak>(d.content.first())
        assertIs<OdfContentBlock.Paragraph>(d.content.last())
    }

    // --- Lists ---

    @Test
    fun nested_list_depth_tracked() {
        val d = doc(
            """<text:list text:style-name="L1">""" +
                """<text:list-item><text:p>a</text:p>""" +
                """<text:list text:style-name="L1"><text:list-item><text:p>b</text:p></text:list-item></text:list>""" +
                """</text:list-item></text:list>"""
        )
        val paras = paragraphs(d)
        assertEquals(2, paras.size)
        assertEquals(1, paras[0].listLevel)
        assertEquals(2, paras[1].listLevel)
        assertEquals(listOf(1, 1), paras.map { it.listItemIndex })
    }

    @Test
    fun list_start_value_and_item_start_value() {
        val autoStyles =
            """<text:list-style style:name="L9">""" +
                """<text:list-level-style-number text:level="1" text:num-format="1" text:start-value="5"/>""" +
                """</text:list-style>"""
        val d = doc(
            """<text:list text:style-name="L9">""" +
                """<text:list-item><text:p>a</text:p></text:list-item>""" +
                """<text:list-item text:start-value="9"><text:p>b</text:p></text:list-item>""" +
                """</text:list>""",
            autoStyles
        )
        assertEquals(listOf(5, 9), paragraphs(d).map { it.listItemIndex })
    }

    @Test
    fun bullet_list_type_and_checkbox_items() {
        val autoStyles = bulletListStyle +
            """<text:list-style style:name="LC">""" +
            """<text:list-level-style-bullet text:level="1" text:bullet-char="☐"/>""" +
            """</text:list-style>"""
        val d = doc(
            """<text:list text:style-name="LB"><text:list-item><text:p>a</text:p></text:list-item></text:list>""" +
                """<text:list text:style-name="LC">""" +
                """<text:list-item text:checkbox-status="checked"><text:p>b</text:p></text:list-item></text:list>""",
            autoStyles
        )
        val paras = paragraphs(d)
        assertEquals(ListType.BULLET, paras[0].listType)
        assertEquals("▪", paras[0].listBulletChar)
        assertEquals(ListType.CHECKBOX, paras[1].listType)
        assertTrue(paras[1].listChecked)
    }

    @Test
    fun continue_list_by_id_resumes_numbering() {
        val d = doc(
            """<text:list text:style-name="L1" xml:id="l1"><text:list-item><text:p>a</text:p></text:list-item></text:list>""" +
                """<text:list text:style-name="L1" text:continue-list="l1">""" +
                """<text:list-item><text:p>b</text:p></text:list-item></text:list>"""
        )
        assertEquals(listOf(1, 2), paragraphs(d).map { it.listItemIndex })
    }

    @Test
    fun list_number_format_and_affixes_exposed() {
        val autoStyles =
            """<text:list-style style:name="LP">""" +
                """<text:list-level-style-number text:level="1" text:num-format="a" """ +
                """text:num-prefix="(" text:num-suffix=")"/>""" +
                """</text:list-style>"""
        val d = doc(
            """<text:list text:style-name="LP"><text:list-item><text:p>a</text:p></text:list-item></text:list>""",
            autoStyles
        )
        val p = paragraphs(d).single()
        assertEquals("a", p.listNumberFormat)
        assertEquals("(", p.listNumberPrefix)
        assertEquals(")", p.listNumberSuffix)
    }

    // --- Sections / bookmarks / TOC ---

    @Test
    fun sections_emit_start_and_end() {
        val d = doc(
            """<text:section text:name="Intro"><text:p>x</text:p></text:section>""",
            ""
        )
        assertIs<OdfContentBlock.SectionStart>(d.content[0])
        assertEquals("Intro", (d.content[0] as OdfContentBlock.SectionStart).name)
        assertIs<OdfContentBlock.Paragraph>(d.content[1])
        assertIs<OdfContentBlock.SectionEnd>(d.content[2])
    }

    @Test
    fun bookmarks_recorded_with_content_index() {
        val d = doc(
            """<text:p>one</text:p><text:bookmark text:name="B1"/><text:p>two</text:p>""",
            ""
        )
        assertEquals(listOf(OdfBookmark("B1", 1)), d.bookmarks)
        assertEquals(2, paragraphs(d).size)
    }

    @Test
    fun toc_title_and_entries() {
        val d = doc(
            """<text:table-of-content>""" +
                """<text:index-body>""" +
                """<text:index-title><text:p>My TOC</text:p></text:index-title>""" +
                """<text:p>Chapter 1 .... 1</text:p>""" +
                """<text:p>Chapter 2 .... 3</text:p>""" +
                """</text:index-body></text:table-of-content>""",
            ""
        )
        val toc = d.content.filterIsInstance<OdfContentBlock.TableOfContents>().single()
        assertEquals("My TOC", toc.title)
        assertEquals(2, toc.entries.size)
        assertTrue(toc.entries[0].spans.single().text.startsWith("Chapter 1"))
    }

    @Test
    fun toc_default_title_when_no_index_title() {
        val d = doc(
            """<text:table-of-content><text:index-body><text:p>E1</text:p></text:index-body></text:table-of-content>""",
            ""
        )
        val toc = d.content.filterIsInstance<OdfContentBlock.TableOfContents>().single()
        assertEquals("Table of Contents", toc.title)
        assertEquals(1, toc.entries.size)
    }

    // --- Text tables ---

    @Test
    fun text_table_with_columns_rows_and_header() {
        val d = doc(
            """<table:table table:name="T">""" +
                """<table:table-column table:number-columns-repeated="2"/>""" +
                """<table:table-header-rows>""" +
                """<table:table-row><table:table-cell><text:p>H1</text:p></table:table-cell>""" +
                """<table:table-cell><text:p>H2</text:p></table:table-cell></table:table-row>""" +
                """</table:table-header-rows>""" +
                """<table:table-row><table:table-cell><text:p>A</text:p></table:table-cell>""" +
                """<table:table-cell table:number-columns-spanned="2"><text:p>B</text:p></table:table-cell></table:table-row>""" +
                """<table:table-row><table:covered-table-cell/></table:table-row>""" +
                """</table:table>""",
            ""
        )
        val table = d.content.filterIsInstance<OdfContentBlock.Table>().single().table
        assertEquals("T", table.name)
        assertEquals(2, table.columns.size)
        assertEquals(3, table.rows.size)
        assertEquals(1, table.headerRowCount)
        assertEquals("H1", table.rows[0].cells[0].paragraphs.single().spans.single().text)
        assertEquals(2, table.rows[1].cells[1].colSpan)
        assertTrue(table.rows[2].cells.single().isCovered)
    }

    @Test
    fun text_table_repeated_columns_capped() {
        val d = doc(
            """<table:table table:name="T">""" +
                """<table:table-column table:number-columns-repeated="5000"/>""" +
                """<table:table-row><table:table-cell><text:p>x</text:p></table:table-cell></table:table-row>""" +
                """</table:table>""",
            ""
        )
        val table = d.content.filterIsInstance<OdfContentBlock.Table>().single().table
        // MAX_REPEATED = 100 caps runaway repeats.
        assertEquals(100, table.columns.size)
    }

    @Test
    fun text_table_cell_formula_and_spans() {
        val d = doc(
            """<table:table table:name="T">""" +
                """<table:table-row><table:table-cell table:formula="of:=SUM([.A1])">""" +
                """<text:p>5</text:p><text:p>more</text:p></table:table-cell></table:table-row>""" +
                """</table:table>""",
            ""
        )
        val cell: OdfTableCell = d.content.filterIsInstance<OdfContentBlock.Table>().single().table.rows[0].cells[0]
        assertEquals("of:=SUM([.A1])", cell.formula)
        assertEquals(2, cell.paragraphs.size)
    }

    // --- Tracked changes ---

    private val changesBody =
        """<text:tracked-changes>""" +
            """<text:changed-region text:id="c1"><text:insertion><dc:creator>Alice</dc:creator><dc:date>2024-01-01</dc:date></text:insertion></text:changed-region>""" +
            """<text:changed-region text:id="c2"><text:deletion><text:p>gone <text:span>text</text:span></text:p></text:deletion></text:changed-region>""" +
            """<text:changed-region text:id="c3"><text:format-change/></text:changed-region>""" +
            """</text:tracked-changes>"""

    @Test
    fun tracked_changes_metadata_and_deletion_text() {
        val d = doc(changesBody + """<text:p>after</text:p>""", "")
        assertEquals(3, d.changes.size)
        assertEquals("insertion", d.changes[0].type)
        assertEquals("Alice", d.changes[0].author)
        assertEquals("2024-01-01", d.changes[0].date)
        assertEquals("deletion", d.changes[1].type)
        assertEquals("format-change", d.changes[2].type)
        assertEquals(1, paragraphs(d).size)
    }

    @Test
    fun insertion_range_marked_on_spans() {
        val d = doc(
            changesBody +
                """<text:p>before<text:change-start text:change-id="c1"/> new<text:change-end text:change-id="c1"/> tail</text:p>""",
            ""
        )
        val spans = paragraphs(d).single().spans
        val inserted = spans.filter { it.changeKind == "insertion" }
        assertEquals(1, inserted.size)
        assertEquals(" new", inserted.single().text)
        assertEquals("c1", inserted.single().changeId)
        assertFalse(spans.any { it.text == "before" && it.changeKind == "insertion" })
    }

    @Test
    fun deletion_point_expands_deleted_text() {
        val d = doc(
            changesBody + """<text:p>keep<text:change text:change-id="c2"/> end</text:p>""",
            ""
        )
        val spans = paragraphs(d).single().spans
        val deleted = spans.filter { it.changeKind == "deletion" }
        assertEquals(1, deleted.size)
        assertEquals("c2", deleted.single().changeId)
        assertTrue(deleted.single().text.contains("gone"))
        assertTrue(deleted.single().text.contains("text"))
    }

    @Test
    fun unknown_change_id_deletion_is_empty_span() {
        val d = doc("""<text:p>a<text:change text:change-id="nope"/>b</text:p>""", "")
        val deleted = paragraphs(d).single().spans.filter { it.changeKind == "deletion" }
        assertEquals(1, deleted.size)
        assertEquals("", deleted.single().text)
    }

    // --- Body inline media blocks ---

    @Test
    fun body_paragraph_frame_text_box_emits_paragraphs() {
        val d = doc(
            """<text:p><draw:frame draw:name="f" svg:width="2cm" svg:height="1cm" """ +
                """xmlns:svg="urn:oasis:names:tc:opendocument:xmlns:svg-compatible:1.0">""" +
                """<draw:text-box><text:p>boxed</text:p></draw:text-box></draw:frame></text:p>""",
            ""
        )
        assertTrue(paragraphs(d).any { it.spans.singleOrNull()?.text == "boxed" })
    }

    @Test
    fun text_doc_frame_with_text_box_emits_paragraphs() {
        val d = doc(
            """<draw:frame draw:name="f" svg:x="0" svg:y="0" svg:width="3cm" svg:height="2cm" """ +
                """xmlns:svg="urn:oasis:names:tc:opendocument:xmlns:svg-compatible:1.0">""" +
                """<draw:text-box><text:p>framed</text:p></draw:text-box></draw:frame>""",
            ""
        )
        assertTrue(paragraphs(d).any { it.spans.singleOrNull()?.text == "framed" })
    }
}
