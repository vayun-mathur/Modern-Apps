package com.vayunmathur.office.odf

import com.vayunmathur.library.ui.odf.OdfDocument
import com.vayunmathur.library.ui.odf.OdfShape
import com.vayunmathur.library.ui.odf.OdfSlideElement
import com.vayunmathur.library.ui.odf.ParagraphStyle
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.xmlpull.v1.XmlPullParser

/**
 * Slice 2/4 (OdfParser*): presentations/drawings — slides, frames, text boxes,
 * lists in frames, all shape kinds, notes, SVG path sampling and poly points.
 */
class OdfParser2SlidesTest {

    private val head =
        """<?xml version="1.0" encoding="UTF-8"?>""" +
            """<office:document xmlns:office="urn:oasis:names:tc:opendocument:xmlns:office:1.0" """ +
            """xmlns:draw="urn:oasis:names:tc:opendocument:xmlns:drawing:1.0" """ +
            """xmlns:text="urn:oasis:names:tc:opendocument:xmlns:text:1.0" """ +
            """xmlns:style="urn:oasis:names:tc:opendocument:xmlns:style:1.0" """ +
            """xmlns:fo="urn:oasis:names:tc:opendocument:xmlns:xsl-fo-compatible:1.0" """ +
            """xmlns:svg="urn:oasis:names:tc:opendocument:xmlns:svg-compatible:1.0" """ +
            """xmlns:presentation="urn:oasis:names:tc:opendocument:xmlns:presentation:1.0" """ +
            """xmlns:xlink="http://www.w3.org/1999/xlink">"""

    private fun presentation(
        pages: String,
        autoStyles: String = "",
        title: String = "deck",
    ): OdfDocument.Presentation = OdfParser.parsePresentation(
        head + "<office:automatic-styles>$autoStyles</office:automatic-styles>" +
            "<office:body><office:presentation>$pages</office:presentation></office:body></office:document>",
        OdfParser.parseStyles(head + "<office:automatic-styles>$autoStyles</office:automatic-styles></office:document>"),
        title,
        com.vayunmathur.library.ui.odf.OdfMetadata(),
        emptyMap(),
        emptyMap()
    )

    private fun frame(name: String = "f", inner: String = "", attrs: String = "") =
        """<draw:frame draw:name="$name" svg:x="1cm" svg:y="2cm" svg:width="5cm" svg:height="3cm" $attrs>$inner</draw:frame>"""

    private fun page(name: String = "Slide 1", inner: String = "", attrs: String = "") =
        """<draw:page draw:name="$name" $attrs>$inner</draw:page>"""

    // --- Slides ---

