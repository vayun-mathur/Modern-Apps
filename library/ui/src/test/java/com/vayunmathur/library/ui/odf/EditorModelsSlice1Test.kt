package com.vayunmathur.library.ui.odf

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Small editor models and exporter branches: page setup, gradients,
 * conditional formats, validations, slide bounds, editor-state paragraph
 * ops, pdf list/table paths and epub images. Slice-1 coverage for
 * library/ui (odf package).
 *
 * Robolectric because image exporters use android.util.Base64.
 */
@RunWith(RobolectricTestRunner::class)
class EditorModelsSlice1Test {

    @Test
    fun `page setup reports orientation and margins`() {
        val portrait = OdfPageSetup()
        assertFalse(portrait.isLandscape)
        assertEquals(793.7f, portrait.widthPx)
        assertEquals(75.6f, portrait.marginTopPx)
        assertEquals(75.6f, portrait.marginRightPx)
        assertEquals(75.6f, portrait.marginBottomPx)
        assertEquals(75.6f, portrait.marginLeftPx)
        assertEquals(1122.5f, portrait.heightPx)
        val landscape = OdfPageSetup(widthPx = 1122.5f, heightPx = 793.7f)
        assertTrue(landscape.isLandscape)
    }

    @Test
    fun `a gradient carries its colors angle and style`() {
        val g = OdfGradient(startColor = 0xFFFF0000, endColor = 0xFF0000FF, angle = 45f)
        assertEquals(0xFFFF0000, g.startColor)
        assertEquals(0xFF0000FF, g.endColor)
        assertEquals(45f, g.angle)
        assertEquals("linear", g.style)
    }

    @Test
    fun `a conditional format carries its condition and colors`() {
        val c = OdfCondFormat(
            condition = "cell-content() > 5",
            backgroundColor = 0xFFFFFF00,
            textColor = 0xFF000000,
        )
        assertEquals("cell-content() > 5", c.condition)
        assertEquals(0xFFFFFF00, c.backgroundColor)
        assertEquals(0xFF000000, c.textColor)
    }

    @Test
    fun `a list validation exposes its values`() {
        val v = OdfDataValidation(
            name = "v",
            condition = "cell-content-is-in-list(\"a\";\"b\"; \"c\")",
        )
        assertEquals(listOf("a", "b", "c"), v.listValues())
        assertNull(OdfDataValidation(name = "w", condition = "cell-content() > 1").listValues())
        assertEquals(emptyList(), OdfDataValidation(name = "e", condition = "cell-content-is-in-list()").listValues())
    }

    @Test
    fun `element bounds resize frames and every shape kind`() {
        val frame = OdfSlideElement.Frame(
            OdfFrame(x = 0f, y = 0f, width = 10f, height = 10f, paragraphs = emptyList())
        )
        val moved = setElementBounds(frame, 5f, 6f, 50f, 60f) as OdfSlideElement.Frame
        assertEquals(5f, moved.frame.x)
        assertEquals(60f, moved.frame.height)
        val shapes: List<OdfShape> = listOf(
            OdfShape.Rect(0f, 0f, 1f, 1f),
            OdfShape.Ellipse(0f, 0f, 1f, 1f),
            OdfShape.Line(0f, 0f, 9f, 4f),
            OdfShape.CustomShape(0f, 0f, 1f, 1f),
        )
        for (s in shapes) {
            val out = setElementBounds(OdfSlideElement.Shape(s), 2f, 3f, 40f, 30f)
            val b = (out as OdfSlideElement.Shape).shape
            assertEquals(2f, b.x, "x for ${s::class.simpleName}")
            assertEquals(40f, b.width, "w for ${s::class.simpleName}")
        }
    }

    @Test
    fun `a polyline resizes like the other shapes`() {
        val line = OdfShape.Polyline(
            x = 1f, y = 1f, width = 5f, height = 5f,
            points = listOf(1f to 1f, 6f to 6f),
        )
        val out = setElementBounds(OdfSlideElement.Shape(line), 0f, 0f, 20f, 20f)
        val b = (out as OdfSlideElement.Shape).shape as OdfShape.Polyline
        assertEquals(20f, b.width)
        assertEquals(listOf(1f to 1f, 6f to 6f), b.points)
    }

    private fun doc(vararg texts: String) = OdfDocument.TextDocument(
        title = "t",
        content = texts.map { OdfContentBlock.Paragraph(OdfParagraph(listOf(OdfSpan(it)))) },
    )

    private fun paraAt(doc: OdfDocument.TextDocument, i: Int): OdfParagraph =
        (doc.content[i] as OdfContentBlock.Paragraph).paragraph

    @Test
    fun `insert and clear run formatting flow through state`() {
        val s = OdfTextEditorState(doc("hello world"))
        s.insertTextInRun(0, 0, 5, "!")
        assertTrue(paraAt(s.current, 0).spans.joinToString("") { it.text }.contains("!"))
        s.applyRunSpanStyle(0, 0, 0, 5) { it.copy(bold = true) }
        assertTrue(paraAt(s.current, 0).spans.any { it.bold })
        s.clearRunFormatting(0, 0, 0, 5)
        assertTrue(paraAt(s.current, 0).spans.none { it.bold })
        assertTrue(s.canUndo.value)
    }

