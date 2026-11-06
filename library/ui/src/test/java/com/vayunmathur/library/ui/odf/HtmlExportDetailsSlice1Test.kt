package com.vayunmathur.library.ui.odf

import androidx.compose.ui.text.style.TextAlign
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlin.test.Test
import kotlin.test.assertTrue
import org.junit.runner.RunWith

/**
 * HTML export details: tables, images, checkboxes, nested lists, header rows.
 * Slice-1 coverage for library/ui (odf package). Robolectric for Base64.
 */
@RunWith(AndroidJUnit4::class)
class HtmlExportDetailsSlice1Test {

    private fun para(text: String) =
        OdfContentBlock.Paragraph(OdfParagraph(listOf(OdfSpan(text))))

    private fun docOf(vararg blocks: OdfContentBlock) =
        OdfDocument.TextDocument(title = "t", content = blocks.toList())

    @Test
    fun `a table exports headers spans and colors`() {
        val html = HtmlOdfConverter.odfToHtml(
            docOf(
                OdfContentBlock.Table(
                    OdfTable(
                        rows = listOf(
                            OdfTableRow(
                                listOf(
                                    OdfTableCell(
                                        listOf(OdfParagraph(listOf(OdfSpan("h")))),
                                        colSpan = 2,
                                        backgroundColor = 0xFFFF00,
                                    ),
                                    OdfTableCell(isCovered = true),
                                )
                            ),
                            OdfTableRow(
                                listOf(
                                    OdfTableCell(listOf(OdfParagraph(listOf(OdfSpan("a"))))),
                                    OdfTableCell(listOf(OdfParagraph(listOf(OdfSpan("b"))))),
                                )
                            ),
                        ),
                        headerRowCount = 1,
                    )
                )
            )
        )
        assertTrue(html.contains("<table"), html)
        assertTrue(html.contains("<th"), html)
        assertTrue(html.contains("colspan=\"2\""), html)
        assertTrue(html.contains("background-color:#FFFF00"), html)
        assertTrue(html.contains("<td>a</td>") || html.contains(">a</td>"), html)
    }

    @Test
    fun `an image exports as a data url`() {
        val html = HtmlOdfConverter.odfToHtml(
            docOf(
                OdfContentBlock.Image(
                    OdfImage(
                        path = "pic.png",
                        imageData = byteArrayOf(1, 2, 3),
                        width = 64f,
                        height = 32f,
                        altDesc = "alt",
                    )
                )
            )
        )
        assertTrue(html.contains("<img"), html)
        assertTrue(html.contains("data:image/png;base64,"), html)
        assertTrue(html.contains("width=\"64\""), html)
        assertTrue(html.contains("alt=\"alt\""), html)
    }

    @Test
    fun `an empty image exports to nothing`() {
        val html = HtmlOdfConverter.odfToHtml(
            docOf(OdfContentBlock.Image(OdfImage(path = "x.png", imageData = byteArrayOf())))
        )
        assertTrue(!html.contains("<img"), html)
    }

    @Test
    fun `checkbox items render ballot boxes`() {
        fun item(text: String, checked: Boolean) = OdfContentBlock.Paragraph(
            OdfParagraph(
                listOf(OdfSpan(text)),
                style = ParagraphStyle.LIST_ITEM,
                listLevel = 1,
                listType = ListType.CHECKBOX,
                listChecked = checked,
            )
        )
        val html = HtmlOdfConverter.odfToHtml(docOf(item("done", true), item("todo", false)))
        // ☑ (U+2611) for checked, ☐ (U+2610) for unchecked.
        assertTrue(html.contains("☑"), html)
        assertTrue(html.contains("☐"), html)
    }

    @Test
    fun `numbered lists group into ordered lists`() {
        fun item(text: String) = OdfContentBlock.Paragraph(
            OdfParagraph(
                listOf(OdfSpan(text)),
                style = ParagraphStyle.LIST_ITEM,
                listLevel = 1,
                listType = ListType.NUMBERED,
            )
        )
        val html = HtmlOdfConverter.odfToHtml(docOf(item("one"), item("two")))
        assertTrue(html.contains("<ol>"), html)
        assertTrue(!html.contains("<ul>"), html)
    }

    @Test
    fun `a formula block exports escaped mathml in code`() {
        val html = HtmlOdfConverter.odfToHtml(docOf(OdfContentBlock.Formula("<math><mi>x</mi></math>")))
        assertTrue(html.contains("<code>"), html)
        assertTrue(html.contains("&lt;math&gt;"), html)
    }

    @Test
    fun `a toc exports its entries and a page break exports a rule`() {
        val html = HtmlOdfConverter.odfToHtml(
            docOf(
                OdfContentBlock.TableOfContents(
                    "C",
                    listOf(OdfParagraph(listOf(OdfSpan("Ch1")), style = ParagraphStyle.HEADING1)),
                ),
                OdfContentBlock.PageBreak,
            )
        )
        assertTrue(html.contains("Ch1"), html)
        assertTrue(html.contains("<hr"), html)
    }

    @Test
    fun `alignment maps to align attributes`() {
        val html = HtmlOdfConverter.odfToHtml(
            docOf(
                OdfContentBlock.Paragraph(
                    OdfParagraph(listOf(OdfSpan("c")), alignment = TextAlign.Center)
                ),
                OdfContentBlock.Paragraph(
                    OdfParagraph(listOf(OdfSpan("j")), alignment = TextAlign.Justify)
                ),
            )
        )
        assertTrue(html.contains("center"), html)
        assertTrue(html.contains("justify"), html)
    }
}
