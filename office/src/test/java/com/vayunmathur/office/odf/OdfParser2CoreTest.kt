package com.vayunmathur.office.odf

import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.LayoutDirection
import com.vayunmathur.library.ui.odf.OdfContentBlock
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Slice 2/4 (OdfParser*): unit conversion, color/clip/rotation parsing and other
 * pure helpers on [OdfParser] shared by every OdfParser*.kt extension file.
 */
class OdfParser2CoreTest {

    // --- parseDimension ---

    @Test
    fun dimension_absolute_units() {
        assertEquals(2f * 37.8f, OdfParser.parseDimension("2cm"), 0.001f)
        assertEquals(5f * 3.78f, OdfParser.parseDimension("5mm"), 0.001f)
        assertEquals(96f, OdfParser.parseDimension("1in"), 0.001f)
        assertEquals(12f * 1.33f, OdfParser.parseDimension("12pt"), 0.001f)
        assertEquals(16f, OdfParser.parseDimension("1pc"), 0.001f)
        assertEquals(32f, OdfParser.parseDimension("2em"), 0.001f)
    }

    @Test
    fun dimension_unitless_and_null() {
        assertEquals(10f, OdfParser.parseDimension("10"), 0.001f)
        assertEquals(0f, OdfParser.parseDimension(null), 0.001f)
        assertEquals(0f, OdfParser.parseDimension("abc"), 0.001f)
        assertEquals(-4f, OdfParser.parseDimension("-4"), 0.001f)
    }

    // --- parseColor ---

    @Test
    fun color_six_digit_hex() {
        assertEquals(0xFFFF0000L, OdfParser.parseColor("#ff0000"))
        assertEquals(0xFF00FF00L, OdfParser.parseColor("00ff00"))
    }

    @Test
    fun color_three_digit_shorthand_expands() {
        assertEquals(0xFFFFFFFFL, OdfParser.parseColor("#FFF"))
        assertEquals(0xFF000000L, OdfParser.parseColor("#000"))
    }

    @Test
    fun color_invalid_returns_null() {
        assertNull(OdfParser.parseColor("#12"))
        assertNull(OdfParser.parseColor("red"))
        assertNull(OdfParser.parseColor(""))
        assertNull(OdfParser.parseColor("#gggggg"))
    }

    // --- parseClip ---

    @Test
    fun clip_percent_rect_returns_left_top_right_bottom() {
        val clip = assertNotNull(OdfParser.parseClip("rect(10% 20% 30% 40%)"))
        assertEquals(0.4f, clip[0], 0.0001f)
        assertEquals(0.1f, clip[1], 0.0001f)
        assertEquals(0.2f, clip[2], 0.0001f)
        assertEquals(0.3f, clip[3], 0.0001f)
    }

    @Test
    fun clip_comma_separated_and_clamped() {
        val clip = assertNotNull(OdfParser.parseClip("rect(150%, 0%, 0%, 0%)"))
        assertEquals(0.95f, clip[1], 0.0001f)
    }

    @Test
    fun clip_zero_crop_and_malformed_return_null() {
        assertNull(OdfParser.parseClip("rect(0% 0% 0% 0%)"))
        assertNull(OdfParser.parseClip(null))
        assertNull(OdfParser.parseClip("none"))
        assertNull(OdfParser.parseClip("rect(10% 20%)"))
    }

    // --- parseClipLengths ---

    @Test
    fun clip_lengths_absolute_values_become_fractions() {
        // parseDimension("10px") == 10 (unknown unit -> raw number), "40px" == 40.
        val clip = assertNotNull(OdfParser.parseClipLengths("rect(10px 20px 30px 40px)", 100f, 200f))
        assertEquals(0.4f, clip[0], 0.0001f) // left = 40/100
        assertEquals(0.05f, clip[1], 0.0001f) // top = 10/200
        assertEquals(0.2f, clip[2], 0.0001f) // right = 20/100
        assertEquals(0.15f, clip[3], 0.0001f) // bottom = 30/200
    }

    @Test
    fun clip_lengths_percent_falls_back_to_percent_parse() {
        val clip = assertNotNull(OdfParser.parseClipLengths("rect(10% 0% 0% 0%)", 100f, 100f))
        assertEquals(0.1f, clip[1], 0.0001f)
    }

    @Test
    fun clip_lengths_invalid_return_null() {
        assertNull(OdfParser.parseClipLengths(null, 100f, 100f))
        assertNull(OdfParser.parseClipLengths("rect(10px 0px 0px 0px)", 0f, 100f))
        assertNull(OdfParser.parseClipLengths("rect(0px 0px 0px 0px)", 100f, 100f))
        assertNull(OdfParser.parseClipLengths("nope", 100f, 100f))
    }

