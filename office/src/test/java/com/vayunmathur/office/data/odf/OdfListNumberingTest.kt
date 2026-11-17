package com.vayunmathur.office.data.odf

import com.vayunmathur.library.ui.odf.ListType
import com.vayunmathur.library.ui.odf.OdfContentBlock
import com.vayunmathur.library.ui.odf.OdfDocument
import com.vayunmathur.library.ui.odf.OdfParagraph
import com.vayunmathur.library.ui.odf.OdfSerializer
import com.vayunmathur.library.ui.odf.OdfSpan
import com.vayunmathur.library.ui.odf.ParagraphStyle
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Word (and LibreOffice) write one `<text:list>` per item and chain them with
 * text:continue-numbering / text:continue-list. Ignoring those restarts every item at 1, so the
 * whole document renders as "1." repeated.
 */
class OdfListNumberingTest {

    private val listStyle =
        """<text:list-style style:name="L1">""" +
            """<text:list-level-style-number text:level="1" style:num-format="1" style:num-suffix="."/>""" +
            """<text:list-level-style-number text:level="2" style:num-format="1" style:num-suffix="."/>""" +
            "</text:list-style>"

    private fun doc(body: String): OdfDocument.TextDocument = OdfParser.parseTextXml(
        """<?xml version="1.0" encoding="UTF-8"?>""" +
            """<office:document xmlns:office="urn:oasis:names:tc:opendocument:xmlns:office:1.0" """ +
            """xmlns:text="urn:oasis:names:tc:opendocument:xmlns:text:1.0" """ +
            """xmlns:style="urn:oasis:names:tc:opendocument:xmlns:style:1.0" """ +
            """xmlns:xml="http://www.w3.org/XML/1998/namespace">""" +
            "<office:automatic-styles>$listStyle</office:automatic-styles>" +
            "<office:body><office:text>$body</office:text></office:body></office:document>"
    )

    private fun items(d: OdfDocument.TextDocument) =
        d.content.mapNotNull { (it as? OdfContentBlock.Paragraph)?.paragraph }

    private fun numbers(d: OdfDocument.TextDocument) = items(d).map { it.listItemIndex }

    private fun item(text: String, attrs: String = "") = "<text:list-item$attrs><text:p>$text</text:p></text:list-item>"

    @Test
    fun singleListCountsUp() {
        val d = doc("""<text:list text:style-name="L1">${item("a")}${item("b")}${item("c")}</text:list>""")
        assertEquals(listOf(1, 2, 3), numbers(d))
        assertEquals(listOf(ListType.NUMBERED, ListType.NUMBERED, ListType.NUMBERED), items(d).map { it.listType })
    }

    @Test
    fun continueNumberingChainsAcrossSiblingLists() {
        val d = doc(
            """<text:list text:style-name="L1">${item("a")}</text:list>""" +
                """<text:list text:style-name="L1" text:continue-numbering="true">${item("b")}</text:list>""" +
                """<text:list text:style-name="L1" text:continue-numbering="true">${item("c")}</text:list>"""
        )
        assertEquals(listOf(1, 2, 3), numbers(d))
    }

    @Test
    fun continueListFollowsTheReferencedList() {
        val d = doc(
            """<text:list text:style-name="L1" xml:id="l1">${item("a")}${item("b")}</text:list>""" +
                """<text:list text:style-name="L1" xml:id="l2" text:continue-list="l1">${item("c")}</text:list>""" +
                """<text:list text:style-name="L1" text:continue-list="l2">${item("d")}</text:list>"""
        )
        assertEquals(listOf(1, 2, 3, 4), numbers(d))
    }

    @Test
    fun listsWithoutContinuationRestart() {
        val d = doc(
            """<text:list text:style-name="L1">${item("a")}${item("b")}</text:list>""" +
                """<text:list text:style-name="L1">${item("c")}</text:list>"""
        )
        assertEquals(listOf(1, 2, 1), numbers(d))
    }

    @Test
    fun itemStartValueRestartsNumbering() {
        val d = doc(
            """<text:list text:style-name="L1">${item("a")}${item("b", """ text:start-value="7"""")}${item("c")}</text:list>"""
        )
        assertEquals(listOf(1, 7, 8), numbers(d))
    }

    /** The app saves/reloads (and shares) documents as flat ODF, so numbering has to survive that. */
    @Test
    fun numbersSurviveSerializeAndReparse() {
        fun listItem(text: String, index: Int) = OdfContentBlock.Paragraph(OdfParagraph(
            spans = listOf(OdfSpan(text)),
            style = ParagraphStyle.LIST_ITEM,
            listLevel = 1,
            listType = ListType.NUMBERED,
            listItemIndex = index
        ))
        val original = OdfDocument.TextDocument(
            title = "Exercise",
            content = listOf(listItem("a", 1), listItem("b", 2), listItem("c", 3))
        )
        val reparsed = OdfParser.parseTextXml(OdfSerializer.serializeFlat(original))
        assertEquals(listOf(1, 2, 3), numbers(reparsed))
    }

    @Test
    fun nestedListsCountPerLevel() {
        val d = doc(
            """<text:list text:style-name="L1">""" +
                item("a") +
                """<text:list-item><text:list>${item("a1")}${item("a2")}</text:list></text:list-item>""" +
                item("b") +
                "</text:list>"
        )
        assertEquals(listOf(1, 1, 2, 3), numbers(d))
        assertEquals(listOf(1, 2, 2, 1), items(d).map { it.listLevel })
    }
}