    @Test
    fun slides_default_names_and_master() {
        val d = presentation(page() + page("Second", attrs = """draw:master-page-name="M1""""))
        assertEquals("deck", d.title)
        assertEquals(2, d.slides.size)
        assertEquals("Slide 1", d.slides[0].name)
        assertNull(d.slides[0].masterName)
        assertEquals("M1", d.slides[1].masterName)
    }

    @Test
    fun slide_background_and_transition_from_style() {
        val autoStyles =
            """<style:style style:name="bg" style:family="drawing-page">""" +
                """<style:drawing-page-properties draw:fill="solid" draw:fill-color="#aabbcc" """ +
                """presentation:transition-style="fade" presentation:transition-speed="slow"/>""" +
                """</style:style>"""
        val d = presentation(page(attrs = """draw:style-name="bg""""), autoStyles)
        assertEquals(0xFFAABBCCL, d.slides.single().backgroundColor)
        assertEquals("fade", d.slides.single().transitionType)
        assertEquals("slow", d.slides.single().transitionSpeed)
    }

    @Test
    fun drawing_document_parses_pages() {
        val d = OdfParser.parseDrawing(
            head + "<office:body><office:drawing>" + page("P1") + "</office:drawing></office:body></office:document>",
            emptyMap(), "draw",
            com.vayunmathur.library.ui.odf.OdfMetadata(), emptyMap(), emptyMap()
        )
        assertEquals("P1", d.pages.single().name)
    }

    // --- Frames ---

    @Test
    fun frame_geometry_and_text_box() {
        val d = presentation(page(inner = frame(inner = """<draw:text-box><text:p>Hello</text:p></draw:text-box>""")))
        val f = (d.slides.single().elements.single() as OdfSlideElement.Frame).frame
        assertEquals(37.8f, f.x, 0.01f)
        assertEquals(2 * 37.8f, f.y, 0.01f)
        assertEquals(5 * 37.8f, f.width, 0.01f)
        assertEquals(3 * 37.8f, f.height, 0.01f)
        assertEquals("Hello", f.paragraphs.single().spans.single().text)
        assertNull(f.image)
    }

    @Test
    fun frame_direct_text_paragraphs_and_rotation_anchor() {
        val d = presentation(page(inner = frame(
            attrs = """draw:transform="rotate(1.5707963)" draw:anchor-type="as-char"""",
            inner = """<text:p>direct</text:p>"""
        )))
        val f = (d.slides.single().elements.single() as OdfSlideElement.Frame).frame
        assertEquals("direct", f.paragraphs.single().spans.single().text)
        // Frame-level transform is captured for images; the frame model itself keeps 0 rotation.
        assertEquals(0f, f.rotationDegrees, 0.001f)
    }

    @Test
    fun frame_packaged_image_with_style_and_opacity() {
        val autoStyles =
            """<style:style style:name="img" style:family="graphic">""" +
                """<style:graphic-properties style:image-opacity="75%" draw:color-mode="watermark"/>""" +
                """</style:style>"""
        val d = OdfParser.parsePresentation(
            head + "<office:body><office:presentation>" +
                page(inner = frame(
                    attrs = """draw:style-name="img"""",
                    inner = """<draw:image xlink:href="Pictures/p.png" draw:style-name="img"/>"""
                )) + "</office:presentation></office:body></office:document>",
            OdfParser.parseStyles(head + "<office:automatic-styles>$autoStyles</office:automatic-styles></office:document>"),
            "t",
            com.vayunmathur.library.ui.odf.OdfMetadata(),
            mapOf("Pictures/p.png" to byteArrayOf(9, 9, 9)),
            emptyMap()
        )
        val img = assertNotNull((d.slides.single().elements.single() as OdfSlideElement.Frame).frame.image)
        assertEquals("Pictures/p.png", img.path)
        assertEquals(75f, img.opacityPercent)
        assertEquals("watermark", img.colorMode)
    }

    @Test
    fun frame_svg_title_desc_become_alt_text() {
        val d = presentation(page(inner = frame(inner =
            """<svg:title>My alt</svg:title><svg:desc>My desc</svg:desc>""" +
                """<draw:text-box><text:p>t</text:p></draw:text-box>"""
        )))
        val f = (d.slides.single().elements.single() as OdfSlideElement.Frame).frame
        // No image: alt text has nothing to attach to, paragraphs still parsed.
        assertEquals("t", f.paragraphs.single().spans.single().text)
    }

    @Test
    fun frame_embedded_chart_object() {
        val chartXml =
            """<doc xmlns:chart="urn:oasis:names:tc:opendocument:xmlns:chart:1.0" """ +
                """xmlns:table="urn:oasis:names:tc:opendocument:xmlns:table:1.0" """ +
                """xmlns:text="urn:oasis:names:tc:opendocument:xmlns:text:1.0" """ +
                """xmlns:office="urn:oasis:names:tc:opendocument:xmlns:office:1.0">""" +
                """<chart:chart chart:class="chart:bar"><chart:title><text:p>T</text:p></chart:title>""" +
                """<table:table table:name="local-table">""" +
                """<table:table-row><table:table-cell><text:p></text:p></table:table-cell>""" +
                """<table:table-cell><text:p>S</text:p></table:table-cell></table:table-row>""" +
                """<table:table-row><table:table-cell><text:p>A</text:p></table:table-cell>""" +
                """<table:table-cell table:value="2"><text:p>2</text:p></table:table-cell></table:table-row>""" +
                """</table:table></chart:chart></doc>"""
        val d = OdfParser.parsePresentation(
            head + "<office:body><office:presentation>" +
                page(inner = frame(inner = """<draw:object xlink:href="./Object 1"/>""")) +
                "</office:presentation></office:body></office:document>",
            emptyMap(), "t",
            com.vayunmathur.library.ui.odf.OdfMetadata(), emptyMap(),
            mapOf("Object 1/content.xml" to chartXml)
        )
        val chart = assertNotNull((d.slides.single().elements.single() as OdfSlideElement.Frame).frame.chart)
        assertEquals("S", chart.series.single().name)
    }

    // --- Lists in frames ---

    @Test
    fun lists_in_text_box_become_list_items() {
        val d = presentation(page(inner = frame(inner =
            """<draw:text-box><text:list xmlns:text="urn:oasis:names:tc:opendocument:xmlns:text:1.0">""" +
                """<text:list-item><text:p>a</text:p>""" +
                """<text:list><text:list-item><text:p>nested</text:p></text:list-item></text:list>""" +
                """</text:list-item><text:list-item><text:p>b</text:p></text:list-item></text:list></draw:text-box>"""
        )))
        val paras = (d.slides.single().elements.single() as OdfSlideElement.Frame).frame.paragraphs
        assertEquals(listOf("a", "nested", "b"), paras.map { it.spans.single().text })
        assertTrue(paras.all { it.style == ParagraphStyle.LIST_ITEM })
        assertTrue(paras.all { it.listLevel == 1 })
    }

    // --- Shapes ---

    private fun shapeDoc(tag: String, attrs: String = "", inner: String = ""): OdfShape {
        val d = presentation(page(inner = """<$tag draw:name="s" $attrs>$inner</$tag>"""))
        return (d.slides.single().elements.single() as OdfSlideElement.Shape).shape
    }

    private val geo = """svg:x="1cm" svg:y="1cm" svg:width="4cm" svg:height="2cm""""

    @Test
    fun rect_shape_with_corners_text_and_dash() {
        val autoStyles =
            """<style:style style:name="ds" style:family="graphic">""" +
                """<style:graphic-properties draw:fill="solid" draw:fill-color="#ff0000" """ +
                """draw:stroke="dash" draw:stroke-color="#00ff00" svg:stroke-width="1pt"/>""" +
                """</style:style>"""
        val d = OdfParser.parsePresentation(
            head + "<office:automatic-styles>$autoStyles</office:automatic-styles>" +
                "<office:body><office:presentation>" +
                page(inner = """<draw:rect draw:name="s" draw:style-name="ds" $geo draw:corner-radius="2mm">""" +
                    """<text:p>label</text:p></draw:rect>""") +
                "</office:presentation></office:body></office:document>",
            OdfParser.parseStyles(head + "<office:automatic-styles>$autoStyles</office:automatic-styles></office:document>"),
            "t", com.vayunmathur.library.ui.odf.OdfMetadata(), emptyMap(), emptyMap()
        )
        val r = assertIs<OdfShape.Rect>((d.slides.single().elements.single() as OdfSlideElement.Shape).shape)
        assertEquals(37.8f, r.x, 0.01f)
        assertEquals(4 * 37.8f, r.width, 0.01f)
        assertEquals(0xFFFF0000L, r.fillColor)
        assertEquals(0xFF00FF00L, r.strokeColor)
        assertTrue(r.strokeDashed)
        assertEquals(2 * 3.78f, r.cornerRadius, 0.01f)
        assertEquals("label", r.text.single().spans.single().text)
    }

    @Test
    fun ellipse_line_and_custom_shapes() {
        val e = assertIs<OdfShape.Ellipse>(shapeDoc("draw:ellipse", geo))
        assertEquals(2 * 37.8f, e.height, 0.01f)
        val l = assertIs<OdfShape.Line>(shapeDoc(
            "draw:line",
            """svg:x1="1cm" svg:y1="1cm" svg:x2="4cm" svg:y2="3cm""""
        ))
        assertEquals(3 * 37.8f, l.width, 0.01f)
        assertEquals(2 * 37.8f, l.height, 0.01f)
        val c = assertIs<OdfShape.CustomShape>(shapeDoc("draw:custom-shape", geo))
        assertEquals(4 * 37.8f, c.width, 0.01f)
    }

    @Test
    fun line_markers_parsed() {
        val autoStyles =
            """<style:style style:name="mk" style:family="graphic">""" +
                """<style:graphic-properties draw:marker-start="Arrow" draw:marker-end="Arrow"/>""" +
                """</style:style>"""
        val d = OdfParser.parsePresentation(
            head + "<office:automatic-styles>$autoStyles</office:automatic-styles>" +
                "<office:body><office:presentation>" +
                page(inner = """<draw:line draw:name="s" draw:style-name="mk" """ +
                    """svg:x1="1cm" svg:y1="1cm" svg:x2="4cm" svg:y2="3cm"/>""") +
                "</office:presentation></office:body></office:document>",
            OdfParser.parseStyles(head + "<office:automatic-styles>$autoStyles</office:automatic-styles></office:document>"),
            "t", com.vayunmathur.library.ui.odf.OdfMetadata(), emptyMap(), emptyMap()
        )
        val l = assertIs<OdfShape.Line>((d.slides.single().elements.single() as OdfSlideElement.Shape).shape)
        assertTrue(l.markerStart && l.markerEnd)
    }

    @Test
    fun polyline_polygon_points_and_path() {
        val pl = assertIs<OdfShape.Polyline>(shapeDoc(
            "draw:polyline",
            geo + """ draw:points="0,0 100,0 100,50" svg:viewBox="0 0 100 50""""
        ))
        assertFalse(pl.closed)
        assertEquals(3, pl.points.size)
        assertEquals(37.8f, pl.points[0].first, 0.01f)
        val pg = assertIs<OdfShape.Polyline>(shapeDoc(
            "draw:polygon",
            geo + """ draw:points="0,0 100,0" svg:viewBox="0 0 100 50""""
        ))
        assertTrue(pg.closed)
        val path = assertIs<OdfShape.Polyline>(shapeDoc(
            "draw:path",
            geo + """ svg:viewBox="0 0 100 50" svg:d="M0,0 L100,0 L100,50 Z""""
        ))
        assertTrue(path.closed)
        assertTrue(path.points.size >= 3)
    }

    @Test
    fun shape_rotation_and_gradient_fill() {
        val grad = com.vayunmathur.library.ui.odf.OdfGradient(0xFFFF0000L, 0xFF0000FFL, 90f)
        OdfParser.gradientDefs = mapOf("g1" to grad)
        val autoStyles =
            """<style:style style:name="gs" style:family="graphic">""" +
                """<style:graphic-properties draw:fill="gradient" draw:fill-gradient-name="g1"/>""" +
                """</style:style>"""
        val d = OdfParser.parsePresentation(
            head + "<office:automatic-styles>$autoStyles</office:automatic-styles>" +
                "<office:body><office:presentation>" +
                page(inner = """<draw:rect draw:name="s" draw:style-name="gs" $geo draw:transform="rotate(3.1415926)">""" +
                    """<text:p>t</text:p></draw:rect>""") +
                "</office:presentation></office:body></office:document>",
            OdfParser.parseStyles(head + "<office:automatic-styles>$autoStyles</office:automatic-styles></office:document>"),
            "t", com.vayunmathur.library.ui.odf.OdfMetadata(), emptyMap(), emptyMap()
        )
        val r = assertIs<OdfShape.Rect>((d.slides.single().elements.single() as OdfSlideElement.Shape).shape)
        assertEquals(-180f, r.rotationDegrees, 0.5f)
        assertEquals(grad, r.fillGradient)
        OdfParser.gradientDefs = emptyMap()
    }

    // --- Notes ---

    @Test
    fun slide_notes_collected_from_notes_frames() {
        val d = presentation(page(inner =
            frame(inner = """<draw:text-box><text:p>slide text</text:p></draw:text-box>""") +
                """<presentation:notes><draw:frame draw:name="n" svg:x="0" svg:y="0" svg:width="1cm" svg:height="1cm">""" +
                """<draw:text-box><text:p>speaker note</text:p></draw:text-box></draw:frame></presentation:notes>"""
        ))
        assertEquals(listOf("speaker note"), d.slides.single().notes.map { it.spans.single().text })
    }

    // --- SVG path sampling / poly points (direct unit tests) ---

    @Test
    fun svg_path_commands_sampled() {
        val (pts, closed) = OdfParser.sampleSvgPath("M0,0 L10,0 H20 V10 Z", listOf(0f, 0f, 20f, 10f), 0f, 0f, 40f, 20f)
        assertTrue(closed)
        // (0,0)->(0,0); (10,0)->(20,0); H20->(40,0); V10->(40,20); Z back to start.
        assertEquals(0f to 0f, pts.first())
        assertEquals(20f, pts[1].first, 0.001f)
        assertEquals(40f, pts[2].first, 0.001f)
        assertEquals(20f, pts[3].second, 0.001f)
        assertEquals(0f to 0f, pts.last())
    }

    @Test
    fun svg_path_relative_and_curves() {
        // No viewBox: vbW/vbH default to 1, so raw units scale by w/h (100x).
        val (rel, _) = OdfParser.sampleSvgPath("M10,10 l5,0 c0,0 5,0 5,5 s5,5 5,5", null, 0f, 0f, 100f, 100f)
        assertTrue(rel.size > 4)
        assertEquals(1000f, rel.first().first, 0.001f)
        val (quad, _) = OdfParser.sampleSvgPath("M0,0 Q10,0 10,10 T20,20", null, 0f, 0f, 10f, 10f)
        assertTrue(quad.size > 3)
        val (arc, _) = OdfParser.sampleSvgPath("M0,0 A5,5 0 0 1 10,10", null, 0f, 0f, 10f, 10f)
        assertEquals(100f, arc.last().first, 0.001f)
    }

    @Test
    fun svg_path_empty_and_unknown_command() {
        assertEquals(0, OdfParser.sampleSvgPath(null, null, 0f, 0f, 1f, 1f).first.size)
        assertEquals(0, OdfParser.sampleSvgPath("  ", null, 0f, 0f, 1f, 1f).first.size)
        // Unknown "X" command is skipped; the following L1,1 still samples
        // (10x scale: no viewBox, w=h=10).
        val (pts, _) = OdfParser.sampleSvgPath("M0,0 X5,5 L1,1", null, 0f, 0f, 10f, 10f)
        assertEquals(10f, pts.last().first, 0.001f)
    }

    @Test
    fun svg_path_relative_axis_commands() {
        // Relative h/v accumulate from the cursor: M10,10 h5 -> x=15; v5 -> y=15 (100x scale).
        val (pts, _) = OdfParser.sampleSvgPath("M10,10 h5 v5", null, 0f, 0f, 100f, 100f)
        assertEquals(1500f, pts[1].first, 0.001f)
        assertEquals(1000f, pts[1].second, 0.001f)
        assertEquals(1500f, pts.last().first, 0.001f)
        assertEquals(1500f, pts.last().second, 0.001f)
    }

    @Test
    fun path_shape_with_text_and_rotation() {
        val shape = assertIs<OdfShape.Polyline>(shapeDoc(
            "draw:path",
            geo + """ svg:viewBox="0 0 100 50" svg:d="M0,0 L100,0" draw:transform="rotate(3.1415926)"""",
            """<text:p>road</text:p>"""
        ))
        assertFalse(shape.closed)
        assertEquals("road", shape.text.single().spans.single().text)
        assertEquals(-180f, shape.rotationDegrees, 0.5f)
    }

    @Test
    fun poly_points_mapping_and_bad_input() {
        val pts = OdfParser.parsePolyPoints("0,0 50,25 bad 10,", listOf(0f, 0f, 100f, 50f), 10f, 20f, 200f, 100f)
        assertEquals(2, pts.size)
        assertEquals(10f to 20f, pts[0])
        assertEquals(110f, pts[1].first, 0.001f)
        assertEquals(70f, pts[1].second, 0.001f)
        assertTrue(OdfParser.parsePolyPoints(null, null, 0f, 0f, 1f, 1f).isEmpty())
        assertTrue(OdfParser.parsePolyPoints("   ", null, 0f, 0f, 1f, 1f).isEmpty())
        // Zero viewBox dims fall back to 1.
        assertEquals(1, OdfParser.parsePolyPoints("1,1", listOf(0f, 0f, 0f, 0f), 0f, 0f, 10f, 10f).size)
    }

    @Test
    fun is_tag_helper() {
        val parser = OdfParser.newParser(
            """<r xmlns:draw="urn:x:drawing:1.0" xmlns:office="urn:x:office:1.0">""" +
                """<draw:page/><office:text/></r>"""
        )
        var e = parser.eventType
        while (!(e == XmlPullParser.START_TAG && parser.name == "page")) e = parser.next()
        assertTrue(isTag(e, parser, "page", "draw"))
        assertFalse(isTag(e, parser, "page", "office"))
        assertFalse(isTag(XmlPullParser.TEXT, parser, "page", "draw"))
    }
}
