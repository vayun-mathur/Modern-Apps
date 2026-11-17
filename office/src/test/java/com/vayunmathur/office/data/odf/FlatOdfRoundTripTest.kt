package com.vayunmathur.office.data.odf

import com.vayunmathur.library.ui.odf.OdfCell
import com.vayunmathur.library.ui.odf.OdfContentBlock
import com.vayunmathur.library.ui.odf.OdfDocument
import com.vayunmathur.library.ui.odf.OdfParagraph
import com.vayunmathur.library.ui.odf.OdfRow
import com.vayunmathur.library.ui.odf.OdfSerializer
import com.vayunmathur.library.ui.odf.OdfSheet
import com.vayunmathur.library.ui.odf.OdfSlide
import com.vayunmathur.library.ui.odf.OdfSlideElement
import com.vayunmathur.library.ui.odf.OdfFrame
import com.vayunmathur.library.ui.odf.OdfSpan
import kotlin.test.Test
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.StringReader

/**
 * Regression guard for online sharing: the recipient (and the owner reopening from the Online tab)
 * rebuilds a document by running it back through [OdfSerializer.serializeFlat] and then re-parsing
 * it. If the serialized flat ODF isn't well-formed or uses an undeclared namespace prefix, parsing
 * throws "undefined prefix: X" / "missing content.xml" and the shared doc won't open.
 *
 * These tests re-parse the serializer output with a **namespace-aware** pull parser, which fails on
 * exactly those errors (this is what caught the missing `xmlns:meta` on the flat root).
 */
class FlatOdfRoundTripTest {

    /** Parses [xml] with namespace processing on; throws on malformed XML or an undeclared prefix. */
    private fun assertWellFormedWithNamespaces(xml: String) {
        val factory = XmlPullParserFactory.newInstance()
        factory.isNamespaceAware = true
        val parser = factory.newPullParser()
        parser.setInput(StringReader(xml))
        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            event = parser.next() // resolving each element's prefix; throws if undeclared
        }
    }

    @Test
    fun text_document_flat_is_wellformed() {
        val doc = OdfDocument.TextDocument(
            title = "Round Trip",
            content = listOf(
                OdfContentBlock.Paragraph(OdfParagraph(listOf(OdfSpan("Hello ", bold = true), OdfSpan("world")))),
                OdfContentBlock.Paragraph(OdfParagraph(listOf(OdfSpan("second paragraph"))))
            )
        )
        assertWellFormedWithNamespaces(OdfSerializer.serializeFlat(doc))
    }

    @Test
    fun spreadsheet_flat_is_wellformed() {
        val doc = OdfDocument.Spreadsheet(
            title = "Sheet",
            sheets = listOf(OdfSheet("Sheet 1", listOf(OdfRow(listOf(OdfCell(text = "A1"), OdfCell(text = "B1"))))))
        )
        assertWellFormedWithNamespaces(OdfSerializer.serializeFlat(doc))
    }

    @Test
    fun presentation_flat_is_wellformed() {
        val doc = OdfDocument.Presentation(
            title = "Deck",
            slides = listOf(OdfSlide("Slide 1", listOf(
                OdfSlideElement.Frame(OdfFrame(x = 10f, y = 10f, width = 300f, height = 80f,
                    paragraphs = listOf(OdfParagraph(listOf(OdfSpan("Title"))))))
            )))
        )
        assertWellFormedWithNamespaces(OdfSerializer.serializeFlat(doc))
    }
}
