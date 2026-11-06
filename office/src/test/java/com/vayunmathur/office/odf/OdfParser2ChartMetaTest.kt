package com.vayunmathur.office.odf

import com.vayunmathur.library.ui.odf.ChartType
import com.vayunmathur.library.ui.odf.OdfContentBlock
import com.vayunmathur.library.ui.odf.OdfDocument
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Slice 2/4 (OdfParser*): embedded charts, metadata, page setup, header/footer
 * and number (data-style) parsing.
 */
class OdfParser2ChartMetaTest {

    private fun chartXml(
        chartClass: String = "chart:bar",
        stacked: String = "",
        legend: Boolean = true,
        titles: String = "",
        table: String? = null,
        styles: String = "",
    ): String {
        val legendTag = if (legend) "<chart:legend/>" else ""
        val data = table ?: (
            """<table:table table:name="local-table">""" +
                """<table:table-row><table:table-cell><text:p></text:p></table:table-cell>""" +
                """<table:table-cell><text:p>Q1</text:p></table:table-cell>""" +
                """<table:table-cell><text:p>Q2</text:p></table:table-cell></table:table-row>""" +
                """<table:table-row><table:table-cell><text:p>Apples</text:p></table:table-cell>""" +
                """<table:table-cell table:value="3"><text:p>3</text:p></table:table-cell>""" +
                """<table:table-cell><text:p>seven</text:p></table:table-cell></table:table-row>""" +
                """<table:table-row><table:table-cell><text:p>Pears</text:p></table:table-cell>""" +
                """<table:table-cell table:value="1"><text:p>1</text:p></table:table-cell>""" +
                """<table:table-cell table:value="2"><text:p>2</text:p></table:table-cell></table:table-row>""" +
                """</table:table>"""
            )
        // Note: no office: prefix on the root; parseChart only reads local names,
        // but the namespace-aware parser requires every used prefix to be declared.
        return """<doc xmlns:chart="urn:oasis:names:tc:opendocument:xmlns:chart:1.0" """ +
            """xmlns:table="urn:oasis:names:tc:opendocument:xmlns:table:1.0" """ +
            """xmlns:text="urn:oasis:names:tc:opendocument:xmlns:text:1.0" """ +
            """xmlns:style="urn:oasis:names:tc:opendocument:xmlns:style:1.0" """ +
            """xmlns:draw="urn:oasis:names:tc:opendocument:xmlns:drawing:1.0">""" +
            """<doc-styles>$styles</doc-styles>""" +
            """<chart:chart chart:class="$chartClass">$titles$legendTag""" +
            """<chart:plot-area $stacked><chart:series chart:style-name="s1"/><chart:series/></chart:plot-area>""" +
            data + "</chart:chart></doc>"
    }

    // --- Charts ---

    @Test
    fun bar_chart_series_categories_and_values() {
        val chart = assertNotNull(OdfParser.parseChart(chartXml()))
        assertEquals(ChartType.BAR, chart.type)
        assertEquals(listOf("Apples", "Pears"), chart.categories)
        assertEquals(2, chart.series.size)
        assertEquals("Q1", chart.series[0].name)
        assertEquals(listOf(3f, 1f), chart.series[0].values)
        // Non-numeric cached text falls back to parsed float, else 0.
        assertEquals(listOf(0f, 2f), chart.series[1].values)
        assertTrue(chart.legend)
    }

