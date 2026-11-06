package com.vayunmathur.office.odf

import androidx.compose.ui.text.style.TextAlign
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Slice 2/4 (OdfParser*): style parsing, inheritance merging, list styles and gradients.
 */
class OdfParser2StylesTest {

    private val ns =
        """<office:document xmlns:office="urn:oasis:names:tc:opendocument:xmlns:office:1.0" """ +
            """xmlns:style="urn:oasis:names:tc:opendocument:xmlns:style:1.0" """ +
            """xmlns:fo="urn:oasis:names:tc:opendocument:xmlns:xsl-fo-compatible:1.0" """ +
            """xmlns:text="urn:oasis:names:tc:opendocument:xmlns:text:1.0" """ +
            """xmlns:draw="urn:oasis:names:tc:opendocument:xmlns:drawing:1.0" """ +
            """xmlns:xlink="http://www.w3.org/1999/xlink" """ +
            """xmlns:table="urn:oasis:names:tc:opendocument:xmlns:table:1.0" """ +
            """xmlns:svg="urn:oasis:names:tc:opendocument:xmlns:svg-compatible:1.0" """ +
            """xmlns:loext="urn:org:documentfoundation:names:experimental:office:xmlns:loext:1.0" """ +
            """xmlns:presentation="urn:oasis:names:tc:opendocument:xmlns:presentation:1.0" """ +
            """xmlns:chart="urn:oasis:names:tc:opendocument:xmlns:chart:1.0">"""

    private fun stylesXml(inner: String) = ns + "<office:automatic-styles>$inner</office:automatic-styles></office:document>"

    private fun styleDef(
        name: String,
        parent: String? = null,
        dataStyle: String? = null,
        inner: String,
    ): String {
        val p = if (parent != null) """ style:parent-style-name="$parent"""" else ""
        val d = if (dataStyle != null) """ style:data-style-name="$dataStyle"""" else ""
        return """<style:style style:name="$name" style:family="paragraph"$p$d>$inner</style:style>"""
    }

    // --- Text properties ---

    @Test
    fun text_properties_parsed() {
        val m = OdfParser.parseStyles(stylesXml(styleDef(
            "T1",
            inner = """<style:text-properties fo:font-weight="bold" fo:font-style="italic" """ +
                """fo:font-size="14pt" fo:font-name="Arial" fo:color="#ff0000" """ +
                """fo:background-color="#00ff00" """ +
                """style:text-underline-style="solid" style:text-underline-color="#0000ff" """ +
                """style:text-line-through-style="solid" fo:letter-spacing="2pt" """ +
                """fo:text-transform="uppercase" fo:language="en" fo:country="US"/>""" +
                """<style:text-properties style:text-position="super 58%"/>"""
        )))
        val s = m["T1"]!!
        assertTrue(s.bold && s.italic)
        assertEquals(14f, s.fontSize ?: 0f)
        assertEquals("Arial", s.fontFamily)
        assertEquals(0xFFFF0000L, s.color)
        assertEquals(0xFF00FF00L, s.backgroundColor)
        assertTrue(s.underline)
        assertEquals("solid", s.underlineStyle)
        assertEquals(0xFF0000FFL, s.underlineColor)
        assertTrue(s.strikethrough)
        assertEquals(2f, s.letterSpacing ?: 0f)
        assertEquals("uppercase", s.textTransform)
        assertEquals("en", s.language)
        assertEquals("US", s.country)
        assertTrue(s.superscript)
    }

