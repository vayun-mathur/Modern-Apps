package com.vayunmathur.office.odf

import com.vayunmathur.library.ui.odf.OdfImage
import com.vayunmathur.library.ui.odf.OdfSlideElement
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.xmlpull.v1.XmlPullParser

/**
 * Slice 2/4 (OdfParser*), Robolectric part: inline base64 images
 * ([OdfParser.readInlineImageData]) need android.util.Base64, which is a stub
 * on plain JVM tests. Also covers frame-embedded base64 images and the
 * packaged-image clip fallback via graphic-style clip.
 */
@RunWith(RobolectricTestRunner::class)
class OdfParser2MediaRoboTest {

    private val head =
        """<?xml version="1.0" encoding="UTF-8"?>""" +
            """<office:document xmlns:office="urn:oasis:names:tc:opendocument:xmlns:office:1.0" """ +
            """xmlns:text="urn:oasis:names:tc:opendocument:xmlns:text:1.0" """ +
            """xmlns:draw="urn:oasis:names:tc:opendocument:xmlns:drawing:1.0" """ +
            """xmlns:svg="urn:oasis:names:tc:opendocument:xmlns:svg-compatible:1.0" """ +
            """xmlns:xlink="http://www.w3.org/1999/xlink" """ +
            """xmlns:style="urn:oasis:names:tc:opendocument:xmlns:style:1.0" """ +
            """xmlns:fo="urn:oasis:names:tc:opendocument:xmlns:xsl-fo-compatible:1.0">"""

    // 1x1 transparent PNG.
    private val tinyPng =
        "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNk+M9QDwADhgGAWjR9awAAAABJRU5ErkJggg=="

    /** Runs [OdfParser.parseInlineContent] over a bare `<text:p>` fragment. */
    private fun inlineSpans(
        fragment: String,
        imagesOut: MutableList<OdfImage>? = null,
    ): List<com.vayunmathur.library.ui.odf.OdfSpan> {
        val parser = OdfParser.newParser(
            """<w xmlns:text="urn:oasis:names:tc:opendocument:xmlns:text:1.0" """ +
                """xmlns:draw="urn:oasis:names:tc:opendocument:xmlns:drawing:1.0" """ +
                """xmlns:xlink="http://www.w3.org/1999/xlink" """ +
                """xmlns:svg="urn:oasis:names:tc:opendocument:xmlns:svg-compatible:1.0">""" +
                fragment + "</w>"
        )
        var e = parser.eventType
        while (!(e == XmlPullParser.START_TAG && parser.name == "p")) e = parser.next()
        return OdfParser.parseInlineContent(parser, "p", emptyMap(), emptyMap(), null, imagesOut)
    }

    @Test
    fun inline_base64_image_decoded() {
        val imagesOut = mutableListOf<OdfImage>()
        inlineSpans(
            """<text:p>t<draw:frame draw:name="f" svg:width="2cm" svg:height="1cm">""" +
                """<draw:image><office:binary-data xmlns:office="urn:o">""" + tinyPng +
                """</office:binary-data></draw:image></draw:frame></text:p>""",
            imagesOut
        )
        val img = imagesOut.single()
        assertEquals("inline", img.path)
        assertTrue(img.imageData.isNotEmpty())
        assertEquals(2f * 37.8f, img.width, 0.01f)
    }

    @Test
    fun inline_invalid_base64_skipped() {
        val imagesOut = mutableListOf<OdfImage>()
        inlineSpans(
            """<text:p>t<draw:frame draw:name="f"><draw:image>""" +
                """<office:binary-data xmlns:office="urn:o">!!!not-base64!!!</office:binary-data>""" +
                """</draw:image></draw:frame></text:p>""",
            imagesOut
        )
        assertTrue(imagesOut.isEmpty())
    }

