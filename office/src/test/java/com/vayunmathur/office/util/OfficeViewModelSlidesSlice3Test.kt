package com.vayunmathur.office.util

import com.vayunmathur.library.ui.odf.ChartType
import com.vayunmathur.library.ui.odf.OdfChart
import com.vayunmathur.library.ui.odf.OdfChartSeries
import com.vayunmathur.library.ui.odf.OdfContentBlock
import com.vayunmathur.library.ui.odf.OdfDocument
import com.vayunmathur.library.ui.odf.OdfFrame
import com.vayunmathur.library.ui.odf.OdfParagraph
import com.vayunmathur.library.ui.odf.OdfShape
import com.vayunmathur.library.ui.odf.OdfSlide
import com.vayunmathur.library.ui.odf.OdfSlideElement
import com.vayunmathur.library.ui.odf.OdfSpan
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

/**
 * Slice 3/4: presentation editing ops on [OfficeViewModel] — slides, elements,
 * notes/chrome, rotation, colors and bounds.
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class OfficeViewModelSlidesSlice3Test : OfficeViewModelSlice3Harness() {

    private fun frame(text: String) = OdfSlideElement.Frame(
        OdfFrame(x = 10f, y = 10f, width = 100f, height = 50f,
            paragraphs = listOf(OdfParagraph(listOf(OdfSpan(text))))))

    private fun slideDoc() = OdfDocument.Presentation(
        title = "Deck",
        slides = listOf(
            OdfSlide(name = "Slide 1", elements = listOf(frame("one"))),
            OdfSlide(name = "Slide 2", elements = listOf(frame("two"))),
        ),
    )

    private fun loaded(): OfficeViewModel {
        val vm = newVm()
        vm.stateMutable.value = OfficeViewModel.ViewState.Loaded(slideDoc())
        return vm
    }

    private fun docOf(vm: OfficeViewModel): OdfDocument.Presentation =
        (vm.state.value as OfficeViewModel.ViewState.Loaded).document as OdfDocument.Presentation

    private fun barChart() = OdfChart(
        type = ChartType.BAR,
        categories = listOf("a"),
        series = listOf(OdfChartSeries("s", listOf(1f))),
    )

    @Test
    fun `add delete duplicate move slides`() = runTest {
        val vm = loaded()
        vm.addSlide(0)
        assertEquals(3, docOf(vm).slides.size)
        assertEquals("Slide 3", docOf(vm).slides[1].name)
        vm.duplicateSlide(0)
        assertTrue(docOf(vm).slides[1].name.endsWith("(copy)"))
        vm.moveSlideUp(1)
        assertTrue(docOf(vm).slides[0].name.endsWith("(copy)"))
        vm.moveSlideDown(0)
        assertTrue(docOf(vm).slides[1].name.endsWith("(copy)"))
        // Boundary moves are no-ops.
        vm.moveSlideUp(0)
        vm.moveSlideDown(docOf(vm).slides.size - 1)
        vm.deleteSlide(0)
        assertEquals(3, docOf(vm).slides.size)
        // Sole slide is protected.
        val single = newVm()
        single.stateMutable.value = OfficeViewModel.ViewState.Loaded(
            OdfDocument.Presentation(title = "D", slides = listOf(
                OdfSlide(name = "Only", elements = emptyList()))))
        single.deleteSlide(0)
        assertEquals(1, ((single.state.value as OfficeViewModel.ViewState.Loaded)
            .document as OdfDocument.Presentation).slides.size)
    }

    @Test
    fun `updateSlideElementText for frame and shape`() = runTest {
        val vm = loaded()
        vm.updateSlideElementText(0, 0, "new\nlines")
        val paras = (docOf(vm).slides[0].elements[0] as OdfSlideElement.Frame).frame.paragraphs
        assertEquals(listOf("new", "lines"), paras.map { it.spans.joinToString("") { s -> s.text } })
        // Shape element path.
        vm.addShapeToSlide(0, "rect")
        vm.updateSlideElementText(0, 1, "shaped")
        val shape = (docOf(vm).slides[0].elements[1] as OdfSlideElement.Shape).shape
        assertEquals("shaped", shape.text.joinToString("") { p -> p.spans.joinToString("") { it.text } })
    }

    @Test
    fun `addTextBox addShape insertImage insertChart`() = runTest {
        val vm = loaded()
        vm.addTextBoxToSlide(0)
        vm.addShapeToSlide(0, "ellipse")
        vm.addShapeToSlide(0, "line")
        vm.addShapeToSlide(0, "bogus-kind-defaults-to-rect")
        vm.insertImageIntoSlide(0, "s.png", byteArrayOf(1))
        vm.insertChartIntoSlide(0, barChart())
        val els = docOf(vm).slides[0].elements
        assertEquals(7, els.size)
        assertTrue(els[5] is OdfSlideElement.Frame)
        val imgFrame = (els[5] as OdfSlideElement.Frame).frame
        assertEquals("Pictures/s.png", imgFrame.image!!.path)
        assertTrue(docOf(vm).images.containsKey("Pictures/s.png"))
        assertTrue((els[6] as OdfSlideElement.Frame).frame.chart != null)
        assertTrue(els[2] is OdfSlideElement.Shape)
    }

    @Test
    fun `slide notes background transition`() = runTest {
        val vm = loaded()
        vm.setSlideNotes(0, "line1\nline2")
        assertEquals("line1\nline2", vm.slideNotesText(0))
        vm.setSlideNotes(0, "  ")
        assertEquals("", vm.slideNotesText(0))
        assertEquals("", vm.slideNotesText(9))
        vm.setSlideBackgroundColor(0, 0xFFFF0000L)
        assertEquals(0xFFFF0000L, docOf(vm).slides[0].backgroundColor)
        vm.setSlideBackgroundColor(0, null)
        assertEquals(null, docOf(vm).slides[0].backgroundColor)
        vm.setSlideTransition(0, "fade", "fast")
        assertEquals("fade", docOf(vm).slides[0].transitionType)
        assertEquals("fast", docOf(vm).slides[0].transitionSpeed)
    }

    @Test
    fun `slide element text style toggles`() = runTest {
        val vm = loaded()
        vm.toggleSlideElementBold(0, 0)
        vm.toggleSlideElementItalic(0, 0)
        vm.toggleSlideElementUnderline(0, 0)
        vm.setSlideElementColor(0, 0, 0xFF00FF00L)
        val span = (docOf(vm).slides[0].elements[0] as OdfSlideElement.Frame)
            .frame.paragraphs[0].spans[0]
        assertTrue(span.bold)
        assertTrue(span.italic)
        assertTrue(span.underline)
        assertEquals(0xFF00FF00L, span.color)
        vm.toggleSlideElementBold(0, 0)
        assertFalse((docOf(vm).slides[0].elements[0] as OdfSlideElement.Frame)
            .frame.paragraphs[0].spans[0].bold)
        vm.setSlideElementAlignment(0, 0, androidx.compose.ui.text.style.TextAlign.Center)
        assertEquals(androidx.compose.ui.text.style.TextAlign.Center,
            (docOf(vm).slides[0].elements[0] as OdfSlideElement.Frame)
                .frame.paragraphs[0].alignment)
    }

    @Test
    fun `element bounds move and delete duplicate reorder`() = runTest {
        val vm = loaded()
        vm.addTextBoxToSlide(0)
        vm.setSlideElementBounds(0, 1, 5f, 6f, 70f, 80f)
        val b = (docOf(vm).slides[0].elements[1] as OdfSlideElement.Frame).frame
        assertEquals(5f, b.x)
        assertEquals(70f, b.width)
        vm.duplicateSlideElement(0, 1)
        assertEquals(3, docOf(vm).slides[0].elements.size)
        val dup = (docOf(vm).slides[0].elements[2] as OdfSlideElement.Frame).frame
        assertEquals(25f, dup.x)
        assertEquals(26f, dup.y)
        // Reorder to front moves the original first element last.
        vm.reorderSlideElement(0, 0, toFront = true)
        val moved = docOf(vm).slides[0].elements.last() as OdfSlideElement.Frame
        assertEquals("one", moved.frame.paragraphs[0].spans[0].text)
        vm.deleteSlideElement(0, 0)
        assertEquals(2, docOf(vm).slides[0].elements.size)
        // Out-of-range delete is a no-op.
        vm.deleteSlideElement(0, 9)
        assertEquals(2, docOf(vm).slides[0].elements.size)
    }

    @Test
    fun `rotation for shapes and image frames`() = runTest {
        val vm = loaded()
        vm.addShapeToSlide(0, "rect")
        vm.setSlideElementRotation(0, 1, 30f)
        assertEquals(30f, (docOf(vm).slides[0].elements[1] as OdfSlideElement.Shape).shape.rotationDegrees)
        // Frame without an image: rotation is a no-op that keeps the element.
        vm.setSlideElementRotation(0, 0, 15f)
        assertTrue(docOf(vm).slides[0].elements[0] is OdfSlideElement.Frame)
        // Frame with an image rotates the image.
        vm.insertImageIntoSlide(0, "r.png", byteArrayOf(2))
        vm.setSlideElementRotation(0, 2, 45f)
        assertEquals(45f, (docOf(vm).slides[0].elements[2] as OdfSlideElement.Frame).frame.image!!.rotationDegrees)
        // Legacy rotateSlideImage path.
        vm.rotateSlideImage(0, 2, 45f)
        assertEquals(90f, (docOf(vm).slides[0].elements[2] as OdfSlideElement.Frame).frame.image!!.rotationDegrees)
    }

    @Test
    fun `slide image crop fill stroke recolor`() = runTest {
        val vm = loaded()
        vm.insertImageIntoSlide(0, "c.png", byteArrayOf(3))
        vm.setSlideImageCrop(0, 1, 0.1f, 0.2f, 0.3f, 0.4f)
        assertEquals(0.1f, (docOf(vm).slides[0].elements[1] as OdfSlideElement.Frame).frame.image!!.cropLeftPct)
        vm.setSlideElementFill(0, 1, 0xFFFF0000L)
        vm.setSlideElementStroke(0, 1, 0xFF00FF00L)
        val frame = (docOf(vm).slides[0].elements[1] as OdfSlideElement.Frame).frame
        assertEquals(0xFFFF0000L, frame.fillColor)
        assertEquals(0xFF00FF00L, frame.strokeColor)
        // Shape recolor across all shape kinds.
        vm.addShapeToSlide(0, "ellipse")
        vm.setSlideElementFill(0, 2, 0xFF0000FFL)
        assertEquals(0xFF0000FFL, ((docOf(vm).slides[0].elements[2] as OdfSlideElement.Shape).shape as OdfShape.Ellipse).fillColor)
        // Crop on a non-image frame leaves the element unchanged.
        val before = docOf(vm).slides[0].elements[0]
        vm.setSlideImageCrop(0, 0, 0.5f, 0.5f, 0.5f, 0.5f)
        assertEquals(before, docOf(vm).slides[0].elements[0])
        // Replace bytes resets crop/rotation and registers the new path.
        vm.replaceSlideImage(0, 1, "d.png", byteArrayOf(4))
        val replaced = (docOf(vm).slides[0].elements[1] as OdfSlideElement.Frame).frame.image!!
        assertEquals("Pictures/d.png", replaced.path)
        assertEquals(0f, replaced.rotationDegrees)
        assertTrue(docOf(vm).images.containsKey("Pictures/d.png"))
    }

    @Test
    fun `createNewPresentation builds title slide`() = runTest {
        val vm = idleVm()
        vm.createNewPresentation()
        val doc = (vm.state.value as OfficeViewModel.ViewState.Loaded)
            .document as OdfDocument.Presentation
        assertEquals("Untitled Presentation", doc.title)
        assertEquals(1, doc.slides.size)
        assertTrue(vm.isEditMode.value)
        assertTrue(vm.hasUnsavedChanges.value)
    }

    @Test
    fun `exportCsv quotes and delimits`() = runTest {
        val vm = newVm()
        vm.stateMutable.value = OfficeViewModel.ViewState.Loaded(
            OdfDocument.Spreadsheet(title = "B", sheets = listOf(
                com.vayunmathur.library.ui.odf.OdfSheet("S", listOf(
                    com.vayunmathur.library.ui.odf.OdfRow(listOf(
                        com.vayunmathur.library.ui.odf.OdfCell("a,b"),
                        com.vayunmathur.library.ui.odf.OdfCell("q\"q"),
                    )),
                    com.vayunmathur.library.ui.odf.OdfRow(listOf(
                        com.vayunmathur.library.ui.odf.OdfCell("plain"),
                        com.vayunmathur.library.ui.odf.OdfCell("multi\nline"),
                    )),
                )))))
        val csv = vm.exportCsv()
        assertTrue(csv.contains("\"a,b\""))
        assertTrue(csv.contains("\"q\"\"q\""))
        assertTrue(csv.contains("\"multi\nline\""))
        val semi = vm.exportCsv(';')
        assertTrue(semi.contains(";"))
        // Non-spreadsheet doc exports empty.
        val text = newVm()
        text.stateMutable.value = OfficeViewModel.ViewState.Loaded(
            OdfDocument.TextDocument(title = "T", content = emptyList()))
        assertEquals("", text.exportCsv())
        val empty = newVm()
        assertEquals("", empty.exportCsv())
    }

    @Test
    fun `exportAsPlainText covers all doc kinds`() = runTest {
        val text = newVm()
        text.stateMutable.value = OfficeViewModel.ViewState.Loaded(
            OdfDocument.TextDocument(title = "T", content = listOf(
                OdfContentBlock.Paragraph(OdfParagraph(listOf(OdfSpan("hi")))),
                OdfContentBlock.PageBreak,
                OdfContentBlock.Image(com.vayunmathur.library.ui.odf.OdfImage(
                    path = "p", imageData = byteArrayOf())),
                OdfContentBlock.TableOfContents("TOC", listOf(OdfParagraph(listOf(OdfSpan("e"))))),
            )))
        val plain = text.exportAsPlainText()
        assertTrue(plain.contains("hi"))
        assertTrue(plain.contains("---"))
        assertTrue(plain.contains("[Image]"))
        assertTrue(plain.contains("TOC"))
        val pres = loaded()
        assertTrue(pres.exportAsPlainText().contains("Slide 1"))
        assertTrue(pres.exportFlat().isNotEmpty())
        assertEquals("pptx", pres.ooxmlExtension())
        val empty = newVm()
        assertEquals("", empty.exportAsPlainText())
        assertEquals("", empty.exportFlat())
    }

    @Test
    fun `exportMarkdown html rtf latex epub pdf ooxml`() = runTest {
        val vm = newVm()
        vm.stateMutable.value = OfficeViewModel.ViewState.Loaded(
            OdfDocument.TextDocument(title = "T", content = listOf(
                OdfContentBlock.Paragraph(OdfParagraph(listOf(OdfSpan("hello")))))))
        assertTrue(vm.exportMarkdown().contains("hello"))
        assertTrue(vm.exportHtml().contains("hello"))
        assertTrue(vm.exportRtf().isNotEmpty())
        assertTrue(vm.exportLatex().isNotEmpty())
        assertTrue(vm.exportEpub().isNotEmpty())
        assertTrue(vm.exportPdf().isNotEmpty())
        assertTrue(vm.exportOoxml().isNotEmpty())
        val sheetVm = newVm()
        sheetVm.stateMutable.value = OfficeViewModel.ViewState.Loaded(
            OdfDocument.Spreadsheet(title = "B", sheets = listOf(
                com.vayunmathur.library.ui.odf.OdfSheet("S", listOf(
                    com.vayunmathur.library.ui.odf.OdfRow(listOf(
                        com.vayunmathur.library.ui.odf.OdfCell("x"))))))))
        assertEquals("xlsx", sheetVm.ooxmlExtension())
        assertEquals("", sheetVm.exportMarkdown())
        assertEquals("", sheetVm.exportHtml())
        val empty = newVm()
        assertEquals("", empty.exportMarkdown())
        assertEquals(0, empty.exportOoxml().size)
    }
}