    @Test
    fun `paragraph structure ops flow through state`() {
        val s = OdfTextEditorState(doc("a", "b", "c"))
        s.changeListLevel(0, 1)
        s.restartNumbering(0)
        s.insertHorizontalLine(1)
        s.indentParagraph(0)
        s.outdentParagraph(0)
        s.setParagraphAlignment(0, androidx.compose.ui.text.style.TextAlign.Center)
        assertEquals(
            androidx.compose.ui.text.style.TextAlign.Center,
            paraAt(s.current, 0).alignment,
        )
        s.moveParagraphDown(0)
        assertEquals("b", paraAt(s.current, 0).spans.joinToString("") { it.text })
        s.moveParagraphUp(1)
        assertEquals("a", paraAt(s.current, 0).spans.joinToString("") { it.text })
        s.duplicateParagraph(0)
        assertEquals(5, s.current.content.size)
        s.deleteParagraph(4)
        assertEquals(4, s.current.content.size)
    }

    @Test
    fun `checkbox state ops flow through state`() {
        val s = OdfTextEditorState(
            OdfDocument.TextDocument(
                title = "t",
                content = listOf(
                    OdfContentBlock.Paragraph(
                        OdfParagraph(
                            listOf(OdfSpan("task")),
                            style = ParagraphStyle.LIST_ITEM,
                            listType = ListType.CHECKBOX,
                        )
                    )
                ),
            )
        )
        s.setCheckboxChecked(0, true)
        assertTrue(paraAt(s.current, 0).listChecked)
        s.toggleCheckbox(0)
        assertFalse(paraAt(s.current, 0).listChecked)
    }

    @Test
    fun `pdf export covers lists tables toc and formulas`() {
        val bytes = PdfExporter.export(
            OdfDocument.TextDocument(
                title = "t",
                content = listOf(
                    OdfContentBlock.Paragraph(
                        OdfParagraph(
                            listOf(OdfSpan("item")),
                            style = ParagraphStyle.LIST_ITEM,
                            listType = ListType.NUMBERED,
                            listItemIndex = 2,
                            listLevel = 1,
                        )
                    ),
                    OdfContentBlock.Paragraph(
                        OdfParagraph(
                            listOf(OdfSpan("todo")),
                            style = ParagraphStyle.LIST_ITEM,
                            listType = ListType.CHECKBOX,
                            listChecked = true,
                        )
                    ),
                    OdfContentBlock.Table(
                        OdfTable(
                            rows = listOf(
                                OdfTableRow(
                                    listOf(
                                        OdfTableCell(
                                            listOf(OdfParagraph(listOf(OdfSpan("cell•(x)"))))
                                        )
                                    )
                                )
                            )
                        )
                    ),
                    OdfContentBlock.TableOfContents(
                        "C",
                        listOf(OdfParagraph(listOf(OdfSpan("entry")))),
                    ),
                    OdfContentBlock.Formula("x^2"),
                ),
            )
        )
        val text = bytes.toString(Charsets.ISO_8859_1)
        assertTrue(text.startsWith("%PDF"), text.take(20))
        // The marker and word are separate Tj tokens, so assert both parts.
        assertTrue(text.contains("(2.)"), text.take(3000))
        assertTrue(text.contains("(item)"), text.take(3000))
        assertTrue(text.contains("[x]"), text)
        assertTrue(text.contains("entry"), text)
    }

    @Test
    fun `epub export embeds images`() {
        val bytes = EpubExporter.export(
            OdfDocument.TextDocument(
                title = "T",
                content = listOf(
                    OdfContentBlock.Paragraph(OdfParagraph(listOf(OdfSpan("hi")))),
                    OdfContentBlock.Image(
                        OdfImage(
                            path = "p.png",
                            imageData = byteArrayOf(1, 2, 3),
                        )
                    ),
                ),
            )
        )
        val names = LinkedHashSet<String>()
        java.util.zip.ZipInputStream(java.io.ByteArrayInputStream(bytes)).use { zis ->
            var e = zis.nextEntry
            while (e != null) {
                names.add(e.name)
                zis.closeEntry()
                e = zis.nextEntry
            }
        }
        assertTrue(names.any { it.endsWith(".xhtml") }, names.toString())
        assertTrue(names.any { it.contains("images/") }, names.toString())
    }

    @Test
    fun `html export covers styled spans tables and images`() {
        val html = HtmlOdfExport.odfToHtml(
            OdfDocument.TextDocument(
                title = "T",
                content = listOf(
                    OdfContentBlock.Paragraph(
                        OdfParagraph(
                            listOf(
                                OdfSpan("b", bold = true),
                                OdfSpan("i", italic = true, color = 0xFFFF0000),
                                OdfSpan("link", href = "https://example.com"),
                            ),
                            alignment = androidx.compose.ui.text.style.TextAlign.Center,
                        )
                    ),
                    OdfContentBlock.Table(
                        OdfTable(
                            rows = listOf(
                                OdfTableRow(
                                    listOf(
                                        OdfTableCell(
                                            listOf(OdfParagraph(listOf(OdfSpan("c"))))
                                        )
                                    )
                                )
                            )
                        )
                    ),
                ),
            )
        )
        assertTrue(html.contains("<strong>b</strong>"), html.take(2000))
        assertTrue(html.contains("<em>"), html.take(2000))
        assertTrue(html.contains("color:#FF0000"), html)
        assertTrue(html.contains("https://example.com"), html)
        assertTrue(html.contains("<table"), html)
    }
}