    @Test
    fun frame_base64_image_with_opacity() {
        val autoStyles =
            """<style:style style:name="op" style:family="graphic">""" +
                """<style:graphic-properties style:image-opacity="80%"/>""" +
                """</style:style>"""
        val d = OdfParser.parsePresentation(
            head + "<office:automatic-styles>$autoStyles</office:automatic-styles>" +
                "<office:body><office:presentation>" +
                """<draw:page draw:name="S1">""" +
                """<draw:frame draw:name="f" svg:x="0" svg:y="0" svg:width="3cm" svg:height="2cm">""" +
                """<draw:image draw:style-name="op"><office:binary-data xmlns:office="urn:o">""" + tinyPng +
                """</office:binary-data></draw:image></draw:frame></draw:page>""" +
                "</office:presentation></office:body></office:document>",
            OdfParser.parseStyles(head + "<office:automatic-styles>$autoStyles</office:automatic-styles></office:document>"),
            "t", com.vayunmathur.library.ui.odf.OdfMetadata(), emptyMap(), emptyMap()
        )
        val frame = (d.slides.single().elements.single() as OdfSlideElement.Frame).frame
        val img = assertNotNull(frame.image)
        assertEquals("inline", img.path)
        assertEquals(3f * 37.8f, img.width, 0.01f)
        assertEquals(80f, img.opacityPercent)
    }

    @Test
    fun packaged_image_style_clip_fallback() {
        // No fo:clip on the frame; the graphic style's fo:clip is parsed (style
        // "clip" resolves with the raw rect). Natural size is 0 under the test
        // runtime (no bitmap decoder), so image crops can't resolve — assert the
        // image itself plus parseClip's [left, top, right, bottom] contract.
        val autoStyles =
            """<style:style style:name="clip" style:family="graphic">""" +
                """<style:graphic-properties fo:clip="rect(10% 20% 30% 40%)"/>""" +
                """</style:style>"""
        val png = android.util.Base64.decode(tinyPng, android.util.Base64.DEFAULT)
        val d = OdfParser.parsePresentation(
            head + "<office:automatic-styles>$autoStyles</office:automatic-styles>" +
                "<office:body><office:presentation>" +
                """<draw:page draw:name="S1">""" +
                """<draw:frame draw:name="f" svg:x="0" svg:y="0" svg:width="3cm" svg:height="2cm">""" +
                """<draw:image xlink:href="Pictures/p.png" draw:style-name="clip"/>""" +
                """</draw:frame></draw:page>""" +
                "</office:presentation></office:body></office:document>",
            OdfParser.parseStyles(head + "<office:automatic-styles>$autoStyles</office:automatic-styles></office:document>"),
            "t", com.vayunmathur.library.ui.odf.OdfMetadata(),
            mapOf("Pictures/p.png" to png), emptyMap()
        )
        val img = assertNotNull((d.slides.single().elements.single() as OdfSlideElement.Frame).frame.image)
        assertEquals("Pictures/p.png", img.path)
        assertEquals(png.size, img.imageData.size)
        // parseClip("rect(top right bottom left)") -> [left, top, right, bottom].
        val clip = assertNotNull(OdfParser.parseClip("rect(10% 20% 30% 40%)"))
        assertEquals(0.4f, clip[0], 0.0001f)
        assertEquals(0.1f, clip[1], 0.0001f)
        assertEquals(0.2f, clip[2], 0.0001f)
        assertEquals(0.3f, clip[3], 0.0001f)
    }

    @Test
    fun footnote_without_footnotes_out_still_adds_span() {
        // footnotes == null: citation span still emitted.
        val parser = OdfParser.newParser(
            """<w xmlns:text="urn:oasis:names:tc:opendocument:xmlns:text:1.0">""" +
                """<text:p>a<text:note><text:note-citation>2</text:note-citation>""" +
                """<text:note-body><text:p>body</text:p></text:note-body></text:note></text:p></w>"""
        )
        var e = parser.eventType
        while (!(e == XmlPullParser.START_TAG && parser.name == "p")) e = parser.next()
        val spans = OdfParser.parseInlineContent(parser, "p", emptyMap(), emptyMap(), null)
        assertEquals(listOf("a", "2"), spans.map { it.text })
        assertTrue(spans[1].superscript)
    }

    @Test
    fun empty_annotation_yields_marker_with_empty_comment() {
        val parser = OdfParser.newParser(
            """<w xmlns:text="urn:oasis:names:tc:opendocument:xmlns:text:1.0" """ +
                """xmlns:dc="http://purl.org/dc/elements/1.1" xmlns:office="urn:o">""" +
                """<text:p>a<office:annotation><dc:creator></dc:creator></office:annotation></text:p></w>"""
        )
        var e = parser.eventType
        while (!(e == XmlPullParser.START_TAG && parser.name == "p")) e = parser.next()
        val spans = OdfParser.parseInlineContent(parser, "p", emptyMap())
        assertEquals("a", spans.first().text)
        assertNotNull(spans.last().annotation)
    }
}