    // --- parseRotationDegrees ---

    @Test
    fun rotation_radians_to_clockwise_degrees() {
        // rotate() is counter-clockwise radians; result is clockwise degrees.
        assertEquals(-90f, OdfParser.parseRotationDegrees("rotate(1.5707963)"), 0.01f)
        assertEquals(0f, OdfParser.parseRotationDegrees(null), 0.001f)
        assertEquals(0f, OdfParser.parseRotationDegrees("translate(10)"), 0.001f)
        assertEquals(0f, OdfParser.parseRotationDegrees("rotate(oops)"), 0.001f)
    }

    // --- formulaBlock (via body paragraphs with embedded objects) ---

    private val mathHead =
        """<?xml version="1.0" encoding="UTF-8"?>""" +
            """<office:document xmlns:office="urn:oasis:names:tc:opendocument:xmlns:office:1.0" """ +
            """xmlns:text="urn:oasis:names:tc:opendocument:xmlns:text:1.0" """ +
            """xmlns:draw="urn:oasis:names:tc:opendocument:xmlns:drawing:1.0" """ +
            """xmlns:xlink="http://www.w3.org/1999/xlink">"""

    @Test
    fun formula_block_math_vs_plain() {
        // Embedded math content in a body paragraph becomes a Formula block;
        // other embedded content becomes a centered italic placeholder paragraph.
        val doc = OdfParser.parseTextDocument(
            mathHead +
                "<office:body><office:text>" +
                """<text:p><draw:object xlink:href="./Object 1"/></text:p>""" +
                """<text:p><draw:object xlink:href="./Object 2"/></text:p>""" +
                "</office:text></office:body></office:document>",
            emptyMap(), emptyMap(), "t",
            com.vayunmathur.library.ui.odf.OdfMetadata(), emptyMap(), null,
            mapOf(
                "Object 1/content.xml" to "<math xmlns:m=\"urn:math\"><m:mi>x</m:mi></math>",
                "Object 2/content.xml" to "<office:spreadsheet xmlns:office=\"urn:oasis:names:tc:opendocument:xmlns:office:1.0\"/>"
            )
        )
        val formula = doc.content.filterIsInstance<OdfContentBlock.Formula>().single()
        assertEquals("<math xmlns:m=\"urn:math\"><m:mi>x</m:mi></math>", formula.mathml)
        // The empty first paragraph is dropped; only the spreadsheet placeholder remains.
        val paras = doc.content.filterIsInstance<OdfContentBlock.Paragraph>()
        assertEquals(1, paras.size)
        val placeholder = paras.single().paragraph
        assertTrue(placeholder.spans.single().text.contains("Embedded spreadsheet"))
        assertTrue(placeholder.spans.single().italic)
        assertEquals(TextAlign.Center, placeholder.alignment)
    }

    // --- parseDirection ---

    @Test
    fun direction_from_writing_mode() {
        assertEquals(LayoutDirection.Rtl, OdfParser.parseDirection("rl-tb"))
        assertEquals(LayoutDirection.Ltr, OdfParser.parseDirection("lr-tb"))
        assertNull(OdfParser.parseDirection(null))
        assertNull(OdfParser.parseDirection("tb"))
    }

    // --- unescapeXml / parseFormulaText ---

    @Test
    fun unescape_xml_entities() {
        assertEquals("<a \"b\" & 'c'>", OdfParser.unescapeXml("&lt;a &quot;b&quot; &amp; &apos;c&apos;&gt;"))
    }

    @Test
    fun formula_text_prefers_annotation() {
        val xml = "<math><annotation>x+y &lt; z</annotation><m:mi>x</m:mi></math>"
        assertEquals("x+y < z", OdfParser.parseFormulaText(xml))
    }

    @Test
    fun formula_text_joins_math_tokens() {
        val xml = "<math xmlns:m=\"m\"><m:mi>x</m:mi><m:mo>+</m:mo><m:mn>2</m:mn></math>"
        assertEquals("x + 2", OdfParser.parseFormulaText(xml))
        val plain = "<math><mi>a</mi><mo>=</mo><mi>b</mi></math>"
        assertEquals("a = b", OdfParser.parseFormulaText(plain))
    }

    @Test
    fun formula_text_no_math_returns_null() {
        assertNull(OdfParser.parseFormulaText("<office:text><text:p>hi</text:p></office:text>"))
        assertNull(OdfParser.parseFormulaText("<math></math>"))
        assertNotNull(OdfParser.parseFormulaText("<math><m:mi>x</m:mi></math>"))
    }

    @Test
    fun link_color_constant() {
        assertEquals(0xFF0066CCL, OdfParser.LINK_COLOR)
    }
}