    @Test
    fun chart_type_mapping() {
        assertEquals(ChartType.LINE, OdfParser.parseChart(chartXml("chart:line"))!!.type)
        assertEquals(ChartType.SCATTER, OdfParser.parseChart(chartXml("chart:scatter"))!!.type)
        assertEquals(ChartType.PIE, OdfParser.parseChart(chartXml("chart:circle"))!!.type)
        assertEquals(ChartType.DONUT, OdfParser.parseChart(chartXml("chart:ring"))!!.type)
        assertEquals(ChartType.RADAR, OdfParser.parseChart(chartXml("chart:radar"))!!.type)
        assertEquals(ChartType.BUBBLE, OdfParser.parseChart(chartXml("chart:bubble"))!!.type)
        assertEquals(ChartType.AREA, OdfParser.parseChart(chartXml("chart:area"))!!.type)
        assertEquals(ChartType.STACKED_BAR, OdfParser.parseChart(chartXml("chart:bar", """chart:stacked="true""""))!!.type)
        assertEquals(ChartType.BAR, OdfParser.parseChart(chartXml("chart:bar", legend = false))!!.type)
        assertFalse(OdfParser.parseChart(chartXml("chart:bar", legend = false))!!.legend)
    }

    @Test
    fun chart_titles_and_axis_titles() {
        val xml = chartXml(
            titles = """<chart:title><text:p>Main</text:p></chart:title>""" +
                """<chart:axis chart:dimension="x"><chart:title><text:p>Months</text:p></chart:title></chart:axis>""" +
                """<chart:axis chart:dimension="y"><chart:title><text:p>Sales</text:p></chart:title></chart:axis>""" +
                """<chart:subtitle><text:p>Sub</text:p></chart:subtitle>"""
        )
        val chart = assertNotNull(OdfParser.parseChart(xml))
        assertEquals("Main", chart.title)
        assertEquals("Sub", chart.subtitle)
        assertEquals("Months", chart.xAxisTitle)
        assertEquals("Sales", chart.yAxisTitle)
    }

    @Test
    fun chart_series_styles_fill_and_labels() {
        val styles =
            """<style:style style:name="s1" style:family="chart">""" +
                """<style:graphic-properties draw:fill-color="#00ff00"/>""" +
                """<style:chart-properties chart:data-label-number="value"/>""" +
                """</style:style>"""
        val chart = assertNotNull(OdfParser.parseChart(chartXml(styles = styles)))
        assertEquals(0xFF00FF00L, chart.series[0].color)
        assertTrue(chart.series[0].dataLabels)
        assertFalse(chart.series[1].dataLabels)
    }

    @Test
    fun chart_rejected_without_enough_rows_or_series() {
        assertNull(OdfParser.parseChart(chartXml(table = "<table:table table:name=\"local-table\"/>")))
        val oneCol =
            """<table:table table:name="local-table">""" +
                """<table:table-row><table:table-cell><text:p>H</text:p></table:table-cell></table:table-row>""" +
                """<table:table-row><table:table-cell><text:p>R</text:p></table:table-cell></table:table-row>""" +
                """</table:table>"""
        assertNull(OdfParser.parseChart(chartXml(table = oneCol)))
        // Header-only (no data rows) -> null.
        val headerOnly =
            """<table:table table:name="local-table">""" +
                """<table:table-row><table:table-cell><text:p></text:p></table:table-cell>""" +
                """<table:table-cell><text:p>S</text:p></table:table-cell></table:table-row>""" +
                """</table:table>"""
        assertNull(OdfParser.parseChart(chartXml(table = headerOnly)))
    }

    @Test
    fun chart_default_series_name_when_header_blank() {
        val table =
            """<table:table table:name="local-table">""" +
                """<table:table-row><table:table-cell><text:p></text:p></table:table-cell>""" +
                """<table:table-cell><text:p></text:p></table:table-cell></table:table-row>""" +
                """<table:table-row><table:table-cell><text:p>A</text:p></table:table-cell>""" +
                """<table:table-cell table:value="4"><text:p>4</text:p></table:table-cell></table:table-row>""" +
                """</table:table>"""
        assertEquals("Series 1", OdfParser.parseChart(chartXml(table = table))!!.series.single().name)
    }

    // --- Metadata ---

    private val metaHead =
        """<office:document-meta xmlns:office="urn:oasis:names:tc:opendocument:xmlns:office:1.0" """ +
            """xmlns:meta="urn:oasis:names:tc:opendocument:xmlns:meta:1.0" """ +
            """xmlns:dc="http://purl.org/dc/elements/1.1">""" +
            """<office:meta>"""

    private val metaTail = "</office:meta></office:document-meta>"

    @Test
    fun metadata_all_fields() {
        val m = OdfParser.parseMetadata(
            metaHead +
                """<dc:title>Report</dc:title><dc:creator>Editor</dc:creator>""" +
                """<meta:initial-creator>Author</meta:initial-creator>""" +
                """<meta:creation-date>2023-01-01</meta:creation-date><dc:date>2024-02-02</dc:date>""" +
                """<dc:description>Desc</dc:description><dc:subject>Subj</dc:subject>""" +
                """<meta:keyword>k1</meta:keyword><meta:keyword>k2</meta:keyword>""" +
                """<meta:generator>LibreOffice</meta:generator><meta:editing-cycles>7</meta:editing-cycles>""" +
                """<meta:document-statistic meta:page-count="5" meta:word-count="100" """ +
                """meta:character-count="600" meta:paragraph-count="10"/>""" +
                """<meta:user-defined meta:name="Rev" meta:value-type="float">3</meta:user-defined>""" +
                metaTail
        )
        assertEquals("Report", m.title)
        // dc:creator wins over initial-creator for author.
        assertEquals("Editor", m.author)
        assertEquals("Author", m.creator)
        assertEquals("2023-01-01", m.creationDate)
        assertEquals("2024-02-02", m.modifiedDate)
        assertEquals("Desc", m.description)
        assertEquals("Subj", m.subject)
        assertEquals(listOf("k1", "k2"), m.keywords)
        assertEquals("LibreOffice", m.generator)
        assertEquals(7, m.editingCycles)
        assertEquals(5, m.pageCount)
        assertEquals(100, m.wordCount)
        assertEquals(600, m.charCount)
        assertEquals(10, m.paragraphCount)
        assertEquals("3", m.userDefined["Rev"])
        assertEquals("float", m.userDefinedTypes["Rev"])
    }

    @Test
    fun metadata_missing_fields_default() {
        val m = OdfParser.parseMetadata(metaHead + metaTail)
        assertNull(m.title)
        assertNull(m.author)
        assertTrue(m.keywords.isEmpty())
        assertNull(m.editingCycles)
    }

    @Test
    fun metadata_initial_creator_fallback_and_bad_cycles() {
        val m = OdfParser.parseMetadata(
            metaHead + """<meta:initial-creator>Solo</meta:initial-creator>""" +
                """<meta:editing-cycles>many</meta:editing-cycles>""" + metaTail
        )
        assertEquals("Solo", m.author)
        assertNull(m.editingCycles)
    }

    // --- Page setup / header-footer ---

    private val stylesHead =
        """<office:document-styles xmlns:office="urn:oasis:names:tc:opendocument:xmlns:office:1.0" """ +
            """xmlns:style="urn:oasis:names:tc:opendocument:xmlns:style:1.0" """ +
            """xmlns:fo="urn:oasis:names:tc:opendocument:xmlns:xsl-fo-compatible:1.0" """ +
            """xmlns:text="urn:oasis:names:tc:opendocument:xmlns:text:1.0">"""

    @Test
    fun page_setup_explicit_and_uniform_margin() {
        val setup = OdfParser.parsePageSetup(
            stylesHead +
                """<style:page-layout><style:page-layout-properties fo:page-width="10cm" """ +
                """fo:page-height="20cm" fo:margin="1cm"/></style:page-layout>""" +
                """</office:document-styles>"""
        )
        assertNotNull(setup)
        assertEquals(10 * 37.8f, setup.widthPx, 0.01f)
        assertEquals(20 * 37.8f, setup.heightPx, 0.01f)
        assertEquals(37.8f, setup.marginLeftPx, 0.01f)
        assertEquals(37.8f, setup.marginTopPx, 0.01f)
        assertFalse(setup.isLandscape)
    }

    @Test
    fun page_setup_per_side_margins_and_landscape() {
        val setup = OdfParser.parsePageSetup(
            stylesHead +
                """<style:page-layout><style:page-layout-properties fo:page-width="30cm" """ +
                """fo:page-height="20cm" fo:margin-left="2cm" fo:margin-top="3cm"/></style:page-layout>""" +
                """</office:document-styles>"""
        )
        assertNotNull(setup)
        assertEquals(2 * 37.8f, setup.marginLeftPx, 0.01f)
        assertEquals(3 * 37.8f, setup.marginTopPx, 0.01f)
        // Unspecified sides fall back to defaults.
        assertEquals(com.vayunmathur.library.ui.odf.OdfPageSetup().marginRightPx, setup.marginRightPx, 0.01f)
        assertTrue(setup.isLandscape)
    }

    @Test
    fun page_setup_absent_returns_null_and_defaults_used() {
        assertNull(OdfParser.parsePageSetup(stylesHead + "</office:document-styles>"))
    }

    @Test
    fun header_footer_paragraphs_collected() {
        val hf = OdfParser.parseHeaderFooter(
            stylesHead +
                """<style:master-page><style:header><text:p>Head <text:span>one</text:span></text:p></style:header>""" +
                """<style:footer><text:p>Foot</text:p></style:footer></style:master-page>""" +
                """</office:document-styles>""",
            emptyMap()
        )
        assertEquals("Head one", hf.headerParagraphs.single().spans.joinToString("") { it.text })
        assertEquals("Foot", hf.footerParagraphs.single().spans.joinToString("") { it.text })
    }

    @Test
    fun header_footer_empty_paragraphs_skipped() {
        val hf = OdfParser.parseHeaderFooter(
            stylesHead +
                """<style:master-page><style:header><text:p></text:p></style:header></style:master-page>""" +
                """</office:document-styles>""",
            emptyMap()
        )
        assertTrue(hf.headerParagraphs.isEmpty())
        assertTrue(hf.footerParagraphs.isEmpty())
    }

    // --- Number styles ---

    private val numHead =
        """<doc xmlns:number="urn:oasis:names:tc:opendocument:xmlns:datastyle:1.0" """ +
            """xmlns:style="urn:oasis:names:tc:opendocument:xmlns:style:1.0">"""

    @Test
    fun number_styles_all_kinds() {
        val m = OdfParser.parseNumberStyles(
            numHead +
                """<number:number-style style:name="N1">""" +
                """<number:number number:decimal-places="3" number:grouping="true"/></number:number-style>""" +
                """<number:percentage-style style:name="P1"><number:number number:decimal-places="1"/></number:percentage-style>""" +
                """<number:currency-style style:name="C1"><number:currency-symbol>€</number:currency-symbol>""" +
                """<number:number number:decimal-places="0"/></number:currency-style>""" +
                """<number:date-style style:name="D1"><number:year number:style="long"/><number:text>-</number:text>""" +
                """<number:month/><number:day/></number:date-style>""" +
                """<number:time-style style:name="T1"><number:hours/><number:minutes/></number:time-style>""" +
                """<number:number-style style:name="S1"><number:scientific-number number:decimal-places="2"/></number:number-style>""" +
                """<number:number-style style:name="F1"><number:fraction number:min-denominator-digits="2"/></number:number-style>""" +
                """</doc>"""
        )
        assertEquals(3, m["N1"]!!.decimals)
        assertTrue(m["N1"]!!.grouping)
        assertTrue(m["P1"]!!.percent)
        assertEquals("€", m["C1"]!!.currencySymbol)
        assertTrue(m["D1"]!!.isDate)
        // year + literal "-" + month + day.
        assertEquals(4, m["D1"]!!.dateTimeTokens.size)
        assertEquals("year", m["D1"]!!.dateTimeTokens[0].kind)
        assertEquals("long", m["D1"]!!.dateTimeTokens[0].style)
        assertEquals("text", m["D1"]!!.dateTimeTokens[1].kind)
        assertEquals("-", m["D1"]!!.dateTimeTokens[1].text)
        assertTrue(m["T1"]!!.isTime)
        assertTrue(m["S1"]!!.isScientific)
        assertEquals(2, m["S1"]!!.decimals)
        assertTrue(m["F1"]!!.isFraction)
        assertEquals(2, m["F1"]!!.fractionDenominatorDigits)
    }

    @Test
    fun number_styles_date_tokens_only_in_date_time() {
        val m = OdfParser.parseNumberStyles(
            numHead +
                """<number:number-style style:name="N"><number:year/><number:text>x</number:text></number:number-style>""" +
                """</doc>"""
        )
        assertTrue(m["N"]!!.dateTimeTokens.isEmpty())
        assertFalse(m["N"]!!.isDate)
    }

    // --- parseTextXml seam ---

    @Test
    fun parse_text_xml_seam_with_file_name() {
        val d = OdfParser.parseTextXml(
            """<?xml version="1.0"?><office:document """ +
                """xmlns:office="urn:oasis:names:tc:opendocument:xmlns:office:1.0" """ +
                """xmlns:text="urn:oasis:names:tc:opendocument:xmlns:text:1.0">""" +
                """<office:body><office:text><text:p>seam</text:p></office:text></office:body></office:document>""",
            "custom.odt"
        )
        assertIs<OdfDocument.TextDocument>(d)
        assertEquals("custom.odt", d.title)
        val paras: List<com.vayunmathur.library.ui.odf.OdfParagraph> =
            d.content.mapNotNull { (it as? OdfContentBlock.Paragraph)?.paragraph }
        assertEquals("seam", paras.single().spans.single().text)
    }
}