    @Test
    fun font_size_units_and_small_caps() {
        fun sizeOf(attr: String): Float? {
            val m = OdfParser.parseStyles(stylesXml(styleDef(
                "T",
                inner = """<style:text-properties $attr/>"""
            )))
            return m["T"]!!.fontSize
        }
        assertEquals(12f, sizeOf("""fo:font-size="12px""""))
        assertEquals(6f, sizeOf("""fo:font-size="50%""""))
        // px@96 -> pt: 96px wide glyph at 96dpi = 1in = 72pt.
        assertEquals(72f, sizeOf("""fo:font-size="2.54cm"""") ?: 0f, 0.01f)
        assertEquals(24f, sizeOf("""fo:font-size="24""""))
        assertNull(sizeOf("""fo:font-size="huge""""))

        val m = OdfParser.parseStyles(stylesXml(styleDef(
            "T",
            inner = """<style:text-properties fo:font-variant="small-caps"/>""" +
                """<style:text-properties style:text-position="sub 58%"/>"""
        )))
        assertEquals("uppercase", m["T"]!!.textTransform)
        assertTrue(m["T"]!!.subscript)
    }

    @Test
    fun text_position_shift_sign() {
        fun posOf(tp: String): Pair<Boolean, Boolean> {
            val m = OdfParser.parseStyles(stylesXml(styleDef(
                "T",
                inner = """<style:text-properties style:text-position="$tp"/>"""
            )))
            return m["T"]!!.superscript to m["T"]!!.subscript
        }
        assertEquals(true to false, posOf("33% 58%"))
        assertEquals(false to true, posOf("-33% 58%"))
        assertEquals(false to false, posOf("0% 100%"))
        assertEquals(false to false, posOf("none"))
    }

    @Test
    fun font_family_fallback_and_transparent_background_ignored() {
        val m = OdfParser.parseStyles(stylesXml(styleDef(
            "T",
            inner = """<style:text-properties fo:font-family="Helvetica" fo:background-color="transparent" """ +
                """fo:letter-spacing="normal" fo:text-transform="none"/>"""
        )))
        val s = m["T"]!!
        assertEquals("Helvetica", s.fontFamily)
        assertNull(s.backgroundColor)
        assertNull(s.letterSpacing)
        assertNull(s.textTransform)
    }

    // --- Paragraph properties ---

    @Test
    fun paragraph_properties_parsed() {
        val m = OdfParser.parseStyles(stylesXml(styleDef(
            "P1",
            inner = """<style:paragraph-properties fo:text-align="justify" """ +
                """fo:margin-left="1cm" fo:margin-right="2cm" fo:margin-top="3mm" fo:margin-bottom="4mm" """ +
                """fo:text-indent="5mm" fo:padding="2mm" fo:background-color="#123456" """ +
                """fo:break-before="page" fo:break-after="page" fo:writing-mode="rl-tb" """ +
                """fo:line-height="150%" fo:keep-with-next="always" fo:keep-together="always" """ +
                """fo:widows="2" fo:orphans="3" fo:border="1pt solid #ff0000"/>"""
        )))
        val s = m["P1"]!!
        assertEquals(TextAlign.Justify, s.textAlign)
        assertEquals(37.8f, s.marginLeft, 0.01f)
        assertEquals(2 * 37.8f, s.marginRight, 0.01f)
        assertEquals(5 * 3.78f, s.textIndent ?: 0f, 0.01f)
        assertEquals(2 * 3.78f, s.padding, 0.01f)
        assertEquals(0xFF123456L, s.paragraphBackgroundColor)
        assertEquals("page", s.breakBefore)
        assertEquals("page", s.breakAfter)
        assertEquals("rl-tb", s.writingMode)
        assertEquals(1.5f, s.lineHeightPercent)
        assertTrue(s.keepWithNext && s.keepTogether)
        assertEquals(2, s.widows)
        assertEquals(3, s.orphans)
        assertEquals(0xFFFF0000L, s.paragraphBorderColor)
        assertNotNull(s.paraBorders)
        assertFalse(s.paraBorders!!.isEmpty())
    }

    @Test
    fun paragraph_align_values_and_padding_sides() {
        fun alignOf(a: String?): TextAlign? {
            val attr = if (a == null) "" else """ fo:text-align="$a""""
            val m = OdfParser.parseStyles(stylesXml(styleDef("P", inner = "<style:paragraph-properties$attr/>")))
            return m["P"]!!.textAlign
        }
        assertEquals(TextAlign.Start, alignOf("start"))
        assertEquals(TextAlign.Start, alignOf("left"))
        assertEquals(TextAlign.Center, alignOf("center"))
        assertEquals(TextAlign.End, alignOf("end"))
        assertEquals(TextAlign.End, alignOf("right"))
        assertEquals(TextAlign.Justify, alignOf("justify"))
        assertNull(alignOf("char"))
        assertNull(alignOf(null))

        val m = OdfParser.parseStyles(stylesXml(styleDef(
            "P",
            inner = """<style:paragraph-properties fo:padding-left="3mm"/>"""
        )))
        assertEquals(3 * 3.78f, m["P"]!!.padding, 0.01f)
    }

    @Test
    fun tab_stops_and_drop_cap() {
        val m = OdfParser.parseStyles(stylesXml(styleDef(
            "P",
            inner = """<style:paragraph-properties><style:tab-stop style:position="2cm" """ +
                """style:type="right" style:leader-char="."/></style:paragraph-properties>""" +
                """<style:drop-cap style:lines="3" style:length="2"/>"""
        )))
        val s = m["P"]!!
        assertEquals(listOf(2 * 37.8f), s.tabStops)
        assertEquals(2 * 37.8f, s.tabStopDetails.single().position, 0.01f)
        assertEquals("right", s.tabStopDetails.single().type)
        assertEquals(".", s.tabStopDetails.single().leaderChar)
        assertEquals(3, s.dropCapLines)
        assertEquals(2, s.dropCapLength)
    }

    // --- Graphic / drawing-page / table properties ---

    @Test
    fun graphic_properties_parsed() {
        val m = OdfParser.parseStyles(stylesXml(styleDef(
            "G",
            inner = """<style:graphic-properties draw:fill="solid" draw:fill-color="#112233" """ +
                """draw:stroke="dash" draw:stroke-color="#445566" svg:stroke-width="2pt" """ +
                """draw:marker-start="Arrow" fo:clip="rect(1% 0% 0% 0%)" """ +
                """style:image-opacity="50%" draw:color-mode="greyscale"/>"""
        )))
        val s = m["G"]!!
        assertEquals(0xFF112233L, s.drawFillColor)
        assertTrue(s.strokeDashed)
        assertEquals(0xFF445566L, s.drawStrokeColor)
        assertTrue(s.markerStart)
        assertEquals("rect(1% 0% 0% 0%)", s.clip)
        assertEquals(50f, s.imageOpacity)
        assertEquals("greyscale", s.imageColorMode)
    }

    @Test
    fun gradient_fill_name_and_drawing_page_transition() {
        val m = OdfParser.parseStyles(stylesXml(styleDef(
            "G",
            inner = """<style:graphic-properties draw:fill="gradient" draw:fill-gradient-name="grad1"/>""" +
                """<style:drawing-page-properties draw:fill="solid" draw:fill-color="#abcdef" """ +
                """presentation:transition-style="dissolve" presentation:transition-speed="medium"/>"""
        )))
        val s = m["G"]!!
        assertEquals("grad1", s.fillGradientName)
        assertEquals(0xFFABCDEFL, s.drawFillColor)
        assertEquals("dissolve", s.transitionType)
        assertEquals("medium", s.transitionSpeed)
    }

    @Test
    fun table_cell_and_column_row_properties() {
        val m = OdfParser.parseStyles(stylesXml(
            styleDef(
                "C",
                inner = """<style:table-cell-properties fo:background-color="#aabbcc" """ +
                    """fo:border="1pt solid #112233" fo:border-top="2pt dashed #445566" """ +
                    """style:vertical-align="middle" fo:wrap-option="wrap"/>"""
            ) +
                styleDef("COL", inner = """<style:table-column-properties style:column-width="3cm"/>""") +
                styleDef("ROW", inner = """<style:table-row-properties style:row-height="1cm"/>""")
        ))
        val c = m["C"]!!
        assertEquals(0xFFAABBCCL, c.cellBackgroundColor)
        assertEquals(0xFF112233L, c.cellBorderColor)
        assertNotNull(c.cellBorders)
        assertEquals("middle", c.cellVerticalAlign)
        assertTrue(c.cellWrap)
        assertEquals(3 * 37.8f, m["COL"]!!.columnWidth ?: 0f, 0.01f)
        assertEquals(37.8f, m["ROW"]!!.rowHeight ?: 0f, 0.01f)
    }

    @Test
    fun conditional_map_and_columns_and_data_style() {
        val m = OdfParser.parseStyles(stylesXml(styleDef(
            "C",
            dataStyle = "N1",
            inner = """<style:table-cell-properties><style:map """ +
                """style:condition="cell-content()&gt;5" style:apply-style-name="High"/></style:table-cell-properties>""" +
                """<style:columns fo:column-count="2"/>"""
        )))
        assertEquals(listOf("cell-content()>5" to "High"), m["C"]!!.conditionalMaps)
        assertEquals(2, m["C"]!!.columnCount)
        assertEquals("N1", m["C"]!!.dataStyleName)
    }

    // --- resolveStyle inheritance ---

    @Test
    fun resolve_style_merges_parent_chain() {
        val m = OdfParser.parseStyles(stylesXml(
            styleDef(
                "Parent",
                inner = """<style:text-properties fo:font-weight="bold" fo:font-size="10pt" fo:color="#111111" """ +
                    """fo:text-transform="lowercase"/>""" +
                    """<style:paragraph-properties fo:text-align="center" fo:margin-left="1cm" """ +
                    """fo:keep-together="always" fo:padding="2mm"/>""" +
                    """<style:graphic-properties draw:fill="solid" draw:fill-color="#222222"/>"""
            ) +
                styleDef("Child", parent = "Parent", inner = """<style:text-properties fo:font-style="italic"/>""")
        ))
        val s = OdfParser.resolveStyle("Child", m)
        assertTrue(s.bold && s.italic)
        assertEquals(10f, s.fontSize)
        assertEquals(0xFF111111L, s.color)
        assertEquals("lowercase", s.textTransform)
        assertEquals(TextAlign.Center, s.textAlign)
        assertEquals(37.8f, s.marginLeft, 0.01f)
        assertTrue(s.keepTogether)
        assertEquals(2 * 3.78f, s.padding, 0.01f)
        assertEquals(0xFF222222L, s.drawFillColor)
    }

    @Test
    fun resolve_style_unknown_name_returns_default() {
        val s = OdfParser.resolveStyle("Missing", emptyMap())
        assertFalse(s.bold)
        assertNull(s.color)
        assertNull(OdfParser.resolveStyle(null, emptyMap()).fontFamily)
    }

    // --- List styles ---

    @Test
    fun list_styles_number_bullet_image_outline() {
        val m = OdfParser.parseListStyles(stylesXml(
            """<text:list-style style:name="L1">""" +
                """<text:list-level-style-number text:level="1" text:num-format="1" """ +
                """text:num-prefix="[" text:num-suffix="]" text:start-value="4"/>""" +
                """<text:list-level-style-bullet text:level="2" text:bullet-char="•"/>""" +
                """<text:list-level-style-image text:level="3" xlink:href="Pictures/b.png"/>""" +
                """</text:list-style>""" +
                """<text:outline-style>""" +
                """<text:outline-level-style text:level="1" text:num-format="I" """ +
                """text:num-suffix="." text:start-value="2" text:display-levels="1"/>""" +
                """<text:outline-level-style text:level="2"/>""" +
                """</text:outline-style>"""
        ))
        val l1 = m["L1"]!!
        assertTrue(l1.anyNumbered)
        val n1 = l1.levelStyle(1)
        assertTrue(n1.numbered)
        assertEquals("[", n1.prefix)
        assertEquals("]", n1.suffix)
        assertEquals(4, n1.startValue)
        assertEquals("•", l1.levelStyle(2).bulletChar)
        assertEquals("Pictures/b.png", l1.levelStyle(3).bulletImagePath)
        // Nearest-level fallback for an undefined level.
        assertEquals("Pictures/b.png", l1.levelStyle(4).bulletImagePath)

        val outline = m["%outline%"]!!
        assertEquals("I", outline.levelStyle(1).numberFormat)
        assertEquals(2, outline.levelStyle(1).startValue)
        // Empty num-format means a bullet (unnumbered) level.
        assertFalse(outline.levelStyle(2).numbered)
    }

    @Test
    fun checkbox_bullet_detection() {
        val m = OdfParser.parseListStyles(stylesXml(
            """<text:list-style style:name="CB">""" +
                """<text:list-level-style-bullet text:level="1" text:bullet-char="☐"/>""" +
                """<text:list-level-style-bullet text:level="2" loext:checkbox="true"/>""" +
                """</text:list-style>"""
        ))
        assertTrue(m["CB"]!!.levelStyle(1).checkbox)
        assertTrue(m["CB"]!!.levelStyle(2).checkbox)
        assertFalse(m["CB"]!!.anyNumbered)
    }

    // --- Gradients ---

    @Test
    fun gradients_parsed_with_angle_kinds() {
        val m = OdfParser.parseGradients(
            ns +
                """<draw:gradient draw:name="g1" draw:start-color="#ff0000" draw:end-color="#0000ff" """ +
                """draw:angle="900" draw:style="linear"/>""" +
                """<draw:gradient draw:name="g2" draw:start-color="#000000" draw:end-color="#ffffff" """ +
                """draw:angle="45deg" draw:style="axial"/>""" +
                """<draw:gradient draw:name="bad" draw:start-color="#000000"/>""" +
                """</office:document>"""
        )
        assertEquals(2, m.size)
        assertEquals(0xFFFF0000L, m["g1"]!!.startColor)
        assertEquals(0xFF0000FFL, m["g1"]!!.endColor)
        assertEquals(90f, m["g1"]!!.angle, 0.001f)
        assertEquals("linear", m["g1"]!!.style)
        assertEquals(45f, m["g2"]!!.angle, 0.001f)
    }
}
